package com.gtasixty.spatial;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared state and bridge between the Meta Spatial SDK host ({@link SpatialMainActivity}) and the
 * embedded 2D panel UI ({@link MainActivity} / {@link DirectController} / {@link DisplayControlsView}).
 *
 * <p>Persists curve, tilt, vertical height offset, locked-16:9 width, room depth, backdrop mode,
 * supersampled panel resolution, foliage TAA, and GLES 3.1 compute-flow toggles in
 * {@code SharedPreferences("gta60_spatial")}, synchronizes corner-drag resizes back to the UI,
 * forwards launch extras, and routes hardware gamepad events.
 */
public final class SpatialDisplayBridge {

    static final int BACKDROP_PASSTHROUGH = 0;
    static final int BACKDROP_DIM = 1;
    static final int BACKDROP_DARK = 2;
    static final int BACKDROP_JIMMYS_ROOM = 3;
    static final int BACKDROP_COUNT = 4;

    static final int PANEL_SCALE_1440P = CaptureProtocolHelper.PANEL_SCALE_1440P;
    static final int PANEL_SCALE_1800P = CaptureProtocolHelper.PANEL_SCALE_1800P;
    static final int PANEL_SCALE_2160P = CaptureProtocolHelper.PANEL_SCALE_2160P;
    static final int DEFAULT_PANEL_SCALE = CaptureProtocolHelper.DEFAULT_PANEL_SCALE;

    static final float ASPECT_RATIO = 16.0f / 9.0f;
    static final float MIN_WIDTH_M = 1.0f;
    static final float MAX_WIDTH_M = 4.5f;
    static final float DEFAULT_WIDTH_M = 2.2f;

    static final float MIN_DIST_M = 1.0f;
    static final float MAX_DIST_M = 5.0f;
    static final float DEFAULT_DIST_M = 2.2f;

    static final float MIN_TILT_DEG = -45.0f;
    static final float MAX_TILT_DEG = 45.0f;
    static final float DEFAULT_TILT_DEG = 0.0f;

    static final float MIN_HEIGHT_M = -0.60f;
    static final float MAX_HEIGHT_M = 0.80f;
    static final float DEFAULT_HEIGHT_M = 0.0f;

    static final int MIN_CURVE_PCT = 0;
    static final int MAX_CURVE_PCT = 100;
    static final int DEFAULT_CURVE_PCT = 0;

    static final int DEFAULT_BACKDROP = BACKDROP_PASSTHROUGH;

    private static final String PREFS_NAME = "gta60_spatial";
    private static final String KEY_CURVE_PCT = "spatialCurvePct";
    private static final String KEY_TILT_DEG = "spatialTiltDeg";
    private static final String KEY_HEIGHT_M = "spatialHeightM";
    private static final String KEY_WIDTH_M = "spatialWidthM";
    private static final String KEY_DIST_M = "spatialDistanceM";
    private static final String KEY_BACKDROP = "spatialBackdrop";
    private static final String KEY_PANEL_SCALE = "spatialPanelScale";
    private static final String KEY_TAA_ENABLED = "spatialTaaEnabled";
    private static final String KEY_COMPUTE_FLOW = "spatialComputeFlow";
    private static final String KEY_GTA_HANDS = "spatialGtaHandsEnabled";
    private static final String KEY_GTA_HANDS_CTRL_UP = "spatialGtaHandsCtrlUpM";
    private static final String KEY_GTA_HANDS_CTRL_BACK = "spatialGtaHandsCtrlBackM";

    static int panelPxW(int panelScale) {
        return CaptureProtocolHelper.panelPxW(panelScale);
    }

    static int panelPxH(int panelScale) {
        return CaptureProtocolHelper.panelPxH(panelScale);
    }

    static int panelDpi(int panelScale) {
        return CaptureProtocolHelper.panelDpi(panelScale);
    }

    static String panelScaleLabel(int panelScale) {
        return CaptureProtocolHelper.panelScaleLabel(panelScale);
    }

    static float cylinderArcDegrees(int curvePct) {
        if (curvePct <= 0) return 0f;
        int clamped = Math.max(1, Math.min(MAX_CURVE_PCT, curvePct));
        return 18.0f + (clamped / 100.0f) * 92.0f;
    }

    static float cylinderRadiusMeters(float widthMeters, int curvePct) {
        if (curvePct <= 0) return 0f;
        float arcRad = (float) Math.toRadians(cylinderArcDegrees(curvePct));
        return Math.max(0.75f, widthMeters / arcRad);
    }

    public static final class DisplaySettings {
        public int curvePct;
        public float tiltDeg;
        public float heightOffsetM;
        public float widthMeters;
        public float distanceMeters;
        public int backdropMode;
        public int panelScale;
        public boolean taaEnabled;
        public boolean computeFlowEnabled;
        /** Draw GTA V hands + the auto-spawning 3D Xbox controller from Quest hand tracking. */
        public boolean gtaHandsEnabled;
        /** Fine-tune nudge for the virtual controller, in controller-local metres. */
        public float gtaHandsCtrlUpM;
        public float gtaHandsCtrlBackM;

        DisplaySettings() {
            this.curvePct = DEFAULT_CURVE_PCT;
            this.tiltDeg = DEFAULT_TILT_DEG;
            this.heightOffsetM = DEFAULT_HEIGHT_M;
            this.widthMeters = DEFAULT_WIDTH_M;
            this.distanceMeters = DEFAULT_DIST_M;
            this.backdropMode = DEFAULT_BACKDROP;
            this.panelScale = DEFAULT_PANEL_SCALE;
            this.taaEnabled = true;
            this.computeFlowEnabled = true;
            this.gtaHandsEnabled = true;
            this.gtaHandsCtrlUpM = 0.0f;
            this.gtaHandsCtrlBackM = 0.0f;
        }

        DisplaySettings(DisplaySettings other) {
            this.curvePct = other.curvePct;
            this.tiltDeg = other.tiltDeg;
            this.heightOffsetM = other.heightOffsetM;
            this.widthMeters = other.widthMeters;
            this.distanceMeters = other.distanceMeters;
            this.backdropMode = other.backdropMode;
            this.panelScale = other.panelScale;
            this.taaEnabled = other.taaEnabled;
            this.computeFlowEnabled = other.computeFlowEnabled;
            this.gtaHandsEnabled = other.gtaHandsEnabled;
            this.gtaHandsCtrlUpM = other.gtaHandsCtrlUpM;
            this.gtaHandsCtrlBackM = other.gtaHandsCtrlBackM;
            clamp();
        }

        void clamp() {
            curvePct = Math.max(MIN_CURVE_PCT, Math.min(MAX_CURVE_PCT, curvePct));
            tiltDeg = Math.max(MIN_TILT_DEG, Math.min(MAX_TILT_DEG, tiltDeg));
            heightOffsetM = Math.max(MIN_HEIGHT_M, Math.min(MAX_HEIGHT_M, heightOffsetM));
            widthMeters = Math.max(MIN_WIDTH_M, Math.min(MAX_WIDTH_M, widthMeters));
            distanceMeters = Math.max(MIN_DIST_M, Math.min(MAX_DIST_M, distanceMeters));
            if (backdropMode < BACKDROP_PASSTHROUGH || backdropMode > BACKDROP_JIMMYS_ROOM) {
                backdropMode = DEFAULT_BACKDROP;
            }
            if (panelScale < PANEL_SCALE_1440P || panelScale > PANEL_SCALE_2160P) {
                panelScale = DEFAULT_PANEL_SCALE;
            }
            gtaHandsCtrlUpM = Math.max(-0.10f, Math.min(0.10f, gtaHandsCtrlUpM));
            gtaHandsCtrlBackM = Math.max(-0.10f, Math.min(0.10f, gtaHandsCtrlBackM));
        }

        float heightMeters() {
            return widthMeters / ASPECT_RATIO;
        }

        float cylinderArcDegrees() {
            return SpatialDisplayBridge.cylinderArcDegrees(curvePct);
        }

        float cylinderRadiusMeters() {
            return SpatialDisplayBridge.cylinderRadiusMeters(widthMeters, curvePct);
        }

        String backdropLabel() {
            switch (backdropMode) {
                case BACKDROP_DIM:
                    return "Dim";
                case BACKDROP_DARK:
                    return "Dark";
                case BACKDROP_JIMMYS_ROOM:
                    return "Jimmy's Room";
                default:
                    return "Passthrough";
            }
        }
    }

    public interface SpatialListener {
        void onDisplaySettingsChanged(DisplaySettings settings);
        void onRecenterRequested();
        void onRefreshRateRequested(int hz);
    }

    public interface UiListener {
        void onDisplaySettingsChanged(DisplaySettings settings);
    }

    public interface GamepadSink {
        boolean onGamepadKey(KeyEvent event);
        boolean onGamepadMotion(MotionEvent event);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final CopyOnWriteArrayList<UiListener> UI_LISTENERS = new CopyOnWriteArrayList<>();
    private static final DisplaySettings CURRENT = new DisplaySettings();

    private static boolean loaded = false;
    private static int requestedRefreshHz = 120;
    private static Bundle pendingLaunchExtras = null;
    private static WeakReference<SpatialListener> spatialRef = new WeakReference<>(null);
    private static WeakReference<GamepadSink> gamepadRef = new WeakReference<>(null);
    private static WeakReference<Runnable> stopStreamRef = new WeakReference<>(null);

    private SpatialDisplayBridge() {}

    static DisplaySettings load(Context context) {
        synchronized (LOCK) {
            if (!loaded && context != null) {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                CURRENT.curvePct = prefs.getInt(KEY_CURVE_PCT, DEFAULT_CURVE_PCT);
                CURRENT.tiltDeg = prefs.getFloat(KEY_TILT_DEG, DEFAULT_TILT_DEG);
                CURRENT.heightOffsetM = prefs.getFloat(KEY_HEIGHT_M, DEFAULT_HEIGHT_M);
                CURRENT.widthMeters = prefs.getFloat(KEY_WIDTH_M, DEFAULT_WIDTH_M);
                CURRENT.distanceMeters = prefs.getFloat(KEY_DIST_M, DEFAULT_DIST_M);
                CURRENT.backdropMode = prefs.getInt(KEY_BACKDROP, DEFAULT_BACKDROP);
                CURRENT.panelScale = prefs.getInt(KEY_PANEL_SCALE, DEFAULT_PANEL_SCALE);
                CURRENT.taaEnabled = prefs.getBoolean(KEY_TAA_ENABLED, true);
                CURRENT.computeFlowEnabled = prefs.getBoolean(KEY_COMPUTE_FLOW, true);
                CURRENT.gtaHandsEnabled = prefs.getBoolean(KEY_GTA_HANDS, true);
                CURRENT.gtaHandsCtrlUpM = prefs.getFloat(KEY_GTA_HANDS_CTRL_UP, 0.0f);
                CURRENT.gtaHandsCtrlBackM = prefs.getFloat(KEY_GTA_HANDS_CTRL_BACK, 0.0f);
                CURRENT.clamp();
                loaded = true;
            }
            return new DisplaySettings(CURRENT);
        }
    }

    static DisplaySettings getSettings() {
        synchronized (LOCK) {
            return new DisplaySettings(CURRENT);
        }
    }

    static void applyFromUi(Context context, DisplaySettings updated) {
        final DisplaySettings copy = new DisplaySettings(updated);
        copy.clamp();
        synchronized (LOCK) {
            loaded = true;
            CURRENT.curvePct = copy.curvePct;
            CURRENT.tiltDeg = copy.tiltDeg;
            CURRENT.heightOffsetM = copy.heightOffsetM;
            CURRENT.widthMeters = copy.widthMeters;
            CURRENT.distanceMeters = copy.distanceMeters;
            CURRENT.backdropMode = copy.backdropMode;
            CURRENT.panelScale = copy.panelScale;
            CURRENT.taaEnabled = copy.taaEnabled;
            CURRENT.computeFlowEnabled = copy.computeFlowEnabled;
            CURRENT.gtaHandsEnabled = copy.gtaHandsEnabled;
            CURRENT.gtaHandsCtrlUpM = copy.gtaHandsCtrlUpM;
            CURRENT.gtaHandsCtrlBackM = copy.gtaHandsCtrlBackM;
        }
        save(context, copy);
        notifySpatial(copy);
        notifyUi(copy);
    }

    static void notifySpatialPanelResized(Context context, float newWidthMeters) {
        final DisplaySettings copy;
        synchronized (LOCK) {
            loaded = true;
            CURRENT.widthMeters = newWidthMeters;
            CURRENT.clamp();
            copy = new DisplaySettings(CURRENT);
        }
        save(context, copy);
        notifyUi(copy);
    }

    private static void save(Context context, DisplaySettings s) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_CURVE_PCT, s.curvePct)
                .putFloat(KEY_TILT_DEG, s.tiltDeg)
                .putFloat(KEY_HEIGHT_M, s.heightOffsetM)
                .putFloat(KEY_WIDTH_M, s.widthMeters)
                .putFloat(KEY_DIST_M, s.distanceMeters)
                .putInt(KEY_BACKDROP, s.backdropMode)
                .putInt(KEY_PANEL_SCALE, s.panelScale)
                .putBoolean(KEY_TAA_ENABLED, s.taaEnabled)
                .putBoolean(KEY_COMPUTE_FLOW, s.computeFlowEnabled)
                .putBoolean(KEY_GTA_HANDS, s.gtaHandsEnabled)
                .putFloat(KEY_GTA_HANDS_CTRL_UP, s.gtaHandsCtrlUpM)
                .putFloat(KEY_GTA_HANDS_CTRL_BACK, s.gtaHandsCtrlBackM)
                .apply();
    }

    static void setSpatialListener(SpatialListener listener) {
        spatialRef = new WeakReference<>(listener);
        if (listener != null) {
            final DisplaySettings snap = getSettings();
            final int hz = requestedRefreshHz;
            MAIN.post(() -> {
                listener.onRefreshRateRequested(hz);
                listener.onDisplaySettingsChanged(snap);
            });
        }
    }

    static void addUiListener(UiListener listener) {
        if (listener != null && !UI_LISTENERS.contains(listener)) {
            UI_LISTENERS.add(listener);
            listener.onDisplaySettingsChanged(getSettings());
        }
    }

    static void removeUiListener(UiListener listener) {
        UI_LISTENERS.remove(listener);
    }

    static void requestRecenter() {
        final SpatialListener s = spatialRef.get();
        if (s != null) {
            MAIN.post(s::onRecenterRequested);
        }
    }

    static void requestRefreshRate(int hz) {
        final int clamped = (hz == 90) ? 90 : 120;
        requestedRefreshHz = clamped;
        final SpatialListener s = spatialRef.get();
        if (s != null) {
            MAIN.post(() -> s.onRefreshRateRequested(clamped));
        }
    }

    static int getRequestedRefreshHz() {
        return requestedRefreshHz;
    }

    static void setPendingLaunchExtras(Bundle extras) {
        synchronized (LOCK) {
            pendingLaunchExtras = extras != null ? new Bundle(extras) : null;
        }
    }

    static Bundle consumePendingLaunchExtras() {
        synchronized (LOCK) {
            Bundle out = pendingLaunchExtras;
            pendingLaunchExtras = null;
            return out;
        }
    }

    static void registerGamepadSink(GamepadSink sink) {
        gamepadRef = new WeakReference<>(sink);
    }

    static void registerStopStreamSink(Runnable sink) {
        stopStreamRef = new WeakReference<>(sink);
    }

    static void stopActiveStream() {
        final Runnable sink = stopStreamRef.get();
        if (sink != null) {
            MAIN.post(sink);
        }
    }

    static boolean dispatchGamepadKey(KeyEvent event) {
        final GamepadSink sink = gamepadRef.get();
        return sink != null && sink.onGamepadKey(event);
    }

    static boolean dispatchGamepadMotion(MotionEvent event) {
        final GamepadSink sink = gamepadRef.get();
        return sink != null && sink.onGamepadMotion(event);
    }

    private static void notifySpatial(DisplaySettings snap) {
        final SpatialListener s = spatialRef.get();
        if (s != null) {
            MAIN.post(() -> s.onDisplaySettingsChanged(snap));
        }
    }

    private static void notifyUi(DisplaySettings snap) {
        MAIN.post(() -> {
            for (UiListener u : UI_LISTENERS) {
                u.onDisplaySettingsChanged(snap);
            }
        });
    }
}
