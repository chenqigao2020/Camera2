package com.cc.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class CameraView extends TextureView {

    private boolean isInitComplete = false;

    private Size previewSize;

    private OnCameraFrameChangeListener onCameraFrameChangeListener;

    private OnCameraInitCompleteListener onCameraInitCompleteListener;

    private OnCameraStateChangeListener onCameraStateChangeListener;

    private CameraManager cameraManager;

    private CameraDevice cameraDevice;

    private CameraCaptureSession cameraCaptureSession;

    private ImageReader imageReader;

    private Handler workHandler;

    private Handler imageReaderWorkHandler;

    private Handler sessionHandler;

    private String cameraId;

    private boolean isCreatePreview;

    private Surface surface;

    public CameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        HandlerThread handlerThread = new HandlerThread("CameraView");
        handlerThread.start();
        workHandler = new Handler(handlerThread.getLooper());
        handlerThread = new HandlerThread("imageReader");
        handlerThread.start();
        imageReaderWorkHandler = new Handler(handlerThread.getLooper());
        handlerThread = new HandlerThread("sessionHandler");
        handlerThread.start();
        sessionHandler = new Handler(handlerThread.getLooper());
        cameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
        setSurfaceTextureListener(new SurfaceTextureListener());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        workHandler.getLooper().quit();
        imageReaderWorkHandler.getLooper().quit();
        setSurfaceTextureListener(null);
        destroyCameraPreviewSession();
        sessionHandler.getLooper().quitSafely();
    }

    public void createCameraPreviewSession() {
        if(!isInitComplete){
            throw new RuntimeException("...init?");
        }
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if(isCreatePreview){
                   return;
                }
                isCreatePreview = true;
                if(!checkPermission()){
                    return;
                }
                initImageReader();
                try {
                    final SurfaceTexture surfaceTexture = getSurfaceTexture();
                    surface = new Surface(surfaceTexture);
                    cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(@NonNull CameraDevice camera) {
                            cameraDevice = camera;
                            try {
                                surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                                final CaptureRequest.Builder mPreviewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                mPreviewRequestBuilder.addTarget(surface);
                                mPreviewRequestBuilder.addTarget(imageReader.getSurface());
                                List<Surface> surfaceList = new ArrayList<>(2);
                                surfaceList.add(surface);
                                surfaceList.add(imageReader.getSurface());
                                camera.createCaptureSession(surfaceList, new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                                        CameraView.this.cameraCaptureSession = cameraCaptureSession;
                                        try {
                                            // 自动对焦应
                                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                            CaptureRequest mPreviewRequest = mPreviewRequestBuilder.build();
                                            cameraCaptureSession.setRepeatingRequest(mPreviewRequest, null, workHandler);
                                        } catch (Throwable throwable) {
                                            throwable.printStackTrace();
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {}

                                }, workHandler);
                            } catch (Throwable throwable){
                                throwable.printStackTrace();
                            }
                        }

                        @Override
                        public void onDisconnected(@NonNull CameraDevice camera) {
                            cameraDevice = camera;
                        }

                        @Override
                        public void onError(@NonNull CameraDevice camera, int error) {
                            cameraDevice = camera;
                        }
                    }, workHandler);
                } catch (Throwable throwable){
                    throwable.printStackTrace();
                }
            }
        };
        try {
            sessionHandler.post(runnable);
        } catch (Throwable throwable){
            throwable.printStackTrace();
        }
    }

    private void initImageReader(){
        imageReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 1);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                } catch (Throwable throwable){
                    throwable.printStackTrace();
                }
                if(image == null){
                    return;
                }
                OnCameraFrameChangeListener listener = onCameraFrameChangeListener;
                if(listener == null){
                    try {
                        image.close();
                    } catch (Throwable throwable){
                        throwable.printStackTrace();
                    }
                }else{
                    listener.onCameraFrameChange(image, getWidth(), getHeight());
                }
            }
        }, imageReaderWorkHandler);
    }

    public void destroyCameraPreviewSession(){
        sessionHandler.post(new Runnable() {
            @Override
            public void run() {
                if(!isCreatePreview){
                    return;
                }
                isCreatePreview = false;
                if(imageReader != null){
                    try {
                        imageReader.close();
                    } catch (Throwable throwable){
                        throwable.printStackTrace();
                    }
                    imageReader = null;
                }
                if(cameraCaptureSession != null){
                    try {
                        cameraCaptureSession.close();
                    } catch (Throwable throwable){
                        throwable.printStackTrace();
                    }
                    cameraCaptureSession = null;
                }
                if(cameraDevice != null){
                    try {
                        cameraDevice.close();
                    } catch (Throwable throwable){
                        throwable.printStackTrace();
                    }
                    cameraDevice = null;
                }
                if(surface != null){
                    try {
                        Canvas canvas = surface.lockCanvas(new Rect(0, 0, getWidth(), getHeight()));
                        surface.unlockCanvasAndPost(canvas);
                        surface.release();
                    } catch (Throwable throwable){
                        throwable.printStackTrace();
                    }
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        if(onCameraStateChangeListener != null){
                            onCameraStateChangeListener.onPreviewStop();
                        }
                    }
                });
            }
        });
    }

    private boolean checkPermission(){
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    public void setOnCameraFrameChangeListener(OnCameraFrameChangeListener onCameraFrameChangeListener) {
        this.onCameraFrameChangeListener = onCameraFrameChangeListener;
    }

    public void setOnCameraInitCompleteListener(OnCameraInitCompleteListener onCameraInitCompleteListener) {
        this.onCameraInitCompleteListener = onCameraInitCompleteListener;
    }

    public void setOnCameraStateChangeListener(OnCameraStateChangeListener onCameraStateChangeListener) {
        this.onCameraStateChangeListener = onCameraStateChangeListener;
    }

    public boolean isInitComplete() {
        return isInitComplete;
    }

    /**
     * 返回view高宽和支持高宽最相近的尺寸，如果无可用尺寸，则会返回viewWidth、viewHeight组成的尺寸（应该不会存在这种情况）
     * **/
    private Size chooseOptimalSize(Size[] choices, int viewWidth, int viewHeight) {
        Size size = null;
        if(choices != null){
            for(Size s : choices){
                int c = s.getWidth() - viewWidth + s.getHeight() - viewHeight;
                c = Math.abs(c);
                if(size == null){
                    size = s;
                }else{
                    int oldC = size.getWidth() - viewWidth + size.getHeight() - viewHeight;
                    oldC = Math.abs(oldC);
                    if(c < oldC){
                        size = s;
                    }
                }
            }
        }
        if(size == null){
            size = new Size(viewWidth, viewHeight);
        }
        return size;
    }

    /**
     * 成像矩阵转换
     * **/
    private void configureTransform(TextureView textureView, Size optimumSize, float viewWidth, float viewHeight) {
        Matrix matrix = new Matrix();
        float optimumSizeHeight = optimumSize.getWidth();
        float optimumSizeWidth = optimumSize.getHeight();
        //取高宽缩放比例最大的
        float scale = Math.max(
                viewHeight / optimumSizeHeight,
                viewWidth / optimumSizeWidth
        );
        matrix.setScale(
                //相同的比例保证图像不会被拉伸
                scale,
                scale,
                //从图像中心缩放
                optimumSize.getHeight() / 2f,
                optimumSize.getWidth() / 2f
        );
        textureView.setTransform(matrix);
        //图像默认会拉伸至view的高宽，所以这里要设置view的高宽为转换后的高宽
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        if(layoutParams == null){
            throw new RuntimeException("getLayoutParams() == null, 需要设置一个LayoutParams");
        }
        layoutParams.width = (int) (optimumSize.getHeight() * scale);
        layoutParams.height = (int) (optimumSize.getWidth() * scale);
        setLayoutParams(layoutParams);
    }

    private class SurfaceTextureListener implements TextureView.SurfaceTextureListener {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            Size[] choicesSizes = null;
            try {
                String[] cameraArray = cameraManager.getCameraIdList();
                if(cameraArray.length < 2){
                    return;
                }
                cameraId = cameraArray[1];
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if(map != null){
                    choicesSizes = map.getOutputSizes(SurfaceTexture.class);
                }
            } catch (Throwable throwable){
                throwable.printStackTrace();
            }
            if(choicesSizes == null){
                return;
            }
            previewSize = chooseOptimalSize(choicesSizes, getWidth(), getHeight());
            configureTransform(CameraView.this, previewSize, getWidth(), getHeight());
            if(!isInitComplete){
                isInitComplete = true;
                if(onCameraInitCompleteListener != null){
                    onCameraInitCompleteListener.onInitComplete();
                }
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            destroyCameraPreviewSession();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    }

}