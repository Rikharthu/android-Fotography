package com.example.uberv.fotography;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
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
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Chronometer;
import android.widget.ImageButton;
import android.widget.Toast;

import com.example.uberv.fotography.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
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

    private static final int PERMISSION_REQUEST_CAMERA_AND_STORAGE = 0;
    private static final String FRAGMENT_DIALOG = "dialog";
    public static final String KEY_CAMERA_FACING = "KEY_CAMERA_FACING";
    private static final int FROM_RADS_TO_DEGS = -57;
    public static final String CHRONOMETER_LABEL_PREFIX = "REC ";

    @BindView(R.id.chronometer)
    Chronometer mChronometer;
    @BindView(R.id.switch_camera_btn)
    ImageButton mSwitchCameraBtn;
    @BindView(R.id.take_photo_btn)
    ImageButton mTakePhotoBtn;
    @BindView(R.id.switch_mode_btn)
    ImageButton mSwitchModeBtn;
    @BindView(R.id.preview)
    TextureView mTextureView;

    private CameraDevice mCameraDevice;
    private CameraDevice.StateCallback mCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Timber.d("Camera device opened");
            mCameraDevice = cameraDevice;
            if (mIsRecording) {
                startRecording();
            } else {
                startPreview();
            }
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
    private Size mVideoSize;
    private MediaRecorder mMediaRecorder;
    private int mTotalRotation;
    private int mRealTotalRotation;
    private SensorManager mSensorManager;
    private File mVideoFolder;
    private boolean mDidAskCameraPermission = false;
    private boolean mIsFrontCamera = false;
    private boolean mIsLandscape = false;
    private boolean mIsRecording = false;
    private boolean mIsVideoMode = false;

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

    private ImageReader mImageReader;
    private File mVideoFileName;


    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        if (savedInstanceState != null) {
            mIsFrontCamera = savedInstanceState.getBoolean(KEY_CAMERA_FACING, false);
        }

        hideSystemUI();

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        mVideoFolder = FileUtils.getVideoOutputDirectory();

//        setupMediaRecorder();
    }

    private void setupMediaRecorder() throws IOException {
        Timber.d("Setting up MediaRecorder");
        mMediaRecorder = new MediaRecorder();
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFile(mVideoFileName.getAbsolutePath());
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setVideoEncodingBitRate(10000000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
//        mMediaRecorder.setOrientationHint(mTotalRotation);
        mMediaRecorder.setOrientationHint(mRealTotalRotation);
        mMediaRecorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
            @Override
            public void onError(MediaRecorder mediaRecorder, int i, int i1) {
                Timber.d("error");
            }
        });
        mMediaRecorder.prepare();
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

        if (requestCode == PERMISSION_REQUEST_CAMERA_AND_STORAGE) {
            if (grantResults.length != 2 || grantResults[0] != PackageManager.PERMISSION_GRANTED || grantResults[1] != PackageManager.PERMISSION_GRANTED) {
                // notify user and shutdown app
                ErrorDialog.newInstance("This app needs camera and storage permission.")
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
        requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CAMERA_AND_STORAGE);
    }

    private boolean hasCameraPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
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
                mTotalRotation = sensorToDeviceOrientation(characteristics, deviceOrientation);
                Timber.d("Total rotation: " + mTotalRotation);

                // Force preview to be in landscape mode
                // swap width and height if we are in portrait mode (rotation between sensor and device is 90 or 270 degrees)
                boolean swapRotation = mTotalRotation == 90 || mTotalRotation == 270;
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
                mVideoSize = chooseOptimalSize(
                        map.getOutputSizes(MediaRecorder.class),
                        rotatedWidth, rotatedHeight
                );
                Timber.d("Optimal preview size is " + mPreviewSize.toString());
                Timber.d("Optimal video size is " + mVideoSize.toString());

                Timber.d("Selected camera id: " + cameraId);
                mCameraId = cameraId;

                return;
            }
        } catch (CameraAccessException e) {
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

    private void startRecording() {
        Timber.d("Starting recording");
        try {
            SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);

            mVideoFileName = FileUtils.createVideoFileName(FileUtils.getVideoOutputDirectory());
            setupMediaRecorder();
            Surface recordSurface = mMediaRecorder.getSurface();

            // Create capture builder request
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            mCaptureRequestBuilder.addTarget(previewSurface);
            mCaptureRequestBuilder.addTarget(recordSurface);

            // Create camera capture session
            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, recordSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                session.setRepeatingRequest(
                                        mCaptureRequestBuilder.build(),
                                        mRecordingCaptureCallback,
                                        null // all the work here will happen on the MediaRecorder
                                );
                            } catch (CameraAccessException e) {
                                Timber.e(e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Timber.e("Could not configure camera capture session for recording");
                        }
                    }, null);
            mMediaRecorder.start();
        } catch (IOException e) {
            Timber.e(e);
        } catch (CameraAccessException e) {
            Timber.e(e);
        }
    }

    private void startStopChronometer(boolean isStart) {
        if (isStart) {
            // hide other views
            mSwitchCameraBtn.setVisibility(View.GONE);
            mSwitchModeBtn.setVisibility(View.GONE);

            mChronometer.setVisibility(View.VISIBLE);
            mChronometer.setBase(SystemClock.elapsedRealtime());
            mChronometer.start();
        } else {
            // turn off
            mSwitchCameraBtn.setVisibility(View.VISIBLE);
            mSwitchModeBtn.setVisibility(View.VISIBLE);
            mChronometer.stop();
            mChronometer.setVisibility(View.GONE);
        }
    }

    private void stopRecording() {
        mMediaRecorder.stop();
        mMediaRecorder.reset();
        startStopChronometer(false);
        Timber.d("Video saved to " + mVideoFileName.getAbsolutePath());
        Toast.makeText(this, "Video saved to " + mVideoFileName.getAbsolutePath(), Toast.LENGTH_SHORT).show();
    }

    private byte[] convertYUV420888ToNV21(Image imgYUV420) {
        // Converting YUV_420_888 data to YUV_420_SP (NV21).
        byte[] data;
        ByteBuffer buffer0 = imgYUV420.getPlanes()[0].getBuffer();
        ByteBuffer buffer2 = imgYUV420.getPlanes()[2].getBuffer();
        int buffer0_size = buffer0.remaining();
        int buffer2_size = buffer2.remaining();
        data = new byte[buffer0_size + buffer2_size];
        buffer0.get(data, 0, buffer0_size);
        buffer2.get(data, buffer0_size, buffer2_size);
        return data;
    }

    /**
     * Constructs a preview Camera 2 request
     */
    private void startPreview() {
        Timber.d("Starting preview");

        SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        mImageReader = ImageReader.newInstance(mPreviewSize.getWidth(), mPreviewSize.getHeight(), ImageFormat.YUV_420_888, 1);
        mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image img = reader.acquireNextImage();
                Timber.d("New image available: " + img);
                if (img != null) {
                    byte[] imageBytes = convertYUV420888ToNV21(img);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                    img.close();
                }
            }
        }, mBackgroundHandler);
        try {
            // Create a capture request that contains configuration for the capture hardware (sensors,lens,flash),
            // the processing pipeline, the control algorithms and output buffers (surface)
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(previewSurface);

            // configure a capture sessions that is used for capturing images from the camera
            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, mImageReader.getSurface()), // set of output surfaces
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
            Timber.e(e, "Could not start camera preview");
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
    void onTakePhotoButtonPressed() {
        if (mIsVideoMode) {
            onStartStopRecording();
        } else {
            onTakePhoto();
        }
    }

    private void onTakePhoto() {
        Timber.d("Taking photo");
        Toast.makeText(this, "Taking Photo", Toast.LENGTH_SHORT).show();
    }

    private void onStartStopRecording() {
        if (mIsRecording) {
            // Stop recording
            Timber.d("Stopping recording");

            stopRecording();
            startPreview();

            mIsRecording = false;
            mTakePhotoBtn.setImageResource(R.drawable.recording_start);
        } else {
            // Start recording
            Timber.d("Starting recording");

            mIsRecording = true;
            mTakePhotoBtn.setImageResource(R.drawable.recording_stop);
            playRecordingSound();

            startRecording();
            startStopChronometer(true);
        }
    }

    private void playRecordingSound() {
        final MediaPlayer mp = MediaPlayer.create(this, R.raw.camera_flash);
        mp.start();
    }

    @OnClick(R.id.switch_mode_btn)
    void onSwitchMode() {
        Timber.d("Switching camera mode");
        mIsVideoMode = !mIsVideoMode;
        if (mIsVideoMode) {
            mSwitchModeBtn.setImageResource(android.R.drawable.ic_menu_camera);
            mTakePhotoBtn.setImageResource(R.drawable.recording_start);
        } else {
            mSwitchModeBtn.setImageResource(R.drawable.videocamera);
            mTakePhotoBtn.setImageResource(android.R.drawable.ic_menu_camera);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (sensorEvent.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            checkDeviceRotation(sensorEvent.values);
        }
    }

    private void checkDeviceRotation(float[] values) {
        // TODO understand what's happening
        float roll = calculateRoll(values);
        boolean isNegative = roll < 0;
        roll = Math.abs(roll);

        int closestOrientation = calculateClosestOrientation((int) roll);
        mRealTotalRotation = (mTotalRotation + closestOrientation + 360) % 360;

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

    private int calculateClosestOrientation(int rotation) {
        int count = rotation / 45 + 1;
        if (count % 2 != 0) {
            count -= 1;
        }
        int b = 45 * count;
        return b;
    }

    private float calculateRoll(float[] values) {
        float[] rotationMatrix = new float[9];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values);
        int worldAxisX = SensorManager.AXIS_X;
        int worldAxisZ = SensorManager.AXIS_Z;
        float[] adjustedRotationMatrix = new float[9];
        SensorManager.remapCoordinateSystem(rotationMatrix, worldAxisX, worldAxisZ, adjustedRotationMatrix);
        float[] orientation = new float[3];
        SensorManager.getOrientation(adjustedRotationMatrix, orientation);
        return orientation[2] * FROM_RADS_TO_DEGS;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        // do nothing
    }

    private CameraCaptureSession.CaptureCallback mRecordingCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            super.onCaptureStarted(session, request, timestamp, frameNumber);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
        }
    };

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
