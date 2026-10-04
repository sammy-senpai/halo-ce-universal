package com.halo.decomp;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.libsdl.app.SDLActivity;

/**
 * The game: SDL3's activity, running libmain.so (port/android/host), which
 * loads the game image from the APK's assets.
 */
public class HaloActivity extends SDLActivity {
    /** lets system link's broadcasts in over Wi-Fi while the game runs */
    private WifiManager.MulticastLock multicastLock;
    /** the on-screen controls; hidden while a game controller is in use */
    private TouchControls touchControls;
    /** the card shown over the game while the controls are edited */
    private TouchSettingsPanel touchPanel;

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferHighestRefreshRate();
        acquireMulticastLock();

        touchControls = new TouchControls(this);
        addContentView(touchControls, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        touchPanel = new TouchSettingsPanel(this, touchControls);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
            (int) (330 * getResources().getDisplayMetrics().density),
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        panelParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        addContentView(touchPanel, panelParams);
        
        // a new version looked for while the game starts
        Updater.start(this);
    }

    @Override
    protected void onDestroy() {
        if (multicastLock != null && multicastLock.isHeld())
            multicastLock.release();
        multicastLock = null;
        super.onDestroy();
    }

    /*
     * The on-screen controls give way to a game controller: they hide when
     * one is used (a button, or a stick or trigger pushed well off center,
     * so that a worn stick's drift does not count), and come back at the
     * next touch of the screen.
     */

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (touchControls != null) {
            // the back gesture or button leaves the control editor, not the game
            if (touchControls.isEditMode() && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP)
                    touchControls.setEditMode(false);
                return true;
            }
            if (KeyEvent.isGamepadButton(event.getKeyCode()))
                touchControls.setHidden(true);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.getActionMasked() == MotionEvent.ACTION_MOVE &&
            (Math.abs(event.getAxisValue(MotionEvent.AXIS_X)) > 0.6f ||
             Math.abs(event.getAxisValue(MotionEvent.AXIS_Y)) > 0.6f ||
             Math.abs(event.getAxisValue(MotionEvent.AXIS_Z)) > 0.6f ||
             Math.abs(event.getAxisValue(MotionEvent.AXIS_RZ)) > 0.6f ||
             event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f ||
             event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f)) {
            if (touchControls != null)
                touchControls.setHidden(true);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN &&
            event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            if (touchControls != null)
                touchControls.setHidden(false);
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * Many phones drop the Wi-Fi's broadcast and multicast datagrams to
     * save power unless an app holds this: without it they would not see
     * system link games on the local network, nor be seen hosting one.
     */
    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null)
                return;
            multicastLock = wifi.createMulticastLock("halo-system-link");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (RuntimeException e) {
            // (no Wi-Fi, or not allowed: the local network may miss games)
            multicastLock = null;
        }
    }

    /**
     * The game draws a frame at every display refresh, between its 30 Hz
     * ticks (port/linux/game/render_interpolation.c); Android otherwise
     * often keeps an app at 60 Hz on a faster display.
     */
    private void preferHighestRefreshRate() {
        Display display = getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode();
        Display.Mode best = current;

        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth() &&
                mode.getPhysicalHeight() == current.getPhysicalHeight() &&
                mode.getRefreshRate() > best.getRefreshRate()) {
                best = mode;
            }
        }
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.preferredDisplayModeId = best.getModeId();
        getWindow().setAttributes(attributes);
    }
}
