package com.genymobile.scrcpy.audio;

import android.media.MediaCodec;
import java.nio.ByteBuffer;
import com.genymobile.scrcpy.util.Ln;

public class SwitchingAudioCapture implements AudioCapture {

    interface CaptureFactory {
        AudioCapture create(AudioSource source);
    }

    private final CaptureFactory captureFactory;

    private AudioSource currentSource;
    private AudioCapture currentCapture;
    private boolean started = false;

    public SwitchingAudioCapture(AudioSource initialSource, boolean keepPlayingOnDevice) {
        this(initialSource, source -> source.isDirect() ? new AudioDirectCapture(source) : new AudioPlaybackCapture(keepPlayingOnDevice));
    }

    SwitchingAudioCapture(AudioSource initialSource, CaptureFactory captureFactory) {
        this.currentSource = initialSource;
        this.captureFactory = captureFactory;
        createCapture(initialSource);
    }

    private void createCapture(AudioSource source) {
        currentCapture = captureFactory.create(source);
    }

    public synchronized void switchSource(AudioSource newSource) {
        if (currentSource == newSource) {
            return;
        }
        if (started) {
            throw new IllegalStateException("Audio source may only be switched between recorder generations");
        }
        Ln.i("Switching audio source to: " + newSource);
        currentSource = newSource;
        createCapture(newSource);
    }

    @Override
    public void checkCompatibility() throws AudioCaptureException {
        AudioCapture capture;
        synchronized (this) {
            capture = currentCapture;
        }
        capture.checkCompatibility();
    }

    @Override
    public synchronized void start() throws AudioCaptureException {
        try {
            currentCapture.start();
            started = true;
        } catch (AudioCaptureException | RuntimeException | Error e) {
            currentCapture.stop();
            throw e;
        }
    }

    @Override
    public void stop() {
        AudioCapture capture;
        synchronized (this) {
            if (!started) {
                return;
            }
            started = false;
            capture = currentCapture;
        }
        capture.stop();
    }

    @Override
    public int read(ByteBuffer outDirectBuffer, MediaCodec.BufferInfo outBufferInfo) {
        AudioCapture capture;
        synchronized (this) {
            if (!started) {
                return -1;
            }
            capture = currentCapture;
        }
        return capture.read(outDirectBuffer, outBufferInfo);
    }
}
