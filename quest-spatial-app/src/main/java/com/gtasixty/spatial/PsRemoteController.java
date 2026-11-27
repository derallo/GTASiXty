package com.gtasixty.spatial;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.gtasixty.spatial.voice.JimmyVoiceModule;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PlayStation 5 / PlayStation 4 Remote Play controller powered by {@code libchiaki} ({@code libgtaps.so}).
 *
 * <p>Routes incoming Annex-B H.264/HEVC NAL units directly into Android hardware {@link MediaCodec}
 * -> {@link CaptureGlRenderer} (Adreno 740 OpenGL ES shaders, SuperRes, RCAS, FG60/FG90, Look) ->
 * {@link MainActivity}'s {@link SurfaceView} -> Horizon OS OpenXR Quad/Cylinder swapchain layer.
 */
final class PsRemoteController implements PsChiakiNative.SessionCallback {

    private static final String TAG = "GTA60-PS";

    private static final int[] RES_WIDTHS = {1920, 1280, 960};
    private static final int[] RES_HEIGHTS = {1080, 720, 540};
    private static final String[] RES_LABELS = {"1080p", "720p", "540p"};
    private static final int[] BITRATE_KBPS = {15000, 25000, 35000, 50000};
    private static final String[] BITRATE_LABELS = {"15 Mbps", "25 Mbps", "35 Mbps", "50 Mbps"};
    private static final String[] LOOK_LABELS = {"Normal", "Vivid", "Night"};

    private final Activity activity;
    private final FrameLayout root;
    private final SharedPreferences prefs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final PsGamepadMapper gamepad = new PsGamepadMapper();

    private LinearLayout bar;
    private TextView statusText;
    private TextView hoverHintText;
    private HoverHintHelper hoverHints;
    private Button platformBtn;
    private Button modeBtn;
    private Button consoleBtn;
    private Button linkBtn;
    private Button startStopBtn;
    private Button wakeBtn;
    private Button refreshBtn;
    private Button resBtn;
    private Button fpsBtn;
    private Button codecBtn;
    private Button bitrateBtn;
    private Button enhanceBtn;
    private Button lookBtn;
    private Button hzBtn;
    private Button psBtn;
    private Button touchBtn;
    private Button updateBtn;
    private CheckBox autoHideCheck;
    private boolean syncingAutoHideCheck = false;
    private String availableUpdateVersion = "";

    private volatile boolean visible = false;
    private volatile boolean streaming = false;
    private volatile boolean sessionConnected = false;
    private volatile long sessionHandle = 0L;
    private volatile PsConsoleStore.PsConsole selectedConsole;

    // Stream & Quest GPU preferences (persisted in gta60_ps)
    private int resIdx = 0;      // 1080p default
    private int cfgFps = 60;     // 60 fps default
    private int codecId = PsChiakiNative.CODEC_H265;
    private int bitrateIdx = 1;  // 25 Mbps default
    private int enhanceMode = CaptureGlRenderer.MODE_FG60_SR;
    private int lookMode = CaptureGlRenderer.LOOK_NORMAL;
    private int refreshHz = 120;

    // Video decode -> OpenGL -> OpenXR swapchain
    private final Object codecLock = new Object();
    private MediaCodec mediaCodec;
    private CaptureGlRenderer captureGl;
    private Thread decodeOutThread;
    private volatile boolean firstFrameRendered = false;
    private volatile int streamWidth = 1920;
    private volatile int streamHeight = 1080;
    private long ptsCounterUs = 0L;

    // Audio playback
    private final Object audioLock = new Object();
    private AudioTrack audioTrack;
    private int audioChannels = 2;
    private int audioRate = 48000;

    // Telemetry
    private volatile long statFrames = 0;
    private volatile long statRendered = 0;
    private volatile long statGenFrames = 0;
    private volatile long statBytes = 0;
    private long lastStatTimeMs = 0L;
    private long lastRumbleMs = 0L;
    private long lastPadSendMs = 0L;
    private volatile boolean padDirty = false;
    private volatile boolean padTickerRunning = false;

    private final SpatialDisplayBridge.UiListener spatialUiListener = settings -> {
        if (settings == null) return;
        uiHandler.post(() -> {
            if (autoHideCheck != null) {
                syncingAutoHideCheck = true;
                try {
                    if (autoHideCheck.isChecked() != settings.autoHideMenuOnController) {
                        autoHideCheck.setChecked(settings.autoHideMenuOnController);
                    }
                } finally {
                    syncingAutoHideCheck = false;
                }
            }
            final CaptureGlRenderer gl = captureGl;
            if (gl != null) {
                gl.setTaaEnabled(settings.taaEnabled);
                gl.setComputeFlowEnabled(settings.computeFlowEnabled);
            }
            final Activity act = PsRemoteController.this.activity;
            if (streaming && streamWidth > 0 && streamHeight > 0 && act instanceof MainActivity) {
                final SurfaceView sv = ((MainActivity) act).getSurfaceView();
                if (sv != null) {
                    final int[] dims = CaptureProtocolHelper.computeSurfaceDimensions(
                            streamWidth,
                            streamHeight,
                            SpatialDisplayBridge.panelPxW(settings.panelScale),
                            SpatialDisplayBridge.panelPxH(settings.panelScale));
                    sv.getHolder().setFixedSize(dims[0], dims[1]);
                    ((MainActivity) act).adjustSurfaceAspect(streamWidth, streamHeight);
                }
            }
        });
    };

    private final Runnable telemetryTick = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            final long now = System.currentTimeMillis();
            if (streaming && streamWidth > 0) {
                final double dt = (now - lastStatTimeMs) / 1000.0;
                if (dt >= 0.5) {
                    final long f = statFrames;
                    final long r = statRendered;
                    final long g = statGenFrames;
                    final long b = statBytes;
                    statFrames = 0;
                    statRendered = 0;
                    statGenFrames = 0;
                    statBytes = 0;
                    lastStatTimeMs = now;
                    final double recvFps = f / dt;
                    final double renFps = r / dt;
                    final double mbps = (b * 8.0) / (dt * 1e6);
                    final long genPct = r > 0 ? Math.round((g * 100.0) / r) : 0;
                    final String tag = (selectedConsole != null && !selectedConsole.isPs5) ? "PS4" : "PS5";
                    final String codecLabel = codecId == PsChiakiNative.CODEC_H265 ? "HEVC" : "H.264";
                    if (statusText != null) {
                        statusText.setText(String.format(Locale.US,
                                "%s %dx%d @ %.1f/%.1f fps (Gen %d%%) | %.1f Mbps | %s | GPU: %s",
                                tag, streamWidth, streamHeight, renFps, recvFps, genPct, mbps, codecLabel,
                                CaptureProtocolHelper.simpleCaptureEnhanceLabel(enhanceMode)));
                    }
                }
            }
            uiHandler.postDelayed(this, 500);
        }
    };

    private final Runnable autoHideHudTick = new Runnable() {
        @Override
        public void run() {
            if (visible && streaming && bar != null) {
                if (activity instanceof MainActivity && ((MainActivity) activity).isDisplayControlsOpen()) {
                    uiHandler.postDelayed(this, 4000);
                    return;
                }
                bar.setVisibility(View.GONE);
            }
        }
    };

    private final Choreographer.FrameCallback padFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!padTickerRunning) return;
            final long now = SystemClock.uptimeMillis();
            if (streaming && sessionHandle != 0L && (padDirty || (now - lastPadSendMs) >= 20000L)) {
                flushGamepadState();
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    PsRemoteController(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
        this.prefs = activity.getSharedPreferences("gta60_ps", Context.MODE_PRIVATE);
        this.resIdx = Math.max(0, Math.min(RES_LABELS.length - 1, prefs.getInt("psResIdx", 0)));
        this.cfgFps = prefs.getInt("psCfgFps", 60) == 30 ? 30 : 60;
        this.codecId = prefs.getInt("psCodecId", PsChiakiNative.CODEC_H265) == PsChiakiNative.CODEC_H264
                ? PsChiakiNative.CODEC_H264
                : PsChiakiNative.CODEC_H265;
        this.bitrateIdx = Math.max(0, Math.min(BITRATE_KBPS.length - 1, prefs.getInt("psBitrateIdx", 1)));
        this.enhanceMode = CaptureProtocolHelper.normalizeCaptureEnhanceMode(
                prefs.getInt("psEnhanceMode", CaptureGlRenderer.MODE_FG60_SR));
        this.lookMode = Math.max(0, Math.min(LOOK_LABELS.length - 1,
                prefs.getInt("psLookMode", CaptureGlRenderer.LOOK_NORMAL)));
        this.refreshHz = prefs.getInt("psRefreshHz", 120) == 90 ? 90 : 120;

        loadSavedConsoleSelection();
        createViews();
    }

    boolean isVisible() {
        return visible;
    }

    boolean isStreaming() {
        return streaming || starting.get();
    }

    void hideHud() {
        uiHandler.removeCallbacks(autoHideHudTick);
        if (bar != null) bar.setVisibility(View.GONE);
    }

    void toggleHud() {
        if (!visible || bar == null) return;
        final boolean show = bar.getVisibility() != View.VISIBLE;
        bar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).notifyMenuOpenedOrInteracted();
            }
            if (streaming) {
                scheduleAutoHideHud(8000);
            }
        } else {
            uiHandler.removeCallbacks(autoHideHudTick);
        }
    }

    void setAvailableUpdateBadge(String versionName) {
        availableUpdateVersion = versionName == null ? "" : versionName;
        if (updateBtn != null && !availableUpdateVersion.isEmpty()) {
            updateBtn.setText("Update (v" + availableUpdateVersion + ")");
            updateBtn.setBackgroundColor(Color.parseColor("#D97706"));
        }
    }

    void show() {
        if (visible) return;
        visible = true;
        SpatialDisplayBridge.addUiListener(spatialUiListener);
        loadSavedConsoleSelection();
        bar.setVisibility(View.VISIBLE);
        applyRefreshRate();
        updateButtons();
        lastStatTimeMs = System.currentTimeMillis();
        uiHandler.removeCallbacks(telemetryTick);
        uiHandler.postDelayed(telemetryTick, 500);

        if (!PsChiakiNative.isAvailable()) {
            setStatus("PlayStation Remote Play native engine missing (" + PsChiakiNative.getLoadError()
                    + "). Run scripts\\build-ps-chiaki.ps1 or switch Mode to Capture Card.");
        } else if (selectedConsole != null) {
            setStatus("PlayStation: ready (" + selectedConsole.displayName() + ") \u2014 tap Start to play");
        } else {
            setStatus("PlayStation: tap Link PS to pair your PS5/PS4, or Scan LAN to find consoles.");
        }
    }

    void hide() {
        if (!visible) return;
        visible = false;
        SpatialDisplayBridge.removeUiListener(spatialUiListener);
        uiHandler.removeCallbacks(autoHideHudTick);
        uiHandler.removeCallbacks(telemetryTick);
        stop();
        if (bar != null) bar.setVisibility(View.GONE);
    }

    void destroy() {
        hide();
        io.shutdownNow();
        if (bar != null) {
            root.removeView(bar);
            bar = null;
        }
    }

    private void loadSavedConsoleSelection() {
        final List<PsConsoleStore.PsConsole> all = PsConsoleStore.loadAll(activity);
        if (all.isEmpty()) {
            selectedConsole = null;
            return;
        }
        final String lastHost = PsConsoleStore.getLastSelectedHost(activity);
        PsConsoleStore.PsConsole match = all.get(0);
        for (PsConsoleStore.PsConsole c : all) {
            if (c.host.equals(lastHost)) {
                match = c;
                break;
            }
        }
        selectedConsole = match;
    }

    private void createViews() {
        bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#E6111622"));
        final int pad = dp(8);
        bar.setPadding(pad, pad, pad, pad);

        hoverHintText = new TextView(activity);
        hoverHintText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hoverHints = new HoverHintHelper(hoverHintText,
                "Point at any option to see what it does \u2022 Tap video anytime to show/hide menu");

        // Row 1: Platform, Mode, Console, Pairing, Stream
        final LinearLayout row1 = new LinearLayout(activity);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = new TextView(activity);
        title.setText("GTA-Sixty");
        title.setTextColor(Color.parseColor("#38BDF8"));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setPadding(0, 0, dp(10), 0);
        row1.addView(title);

        platformBtn = button(ConsolePlatform.toggleButtonLabel(ConsolePlatform.PLAYSTATION),
                ConsolePlatform.accentHex(ConsolePlatform.PLAYSTATION), v -> {
                    if (activity instanceof MainActivity) ((MainActivity) activity).toggleConsolePlatform();
                });
        hoverHints.attach(platformBtn,
                "Console: Switch between PlayStation (PS5/PS4 Remote Play / Capture) and Xbox (Wi-Fi / Capture / PC).");
        row1.addView(platformBtn);

        modeBtn = button("Mode: PS Remote \u25BE", "#0F766E", v -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).showModePicker();
        });
        hoverHints.attach(modeBtn,
                "Mode: Switch between PlayStation Remote Play (Wi-Fi/LAN) and Capture Card (HDMI via LAN or USB-C).");
        row1.addView(modeBtn);

        consoleBtn = button("PS Console", "#1E3A8A", v -> showConsolePicker());
        hoverHints.attach(consoleBtn,
                "Console: Pick a paired PS5/PS4 or scan your LAN for PlayStation consoles.");
        row1.addView(consoleBtn);

        linkBtn = button("Link PS", "#7C3AED", v -> showLinkConsoleDialog(null));
        hoverHints.attach(linkBtn,
                "Link PS: Pair a PS5 or PS4 using your PSN Account ID and the 8-digit Link Device PIN.");
        row1.addView(linkBtn);

        startStopBtn = button("Start", "#16A34A", v -> {
            if (streaming || starting.get()) stop();
            else start();
        });
        hoverHints.attach(startStopBtn,
                "Start / Stop: Connect directly to your PS5/PS4 via libchiaki, or cleanly end the stream.");
        row1.addView(startStopBtn);

        wakeBtn = button("Wake PS", "#0369A1", v -> wakeSelectedConsole());
        hoverHints.attach(wakeBtn,
                "Wake PS: Send a Remote Play wakeup packet to wake your paired PS5/PS4 from Rest Mode.");
        row1.addView(wakeBtn);

        refreshBtn = button("Refresh", "#475569", v -> {
            if (sessionHandle != 0L) {
                io.execute(() -> PsChiakiNative.nativeSessionRequestIdr(sessionHandle));
                setStatus("Requested keyframe (IDR) from PlayStation...");
            }
        });
        hoverHints.attach(refreshBtn,
                "Refresh: Request an instant keyframe (IDR) if the video stream shows packet-loss artifacts.");
        row1.addView(refreshBtn);

        row1.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1.0f));

        final Button jimmyBtn = JimmyVoiceModule.createTopBarButton(activity, () -> {
            if (streaming) scheduleAutoHideHud(8000);
        });
        hoverHints.attach(jimmyBtn, JimmyVoiceModule.TOP_BAR_HINT);
        row1.addView(jimmyBtn);

        final Button displayBtn = button("Display", "#0284C7", v -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).toggleDisplayControls();
            if (streaming) scheduleAutoHideHud(8000);
        });
        hoverHints.attach(displayBtn,
                "Display: Adjust Screen Size, Depth, Height, Tilt, Curve, Backdrop (Jimmy's Room), and GTA Hands.");
        row1.addView(displayBtn);

        final Button recenterBtn = button("Recenter", "#2563EB", v -> {
            setStatus("Look where you want the screen \u2014 recentering in 1.5s...");
            SpatialDisplayBridge.requestRecenter();
            if (streaming) scheduleAutoHideHud(8000);
        });
        hoverHints.attach(recenterBtn,
                "Recenter: Snap the screen directly in front of where you are looking (1.5s delay so you can look forward).");
        row1.addView(recenterBtn);

        final Button netCheckBtn = button("Net Check", "#0369A1", v -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).openNetworkDiagnostics();
        });
        hoverHints.attach(netCheckBtn, "Net Check: Test Quest Wi-Fi, LAN discovery, and capture status.");
        row1.addView(netCheckBtn);

        final Button adbBtn = button("Wi-Fi ADB", "#2563EB", v -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).openWirelessAdbHelper();
        });
        hoverHints.attach(adbBtn, "Wi-Fi ADB: View Quest Wi-Fi IP and open Android Developer Options.");
        row1.addView(adbBtn);

        updateBtn = button("Update", "#1E293B", v -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).checkForUpdates();
        });
        if (!availableUpdateVersion.isEmpty()) setAvailableUpdateBadge(availableUpdateVersion);
        hoverHints.attach(updateBtn, "Update: Check GitHub for newer GTA-Sixty versions.");
        row1.addView(updateBtn);

        final Button hideBtn = button("Hide Menu", "#475569", v -> toggleHud());
        hoverHints.attach(hideBtn,
                "Hide Menu: Hide this top menu bar (tap anywhere on the video to bring it back).");
        row1.addView(hideBtn);

        final Button helpBtn = button("?", "#334155", v -> showHelp());
        hoverHints.attach(helpBtn,
                "Help: View PlayStation 5 / PS4 Remote Play pairing & setup guide.");
        row1.addView(helpBtn);

        bar.addView(row1);

        // Row 2: Video profile, Quest GPU shaders, quick PS/Touchpad buttons, tools
        final LinearLayout row2 = new LinearLayout(activity);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2Lp.topMargin = dp(6);
        row2.setLayoutParams(r2Lp);

        resBtn = button("Res: " + RES_LABELS[resIdx], "#1E293B", v -> {
            resIdx = (resIdx + 1) % RES_LABELS.length;
            prefs.edit().putInt("psResIdx", resIdx).apply();
            resBtn.setText("Res: " + RES_LABELS[resIdx]);
            setStatus("PS Remote resolution: " + RES_LABELS[resIdx] + (streaming ? " (applies on next Start)" : ""));
        });
        hoverHints.attach(resBtn, "Resolution: Cycle Remote Play target resolution (1080p, 720p, or 540p).");
        row2.addView(resBtn);

        fpsBtn = button(cfgFps + " FPS", "#1E293B", v -> {
            cfgFps = (cfgFps == 60) ? 30 : 60;
            prefs.edit().putInt("psCfgFps", cfgFps).apply();
            fpsBtn.setText(cfgFps + " FPS");
            setStatus("PS Remote FPS: " + cfgFps + (streaming ? " (applies on next Start)" : ""));
        });
        hoverHints.attach(fpsBtn, "FPS: Toggle target Remote Play frame rate between 60 FPS and 30 FPS.");
        row2.addView(fpsBtn);

        codecBtn = button("Codec: " + (codecId == PsChiakiNative.CODEC_H265 ? "HEVC" : "H264"), "#0F766E", v -> {
            codecId = (codecId == PsChiakiNative.CODEC_H265)
                    ? PsChiakiNative.CODEC_H264
                    : PsChiakiNative.CODEC_H265;
            prefs.edit().putInt("psCodecId", codecId).apply();
            codecBtn.setText("Codec: " + (codecId == PsChiakiNative.CODEC_H265 ? "HEVC" : "H264"));
            setStatus("PS Remote codec: " + (codecId == PsChiakiNative.CODEC_H265 ? "HEVC (PS5)" : "H.264 (PS4/PS5)"));
        });
        hoverHints.attach(codecBtn, "Codec: Switch between HEVC (recommended for PS5) and H.264 (PS4 or fallback).");
        row2.addView(codecBtn);

        bitrateBtn = button(BITRATE_LABELS[bitrateIdx], "#1E293B", v -> {
            bitrateIdx = (bitrateIdx + 1) % BITRATE_KBPS.length;
            prefs.edit().putInt("psBitrateIdx", bitrateIdx).apply();
            bitrateBtn.setText(BITRATE_LABELS[bitrateIdx]);
            setStatus("PS Remote bitrate: " + BITRATE_LABELS[bitrateIdx] + (streaming ? " (applies on next Start)" : ""));
        });
        hoverHints.attach(bitrateBtn, "Bitrate: Cycle Remote Play video bitrate (15, 25, 35, or 50 Mbps).");
        row2.addView(bitrateBtn);

        enhanceBtn = button("Enhance: " + CaptureProtocolHelper.simpleCaptureEnhanceLabel(enhanceMode), "#0284C7", v -> {
            enhanceMode = CaptureProtocolHelper.nextCaptureEnhanceMode(enhanceMode);
            prefs.edit().putInt("psEnhanceMode", enhanceMode).apply();
            enhanceBtn.setText("Enhance: " + CaptureProtocolHelper.simpleCaptureEnhanceLabel(enhanceMode));
            final CaptureGlRenderer gl = captureGl;
            if (gl != null) gl.setEnhanceMode(enhanceMode);
            if (CaptureCadenceController.isFg90(enhanceMode) && refreshHz != 90) {
                refreshHz = 90;
                if (hzBtn != null) hzBtn.setText("90 Hz");
                prefs.edit().putInt("psRefreshHz", 90).apply();
                applyRefreshRate();
            } else if (!CaptureCadenceController.isFg90(enhanceMode) && refreshHz != 120) {
                refreshHz = 120;
                if (hzBtn != null) hzBtn.setText("120 Hz");
                prefs.edit().putInt("psRefreshHz", 120).apply();
                applyRefreshRate();
            }
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).applySurfaceFrameRate(effectiveSurfaceFrameRate());
            }
            setStatus("Enhance: " + CaptureProtocolHelper.simpleCaptureEnhanceLabel(enhanceMode));
        });
        hoverHints.attach(enhanceBtn,
                "Enhance: Cycle Off (raw), 60+ (60 fps frame gen + SuperRes), or 90+ (90 fps frame gen + SuperRes) on Quest GPU.");
        row2.addView(enhanceBtn);

        lookBtn = button("Look: " + LOOK_LABELS[lookMode], "#0F766E", v -> {
            lookMode = (lookMode + 1) % LOOK_LABELS.length;
            prefs.edit().putInt("psLookMode", lookMode).apply();
            lookBtn.setText("Look: " + LOOK_LABELS[lookMode]);
            final CaptureGlRenderer gl = captureGl;
            if (gl != null) gl.setLookMode(lookMode);
            setStatus("Look: " + LOOK_LABELS[lookMode]);
        });
        hoverHints.attach(lookBtn, "Look: Cycle color presets \u2014 Normal, Vivid, or Night.");
        row2.addView(lookBtn);

        hzBtn = button(refreshHz + " Hz", "#1E293B", v -> {
            refreshHz = (refreshHz == 120) ? 90 : 120;
            prefs.edit().putInt("psRefreshHz", refreshHz).apply();
            hzBtn.setText(refreshHz + " Hz");
            applyRefreshRate();
        });
        hoverHints.attach(hzBtn, "Refresh Rate: Switch headset refresh between 120 Hz and 90 Hz.");
        row2.addView(hzBtn);

        psBtn = button("PS Btn", "#1E3A8A", v -> sendQuickButtonTap(PsGamepadMapper.BUTTON_PS));
        hoverHints.attach(psBtn, "PS Btn: Send a PlayStation Home button press to the console.");
        row2.addView(psBtn);

        touchBtn = button("Touchpad", "#1E3A8A", v -> sendQuickButtonTap(PsGamepadMapper.BUTTON_TOUCHPAD));
        hoverHints.attach(touchBtn, "Touchpad: Send a DualSense/DS4 Touchpad click (helpful when using an Xbox pad).");
        row2.addView(touchBtn);

        autoHideCheck = new CheckBox(activity);
        autoHideCheck.setText("Auto-hide");
        autoHideCheck.setTextColor(Color.parseColor("#E2E8F0"));
        autoHideCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        autoHideCheck.setButtonTintList(ColorStateList.valueOf(Color.parseColor("#22D3EE")));
        autoHideCheck.setChecked(SpatialDisplayBridge.getSettings().autoHideMenuOnController);
        final LinearLayout.LayoutParams chkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chkLp.leftMargin = dp(4);
        chkLp.rightMargin = dp(4);
        autoHideCheck.setLayoutParams(chkLp);
        autoHideCheck.setOnCheckedChangeListener((btn, isChecked) -> {
            if (syncingAutoHideCheck) return;
            final SpatialDisplayBridge.DisplaySettings s = SpatialDisplayBridge.getSettings();
            s.autoHideMenuOnController = isChecked;
            SpatialDisplayBridge.applyFromUi(activity, s);
        });
        hoverHints.attach(autoHideCheck, "Auto-hide: Hide menus 6 seconds after the virtual controller appears.");
        row2.addView(autoHideCheck);

        bar.addView(row2);

        // Row 3: Hover hint bar
        final LinearLayout hintBox = new LinearLayout(activity);
        hintBox.setOrientation(LinearLayout.HORIZONTAL);
        final GradientDrawable hintBg = new GradientDrawable();
        hintBg.setColor(Color.parseColor("#B30B1324"));
        hintBg.setCornerRadius(dp(6));
        hintBox.setBackground(hintBg);
        hintBox.setPadding(dp(8), dp(4), dp(8), dp(4));
        final LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(6);
        hintBox.setLayoutParams(hintLp);
        hintBox.addView(hoverHintText);
        bar.addView(hintBox);

        // Row 4: Status / Telemetry
        statusText = new TextView(activity);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusText.setPadding(0, dp(4), 0, 0);
        bar.addView(statusText);

        bar.setVisibility(View.GONE);
        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        updateButtons();
    }

    private void showConsolePicker() {
        final List<PsConsoleStore.PsConsole> saved = PsConsoleStore.loadAll(activity);
        final List<String> labels = new ArrayList<>();
        for (PsConsoleStore.PsConsole c : saved) {
            labels.add(c.displayName() + " \u2022 " + c.host);
        }
        labels.add("+ Scan LAN for PS5 / PS4...");
        labels.add("+ Link New Console Manually...");

        new AlertDialog.Builder(activity)
                .setTitle("PlayStation Consoles")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which < saved.size()) {
                        selectedConsole = saved.get(which);
                        PsConsoleStore.setLastSelectedHost(activity, selectedConsole.host);
                        updateButtons();
                        setStatus("Selected " + selectedConsole.displayName() + " \u2014 tap Start to play");
                    } else if (which == saved.size()) {
                        discoverConsolesAsync();
                    } else {
                        showLinkConsoleDialog(null);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void discoverConsolesAsync() {
        if (!PsChiakiNative.isAvailable()) {
            setStatus("PlayStation native engine unavailable: " + PsChiakiNative.getLoadError());
            return;
        }
        setStatus("Scanning LAN for PS5 / PS4 consoles...");
        final String[] targets = (activity instanceof MainActivity)
                ? ((MainActivity) activity).getDiscoveryBroadcastStrings()
                : new String[]{"255.255.255.255"};

        io.execute(() -> {
            final String[] raw = PsChiakiNative.nativeDiscoverConsoles(targets, 1600);
            final List<PsChiakiNative.DiscoveredConsole> found = PsChiakiNative.parseDiscoveryResults(raw);
            uiHandler.post(() -> {
                if (!visible) return;
                if (found.isEmpty()) {
                    setStatus("No PS5/PS4 found on LAN. Enable Remote Play on your console and tap Link PS.");
                    return;
                }
                final List<PsConsoleStore.PsConsole> saved = PsConsoleStore.loadAll(activity);
                final String[] items = new String[found.size()];
                for (int i = 0; i < found.size(); i++) {
                    final PsChiakiNative.DiscoveredConsole dc = found.get(i);
                    final boolean paired = findSavedForHost(saved, dc.hostAddr, dc.hostId) != null;
                    final String name = dc.hostName.isEmpty() ? (dc.isPs5 ? "PS5" : "PS4") : dc.hostName;
                    items[i] = name + " (" + dc.hostAddr + ", " + dc.stateLabel()
                            + (paired ? ", Paired" : ", Not Paired") + ")";
                }
                new AlertDialog.Builder(activity)
                        .setTitle("Discovered PlayStation Consoles")
                        .setItems(items, (d, idx) -> {
                            final PsChiakiNative.DiscoveredConsole dc = found.get(idx);
                            final PsConsoleStore.PsConsole match = findSavedForHost(saved, dc.hostAddr, dc.hostId);
                            if (match != null) {
                                selectedConsole = match;
                                PsConsoleStore.setLastSelectedHost(activity, match.host);
                                updateButtons();
                                setStatus("Selected " + match.displayName() + " \u2014 tap Start");
                            } else {
                                showLinkConsoleDialog(dc);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    private static PsConsoleStore.PsConsole findSavedForHost(
            List<PsConsoleStore.PsConsole> saved, String hostAddr, String hostId) {
        for (PsConsoleStore.PsConsole c : saved) {
            if (hostAddr != null && !hostAddr.isEmpty() && hostAddr.equals(c.host)) return c;
            if (hostId != null && !hostId.isEmpty() && hostId.equalsIgnoreCase(c.macHex)) return c;
        }
        return null;
    }

    private void showLinkConsoleDialog(PsChiakiNative.DiscoveredConsole prefill) {
        if (!PsChiakiNative.isAvailable()) {
            setStatus("Cannot link console: libgtaps.so not loaded (" + PsChiakiNative.getLoadError() + ")");
            return;
        }

        final LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        final int p = dp(16);
        form.setPadding(p, p, p, p);

        final TextView intro = new TextView(activity);
        intro.setText("On your PS5/PS4: Settings -> System -> Remote Play -> Link Device to get an 8-digit PIN.");
        intro.setTextColor(Color.WHITE);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        form.addView(intro);

        final boolean[] isPs5 = {prefill == null || prefill.isPs5};
        final Button typeBtn = button(isPs5[0] ? "Console: PS5" : "Console: PS4", "#0070D1", null);
        typeBtn.setOnClickListener(v -> {
            isPs5[0] = !isPs5[0];
            typeBtn.setText(isPs5[0] ? "Console: PS5" : "Console: PS4");
        });
        final LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tbLp.topMargin = dp(8);
        form.addView(typeBtn, tbLp);

        final EditText hostField = new EditText(activity);
        hostField.setHint("Console IP (e.g. 192.168.1.50)");
        hostField.setInputType(InputType.TYPE_CLASS_TEXT);
        if (prefill != null && !prefill.hostAddr.isEmpty()) {
            hostField.setText(prefill.hostAddr);
        } else if (selectedConsole != null) {
            hostField.setText(selectedConsole.host);
        }
        form.addView(hostField);

        final EditText accountField = new EditText(activity);
        accountField.setHint("PSN Account ID (Base64 or numeric ID)");
        accountField.setInputType(InputType.TYPE_CLASS_TEXT);
        final String savedAcct = PsConsoleStore.getSavedPsnAccountIdBase64(activity);
        if (!savedAcct.isEmpty()) accountField.setText(savedAcct);
        form.addView(accountField);

        final LinearLayout authRow = new LinearLayout(activity);
        authRow.setOrientation(LinearLayout.HORIZONTAL);
        final Button psnSignInBtn = button("Sign in PSN (Auto-Fill ID)", "#7C3AED", v -> {
            PsnAuthHelper.showSignInDialog(activity, new PsnAuthHelper.Callback() {
                @Override
                public void onSuccess(String accountIdBase64, String onlineId) {
                    accountField.setText(accountIdBase64);
                    PsConsoleStore.setSavedPsnAccountIdBase64(activity, accountIdBase64);
                    setStatus("PSN Account ID obtained" + (onlineId.isEmpty() ? "" : " (" + onlineId + ")"));
                }

                @Override
                public void onError(String message) {
                    setStatus(message);
                }
            });
        });
        authRow.addView(psnSignInBtn);

        final Button pasteUrlBtn = button("Paste Redirect URL", "#334155", v -> {
            final EditText urlInput = new EditText(activity);
            urlInput.setHint("https://remoteplay.dl.playstation.net/remoteplay/redirect?code=...");
            new AlertDialog.Builder(activity)
                    .setTitle("Paste PSN Redirect URL")
                    .setMessage("Open this URL on your phone/PC, sign in, and paste the final redirect URL:\n\n"
                            + PsnAuthHelper.LOGIN_URL)
                    .setView(urlInput)
                    .setPositiveButton("Extract ID", (d2, w2) -> {
                        PsnAuthHelper.exchangeCodeAsync(activity, urlInput.getText().toString(),
                                new PsnAuthHelper.Callback() {
                                    @Override
                                    public void onSuccess(String accountIdBase64, String onlineId) {
                                        accountField.setText(accountIdBase64);
                                        PsConsoleStore.setSavedPsnAccountIdBase64(activity, accountIdBase64);
                                        setStatus("PSN Account ID extracted.");
                                    }

                                    @Override
                                    public void onError(String message) {
                                        setStatus(message);
                                    }
                                });
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        authRow.addView(pasteUrlBtn);
        form.addView(authRow);

        final EditText pinField = new EditText(activity);
        pinField.setHint("8-digit Link PIN from PS5/PS4");
        pinField.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(pinField);

        final ScrollView sv = new ScrollView(activity);
        sv.addView(form);

        new AlertDialog.Builder(activity)
                .setTitle("Link PlayStation Console")
                .setView(sv)
                .setPositiveButton("Register", (d, w) -> {
                    final String host = hostField.getText().toString().trim();
                    final String acctRaw = accountField.getText().toString().trim();
                    final String pinRaw = pinField.getText().toString().trim();
                    final byte[] acctBytes = ConsolePlatform.parsePsnAccountId(acctRaw);
                    if (host.isEmpty()) {
                        setStatus("Enter your PlayStation's LAN IP address.");
                        return;
                    }
                    if (acctBytes == null) {
                        setStatus("Invalid PSN Account ID. Tap Sign in PSN or enter a valid Base64/numeric ID.");
                        return;
                    }
                    final int pin;
                    try {
                        pin = Integer.parseInt(pinRaw);
                    } catch (NumberFormatException e) {
                        setStatus("Enter the 8-digit pairing PIN shown on your PlayStation.");
                        return;
                    }
                    final String b64 = ConsolePlatform.encodePsnAccountIdBase64(acctBytes);
                    PsConsoleStore.setSavedPsnAccountIdBase64(activity, b64);
                    setStatus("Registering " + (isPs5[0] ? "PS5" : "PS4") + " at " + host + "...");
                    io.execute(() -> {
                        final String resStr = PsChiakiNative.nativeRegisterConsole(
                                host, isPs5[0], acctBytes, null, pin, 0);
                        final PsChiakiNative.RegistResult res = PsChiakiNative.parseRegistrationResult(resStr);
                        uiHandler.post(() -> {
                            if (!res.ok) {
                                setStatus("Pair failed: " + res.error);
                                return;
                            }
                            final PsConsoleStore.PsConsole console = new PsConsoleStore.PsConsole(
                                    host, res.nickname, isPs5[0], res.target,
                                    res.macHex, res.registKeyHex, res.morningHex, b64);
                            PsConsoleStore.saveOrUpdate(activity, console);
                            selectedConsole = console;
                            updateButtons();
                            setStatus("Paired " + console.displayName() + " \u2014 tap Start to play!");
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void wakeSelectedConsole() {
        if (!PsChiakiNative.isAvailable()) {
            setStatus("PlayStation native engine unavailable.");
            return;
        }
        final PsConsoleStore.PsConsole c = selectedConsole;
        if (c == null) {
            setStatus("Pair a PlayStation console first via Link PS.");
            return;
        }
        setStatus("Sending Rest Mode wakeup to " + c.displayName() + "...");
        io.execute(() -> {
            final int err = PsChiakiNative.nativeWakeConsole(c.host, c.wakeCredential(), c.isPs5);
            uiHandler.post(() -> {
                if (err == 0) {
                    setStatus("Wakeup sent to " + c.displayName() + " \u2014 wait a few seconds, then tap Start.");
                } else {
                    setStatus("Wakeup failed (code " + err + ").");
                }
            });
        });
    }

    void start() {
        if (!PsChiakiNative.isAvailable()) {
            setStatus("PlayStation native engine not built (" + PsChiakiNative.getLoadError() + ").");
            return;
        }
        final PsConsoleStore.PsConsole console = selectedConsole;
        if (console == null) {
            showLinkConsoleDialog(null);
            return;
        }
        if (streaming || !starting.compareAndSet(false, true)) return;

        final Surface surf = (activity instanceof MainActivity) ? ((MainActivity) activity).getActiveSurface() : null;
        if (surf == null || !surf.isValid()) {
            starting.set(false);
            setStatus("Display surface unavailable yet \u2014 tap Start again.");
            return;
        }

        final int width = RES_WIDTHS[resIdx];
        final int height = RES_HEIGHTS[resIdx];
        final int fps = cfgFps;
        final int bitrateKbps = BITRATE_KBPS[bitrateIdx];

        streamWidth = width;
        streamHeight = height;
        firstFrameRendered = false;
        sessionConnected = false;
        ptsCounterUs = 0L;
        statFrames = 0;
        statRendered = 0;
        statGenFrames = 0;
        statBytes = 0;
        lastStatTimeMs = System.currentTimeMillis();
        gamepad.reset();
        updateButtons();
        setStatus("Starting " + console.displayName() + " (" + RES_LABELS[resIdx] + " " + fps + "fps)...");

        io.execute(() -> {
            try {
                final CaptureGlRenderer gl = new CaptureGlRenderer(
                        activity,
                        surf,
                        width,
                        height,
                        enhanceMode,
                        lookMode,
                        true,
                        false,
                        generated -> {
                            statRendered++;
                            if (generated) statGenFrames++;
                        });
                gl.setDecoderImageVScale(1.0f);
                final Surface glSurf = gl.getDecoderSurface();
                if (glSurf == null || !glSurf.isValid()) {
                    gl.release();
                    throw new IllegalStateException("GPU shader pipeline failed to initialize");
                }

                final String mime = (codecId == PsChiakiNative.CODEC_H265) ? "video/hevc" : "video/avc";
                final MediaCodec mc = createConfiguredDecoder(mime, width, height, glSurf);

                synchronized (codecLock) {
                    captureGl = gl;
                    mediaCodec = mc;
                }

                streaming = true;
                decodeOutThread = new Thread(() -> drainDecoderLoop(mc), "GTA60-PsDecOut");
                decodeOutThread.start();

                uiHandler.post(() -> {
                    if (activity instanceof MainActivity) {
                        final MainActivity ma = (MainActivity) activity;
                        final SurfaceView sv = ma.getSurfaceView();
                        final SpatialDisplayBridge.DisplaySettings ds = SpatialDisplayBridge.getSettings();
                        final int[] dims = CaptureProtocolHelper.computeSurfaceDimensions(
                                width,
                                height,
                                SpatialDisplayBridge.panelPxW(ds.panelScale),
                                SpatialDisplayBridge.panelPxH(ds.panelScale));
                        if (sv != null) sv.getHolder().setFixedSize(dims[0], dims[1]);
                        ma.adjustSurfaceAspect(width, height);
                        ma.applySurfaceFrameRate(effectiveSurfaceFrameRate());
                    }
                    applyRefreshRate();
                    ensurePadTicker(true);
                    updateButtons();
                });

                final long handle = PsChiakiNative.nativeSessionStart(
                        console.host,
                        console.isPs5,
                        console.registKeyBytes(),
                        console.morningBytes(),
                        width,
                        height,
                        fps,
                        bitrateKbps,
                        codecId,
                        this);
                if (handle == 0L) {
                    throw new IllegalStateException("chiaki_session_start returned null handle");
                }
                sessionHandle = handle;
                starting.set(false);
                uiHandler.post(this::updateButtons);
            } catch (Exception e) {
                Log.e(TAG, "PS Remote start failed", e);
                starting.set(false);
                stopInternal();
                setStatus("PS Remote start failed: " + e.getMessage());
            }
        });
    }

    private MediaCodec createConfiguredDecoder(String mime, int width, int height, Surface outputSurf)
            throws Exception {
        final MediaCodec mc = MediaCodec.createDecoderByType(mime);
        final MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
        if (Build.VERSION.SDK_INT >= 30) {
            fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        fmt.setInteger(MediaFormat.KEY_PRIORITY, 0);
        fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, 240);

        try {
            fmt.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
            fmt.setInteger("vendor.qti-ext-dec-picture-order.enable", 1);
            mc.configure(fmt, outputSurf, null, 0);
        } catch (Exception vendorEx) {
            final MediaFormat fallback = MediaFormat.createVideoFormat(mime, width, height);
            if (Build.VERSION.SDK_INT >= 30) fallback.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            fallback.setInteger(MediaFormat.KEY_PRIORITY, 0);
            fallback.setInteger(MediaFormat.KEY_OPERATING_RATE, 240);
            mc.configure(fallback, outputSurf, null, 0);
        }
        mc.start();
        return mc;
    }

    void stop() {
        starting.set(false);
        if (!streaming && sessionHandle == 0L && mediaCodec == null) return;
        io.execute(this::stopInternal);
    }

    private void stopInternal() {
        streaming = false;
        sessionConnected = false;
        uiHandler.post(() -> {
            ensurePadTicker(false);
            uiHandler.removeCallbacks(autoHideHudTick);
            updateButtons();
        });

        final long h = sessionHandle;
        sessionHandle = 0L;
        if (h != 0L) {
            gamepad.reset();
            PsChiakiNative.nativeSessionSetControllerState(
                    h, 0, 0, 0, (short) 0, (short) 0, (short) 0, (short) 0, false, 960, 470);
            PsChiakiNative.nativeSessionStop(h);
        }
        releaseDecoderAndGl();
        releaseAudio();
        setStatus("PlayStation Remote Play disconnected.");
    }

    private void releaseDecoderAndGl() {
        final MediaCodec mc;
        synchronized (codecLock) {
            mc = mediaCodec;
            mediaCodec = null;
        }
        final Thread drain = decodeOutThread;
        decodeOutThread = null;
        if (drain != null && drain != Thread.currentThread()) {
            try {
                drain.join(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (mc != null) {
            try { mc.stop(); } catch (Exception ignored) {}
            try { mc.release(); } catch (Exception ignored) {}
        }
        final CaptureGlRenderer gl;
        synchronized (codecLock) {
            gl = captureGl;
            captureGl = null;
        }
        if (gl != null) {
            gl.release();
        }
    }

    private void releaseAudio() {
        synchronized (audioLock) {
            if (audioTrack != null) {
                try { audioTrack.stop(); } catch (Exception ignored) {}
                try { audioTrack.release(); } catch (Exception ignored) {}
                audioTrack = null;
            }
        }
    }

    private boolean isActiveCodec(MediaCodec mc) {
        synchronized (codecLock) {
            return mediaCodec == mc;
        }
    }

    private void drainDecoderLoop(MediaCodec mc) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Exception ignored) {
        }
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (streaming && isActiveCodec(mc)) {
            try {
                int outIdx = mc.dequeueOutputBuffer(info, 4000);
                if (outIdx >= 0) {
                    for (;;) {
                        final int nextIdx = mc.dequeueOutputBuffer(info, 0);
                        if (nextIdx >= 0) {
                            mc.releaseOutputBuffer(outIdx, false);
                            outIdx = nextIdx;
                        } else {
                            if (nextIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                handleDecoderFormatChanged(mc.getOutputFormat());
                            }
                            break;
                        }
                    }
                    if (!firstFrameRendered) {
                        firstFrameRendered = true;
                        Log.i(TAG, String.format("First PS Remote frame rendered (%dx%d)", streamWidth, streamHeight));
                    }
                    final CaptureGlRenderer gl = captureGl;
                    if (gl != null) {
                        gl.onDecodedFrame(false);
                        mc.releaseOutputBuffer(outIdx, true);
                    } else {
                        statRendered++;
                        mc.releaseOutputBuffer(outIdx, System.nanoTime());
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    handleDecoderFormatChanged(mc.getOutputFormat());
                }
            } catch (IllegalStateException e) {
                break;
            }
        }
    }

    private void handleDecoderFormatChanged(MediaFormat fmt) {
        if (fmt == null) return;
        int w = fmt.containsKey(MediaFormat.KEY_WIDTH) ? fmt.getInteger(MediaFormat.KEY_WIDTH) : streamWidth;
        int h = fmt.containsKey(MediaFormat.KEY_HEIGHT) ? fmt.getInteger(MediaFormat.KEY_HEIGHT) : streamHeight;
        if (fmt.containsKey("crop-left") && fmt.containsKey("crop-right")
                && fmt.containsKey("crop-top") && fmt.containsKey("crop-bottom")) {
            w = fmt.getInteger("crop-right") - fmt.getInteger("crop-left") + 1;
            h = fmt.getInteger("crop-bottom") - fmt.getInteger("crop-top") + 1;
        }
        if (w > 0 && h > 0 && (w != streamWidth || h != streamHeight)) {
            streamWidth = w;
            streamHeight = h;
            final int fw = w;
            final int fh = h;
            uiHandler.post(() -> {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).adjustSurfaceAspect(fw, fh);
                }
            });
        }
    }

    // ---- PsChiakiNative.SessionCallback (called on libchiaki native threads) ----

    @Override
    public boolean onVideoSample(byte[] data, int size, int framesLost, boolean frameRecovered) {
        if (!streaming || data == null || size <= 0) return false;
        final MediaCodec mc;
        synchronized (codecLock) {
            mc = mediaCodec;
        }
        if (mc == null) return false;

        statFrames++;
        statBytes += size;
        int offset = 0;
        while (offset < size && streaming && isActiveCodec(mc)) {
            try {
                final int idx = mc.dequeueInputBuffer(10000);
                if (idx < 0) return false;
                final ByteBuffer buf = mc.getInputBuffer(idx);
                if (buf == null) return false;
                buf.clear();
                final int chunk = Math.min(size - offset, buf.remaining());
                buf.put(data, offset, chunk);
                ptsCounterUs += (cfgFps >= 60 ? 16667L : 33333L);
                mc.queueInputBuffer(idx, 0, chunk, ptsCounterUs, 0);
                offset += chunk;
            } catch (IllegalStateException e) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onAudioSettings(int channels, int rate) {
        synchronized (audioLock) {
            audioChannels = channels <= 1 ? 1 : 2;
            audioRate = rate > 0 ? rate : 48000;
            if (audioTrack != null) {
                try { audioTrack.stop(); } catch (Exception ignored) {}
                try { audioTrack.release(); } catch (Exception ignored) {}
                audioTrack = null;
            }
        }
    }

    @Override
    public void onAudioPcm(byte[] pcm, int byteLength) {
        if (!streaming || pcm == null || byteLength <= 0) return;
        synchronized (audioLock) {
            try {
                if (audioTrack == null) {
                    final int chMask = audioChannels == 1
                            ? AudioFormat.CHANNEL_OUT_MONO
                            : AudioFormat.CHANNEL_OUT_STEREO;
                    final int minBuf = AudioTrack.getMinBufferSize(
                            audioRate, chMask, AudioFormat.ENCODING_PCM_16BIT);
                    final AudioTrack.Builder b = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_GAME)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setSampleRate(audioRate)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setChannelMask(chMask)
                                    .build())
                            .setBufferSizeInBytes(Math.max(minBuf, audioRate * audioChannels * 2 / 50))
                            .setTransferMode(AudioTrack.MODE_STREAM);
                    if (Build.VERSION.SDK_INT >= 26) {
                        b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
                    }
                    audioTrack = b.build();
                    audioTrack.play();
                }
                if (Build.VERSION.SDK_INT >= 23) {
                    audioTrack.write(pcm, 0, byteLength, AudioTrack.WRITE_NON_BLOCKING);
                } else {
                    audioTrack.write(pcm, 0, byteLength);
                }
            } catch (Exception e) {
                Log.w(TAG, "PS AudioTrack error: " + e.getMessage());
            }
        }
    }

    @Override
    public void onEvent(int eventType, int arg0, int arg1, String strArg) {
        switch (eventType) {
            case PsChiakiNative.EVENT_CONNECTED:
                sessionConnected = true;
                setStatus("Connected to "
                        + (selectedConsole != null ? selectedConsole.displayName() : "PlayStation")
                        + " \u2014 streaming!");
                uiHandler.post(() -> {
                    updateButtons();
                    scheduleAutoHideHud(8000);
                });
                break;

            case PsChiakiNative.EVENT_LOGIN_PIN_REQUEST:
                uiHandler.post(() -> promptLoginPin(arg0 != 0));
                break;

            case PsChiakiNative.EVENT_RUMBLE:
                triggerRumble(arg0, arg1);
                break;

            case PsChiakiNative.EVENT_QUIT:
                final boolean isError = (arg1 != 0);
                final String reason = strArg == null ? "Session ended" : strArg;
                Log.i(TAG, "PS session quit (error=" + isError + "): " + reason);
                io.execute(() -> {
                    stopInternal();
                    if (isError) {
                        setStatus("PS Remote stopped: " + reason);
                    }
                });
                break;

            default:
                break;
        }
    }

    private void promptLoginPin(boolean previousIncorrect) {
        if (!visible || !streaming) return;
        final EditText input = new EditText(activity);
        input.setHint("4-digit PS passcode");
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(activity)
                .setTitle("PlayStation Login Passcode")
                .setMessage(previousIncorrect
                        ? "Incorrect passcode. Enter your PlayStation profile 4-digit passcode:"
                        : "Enter your PlayStation profile 4-digit passcode:")
                .setView(input)
                .setPositiveButton("Submit", (d, w) -> {
                    final String pin = input.getText().toString().trim();
                    final long h = sessionHandle;
                    if (h != 0L) {
                        io.execute(() -> PsChiakiNative.nativeSessionSetLoginPin(h, pin));
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> stop())
                .show();
    }

    private void triggerRumble(int left, int right) {
        final long now = SystemClock.uptimeMillis();
        if (now - lastRumbleMs < 20L) return;
        lastRumbleMs = now;
        final int amp = Math.max(0, Math.min(255, Math.max(left, right)));
        if (amp <= 0) return;

        final int[] ids = InputDevice.getDeviceIds();
        for (int id : ids) {
            final InputDevice dev = InputDevice.getDevice(id);
            if (dev == null) continue;
            final int src = dev.getSources();
            if ((src & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) continue;
            if (Build.VERSION.SDK_INT >= 31) {
                final VibratorManager vm = dev.getVibratorManager();
                if (vm != null) {
                    try {
                        vm.vibrate(CombinedVibration.createParallel(
                                VibrationEffect.createOneShot(45, amp)));
                    } catch (Exception ignored) {
                    }
                }
            } else {
                @SuppressWarnings("deprecation")
                final Vibrator v = dev.getVibrator();
                if (v != null && v.hasVibrator()) {
                    try {
                        v.vibrate(VibrationEffect.createOneShot(45, amp));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    // ---- Gamepad Input ----

    boolean onGamepadKey(KeyEvent event) {
        if (!visible || !streaming || sessionHandle == 0L) return false;
        if ((event.getSource() & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
                && (event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
            return false;
        }
        final int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;
        if (gamepad.applyKey(event.getKeyCode(), action == KeyEvent.ACTION_DOWN)) {
            flushGamepadState();
            return true;
        }
        return false;
    }

    boolean onGamepadMotion(MotionEvent ev) {
        if (!visible || !streaming || sessionHandle == 0L) return false;
        if ((ev.getSource() & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
                && (ev.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
            return false;
        }
        if (ev.getAction() != MotionEvent.ACTION_MOVE) return false;
        gamepad.applyAxes(
                ev.getAxisValue(MotionEvent.AXIS_X),
                ev.getAxisValue(MotionEvent.AXIS_Y),
                ev.getAxisValue(MotionEvent.AXIS_Z),
                ev.getAxisValue(MotionEvent.AXIS_RZ),
                ev.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                ev.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                ev.getAxisValue(MotionEvent.AXIS_BRAKE),
                ev.getAxisValue(MotionEvent.AXIS_GAS),
                ev.getAxisValue(MotionEvent.AXIS_HAT_X),
                ev.getAxisValue(MotionEvent.AXIS_HAT_Y));
        if (gamepad.consumeDirty()) {
            padDirty = true;
        }
        return true;
    }

    private void flushGamepadState() {
        final long h = sessionHandle;
        if (h == 0L) return;
        padDirty = false;
        gamepad.consumeDirty();
        lastPadSendMs = SystemClock.uptimeMillis();
        PsChiakiNative.nativeSessionSetControllerState(
                h,
                gamepad.getButtons(),
                gamepad.getL2(),
                gamepad.getR2(),
                gamepad.getLx(),
                gamepad.getLy(),
                gamepad.getRx(),
                gamepad.getRy(),
                gamepad.isTouchDown(),
                gamepad.getTouchX(),
                gamepad.getTouchY());
    }

    private void sendQuickButtonTap(int mask) {
        if (!streaming || sessionHandle == 0L) {
            setStatus("Start PlayStation stream first to send controller buttons.");
            return;
        }
        gamepad.setVirtualButton(mask, true);
        flushGamepadState();
        uiHandler.postDelayed(() -> {
            gamepad.setVirtualButton(mask, false);
            flushGamepadState();
        }, 120);
    }

    private void ensurePadTicker(boolean on) {
        if (on && !padTickerRunning) {
            padTickerRunning = true;
            Choreographer.getInstance().postFrameCallback(padFrameCallback);
        } else if (!on && padTickerRunning) {
            padTickerRunning = false;
            Choreographer.getInstance().removeFrameCallback(padFrameCallback);
        }
    }

    // ---- Helpers ----

    private int effectiveSurfaceFrameRate() {
        if (CaptureCadenceController.isFg120(enhanceMode)) return 120;
        if (CaptureCadenceController.isFg90(enhanceMode)) return 90;
        return cfgFps > 0 ? cfgFps : 60;
    }

    private void applyRefreshRate() {
        SpatialDisplayBridge.requestRefreshRate(refreshHz);
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).applyDisplayRefreshRate(refreshHz);
        }
    }

    private void scheduleAutoHideHud(long delayMs) {
        uiHandler.removeCallbacks(autoHideHudTick);
        if (visible && streaming) {
            uiHandler.postDelayed(autoHideHudTick, delayMs);
        }
    }

    private void updateButtons() {
        if (consoleBtn != null) {
            consoleBtn.setText(selectedConsole != null ? selectedConsole.displayName() : "PS Console");
        }
        if (startStopBtn != null) {
            final boolean active = streaming || starting.get();
            startStopBtn.setText(active ? "Stop" : "Start");
            startStopBtn.setBackgroundColor(Color.parseColor(active ? "#DC2626" : "#16A34A"));
        }
    }

    private void setStatus(String text) {
        Log.i(TAG, text);
        uiHandler.post(() -> {
            if (statusText != null) statusText.setText(text);
        });
    }

    private void showHelp() {
        final String msg =
                "PlayStation Remote Play (libchiaki) connects Quest 3 directly to your PS5 or PS4 over Wi-Fi/LAN:\n\n"
                + "\u2022 Step 1 (On PS5/PS4): Settings -> System -> Remote Play -> Enable Remote Play -> Link Device (shows an 8-digit PIN).\n\n"
                + "\u2022 Step 2 (In GTA-Sixty): Tap 'Link PS', sign in to PSN (to auto-fill your PSN Account ID), enter the 8-digit PIN, and tap Register.\n\n"
                + "\u2022 Step 3 (Play): Tap Start. Video decodes via Quest hardware MediaCodec directly into GTA-Sixty's OpenGL shader pipeline (SuperRes, RCAS, 60+/90+ frame gen) and OpenXR Curved/Spatial screen!\n\n"
                + "\u2022 Platform Toggle: Tap 'PS \u21C4 Xbox' anytime to switch back to Xbox without touching any Xbox settings.";
        final ScrollView sv = new ScrollView(activity);
        final TextView tv = new TextView(activity);
        tv.setText(msg);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        final int p = dp(16);
        tv.setPadding(p, p, p, p);
        sv.addView(tv);
        new AlertDialog.Builder(activity)
                .setTitle("PlayStation Remote Play Help")
                .setView(sv)
                .setPositiveButton("Close", null)
                .show();
    }

    private Button button(String text, String hex, View.OnClickListener listener) {
        final Button b = new Button(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(hex));
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        if (listener != null) b.setOnClickListener(listener);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(5);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, activity.getResources().getDisplayMetrics()));
    }
}
