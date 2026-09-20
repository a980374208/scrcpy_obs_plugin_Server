package com.genymobile.scrcpy.audio;

import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.util.Ln;

import android.media.MediaCodec;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public class AudioRecorderLifecycleTest {

    private static final long TIMEOUT_MS = 1000;

    private static final class BlockingCapture implements AudioCapture {
        private final String name;
        private final List<String> events;
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicInteger stopCount = new AtomicInteger();

        BlockingCapture(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public void checkCompatibility() {
            // compatible
        }

        @Override
        public void start() {
            events.add(name + ":capture-start");
        }

        @Override
        public void stop() {
            if (stopCount.incrementAndGet() == 1) {
                events.add(name + ":capture-stop");
                stopped.countDown();
            }
        }

        @Override
        public int read(ByteBuffer outDirectBuffer, MediaCodec.BufferInfo outBufferInfo) {
            events.add(name + ":read-enter");
            readEntered.countDown();
            try {
                stopped.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            events.add(name + ":read-exit");
            return -1;
        }
    }

    private static final class CaptureRecorder implements AsyncProcessor {
        private final String name;
        private final SwitchingAudioCapture capture;
        private final List<String> events;
        private final AtomicInteger writers;
        private final AtomicInteger maxWriters;

        private Thread thread;

        CaptureRecorder(String name, SwitchingAudioCapture capture, List<String> events, AtomicInteger writers, AtomicInteger maxWriters) {
            this.name = name;
            this.capture = capture;
            this.events = events;
            this.writers = writers;
            this.maxWriters = maxWriters;
        }

        @Override
        public void start(TerminationListener listener) {
            thread = new Thread(() -> {
                try {
                    capture.start();
                    int count = writers.incrementAndGet();
                    maxWriters.accumulateAndGet(count, Math::max);
                    events.add(name + ":writer-start");
                    capture.read(ByteBuffer.allocateDirect(1), null);
                } catch (AudioCaptureException e) {
                    throw new AssertionError(e);
                } finally {
                    capture.stop();
                    if (writers.get() > 0) {
                        writers.decrementAndGet();
                    }
                    events.add(name + ":writer-stop");
                    listener.onTerminated(false);
                }
            }, name);
            thread.start();
        }

        @Override
        public void stop() {
            events.add(name + ":recorder-stop");
            capture.stop();
        }

        @Override
        public void join() throws InterruptedException {
            if (thread != null) {
                thread.join();
            }
            events.add(name + ":recorder-join");
        }
    }

    private static final class ControlledRecorder implements AsyncProcessor {
        private final String name;
        private final List<String> events;
        private final AtomicInteger writers;
        private final AtomicInteger maxWriters;
        private final boolean terminateOnStart;
        private final boolean delayCallback;
        private final CountDownLatch exitAllowed;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch stopRequested = new CountDownLatch(1);
        private final CountDownLatch callbackAllowed = new CountDownLatch(1);
        private final AtomicBoolean stopOnce = new AtomicBoolean();

        private Thread thread;

        ControlledRecorder(String name, List<String> events, AtomicInteger writers, AtomicInteger maxWriters, boolean terminateOnStart,
                boolean delayCallback, CountDownLatch exitAllowed) {
            this.name = name;
            this.events = events;
            this.writers = writers;
            this.maxWriters = maxWriters;
            this.terminateOnStart = terminateOnStart;
            this.delayCallback = delayCallback;
            this.exitAllowed = exitAllowed;
        }

        @Override
        public void start(TerminationListener listener) {
            thread = new Thread(() -> {
                events.add(name + ":start");
                started.countDown();
                if (terminateOnStart) {
                    events.add(name + ":startup-failed");
                    listener.onTerminated(false);
                    return;
                }
                int count = writers.incrementAndGet();
                maxWriters.accumulateAndGet(count, Math::max);
                try {
                    stopRequested.await();
                    if (exitAllowed != null) {
                        exitAllowed.await();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    writers.decrementAndGet();
                    events.add(name + ":exit");
                }
                if (delayCallback) {
                    Thread callbackThread = new Thread(() -> {
                        try {
                            callbackAllowed.await();
                            events.add(name + ":late-callback");
                            listener.onTerminated(false);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }, name + "-callback");
                    callbackThread.setDaemon(true);
                    callbackThread.start();
                } else {
                    listener.onTerminated(false);
                }
            }, name);
            thread.start();
        }

        @Override
        public void stop() {
            if (stopOnce.compareAndSet(false, true)) {
                events.add(name + ":stop");
                stopRequested.countDown();
            }
        }

        @Override
        public void join() throws InterruptedException {
            if (thread != null) {
                thread.join();
            }
            events.add(name + ":join");
        }
    }

    @Before
    public void disableAndroidInfoLogs() {
        Ln.initLogLevel(Ln.Level.ERROR);
    }

    @Test(timeout = 3000)
    public void testSourceSwitchStopsAndJoinsOldGenerationBeforeNewStart() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<BlockingCapture> captures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger writers = new AtomicInteger();
        AtomicInteger maxWriters = new AtomicInteger();
        SwitchingAudioCapture capture = new SwitchingAudioCapture(AudioSource.OUTPUT, source -> {
            BlockingCapture delegate = new BlockingCapture(source.name(), events);
            captures.add(delegate);
            return delegate;
        });
        AtomicInteger recorderIds = new AtomicInteger();
        AudioRecorderLifecycle lifecycle = new AudioRecorderLifecycle(true, AudioSource.OUTPUT, true, source -> {
            capture.switchSource(source);
            return new CaptureRecorder("recorder-" + recorderIds.incrementAndGet(), capture, events, writers, maxWriters);
        });

        lifecycle.start(fatalError -> events.add("lifecycle-end:" + fatalError));
        await(() -> captures.size() == 1 && captures.get(0).readEntered.getCount() == 0, "first generation did not enter read");
        lifecycle.requestStart(AudioSource.MIC);
        await(() -> captures.size() == 2 && captures.get(1).readEntered.getCount() == 0, "second generation did not enter read");

        Assert.assertTrue(indexOf(events, "recorder-1:recorder-join") < indexOf(events, "MIC:capture-start"));
        Assert.assertEquals(1, maxWriters.get());
        Assert.assertEquals("old recorder finally stopped the new capture", 0, captures.get(1).stopCount.get());
        stopAndJoin(lifecycle);
    }

    @Test(timeout = 3000)
    public void testRapidStopStartStopLeavesStoppedStateAndOneWriterMaximum() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<ControlledRecorder> recorders = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger writers = new AtomicInteger();
        AtomicInteger maxWriters = new AtomicInteger();
        AudioRecorderLifecycle lifecycle = new AudioRecorderLifecycle(true, AudioSource.OUTPUT, true, source -> {
            ControlledRecorder recorder = new ControlledRecorder(source.name() + "-" + (recorders.size() + 1), events, writers, maxWriters, false,
                    false, null);
            recorders.add(recorder);
            return recorder;
        });

        lifecycle.start(fatalError -> events.add("lifecycle-end:" + fatalError));
        await(() -> writers.get() == 1, "initial writer did not start");
        lifecycle.requestStop();
        lifecycle.requestStart(AudioSource.MIC);
        lifecycle.requestStop();
        await(() -> writers.get() == 0 && lifecycle.getState() == AudioRecorderLifecycle.State.STOPPED, "lifecycle did not settle stopped");

        Assert.assertEquals(1, maxWriters.get());
        stopAndJoin(lifecycle);
    }

    @Test(timeout = 3000)
    public void testFailedGenerationCanBeStartedAgainWithoutLostOwner() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<ControlledRecorder> recorders = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger writers = new AtomicInteger();
        AtomicInteger maxWriters = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        AudioRecorderLifecycle lifecycle = new AudioRecorderLifecycle(true, AudioSource.OUTPUT, true, source -> {
            int attempt = attempts.incrementAndGet();
            ControlledRecorder recorder = new ControlledRecorder("attempt-" + attempt, events, writers, maxWriters, attempt == 1, false, null);
            recorders.add(recorder);
            return recorder;
        });

        lifecycle.start(fatalError -> events.add("lifecycle-end:" + fatalError));
        await(() -> lifecycle.getState() == AudioRecorderLifecycle.State.FAILED, "failed generation was not retired");
        lifecycle.requestStart(AudioSource.OUTPUT);
        await(() -> writers.get() == 1 && lifecycle.getState() == AudioRecorderLifecycle.State.RUNNING, "retry generation did not start");

        Assert.assertEquals(2, attempts.get());
        Assert.assertTrue(events.contains("attempt-1:join"));
        Assert.assertEquals(1, maxWriters.get());
        stopAndJoin(lifecycle);
    }

    @Test(timeout = 3000)
    public void testLateOldCallbackCannotReplaceNewGenerationState() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<ControlledRecorder> recorders = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger writers = new AtomicInteger();
        AtomicInteger maxWriters = new AtomicInteger();
        AudioRecorderLifecycle lifecycle = new AudioRecorderLifecycle(true, AudioSource.OUTPUT, true, source -> {
            ControlledRecorder recorder = new ControlledRecorder(source.name(), events, writers, maxWriters, false, recorders.isEmpty(), null);
            recorders.add(recorder);
            return recorder;
        });

        lifecycle.start(fatalError -> events.add("lifecycle-end:" + fatalError));
        await(() -> recorders.size() == 1 && writers.get() == 1, "old generation did not start");
        lifecycle.requestStart(AudioSource.MIC);
        await(() -> recorders.size() == 2 && writers.get() == 1 && lifecycle.getState() == AudioRecorderLifecycle.State.RUNNING,
                "new generation did not start");

        recorders.get(0).callbackAllowed.countDown();
        await(() -> events.contains("OUTPUT:late-callback"), "old callback was not delivered");
        Assert.assertEquals(AudioRecorderLifecycle.State.RUNNING, lifecycle.getState());
        Assert.assertEquals(1, writers.get());
        Assert.assertEquals(1, maxWriters.get());
        stopAndJoin(lifecycle);
    }

    @Test(timeout = 3000)
    public void testShutdownWaitsForRetiringGeneration() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger writers = new AtomicInteger();
        AtomicInteger maxWriters = new AtomicInteger();
        CountDownLatch exitAllowed = new CountDownLatch(1);
        AtomicReference<ControlledRecorder> recorderRef = new AtomicReference<>();
        AudioRecorderLifecycle lifecycle = new AudioRecorderLifecycle(true, AudioSource.OUTPUT, true, source -> {
            ControlledRecorder recorder = new ControlledRecorder("retiring", events, writers, maxWriters, false, false, exitAllowed);
            recorderRef.set(recorder);
            return recorder;
        });

        lifecycle.start(fatalError -> events.add("lifecycle-end:" + fatalError));
        await(() -> writers.get() == 1, "writer did not start");
        lifecycle.requestStop();
        Assert.assertTrue(recorderRef.get().stopRequested.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        lifecycle.stop();

        Thread joiner = new Thread(() -> {
            try {
                lifecycle.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "lifecycle-join-test");
        joiner.start();
        joiner.join(100);
        Assert.assertTrue("shutdown returned before the retiring generation exited", joiner.isAlive());

        exitAllowed.countDown();
        joiner.join(TIMEOUT_MS);
        Assert.assertFalse("shutdown did not finish after the retiring generation exited", joiner.isAlive());
        Assert.assertTrue(events.contains("retiring:join"));
        Assert.assertEquals(AudioRecorderLifecycle.State.TERMINATED, lifecycle.getState());
    }

    private static int indexOf(List<String> events, String event) {
        int index = events.indexOf(event);
        Assert.assertTrue("missing event: " + event + " in " + events, index >= 0);
        return index;
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        Assert.assertTrue(message, condition.getAsBoolean());
    }

    private static void stopAndJoin(AudioRecorderLifecycle lifecycle) throws InterruptedException {
        lifecycle.stop();
        Thread joiner = new Thread(() -> {
            try {
                lifecycle.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "lifecycle-stop-join-test");
        joiner.start();
        joiner.join(TIMEOUT_MS);
        Assert.assertFalse("audio lifecycle did not terminate", joiner.isAlive());
    }
}
