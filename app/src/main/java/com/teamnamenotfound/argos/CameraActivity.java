/* Copyright 2019 TeamNameNotFound

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package com.teamnamenotfound.argos;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Toast;
import java.nio.ByteBuffer;
import java.util.Locale;

import com.teamnamenotfound.argos.env.Logger;
import com.teamnamenotfound.argos.env.ImageUtils;

public abstract class CameraActivity extends Activity implements OnImageAvailableListener, Camera.
        PreviewCallback {
  private static final Logger LOGGER = new Logger();

  private static final int PERMISSIONS_REQUEST = 1;

  private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
  private static final String PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;

  private boolean debug = false;
  // Is double tap event processing?
  private boolean processing;

  private Handler handler;
  private HandlerThread handlerThread;
  private boolean useCamera2API;
  protected Bitmap rgbFrameBitmap = null;
  private int[] rgbBytes = null;
  protected int previewWidth = 0;
  protected int previewHeight = 0;
  protected Bitmap croppedBitmap = null;
  protected static final boolean SAVE_PREVIEW_BITMAP = false;
  protected long lastProcessingTimeMs;
  protected Bitmap cropCopyBitmap;
  protected ResultsView resultsView;
  protected boolean computing = false;
  protected Runnable postInferenceCallback;
  protected byte[][] yuvBytes=new byte[3][];
  protected int yRowStride;
  protected TextToSpeech mTTS;
  protected Vibrator mVibrator;
  protected MyGestureListener mgListener;
  protected GestureDetector mDetector;

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    LOGGER.d("onCreate " + this);
    super.onCreate(null);

    setContentView(R.layout.activity_camera);

    mgListener = new MyGestureListener();
    mDetector = new GestureDetector(this, mgListener);

    if (hasPermission()) {
      setFragment();
    } else {
      requestPermission();
    }
  }

  /**
   * Callback for android.hardware.Camera API
   */
  @Override
  public void onPreviewFrame(final byte[] bytes, final Camera camera) {
    if (computing) {
      return;
    }
    computing = true;
    yuvBytes[0] = bytes;
    try {
      // Initialize the storage bitmaps once when the resolution is known.
      if (rgbBytes == null) {
        Camera.Size previewSize = camera.getParameters().getPreviewSize();
        previewHeight = previewSize.height;
        previewWidth = previewSize.width;
        rgbBytes = new int[previewWidth * previewHeight];
        onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 90);
      }
      ImageUtils.convertYUV420SPToARGB8888(bytes, rgbBytes, previewWidth, previewHeight);
    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      return;
    }
    postInferenceCallback = new Runnable() {
      @Override
      public void run() {
        camera.addCallbackBuffer(bytes);
      }
    };
    processImageRGBbytes(rgbBytes);
  }

  /**
   * Callback for Camera2 API
   */
  @Override
  public void onImageAvailable(final ImageReader reader) {
    Image image = null;
    //We need wait until we have some size from onPreviewSizeChosen
    if (previewWidth == 0 || previewHeight == 0) {
      return;
    }
    rgbBytes = new int[previewWidth * previewHeight];
    try {
      image = reader.acquireLatestImage();

      if (image == null) {
        return;
      }

      if (computing) {
        image.close();
        return;
      }
      computing = true;
      Trace.beginSection("imageAvailable");
      final Plane[] planes = image.getPlanes();
      fillBytes(planes, yuvBytes);
      yRowStride = planes[0].getRowStride();
      final int uvRowStride = planes[1].getRowStride();
      final int uvPixelStride = planes[1].getPixelStride();
      ImageUtils.convertYUV420ToARGB8888(
          yuvBytes[0],
          yuvBytes[1],
          yuvBytes[2],
          previewWidth,
          previewHeight,
          yRowStride,
          uvRowStride,
          uvPixelStride,
          rgbBytes);
      image.close();

    } catch (final Exception e) {
      if (image != null) {
        image.close();
      }
      LOGGER.e(e, "Exception!");
      Trace.endSection();
      return;
    }
    processImageRGBbytes(rgbBytes);
    Trace.endSection();
  }

  @Override
  public synchronized void onStart() {
    LOGGER.d("onStart " + this);
    super.onStart();
    initTTS();
  }

  @Override
  public synchronized void onResume() {
    LOGGER.d("onResume " + this);
    super.onResume();

    handlerThread = new HandlerThread("inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
    // Keep screen always on
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    // Get Vibrator
    mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    processing = false;
  }

  @Override
  public synchronized void onPause() {
    LOGGER.d("onPause " + this);

    if (!isFinishing()) {
      LOGGER.d("Requesting finish");
      finish();
    }

    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (final InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }
    closeVibrator();
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    processing = false;
    super.onPause();
  }

  @Override
  public synchronized void onStop() {
    LOGGER.d("onStop " + this);
    super.onStop();
  }

  @Override
  public synchronized void onDestroy() {
    LOGGER.d("onDestroy " + this);
    super.onDestroy();
    closeTTS();
  }

  public boolean onTouchEvent(MotionEvent event) {
    return mDetector.onTouchEvent(event);
  }

  // Customized GestureListener class
  private class MyGestureListener extends GestureDetector.SimpleOnGestureListener {

    @Override
    public boolean onDoubleTap(MotionEvent event) {
      if(!processing) {
        processing = true;
        Toast.makeText(getApplicationContext(), "Double Tap", Toast.LENGTH_SHORT).show();
        // It will get the next frame/photo and analysis the photo
        postInferenceCallback.run();
      }
      processing = false;
      return true;
    }
  }
  private void initTTS(){
    mTTS = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
      @Override
      public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
          Log.d("TTS","Creating TTS");
          // Set pitch and speed
          mTTS.setPitch(1.0f);
          mTTS.setSpeechRate(0.9f);

          // Check if the cellphone supports English
          int result = mTTS.setLanguage(Locale.US);
          boolean TTSLang= (result == TextToSpeech.LANG_MISSING_DATA ||
                  result == TextToSpeech.LANG_NOT_SUPPORTED);
          if(TTSLang){
            Log.e("TTS", "English isn't supported" );
          }
        }else{
          Log.e("TTS", "Cann't create TestToSpeech object");
        }
      }
    });
  }

  /**
   * Release TextToSpeech
   */
  private void closeTTS(){
    if (mTTS != null) {
      mTTS.stop();
      mTTS.shutdown();
    }
  }

  /**
   * Closes the vibrator
   */
  private void closeVibrator(){
    if (mVibrator != null) {
      mVibrator.cancel();
      mVibrator = null;
    }
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }

  @Override
  public void onRequestPermissionsResult(
      final int requestCode, final String[] permissions, final int[] grantResults) {
    switch (requestCode) {
      case PERMISSIONS_REQUEST: {
        if (grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
            && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
          setFragment();
        } else {
          requestPermission();
        }
      }
    }
  }

  private boolean hasPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED &&
          checkSelfPermission(PERMISSION_STORAGE) == PackageManager.PERMISSION_GRANTED;
    } else {
      return true;
    }
  }

  private void requestPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA) ||
          shouldShowRequestPermissionRationale(PERMISSION_STORAGE)) {
        Toast.makeText(CameraActivity.this,
            "Camera AND storage permission are required for this demo", Toast.LENGTH_LONG).show();
      }
      requestPermissions(new String[] {PERMISSION_CAMERA, PERMISSION_STORAGE}, PERMISSIONS_REQUEST);
    }
  }

  // Returns true if the device supports the required hardware level, or better.
  boolean isHardwareLevelSupported(CameraCharacteristics characteristics, int requiredLevel) {
    int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
    if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
      return requiredLevel == deviceLevel;
    }
    // deviceLevel is not LEGACY, can use numerical sort
    return requiredLevel <= deviceLevel;
  }

  private String chooseCamera() {
    final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    try {
      for (final String cameraId : manager.getCameraIdList()) {
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        // We don't use a front facing camera in this sample.
        final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          continue;
        }

        final StreamConfigurationMap map =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (map == null) {
          continue;
        }
        useCamera2API = false;
                //isHardwareLevelSupported(characteristics,
            //CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
        LOGGER.i("Camera API lv2?: %s", useCamera2API);
        return cameraId;
      }
    } catch (CameraAccessException e) {
      LOGGER.e(e, "Not allowed to access camera");
    }

    return null;
  }

  protected void setFragment() {
    String cameraId = chooseCamera();

    Fragment fragment;
    if (useCamera2API) {
      CameraConnectionFragment camera2Fragment =
          CameraConnectionFragment.newInstance(
              new CameraConnectionFragment.ConnectionCallback() {
                @Override
                public void onPreviewSizeChosen(final Size size, final int rotation) {
                  previewHeight = size.getHeight();
                  previewWidth = size.getWidth();
                  CameraActivity.this.onPreviewSizeChosen(size, rotation);
                }
              },
              this,
              getLayoutId(),
              getDesiredPreviewFrameSize());

      camera2Fragment.setCamera(cameraId);
      fragment = camera2Fragment;
    } else {
      fragment = new LegacyCameraConnectionFragment(this, getLayoutId());
    }

    getFragmentManager()
        .beginTransaction()
        .replace(R.id.container, fragment)
        .commit();
  }

  protected void fillBytes(final Plane[] planes, final byte[][] yuvBytes) {
    // Because of the variable row stride it's not possible to know in
    // advance the actual necessary dimensions of the yuv planes.
    for (int i = 0; i < planes.length; ++i) {
      final ByteBuffer buffer = planes[i].getBuffer();
      if (yuvBytes[i] == null) {
        LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity());
        yuvBytes[i] = new byte[buffer.capacity()];
      }
      buffer.get(yuvBytes[i]);
    }
  }

  public boolean isDebug() {
    return debug;
  }

  public void requestRender() {
    final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
    if (overlay != null) {
      overlay.postInvalidate();
    }
  }

  public void addCallback(final OverlayView.DrawCallback callback) {
    final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
    if (overlay != null) {
      overlay.addCallback(callback);
    }
  }

  public void onSetDebug(final boolean debug) {}

  @Override
  public boolean onKeyDown(final int keyCode, final KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
      debug = !debug;
      requestRender();
      onSetDebug(debug);
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  protected abstract void processImageRGBbytes(int[] rgbBytes ) ;
  protected abstract void onPreviewSizeChosen(final Size size, final int rotation);
  protected abstract int getLayoutId();
  protected abstract Size getDesiredPreviewFrameSize();
}
