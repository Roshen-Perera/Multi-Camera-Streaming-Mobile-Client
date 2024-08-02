package lk.ijse.pixelstream;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceView;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class StreamActivity extends AppCompatActivity {

    private static final int CAMERA_REQUEST_CODE = 100;
    private static final String TAG = "StreamActivity";

    private SurfaceView surfaceView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private Socket socket;
    private OutputStream outputStream;
    private ImageReader imageReader;
    private Thread sendThread;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stream);

        surfaceView = findViewById(R.id.surfaceView);
        startStreaming();

        Button stopButton = findViewById(R.id.stopButton);
        stopButton.setOnClickListener(v -> {
            stopStreaming();
            startActivity(new Intent(StreamActivity.this, MainActivity.class));
        });

        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.INTERNET}, CAMERA_REQUEST_CODE);
    }

    private void stopStreaming() {
        Log.d(TAG, "Stopping streaming");
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (sendThread != null && sendThread.isAlive()) {
            sendThread.interrupt();
        }
        try {
            if (outputStream != null) {
                outputStream.close();
            }
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void startStreaming() {
        Log.d(TAG, "Starting streaming");
        CameraManager cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String cameraId = cameraManager.getCameraIdList()[0];
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    startCameraPreview();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startCameraPreview() {
        Surface surface = surfaceView.getHolder().getSurface();
        imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2);

        try {
            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            captureRequestBuilder.addTarget(imageReader.getSurface());

            cameraDevice.createCaptureSession(
                    Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            cameraCaptureSession = session;
                            try {
                                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {}
                    },
                    null
            );

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    sendFrame(bytes);
                    image.close();
                }
            }, null);

            sendThread = new Thread(() -> {
                try {
                    socket = new Socket(MainActivity.hostIp.getText().toString(), 7777);
                    outputStream = socket.getOutputStream();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            sendThread.start();

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void sendFrame(byte[] frameData) {
        new Thread(() -> {
            try {
                if (outputStream != null) {
                    outputStream.write(frameData);
                    outputStream.flush();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }
}
