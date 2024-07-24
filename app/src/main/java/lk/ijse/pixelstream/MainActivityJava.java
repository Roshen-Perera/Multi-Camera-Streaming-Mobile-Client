package lk.ijse.pixelstream;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.view.Surface;
import android.view.SurfaceView;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;

public class MainActivityJava extends AppCompatActivity {

    private SurfaceView surfaceView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private Socket socket;
    private OutputStream outputStream;
    private ImageReader imageReader;
    private static final int CAMERA_REQUEST_CODE = 100;
    private String uniqueDeviceID = "device_" + System.currentTimeMillis();

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.surfaceView);
        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);

        startButton.setOnClickListener(v -> startStreaming());
        stopButton.setOnClickListener(v -> stopStreaming());

        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.INTERNET}, CAMERA_REQUEST_CODE);
    }

    private void stopStreaming() {
        System.out.println("Stopping streaming");
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

    private void startStreaming() {
        System.out.println("Hello World");
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
                    List.of(surface, imageReader.getSurface()),
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
                java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);

                // Send frame data to server
                sendFrame(bytes);

                image.close();
            }, null);

            new Thread(() -> {
                try {
                    socket = new Socket("192.168.1.18", 7777);
                    outputStream = socket.getOutputStream();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void sendFrame(byte[] frameData) {
        new Thread(() -> {
            try {
                byte[] dataWithId = (uniqueDeviceID + ":" + frameData.length + "\n").getBytes();
                byte[] combined = new byte[dataWithId.length + frameData.length];
                System.arraycopy(dataWithId, 0, combined, 0, dataWithId.length);
                System.arraycopy(frameData, 0, combined, dataWithId.length, frameData.length);
                outputStream.write(combined);
                outputStream.flush();
                System.out.println("Sent data with ID: " + uniqueDeviceID);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
