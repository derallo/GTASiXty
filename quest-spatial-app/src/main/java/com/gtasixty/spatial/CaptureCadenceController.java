package com.gtasixty.spatial;

/**
 * Cadence state machine for Capture mode frame generation.
 *
 * <p>Replaces the old fixed {@code dupGateFrames = 12} countdown (which discarded 12 real 60 fps
 * frames after a single brief duplicate hitch) with explicit 30 fps alternation locking,
 * zero-latency 60 fps single-drop extrapolation, and 60-&gt;120 fps midpoint generation.
 */
final class CaptureCadenceController {

    static final int MODE_OFF = 0;
    static final int MODE_RCAS = 1;
    static final int MODE_SUPERRES = 2;
    static final int MODE_FG60 = 3;
    static final int MODE_FG60_SR = 4;
    static final int MODE_FG90 = 5;
    static final int MODE_FG90_SR = 6;
    static final int MODE_FG120 = 7;
    static final int MODE_FG120_SR = 8;

    enum Action {
        PASSTHROUGH_REAL,
        WARP_30_PAIR,
        HOLD_30_REAL,
        EXTRAP_GAP_FILL,
        WARP_60_TO_120,
        DEFER_TO_SUBFRAME_TIMER
    }

    private int uniqueCount = 0;
    private int consecUnique = 0;
    private int consecDups = 0;
    private int altPairs = 0;
    private boolean cadenceLocked30 = false;

    void reset() {
        uniqueCount = 0;
        consecUnique = 0;
        consecDups = 0;
        altPairs = 0;
        cadenceLocked30 = false;
    }

    int getUniqueCount() {
        return uniqueCount;
    }

    boolean isCadenceLocked30() {
        return cadenceLocked30;
    }

    static boolean isFgMode(int mode) {
        return mode >= MODE_FG60;
    }

    static boolean isFg90(int mode) {
        return mode == MODE_FG90 || mode == MODE_FG90_SR;
    }

    static boolean isFg120(int mode) {
        return mode == MODE_FG120 || mode == MODE_FG120_SR;
    }

    static boolean isSuperResMode(int mode) {
        return mode == MODE_SUPERRES
                || mode == MODE_FG60_SR
                || mode == MODE_FG90_SR
                || mode == MODE_FG120_SR;
    }

    Action onInputFrame(boolean isDup, int mode, boolean subFrameTimerActive) {
        if (mode < MODE_FG60) {
            reset();
            return Action.PASSTHROUGH_REAL;
        }

        if (uniqueCount == 0) {
            uniqueCount = 1;
            consecUnique = 1;
            consecDups = 0;
            altPairs = 0;
            cadenceLocked30 = false;
            return Action.PASSTHROUGH_REAL;
        }

        if (!isDup) {
            if (consecDups == 1) {
                if (altPairs < 4) altPairs++;
                if (altPairs >= 2) {
                    cadenceLocked30 = true;
                }
            }
            consecDups = 0;
            consecUnique++;
            uniqueCount++;

            // Two consecutive unique frames immediately prove 60 fps content: unlock 30 fps
            // cadence at once instead of throwing away 12 real frames.
            if (consecUnique >= 2) {
                cadenceLocked30 = false;
                altPairs = 0;
            }

            if (cadenceLocked30 && uniqueCount >= 2) {
                return Action.WARP_30_PAIR;
            }
            if (isFg120(mode) && uniqueCount >= 2) {
                return Action.WARP_60_TO_120;
            }
            return Action.PASSTHROUGH_REAL;
        }

        consecDups++;
        consecUnique = 0;

        if (consecDups >= 3) {
            // Static menu / pause screen: exit 30 fps interpolation lock and refresh cleanly.
            cadenceLocked30 = false;
            altPairs = 0;
        }

        if (subFrameTimerActive && (isFg90(mode) || isFg120(mode))) {
            return Action.DEFER_TO_SUBFRAME_TIMER;
        }

        if (cadenceLocked30) {
            return Action.HOLD_30_REAL;
        }

        if (consecDups == 1 && uniqueCount >= 2) {
            return Action.EXTRAP_GAP_FILL;
        }

        return Action.PASSTHROUGH_REAL;
    }
}
