package com.genymobile.scrcpy.audio;

import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.util.Ln;

/** Owns every recorder generation which writes to one audio file descriptor. */
public final class AudioRecorderLifecycle implements AsyncProcessor {

    public interface RecorderFactory {
        AsyncProcessor create(AudioSource source) throws Exception;
    }

    enum State {
        NEW,
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        FAILED,
        TERMINATED
    }

    private static final class Generation {
        private final long id;
        private final long requestVersion;
        private final AudioSource source;
        private final AsyncProcessor recorder;

        private boolean terminated;
        private boolean fatalError;

        private Generation(long id, long requestVersion, AudioSource source, AsyncProcessor recorder) {
            this.id = id;
            this.requestVersion = requestVersion;
            this.source = source;
            this.recorder = recorder;
        }
    }

    private final Object lock = new Object();
    private final RecorderFactory recorderFactory;
    private final boolean keepAliveAfterNonFatalFailure;

    private boolean desiredEnabled;
    private AudioSource desiredSource;
    private long requestVersion;
    private long attemptedVersion = -1;
    private long nextGeneration;
    private boolean shutdownRequested;
    private Generation activeGeneration;
    private State state = State.NEW;
    private Thread thread;

    public AudioRecorderLifecycle(boolean initialEnabled, AudioSource initialSource, boolean keepAliveAfterNonFatalFailure,
            RecorderFactory recorderFactory) {
        this.desiredEnabled = initialEnabled;
        this.desiredSource = initialSource;
        this.keepAliveAfterNonFatalFailure = keepAliveAfterNonFatalFailure;
        this.recorderFactory = recorderFactory;
    }

    public void requestStart(AudioSource source) {
        synchronized (lock) {
            if (shutdownRequested) {
                return;
            }
            if (!desiredEnabled || desiredSource != source || activeGeneration == null) {
                desiredEnabled = true;
                desiredSource = source;
                ++requestVersion;
                lock.notifyAll();
            }
        }
    }

    public void requestStop() {
        synchronized (lock) {
            if (desiredEnabled || activeGeneration != null) {
                desiredEnabled = false;
                ++requestVersion;
                lock.notifyAll();
            }
        }
    }

    public void requestSource(AudioSource source) {
        synchronized (lock) {
            if (!shutdownRequested && desiredSource != source) {
                desiredSource = source;
                ++requestVersion;
                lock.notifyAll();
            }
        }
    }

    @Override
    public void start(TerminationListener listener) {
        synchronized (lock) {
            if (thread != null) {
                throw new IllegalStateException("Audio recorder lifecycle already started");
            }
            thread = new Thread(() -> run(listener), "audio-lifecycle");
            thread.start();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            shutdownRequested = true;
            desiredEnabled = false;
            ++requestVersion;
            lock.notifyAll();
        }
    }

    @Override
    public void join() throws InterruptedException {
        Thread threadRef;
        synchronized (lock) {
            threadRef = thread;
        }
        if (threadRef != null) {
            threadRef.join();
        }
    }

    State getState() {
        synchronized (lock) {
            return state;
        }
    }

    private void run(TerminationListener listener) {
        boolean fatalError = false;
        try {
            while (true) {
                Generation generationToRetire = null;
                boolean unexpectedTermination = false;
                long startVersion = -1;
                AudioSource startSource = null;

                synchronized (lock) {
                    while (true) {
                        if (activeGeneration != null && (activeGeneration.terminated || shutdownRequested || !desiredEnabled
                                || activeGeneration.requestVersion != requestVersion || activeGeneration.source != desiredSource)) {
                            generationToRetire = activeGeneration;
                            unexpectedTermination = activeGeneration.terminated && !shutdownRequested && desiredEnabled
                                    && activeGeneration.requestVersion == requestVersion && activeGeneration.source == desiredSource;
                            state = State.STOPPING;
                            break;
                        }
                        if (shutdownRequested) {
                            state = State.TERMINATED;
                            return;
                        }
                        if (activeGeneration == null && desiredEnabled && attemptedVersion != requestVersion) {
                            startVersion = requestVersion;
                            startSource = desiredSource;
                            attemptedVersion = requestVersion;
                            state = State.STARTING;
                            break;
                        }
                        if (activeGeneration == null && !desiredEnabled) {
                            state = State.STOPPED;
                        }
                        lock.wait();
                    }
                }

                if (generationToRetire != null) {
                    retire(generationToRetire);
                    synchronized (lock) {
                        if (activeGeneration == generationToRetire) {
                            activeGeneration = null;
                        }
                        fatalError = generationToRetire.fatalError;
                        if (!fatalError && unexpectedTermination) {
                            state = State.FAILED;
                        }
                    }
                    if (fatalError || (unexpectedTermination && !keepAliveAfterNonFatalFailure)) {
                        return;
                    }
                    continue;
                }

                startGeneration(startVersion, startSource);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (lock) {
                fatalError = !shutdownRequested;
            }
        } finally {
            Generation generation;
            synchronized (lock) {
                generation = activeGeneration;
            }
            if (generation != null) {
                try {
                    retire(generation);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                synchronized (lock) {
                    if (activeGeneration == generation) {
                        activeGeneration = null;
                    }
                }
            }
            synchronized (lock) {
                state = State.TERMINATED;
            }
            listener.onTerminated(fatalError);
        }
    }

    private void startGeneration(long version, AudioSource source) throws InterruptedException {
        AsyncProcessor recorder;
        try {
            recorder = recorderFactory.create(source);
        } catch (Throwable t) {
            Ln.e("Failed to create audio recorder generation", t);
            synchronized (lock) {
                state = State.FAILED;
                if (!keepAliveAfterNonFatalFailure) {
                    shutdownRequested = true;
                }
            }
            return;
        }

        Generation generation;
        boolean stale;
        synchronized (lock) {
            stale = shutdownRequested || !desiredEnabled || requestVersion != version || desiredSource != source;
            if (!stale) {
                generation = new Generation(++nextGeneration, version, source, recorder);
                activeGeneration = generation;
            } else {
                generation = null;
            }
        }
        if (stale) {
            recorder.stop();
            recorder.join();
            return;
        }

        try {
            recorder.start(childFatalError -> onRecorderTerminated(generation, childFatalError));
            synchronized (lock) {
                if (activeGeneration == generation && !generation.terminated) {
                    state = State.RUNNING;
                }
            }
        } catch (Throwable t) {
            Ln.e("Failed to start audio recorder generation " + generation.id, t);
            recorder.stop();
            recorder.join();
            synchronized (lock) {
                if (activeGeneration == generation) {
                    activeGeneration = null;
                }
                state = State.FAILED;
                if (!keepAliveAfterNonFatalFailure) {
                    shutdownRequested = true;
                }
                lock.notifyAll();
            }
        }
    }

    private void onRecorderTerminated(Generation generation, boolean fatalError) {
        synchronized (lock) {
            generation.terminated = true;
            generation.fatalError = fatalError;
            lock.notifyAll();
        }
    }

    private static void retire(Generation generation) throws InterruptedException {
        try {
            generation.recorder.stop();
        } catch (Throwable t) {
            Ln.e("Failed to stop audio recorder generation " + generation.id, t);
        }
        generation.recorder.join();
    }
}
