package com.gtasixty.spatial;

/**
 * Pure-JVM Android gamepad -> PlayStation ChiakiControllerState mapper.
 *
 * <p>Matches {@code chiaki/controller.h} bitmasks and {@code chiaki-ng}'s Android {@code StreamInput}
 * axis conventions (sticks are NOT Y-inverted: down on Android is positive and down in Chiaki is positive).
 */
public final class PsGamepadMapper {

    // ChiakiControllerButton bitmasks (from lib/include/chiaki/controller.h)
    public static final int BUTTON_CROSS      = 1 << 0;
    public static final int BUTTON_MOON       = 1 << 1; // Circle
    public static final int BUTTON_BOX        = 1 << 2; // Square
    public static final int BUTTON_PYRAMID    = 1 << 3; // Triangle
    public static final int BUTTON_DPAD_LEFT  = 1 << 4;
    public static final int BUTTON_DPAD_RIGHT = 1 << 5;
    public static final int BUTTON_DPAD_UP    = 1 << 6;
    public static final int BUTTON_DPAD_DOWN  = 1 << 7;
    public static final int BUTTON_L1         = 1 << 8;
    public static final int BUTTON_R1         = 1 << 9;
    public static final int BUTTON_L3         = 1 << 10;
    public static final int BUTTON_R3         = 1 << 11;
    public static final int BUTTON_OPTIONS    = 1 << 12;
    public static final int BUTTON_SHARE      = 1 << 13;
    public static final int BUTTON_TOUCHPAD   = 1 << 14;
    public static final int BUTTON_PS         = 1 << 15;

    // Android KeyEvent.KEYCODE_* constants duplicated here so unit tests run on pure JVM.
    public static final int KEYCODE_DPAD_UP       = 19;
    public static final int KEYCODE_DPAD_DOWN     = 20;
    public static final int KEYCODE_DPAD_LEFT     = 21;
    public static final int KEYCODE_DPAD_RIGHT    = 22;
    public static final int KEYCODE_BUTTON_A      = 96;
    public static final int KEYCODE_BUTTON_B      = 97;
    public static final int KEYCODE_BUTTON_C      = 98;
    public static final int KEYCODE_BUTTON_X      = 99;
    public static final int KEYCODE_BUTTON_Y      = 100;
    public static final int KEYCODE_BUTTON_Z      = 101;
    public static final int KEYCODE_BUTTON_L1     = 102;
    public static final int KEYCODE_BUTTON_R1     = 103;
    public static final int KEYCODE_BUTTON_L2     = 104;
    public static final int KEYCODE_BUTTON_R2     = 105;
    public static final int KEYCODE_BUTTON_THUMBL = 106;
    public static final int KEYCODE_BUTTON_THUMBR = 107;
    public static final int KEYCODE_BUTTON_START  = 108;
    public static final int KEYCODE_BUTTON_SELECT = 109;
    public static final int KEYCODE_BUTTON_MODE   = 110;
    public static final int KEYCODE_BUTTON_1      = 188;

    private int buttons;
    private int keyL2;
    private int keyR2;
    private int axisL2;
    private int axisR2;
    private short lx;
    private short ly;
    private short rx;
    private short ry;
    private boolean touchDown;
    private int touchX = 960;
    private int touchY = 470;
    private boolean dirty;

    public static int mapKeyCode(int keyCode) {
        switch (keyCode) {
            case KEYCODE_BUTTON_A:
                return BUTTON_CROSS;
            case KEYCODE_BUTTON_B:
                return BUTTON_MOON;
            case KEYCODE_BUTTON_X:
                return BUTTON_BOX;
            case KEYCODE_BUTTON_Y:
                return BUTTON_PYRAMID;
            case KEYCODE_DPAD_LEFT:
                return BUTTON_DPAD_LEFT;
            case KEYCODE_DPAD_RIGHT:
                return BUTTON_DPAD_RIGHT;
            case KEYCODE_DPAD_UP:
                return BUTTON_DPAD_UP;
            case KEYCODE_DPAD_DOWN:
                return BUTTON_DPAD_DOWN;
            case KEYCODE_BUTTON_L1:
                return BUTTON_L1;
            case KEYCODE_BUTTON_R1:
                return BUTTON_R1;
            case KEYCODE_BUTTON_THUMBL:
                return BUTTON_L3;
            case KEYCODE_BUTTON_THUMBR:
                return BUTTON_R3;
            case KEYCODE_BUTTON_START:
                return BUTTON_OPTIONS;
            case KEYCODE_BUTTON_SELECT:
                return BUTTON_SHARE;
            case KEYCODE_BUTTON_MODE:
            case KEYCODE_BUTTON_C:
                return BUTTON_PS;
            case KEYCODE_BUTTON_Z:
            case KEYCODE_BUTTON_1:
                return BUTTON_TOUCHPAD;
            default:
                return 0;
        }
    }

    public boolean applyKey(int keyCode, boolean down) {
        if (keyCode == KEYCODE_BUTTON_L2) {
            final int next = down ? 255 : 0;
            if (keyL2 != next) {
                keyL2 = next;
                dirty = true;
            }
            return true;
        }
        if (keyCode == KEYCODE_BUTTON_R2) {
            final int next = down ? 255 : 0;
            if (keyR2 != next) {
                keyR2 = next;
                dirty = true;
            }
            return true;
        }
        final int mask = mapKeyCode(keyCode);
        if (mask == 0) return false;
        final int nextButtons = down ? (buttons | mask) : (buttons & ~mask);
        if (nextButtons != buttons) {
            buttons = nextButtons;
            dirty = true;
        }
        return true;
    }

    public void applyAxes(
            float rawLx,
            float rawLy,
            float rawRx,
            float rawRy,
            float lTrigger,
            float rTrigger,
            float brake,
            float gas,
            float hatX,
            float hatY) {
        final short nextLx = signedAxis(rawLx);
        final short nextLy = signedAxis(rawLy);
        final short nextRx = signedAxis(rawRx);
        final short nextRy = signedAxis(rawRy);
        final int nextL2 = unsignedByte(Math.max(lTrigger, brake));
        final int nextR2 = unsignedByte(Math.max(rTrigger, gas));

        final int dpadMask =
                (hatX < -0.5f ? BUTTON_DPAD_LEFT : 0)
                | (hatX > 0.5f ? BUTTON_DPAD_RIGHT : 0)
                | (hatY < -0.5f ? BUTTON_DPAD_UP : 0)
                | (hatY > 0.5f ? BUTTON_DPAD_DOWN : 0);
        final int allDpad = BUTTON_DPAD_LEFT | BUTTON_DPAD_RIGHT | BUTTON_DPAD_UP | BUTTON_DPAD_DOWN;
        final int nextButtons = (buttons & ~allDpad) | dpadMask;

        if (nextLx != lx || nextLy != ly || nextRx != rx || nextRy != ry
                || nextL2 != axisL2 || nextR2 != axisR2 || nextButtons != buttons) {
            lx = nextLx;
            ly = nextLy;
            rx = nextRx;
            ry = nextRy;
            axisL2 = nextL2;
            axisR2 = nextR2;
            buttons = nextButtons;
            dirty = true;
        }
    }

    public void setVirtualButton(int buttonMask, boolean down) {
        final int next = down ? (buttons | buttonMask) : (buttons & ~buttonMask);
        if (next != buttons) {
            buttons = next;
            dirty = true;
        }
    }

    public void setTouch(boolean down, int x, int y) {
        if (touchDown != down || touchX != x || touchY != y) {
            touchDown = down;
            touchX = x;
            touchY = y;
            dirty = true;
        }
    }

    public void reset() {
        buttons = 0;
        keyL2 = 0;
        keyR2 = 0;
        axisL2 = 0;
        axisR2 = 0;
        lx = 0;
        ly = 0;
        rx = 0;
        ry = 0;
        touchDown = false;
        dirty = false;
    }

    public int getButtons() {
        return buttons;
    }

    public int getL2() {
        return axisL2 > 0 ? axisL2 : keyL2;
    }

    public int getR2() {
        return axisR2 > 0 ? axisR2 : keyR2;
    }

    public short getLx() {
        return lx;
    }

    public short getLy() {
        return ly;
    }

    public short getRx() {
        return rx;
    }

    public short getRy() {
        return ry;
    }

    public boolean isTouchDown() {
        return touchDown;
    }

    public int getTouchX() {
        return touchX;
    }

    public int getTouchY() {
        return touchY;
    }

    public boolean consumeDirty() {
        final boolean was = dirty;
        dirty = false;
        return was;
    }

    private static short signedAxis(float v) {
        final float clamped = Math.max(-1f, Math.min(1f, v));
        return (short) Math.round(clamped * 32767f);
    }

    private static int unsignedByte(float v) {
        final float clamped = Math.max(0f, Math.min(1f, v));
        return Math.round(clamped * 255f);
    }
}
