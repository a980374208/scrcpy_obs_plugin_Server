package com.genymobile.scrcpy.audio;

import com.genymobile.scrcpy.Options;

import android.media.MediaCodec;

import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SwitchingAudioCaptureTest {

    private static final class BlockingCapture implements AudioCapture {
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicInteger stopCount = new AtomicInteger();

        @Override
        public void checkCompatibility() {
            // compatible
        }

        @Override
        public void start() {
            // started
        }

        @Override
        public void stop() {
            stopCount.incrementAndGet();
            stopped.countDown();
        }

        @Override
        public int read(ByteBuffer outDirectBuffer, MediaCodec.BufferInfo outBufferInfo) {
            readEntered.countDown();
            try {
                if (!stopped.await(1, TimeUnit.SECONDS)) {
                    return 0;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    @Test(timeout = 3000)
    public void testStopUnblocksReadWithoutWaitingForReadMonitor() throws Exception {
        BlockingCapture delegate = new BlockingCapture();
        SwitchingAudioCapture capture = new SwitchingAudioCapture(AudioSource.OUTPUT, source -> delegate);
        capture.start();

        Thread reader = new Thread(() -> capture.read(ByteBuffer.allocateDirect(1), null), "blocking-audio-read-test");
        reader.start();
        Assert.assertTrue("read did not block in the fake capture", delegate.readEntered.await(1, TimeUnit.SECONDS));

        Thread stopper = new Thread(capture::stop, "audio-stop-test");
        stopper.start();
        stopper.join(1000);
        reader.join(1000);

        Assert.assertFalse("stop is blocked by read holding the switching monitor", stopper.isAlive());
        Assert.assertFalse("read did not exit after capture stop", reader.isAlive());
        Assert.assertEquals(1, delegate.stopCount.get());
    }

    @Test
    public void testEncodedAndRawRecordersBothPropagateStopToCapture() {
        BlockingCapture encodedCapture = new BlockingCapture();
        new AudioEncoder(encodedCapture, null, new Options()).stop();
        Assert.assertEquals(1, encodedCapture.stopCount.get());

        BlockingCapture rawCapture = new BlockingCapture();
        new AudioRawRecorder(rawCapture, null).stop();
        Assert.assertEquals(1, rawCapture.stopCount.get());
    }
}
