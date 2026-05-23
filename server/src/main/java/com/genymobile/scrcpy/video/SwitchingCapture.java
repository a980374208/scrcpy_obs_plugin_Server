package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.device.ConfigurationException;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.device.NewDisplay;
import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.util.Ln;

import android.view.Surface;

import java.io.IOException;

public class SwitchingCapture extends SurfaceCapture {

    private final Controller controller;
    private final Options options;

    private SurfaceCapture delegate;
    private VideoSource source;
    private int displayId;
    private String cameraId;

    private boolean initialized;

    public SwitchingCapture(Controller controller, Options options) {
        this.controller = controller;
        this.options = options;
        this.source = options.getVideoSource();
        this.displayId = options.getDisplayId();
        this.cameraId = options.getCameraId();

        createDelegate();
    }

    private void createDelegate() {
        if (source == VideoSource.DISPLAY) {
            NewDisplay newDisplay = options.getNewDisplay();
            if (newDisplay != null) {
                delegate = new NewDisplayCapture(controller, options);
            } else {
                delegate = new ScreenCapture(controller, options);
            }
        } else {
            delegate = new CameraCapture(options);
        }

        if (initialized) {
            try {
                delegate.init(this::invalidate);
            } catch (ConfigurationException | IOException e) {
                Ln.e("Could not initialize new delegate", e);
                // What to do here? If it fails, maybe we should close?
            }
        }
    }

    public synchronized void switchSource(VideoSource newSource, int newDisplayId, String newCameraId) {
        if (source == newSource && displayId == newDisplayId && (cameraId == null ? newCameraId == null : cameraId.equals(newCameraId))) {
            return;
        }

        source = newSource;
        displayId = newDisplayId;
        cameraId = newCameraId;

        // Update options so that the new delegate is created with the new parameters
        // Note: Options should ideally be cloned or modified carefully
        options.setVideoSource(newSource);
        options.setDisplayId(newDisplayId);
        options.setCameraId(newCameraId);

        if (delegate != null) {
            delegate.release();
        }
        createDelegate();
        invalidate();
    }

    @Override
    protected void init() throws ConfigurationException, IOException {
        initialized = true;
        delegate.init(this::invalidate);
    }

    @Override
    public void release() {
        delegate.release();
    }

    @Override
    public void prepare() throws ConfigurationException, IOException {
        delegate.prepare();
    }

    @Override
    public void start(Surface surface) throws IOException {
        delegate.start(surface);
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public Size getSize() {
        return delegate.getSize();
    }

    @Override
    public boolean setMaxSize(int maxSize) {
        return delegate.setMaxSize(maxSize);
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public void requestInvalidate() {
        delegate.requestInvalidate();
    }
}
