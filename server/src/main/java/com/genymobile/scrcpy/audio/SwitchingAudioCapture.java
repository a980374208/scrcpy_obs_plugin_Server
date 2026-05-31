package com.genymobile.scrcpy.audio;

import android.media.MediaCodec;
import java.nio.ByteBuffer;
import com.genymobile.scrcpy.util.Ln;

public class SwitchingAudioCapture implements AudioCapture {

    private final boolean keepPlayingOnDevice;

    private AudioSource currentSource;
    private AudioCapture currentCapture;
    private boolean started = false;

    public SwitchingAudioCapture(AudioSource initialSource, boolean keepPlayingOnDevice) {
        this.currentSource = initialSource;
        this.keepPlayingOnDevice = keepPlayingOnDevice;
        createCapture(initialSource);
    }

    private void createCapture(AudioSource source) {
        if (source.isDirect()) {
            this.currentCapture = new AudioDirectCapture(source);
        } else {
            this.currentCapture = new AudioPlaybackCapture(keepPlayingOnDevice);
        }
    }

    public synchronized void switchSource(AudioSource newSource) {
        if (currentSource == newSource) {
            return;
        }
        Ln.i("Switching audio source to: " + newSource);
        if (started) {
            currentCapture.stop();
        }

        currentSource = newSource;
        createCapture(newSource);

        if (started) {
            try {
                currentCapture.checkCompatibility();
                currentCapture.start();
            } catch (AudioCaptureException e) {
                Ln.e("Failed to start new audio capture during switch", e);
            }
        }
    }

    @Override
    public synchronized void checkCompatibility() throws AudioCaptureException {
        currentCapture.checkCompatibility();
    }

    @Override
    public synchronized void start() throws AudioCaptureException {
        currentCapture.start();
        started = true;
    }

    @Override
    public synchronized void stop() {
        currentCapture.stop();
        started = false;
    }

    @Override
    public synchronized int read(ByteBuffer outDirectBuffer, MediaCodec.BufferInfo outBufferInfo) {
        if (!started) {
            return -1;
        }
        return currentCapture.read(outDirectBuffer, outBufferInfo);
    }
}
