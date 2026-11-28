package com.gtasixty.spatial;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.gtasixty.spatial.voice.JimmyVoiceModule;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Direct" transport: the headset talks to the console itself, with no PC in the path.
 *
 * <p>Auth, session provisioning and the SDP/ICE exchange are Java ({@link XboxDirect}); the media
 * peer connection, data channels, gamepad reports, rumble forwarding, and on-Quest Adreno 740
 * WebGL2 enhancement pipeline (30 FPS duplicate detector, AMD FidelityFX RCAS sharpening,
 * SuperRes, Night/Vivid looks, and 2-level 30->60 optical-flow frame generation) live in the
 * WebView page in assets/direct.
 */
final class DirectController {
    private static final String TAG = "GTA60Direct";
    private static final String PAGE = "file:///android_asset/direct/index.html";
    private static final String[] ENHANCE_LABELS = {
        "Enhance: Off",
        "Enhance: RCAS",
        "Enhance: SuperRes",
        "Enhance: FG 60",
        "Enhance: FG60+SR"
    };
    private static final String[] LOOK_LABELS = {
        "Look: Normal",
        "Look: Vivid",
        "Look: Night"
    };

    private final Activity activity;
    private final FrameLayout root;
    private final XboxDirect xbox;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final InputManager inputManager;

    private WebView webView;
    private LinearLayout bar;
    private TextView status;
    private TextView hoverHintText;
    private HoverHintHelper hoverHints;
    private Button modeBtn;
    private Button platformBtn;
    private Button signInBtn;
    private Button consoleBtn;
    private Button startStopBtn;
    private Button enhanceBtn;
    private Button lookBtn;
    private Button hzBtn;
    private Button padBtn;
    private Button updateBtn;
    private CheckBox autoHideCheck;
    private boolean syncingAutoHideCheck = false;
    private String availableUpdateVersion = "";
    private final SpatialDisplayBridge.UiListener spatialUiListener = settings -> {
        if (settings == null || autoHideCheck == null) return;
        syncingAutoHideCheck = true;
        try {
            if (autoHideCheck.isChecked() != settings.autoHideMenuOnController) {
                autoHideCheck.setChecked(settings.autoHideMenuOnController);
            }
        } finally {
            syncingAutoHideCheck = false;
        }
    };

    private volatile boolean visible;
    private volatile boolean streaming;
    private volatile boolean stopped;
    private volatile boolean pageLoaded;
    private volatile boolean startWhenReady;
    private volatile boolean signingIn;
    private volatile boolean dialogOpen;
    private volatile boolean activityPaused = false;
    /** True once the page completed the data-channel handshake and media is actually flowing. */
    private volatile boolean pageStreaming;

    private volatile String sessionId = "";
    private volatile XboxDirect.Console selected;
    private int enhanceMode = 4; // default: FG60+SR (30->60 Explained-Warp + 3200x1800 SuperRes + RCAS)
    private int lookMode = 0;    // default: Normal (0=Normal, 1=Vivid, 2=Night)
    private int refreshHz = 120; // default: 120 Hz (divides both 30 and 60 evenly)

    // True while Android reports a real gamepad attached to the headset. This never removes pad 0
    // (the console kicks a session with no pad, see the keep-alive note in direct.js); it only
    // decides when to re-announce pad 0 and when the Java-side pad ticker may send reports.
    private volatile boolean localPadPresent;
    private long lastPresenceCheckMs;
    private int sessionGeneration;
    private volatile long lastStartMs = 0L;
    private volatile boolean autoRetriedDisconnect = false;

    // Pad state is written by the UI thread from dispatchKeyEvent/MotionEvent. Button edges are
    // flushed immediately (0 ms delay); analog stick/trigger axes are coalesced on the display
    // Choreographer vsync tick (120/90 Hz) without postDelayed(8)/16ms quantization jitter.
    private volatile int padButtons, padLt, padRt;
    private volatile short padLX, padLY, padRX, padRY;
    private volatile int lastSentButtons;
    private volatile boolean padDirty;
    private long lastPadPostMs;

    private final Runnable autoHideHudTick = new Runnable() {
        @Override
        public void run() {
            if (visible && streaming && bar != null) {
                if (activity instanceof MainActivity && ((MainActivity) activity).isDisplayControlsOpen()) {
                    handler.postDelayed(this, 4000);
                    return;
                }
                bar.setVisibility(View.GONE);
            }
        }
    };

    /**
     * Fails a start that never produces media. The console can accept the SDP answer and then stay
     * silent (a PC/direct session collision does this), which otherwise leaves the UI on "Starting
     * WebRTC" for ever with only Stop as an escape.
     */
    private final Runnable startWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!streaming || pageStreaming) return;
            failStart("The console did not start the stream (session collision or stuck console). "
                    + "Press Start XRemote to retry.");
        }
    };

    DirectController(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
        this.xbox = new XboxDirect(activity);
        this.prefs = activity.getSharedPreferences("gta60", Context.MODE_PRIVATE);
        this.enhanceMode = CaptureProtocolHelper.normalizeDirectEnhanceMode(prefs.getInt("directEnhanceMode", 4));
        this.lookMode = Math.max(0, Math.min(LOOK_LABELS.length - 1, prefs.getInt("directLookMode", 0)));
        this.refreshHz = prefs.getInt("directRefreshHz", 120) == 90 ? 90 : 120;
        this.inputManager = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(inputDeviceListener, handler);
        }
        this.localPadPresent = detectLocalPad();
        createViews();
        if (xbox.signedIn() && !xbox.getPersistedSession().isEmpty()) {
            io.execute(xbox::cleanupPersistedSession);
        }
    }

    boolean isVisible() {
        return visible;
    }

    boolean isStreaming() {
        return streaming;
    }

    boolean isSignedIn() {
        return xbox.signedIn();
    }

    void hideHud() {
        handler.removeCallbacks(autoHideHudTick);
        if (bar != null) bar.setVisibility(View.GONE);
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
        stopped = false;
        SpatialDisplayBridge.addUiListener(spatialUiListener);
        refreshLocalPad("show");
        webView.setVisibility(View.VISIBLE);
        bar.setVisibility(View.VISIBLE);
        applyRefreshRate();
        if (!pageLoaded) {
            webView.loadUrl(PAGE);
        } else {
            webView.evaluateJavascript("GTA60Direct.setEnhance(" + enhanceMode + ");GTA60Direct.setLook(" + lookMode + ")", null);
        }
        updateButtons();
        if (xbox.signedIn()) {
            if (selected != null) {
                setStatus("Xbox Direct: ready (" + selected.name + ") \u2014 tap Start to play");
            } else {
                setStatus("Xbox Direct: finding your Xbox on your account / local network...");
                autoDiscoverConsole();
            }
        } else {
            setStatus("Xbox Direct: tap Sign in to find your Xbox automatically (no IP needed)");
        }
    }

    void hide() {
        if (!visible) return;
        visible = false;
        SpatialDisplayBridge.removeUiListener(spatialUiListener);
        handler.removeCallbacks(autoHideHudTick);
        stop();
        if (webView != null) webView.setVisibility(View.GONE);
        if (bar != null) bar.setVisibility(View.GONE);
    }

    private void autoDiscoverConsole() {
        if (!xbox.signedIn() || streaming || busy.get()) return;
        io.execute(() -> {
            try {
                final List<XboxDirect.Console> consoles = xbox.consoles();
                if (consoles.isEmpty()) {
                    runOnUi(() -> {
                        if (visible && !streaming) {
                            setStatus("Xbox Direct: no console found yet \u2014 enable Remote features on Xbox, then tap Xbox");
                        }
                    });
                    return;
                }
                XboxDirect.Console pick = consoles.get(0);
                for (XboxDirect.Console c : consoles) {
                    if ("On".equalsIgnoreCase(c.powerState)) {
                        pick = c;
                        break;
                    }
                }
                final XboxDirect.Console chosen = pick;
                runOnUi(() -> {
                    if (!visible) return;
                    if (selected == null) selected = chosen;
                    updateButtons();
                    if (!streaming) {
                        setStatus("Xbox Direct: found " + selected.name + " \u2014 tap Start to play");
                    }
                });
            } catch (Exception e) {
                Log.d(TAG, "Auto console discovery skipped: " + e.getMessage());
            }
        });
    }

    void onActivityPause() {
        activityPaused = true;
    }

    void onActivityResume() {
        final boolean wasPaused = activityPaused;
        activityPaused = false;
        if (visible && webView != null) {
            if (wasPaused) {
                webView.onResume();
            }
            applyRefreshRate();
            refreshLocalPad("resume");
            if (streaming && pageLoaded) {
                webView.evaluateJavascript("GTA60Direct.onResume()", null);
            }
        }
    }

    void destroy() {
        SpatialDisplayBridge.removeUiListener(spatialUiListener);
        handler.removeCallbacks(autoHideHudTick);
        visible = false;
        if (inputManager != null) {
            try { inputManager.unregisterInputDeviceListener(inputDeviceListener); } catch (Exception ignored) {}
        }
        final String activeId = sessionId;
        sessionId = "";
        try {
            stop(true);
        } catch (Exception ignored) {
        }
        io.shutdownNow();
        if (activeId != null && !activeId.isEmpty()) {
            final Thread stopThread = new Thread(() -> xbox.stop(activeId), "direct-stop");
            stopThread.setDaemon(true);
            stopThread.start();
        }
        handler.removeCallbacksAndMessages(null);
        ensurePadTicker(false);
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        if (bar != null) {
            root.removeView(bar);
            bar = null;
        }
    }

    void setEnhanceMode(int mode) {
        enhanceMode = CaptureProtocolHelper.normalizeDirectEnhanceMode(mode);
        prefs.edit().putInt("directEnhanceMode", enhanceMode).apply();
        if (enhanceBtn != null) {
            enhanceBtn.setText("Enhance: " + CaptureProtocolHelper.simpleDirectEnhanceLabel(enhanceMode));
        }
        if (CaptureCadenceController.isFg90(enhanceMode) && refreshHz != 90) {
            refreshHz = 90;
            prefs.edit().putInt("directRefreshHz", 90).apply();
            if (hzBtn != null) hzBtn.setText("90 Hz");
            applyRefreshRate();
        } else if (!CaptureCadenceController.isFg90(enhanceMode) && refreshHz != 120) {
            refreshHz = 120;
            prefs.edit().putInt("directRefreshHz", 120).apply();
            if (hzBtn != null) hzBtn.setText("120 Hz");
            applyRefreshRate();
        }
        if (webView != null && pageLoaded) {
            webView.evaluateJavascript("GTA60Direct.setEnhance(" + enhanceMode + ")", null);
        }
        if (streaming) scheduleAutoHideHud(8000);
    }

    void setLookMode(int mode) {
        lookMode = Math.max(0, Math.min(LOOK_LABELS.length - 1, mode));
        prefs.edit().putInt("directLookMode", lookMode).apply();
        if (lookBtn != null) lookBtn.setText(LOOK_LABELS[lookMode]);
        if (webView != null && pageLoaded) {
            webView.evaluateJavascript("GTA60Direct.setLook(" + lookMode + ")", null);
        }
        if (streaming) scheduleAutoHideHud(8000);
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
            handler.removeCallbacks(autoHideHudTick);
        }
    }

    private void scheduleAutoHideHud(long delayMs) {
        handler.removeCallbacks(autoHideHudTick);
        if (visible && streaming) {
            handler.postDelayed(autoHideHudTick, delayMs);
        }
    }

    private void applyRefreshRate() {
        SpatialDisplayBridge.requestRefreshRate(refreshHz);
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).applyDisplayRefreshRate(refreshHz);
        }
    }

    // ---------------------------------------------------------------- actions

    void signIn() {
        if (signingIn) return;
        signingIn = true;
        setStatus("Requesting a sign-in code...");
        io.execute(() -> {
            try {
                final XboxDirect.DeviceCode code = xbox.beginSignIn();
                runOnUi(() -> showDeviceCode(code));
            } catch (Exception e) {
                signingIn = false;
                fail("Sign-in request failed: " + e.getMessage());
            }
        });
    }

    void chooseConsole() {
        if (!xbox.signedIn()) {
            signIn();
            return;
        }
        setStatus("Finding consoles...");
        io.execute(() -> {
            try {
                final List<XboxDirect.Console> consoles = xbox.consoles();
                runOnUi(() -> {
                    if (consoles.isEmpty()) {
                        setStatus("No Xbox found on this account â€” enable Remote features on your Xbox (no IP needed)");
                        return;
                    }
                    if (consoles.size() == 1) {
                        selected = consoles.get(0);
                        updateButtons();
                        setStatus("Console: " + selected.name);
                        return;
                    }
                    final String[] names = new String[consoles.size()];
                    for (int i = 0; i < consoles.size(); i++) {
                        names[i] = consoles.get(i).name + " (" + consoles.get(i).consoleType + ", "
                                + consoles.get(i).powerState + ")";
                    }
                    new AlertDialog.Builder(activity)
                            .setTitle("Console")
                            .setItems(names, (d, which) -> {
                                selected = consoles.get(which);
                                updateButtons();
                                setStatus("Console: " + selected.name);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            } catch (Exception e) {
                fail("Console list failed: " + e.getMessage());
            }
        });
    }

    void start() {
        start(false);
    }

    private void start(boolean isAutoRetry) {
        if (streaming || !busy.compareAndSet(false, true)) return;
        if (!xbox.signedIn()) {
            busy.set(false);
            signIn();
            return;
        }
        stopped = false;
        lastStartMs = SystemClock.elapsedRealtime();
        if (!isAutoRetry) {
            autoRetriedDisconnect = false;
        }
        sessionGeneration++;
        applyRefreshRate();
        refreshLocalPad("start");
        setStatus("Provisioning a console session...");
        io.execute(() -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).releasePcSessionBlocking();
            }
            if (stopped) {
                busy.set(false);
                return;
            }
            String id = "";
            try {
                XboxDirect.Console console = selected;
                if (console == null) {
                    final List<XboxDirect.Console> consoles = xbox.consoles();
                    if (consoles.isEmpty()) throw new IllegalStateException("no console found on this account");
                    console = consoles.get(0);
                    final XboxDirect.Console chosen = console;
                    runOnUi(() -> selected = chosen);
                }
                id = xbox.play(console.serverId);
                sessionId = id;
                final XboxDirect.Console target = console;
                runOnUi(() -> setStatus("Attaching to " + target.name + "..."));
                boolean connectSent = false;
                for (int attempt = 0; attempt < 60 && !stopped; attempt++) {
                    final String state = xbox.state(id);
                    Log.i(TAG, "session state: " + state);
                    if ("Provisioned".equals(state)) break;
                    if ("ReadyToConnect".equals(state) && !connectSent) {
                        xbox.connect(id);
                        connectSent = true;
                        Log.i(TAG, "connect sent (ReadyToConnect)");
                    }
                    Thread.sleep(1000);
                }
                if (stopped) {
                    xbox.stop(id);
                    return;
                }
                runOnUi(() -> {
                    if (!visible || webView == null) return;
                    streaming = true;
                    pageStreaming = false;
                    busy.set(false);
                    updateButtons();
                    ensurePadTicker(true);
                    pushPadPresence();
                    setStatus("Starting WebRTC to " + (selected != null ? selected.name : "console") + "...");
                    handler.removeCallbacks(startWatchdog);
                    handler.postDelayed(startWatchdog, 35000);
                    if (pageLoaded) {
                        webView.evaluateJavascript(startJs(), null);
                    } else {
                        startWhenReady = true;
                    }
                });
            } catch (Exception e) {
                busy.set(false);
                if (!id.isEmpty()) {
                    final String failedId = id;
                    io.execute(() -> xbox.stop(failedId));
                }
                runOnUi(() -> {
                    streaming = false;
                    updateButtons();
                });
                String why = String.valueOf(e.getMessage());
                if (why.contains("ServerStartStreamingV2CommandSent")) {
                    why += " - the console is stuck in a failed streaming start; restart the Xbox "
                            + "(Guide > Restart console) and try again.";
                }
                fail("XRemote start failed: " + why);
            }
        });
    }

    void stop() {
        stop(false);
    }

    /**
     * Ends a direct session in the order the console needs: neutralize and release the virtual pad
     * first, then close the peer connection, then release the console session. The 350 ms delay is
     * deliberate - the pad release is a data-channel message and closing the peer connection in the
     * same JS turn can drop it, leaving pad 0 (and a held button) on the console after we are gone.
     */
    private void stop(boolean immediate) {
        stopped = true;
        final String id = sessionId;
        final int generation = ++sessionGeneration;
        sessionId = "";
        streaming = false;
        pageStreaming = false;
        busy.set(false);
        handler.removeCallbacks(keepaliveTick);
        handler.removeCallbacks(autoHideHudTick);
        handler.removeCallbacks(startWatchdog);
        ensurePadTicker(false);
        runOnUi(() -> {
            if (webView != null && pageLoaded) {
                // releasePad() before stop(); both are ordered on the WebView's JS queue.
                webView.evaluateJavascript("GTA60Direct.releasePad()", null);
            }
            if (immediate) {
                if (webView != null) webView.evaluateJavascript("GTA60Direct.stop()", null);
                stopSessionAsync(id);
                finishStopUi(generation);
            } else {
                handler.postDelayed(() -> {
                    if (generation == sessionGeneration && webView != null) {
                        webView.evaluateJavascript("GTA60Direct.stop()", null);
                    }
                    stopSessionAsync(id);
                    finishStopUi(generation);
                }, 350);
            }
        });
    }

    /** Releases the console-side session; safe if the controller is already shutting down. */
    private void stopSessionAsync(String id) {
        if (id == null || id.isEmpty() || io.isShutdown()) return;
        try {
            io.execute(() -> xbox.stop(id));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void finishStopUi(int generation) {
        if (generation != sessionGeneration) return;
        runOnUi(() -> {
            if (!visible) return;
            if (bar != null) bar.setVisibility(View.VISIBLE);
            updateButtons();
            setStatus("XRemote: idle");
        });
    }

    void pad(int buttons, short lx, short ly, short rx, short ry, int lt, int rt) {
        padButtons = buttons;
        padLX = lx;
        padLY = ly;
        padRX = rx;
        padRY = ry;
        padLt = lt;
        padRt = rt;
        // Input from a real pad is the strongest possible presence signal: the platform listener
        // can miss a fast Bluetooth reconnect, and dropping the report silently would be worse.
        if (!localPadPresent) {
            localPadPresent = true;
            pushPadPresence();
        }
        if (buttons != lastSentButtons) {
            flushPadNow();
        } else {
            padDirty = true;
        }
    }

    private void flushPadNow() {
        if (!streaming || webView == null || !visible) return;
        padDirty = false;
        lastSentButtons = padButtons;
        lastPadPostMs = SystemClock.uptimeMillis();
        final String js = "GTA60Direct.p(" + padButtons + "," + padLX + "," + padLY + ","
                + padRX + "," + padRY + "," + padLt + "," + padRt + ")";
        webView.evaluateJavascript(js, null);
    }

    // ---------------------------------------------------------------- Gamepad presence

    private final InputManager.InputDeviceListener inputDeviceListener = new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {
            refreshLocalPad("device " + deviceId + " added");
        }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            refreshLocalPad("device " + deviceId + " removed");
        }

        @Override
        public void onInputDeviceChanged(int deviceId) {
            refreshLocalPad("device " + deviceId + " changed");
        }
    };

    private boolean detectLocalPad() {
        if (inputManager == null) return false;
        try {
            for (int id : inputManager.getInputDeviceIds()) {
                final InputDevice device = InputDevice.getDevice(id);
                if (device == null || device.isVirtual()) continue;
                if ((device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Recomputes whether the headset has a gamepad and tells the page. A pad appearing triggers the
     * page's add/remove/add re-announce; a pad disappearing only stops the Java-side reports (the
     * page's 20 s keep-alive keeps pad 0 announced, because the console kicks a session with no pad).
     */
    private void refreshLocalPad(String why) {
        final boolean present = detectLocalPad();
        if (present == localPadPresent && why == null) return;
        final boolean changed = present != localPadPresent;
        localPadPresent = present;
        if (changed) {
            Log.i(TAG, "local gamepad " + (present ? "present" : "absent") + " (" + why + ")");
        }
        if (!present) {
            padDirty = false;
            lastSentButtons = 0;
        }
        if (changed) pushPadPresence();
    }

    private void pushPadPresence() {
        if (!streaming || !pageLoaded || webView == null) return;
        webView.evaluateJavascript("GTA60Direct.setPadPresent(" + localPadPresent + ")", null);
    }

    /**
     * The "Fix pad" action. Replays the handshake's gamepadChanged add/remove/add so a game that
     * resumed with a stale controller binding gets a fresh pad-arrival event without being closed.
     */
    private void fixPad() {
        if (!streaming || webView == null || !pageLoaded) {
            setStatus("Start an XRemote stream first");
            return;
        }
        refreshLocalPad("fix pad");
        webView.evaluateJavascript("GTA60Direct.resyncPad()", null);
        setStatus(localPadPresent
                ? "Pad re-synced (Quest controller) - try the controller now"
                : "Virtual pad released (Xbox-paired controller keeps Player 1)");
        scheduleAutoHideHud(8000);
    }

    // ---------------------------------------------------------------- WebView plumbing

    private void createViews() {
        webView = new WebView(activity);
        webView.setBackgroundColor(Color.BLACK);
        webView.setKeepScreenOn(true);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.addJavascriptInterface(new Bridge(), "GTA60Bridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // the page is a local asset; never navigate away
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                view.evaluateJavascript("GTA60Direct.setEnhance(" + enhanceMode + ");GTA60Direct.setLook(" + lookMode + ")", null);
                if (startWhenReady && streaming) {
                    startWhenReady = false;
                    view.evaluateJavascript(startJs(), null);
                }
            }
        });
        webView.setVisibility(View.GONE);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#E6111622"));
        final int pad = dp(8);
        bar.setPadding(pad, pad, pad, pad);

        hoverHintText = new TextView(activity);
        hoverHintText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hoverHints = new HoverHintHelper(hoverHintText,
                "Point at any option to see what it does \u2022 Tap video anytime to show/hide menu");

        // Row 1: Connect, Play & View
        final LinearLayout row1 = new LinearLayout(activity);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = new TextView(activity);
        title.setText("GTA-Sixty");
        title.setTextColor(Color.parseColor("#22C55E"));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setPadding(0, 0, dp(10), 0);
        row1.addView(title);

        platformBtn = button(ConsolePlatform.toggleButtonLabel(ConsolePlatform.XBOX),
                ConsolePlatform.accentHex(ConsolePlatform.XBOX), v -> {
                    if (activity instanceof MainActivity) ((MainActivity) activity).toggleConsolePlatform();
                });
        hoverHints.attach(platformBtn,
                "Console: Switch between Xbox (Direct / Capture / PC) and PlayStation (PS5/PS4 Remote Play / Capture).");
        row1.addView(platformBtn);

        modeBtn = button("Mode: Xbox Direct \u25BE", "#0F766E", v -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).showModePicker();
        });
        hoverHints.attach(modeBtn,
                "Mode: Switch between Xbox Direct (Remote Play, recommended), Capture Card (LAN/USB-C), or PC Relay.");
        row1.addView(modeBtn);

        signInBtn = button("Sign in", "#7C3AED", v -> signIn());
        hoverHints.attach(signInBtn,
                "Sign in: Link your Microsoft/Xbox account once so Quest finds your Xbox automatically on your network.");
        row1.addView(signInBtn);

        consoleBtn = button("Xbox", "#334155", v -> chooseConsole());
        hoverHints.attach(consoleBtn,
                "Xbox: Pick which Xbox console on your account to stream from (auto-selected when found).");
        row1.addView(consoleBtn);

        startStopBtn = button("Start", "#16A34A", v -> {
            if (streaming || busy.get()) stop();
            else start();
        });
        hoverHints.attach(startStopBtn,
                "Start / Stop: Connect directly to your Xbox over your network, or cleanly end the stream.");
        row1.addView(startStopBtn);

        padBtn = button("Fix Pad", "#B45309", v -> fixPad());
        hoverHints.attach(padBtn,
                "Fix Pad: If your controller stops responding in-game, re-sync or release the Quest virtual pad.");
        row1.addView(padBtn);

        final View row1Spacer = new View(activity);
        row1.addView(row1Spacer, new LinearLayout.LayoutParams(0, 1, 1.0f));

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
        hoverHints.attach(netCheckBtn,
                "Net Check: Test Quest Wi-Fi, Xbox Live reachability, LAN servers, and USB capture card status.");
        row1.addView(netCheckBtn);

        final Button adbBtn = button("Wi-Fi ADB", "#2563EB", v -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).openWirelessAdbHelper();
        });
        hoverHints.attach(adbBtn,
                "Wi-Fi ADB: View Quest Wi-Fi IP and open Android Developer Options for wireless debugging.");
        row1.addView(adbBtn);

        updateBtn = button("Update", "#1E293B", v -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).checkForUpdates();
        });
        if (!availableUpdateVersion.isEmpty()) {
            setAvailableUpdateBadge(availableUpdateVersion);
        }
        hoverHints.attach(updateBtn,
                "Update: Check GitHub for newer GTA-Sixty versions and recent enhancements.");
        row1.addView(updateBtn);

        final Button hideBtn = button("Hide Menu", "#475569", v -> toggleHud());
        hoverHints.attach(hideBtn,
                "Hide Menu: Hide this top menu bar (tap anywhere on the video to bring it back).");
        row1.addView(hideBtn);

        final Button helpBtn = button("?", "#334155", v -> showHelp());
        hoverHints.attach(helpBtn,
                "Help: View full setup tips for Xbox Direct, controllers, and display controls.");
        row1.addView(helpBtn);

        bar.addView(row1);

        // Row 2: Picture, Auto-hide & Tools
        final LinearLayout row2 = new LinearLayout(activity);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2Lp.topMargin = dp(6);
        row2.setLayoutParams(r2Lp);

        enhanceBtn = button("Enhance: " + CaptureProtocolHelper.simpleDirectEnhanceLabel(enhanceMode), "#0284C7", v -> {
            setEnhanceMode(CaptureProtocolHelper.nextDirectEnhanceMode(enhanceMode));
        });
        hoverHints.attach(enhanceBtn,
                "Enhance: Cycle Off (raw video), 60+ (60 fps frame gen + SuperRes), or 90+ (90 fps frame gen + SuperRes).");
        row2.addView(enhanceBtn);

        lookBtn = button(LOOK_LABELS[lookMode], "#0F766E", v -> {
            setLookMode((lookMode + 1) % LOOK_LABELS.length);
        });
        hoverHints.attach(lookBtn,
                "Look: Cycle color presets \u2014 Normal, Vivid (punchy contrast), or Night (brighter dark areas).");
        row2.addView(lookBtn);

        hzBtn = button(refreshHz + " Hz", "#1E293B", v -> {
            refreshHz = (refreshHz == 120) ? 90 : 120;
            prefs.edit().putInt("directRefreshHz", refreshHz).apply();
            hzBtn.setText(refreshHz + " Hz");
            applyRefreshRate();
            if (streaming) scheduleAutoHideHud(8000);
        });
        hoverHints.attach(hzBtn,
                "Refresh Rate: Switch headset refresh between 120 Hz (best for 60+/30 fps) and 90 Hz.");
        row2.addView(hzBtn);

        autoHideCheck = new CheckBox(activity);
        autoHideCheck.setText("Auto-hide menu");
        autoHideCheck.setTextColor(Color.parseColor("#E2E8F0"));
        autoHideCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        autoHideCheck.setButtonTintList(ColorStateList.valueOf(Color.parseColor("#22D3EE")));
        autoHideCheck.setChecked(SpatialDisplayBridge.getSettings().autoHideMenuOnController);
        final LinearLayout.LayoutParams chkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chkLp.leftMargin = dp(6);
        chkLp.rightMargin = dp(6);
        autoHideCheck.setLayoutParams(chkLp);
        autoHideCheck.setOnCheckedChangeListener((btn, isChecked) -> {
            if (syncingAutoHideCheck) return;
            final SpatialDisplayBridge.DisplaySettings s = SpatialDisplayBridge.getSettings();
            s.autoHideMenuOnController = isChecked;
            SpatialDisplayBridge.applyFromUi(activity, s);
            if (streaming) scheduleAutoHideHud(8000);
        });
        hoverHints.attach(autoHideCheck,
                "Auto-hide menu: Automatically hide menus 6 seconds after your virtual Xbox controller appears.");
        row2.addView(autoHideCheck);

        bar.addView(row2);

        // Row 3: Hover description strip
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
        status = new TextView(activity);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setPadding(0, dp(4), 0, 0);
        bar.addView(status);

        bar.setVisibility(View.GONE);
        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        updateButtons();
    }

    private void showHelp() {
        final String msg =
                "Xbox Direct mode connects the Quest 3 straight to your Xbox over your network (no PC or IP needed).\n\n"
                + "\u2022 Auto Console Discovery: Sign in once with your Microsoft/Xbox account and GTA-Sixty finds your Xbox automatically.\n\n"
                + "\u2022 Start / Stop: Starts or cleanly ends your Xbox Direct stream and releases the console session.\n\n"
                + "\u2022 Fix Pad: If your controller stops responding in-game, releases or re-syncs the Quest virtual pad so Player 1 works.\n\n"
                + "\u2022 Enhance (Quest GPU):\n"
                + "  - Off: Raw decoded video.\n"
                + "  - 60+: 60 fps optical-flow frame generation + SuperRes sharpening.\n\n"
                + "\u2022 Look & Hz: Choose Normal/Vivid/Night color looks and 120 Hz or 90 Hz refresh.\n\n"
                + "\u2022 Auto-hide menu: Automatically hides the settings menu 3 seconds after the 3D Xbox controller appears.\n\n"
                + "\u2022 Display & Handle: Grab the bottom handle bar to move the screen (thumbstick adjusts depth). Tap Display for Curve, Tilt, Size, Depth, Height, and Jimmy's Room.\n\n"
                + "\u2022 Mode: Switch anytime to Capture Card (HDMI via LAN or USB-C) or PC Relay.\n\n"
                + "\u2022 Updates: Checks GitHub automatically every time you open GTA-Sixty and shows what's new.";

        final android.widget.ScrollView sv = new android.widget.ScrollView(activity);
        final TextView tv = new TextView(activity);
        tv.setText(msg);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        final int p = dp(16);
        tv.setPadding(p, p, p, p);
        sv.addView(tv);

        new AlertDialog.Builder(activity)
                .setTitle("Xbox Direct Mode Help")
                .setView(sv)
                .setNeutralButton("Run Network Check", (d, w) -> {
                    if (activity instanceof MainActivity) {
                        ((MainActivity) activity).openNetworkDiagnostics();
                    }
                })
                .setPositiveButton("Close", null)
                .show();
        if (streaming) scheduleAutoHideHud(8000);
    }

    private Button button(String text, String hex, View.OnClickListener listener) {
        final Button b = new Button(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(hex));
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        b.setOnClickListener(listener);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(5);
        b.setLayoutParams(lp);
        return b;
    }

    private volatile boolean padTickerRunning;

    private final Choreographer.FrameCallback padFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!padTickerRunning) return;
            final long now = SystemClock.uptimeMillis();
            if (streaming && now - lastPresenceCheckMs >= 500) {
                lastPresenceCheckMs = now;
                refreshLocalPad(null);
            }
            if (streaming && localPadPresent && webView != null && visible
                    && (padDirty || now - lastPadPostMs >= 20000)) {
                flushPadNow();
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void ensurePadTicker(boolean on) {
        if (on && !padTickerRunning) {
            padTickerRunning = true;
            Choreographer.getInstance().postFrameCallback(padFrameCallback);
        } else if (!on && padTickerRunning) {
            padTickerRunning = false;
            Choreographer.getInstance().removeFrameCallback(padFrameCallback);
        }
    }

    private final Runnable keepaliveTick = new Runnable() {
        @Override
        public void run() {
            final String id = sessionId;
            if (streaming && !id.isEmpty()) io.execute(() -> xbox.keepalive(id));
            handler.postDelayed(this, 30000);
        }
    };

    private void showDeviceCode(final XboxDirect.DeviceCode code) {
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Sign in to Xbox")
                .setMessage("On your phone or PC, open\n" + code.verificationUri
                        + "\n\nand enter the code:\n\n" + code.userCode
                        + "\n\nThe console list becomes available when this dialog closes.")
                .setNegativeButton("Cancel", (d, which) -> {
                    dialogOpen = false;
                    signingIn = false;
                })
                .setCancelable(true)
                .create();
        dialog.show();
        dialogOpen = true;
        setStatus("Waiting for sign-in (" + code.userCode + ")");

        io.execute(() -> {
            final long deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L;
            while (dialogOpen && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(code.intervalSeconds * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (!dialogOpen) return;
                try {
                    if (xbox.pollSignIn(code)) {
                        runOnUi(() -> {
                            dialogOpen = false;
                            dialog.dismiss();
                            signingIn = false;
                            updateButtons();
                            setStatus("Signed in" + (xbox.accountHint().isEmpty() ? "" : " (" + xbox.accountHint() + ")") + " \u2014 finding your Xbox...");
                            autoDiscoverConsole();
                        });
                        return;
                    }
                } catch (Exception e) {
                    runOnUi(() -> {
                        dialogOpen = false;
                        dialog.dismiss();
                        signingIn = false;
                        updateButtons();
                        fail(e.getMessage());
                    });
                    return;
                }
            }
            runOnUi(() -> {
                if (dialogOpen) {
                    dialogOpen = false;
                    dialog.dismiss();
                    signingIn = false;
                    fail("Sign-in timed out");
                }
            });
        });
    }

    private void updateButtons() {
        if (signInBtn == null) return;
        final boolean signedIn = xbox.signedIn();
        signInBtn.setText(signedIn ? "Signed in" : "Sign in");
        signInBtn.setEnabled(!signedIn && !signingIn);
        consoleBtn.setText(selected == null ? "Xbox" : "Xbox: " + selected.name);
        consoleBtn.setEnabled(signedIn && !streaming);
        if (startStopBtn != null) {
            final boolean active = streaming || busy.get();
            startStopBtn.setText(active ? "Stop" : "Start");
            startStopBtn.setBackgroundColor(Color.parseColor(active ? "#DC2626" : "#16A34A"));
            startStopBtn.setEnabled(active || signedIn);
        }
        if (padBtn != null) padBtn.setEnabled(streaming);
        if (enhanceBtn != null) {
            enhanceBtn.setText("Enhance: " + CaptureProtocolHelper.simpleDirectEnhanceLabel(enhanceMode));
        }
        if (lookBtn != null) lookBtn.setText(LOOK_LABELS[lookMode]);
        if (hzBtn != null) hzBtn.setText(refreshHz + " Hz");
        if (autoHideCheck != null && !syncingAutoHideCheck) {
            syncingAutoHideCheck = true;
            try {
                autoHideCheck.setChecked(SpatialDisplayBridge.getSettings().autoHideMenuOnController);
            } finally {
                syncingAutoHideCheck = false;
            }
        }
    }

    private void setStatus(String text) {
        if (status == null) return;
        status.setText(text);
    }

    private void fail(String message) {
        Log.w(TAG, message);
        handler.removeCallbacks(autoHideHudTick);
        runOnUi(() -> {
            if (bar != null) bar.setVisibility(View.VISIBLE);
            setStatus(message);
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        });
    }

    /** Tears down a start that produced a session but no media, and reports it without hiding Stop. */
    private void failStart(String message) {
        Log.w(TAG, message);
        final String id = sessionId;
        sessionId = "";
        sessionGeneration++;
        streaming = false;
        pageStreaming = false;
        busy.set(false);
        handler.removeCallbacks(keepaliveTick);
        handler.removeCallbacks(autoHideHudTick);
        handler.removeCallbacks(startWatchdog);
        ensurePadTicker(false);
        xbox.clearPersistedSession(id);
        stopSessionAsync(id);
        runOnUi(() -> {
            if (webView != null) webView.evaluateJavascript("GTA60Direct.stop()", null);
            if (bar != null) bar.setVisibility(View.VISIBLE);
            updateButtons();
            fail(message);
        });
    }

    private void runOnUi(Runnable r) {
        activity.runOnUiThread(r);
    }

    /** Start expression carrying this device's LAN IPv4, GPU enhancement mode, look preset, and local pad state. */
    private String startJs() {
        String ip = "";
        for (String a : xbox.localAddresses()) {
            if (!a.contains(":")) {
                ip = a;
                break;
            }
        }
        return "GTA60Direct.start({localIp:" + JSONObject.quote(ip)
                + ",enhance:" + enhanceMode
                + ",look:" + lookMode
                + ",padPresent:" + localPadPresent
                + ",displayHz:" + refreshHz + "})";
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void applyGamepadRumble(int leftMotor, int rightMotor, int leftTrigger, int rightTrigger, int durationMs) {
        try {
            final int leftAmp = clampInt(Math.max(leftMotor, leftTrigger) * 255 / 100, 0, 255);
            final int rightAmp = clampInt(Math.max(rightMotor, rightTrigger) * 255 / 100, 0, 255);
            final int dur = clampInt(durationMs == 0 ? 120 : durationMs, 20, 500);
            for (int devId : InputDevice.getDeviceIds()) {
                final InputDevice dev = InputDevice.getDevice(devId);
                if (dev == null) continue;
                final int src = dev.getSources();
                if ((src & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
                        && (src & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
                    continue;
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    final VibratorManager vm = dev.getVibratorManager();
                    if (vm == null) continue;
                    final int[] ids = vm.getVibratorIds();
                    if (ids == null || ids.length == 0) continue;
                    if (leftAmp == 0 && rightAmp == 0) {
                        vm.cancel();
                        continue;
                    }
                    if (ids.length >= 2) {
                        final CombinedVibration.ParallelCombination combo = CombinedVibration.startParallel();
                        if (leftAmp > 0) combo.addVibrator(ids[0], VibrationEffect.createOneShot(dur, leftAmp));
                        if (rightAmp > 0) combo.addVibrator(ids[1], VibrationEffect.createOneShot(dur, rightAmp));
                        vm.vibrate(combo.combine());
                    } else {
                        final int maxAmp = Math.max(leftAmp, rightAmp);
                        vm.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(dur, maxAmp));
                    }
                } else {
                    @SuppressWarnings("deprecation")
                    final Vibrator v = dev.getVibrator();
                    if (v != null && v.hasVibrator()) {
                        final int maxAmp = Math.max(leftAmp, rightAmp);
                        if (maxAmp == 0) v.cancel();
                        else v.vibrate(VibrationEffect.createOneShot(dur, maxAmp));
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public void log(String message) {
            Log.i(TAG, "js: " + message);
            if (message != null && message.startsWith("direct: streaming")) {
                pageStreaming = true;
                handler.removeCallbacks(startWatchdog);
                runOnUi(() -> {
                    updateButtons();
                    setStatus("Xbox Direct: streaming (" + CaptureProtocolHelper.simpleDirectEnhanceLabel(enhanceMode) + ", " + LOOK_LABELS[lookMode] + ", " + refreshHz + " Hz)");
                    handler.removeCallbacks(keepaliveTick);
                    handler.postDelayed(keepaliveTick, 30000);
                    scheduleAutoHideHud(5000);
                    // The handshake announced pad 0. Tell the page whether a real controller is
                    // attached here (it only triggers a re-announce, never a removal).
                    refreshLocalPad("streaming");
                    pushPadPresence();
                });
            }
        }

        @JavascriptInterface
        public void onRumble(int leftMotor, int rightMotor, int leftTrigger, int rightTrigger, int durationMs) {
            applyGamepadRumble(leftMotor, rightMotor, leftTrigger, rightTrigger, durationMs);
        }

        /** Debug aid: write the exact offer to the app's external files dir for adb pull. */
        @JavascriptInterface
        public void saveDebug(String name, String text) {
            try {
                final java.io.File dir = activity.getExternalFilesDir(null);
                if (dir == null) return;
                final java.io.File file = new java.io.File(dir, name);
                try (java.io.FileWriter w = new java.io.FileWriter(file, false)) {
                    w.write(text);
                }
                Log.i(TAG, "saved debug file " + file.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "saveDebug failed: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void onState(String state) {
            if (state == null) return;
            if ("toggleHud".equals(state)) {
                runOnUi(() -> toggleHud());
                return;
            }
            if (state.startsWith("stats:")) {
                final String[] p = state.split(":");
                if (p.length >= 4) {
                    final String res = p.length >= 5 ? (" " + p[4]) : "";
                    final String rtt = p.length >= 6 ? (", rtt " + p[5] + " ms") : "";
                    final String uniq = p.length >= 7 ? (" (uniq " + p[6] + "->" + p[1] + " fps") : (" " + p[1] + " fps");
                    final String mode = p.length >= 8 ? (" [" + p[7] + "]") : "";
                    final String gpu = p.length >= 9 ? (" gpu " + p[8] + "ms)") : (p.length >= 7 ? ")" : "");
                    final String bwe = p.length >= 10 ? (", bwe " + p[9] + " Mbps") : "";
                    final String text = "XRemote:" + res + uniq + mode + gpu + ", " + p[2] + " Mbps" + bwe + ", jb " + p[3] + " ms" + rtt;
                    Log.i(TAG, text);
                    // Only repaint the Android status TextView when the top HUD bar is visible.
                    runOnUi(() -> {
                        if (bar != null && bar.getVisibility() == View.VISIBLE) {
                            setStatus(text);
                        }
                    });
                }
            } else if ("streaming".equals(state)) {
                runOnUi(() -> {
                    setStatus("Xbox Direct: streaming (" + CaptureProtocolHelper.simpleDirectEnhanceLabel(enhanceMode) + ", " + LOOK_LABELS[lookMode] + ", " + refreshHz + " Hz)");
                    updateButtons();
                    scheduleAutoHideHud(5000);
                });
            } else if ("padIdle".equals(state) || "padReleased".equals(state)) {
                runOnUi(() -> {
                    if (bar != null && bar.getVisibility() == View.VISIBLE) {
                        setStatus("No controller on the headset - pad 0 stays announced (console needs it)");
                    }
                });
            } else if ("padPresent".equals(state)) {
                runOnUi(() -> {
                    if (bar != null && bar.getVisibility() == View.VISIBLE) {
                        setStatus("Controller connected - pad 0 re-announced");
                    }
                });
            } else if ("padResync".equals(state)) {
                Log.i(TAG, "virtual pad re-synced on the console");
            }
        }

        @JavascriptInterface
        public void onError(String message) {
            final String id = sessionId;
            final int generation = ++sessionGeneration;
            sessionId = "";
            pageStreaming = false;
            handler.removeCallbacks(startWatchdog);
            handler.removeCallbacks(autoHideHudTick);
            stopSessionAsync(id);
            final boolean isConsoleEnded = message != null && message.contains("The console ended the session");
            final boolean recentStart = (SystemClock.elapsedRealtime() - lastStartMs) < 18_000L;
            if (isConsoleEnded && !stopped && !autoRetriedDisconnect && recentStart) {
                autoRetriedDisconnect = true;
                Log.w(TAG, "Console finished prior session teardown right after start; auto-retrying in 1.8s");
                runOnUi(() -> {
                    if (generation != sessionGeneration) return;
                    streaming = false;
                    ensurePadTicker(false);
                    updateButtons();
                    if (bar != null) bar.setVisibility(View.VISIBLE);
                    setStatus("Console finished prior session teardown â€” reconnecting...");
                    handler.postDelayed(() -> {
                        if (!stopped && visible && !streaming) {
                            start(true);
                        }
                    }, 1800);
                });
                return;
            }
            runOnUi(() -> {
                if (generation != sessionGeneration) return;
                streaming = false;
                ensurePadTicker(false);
                updateButtons();
                fail(message == null ? "XRemote stream error" : message);
            });
        }

        /**
         * Called synchronously from the page once ICE gathering is complete. Returns the console's
         * answer plus its rewritten candidates, or {"error": ...}. Blocking here is intentional:
         * the page has no video yet, and a synchronous return avoids a second async bridge.
         */
        @JavascriptInterface
        public String exchange(String payload) {
            try {
                final String id = sessionId;
                if (id.isEmpty()) throw new IllegalStateException("no active session");
                final JSONObject in = new JSONObject(payload);
                final String answer = xbox.exchangeSdp(id, in.getString("sdp"));
                final JSONArray local = XboxDirect.rewriteLocalCandidates(
                        in.optJSONArray("candidates") == null ? new JSONArray() : in.optJSONArray("candidates"),
                        xbox.localAddresses());
                final JSONArray remote = xbox.exchangeIce(id, local);
                Log.i(TAG, "local candidates: " + local);
                final JSONObject out = new JSONObject().put("sdp", answer).put("candidates", remote);
                Log.i(TAG, "signaling complete: " + remote.length() + " remote candidates");
                return out.toString();
            } catch (Exception e) {
                Log.w(TAG, "signaling failed: " + e.getMessage());
                try {
                    return new JSONObject().put("error", String.valueOf(e.getMessage())).toString();
                } catch (Exception ignored) {
                    return "{\"error\":\"signaling failed\"}";
                }
            }
        }
    }
}
