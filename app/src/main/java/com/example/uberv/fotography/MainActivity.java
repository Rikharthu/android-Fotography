package com.example.uberv.fotography;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import timber.log.Timber;

public class MainActivity extends AppCompatActivity
        implements SensorEventListener {

    private static final int PERMISSION_REQUEST_CAMERA = 0;
    private static final String FRAGMENT_DIALOG = "dialog";
    public static final String KEY_CAMERA_FACING = "KEY_CAMERA_FACING";
    private static final int FROM_RADS_TO_DEGS = -57;

    @BindView(R.id.switch_camera_btn)
    ImageButton mSwitchCameraBtn;
    @BindView(R.id.take_photo_btn)
    ImageButton mTakePhotoBtn;
    @BindView(R.id.preview)
    TextureView mTextureView;

    private CameraDevice mCameraDevice;
    private CameraDevice.StateCallback mCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Timber.d("Camera device opened");
            mCameraDevice = cameraDevice;
            startPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Timber.d("Camera device opened");
            closeCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            Timber.e("Camera device error occured");
            switch (error) {
                case ERROR_CAMERA_DEVICE:
                    Timber.e("Camera device encountered a fatal error");
                    break;
                case ERROR_CAMERA_DISABLED:
                    Timber.e("Could not open camera device due to device policy");
                    break;
                case ERROR_CAMERA_IN_USE:
                    Timber.e("Camera is already in use");
                    break;
                case ERROR_CAMERA_SERVICE:
                    Timber.e("Camera service has encountered a fatal error");
                    break;
            }
        }
    };
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private String mCameraId;
    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;
    private Size mPreviewSize;
    private boolean mDidAskCameraPermission = false;
    private boolean mIsFrontCamera = false;
    private SensorManager mSensorManager;
    private boolean mIsLandscape = false;

    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            Timber.d("Surface texture available: " + width + "x" + height);
            setupCamera(width, height);
            connectCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            Timber.d("Surface texture size changed to " + width + "x" + height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            Timber.d("Surface texture is destroyed");
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
//            Timber.d("Surface texture has been updated");
        }
    };

    /**
     * Conversion from screen rotation to JPEG orientation.
     * We need it because camera sensor is built in landscape mode.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }


    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        if (savedInstanceState != null) {
            mIsFrontCamera = savedInstanceState.getBoolean(KEY_CAMERA_FACING, false);
        }

        hideSystemUI();

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(KEY_CAMERA_FACING, mIsFrontCamera);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        hideSystemUI();

        startBackgroundThread();

        startCamera();

        mSensorManager.registerListener(this,
                mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
                SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void startCamera() {
        if (mTextureView.isAvailable()) {
            Timber.d("TextureView is available!");
            setupCamera(mTextureView.getWidth(), mTextureView.getHeight());
            connectCamera();
        } else {
            // attach a listener and wait until texture view is available
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        // free camera resources
        closeCamera();

        stopBackgroundThread();

        mSensorManager.unregisterListener(this);

        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CAMERA) {
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                // notify user and shutdown app
                ErrorDialog.newInstance("This app needs camera permission.")
                        .show(getSupportFragmentManager(), FRAGMENT_DIALOG);
            } else {
                // Permission has been granted
                startCamera();
            }
        }
//        else if (requestCode == PERMISSION_REQUEST_WRITE_EXTERNAL) {
//            if (grantResults.length != 1 || grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                // permission granted
//                mIsRecording = true;
//                mRecordImageButton.setImageResource(R.mipmap.btn_video_busy);
//                try {
//                    createVideoFileName();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//            } else {
//                Toast.makeText(this, "App need to save videos", Toast.LENGTH_SHORT).show();
//            }
//        }
    }

    private void checkCameraPermissions() {
        mDidAskCameraPermission = true;
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            // describe user why you need permission
            // TODO make a dialogfragment
            Toast.makeText(this, "This app requires access to camera", Toast.LENGTH_SHORT).show();
        }
        // request permission
        requestPermissions(new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CAMERA);
    }

    private boolean hasCameraPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void setupCamera(int width, int height) {
        Timber.d("Setting up camera for " + width + "x" + height);

        if (!hasCameraPermissions() && !mDidAskCameraPermission) {
            checkCameraPermissions();
            return;
        }

        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            // Iterate through available cameras
            for (String cameraId : cameraManager.getCameraIdList()) {
                // inspect camera characteristics
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

                if (mIsFrontCamera) {
                    if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_BACK) {
                        continue;
                    }
                } else if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    // skip front facing camera
                    continue;
                }

                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                int totalRotation = sensorToDeviceOrientation(characteristics, deviceOrientation);
                Timber.d("Total rotation: " + totalRotation);

                // Force preview to be in landscape mode
                // swap width and height if we are in portrait mode (rotation between sensor and device is 90 or 270 degrees)
                boolean swapRotation = totalRotation == 90 || totalRotation == 270;
                int rotatedWidth;
                int rotatedHeight;
                if (swapRotation) {
                    Timber.d("We are in portrait mode. Swapping width and height");
                    rotatedWidth = height;
                    rotatedHeight = width;
                } else {
                    rotatedWidth = width;
                    rotatedHeight = height;
                }
                Timber.d("rotatedWidth=" + rotatedWidth + ", rotatedHeight=" + rotatedHeight);

                // Calculate preview size
                // get all available camera resolutions
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                mPreviewSize = chooseOptimalSize(
                        map.getOutputSizes(SurfaceTexture.class), // supported preview resolutions
                        rotatedWidth, rotatedHeight
                );
                Timber.d("Optimal preview size is " + mPreviewSize.toString());

                Timber.d("Selected camera id: " + cameraId);
                mCameraId = cameraId;

                return;
            }
        } catch (
                CameraAccessException e)

        {
            Timber.e(e);
        }
    }

    private void connectCamera() {
        Timber.d("Connecting to camera " + mCameraId);
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraManager.openCamera(mCameraId, mCameraStateCallback, mBackgroundHandler);
        } catch (CameraAccessException | SecurityException e) {
            Timber.e(e);
        }
    }

    /**
     * Constructs a preview Camera 2 request
     */
    private void startPreview() {
        SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);
        try {
            // Create a capture request that contains configuration for the capture hardware (sensors,lens,flash),
            // the processing pipeline, the control algorithms and output buffers (surface)
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(previewSurface);

            // configure a capture sessions that is used for capturing images from the camera
            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface), // set of output surfaces
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Timber.d("Camera preview capture session has been configured");
                            // Start sending capture requests
                            try {
                                session.setRepeatingRequest(mCaptureRequestBuilder.build(),
                                        null, null);
                            } catch (CameraAccessException e) {
                                Timber.e(e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Timber.e("Failed to configure camera preview capture session");
                        }
                    }
                    , null);
        } catch (CameraAccessException e) {
            Timber.e(e);
        }
    }

    private void startBackgroundThread() {
        mBackgroundHandlerThread = new HandlerThread("FotographyBackgroundWorker");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (mBackgroundHandlerThread != null) {
            mBackgroundHandlerThread.quitSafely();
            try {
                mBackgroundHandlerThread.join();
            } catch (InterruptedException e) {
                Timber.e(e);
            } finally {
                mBackgroundHandlerThread = null;
            }
            mBackgroundHandler = null;
        }
    }

    private void closeCamera() {
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }

    /**
     * Calculates total rotation between camera sensor orientation and current device orientation
     */
    private static int sensorToDeviceOrientation(CameraCharacteristics characteristics, int deviceOrientation) {
        // Get camera sensor orientation in degrees
        int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        // map device orientation to degrees
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);

        Timber.d("Mapping sensor to device orientation, sensorOrientation=" + sensorOrientation + ", deviceOrietnation=" + deviceOrientation);

        // TODO why 270 or 360?
        // +360 to allow us using %360 to keep result in range [0; 360]
        return (sensorOrientation + deviceOrientation + 360) % 360;
    }

    /**
     * Chooses the best optimal {@link Size} to match passed {@code width} and {@code heigh}
     *
     * @param choices possible size options
     * @param width   desired width
     * @param height  desired height
     * @return Most optimal size for desired {@code width} and {@code heigh}
     */
    private static Size chooseOptimalSize(Size[] choices, int width, int height) {
        List<Size> bigEnough = new ArrayList<>();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * height / width &&         // matches aspect ratio
                    option.getWidth() >= width && option.getHeight() >= height) {   // width and height are big enough
                bigEnough.add(option);
            }
        }
        if (bigEnough.size() > 0) {
            // Get the smallest of sizes that are big enough
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Timber.d("Could not find optimal preview size. Returning first available option.");
            // TODO probably add some more logic here
            // Return default
            return choices[0];
        }
    }

    private void rotateUI(int degrees) {
        mTakePhotoBtn.animate().rotation(degrees);
        mSwitchCameraBtn.animate().rotation(degrees);
    }

    @OnClick(R.id.switch_camera_btn)
    void onSwitchCamera() {
        Timber.d("Switching camera");
        Toast.makeText(this, "Switching Camera", Toast.LENGTH_SHORT).show();
        mIsFrontCamera = !mIsFrontCamera;

        closeCamera();
        startCamera();
    }

    @OnClick(R.id.take_photo_btn)
    void onTakePhoto() {
        Timber.d("Taking photo");
        Toast.makeText(this, "Taking Photo", Toast.LENGTH_SHORT).show();
        rotateUI(90);
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (sensorEvent.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            checkDeviceRotation(sensorEvent.values);
        }
    }

    private void checkDeviceRotation(float[] values) {
        float[] rotationMatrix = new float[9];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values);
        int worldAxisX = SensorManager.AXIS_X;
        int worldAxisZ = SensorManager.AXIS_Z;
        float[] adjustedRotationMatrix = new float[9];
        SensorManager.remapCoordinateSystem(rotationMatrix, worldAxisX, worldAxisZ, adjustedRotationMatrix);
        float[] orientation = new float[3];
        SensorManager.getOrientation(adjustedRotationMatrix, orientation);
        float roll = orientation[2] * FROM_RADS_TO_DEGS;
        boolean isNegative = roll < 0;
        roll = Math.abs(roll);

        if (roll > 45 && roll < 135) {
            // landscape
            if (!mIsLandscape) {
                mIsLandscape = true;
                rotateUI(isNegative ? -90 : 90);
            }
        } else {
            if (mIsLandscape) {
                mIsLandscape = false;
                rotateUI(0);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        // do nothing
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    private static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}
