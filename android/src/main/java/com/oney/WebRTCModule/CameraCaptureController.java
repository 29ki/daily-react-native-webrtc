package com.oney.WebRTCModule;

import android.util.Log;

import com.facebook.react.bridge.ReadableMap;

import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.VideoCapturer;

import java.util.ArrayList;
import java.util.List;

public class CameraCaptureController extends AbstractVideoCaptureController {
    /**
     * The {@link Log} tag with which {@code CameraCaptureController} is to log.
     */
    private static final String TAG
        = CameraCaptureController.class.getSimpleName();

    private boolean isFrontFacing;
    private boolean isCapturing = false;
    private String facingModeWhenStoppedCapturing = null;

    private final CameraEnumerator cameraEnumerator;
    private final ReadableMap constraints;

    /**
     * The {@link CameraEventsHandler} used with
     * {@link CameraEnumerator#createCapturer}. Cached because the
     * implementation does not do anything but logging unspecific to the camera
     * device's name anyway.
     */
    private final CameraEventsHandler cameraEventsHandler = new CameraEventsHandler();

    @Override
    public void startCapture() {
        super.startCapture();
        this.isCapturing = true;
        // Checking if we need to switch the camera
        if(facingModeWhenStoppedCapturing != null && facingModeWhenStoppedCapturing != facingMode()) {
            CameraCaptureController.this.switchCamera(new SwitchCameraHandler() {
                @Override
                public void onSwitchCameraDone(String facingMode) {
                    Log.d(TAG, "Restoring to the right camera facing mode: " + facingMode);
                }
            });
        }
        this.facingModeWhenStoppedCapturing = null;
    }

    @Override
    public boolean stopCapture() {
        this.isCapturing = false;
        this.facingModeWhenStoppedCapturing = this.facingMode();
        return super.stopCapture();
    }

    public CameraCaptureController(CameraEnumerator cameraEnumerator, ReadableMap constraints) {
        super(
             constraints.getInt("width"),
             constraints.getInt("height"),
             constraints.getInt("frameRate"));

        this.cameraEnumerator = cameraEnumerator;
        this.constraints = constraints;
    }

    public interface SwitchCameraHandler {
        // Called whether or not it succeeded
        public void onSwitchCameraDone(String facingMode);
    }

    public void switchCamera(SwitchCameraHandler handler) {
        // When the video is muted, the camera session is destroyed
        // If we try to switch the camera while the session is destroyed
        // we receive this error from libwebrtc: "switchCamera: camera is not running."
        if(!this.isCapturing) {
            // So we are just persisting the state that we want so we can apply it in the future
            this.isFrontFacing = !this.isFrontFacing;
            handler.onSwitchCameraDone(facingMode());
            return;
        }
        if (videoCapturer instanceof CameraVideoCapturer) {
            CameraVideoCapturer capturer = (CameraVideoCapturer) videoCapturer;
            String[] deviceNames = cameraEnumerator.getDeviceNames();
            int deviceCount = deviceNames.length;

            // Nothing to switch to.
            if (deviceCount < 2) {
                handler.onSwitchCameraDone(facingMode());
                return;
            }

            // The usual case.
            if (deviceCount == 2) {
                capturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
                    @Override
                    public void onCameraSwitchDone(boolean b) {
                        isFrontFacing = b;
                        handler.onSwitchCameraDone(facingMode());
                    }

                    @Override
                    public void onCameraSwitchError(String s) {
                        Log.e(TAG, "Error switching camera: " + s);
                        handler.onSwitchCameraDone(facingMode());
                    }
                });
                return;
            }

            // If we are here the device has more than 2 cameras. Cycle through them
            // and switch to the first one of the desired facing mode.
            switchCamera(!isFrontFacing, deviceCount, handler);
        }
    }

    public String facingMode() {
        return  isFrontFacing ? "user" : "environment";
    }

    @Override
    protected VideoCapturer createVideoCapturer() {
        String deviceId = ReactBridgeUtil.getMapStrValue(this.constraints, "deviceId");
        String facingMode = ReactBridgeUtil.getMapStrValue(this.constraints, "facingMode");

        return createVideoCapturer(deviceId, facingMode);
    }

    /**
     * Helper function which tries to switch cameras until the desired facing mode is found.
     *
     * @param desiredFrontFacing - The desired front facing value.
     * @param tries - How many times to try switching.
     */
    private void switchCamera(boolean desiredFrontFacing, int tries, SwitchCameraHandler handler) {
        CameraVideoCapturer capturer = (CameraVideoCapturer) videoCapturer;

        capturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            @Override
            public void onCameraSwitchDone(boolean b) {
                if (b != desiredFrontFacing) {
                    int newTries = tries-1;
                    if (newTries > 0) {
                        switchCamera(desiredFrontFacing, newTries, handler);
                    }
                } else {
                    isFrontFacing = desiredFrontFacing;
                    handler.onSwitchCameraDone(facingMode());
                }
            }

            @Override
            public void onCameraSwitchError(String s) {
                Log.e(TAG, "Error switching camera: " + s);
                handler.onSwitchCameraDone(facingMode());
            }
        });
    }

    /**
     * Constructs a new {@code VideoCapturer} instance attempting to satisfy
     * specific constraints.
     *
     * @param deviceId the ID of the requested video device. If not
     * {@code null} and a {@code VideoCapturer} can be created for it, then
     * {@code facingMode} is ignored.
     * @param facingMode the facing of the requested video source such as
     * {@code user} and {@code environment}. If {@code null}, "user" is
     * presumed.
     * @return a {@code VideoCapturer} satisfying the {@code facingMode} or
     * {@code deviceId} constraint
     */
    private VideoCapturer createVideoCapturer(String deviceId, String facingMode) {
        String[] deviceNames = cameraEnumerator.getDeviceNames();
        List<String> failedDevices = new ArrayList<>();

        // If deviceId is specified, then it takes precedence over facingMode.
        if (deviceId != null) {
            for (String name : deviceNames) {
                if (name.equals(deviceId)) {
                    VideoCapturer videoCapturer
                        = cameraEnumerator.createCapturer(name, cameraEventsHandler);
                    String message = "Create user-specified camera " + name;
                    if (videoCapturer != null) {
                        Log.d(TAG, message + " succeeded");
                        this.isFrontFacing = cameraEnumerator.isFrontFacing(name);
                        return videoCapturer;
                    } else {
                        Log.d(TAG, message + " failed");
                        failedDevices.add(name);
                        break; // fallback to facingMode
                    }
                }
            }
        }

        // Otherwise, use facingMode (defaulting to front/user facing).
        final boolean isFrontFacing
            = facingMode == null || !facingMode.equals("environment");
        for (String name : deviceNames) {
            if (failedDevices.contains(name)) {
                continue;
            }
            try {
                // This can throw an exception when using the Camera 1 API.
                if (cameraEnumerator.isFrontFacing(name) != isFrontFacing) {
                    continue;
                }
            } catch (Exception e) {
                Log.e(
                    TAG,
                    "Failed to check the facing mode of camera " + name,
                    e);
                failedDevices.add(name);
                continue;
            }
            VideoCapturer videoCapturer
                = cameraEnumerator.createCapturer(name, cameraEventsHandler);
            String message = "Create camera " + name;
            if (videoCapturer != null) {
                Log.d(TAG, message + " succeeded");
                this.isFrontFacing = cameraEnumerator.isFrontFacing(name);
                return videoCapturer;
            } else {
                Log.d(TAG, message + " failed");
                failedDevices.add(name);
            }
        }

        // Fallback to any available camera.
        for (String name : deviceNames) {
            if (!failedDevices.contains(name)) {
                VideoCapturer videoCapturer
                    = cameraEnumerator.createCapturer(name, cameraEventsHandler);
                String message = "Create fallback camera " + name;
                if (videoCapturer != null) {
                    Log.d(TAG, message + " succeeded");
                    this.isFrontFacing = cameraEnumerator.isFrontFacing(name);
                    return videoCapturer;
                } else {
                    Log.d(TAG, message + " failed");
                    failedDevices.add(name);
                    // fallback to the next device.
                }
            }
        }

        Log.w(TAG, "Unable to identify a suitable camera.");

        return null;
    }
}
