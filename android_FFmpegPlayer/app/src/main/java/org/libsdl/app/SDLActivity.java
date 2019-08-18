package org.libsdl.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsoluteLayout;
import android.widget.Toast;

import java.util.Arrays;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;


/**
 SDL Activity
 */
public class SDLActivity extends Activity {
    private static final String TAG = "SDL";

    // Keep track of the paused state
    public static boolean mIsPaused, mIsSurfaceReady, mHasFocus;
    public static boolean mExitCalledFromJava;

    // Main components
    protected static SDLActivity mSingleton;
    protected static SDLSurface mSurface;
    protected static View mTextEdit;
    protected static ViewGroup mLayout;
    protected static SDLJoystickHandler mJoystickHandler;

    // This is what SDL runs in. It invokes SDL_main(), eventually
    protected static Thread mSDLThread;
    // Audio
    protected static AudioTrack mAudioTrack;
    // Load the .so
    static {
        System.loadLibrary("SDL2");
        System.loadLibrary("SDL2main");
    }

    public static void initialize() {
        // The static nature of the singleton and Android quirkyness force us to initialize everything here
        // Otherwise, when exiting the app and returning to it, these variables *keep* their pre exit values
        mSingleton = null;
        mSurface = null;
        mTextEdit = null;
        mLayout = null;
        mJoystickHandler = null;
        mSDLThread = null;
        mAudioTrack = null;
        mExitCalledFromJava = false;
        mIsPaused = false;
        mIsSurfaceReady = false;
        mHasFocus = true;
    }

    // Setup
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v("SDL", "onCreate():" + mSingleton);
        super.onCreate(savedInstanceState);
        requestPerssion();
        SDLActivity.initialize();
        // So we can call stuff from static callbacks
        mSingleton = this;
        // Set up the surface
        mSurface = new SDLSurface(getApplication());
        mLayout = new AbsoluteLayout(this);
        mLayout.addView(mSurface);
        setContentView(mLayout);
    }

    public void requestPerssion() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE)
                != PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE)) {
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,}, 1);
            }
        } else {
            Log.i(TAG, "requestPerssion: ");
//
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 1) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PERMISSION_GRANTED) {
                    Toast.makeText(this, "" + "权限" + permissions[i] + "申请成功", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "" + "权限" + permissions[i] + "申请失败", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    // Events
    @Override
    protected void onPause() {
        Log.v("SDL", "onPause()");
        super.onPause();
        SDLActivity.handlePause();
    }

    @Override
    protected void onResume() {
        Log.v("SDL", "onResume()");
        super.onResume();
        SDLActivity.handleResume();
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        Log.v("SDL", "onWindowFocusChanged(): " + hasFocus);

        SDLActivity.mHasFocus = hasFocus;
        if (hasFocus) {
            SDLActivity.handleResume();
        }
    }

    @Override
    public void onLowMemory() {
        Log.v("SDL", "onLowMemory()");
        super.onLowMemory();
        SDLActivity.nativeLowMemory();
    }

    @Override
    protected void onDestroy() {
        Log.v("SDL", "onDestroy()");
        // Send a quit message to the application
        SDLActivity.mExitCalledFromJava = true;
        SDLActivity.nativeQuit();
        // Now wait for the SDL thread to quit
        if (SDLActivity.mSDLThread != null) {
            try {
                SDLActivity.mSDLThread.join();
            } catch(Exception e) {
                Log.v("SDL", "Problem stopping thread: " + e);
            }
            SDLActivity.mSDLThread = null;
            //Log.v("SDL", "Finished waiting for SDL thread");
        }
        super.onDestroy();
        // Reset everything in case the user re opens the app
        SDLActivity.initialize();
    }


    /** Called by onPause or surfaceDestroyed. Even if surfaceDestroyed
     *  is the first to be called, mIsSurfaceReady should still be set
     *  to 'true' during the call to onPause (in a usual scenario).
     */
    public static void handlePause() {
        if (!SDLActivity.mIsPaused && SDLActivity.mIsSurfaceReady) {
            SDLActivity.mIsPaused = true;
            SDLActivity.nativePause();
            mSurface.enableSensor(Sensor.TYPE_ACCELEROMETER, false);
        }
    }

    /** Called by onResume or surfaceCreated. An actual resume should be done only when the surface is ready.
     * Note: Some Android variants may send multiple surfaceChanged events, so we don't need to resume
     * every time we get one of those events, only if it comes after surfaceDestroyed
     */
    public static void handleResume() {
        if (SDLActivity.mIsPaused && SDLActivity.mIsSurfaceReady && SDLActivity.mHasFocus) {
            SDLActivity.mIsPaused = false;
            SDLActivity.nativeResume();
            mSurface.enableSensor(Sensor.TYPE_ACCELEROMETER, true);
        }
    }

    /* The native thread has finished */
    public static void handleNativeExit() {
        SDLActivity.mSDLThread = null;
        mSingleton.finish();
    }


    // Messages from the SDLMain thread
    static final int COMMAND_CHANGE_TITLE = 1;
    static final int COMMAND_UNUSED = 2;
    static final int COMMAND_TEXTEDIT_HIDE = 3;

    protected static final int COMMAND_USER = 0x8000;

    /**
     * This method is called by SDL if SDL did not handle a message itself.
     * This happens if a received message contains an unsupported command.
     * Method can be overwritten to handle Messages in a different class.
     * @param command the command of the message.
     * @param param the parameter of the message. May be null.
     * @return if the message was handled in overridden method.
     */
    protected boolean onUnhandledMessage(int command, Object param) {
        return false;
    }

    /**
     * A Handler class for Messages from native SDL applications.
     * It uses current Activities as target (e.g. for the title).
     * static to prevent implicit references to enclosing object.
     */
    protected static class SDLCommandHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Context context = getContext();
            if (context == null) {
                Log.e(TAG, "error handling message, getContext() returned null");
                return;
            }
            switch (msg.arg1) {
                case COMMAND_CHANGE_TITLE:
                    if (context instanceof Activity) {
                        ((Activity) context).setTitle((String)msg.obj);
                    } else {
                        Log.e(TAG, "error handling message, getContext() returned no Activity");
                    }
                    break;
                case COMMAND_TEXTEDIT_HIDE:
                    if (mTextEdit != null) {
                        mTextEdit.setVisibility(View.GONE);

                        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(mTextEdit.getWindowToken(), 0);
                    }
                    break;

                default:
                    if ((context instanceof SDLActivity) && !((SDLActivity) context).onUnhandledMessage(msg.arg1, msg.obj)) {
                        Log.e(TAG, "error handling message, command is " + msg.arg1);
                    }
            }
        }
    }

    // Handler for the messages
    Handler commandHandler = new SDLCommandHandler();

    // Send a message from the SDLMain thread
    boolean sendCommand(int command, Object data) {
        Message msg = commandHandler.obtainMessage();
        msg.arg1 = command;
        msg.obj = data;
        return commandHandler.sendMessage(msg);
    }

    // C functions we call
    public static native void nativeInit();
    public static native void nativeLowMemory();
    public static native void nativeQuit();
    public static native void nativePause();
    public static native void nativeResume();
    public static native void onNativeResize(int x, int y, int format);
    public static native int onNativePadDown(int device_id, int keycode);
    public static native int onNativePadUp(int device_id, int keycode);
    public static native void onNativeJoy(int device_id, int axis,
                                          float value);
    public static native void onNativeHat(int device_id, int hat_id,
                                          int x, int y);
    public static native void onNativeKeyDown(int keycode);
    public static native void onNativeKeyUp(int keycode);
    public static native void onNativeKeyboardFocusLost();
    public static native void onNativeTouch(int touchDevId, int pointerFingerId,
                                            int action, float x,
                                            float y, float p);
    public static native void onNativeAccel(float x, float y, float z);
    public static native void onNativeSurfaceChanged();
    public static native void onNativeSurfaceDestroyed();
    public static native void nativeFlipBuffers();
    public static native int nativeAddJoystick(int device_id, String name,
                                               int is_accelerometer, int nbuttons,
                                               int naxes, int nhats, int nballs);
    public static native int nativeRemoveJoystick(int device_id);

    public static void flipBuffers() {
        SDLActivity.nativeFlipBuffers();
    }


    public static Context getContext() {
        return mSingleton;
    }

    public static Surface getNativeSurface() {
        return SDLActivity.mSurface.getNativeSurface();
    }

    // Audio
    public static int audioInit(int sampleRate, boolean is16Bit, boolean isStereo, int desiredFrames) {
        int channelConfig = isStereo ? AudioFormat.CHANNEL_CONFIGURATION_STEREO : AudioFormat.CHANNEL_CONFIGURATION_MONO;
        int audioFormat = is16Bit ? AudioFormat.ENCODING_PCM_16BIT : AudioFormat.ENCODING_PCM_8BIT;
        int frameSize = (isStereo ? 2 : 1) * (is16Bit ? 2 : 1);

        Log.v("SDL", "SDL audio: wanted " + (isStereo ? "stereo" : "mono") + " " + (is16Bit ? "16-bit" : "8-bit") + " " + (sampleRate / 1000f) + "kHz, " + desiredFrames + " frames buffer");

        // Let the user pick a larger buffer if they really want -- but ye
        // gods they probably shouldn't, the minimums are horrifyingly high
        // latency already
        desiredFrames = Math.max(desiredFrames, (AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) + frameSize - 1) / frameSize);

        if (mAudioTrack == null) {
            mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                    channelConfig, audioFormat, desiredFrames * frameSize, AudioTrack.MODE_STREAM);

            // Instantiating AudioTrack can "succeed" without an exception and the track may still be invalid
            // Ref: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/media/java/android/media/AudioTrack.java
            // Ref: http://developer.android.com/reference/android/media/AudioTrack.html#getState()

            if (mAudioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e("SDL", "Failed during initialization of Audio Track");
                mAudioTrack = null;
                return -1;
            }

            mAudioTrack.play();
        }

        Log.v("SDL", "SDL audio: got " + ((mAudioTrack.getChannelCount() >= 2) ? "stereo" : "mono") + " " + ((mAudioTrack.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT) ? "16-bit" : "8-bit") + " " + (mAudioTrack.getSampleRate() / 1000f) + "kHz, " + desiredFrames + " frames buffer");
        return 0;
    }

    public static void audioWriteShortBuffer(short[] buffer) {
        for (int i = 0; i < buffer.length; ) {
            int result = mAudioTrack.write(buffer, i, buffer.length - i);
            if (result > 0) {
                i += result;
            } else if (result == 0) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    // Nom nom
                }
            } else {
                Log.w("SDL", "SDL audio: error return from write(short)");
                return;
            }
        }
    }

    public static void audioWriteByteBuffer(byte[] buffer) {
        for (int i = 0; i < buffer.length; ) {
            int result = mAudioTrack.write(buffer, i, buffer.length - i);
            if (result > 0) {
                i += result;
            } else if (result == 0) {
                try {
                    Thread.sleep(1);
                } catch(InterruptedException e) {
                    // Nom nom
                }
            } else {
                Log.w("SDL", "SDL audio: error return from write(byte)");
                return;
            }
        }
    }

    public static void audioQuit() {
        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack = null;
        }
    }

    // Input

    /**
     * @return an array which may be empty but is never null.
     */
    public static int[] inputGetInputDeviceIds(int sources) {
        int[] ids = InputDevice.getDeviceIds();
        int[] filtered = new int[ids.length];
        int used = 0;
        for (int i = 0; i < ids.length; ++i) {
            InputDevice device = InputDevice.getDevice(ids[i]);
            if ((device != null) && ((device.getSources() & sources) != 0)) {
                filtered[used++] = device.getId();
            }
        }
        return Arrays.copyOf(filtered, used);
    }

    // Joystick glue code, just a series of stubs that redirect to the SDLJoystickHandler instance
    public static boolean handleJoystickMotionEvent(MotionEvent event) {
        return mJoystickHandler.handleMotionEvent(event);
    }

    public static void pollInputDevices() {
        if (SDLActivity.mSDLThread != null) {
            mJoystickHandler.pollInputDevices();
        }
    }

}

/**
 Simple nativeInit() runnable
 */
class SDLMain implements Runnable {
    @Override
    public void run() {
        // Runs SDL_main()
        Log.i("xiaohan", "run: start ");
        SDLActivity.nativeInit();
    }
}

/* A null joystick handler for API level < 12 devices (the accelerometer is handled separately) */
class SDLJoystickHandler {
    public boolean handleMotionEvent(MotionEvent event) {
        return false;
    }
    public void pollInputDevices() {
    }
}



