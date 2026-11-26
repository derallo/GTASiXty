package com.gtasixty.spatial;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Thread-safe snapshot of the most recent physical gamepad input seen by the Spatial host activity.
 *
 * <p>In the primary play mode the Xbox pad is paired <em>directly to the Xbox</em> and the Quest only
 * sees the Elgato capture feed, so this snapshot is usually empty. {@link GtaHandsControllerSystem}
 * therefore drives the virtual sticks/triggers/buttons purely from Quest 3 hand tracking, and only
 * layers this data on top when {@link #hasRecentInput(long)} says a real pad is talking to us
 * (Direct / PC relay modes).
 */
public final class GamepadVisualState {

    private static final float STICK_DEADZONE = 0.12f;

    private static volatile float lx, ly, rx, ry;
    private static volatile float lt, rt;
    private static volatile boolean btnA, btnB, btnX, btnY;
    private static volatile boolean btnLb, btnRb;
    private static volatile boolean dpadUp, dpadDown, dpadLeft, dpadRight;
    private static volatile boolean btnView, btnMenu, btnGuide;
    private static volatile long lastInputUptimeMs = 0L;

    private GamepadVisualState() {}

    public static void reset() {
        lx = ly = rx = ry = 0f;
        lt = rt = 0f;
        btnA = btnB = btnX = btnY = false;
        btnLb = btnRb = false;
        dpadUp = dpadDown = dpadLeft = dpadRight = false;
        btnView = btnMenu = btnGuide = false;
        lastInputUptimeMs = 0L;
    }

    /** True when a physical pad sent an event within {@code windowMs}. */
    public static boolean hasRecentInput(long windowMs) {
        long last = lastInputUptimeMs;
        return last != 0L && (SystemClock.uptimeMillis() - last) < windowMs;
    }

    public static long lastInputUptimeMs() {
        return lastInputUptimeMs;
    }

    /** @return true when the event looked like it came from a gamepad and was consumed. */
    public static boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;
        if ((event.getSource() & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
                && (event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
            return false;
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0;
        boolean handled = true;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A: btnA = down; break;
            case KeyEvent.KEYCODE_BUTTON_B: btnB = down; break;
            case KeyEvent.KEYCODE_BUTTON_X: btnX = down; break;
            case KeyEvent.KEYCODE_BUTTON_Y: btnY = down; break;
            case KeyEvent.KEYCODE_BUTTON_L1: btnLb = down; break;
            case KeyEvent.KEYCODE_BUTTON_R1: btnRb = down; break;
            case KeyEvent.KEYCODE_BUTTON_L2: lt = down ? 1f : 0f; break;
            case KeyEvent.KEYCODE_BUTTON_R2: rt = down ? 1f : 0f; break;
            case KeyEvent.KEYCODE_BUTTON_START: btnMenu = down; break;
            case KeyEvent.KEYCODE_BUTTON_SELECT: btnView = down; break;
            case KeyEvent.KEYCODE_BUTTON_MODE: btnGuide = down; break;
            case KeyEvent.KEYCODE_DPAD_UP: dpadUp = down; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dpadDown = down; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: dpadLeft = down; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dpadRight = down; break;
            default: handled = false; break;
        }
        if (handled) {
            lastInputUptimeMs = SystemClock.uptimeMillis();
        }
        return handled;
    }

    /** @return true when the event looked like it came from a gamepad and was consumed. */
    public static boolean onMotionEvent(MotionEvent event) {
        if (event == null) return false;
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
            return false;
        }
        lx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X));
        ly = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y));
        rx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Z));
        ry = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_RZ));

        float lTrigger = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        if (lTrigger == 0f) lTrigger = event.getAxisValue(MotionEvent.AXIS_BRAKE);
        float rTrigger = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        if (rTrigger == 0f) rTrigger = event.getAxisValue(MotionEvent.AXIS_GAS);
        lt = clamp01(lTrigger);
        rt = clamp01(rTrigger);

        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        dpadLeft = hatX < -0.5f;
        dpadRight = hatX > 0.5f;
        dpadUp = hatY < -0.5f;
        dpadDown = hatY > 0.5f;

        lastInputUptimeMs = SystemClock.uptimeMillis();
        return true;
    }

    private static float applyDeadzone(float value) {
        if (Float.isNaN(value)) return 0f;
        if (Math.abs(value) < STICK_DEADZONE) return 0f;
        float sign = value < 0f ? -1f : 1f;
        return sign * (Math.abs(value) - STICK_DEADZONE) / (1f - STICK_DEADZONE);
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return Math.max(0f, Math.min(1f, value));
    }

    public static float leftStickX() { return lx; }
    public static float leftStickY() { return ly; }
    public static float rightStickX() { return rx; }
    public static float rightStickY() { return ry; }
    public static float leftTrigger() { return lt; }
    public static float rightTrigger() { return rt; }
    public static boolean buttonA() { return btnA; }
    public static boolean buttonB() { return btnB; }
    public static boolean buttonX() { return btnX; }
    public static boolean buttonY() { return btnY; }
    public static boolean bumperL() { return btnLb; }
    public static boolean bumperR() { return btnRb; }
    public static boolean dpadUp() { return dpadUp; }
    public static boolean dpadDown() { return dpadDown; }
    public static boolean dpadLeft() { return dpadLeft; }
    public static boolean dpadRight() { return dpadRight; }
    public static boolean guide() { return btnGuide; }
}
