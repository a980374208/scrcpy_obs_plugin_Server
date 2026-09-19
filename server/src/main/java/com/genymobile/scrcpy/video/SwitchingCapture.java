package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.device.ConfigurationException;
import com.genymobile.scrcpy.device.NewDisplay;
import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.util.Ln;

import android.view.Surface;

import java.io.IOException;
import java.util.Objects;

public class SwitchingCapture extends SurfaceCapture {

    interface DelegateFactory {
        SurfaceCapture create(VideoSource source);
    }

    private static final class CaptureConfig {
        private final VideoSource source;
        private final int displayId;
        private final int maxSize;
        private final float maxFps;
        private final String cameraId;
        private final Size cameraSize;
        private final int cameraFps;

        CaptureConfig(VideoSource source, int displayId, int maxSize, float maxFps, String cameraId, Size cameraSize, int cameraFps) {
            this.source = source;
            this.displayId = displayId;
            this.maxSize = maxSize;
            this.maxFps = maxFps;
            this.cameraId = cameraId;
            this.cameraSize = cameraSize;
            this.cameraFps = cameraFps;
        }

        static CaptureConfig fromOptions(Options options) {
            return new CaptureConfig(options.getVideoSource(), options.getDisplayId(), options.getMaxSize(), options.getMaxFps(),
                    options.getCameraId(), options.getCameraSize(), options.getCameraFps());
        }

        void applyTo(Options options) {
            options.setVideoSource(source);
            options.setDisplayId(displayId);
            options.setMaxSize(maxSize);
            options.setMaxFps(maxFps);
            options.setCameraId(cameraId);
            options.setCameraSize(cameraSize);
            options.setCameraFps(cameraFps);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CaptureConfig)) {
                return false;
            }
            CaptureConfig other = (CaptureConfig) o;
            return source == other.source
                    && displayId == other.displayId
                    && maxSize == other.maxSize
                    && Float.compare(maxFps, other.maxFps) == 0
                    && Objects.equals(cameraId, other.cameraId)
                    && Objects.equals(cameraSize, other.cameraSize)
                    && cameraFps == other.cameraFps;
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, displayId, maxSize, maxFps, cameraId, cameraSize, cameraFps);
        }
    }

    private final Controller controller;
    private final Options options;
    private final DelegateFactory delegateFactory;

    // Only the video encoder thread may access the delegate after construction.
    private SurfaceCapture delegate;

    // These fields are shared with the controller thread and are guarded by this.
    private CaptureConfig activeConfig;
    private CaptureConfig pendingConfig;
    private CaptureConfig applyingConfig;
    private boolean initialized;

    public SwitchingCapture(Controller controller, Options options) {
        this(controller, options, source -> createProductionDelegate(controller, options, source));
    }

    SwitchingCapture(Controller controller, Options options, DelegateFactory delegateFactory) {
        this.controller = controller;
        this.options = options;
        this.delegateFactory = delegateFactory;
        activeConfig = CaptureConfig.fromOptions(options);
        delegate = delegateFactory.create(activeConfig.source);
    }

    private static SurfaceCapture createProductionDelegate(Controller controller, Options options, VideoSource source) {
        if (source == VideoSource.DISPLAY) {
            NewDisplay newDisplay = options.getNewDisplay();
            if (newDisplay != null) {
                return new NewDisplayCapture(controller, options);
            }
            return new ScreenCapture(controller, options);
        }
        return new CameraCapture(options);
    }

    public synchronized void switchSource(VideoSource newSource, int newDisplayId, int newMaxSize, float newMaxFps, String newCameraId,
            int newCameraWidth, int newCameraHeight, int newCameraFps) {
        Size newCameraSize = (newCameraWidth > 0 && newCameraHeight > 0) ? new Size(newCameraWidth, newCameraHeight) : null;
        CaptureConfig requested = new CaptureConfig(newSource, newDisplayId, newMaxSize, newMaxFps, newCameraId, newCameraSize,
                newCameraFps);

        CaptureConfig targetConfig = pendingConfig != null ? pendingConfig : applyingConfig != null ? applyingConfig : activeConfig;
        if (requested.equals(targetConfig)) {
            return;
        }

        pendingConfig = requested;
        if (initialized) {
            invalidate();
        }
    }

    @Override
    protected void init() throws ConfigurationException, IOException {
        delegate.init(this::invalidate);
        synchronized (this) {
            initialized = true;
        }
    }

    @Override
    public void release() {
        if (delegate != null) {
            delegate.release();
            delegate = null;
        }
    }

    @Override
    public void prepare() throws ConfigurationException, IOException {
        applyPendingSwitch();
        delegate.prepare();
    }

    private void applyPendingSwitch() throws ConfigurationException, IOException {
        CaptureConfig requested;
        CaptureConfig previousConfig;
        synchronized (this) {
            requested = pendingConfig;
            pendingConfig = null;
            if (requested == null || requested.equals(activeConfig)) {
                return;
            }
            applyingConfig = requested;
            previousConfig = activeConfig;
        }

        SurfaceCapture previousDelegate = delegate;
        delegate = null;
        previousDelegate.release();

        requested.applyTo(options);
        SurfaceCapture requestedDelegate = delegateFactory.create(requested.source);
        try {
            requestedDelegate.init(this::invalidate);
        } catch (ConfigurationException | IOException switchError) {
            requestedDelegate.release();
            restorePreviousDelegate(previousConfig, switchError);
            return;
        }

        delegate = requestedDelegate;
        synchronized (this) {
            activeConfig = requested;
            applyingConfig = null;
        }
    }

    private void restorePreviousDelegate(CaptureConfig previousConfig, Exception switchError) throws ConfigurationException, IOException {
        previousConfig.applyTo(options);
        SurfaceCapture restoredDelegate = delegateFactory.create(previousConfig.source);
        try {
            restoredDelegate.init(this::invalidate);
        } catch (ConfigurationException | IOException restoreError) {
            restoredDelegate.release();
            synchronized (this) {
                applyingConfig = null;
            }
            switchError.addSuppressed(restoreError);
            if (switchError instanceof ConfigurationException) {
                throw (ConfigurationException) switchError;
            }
            throw (IOException) switchError;
        }

        delegate = restoredDelegate;
        synchronized (this) {
            applyingConfig = null;
        }
        if (controller != null) {
            Ln.e("Could not switch video source, restored the previous capture", switchError);
        }
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
        boolean shouldInvalidate;
        synchronized (this) {
            shouldInvalidate = initialized && activeConfig.source == VideoSource.DISPLAY;
        }
        if (shouldInvalidate) {
            invalidate();
        }
    }
}
