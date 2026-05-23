package com.genymobile.scrcpy.util;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.audio.AudioCodec;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.device.DeviceApp;
import com.genymobile.scrcpy.device.DisplayInfo;
import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.video.VideoCodec;
import com.genymobile.scrcpy.wrappers.DisplayManager;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Range;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public final class LogUtils {

    private LogUtils() {
        // not instantiable
    }

    private static String buildEncoderListMessage(String type, Codec[] codecs) {
        StringBuilder builder = new StringBuilder("List of ").append(type).append(" encoders:");
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (Codec codec : codecs) {
            MediaCodecInfo[] encoders = CodecUtils.getEncoders(codecList, codec.getMimeType());
            for (MediaCodecInfo info : encoders) {
                int lineStart = builder.length();
                builder.append("\n    --").append(type).append("-codec=").append(codec.getName());
                builder.append(" --").append(type).append("-encoder=").append(info.getName());
                if (Build.VERSION.SDK_INT >= AndroidVersions.API_29_ANDROID_10) {
                    int lineLength = builder.length() - lineStart;
                    final int column = 70;
                    if (lineLength < column) {
                        int padding = column - lineLength;
                        builder.append(String.format("%" + padding + "s", " "));
                    }
                    builder.append(" (").append(getHwCodecType(info)).append(')');
                    if (info.isVendor()) {
                        builder.append(" [vendor]");
                    }
                    if (info.isAlias()) {
                        builder.append(" (alias for ").append(info.getCanonicalName()).append(')');
                    }
                }

            }
        }

        return builder.toString();
    }

    public static String buildVideoEncoderListMessage() {
        return buildEncoderListMessage("video", VideoCodec.values());
    }

    public static String buildAudioEncoderListMessage() {
        return buildEncoderListMessage("audio", AudioCodec.values());
    }

    @TargetApi(AndroidVersions.API_29_ANDROID_10)
    private static String getHwCodecType(MediaCodecInfo info) {
        if (info.isSoftwareOnly()) {
            return "sw";
        }
        if (info.isHardwareAccelerated()) {
            return "hw";
        }
        return "hybrid";
    }

    public static String buildDisplayListMessage() {
        StringBuilder builder = new StringBuilder("List of displays:");
        DisplayManager displayManager = ServiceManager.getDisplayManager();
        int[] displayIds = displayManager.getDisplayIds();
        if (displayIds == null || displayIds.length == 0) {
            builder.append("\n    (none)");
        } else {
            for (int id : displayIds) {
                builder.append("\n    --display-id=").append(id).append("    (");
                DisplayInfo displayInfo = displayManager.getDisplayInfo(id);
                if (displayInfo != null) {
                    Size size = displayInfo.getSize();
                    builder.append(size.getWidth()).append("x").append(size.getHeight());
                } else {
                    builder.append("size unknown");
                }
                builder.append(")");
            }
        }
        return builder.toString();
    }

    private static String getCameraFacingName(int facing) {
        switch (facing) {
            case CameraCharacteristics.LENS_FACING_FRONT:
                return "front";
            case CameraCharacteristics.LENS_FACING_BACK:
                return "back";
            case CameraCharacteristics.LENS_FACING_EXTERNAL:
                return "external";
            default:
                return "unknown";
        }
    }

    private static boolean isCameraBackwardCompatible(CameraCharacteristics characteristics) {
        int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities == null) {
            return false;
        }

        for (int capability : capabilities) {
            if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) {
                return true;
            }
        }

        return false;
    }
    public static String buildCameraListMessage(boolean includeSizes) {
        StringBuilder builder = new StringBuilder("List of cameras:");
        CameraManager cameraManager = ServiceManager.getCameraManager();
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            if (cameraIds.length == 0) {
                builder.append("\n    (none)");
            } else {
                for (String id : cameraIds) {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);

                    if (!isCameraBackwardCompatible(characteristics)) {
                        continue;
                    }

                    builder.append("\n    --camera-id=").append(id);

                    // --- 优化：判断逻辑/物理摄像头 ---
                    String cameraType = "physical";
                    String physicalDetail = "";

                    if (Build.VERSION.SDK_INT >= AndroidVersions.API_28_ANDROID_9) {
                        Set<String> physicalIds = characteristics.getPhysicalCameraIds();
                        boolean isLogical = false;

                        // 1. 检查官方能力标志
                        int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                        if (capabilities != null) {
                            for (int capability : capabilities) {
                                if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                                    isLogical = true;
                                    break;
                                }
                            }
                        }

                        // 2. 补充判断：如果有物理组件 ID，且组件 ID 里不包含自己，或者包含多个，也视为逻辑摄像头
                        if (!isLogical && physicalIds != null && !physicalIds.isEmpty()) {
                            if (physicalIds.size() > 1 || !physicalIds.contains(id)) {
                                isLogical = true;
                            }
                        }

                        if (isLogical) {
                            cameraType = "logical";
                            if (physicalIds != null && !physicalIds.isEmpty()) {
                                physicalDetail = ", components=" + physicalIds.toString();
                            }
                        }
                    }
                    // ------------------------------

                    int facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    builder.append("    (").append(getCameraFacingName(facing));
                    builder.append(", ").append(cameraType); // 打印类型

                    Rect activeSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    builder.append(", ").append(activeSize.width()).append("x").append(activeSize.height());

                    try {
                        Range<Integer>[] lowFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                        if (lowFpsRanges != null) {
                            SortedSet<Integer> uniqueLowFps = getUniqueSet(lowFpsRanges);
                            builder.append(", fps=").append(uniqueLowFps);
                        }
                    } catch (Exception e) {
                        Ln.w("Could not get available frame rates for camera " + id, e);
                    }

                    builder.append(physicalDetail); // 如果是逻辑摄像头，打印包含的物理组件
                    builder.append(')');

                    if (includeSizes) {
                       StreamConfigurationMap configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                        android.util.Size[] sizes = configs.getOutputSizes(MediaCodec.class);
                        if (sizes == null || sizes.length == 0) {
                            builder.append("\n        (none)");
                        } else {
                            for (android.util.Size size : sizes) {
                                builder.append("\n        - ").append(size.getWidth()).append('x').append(size.getHeight());
                            }
                        }

                        android.util.Size[] highSpeedSizes = configs.getHighSpeedVideoSizes();
                        if (highSpeedSizes != null && highSpeedSizes.length > 0) {
                            builder.append("\n      High speed capture (--camera-high-speed):");
                            for (android.util.Size size : highSpeedSizes) {
                                Range<Integer>[] highFpsRanges = configs.getHighSpeedVideoFpsRanges();
                                SortedSet<Integer> uniqueHighFps = getUniqueSet(highFpsRanges);
                                builder.append("\n        - ").append(size.getWidth()).append("x").append(size.getHeight());
                                builder.append(" (fps=").append(uniqueHighFps).append(')');
                            }
                        }
                    }
                }
            }
        } catch (CameraAccessException e) {
            builder.append("\n    (access denied)");
        }
        return builder.toString();
    }

    private static SortedSet<Integer> getUniqueSet(Range<Integer>[] ranges) {
        SortedSet<Integer> set = new TreeSet<>();
        for (Range<Integer> range : ranges) {
            set.add(range.getUpper());
        }
        return set;
    }


    private static boolean isLogicalCamera(String id, CameraCharacteristics characteristics) {
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_28_ANDROID_9) {
            Set<String> physicalIds = characteristics.getPhysicalCameraIds();
            int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (capabilities != null) {
                for (int capability : capabilities) {
                    if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                        return true;
                    }
                }
            }
            if (physicalIds != null && !physicalIds.isEmpty()) {
                if (physicalIds.size() > 1 || !physicalIds.contains(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static float getDisplayRefreshRate(int displayId) {
        try {
            android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager)
                    com.genymobile.scrcpy.FakeContext.get().getSystemService(android.content.Context.DISPLAY_SERVICE);
            if (dm != null) {
                android.view.Display display = dm.getDisplay(displayId);
                if (display != null) {
                    return display.getRefreshRate();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return 0.0f;
    }

    private static String getDeviceSerial() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            String serial = (String) systemProperties.getMethod("get", String.class).invoke(null, "ro.serialno");
            if (serial != null && !serial.isEmpty()) {
                return serial;
            }
        } catch (Exception e) {
            // ignore
        }

        if (Build.VERSION.SDK_INT >= AndroidVersions.API_26_ANDROID_8_0) {
            try {
                String serial = Build.getSerial();
                if (serial != null && !serial.isEmpty() && !Build.UNKNOWN.equals(serial)) {
                    return serial;
                }
            } catch (SecurityException e) {
                // ignore
            }
        }

        @SuppressWarnings("deprecation")
        String serial = Build.SERIAL;
        return serial != null ? serial : "unknown";
    }

    public static String buildDeviceInfosJson() {
        try {
            JSONObject root = new JSONObject();

            // Device
            String brand = Build.BRAND;
            String model = Build.MODEL;
            String deviceName;
            if (model.toLowerCase().contains(brand.toLowerCase())) {
                deviceName = model;
            } else {
                deviceName = brand + " " + model;
            }
            root.put("device", "[" + Build.MANUFACTURER + "] " + deviceName + " (Android " + Build.VERSION.RELEASE + ")");
            root.put("serial", getDeviceSerial());
            root.put("state", "device");
            root.put("model", model);

            // Display
            JSONArray displays = new JSONArray();
            DisplayManager displayManager = ServiceManager.getDisplayManager();
            int[] displayIds = displayManager.getDisplayIds();
            if (displayIds != null) {
                for (int id : displayIds) {
                    JSONObject display = new JSONObject();
                    display.put("id", id);
                    DisplayInfo displayInfo = displayManager.getDisplayInfo(id);
                    if (displayInfo != null) {
                        Size size = displayInfo.getSize();
                        display.put("size", size.getWidth() + "x" + size.getHeight());
                        float refreshRate = getDisplayRefreshRate(id);
                        if (refreshRate > 0.0f) {
                            display.put("fps", Math.round(refreshRate));
                        } else {
                            display.put("fps", JSONObject.NULL);
                        }
                    } else {
                        display.put("size", "unknown");
                    }
                    displays.put(display);
                }
            }
            root.put("display", displays);

            // Camera
            JSONArray cameras = new JSONArray();
            CameraManager cameraManager = ServiceManager.getCameraManager();
            try {
                String[] cameraIds = cameraManager.getCameraIdList();
                List<String> validCameraIds = new ArrayList<>();
                for (String id : cameraIds) {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                    if (isCameraBackwardCompatible(characteristics)) {
                        validCameraIds.add(id);
                    }
                }

                for (String id : validCameraIds) {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                    JSONObject camera = new JSONObject();
                    camera.put("id", id);

                    int facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    camera.put("facing", getCameraFacingName(facing));

                    String cameraType = isLogicalCamera(id, characteristics) ? "logical" : "physical";
                    camera.put("type", cameraType);

                    // size
                    JSONArray sizesArray = new JSONArray();
                    StreamConfigurationMap configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (configs != null) {
                        android.util.Size[] sizes = configs.getOutputSizes(MediaCodec.class);
                        if (sizes != null) {
                            for (android.util.Size size : sizes) {
                                sizesArray.put(size.getWidth() + "x" + size.getHeight());
                            }
                        }
                    }
                    camera.put("size", sizesArray);

                    // fps
                    JSONArray fpsArray = new JSONArray();
                    try {
                        Range<Integer>[] lowFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                        if (lowFpsRanges != null) {
                            SortedSet<Integer> uniqueLowFps = getUniqueSet(lowFpsRanges);
                            for (int fps : uniqueLowFps) {
                                fpsArray.put(fps);
                            }
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                    camera.put("fps", fpsArray);

                    cameras.put(camera);
                }
            } catch (CameraAccessException e) {
                // ignore
            }
            root.put("camera", cameras);

            return root.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static String buildAppListMessage() {
        List<DeviceApp> apps = Device.listApps();
        return buildAppListMessage("List of apps:", apps);
    }

    @SuppressLint("QueryPermissionsNeeded")
    public static String buildAppListMessage(String title, List<DeviceApp> apps) {
        StringBuilder builder = new StringBuilder(title);

        // Sort by:
        //  1. system flag (system apps are before non-system apps)
        //  2. name
        //  3. package name
        // Comparator.comparing() was introduced in API 24, so it cannot be used here to simplify the code
        Collections.sort(apps, (thisApp, otherApp) -> {
            // System apps first
            int cmp = -Boolean.compare(thisApp.isSystem(), otherApp.isSystem());
            if (cmp != 0) {
                return cmp;
            }

            cmp = Objects.compare(thisApp.getName(), otherApp.getName(), String::compareTo);
            if (cmp != 0) {
                return cmp;
            }

            return Objects.compare(thisApp.getPackageName(), otherApp.getPackageName(), String::compareTo);
        });

        final int column = 30;
        for (DeviceApp app : apps) {
            String name = app.getName();
            int padding = column - name.length();
            builder.append("\n ");
            if (app.isSystem()) {
                builder.append("* ");
            } else {
                builder.append("- ");

            }
            builder.append(name);
            if (padding > 0) {
                builder.append(String.format("%" + padding + "s", " "));
            } else {
                builder.append("\n   ").append(String.format("%" + column + "s", " "));
            }
            builder.append(" ").append(app.getPackageName());
        }

        return builder.toString();
    }
}
