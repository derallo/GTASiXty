package com.gtasixty.spatial.voice;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Modular facade for the experimental "Jimmy" AI voice assistant.
 * Hosts Jimmy's compact status/subtitle pill inside {@link JimmySpatialPanel} below the virtual
 * screen (so it never sits over gameplay), streams Gemini SSE responses, and runs a 5-second
 * conversational follow-up cutoff.
 */
public final class JimmyVoiceModule {

    public static final String TOP_BAR_HINT =
            "Jimmy AI (Experimental): Hands-free voice assistant \u2014 say 'Jimmy' to snap a game screenshot and ask a question.";

    private static final String TAG = "GTA60-Jimmy";
    private static final String ACTION_JIMMY_VOICE = "com.gtasixty.spatial.JIMMY_VOICE";
    private static final int REQ_RECORD_AUDIO_JIMMY = 4901;
    private static final long SHORT_WAKE_AUDIO_THRESHOLD_MS = 1700L;
    private static final long ACTIVE_FOLLOWUP_TIMEOUT_MS = 5000L;
    private static final long MANUAL_CAPTURE_TIMEOUT_MS = 6000L;

    private static WeakReference<JimmyVoiceModule> activeInstance = new WeakReference<>(null);
    private static WeakReference<JimmyOverlayView> pendingSpatialOverlayView = new WeakReference<>(null);
    private static final CopyOnWriteArrayList<Runnable> STATE_LISTENERS = new CopyOnWriteArrayList<>();

    private final Activity activity;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Jimmy-Worker");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Jimmy-TtsExec");
        t.setDaemon(true);
        return t;
    });

    private final GameScreenshotCapturer screenshotCapturer;
    private final JimmyGeminiClient geminiClient = new JimmyGeminiClient();
    private final JimmyTtsPlayer ttsPlayer = new JimmyTtsPlayer();
    private final JimmyAudioEngine audioEngine;
    private final List<JimmyGeminiClient.ChatTurn> chatHistory = new ArrayList<>();
    private final AtomicInteger turnGeneration = new AtomicInteger(0);

    private volatile JimmyOverlayView spatialOverlayView;
    private volatile JimmyOverlayView.UiState lastUiState = JimmyOverlayView.UiState.DISABLED;
    private volatile String lastUiLabel = "";
    private volatile String lastUiSubtitle = null;

    private volatile JimmyConfig.Snapshot config;
    private volatile boolean pendingManualAskAfterPerm = false;
    private volatile boolean destroyed = false;
    private volatile int lastAudioLevel = 0;

    private final BroadcastReceiver adbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_JIMMY_VOICE.equals(intent.getAction())) return;
            handleControlBroadcast(intent);
        }
    };

    private JimmyVoiceModule(Activity activity, FrameLayout root, SurfaceView surfaceView) {
        this.activity = activity;
        this.config = JimmyConfig.load(activity);
        this.screenshotCapturer = new GameScreenshotCapturer(activity, root, surfaceView);

        this.audioEngine = new JimmyAudioEngine(new JimmyAudioEngine.Listener() {
            @Override
            public void onAudioLevel(int level0To100) {
                lastAudioLevel = level0To100;
                final JimmyOverlayView view = spatialOverlayView;
                if (view != null) view.updateAudioLevel(level0To100);
            }

            @Override
            public void onSpeechStarted(boolean wakeWordRequired) {
                publishOverlayState(
                        JimmyOverlayView.UiState.LISTENING,
                        wakeWordRequired ? "Hearing..." : "Listening...",
                        null);
            }

            @Override
            public void onUtteranceCaptured(short[] pcm16, int durationMs, boolean wakeWordRequired) {
                handleUtterance(pcm16, durationMs, wakeWordRequired);
            }

            @Override
            public void onActiveCountdown(int secondsRemaining) {
                final JimmyOverlayView view = spatialOverlayView;
                if (view != null) view.updateCountdownSeconds(secondsRemaining);
            }

            @Override
            public void onActiveTimeout() {
                if (!config.enabled) return;
                resumeListeningIdle(config);
            }

            @Override
            public void onError(String message) {
                publishOverlayState(JimmyOverlayView.UiState.ERROR, "Mic Error", message);
            }
        });

        final IntentFilter filter = new IntentFilter(ACTION_JIMMY_VOICE);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(adbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            @SuppressWarnings("deprecation")
            final Intent ignored = activity.registerReceiver(adbReceiver, filter);
        }

        activeInstance = new WeakReference<>(this);
        final JimmyOverlayView pendingView = pendingSpatialOverlayView.get();
        if (pendingView != null) {
            bindSpatialOverlayView(pendingView);
        }
        applyConfigAndRefresh(false);
    }

    public static JimmyVoiceModule attach(Activity activity, FrameLayout root, SurfaceView surfaceView) {
        if (activity == null || root == null) return null;
        return new JimmyVoiceModule(activity, root, surfaceView);
    }

    public static View createSpatialOverlayView(Context context) {
        final JimmyOverlayView view = new JimmyOverlayView(context, new JimmyOverlayView.Actions() {
            @Override
            public void onPillClicked() {
                final JimmyVoiceModule mod = activeInstance.get();
                if (mod != null) mod.handlePillClick();
            }

            @Override
            public void onSettingsClicked() {
                openSettings(context);
            }
        });
        pendingSpatialOverlayView = new WeakReference<>(view);
        final JimmyVoiceModule mod = activeInstance.get();
        Log.i(TAG, "Created spatial overlay view (boundNow=" + (mod != null) + ")");
        if (mod != null) {
            mod.bindSpatialOverlayView(view);
        }
        return view;
    }

    private void bindSpatialOverlayView(JimmyOverlayView view) {
        final JimmyOverlayView prev = this.spatialOverlayView;
        if (prev != null && prev != view) {
            prev.dispose();
        }
        this.spatialOverlayView = view;
        Log.i(TAG, "Bound spatial overlay view (state=" + lastUiState + ", label=" + lastUiLabel + ")");
        view.updateState(lastUiState, lastUiLabel, lastUiSubtitle);
    }

    private void publishOverlayState(JimmyOverlayView.UiState state, String label, String subtitle) {
        lastUiState = state;
        lastUiLabel = label;
        lastUiSubtitle = subtitle;
        final JimmyOverlayView view = spatialOverlayView;
        if (view != null) {
            view.updateState(state, label, subtitle);
        }
    }

    public static Button createTopBarButton(Activity activity, Runnable onBeforeOpen) {
        final Button b = new Button(activity);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        final float density = activity.getResources().getDisplayMetrics().density;
        final int padH = Math.round(9 * density);
        final int height = Math.round(34 * density);
        final int marginR = Math.round(6 * density);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, height);
        lp.rightMargin = marginR;
        b.setLayoutParams(lp);
        b.setPadding(padH, 0, padH, 0);

        final Runnable sync = () -> {
            final boolean on = isEnabled();
            b.setText(on ? "Jimmy AI: On" : "Jimmy AI");
            b.setBackgroundColor(Color.parseColor(on ? "#0284C7" : "#7C3AED"));
        };
        sync.run();

        b.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                addStateListener(sync);
                sync.run();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                removeStateListener(sync);
            }
        });

        b.setOnClickListener(v -> {
            if (onBeforeOpen != null) onBeforeOpen.run();
            openSettings(activity);
        });
        return b;
    }

    public static void openSettings(Context context) {
        final JimmyVoiceModule mod = activeInstance.get();
        if (mod != null) {
            mod.uiHandler.post(mod::showSettingsDialog);
        }
    }

    public static boolean isEnabled() {
        final JimmyVoiceModule mod = activeInstance.get();
        return mod != null && mod.config.enabled;
    }

    public static int getLastAudioLevel() {
        final JimmyVoiceModule mod = activeInstance.get();
        return mod != null ? mod.lastAudioLevel : 0;
    }

    public static boolean hasMicPermission(Context context) {
        return context != null
                && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    public static void addStateListener(Runnable listener) {
        if (listener != null && !STATE_LISTENERS.contains(listener)) {
            STATE_LISTENERS.add(listener);
        }
    }

    public static void removeStateListener(Runnable listener) {
        STATE_LISTENERS.remove(listener);
    }

    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_RECORD_AUDIO_JIMMY) return false;
        final boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            applyConfigAndRefresh(pendingManualAskAfterPerm);
            pendingManualAskAfterPerm = false;
        } else {
            pendingManualAskAfterPerm = false;
            publishOverlayState(
                    JimmyOverlayView.UiState.ERROR,
                    "Mic Needed",
                    "Microphone permission denied.");
        }
        return true;
    }

    public void onPause() {
        audioEngine.stop();
        ttsPlayer.stopSpeaking();
    }

    public void onResume() {
        if (destroyed) return;
        this.config = JimmyConfig.load(activity);
        applyConfigAndRefresh(false);
    }

    public void destroy() {
        destroyed = true;
        turnGeneration.incrementAndGet();
        geminiClient.cancelCurrent();
        ttsPlayer.stopSpeaking();
        audioEngine.stop();
        screenshotCapturer.destroy();
        final JimmyOverlayView view = spatialOverlayView;
        spatialOverlayView = null;
        if (view != null) view.dispose();
        uiHandler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        ttsExecutor.shutdownNow();
        try {
            activity.unregisterReceiver(adbReceiver);
        } catch (Throwable ignored) {}
        if (activeInstance.get() == this) {
            activeInstance = new WeakReference<>(null);
        }
    }

    private void showSettingsDialog() {
        JimmySettingsDialog.show(activity, config, new JimmySettingsDialog.Callbacks() {
            @Override
            public void onSaved(JimmyConfig.Snapshot updated) {
                saveAndApply(updated, false);
            }

            @Override
            public void onAskNowRequested(JimmyConfig.Snapshot currentDraft) {
                saveAndApply(currentDraft, true);
            }

            @Override
            public void onClearChatRequested() {
                synchronized (chatHistory) {
                    chatHistory.clear();
                }
            }

            @Override
            public void onClearKeysRequested() {
                final JimmyConfig.Snapshot cleared = new JimmyConfig.Snapshot(config);
                cleared.geminiApiKey = "";
                cleared.elevenLabsApiKey = "";
                cleared.enabled = false;
                saveAndApply(cleared, false);
            }

            @Override
            public void onRequestMicPermission() {
                activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO_JIMMY);
            }
        });
    }

    private void saveAndApply(JimmyConfig.Snapshot updated, boolean askImmediately) {
        this.config = new JimmyConfig.Snapshot(updated);
        JimmyConfig.save(activity, this.config);
        notifyStateListeners();
        applyConfigAndRefresh(askImmediately);
    }

    private void applyConfigAndRefresh(boolean askImmediately) {
        final JimmyConfig.Snapshot cfg = this.config;
        audioEngine.configure(cfg.wakeWordEnabled, cfg.sensitivity);

        if (!cfg.enabled) {
            turnGeneration.incrementAndGet();
            geminiClient.cancelCurrent();
            ttsPlayer.stopSpeaking();
            audioEngine.stop();
            publishOverlayState(JimmyOverlayView.UiState.DISABLED, "", null);
            return;
        }

        if (!cfg.hasGeminiKey()) {
            audioEngine.stop();
            publishOverlayState(
                    JimmyOverlayView.UiState.NEEDS_KEYS,
                    "Add Gemini Key",
                    "Tap Jimmy AI button to add key.");
            return;
        }

        if (!hasMicPermission(activity)) {
            pendingManualAskAfterPerm = askImmediately;
            publishOverlayState(
                    JimmyOverlayView.UiState.NEEDS_KEYS,
                    "Grant Mic",
                    "Allow microphone access for Jimmy.");
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO_JIMMY);
            return;
        }

        audioEngine.start();
        if (askImmediately) {
            triggerManualAsk();
        } else {
            resumeListeningIdle(cfg);
        }
    }

    private void handlePillClick() {
        if (!config.enabled || !config.hasGeminiKey()) {
            showSettingsDialog();
            return;
        }
        if (ttsPlayer.isSpeaking()) {
            turnGeneration.incrementAndGet();
            ttsPlayer.stopSpeaking();
            audioEngine.setPausedForBusy(false);
            triggerManualAsk();
            return;
        }
        triggerManualAsk();
    }

    private void triggerManualAsk() {
        if (!config.enabled || !config.hasGeminiKey()) {
            showSettingsDialog();
            return;
        }
        if (!hasMicPermission(activity)) {
            applyConfigAndRefresh(true);
            return;
        }
        if (!audioEngine.isRunning()) audioEngine.start();

        audioEngine.setPausedForBusy(true);
        ttsPlayer.playChimeAsync(true);
        publishOverlayState(JimmyOverlayView.UiState.LISTENING, "Listening (6s)", null);
        uiHandler.postDelayed(() -> {
            if (destroyed || !config.enabled) return;
            audioEngine.setPausedForBusy(false);
            audioEngine.triggerManualCapture(MANUAL_CAPTURE_TIMEOUT_MS);
        }, 200L);
    }

    private void handleUtterance(short[] pcm16, int durationMs, boolean wakeWordRequired) {
        final JimmyConfig.Snapshot cfg = this.config;
        if (!cfg.enabled || !cfg.hasGeminiKey() || destroyed) return;

        final int gen = turnGeneration.incrementAndGet();
        final byte[] wavAudio = JimmyAudioBuffer.encodePcm16ToWav(pcm16, JimmyAudioBuffer.SAMPLE_RATE_16K);

        worker.execute(() -> {
            if (turnGeneration.get() != gen || destroyed) return;
            audioEngine.setPausedForBusy(true);
            final long tStart = System.currentTimeMillis();
            final AtomicBoolean earlySpoke = new AtomicBoolean(false);
            try {
                final boolean useTwoTierWakeGate = wakeWordRequired && durationMs < SHORT_WAKE_AUDIO_THRESHOLD_MS;
                final byte[] screenshot;
                final JimmyGeminiClient.AssistantResult result;

                final JimmyGeminiClient.EarlySpeechListener earlyListener = speech -> {
                    if (turnGeneration.get() == gen && !destroyed && earlySpoke.compareAndSet(false, true)) {
                        startSpeakingAsync(cfg, speech, false, gen);
                    }
                };

                if (useTwoTierWakeGate) {
                    publishOverlayState(JimmyOverlayView.UiState.THINKING, "Checking...", null);
                    final JimmyGeminiClient.AssistantResult wakeCheck = geminiClient.askStreaming(
                            cfg.geminiApiKey,
                            cfg.geminiFallbackModel,
                            cfg.geminiFallbackModel,
                            wavAudio,
                            null,
                            null,
                            snapshotHistory(),
                            true,
                            false,
                            null);
                    if (turnGeneration.get() != gen || destroyed) return;
                    Log.i(TAG, "Wake gate: wake=" + wakeCheck.wakeDetected + " followUp=" + wakeCheck.needsFollowUp
                            + " ms=" + (System.currentTimeMillis() - tStart));
                    if (!wakeCheck.wakeDetected) {
                        resumeListeningIdle(cfg);
                        audioEngine.setPausedForBusy(false);
                        return;
                    }
                    if (wakeCheck.needsFollowUp) {
                        ttsPlayer.playChimeAsync(true);
                        publishOverlayState(JimmyOverlayView.UiState.LISTENING, "Listening (5s)", null);
                        Thread.sleep(190L);
                        audioEngine.setPausedForBusy(false);
                        audioEngine.enterActiveQuestionMode(ACTIVE_FOLLOWUP_TIMEOUT_MS);
                        return;
                    }
                    publishOverlayState(JimmyOverlayView.UiState.THINKING, "Thinking...", null);
                    final long tShot = System.currentTimeMillis();
                    screenshot = screenshotCapturer.captureJpegBytes();
                    Log.i(TAG, "Screenshot bytes=" + (screenshot == null ? 0 : screenshot.length)
                            + " ms=" + (System.currentTimeMillis() - tShot));
                    result = geminiClient.askStreaming(
                            cfg.geminiApiKey,
                            cfg.geminiModel,
                            cfg.geminiFallbackModel,
                            wavAudio,
                            screenshot,
                            null,
                            snapshotHistory(),
                            false,
                            cfg.webSearchEnabled,
                            earlyListener);
                } else {
                    publishOverlayState(
                            JimmyOverlayView.UiState.THINKING,
                            wakeWordRequired ? "Checking..." : "Thinking...",
                            null);
                    final long tShot = System.currentTimeMillis();
                    screenshot = screenshotCapturer.captureJpegBytes();
                    Log.i(TAG, "Screenshot bytes=" + (screenshot == null ? 0 : screenshot.length)
                            + " ms=" + (System.currentTimeMillis() - tShot));
                    result = geminiClient.askStreaming(
                            cfg.geminiApiKey,
                            cfg.geminiModel,
                            cfg.geminiFallbackModel,
                            wavAudio,
                            screenshot,
                            null,
                            snapshotHistory(),
                            wakeWordRequired,
                            cfg.webSearchEnabled,
                            earlyListener);
                }

                if (turnGeneration.get() != gen || destroyed) return;
                processModelResult(cfg, result, gen, earlySpoke);
            } catch (Throwable t) {
                audioEngine.setPausedForBusy(false);
                if (turnGeneration.get() == gen && !destroyed) {
                    Log.w(TAG, "Jimmy turn failed: " + t.getMessage());
                    ttsPlayer.playChimeAsync(false);
                    publishOverlayState(JimmyOverlayView.UiState.ERROR, "Error", shortError(t.getMessage()));
                    uiHandler.postDelayed(() -> {
                        if (turnGeneration.get() == gen && !destroyed) resumeListeningIdle(cfg);
                    }, 4000);
                }
            }
        });
    }

    private void handleTextAsk(String prompt) {
        final JimmyConfig.Snapshot cfg = this.config;
        if (!cfg.enabled || !cfg.hasGeminiKey() || destroyed) {
            publishOverlayState(
                    JimmyOverlayView.UiState.NEEDS_KEYS,
                    "Add Gemini Key",
                    "Configure Gemini API key first.");
            return;
        }
        final int gen = turnGeneration.incrementAndGet();
        worker.execute(() -> {
            if (turnGeneration.get() != gen || destroyed) return;
            audioEngine.setPausedForBusy(true);
            final AtomicBoolean earlySpoke = new AtomicBoolean(false);
            try {
                publishOverlayState(JimmyOverlayView.UiState.THINKING, "Thinking...", null);
                final long tShot = System.currentTimeMillis();
                final byte[] screenshot = screenshotCapturer.captureJpegBytes();
                Log.i(TAG, "TextAsk screenshot bytes=" + (screenshot == null ? 0 : screenshot.length)
                        + " ms=" + (System.currentTimeMillis() - tShot));

                final JimmyGeminiClient.AssistantResult result = geminiClient.askStreaming(
                        cfg.geminiApiKey,
                        cfg.geminiModel,
                        cfg.geminiFallbackModel,
                        null,
                        screenshot,
                        prompt,
                        snapshotHistory(),
                        false,
                        cfg.webSearchEnabled,
                        speech -> {
                            if (turnGeneration.get() == gen && !destroyed && earlySpoke.compareAndSet(false, true)) {
                                startSpeakingAsync(cfg, speech, false, gen);
                            }
                        });
                if (turnGeneration.get() != gen || destroyed) return;
                processModelResult(cfg, result, gen, earlySpoke);
            } catch (Throwable t) {
                audioEngine.setPausedForBusy(false);
                if (turnGeneration.get() == gen && !destroyed) {
                    Log.w(TAG, "Jimmy text ask failed: " + t.getMessage());
                    publishOverlayState(JimmyOverlayView.UiState.ERROR, "Error", shortError(t.getMessage()));
                    uiHandler.postDelayed(() -> {
                        if (turnGeneration.get() == gen && !destroyed) resumeListeningIdle(cfg);
                    }, 4000);
                }
            }
        });
    }

    private void processModelResult(
            JimmyConfig.Snapshot cfg,
            JimmyGeminiClient.AssistantResult result,
            int gen,
            AtomicBoolean earlySpoke) {
        final String preview = result.speech.replace('\n', ' ').replace('\r', ' ');
        final String shortSpeech = preview.length() > 120 ? preview.substring(0, 120) + "..." : preview;
        Log.i(TAG, "Model result: model=" + result.modelUsed
                + " wake=" + result.wakeDetected
                + " followUp=" + result.needsFollowUp
                + " webSearch=" + result.usedWebSearch
                + (result.webSearchQueries.isEmpty() ? "" : " queries=" + result.webSearchQueries)
                + " speechChars=" + result.speech.length()
                + " speech=\"" + shortSpeech + "\"");
        if (!result.wakeDetected) {
            audioEngine.setPausedForBusy(false);
            resumeListeningIdle(cfg);
            return;
        }
        if (result.needsFollowUp) {
            ttsPlayer.playChimeAsync(true);
            publishOverlayState(JimmyOverlayView.UiState.LISTENING, "Listening (5s)", null);
            try { Thread.sleep(190L); } catch (InterruptedException ignored) {}
            audioEngine.setPausedForBusy(false);
            audioEngine.enterActiveQuestionMode(ACTIVE_FOLLOWUP_TIMEOUT_MS);
            return;
        }

        final String spoken = result.speech;
        if (spoken.isEmpty()) {
            audioEngine.setPausedForBusy(false);
            resumeListeningIdle(cfg);
            return;
        }

        recordHistory(result.userTranscript, spoken);
        if (earlySpoke.compareAndSet(false, true)) {
            startSpeakingAsync(cfg, spoken, result.usedWebSearch, gen);
        } else if (result.usedWebSearch && lastUiState == JimmyOverlayView.UiState.SPEAKING) {
            publishOverlayState(JimmyOverlayView.UiState.SPEAKING, "Jimmy \u2022 Web", spoken);
        }
    }

    private void startSpeakingAsync(JimmyConfig.Snapshot cfg, String spoken, boolean usedWebSearch, int gen) {
        final String speakingLabel = usedWebSearch ? "Jimmy \u2022 Web" : "Jimmy";
        ttsExecutor.execute(() -> {
            if (turnGeneration.get() != gen || destroyed) return;
            ttsPlayer.speak(spoken, cfg, new JimmyTtsPlayer.Listener() {
                @Override
                public void onSpeakStarted(String providerLabel) {
                    if (turnGeneration.get() == gen) {
                        publishOverlayState(
                                JimmyOverlayView.UiState.SPEAKING,
                                speakingLabel,
                                spoken);
                    }
                }

                @Override
                public void onSpeakFinished() {
                    if (turnGeneration.get() == gen && !destroyed) {
                        audioEngine.setPausedForBusy(false);
                        audioEngine.enterActiveQuestionMode(ACTIVE_FOLLOWUP_TIMEOUT_MS);
                        publishOverlayState(
                                JimmyOverlayView.UiState.LISTENING,
                                "Listening (5s)",
                                spoken);
                    }
                }

                @Override
                public void onSpeakError(String message) {
                    if (turnGeneration.get() == gen && !destroyed) {
                        audioEngine.setPausedForBusy(false);
                        publishOverlayState(
                                JimmyOverlayView.UiState.ERROR,
                                "Jimmy (Text)",
                                spoken);
                        uiHandler.postDelayed(() -> {
                            if (turnGeneration.get() == gen && !destroyed) resumeListeningIdle(cfg);
                        }, 4500);
                    }
                }
            });
        });
    }

    private void resumeListeningIdle(JimmyConfig.Snapshot cfg) {
        if (!cfg.enabled) return;
        publishOverlayState(JimmyOverlayView.UiState.IDLE, "Jimmy", null);
    }

    private List<JimmyGeminiClient.ChatTurn> snapshotHistory() {
        synchronized (chatHistory) {
            return new ArrayList<>(chatHistory);
        }
    }

    private void recordHistory(String userTranscript, String assistantSpeech) {
        synchronized (chatHistory) {
            if (userTranscript != null && !userTranscript.trim().isEmpty()) {
                chatHistory.add(new JimmyGeminiClient.ChatTurn("user", userTranscript.trim()));
            }
            if (assistantSpeech != null && !assistantSpeech.trim().isEmpty()) {
                chatHistory.add(new JimmyGeminiClient.ChatTurn("model", assistantSpeech.trim()));
            }
            while (chatHistory.size() > 6) {
                chatHistory.remove(0);
            }
        }
    }

    private void handleControlBroadcast(Intent intent) {
        final JimmyConfig.Snapshot next = new JimmyConfig.Snapshot(config);
        boolean changed = false;

        if (intent.getBooleanExtra("clearKeys", false)) {
            next.geminiApiKey = "";
            next.elevenLabsApiKey = "";
            next.enabled = false;
            changed = true;
        }
        if (intent.hasExtra("geminiKey")) {
            next.geminiApiKey = intent.getStringExtra("geminiKey");
            changed = true;
        }
        if (intent.hasExtra("elevenLabsKey")) {
            next.elevenLabsApiKey = intent.getStringExtra("elevenLabsKey");
            changed = true;
        }
        if (intent.hasExtra("voiceId")) {
            next.elevenLabsVoiceId = intent.getStringExtra("voiceId");
            changed = true;
        }
        if (intent.hasExtra("model")) {
            next.geminiModel = intent.getStringExtra("model");
            next.modelExplicit = true;
            changed = true;
        }
        if (intent.hasExtra("wakeWord")) {
            next.wakeWordEnabled = intent.getBooleanExtra("wakeWord", next.wakeWordEnabled);
            changed = true;
        }
        if (intent.hasExtra("webSearch")) {
            next.webSearchEnabled = intent.getBooleanExtra("webSearch", next.webSearchEnabled);
            changed = true;
        }
        if (intent.hasExtra("enabled")) {
            next.enabled = intent.getBooleanExtra("enabled", next.enabled);
            changed = true;
        }
        if (changed) {
            Log.i(TAG, "Updated Jimmy config via ADB broadcast (enabled=" + next.enabled
                    + ", webSearch=" + next.webSearchEnabled
                    + ", model=" + next.geminiModel
                    + ", gemini=" + JimmyConfig.maskKey(next.geminiApiKey)
                    + ", elevenLabs=" + JimmyConfig.maskKey(next.elevenLabsApiKey) + ")");
            saveAndApply(next, false);
        }
        final String askText = intent.getStringExtra("ask");
        if (askText != null && !askText.trim().isEmpty()) {
            if (!config.enabled) {
                next.enabled = true;
                saveAndApply(next, false);
            }
            handleTextAsk(askText.trim());
        }
    }

    private static void notifyStateListeners() {
        for (Runnable r : STATE_LISTENERS) {
            try {
                r.run();
            } catch (Throwable ignored) {}
        }
    }

    private static String shortError(String msg) {
        if (msg == null || msg.trim().isEmpty()) return "Request failed.";
        final String trimmed = msg.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) + "..." : trimmed;
    }
}
