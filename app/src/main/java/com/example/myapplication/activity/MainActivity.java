package com.example.myapplication.activity;


import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.myapplication.R;
import com.example.myapplication.opengl.AspectFrameLayout;
import com.example.myapplication.opengl.CameraUtils;
import com.example.myapplication.opengl.CircularEncoder;
import com.example.myapplication.opengl.Drawable2d;
import com.example.myapplication.opengl.EglCore;
import com.example.myapplication.opengl.GlUtil;
import com.example.myapplication.opengl.ScaledDrawable2d;
import com.example.myapplication.opengl.Sprite2d;
import com.example.myapplication.opengl.Texture2dProgram;
import com.example.myapplication.opengl.TextureMovieEncoder2;
import com.example.myapplication.opengl.VideoEncoderCore;
import com.example.myapplication.opengl.WindowSurface;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback,
        SeekBar.OnSeekBarChangeListener, AdapterView.OnItemSelectedListener, View.OnClickListener,
        SurfaceTexture.OnFrameAvailableListener , Choreographer.FrameCallback{
    public static final String TAG = "MainActivity";
    private SurfaceView cameraOnTextureSurfaceView;
    private TextView tfcCameraParamsText;
    private TextView tfcRectSizeText;
    private TextView tfcZoomAreaText;
    private TextView tfcZoomLabelText;
    private SeekBar tfcZoomSeekbar;
    private TextView tfcSizeLabelText;
    private SeekBar tfcSizeSeekbar;
    private TextView tfcRotateLabelText;
    private SeekBar tfcRotateSeekbar;
    private SurfaceHolder holder;
    //  holder 赋值
    private SurfaceHolder sSurfaceHolder;
    private boolean mFileSaveInProgress;
    private int mCameraPreviewWidth, mCameraPreviewHeight;
    private float mCameraPreviewFps;
    private int mRectWidth, mRectHeight;
    private int mZoomWidth, mZoomHeight;
    private int mRotateDeg;
    private static final int DEFAULT_SIZE_PERCENT = 50;//
    private static final int DEFAULT_ZOOM_PERCENT = 0;
    private static final int DEFAULT_ROTATE_PERCENT = 0;
    private MainHandler mHandler;
    private RenderThread mRenderThread;
    private static final int REQ_CAMERA_WIDTH = 1280;
    private static final int REQ_DESIRED_FPS = 30;
    private static final int REQ_CAMERA_HEIGHT = 720;
    private Button toggleRecording;
    private Spinner spinner;
    private boolean mRecordingEnabled = false;
    // controls button state
    private Camera mCamera;
    private CameraHandler mCameraHandler;
    private float mSecondsOfVideo;
    private File mOutputFile;
    private CircularEncoder mCircEncoder;


    @Override       //当进度发生改变的时候
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (mRenderThread == null) {
            return;
        }
        RenderHandler handler = mRenderThread.getHandler();
        if (seekBar == tfcZoomSeekbar) {
            handler.sendZoomValue(progress);
        } else if (seekBar == tfcSizeSeekbar) {
            handler.sendSizeValue(progress);
        } else if (seekBar == tfcRotateSeekbar) {
            handler.sendRotateValue(progress);
        } else {
            throw new RuntimeException("unknown seek bar");
        }
        handler.sendRedraw();
    }

    @Override      //开始跟踪拖到的时候
    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    @Override   //当用户结束对滑块滑动时,调用该方法
    public void onStopTrackingTouch(SeekBar seekBar) {
    }

    @Override   // 解析手势触摸事件
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_DOWN:
                if (mRenderThread != null) {
                    RenderHandler handler = mRenderThread.getHandler();
                    handler.sendPosition((int) x, (int) y);
                }
                break;
            default:
                break;
        }
        return true;
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        Spinner spinner = (Spinner) parent;
        // 获取选择的位置
        final int selectedItemPosition = spinner.getSelectedItemPosition();
        /*cameraOnTextureSurfaceView.queueEvent(new Runnable() {
            @Override public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeFilterMode(selectedItemPosition);
            }
        });*/
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    @Override
    public void onClick(View v) {
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            mRecordingEnabled = !mRecordingEnabled;
            updateControls();
            rh.setRecordingEnabled(mRecordingEnabled);
            Toast.makeText(this,"mRecordingEnabled",Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        //cameraOnTextureSurfaceView.requestRender();
    }

    @Override
    public void doFrame(long frameTimeNanos) {

    }


    /**
     * 渲染线程助手
     */
    private class RenderHandler extends Handler {


        private static final int MSG_SURFACE_AVAILABLE = 0;
        private static final int MSG_SURFACE_CHANGED = 1;
        private static final int MSG_SURFACE_DESTROYED = 2;
        private static final int MSG_SHUTDOWN = 3;
        private static final int MSG_FRAME_AVAILABLE = 4;
        private static final int MSG_ZOOM_VALUE = 5;
        private static final int MSG_SIZE_VALUE = 6;
        private static final int MSG_ROTATE_VALUE = 7;
        private static final int MSG_POSITION = 8;
        private static final int MSG_REDRAW = 9;
        private static final int MSG_RECORDING_ENABLED = 10;
        private static final int MSG_RECORD_METHOD = 11;

        // 我们不需要一个软引用，在轮询器消失的时候 但是这个不影响
        private WeakReference<RenderThread> renderThreadWeakReference;


        public RenderHandler(RenderThread rt) {
            renderThreadWeakReference = new WeakReference<>(rt);
        }

        /**
         * Sends the "surface available" message.  If the surface was newly created (i.e.
         * this is called from surfaceCreated()), set newSurface to true.  If this is
         * being called during Activity startup for a previously-existing surface, set
         * newSurface to false.
         * <p>
         * The flag tells the caller whether or not it can expect a surfaceChanged() to
         * arrive very soon.
         * <p>
         * Call from UI thread.
         * 如果是刚创建的就是1 如果是activity启动期间被调用的以前就存在的就是0
         *
         * @param holder
         * @param newSurface
         */
        public void sendSurfaceAvailable(SurfaceHolder holder, boolean newSurface) {
            sendMessage(obtainMessage(MSG_SURFACE_AVAILABLE, newSurface ? 1 : 0, 0, holder));
        }

        /**
         * 发送surface发生变化的消息
         *
         * @param format
         * @param width
         * @param height
         */
        public void sendSurfaceChanged(@SuppressWarnings("unused") int format, int width, int height) {
            // 忽略format
            sendMessage(obtainMessage(MSG_SURFACE_CHANGED, width, height));
        }

        /**
         * 发送surface销毁的消息
         */
        public void sendSurfaceDestroyed() {
            sendMessage(obtainMessage(MSG_SURFACE_DESTROYED));
        }

        /**
         * 同上
         */
        public void sendShutdown() {
            sendMessage(obtainMessage(MSG_SHUTDOWN));
        }

        /**
         * 发送surface available 的消息
         */
        public void sendFrameAvailable() {
            sendMessage(obtainMessage(MSG_FRAME_AVAILABLE));
        }

        /**
         * 发送 焦距变化的消息
         */
        public void sendZoomValue(int progress) {
            sendMessage(obtainMessage(MSG_ZOOM_VALUE, progress, 0));
        }

        /**
         * 发送尺寸发生变的消息
         */
        public void sendSizeValue(int progress) {
            sendMessage(obtainMessage(MSG_SIZE_VALUE, progress, 0));
        }

        /**
         * @param progress
         */
        public void sendRotateValue(int progress) {
            sendMessage(obtainMessage(MSG_ROTATE_VALUE, progress, 0));
        }

        /**
         * 位置消息
         *
         * @param x
         * @param y
         */
        public void sendPosition(int x, int y) {
            sendMessage(obtainMessage(MSG_POSITION, x, y));
        }

        public void sendRedraw() {
            sendMessage(obtainMessage(MSG_REDRAW));
        }

        public void setRecordingEnabled(boolean enabled) {
            sendMessage(obtainMessage(MSG_RECORDING_ENABLED, enabled ? 1 : 0, 0));
        }

        public void setRecordMethod(int recordMethod) {
            sendMessage(obtainMessage(MSG_RECORD_METHOD, recordMethod, 0));
        }

        @Override
        public void handleMessage(Message msg) {
            int what = msg.what;
            RenderThread renderThread = renderThreadWeakReference.get();
            if (renderThread == null) {
                return;
            }
            switch (what) {
                case MSG_SURFACE_AVAILABLE:
                    renderThread.surfaceAvailable((SurfaceHolder) msg.obj, msg.arg1 != 0);
                    break;
                case MSG_SURFACE_CHANGED:
                    renderThread.surfaceChanged(msg.arg1, msg.arg2);
                    break;
                case MSG_SURFACE_DESTROYED:
                    renderThread.surfaceDestroy();
                    break;
                case MSG_SHUTDOWN:
                    renderThread.shutDown();
                    break;
                case MSG_FRAME_AVAILABLE:
                    renderThread.frameAvailable();
                    break;
                case MSG_ZOOM_VALUE:
                    renderThread.setZoom(msg.arg1);
                    break;
                case MSG_SIZE_VALUE:
                    renderThread.setSize(msg.arg1);
                    break;
                case MSG_ROTATE_VALUE:
                    renderThread.setRotate(msg.arg1);
                    break;
                case MSG_POSITION:
                    renderThread.setPosition(msg.arg1, msg.arg2);
                    break;
                case MSG_REDRAW:
                    renderThread.draw();
                    break;
                case MSG_RECORDING_ENABLED:
                    renderThread.setRecordingEnabled(msg.arg1 != 0);
                    break;
                case MSG_RECORD_METHOD:
                    renderThread.setRecordMethod(msg.arg1);
                    break;
                default:
                    throw new RuntimeException("unknown message");
            }
        }

    }

    /**
     * 渲染线程
     * 实现侦听器的接口
     */
    private class RenderThread extends Thread implements SurfaceTexture.OnFrameAvailableListener {
        private final Sprite2d[] mEdges;
        private final SurfaceHolder mSurfaceHolder;
        private int mCameraPreviewWidth, mCameraPreviewHeight;
        private MainHandler mMainHandler;
        private volatile RenderHandler mHandler;
        // 锁
        private Object mStartLock = new Object();
        //准备的状态
        private boolean mReady = false;
        private EglCore mEglCore;
        private WindowSurface mWindowSurface;
        private Texture2dProgram mTexProgram;
        private final ScaledDrawable2d mRectDrawable =
                new ScaledDrawable2d(Drawable2d.Prefab.RECTANGLE);
        private final Sprite2d mRect;
        private int mWindowSurfaceWidth, mWindowSurfaceHeight;
        private float mPosY, mPosX;
        private float[] mDisplayProjectionMatrix;
        private float mSizePercent = DEFAULT_SIZE_PERCENT;
        private float mZoomPercent = DEFAULT_ZOOM_PERCENT;
        private float mRotePercent = DEFAULT_ROTATE_PERCENT;
        private SurfaceTexture mCameraTexture;
        private Rect mVideoRect;
        private WindowSurface mInputWindowSurface;
        private int mRecordMethod;
        private boolean mRecordingEnabled;
        private TextureMovieEncoder2 mVideoEncoder;


        // 通过构造方法将MainHandler 传过来
        public RenderThread(SurfaceHolder holder, MainHandler mHandler, File outputFile) {
            mMainHandler = mHandler;
            mSurfaceHolder = holder;
            mOutputFile = outputFile;
            mVideoRect = new Rect();
            mDisplayProjectionMatrix = new float[16];
            Matrix.setIdentityM(mDisplayProjectionMatrix, 0);
            mRect = new Sprite2d(mRectDrawable);
            mEdges = new Sprite2d[4];
        }

        @Override
        public void run() {
            super.run();
            //子线程创建轮询器对消息队列进行轮询
            Looper.prepare();
            //创建一个线程助手在线程准备好之前
            mHandler = new RenderHandler(this);
            synchronized (mStartLock) {
                mReady = true;
                mStartLock.notify();//如果当前的线程不是自己的
            }
            // Prepare EGL and open the camera before we start handling messages.
            // 在处理消息之前 准备EGL 打开相机
            // mEglCore = new EglCore(newSharedContext, EglCore.FLAG_RECORDABLE);
            mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
            openCamera(REQ_CAMERA_WIDTH, REQ_CAMERA_HEIGHT, REQ_DESIRED_FPS);
            //开始轮询
            //Constructor flag: surface must be recordable. This discourages EGL from using a pixel
            //format that cannot be converted efficiently to something usable by the video encoder.
            Looper.loop();
            // 释放相机
            releaseCamera();
            releaseGl();
            mEglCore.release();
            synchronized (mStartLock) {
                mReady = false;
            }
        }

        public void surfaceAvailable(SurfaceHolder holder, boolean newSurface) {
            Surface surface = holder.getSurface();
            mWindowSurface = new WindowSurface(mEglCore, surface, false);
            mWindowSurface.makeCurrent();
            mTexProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT);
            int textureId = mTexProgram.createTextureObject();
            mCameraTexture = new SurfaceTexture(textureId);
            mRect.setTexture(textureId);
            if (!newSurface) {
                mWindowSurfaceWidth = mWindowSurface.getWidth();
                mWindowSurfaceHeight = mWindowSurface.getHeight();
                finishSurfaceSetup();
            }
            mCameraTexture.setOnFrameAvailableListener(this);
        }
        private void setRecordMethod(int recordMethod) {
            Log.d(TAG, "RT: setRecordMethod " + recordMethod);
            mRecordMethod = recordMethod;}
        /**
         * 设置完成
         */
        private void finishSurfaceSetup() {
            int width = mWindowSurfaceWidth;
            int height = mWindowSurfaceHeight;
            // Use full window.
            GLES20.glViewport(0, 0, width, height);
            // Simple orthographic projection, with (0,0) in lower-left corner.
            Matrix.orthoM(mDisplayProjectionMatrix, 0, 0, width, 0, height, -1, 1);
            // Default position is center of screen.
            mPosX = width / 2.0f;
            mPosY = height / 2.0f;
            updateGeometry();
            try { // 设置纹理
                mCamera.setPreviewTexture(mCameraTexture);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            //开始预览
            mCamera.startPreview();
        }

        /**
         * Updates the geometry of mRect, based on the size of the window and the current
         * values set by the UI.
         * 更新几何
         */
        private void updateGeometry() {
            int width = mWindowSurfaceWidth;
            int height = mWindowSurfaceHeight;
            int min = Math.min(width, height);
            float scaled = min * (mSizePercent / 100.0f) * 1.25f;
            // Size
            float cameraAspect = (float) mCameraPreviewWidth / mCameraPreviewHeight;
            int newWidth = Math.round(scaled * cameraAspect);
            int newHeight = Math.round(scaled);
            // 变焦
            float zoomFactor = 1.0f - (mZoomPercent / 100f);
            // 设置默认旋转的角度
            int rotateAngle = Math.round(360 * (mRotePercent / 100f));
            // 设置初始角度
            mRect.setPosition(mPosX, mPosY);
            // 设置旋转角
            mRect.setRotation(rotateAngle);
            // 设置缩放大小
            mRect.setScale(newWidth, newHeight);
            // 变焦
            mRectDrawable.setScale(zoomFactor);
            //消息发送到主线程
            mMainHandler.sendRectSize(newWidth, newHeight);
            mMainHandler.sendZoomArea(Math.round(mCameraPreviewWidth * zoomFactor),
                    Math.round(mCameraPreviewHeight * zoomFactor));
            mMainHandler.sendRotateAngle(rotateAngle);
        }


        private void releaseGl() {
            GlUtil.checkGlError("releaseGl start");
            if (mWindowSurface != null) {
                mWindowSurface.release();
                mWindowSurface = null;
            }
            if (mTexProgram != null) {
                mTexProgram.release();
                mTexProgram = null;
            }
            GlUtil.checkGlError("release error");
            mEglCore.makeNothingCurrent();
        }


        private void openCamera(int desiredWidth, int desiredHeight, int desiredFps) {
            // 对相机进行非空判断
            if (mCamera != null) {
                throw new RuntimeException("相机已经初始化完成");
            }
            Camera.CameraInfo info = new Camera.CameraInfo();
            //获取相机摄像头的数量
            int numberOfCameras = Camera.getNumberOfCameras();
            //是否是前置摄像头 如果是就打开
            for (int i = 0; i < numberOfCameras; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    mCamera = Camera.open(i);
                    break;
                }
            }
            // 如果相机为空就打开
            if (mCamera == null) {
                Log.d("onFrameAvailable", "openCamera: " + numberOfCameras);
                mCamera = Camera.open();
            }
            if (mCamera == null) {
                throw new RuntimeException("不能开启相机");
            }
            Camera.Parameters parameters = mCamera.getParameters();
            CameraUtils.choosePreviewSize(parameters, desiredWidth, desiredHeight);
            int thousandFps = CameraUtils.chooseFixedPreviewFps(parameters, desiredFps * 1000);

            //是否设置相机提示音
            parameters.setRecordingHint(true);
            //设置相机的参数
            mCamera.setParameters(parameters);
            // 设置帧数的范围
            int[] fpsRange = new int[2];
            // 获取预览的尺寸
            Camera.Size previewSize = parameters.getPreviewSize();
            // 获取帧率的范围
            parameters.getPreviewFpsRange(fpsRange);
            String previewFacts = previewSize.width + "x" + previewSize.height;
            if (fpsRange[0] == fpsRange[1]) {
                previewFacts += " @" + (fpsRange[0] / 1000.0) + "fps";
            } else {
                previewFacts += " @[" + (fpsRange[0] / 1000.0) + "-" + (fpsRange[1] / 1000.0) + "]fps";
            }
            mCameraPreviewWidth = previewSize.width;
            mCameraPreviewHeight = previewSize.height;
            // 讲相机的参数发送的主线程
            mMainHandler.sendCameraParams(mCameraPreviewWidth, mCameraPreviewHeight, thousandFps / 1000.0f);
        }

        /**
         * 返回线程助手
         *
         * @return
         */
        public RenderHandler getHandler() {
            return mHandler;
        }

        /**
         * 处理surface变化信息
         *
         * @param width
         * @param height
         */
        public void surfaceChanged(int width, int height) {
            mWindowSurfaceWidth = width;
            mWindowSurfaceHeight = height;
            finishSurfaceSetup();
        }

        /**
         * 线程等待知道UI线程的调用
         */
        public void waitUntilReady() {
            synchronized (mStartLock) {
                while (!mReady) {
                    try {
                        mStartLock.wait();
                    } catch (InterruptedException e) {
                        //e.printStackTrace();
                    }
                }
            }
        }

        /**
         * 停止
         */
        public void shutDown() {
            Looper.myLooper().quit();
        }

        /**
         * 处理销毁消息
         */
        public void surfaceDestroy() {
            releaseGl();
        }

        /**
         * 处理从相机传来的帧数据
         */
        public void frameAvailable() {
            mCameraTexture.updateTexImage();//更新图像
            draw();
        }

        private void draw() {
            GlUtil.checkGlError("draw start");
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            mRect.draw(mTexProgram, mDisplayProjectionMatrix);
            // Use this to "publish" the current frame.
            mWindowSurface.swapBuffers();
            GlUtil.checkGlError("draw error");
        }


        public void setZoom(int percent) {
            mZoomPercent = percent;
            updateGeometry();
        }


        public void setRotate(int progress) {
            mRotePercent = progress;
            updateGeometry();
        }

        public void setSize(int progress) {
            mSizePercent = progress;
            updateGeometry();
        }

        public void setPosition(int x, int y) {
            mPosX = x;
            mPosY = mWindowSurfaceHeight - y;
            updateGeometry();
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            mHandler.sendFrameAvailable();
        }

        public void setRecordingEnabled(boolean recordingEnabled) {
            if (recordingEnabled == mRecordingEnabled) {
                return;
            }
            if (recordingEnabled) {
                startEncoder();
            } else {
                stopEncoder();
            }
            mRecordingEnabled = recordingEnabled;
        }

        private void stopEncoder() {
            if (mVideoEncoder != null) {
                mVideoEncoder.stopRecording();
                // TODO: wait (briefly) until it finishes shutting down so we know file is
                //       complete, or have a callback that updates the UI
                mVideoEncoder = null;
            }
            if (mInputWindowSurface != null) {
                mInputWindowSurface.release();
                mInputWindowSurface = null;
            }
        }

        private void startEncoder() {

            // Record at 1280x720, regardless of the window dimensions.  The encoder may
            // explode if given "strange" dimensions, e.g. a width that is not a multiple
            // of 16.  We can box it as needed to preserve dimensions.
            final int BIT_RATE = 4000000;   // 4Mbps
            final int VIDEO_WIDTH = 1280;
            final int VIDEO_HEIGHT = 720;
            int windowWidth = mWindowSurface.getWidth();
            int windowHeight = mWindowSurface.getHeight();
            float windowAspect = (float) windowHeight / (float) windowWidth;
            int outWidth, outHeight;
            if (VIDEO_HEIGHT > VIDEO_WIDTH * windowAspect) {
                // limited by narrow width; reduce height
                outWidth = VIDEO_WIDTH;
                outHeight = (int) (VIDEO_WIDTH * windowAspect);
            } else {
                // limited by short height; restrict width
                outHeight = VIDEO_HEIGHT;
                outWidth = (int) (VIDEO_HEIGHT / windowAspect);
            }
            int offX = (VIDEO_WIDTH - outWidth) / 2;
            int offY = (VIDEO_HEIGHT - outHeight) / 2;
            mVideoRect.set(offX, offY, offX + outWidth, offY + outHeight);
            Log.d("Adjusting", "Adjusting window " + windowWidth + "x" + windowHeight +
                    " to +" + offX + ",+" + offY + " " +
                    mVideoRect.width() + "x" + mVideoRect.height());

            VideoEncoderCore encoderCore;
            try {
                encoderCore = new VideoEncoderCore(VIDEO_WIDTH, VIDEO_HEIGHT,
                        BIT_RATE, mOutputFile);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            mInputWindowSurface = new WindowSurface(mEglCore, encoderCore.getInputSurface(), true);
            mVideoEncoder = new TextureMovieEncoder2(encoderCore);
        }

    }

    /**
     * 主线程助手
     */
    private class MainHandler extends Handler {

        private static final int MSG_SEND_CAMERA_PARAMS0 = 0;
        private static final int MSG_SEND_CAMERA_PARAMS1 = 1;
        private static final int MSG_SEND_RECT_SIZE = 2;
        private static final int MSG_SEND_ZOOM_AREA = 3;
        private static final int MSG_SEND_ROTATE_ANGLE = 4;
        private static final int MSG_FILE_SAVE_COMPLETE = 5;
        private static final int MSG_BUFFER_STATUS = 6;

        private WeakReference<MainActivity> mWeakActivity;

        /**
         * 将Activity通过构造函数传递进来
         *
         * @param activity
         */
        public MainHandler(MainActivity activity) {
            mWeakActivity = new WeakReference<MainActivity>(activity);
        }

        /**
         * <p>
         * 将相机参数信息发送的到主线程来回调渲染线程
         * <p/>
         */
        public void sendCameraParams(int width, int height, float fps) {
            // The right way to do this is to bundle them up into an object.  The lazy
            // way is to send two messages.
            sendMessage(obtainMessage(MSG_SEND_CAMERA_PARAMS0, width, height));
            sendMessage(obtainMessage(MSG_SEND_CAMERA_PARAMS1, (int) fps * 1000, 0));
        }

        /**
         * Sends the updated rect size to the main thread.
         * <p>
         * Call from render thread.
         * 更新尺寸
         */
        public void sendRectSize(int width, int height) {
            sendMessage(obtainMessage(MSG_SEND_RECT_SIZE, width, height));
        }

        /**
         * 更新焦距
         *
         * @param width
         * @param height
         */
        public void sendZoomArea(int width, int height) {
            sendMessage(obtainMessage(MSG_SEND_ZOOM_AREA, width, height));
        }

        /**
         * 更新旋转角度
         *
         * @param rotateAngle
         */
        public void sendRotateAngle(int rotateAngle) {
            sendMessage(obtainMessage(MSG_SEND_ROTATE_ANGLE, rotateAngle, 0));
        }



        @Override
        public void handleMessage(Message msg) {

            MainActivity mainActivity = mWeakActivity.get();
            if (mainActivity == null) {
                return;
            }
            switch (msg.what) {
                case MSG_SEND_CAMERA_PARAMS0:
                    mainActivity.mCameraPreviewWidth = msg.arg1;
                    mainActivity.mCameraPreviewHeight = msg.arg2;
                    break;
                case MSG_SEND_CAMERA_PARAMS1:
                    mainActivity.mCameraPreviewFps = msg.arg1 / 1000.0f;
                    mainActivity.updateControls();
                    break;
                case MSG_SEND_RECT_SIZE:
                    mainActivity.mRectWidth = msg.arg1;
                    mainActivity.mRectHeight = msg.arg2;
                    //更新UI
                    mainActivity.updateControls();
                    break;
                case MSG_SEND_ZOOM_AREA:
                    mainActivity.mZoomWidth = msg.arg1;
                    mainActivity.mZoomHeight = msg.arg2;
                    //更新UI
                    mainActivity.updateControls();
                    break;
                case MSG_SEND_ROTATE_ANGLE:
                    mainActivity.mRotateDeg = msg.arg1;
                    //更新UI
                    mainActivity.updateControls();
                    break;
                case MSG_FILE_SAVE_COMPLETE:
                    mainActivity.fileSaveComplete(msg.arg1);
                    break;
                case MSG_BUFFER_STATUS:
                    long duration = (((long) msg.arg1) << 32) |
                            (((long) msg.arg2) & 0xffffffffL);
                    mainActivity.updateBufferStatus(duration);
                    break;
                default:
                    throw new RuntimeException("unknown message" + msg.what);
            }
        }


    }

    private void fileSaveComplete(int status) {
        if (!mFileSaveInProgress) {
            throw new RuntimeException("WEIRD: got fileSaveCmplete when not in progress");
        }
        mFileSaveInProgress = false;
        updateControls();
        String str = getString(R.string.nowRecording);
        if (status == 0) {
            str = getString(R.string.recordingSucceeded);
        } else {
            str = getString(R.string.recordingFailed, status);
        }
        Toast toast = Toast.makeText(this, str + "<<<" + status, Toast.LENGTH_SHORT);
        toast.show();
    }

    private void updateBufferStatus(long duration) {
        mSecondsOfVideo = duration / 1000000.0f;
        updateControls();
    }

    private void updateControls() {
        String str = getString(R.string.tfcCameraParams, mCameraPreviewWidth,
                mCameraPreviewHeight, mCameraPreviewFps);
        TextView tv = (TextView) findViewById(R.id.tfcCameraParams_text);
        tv.setText(str);

        str = getString(R.string.tfcRectSize, mRectWidth, mRectHeight);
        tv = (TextView) findViewById(R.id.tfcRectSize_text);
        tv.setText(str);

        str = getString(R.string.tfcZoomArea, mZoomWidth, mZoomHeight);
        tv = (TextView) findViewById(R.id.tfcZoomArea_text);
        tv.setText(str);

        int id = mRecordingEnabled ?
                R.string.toggleRecordingOff : R.string.toggleRecordingOn;
        toggleRecording.setText(id);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mOutputFile = new File(getFilesDir(), "camera-haha.mp4");
        mHandler = new MainHandler(this);
        mSecondsOfVideo = 0.0f;
        initView();//初始化View
        initListener();//初始化监听
        initData();
        updateControls();//更行界面文字
    }


    private void initView() {

        cameraOnTextureSurfaceView = (SurfaceView) findViewById(R.id.cameraOnTexture_surfaceView);

        // 纹理布局添加Holder
        holder = cameraOnTextureSurfaceView.getHolder();
        // 添加回调
        holder.addCallback(this);
        tfcCameraParamsText = (TextView) findViewById(R.id.tfcCameraParams_text);
        tfcRectSizeText = (TextView) findViewById(R.id.tfcRectSize_text);
        tfcZoomAreaText = (TextView) findViewById(R.id.tfcZoomArea_text);
        tfcZoomLabelText = (TextView) findViewById(R.id.tfcZoomLabel_text);
        tfcZoomSeekbar = (SeekBar) findViewById(R.id.tfcZoom_seekbar);
        tfcSizeLabelText = (TextView) findViewById(R.id.tfcSizeLabel_text);
        tfcSizeSeekbar = (SeekBar) findViewById(R.id.tfcSize_seekbar);
        tfcRotateLabelText = (TextView) findViewById(R.id.tfcRotateLabel_text);
        tfcRotateSeekbar = (SeekBar) findViewById(R.id.tfcRotate_seekbar);
        toggleRecording = (Button) findViewById(R.id.toggleRecordingOn);
        spinner = (Spinner) findViewById(R.id.cameraFilter_spinner);
        mCameraHandler = new CameraHandler(this);

        //cameraOnTextureSurfaceView.setEGLContextClientVersion(2);     // select GLES 2.0
        // mRenderer = new CameraSurfaceRenderer(mCameraHandler, sVideoEncoder, outputFile);
        //cameraOnTextureSurfaceView.setRenderer(mRenderer);
        //cameraOnTextureSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    private void initListener() {
        //seekBar 初始值
        tfcZoomSeekbar.setProgress(DEFAULT_ZOOM_PERCENT);
        tfcSizeSeekbar.setProgress(DEFAULT_SIZE_PERCENT);
        tfcRotateSeekbar.setProgress(DEFAULT_ROTATE_PERCENT);
        // seekBar 监听
        tfcZoomSeekbar.setOnSeekBarChangeListener(this);
        tfcSizeSeekbar.setOnSeekBarChangeListener(this);
        tfcRotateSeekbar.setOnSeekBarChangeListener(this);
        spinner.setOnItemSelectedListener(this);
        toggleRecording.setOnClickListener(this);
    }

    private void initData() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.cameraFilterNames, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        // 获取视频编码器的状态
    }


    @Override       // surfaceHolder.callback     当view创建的时候
    public void surfaceCreated(SurfaceHolder holder) {


        if (sSurfaceHolder != null) {
            throw new RuntimeException("surfaceHolder 已经存在");
        }
        //holder 赋值
        sSurfaceHolder = holder;

        if (mRenderThread != null) {
            RenderHandler handler = mRenderThread.getHandler();
            handler.sendSurfaceAvailable(holder, true);





        } else {
            Log.d(TAG, "render thread not running");
        }


    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mRenderThread != null) {
            RenderHandler handler = mRenderThread.getHandler();
            handler.sendSurfaceChanged(format, width, height);
        } else {
            Log.d(TAG, "surfaceChanged: ");
            return;
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mRenderThread != null) {
            RenderHandler handler = mRenderThread.getHandler();
            handler.sendSurfaceDestroyed();
        }
        sSurfaceHolder = null;
    }

    static class CameraHandler extends Handler {
        public static final int MSG_SET_SURFACE_TEXTURE = 0;

        // Weak reference to the Activity; only access this from the UI thread.
        private WeakReference<MainActivity> mWeakActivity;

        public CameraHandler(MainActivity activity) {
            mWeakActivity = new WeakReference<MainActivity>(activity);
        }

        /**
         * Drop the reference to the activity.  Useful as a paranoid measure to ensure that
         * attempts to access a stale Activity through a handler are caught.
         */
        public void invalidateHandler() {
            mWeakActivity.clear();
        }

        @Override  // runs on UI thread
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Log.d(TAG, "CameraHandler [" + this + "]: what=" + what);

            MainActivity activity = mWeakActivity.get();
            if (activity == null) {
                Log.w(TAG, "CameraHandler.handleMessage: activity is null");
                return;
            }

            switch (what) {
                case MSG_SET_SURFACE_TEXTURE:
                    //activity.handleSetSurfaceTexture((SurfaceTexture) inputMessage.obj);
                    break;
                default:
                    throw new RuntimeException("unknown msg " + what);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateControls();
        mRenderThread = new RenderThread(holder, mHandler, mOutputFile);
        mRenderThread.setName("TexFromCam Render");
        AspectFrameLayout layout = (AspectFrameLayout) findViewById(R.id.cameraPreview_afl);
        layout.setAspectRatio((double) mCameraPreviewWidth / mCameraPreviewHeight);
        mRenderThread.start();
        mRenderThread.waitUntilReady();
        RenderHandler handler = mRenderThread.getHandler();
        handler.sendZoomValue(tfcZoomSeekbar.getProgress());
        handler.sendSizeValue(tfcSizeSeekbar.getProgress());
        handler.sendRotateValue(tfcRotateSeekbar.getProgress());
        handler.setRecordMethod(1);
        if (sSurfaceHolder != null) {
            handler.sendSurfaceAvailable(sSurfaceHolder, false);
        } else {
            Log.d(TAG, "没有……");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        releaseCamera();
       /* cameraOnTextureSurfaceView.queueEvent(new Runnable() {
            @Override public void run() {
                // Tell the renderer that it's about to be paused so it can clean up.
                mRenderer.notifyPausing();
            }
        });*/
        // cameraOnTextureSurfaceView.onPause();

        RenderHandler handler = mRenderThread.getHandler();
        handler.sendShutdown();

        try {
            mRenderThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mRenderThread = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }


    private void handleSetSurfaceTexture(SurfaceTexture st) {
        st.setOnFrameAvailableListener(this);
        try {
            mCamera.setPreviewTexture(st);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mCamera.startPreview();
    }

    // Camera filters; must match up with cameraFilterNames in strings.xml
    static final int FILTER_NONE = 0;
    static final int FILTER_BLACK_WHITE = 1;
    static final int FILTER_BLUR = 2;
    static final int FILTER_SHARPEN = 3;
    static final int FILTER_EDGE_DETECT = 4;
    static final int FILTER_EMBOSS = 5;
}

