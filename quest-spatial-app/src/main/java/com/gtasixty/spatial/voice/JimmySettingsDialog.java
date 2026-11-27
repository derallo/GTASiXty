package com.gtasixty.spatial.voice;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-VR configuration dialog for Jimmy AI (Phone LAN Setup, live mic level, live Gemini model
 * discovery, ElevenLabs optional voice, and quick ask/clear actions).
 */
final class JimmySettingsDialog {

    interface Callbacks {
        void onSaved(JimmyConfig.Snapshot updated);
        void onAskNowRequested(JimmyConfig.Snapshot currentDraft);
        void onClearChatRequested();
        void onClearKeysRequested();
        void onRequestMicPermission();
    }

    private JimmySettingsDialog() {}

    static void show(Activity activity, JimmyConfig.Snapshot current, Callbacks callbacks) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        final JimmyConfig.Snapshot draft = new JimmyConfig.Snapshot(current);
        final Handler uiHandler = new Handler(Looper.getMainLooper());
        final AtomicBoolean dialogOpen = new AtomicBoolean(true);

        final ScrollView scroll = new ScrollView(activity);
        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F172A"));
        final int pad = dp(activity, 16);
        root.setPadding(pad, pad, pad, pad);

        final TextView intro = new TextView(activity);
        intro.setText("Say \"Jimmy\" hands-free to snap a game screenshot and ask a question. "
                + "Keys stay locally in this device's private preferences.");
        intro.setTextColor(Color.parseColor("#CBD5E1"));
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        root.addView(intro);

        // Live Mic Status & Level Row
        final LinearLayout micRow = new LinearLayout(activity);
        micRow.setOrientation(LinearLayout.HORIZONTAL);
        micRow.setGravity(Gravity.CENTER_VERTICAL);
        addTopMargin(micRow, dp(activity, 8));
        final TextView micStatusView = new TextView(activity);
        micStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        micRow.addView(micStatusView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        final Button grantMicBtn = smallBtn(activity, "Grant Mic", "#B45309", v -> {
            if (callbacks != null) callbacks.onRequestMicPermission();
        });
        micRow.addView(grantMicBtn);
        root.addView(micRow);

        final Runnable micPoll = new Runnable() {
            @Override
            public void run() {
                if (!dialogOpen.get()) return;
                final boolean granted = JimmyVoiceModule.hasMicPermission(activity);
                if (!granted) {
                    micStatusView.setText("Mic: Permission NOT granted (tap Grant Mic)");
                    micStatusView.setTextColor(Color.parseColor("#F87171"));
                    grantMicBtn.setVisibility(View.VISIBLE);
                } else {
                    grantMicBtn.setVisibility(View.GONE);
                    final int lvl = JimmyVoiceModule.getLastAudioLevel();
                    final boolean active = JimmyVoiceModule.isEnabled();
                    micStatusView.setText(active
                            ? ("Mic: Granted • Live level: " + lvl + "/100")
                            : "Mic: Granted • Enable Jimmy below to listen");
                    micStatusView.setTextColor(Color.parseColor(active ? "#4ADE80" : "#94A3B8"));
                }
                uiHandler.postDelayed(this, 250);
            }
        };

        final CheckBox enableCheck = new CheckBox(activity);
        enableCheck.setText("Enable Jimmy AI Assistant");
        enableCheck.setTextColor(Color.WHITE);
        enableCheck.setChecked(draft.enabled);
        addTopMargin(enableCheck, dp(activity, 8));
        root.addView(enableCheck);

        final CheckBox wakeCheck = new CheckBox(activity);
        wakeCheck.setText("Always-listen \"Jimmy\" wake word (uncheck for tap-pill mode)");
        wakeCheck.setTextColor(Color.parseColor("#E2E8F0"));
        wakeCheck.setChecked(draft.wakeWordEnabled);
        root.addView(wakeCheck);

        final CheckBox webSearchCheck = new CheckBox(activity);
        webSearchCheck.setText("Allow Internet Search (Google Search for game guides & puzzles)");
        webSearchCheck.setTextColor(Color.parseColor("#E2E8F0"));
        webSearchCheck.setChecked(draft.webSearchEnabled);
        root.addView(webSearchCheck);

        // Ephemeral LAN Phone Key Setup Server
        final JimmyLanKeyServer lanServer = new JimmyLanKeyServer();
        final LinearLayout phoneBox = new LinearLayout(activity);
        phoneBox.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable phoneBg = new GradientDrawable();
        phoneBg.setColor(Color.parseColor("#1E293B"));
        phoneBg.setCornerRadius(dp(activity, 8));
        phoneBg.setStroke(dp(activity, 1), Color.parseColor("#0284C7"));
        phoneBox.setBackground(phoneBg);
        final int pbPad = dp(activity, 10);
        phoneBox.setPadding(pbPad, pbPad, pbPad, pbPad);
        addTopMargin(phoneBox, dp(activity, 10));

        final TextView phoneStatus = new TextView(activity);
        phoneStatus.setTextColor(Color.parseColor("#CBD5E1"));
        phoneStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        phoneStatus.setText("Avoid typing keys in VR: start a temporary Wi-Fi page and open it on your phone.");

        final TextView phoneUrlView = new TextView(activity);
        phoneUrlView.setTextColor(Color.parseColor("#22D3EE"));
        phoneUrlView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        phoneUrlView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        phoneUrlView.setTextIsSelectable(true);
        phoneUrlView.setVisibility(View.GONE);
        addTopMargin(phoneUrlView, dp(activity, 6));

        // Gemini Key
        final EditText geminiInput = keyInput(activity, "Paste AIza... key", draft.geminiApiKey);
        final EditText elevenInput = keyInput(activity, "Paste sk_... key (optional)", draft.elevenLabsApiKey);
        final EditText voiceInput = textInput(activity, "Voice ID", draft.elevenLabsVoiceId);
        final EditText modelInput = textInput(activity, JimmyConfig.DEFAULT_GEMINI_MODEL, draft.geminiModel);
        final int[] sensHolder = {draft.sensitivity};

        final Button phoneSetupBtn = smallBtn(activity, "Phone Setup (Open on Phone)", "#0284C7", null);
        ((LinearLayout.LayoutParams) phoneSetupBtn.getLayoutParams()).leftMargin = 0;
        addTopMargin(phoneSetupBtn, dp(activity, 8));
        phoneSetupBtn.setOnClickListener(v -> {
            if (lanServer.isRunning()) {
                lanServer.stop();
                phoneSetupBtn.setText("Phone Setup (Open on Phone)");
                phoneSetupBtn.setBackgroundColor(Color.parseColor("#0284C7"));
                phoneUrlView.setVisibility(View.GONE);
                phoneStatus.setText("Temporary Wi-Fi server stopped.");
                phoneStatus.setTextColor(Color.parseColor("#CBD5E1"));
                return;
            }
            try {
                final String url = lanServer.start(
                        !geminiInput.getText().toString().trim().isEmpty(),
                        !elevenInput.getText().toString().trim().isEmpty(),
                        voiceInput.getText().toString().trim(),
                        received -> activity.runOnUiThread(() -> {
                            if (!received.geminiKey.isEmpty()) {
                                geminiInput.setText(received.geminiKey);
                            }
                            if (!received.elevenLabsKey.isEmpty()) {
                                elevenInput.setText(received.elevenLabsKey);
                            }
                            if (!received.voiceId.isEmpty()) {
                                voiceInput.setText(received.voiceId);
                            }
                            if (!received.geminiModel.isEmpty()) {
                                modelInput.setText(received.geminiModel);
                            }
                            if (received.enableNow) {
                                enableCheck.setChecked(true);
                            }
                            final JimmyConfig.Snapshot updated = readDraft(
                                    enableCheck, wakeCheck, webSearchCheck, geminiInput, elevenInput, voiceInput, modelInput, sensHolder[0], draft);
                            if (callbacks != null) callbacks.onSaved(updated);
                            phoneSetupBtn.setText("Phone Setup (Open on Phone)");
                            phoneSetupBtn.setBackgroundColor(Color.parseColor("#0284C7"));
                            phoneUrlView.setVisibility(View.GONE);
                            phoneStatus.setText("\u2713 Keys received from phone & saved! Temporary server closed.");
                            phoneStatus.setTextColor(Color.parseColor("#4ADE80"));
                        }));
                phoneSetupBtn.setText("Stop Phone Setup Server");
                phoneSetupBtn.setBackgroundColor(Color.parseColor("#DC2626"));
                phoneUrlView.setText(url);
                phoneUrlView.setVisibility(View.VISIBLE);
                phoneStatus.setText("On your phone (same Wi-Fi), open this URL to paste your keys:");
                phoneStatus.setTextColor(Color.parseColor("#E2E8F0"));
            } catch (Exception e) {
                phoneStatus.setText("Could not start Wi-Fi setup server: " + e.getMessage());
                phoneStatus.setTextColor(Color.parseColor("#F87171"));
            }
        });

        phoneBox.addView(phoneStatus);
        phoneBox.addView(phoneUrlView);
        phoneBox.addView(phoneSetupBtn);
        root.addView(phoneBox);

        // Gemini Key row
        root.addView(sectionLabel(activity, "Gemini API Key (Required — Vision + Hearing + Fallback Voice)"));
        root.addView(keyInputRow(activity, geminiInput));

        // ElevenLabs Key row
        root.addView(sectionLabel(activity, "ElevenLabs API Key (Optional — Ultra-fast Voice; falls back to Gemini TTS)"));
        root.addView(keyInputRow(activity, elevenInput));

        // Voice ID row
        root.addView(sectionLabel(activity, "ElevenLabs Voice ID"));
        final LinearLayout voiceRow = new LinearLayout(activity);
        voiceRow.setOrientation(LinearLayout.HORIZONTAL);
        voiceRow.setGravity(Gravity.CENTER_VERTICAL);
        voiceRow.addView(voiceInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        final Button voicePresetBtn = smallBtn(activity, "Presets", "#0369A1", v -> {
            new AlertDialog.Builder(activity)
                    .setTitle("Choose ElevenLabs Voice")
                    .setItems(JimmyConfig.VOICE_LABELS, (d, which) -> {
                        voiceInput.setText(JimmyConfig.VOICE_IDS[which]);
                    })
                    .show();
        });
        voiceRow.addView(voicePresetBtn);
        root.addView(voiceRow);

        // Gemini Model row with live discovery
        root.addView(sectionLabel(activity, "Gemini Model (Safe defaults: gemini-flash-latest)"));
        final LinearLayout modelRow = new LinearLayout(activity);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);
        modelRow.addView(modelInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        @SuppressWarnings("unchecked")
        final List<String>[] cachedLiveModels = new List[]{Arrays.asList(JimmyConfig.MODEL_OPTIONS)};
        final boolean[] liveLoaded = {false};

        final Button modelPickerBtn = smallBtn(activity, "Load / Pick", "#0F766E", null);
        modelPickerBtn.setOnClickListener(v -> {
            final String key = geminiInput.getText().toString().trim();
            if (!liveLoaded[0] && !key.isEmpty()) {
                modelPickerBtn.setText("Loading...");
                modelPickerBtn.setEnabled(false);
                fetchLiveModelsAsync(key, models -> activity.runOnUiThread(() -> {
                    modelPickerBtn.setEnabled(true);
                    modelPickerBtn.setText("Pick Model (" + models.size() + ")");
                    if (!models.isEmpty()) {
                        cachedLiveModels[0] = models;
                        liveLoaded[0] = true;
                    }
                    showModelChooser(activity, cachedLiveModels[0], modelInput);
                }), err -> activity.runOnUiThread(() -> {
                    modelPickerBtn.setEnabled(true);
                    modelPickerBtn.setText("Load / Pick");
                    Toast.makeText(activity, "Using safe presets (" + err + ")", Toast.LENGTH_SHORT).show();
                    showModelChooser(activity, cachedLiveModels[0], modelInput);
                }));
            } else {
                showModelChooser(activity, cachedLiveModels[0], modelInput);
            }
        });
        modelRow.addView(modelPickerBtn);
        root.addView(modelRow);

        if (draft.hasGeminiKey()) {
            fetchLiveModelsAsync(draft.geminiApiKey, models -> activity.runOnUiThread(() -> {
                if (!dialogOpen.get() || models.isEmpty()) return;
                cachedLiveModels[0] = models;
                liveLoaded[0] = true;
                modelPickerBtn.setText("Pick Model (" + models.size() + ")");
            }), err -> {});
        }

        // Sensitivity
        root.addView(sectionLabel(activity, "Mic Wake Sensitivity"));
        final Button sensBtn = smallBtn(
                activity,
                "Sensitivity: " + JimmyConfig.SENSITIVITY_LABELS[sensHolder[0]],
                "#334155",
                null);
        sensBtn.setOnClickListener(v -> {
            sensHolder[0] = (sensHolder[0] + 1) % JimmyConfig.SENSITIVITY_LABELS.length;
            sensBtn.setText("Sensitivity: " + JimmyConfig.SENSITIVITY_LABELS[sensHolder[0]]);
        });
        root.addView(sensBtn);

        // Quick Actions
        final LinearLayout actionsRow = new LinearLayout(activity);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        addTopMargin(actionsRow, dp(activity, 12));

        final AlertDialog[] dialogRef = new AlertDialog[1];

        final Button askNowBtn = smallBtn(activity, "Ask Jimmy Now", "#16A34A", v -> {
            final JimmyConfig.Snapshot snap = readDraft(
                    enableCheck, wakeCheck, webSearchCheck, geminiInput, elevenInput, voiceInput, modelInput, sensHolder[0], draft);
            snap.enabled = true;
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            if (callbacks != null) callbacks.onAskNowRequested(snap);
        });
        ((LinearLayout.LayoutParams) askNowBtn.getLayoutParams()).leftMargin = 0;
        actionsRow.addView(askNowBtn);

        final Button clearChatBtn = smallBtn(activity, "Clear Chat", "#475569", v -> {
            if (callbacks != null) callbacks.onClearChatRequested();
            Toast.makeText(activity, "Jimmy memory cleared.", Toast.LENGTH_SHORT).show();
        });
        actionsRow.addView(clearChatBtn);

        final Button clearKeysBtn = smallBtn(activity, "Clear Keys", "#991B1B", v -> {
            geminiInput.setText("");
            elevenInput.setText("");
            enableCheck.setChecked(false);
            if (callbacks != null) callbacks.onClearKeysRequested();
            Toast.makeText(activity, "API keys cleared.", Toast.LENGTH_SHORT).show();
        });
        actionsRow.addView(clearKeysBtn);

        root.addView(actionsRow);
        scroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Jimmy AI Voice Assistant (Experimental)")
                .setView(scroll)
                .setPositiveButton("Save", (d, w) -> {
                    final JimmyConfig.Snapshot updated = readDraft(
                            enableCheck, wakeCheck, webSearchCheck, geminiInput, elevenInput, voiceInput, modelInput, sensHolder[0], draft);
                    if (callbacks != null) callbacks.onSaved(updated);
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialogRef[0] = dialog;
        dialog.setOnDismissListener(d -> {
            dialogOpen.set(false);
            uiHandler.removeCallbacks(micPoll);
            lanServer.stop();
        });
        dialog.show();
        uiHandler.post(micPoll);
    }

    private static void showModelChooser(Activity activity, List<String> models, EditText targetInput) {
        final String[] items = models.toArray(new String[0]);
        new AlertDialog.Builder(activity)
                .setTitle("Choose Gemini Model")
                .setItems(items, (d, which) -> targetInput.setText(items[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private interface ModelsCallback {
        void onResult(List<String> models);
    }

    private interface ErrorCallback {
        void onError(String message);
    }

    private static void fetchLiveModelsAsync(String apiKey, ModelsCallback onOk, ErrorCallback onErr) {
        final Thread t = new Thread(() -> {
            try {
                final List<String> models = new JimmyGeminiClient().listAvailableModels(apiKey);
                if (onOk != null) onOk.onResult(models);
            } catch (Throwable err) {
                if (onErr != null) onErr.onError(err.getMessage() == null ? "error" : err.getMessage());
            }
        }, "Jimmy-LoadModels");
        t.setDaemon(true);
        t.start();
    }

    private static JimmyConfig.Snapshot readDraft(
            CheckBox enableCheck,
            CheckBox wakeCheck,
            CheckBox webSearchCheck,
            EditText geminiInput,
            EditText elevenInput,
            EditText voiceInput,
            EditText modelInput,
            int sensitivity,
            JimmyConfig.Snapshot base) {
        final JimmyConfig.Snapshot s = new JimmyConfig.Snapshot(base);
        s.enabled = enableCheck.isChecked();
        s.wakeWordEnabled = wakeCheck.isChecked();
        s.webSearchEnabled = webSearchCheck.isChecked();
        s.geminiApiKey = geminiInput.getText().toString().trim();
        s.elevenLabsApiKey = elevenInput.getText().toString().trim();
        final String v = voiceInput.getText().toString().trim();
        s.elevenLabsVoiceId = v.isEmpty() ? JimmyConfig.DEFAULT_VOICE_ID : v;
        final String m = modelInput.getText().toString().trim();
        s.geminiModel = m.isEmpty() ? JimmyConfig.DEFAULT_GEMINI_MODEL : m;
        if (!s.geminiModel.equals(base.geminiModel)) {
            s.modelExplicit = true;
        }
        s.sensitivity = sensitivity;
        return s;
    }

    private static TextView sectionLabel(Context ctx, String text) {
        final TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#38BDF8"));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        addTopMargin(tv, dp(ctx, 10));
        return tv;
    }

    private static EditText keyInput(Context ctx, String hint, String initial) {
        final EditText et = textInput(ctx, hint, initial);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return et;
    }

    private static EditText textInput(Context ctx, String hint, String initial) {
        final EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setText(initial);
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(Color.parseColor("#64748B"));
        et.setBackgroundColor(Color.parseColor("#1E293B"));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        et.setSingleLine(true);
        final int p = dp(ctx, 8);
        et.setPadding(p, dp(ctx, 6), p, dp(ctx, 6));
        return et;
    }

    private static LinearLayout keyInputRow(Activity activity, EditText input) {
        final LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        final boolean[] visible = {false};
        final Button showBtn = smallBtn(activity, "Show", "#334155", null);
        showBtn.setOnClickListener(v -> {
            visible[0] = !visible[0];
            input.setInputType(InputType.TYPE_CLASS_TEXT | (visible[0]
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            input.setSelection(input.getText().length());
            showBtn.setText(visible[0] ? "Hide" : "Show");
        });
        row.addView(showBtn);

        final Button pasteBtn = smallBtn(activity, "Paste", "#2563EB", v -> {
            final ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                final ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                final CharSequence cs = item.coerceToText(activity);
                if (cs != null) {
                    input.setText(cs.toString().trim());
                }
            }
        });
        row.addView(pasteBtn);
        return row;
    }

    private static Button smallBtn(Context ctx, String text, String hexBg, View.OnClickListener l) {
        final Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(hexBg));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        if (l != null) b.setOnClickListener(l);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 32));
        lp.leftMargin = dp(ctx, 6);
        b.setLayoutParams(lp);
        return b;
    }

    private static void addTopMargin(View v, int topPx) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topPx;
        v.setLayoutParams(lp);
    }

    private static int dp(Context ctx, int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }
}
