package com.gtasixty.spatial;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * In-panel overlay drawer for spatial screen geometry and MR environment controls:
 * Curve (with Flat/25/50/80 quick presets & drag-safe slider), Pitch Tilt (-45..+45 deg),
 * Screen Size (locked 16:9), Room Depth, Vertical Height, Backdrop (Passthrough/Dim/Dark),
 * Recenter, and Reset.
 */
final class DisplayControlsView extends FrameLayout implements SpatialDisplayBridge.UiListener {

    interface IntConsumer {
        void accept(int value);
    }

    interface SettingsUpdater {
        void update(SpatialDisplayBridge.DisplaySettings s);
    }

    private final Runnable onUserInteraction;
    private final Runnable onCloseRequested;

    private TextView curveLabel;
    private TextView tiltLabel;
    private TextView sizeLabel;
    private TextView depthLabel;
    private TextView heightLabel;

    private SeekBar curveBar;
    private SeekBar tiltBar;
    private SeekBar sizeBar;
    private SeekBar depthBar;
    private SeekBar heightBar;
    private View curveBox;
    private View tiltBox;
    private View sizeBox;
    private TextView noteBox;

    private Button backdropBtn;
    private Button panelScaleBtn;
    private Button taaBtn;
    private Button flowBtn;
    private Button handsBtn;
    private TextView handsNote;

    private boolean updatingUi = false;
    private boolean draggingCurve = false;
    private boolean curveDragStartedFlat = false;

    DisplayControlsView(Context context, Runnable onUserInteraction, Runnable onCloseRequested) {
        super(context);
        this.onUserInteraction = onUserInteraction;
        this.onCloseRequested = onCloseRequested;
        setBackgroundColor(Color.parseColor("#66000000"));
        setClickable(true);
        setOnClickListener(v -> {
            if (onCloseRequested != null) onCloseRequested.run();
        });
        buildUi();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        SpatialDisplayBridge.addUiListener(this);
        syncFromSettings(SpatialDisplayBridge.getSettings());
    }

    @Override
    protected void onDetachedFromWindow() {
        SpatialDisplayBridge.removeUiListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onDisplaySettingsChanged(SpatialDisplayBridge.DisplaySettings settings) {
        syncFromSettings(settings);
    }

    private void buildUi() {
        final LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        final int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);

        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EE0F172A"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(2), Color.parseColor("#06B6D4"));
        card.setBackground(bg);

        // Header row
        final LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = new TextView(getContext());
        title.setText("Display Settings (16:9 Locked)");
        title.setTextColor(Color.parseColor("#22D3EE"));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        final LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        header.addView(title, titleLp);

        backdropBtn = makeButton("Backdrop: Passthrough", "#0F766E", v -> {
            notifyInteraction();
            applyChange(s -> s.backdropMode = (s.backdropMode + 1) % SpatialDisplayBridge.BACKDROP_COUNT);
        });
        header.addView(backdropBtn);

        header.addView(makeButton("Recenter", "#2563EB", v -> {
            notifyInteraction();
            SpatialDisplayBridge.requestRecenter();
        }));

        header.addView(makeButton("Reset", "#475569", v -> {
            notifyInteraction();
            SpatialDisplayBridge.applyFromUi(getContext(), new SpatialDisplayBridge.DisplaySettings());
            SpatialDisplayBridge.requestRecenter();
        }));

        header.addView(makeButton("Close", "#DC2626", v -> {
            if (onCloseRequested != null) onCloseRequested.run();
        }));

        card.addView(header);

        // Curve presets row
        final LinearLayout presetsRow = new LinearLayout(getContext());
        presetsRow.setOrientation(LinearLayout.HORIZONTAL);
        presetsRow.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams preLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        preLp.topMargin = dp(10);
        presetsRow.setLayoutParams(preLp);

        final TextView preTitle = new TextView(getContext());
        preTitle.setText("Curve Presets:");
        preTitle.setTextColor(Color.parseColor("#CBD5E1"));
        preTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        preTitle.setPadding(0, 0, dp(8), 0);
        presetsRow.addView(preTitle);

        presetsRow.addView(makeButton("Flat (0%)", "#334155", v -> setCurvePreset(0)));
        presetsRow.addView(makeButton("25%", "#0369A1", v -> setCurvePreset(25)));
        presetsRow.addView(makeButton("50%", "#0369A1", v -> setCurvePreset(50)));
        presetsRow.addView(makeButton("80%", "#0369A1", v -> setCurvePreset(80)));

        panelScaleBtn = makeButton("Res: 1800p SR", "#0F766E", v -> {
            notifyInteraction();
            applyChange(s -> s.panelScale = (s.panelScale + 1) % 3);
        });
        presetsRow.addView(panelScaleBtn);

        taaBtn = makeButton("TAA: On", "#0F766E", v -> {
            notifyInteraction();
            applyChange(s -> s.taaEnabled = !s.taaEnabled);
        });
        presetsRow.addView(taaBtn);

        flowBtn = makeButton("Flow: Compute", "#0F766E", v -> {
            notifyInteraction();
            applyChange(s -> s.computeFlowEnabled = !s.computeFlowEnabled);
        });
        presetsRow.addView(flowBtn);

        card.addView(presetsRow);

        // GTA Hands row: the toggle plus a one-line explainer, kept on its own row so the presets row
        // above it does not overflow the 680 dp card.
        final LinearLayout handsRow = new LinearLayout(getContext());
        handsRow.setOrientation(LinearLayout.HORIZONTAL);
        handsRow.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams handsRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        handsRowLp.topMargin = dp(8);
        handsRow.setLayoutParams(handsRowLp);

        handsBtn = makeButton("GTA Hands: On", "#7C3AED", v -> {
            notifyInteraction();
            applyChange(s -> s.gtaHandsEnabled = !s.gtaHandsEnabled);
        });
        handsRow.addView(handsBtn);

        handsNote = new TextView(getContext());
        handsNote.setText("GTA V hands + auto-spawning Xbox pad. Put the Touch controllers down and\n"
                + "turn hand tracking on; hold your real pad with both hands for ~0.25 s.");
        handsNote.setTextColor(Color.parseColor("#94A3B8"));
        handsNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        final LinearLayout.LayoutParams handsNoteLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        handsNoteLp.leftMargin = dp(8);
        handsRow.addView(handsNote, handsNoteLp);
        card.addView(handsRow);

        // Row 1: Curve + Tilt
        final LinearLayout row1 = makePairRow();
        final SliderRefs curveRefs = createSliderBlock(
                "Curve: Flat (0%)",
                100,
                "#38BDF8",
                progress -> applyChange(s -> s.curvePct = progress),
                () -> stepCurve(-10),
                () -> stepCurve(10));
        curveLabel = curveRefs.label;
        curveBar = curveRefs.bar;
        curveBox = curveRefs.container;
        curveBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updatingUi) return;
                notifyInteraction();
                final SpatialDisplayBridge.DisplaySettings preview = SpatialDisplayBridge.getSettings();
                preview.curvePct = progress;
                updateCurveLabel(preview);
                if (draggingCurve) {
                    // Switching 0 <-> >0 swaps Quad <-> Cylinder colliders in ISDK and cancels
                    // pointer drags mid-stream. Commit 0 <-> >0 jumps on release; slide >0 live.
                    if (!curveDragStartedFlat) {
                        final int liveCurve = Math.max(1, progress);
                        applyChange(s -> s.curvePct = liveCurve);
                    }
                } else {
                    applyChange(s -> s.curvePct = progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                notifyInteraction();
                draggingCurve = true;
                curveDragStartedFlat = SpatialDisplayBridge.getSettings().curvePct <= 0;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                notifyInteraction();
                draggingCurve = false;
                final int finalCurve = seekBar.getProgress();
                applyChange(s -> s.curvePct = finalCurve);
            }
        });
        row1.addView(curveRefs.container);

        final SliderRefs tiltRefs = createSliderBlock(
                "Tilt: 0\u00B0 (Level)",
                90,
                "#A78BFA",
                progress -> applyChange(s -> s.tiltDeg = progress - 45.0f),
                () -> stepTilt(-5.0f),
                () -> stepTilt(5.0f));
        tiltLabel = tiltRefs.label;
        tiltBar = tiltRefs.bar;
        tiltBox = tiltRefs.container;
        row1.addView(tiltRefs.container);
        card.addView(row1);

        // Row 2: Screen Size (16:9) + Room Depth
        final LinearLayout row2 = makePairRow();
        final SliderRefs sizeRefs = createSliderBlock(
                "Screen Size (16:9): 2.2 m",
                70,
                "#34D399",
                progress -> applyChange(s -> s.widthMeters = 1.0f + progress * 0.05f),
                () -> stepWidth(-0.2f),
                () -> stepWidth(0.2f));
        sizeLabel = sizeRefs.label;
        sizeBar = sizeRefs.bar;
        sizeBox = sizeRefs.container;
        row2.addView(sizeRefs.container);

        final SliderRefs depthRefs = createSliderBlock(
                "Room Depth: 2.2 m",
                80,
                "#FBBF24",
                progress -> applyChange(s -> s.distanceMeters = 1.0f + progress * 0.05f),
                () -> stepDistance(-0.2f),
                () -> stepDistance(0.2f));
        depthLabel = depthRefs.label;
        depthBar = depthRefs.bar;
        row2.addView(depthRefs.container);
        card.addView(row2);

        // Row 3: Vertical Height (-0.6 m .. +0.8 m)
        final LinearLayout row3 = makePairRow();
        final SliderRefs heightRefs = createSliderBlock(
                "Height: 0.00 m",
                28,
                "#22D3EE",
                progress -> applyChange(s -> s.heightOffsetM = -0.60f + progress * 0.05f),
                () -> stepHeight(-0.10f),
                () -> stepHeight(0.10f));
        heightLabel = heightRefs.label;
        heightBar = heightRefs.bar;
        row3.addView(heightRefs.container);

        noteBox = new TextView(getContext());
        noteBox.setText("Tip: Tilt downward/upward and adjust Height for reclined or couch play.\n"
                + "Grab the bottom handle bar with Trigger or Grip/Pinch to move (thumbstick adjusts depth).\n"
                + "When flat (0%), drag corners to resize 16:9. Tap Recenter if you redraw your boundary.");
        noteBox.setTextColor(Color.parseColor("#94A3B8"));
        noteBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        final LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        noteLp.leftMargin = dp(8);
        noteLp.gravity = Gravity.CENTER_VERTICAL;
        row3.addView(noteBox, noteLp);
        card.addView(row3);

        final ScrollView sv = new ScrollView(getContext());
        sv.addView(card);

        final FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                dp(680), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(sv, cardLp);
    }

    private LinearLayout makePairRow() {
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private static final class SliderRefs {
        final LinearLayout container;
        final TextView label;
        final SeekBar bar;

        SliderRefs(LinearLayout container, TextView label, SeekBar bar) {
            this.container = container;
            this.label = label;
            this.bar = bar;
        }
    }

    private SliderRefs createSliderBlock(
            String initialText,
            int max,
            String accentHex,
            IntConsumer onChanged,
            Runnable onMinus,
            Runnable onPlus) {
        final LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor("#991E293B"));
        final int p = dp(8);
        box.setPadding(p, p, p, p);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.rightMargin = dp(6);
        box.setLayoutParams(lp);

        final TextView label = new TextView(getContext());
        label.setText(initialText);
        label.setTextColor(Color.parseColor(accentHex));
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(label);

        final LinearLayout ctrlRow = new LinearLayout(getContext());
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        ctrlRow.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams ctrlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctrlLp.topMargin = dp(4);
        ctrlRow.setLayoutParams(ctrlLp);

        ctrlRow.addView(makeSmallStepButton("-", v -> {
            notifyInteraction();
            onMinus.run();
        }));

        final SeekBar bar = new SeekBar(getContext());
        bar.setMax(max);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updatingUi) return;
                notifyInteraction();
                onChanged.accept(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                notifyInteraction();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                notifyInteraction();
            }
        });
        final LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        ctrlRow.addView(bar, barLp);

        ctrlRow.addView(makeSmallStepButton("+", v -> {
            notifyInteraction();
            onPlus.run();
        }));

        box.addView(ctrlRow);
        return new SliderRefs(box, label, bar);
    }

    private void setCurvePreset(int pct) {
        notifyInteraction();
        applyChange(s -> s.curvePct = pct);
    }

    private void stepCurve(int delta) {
        applyChange(s -> s.curvePct += delta);
    }

    private void stepTilt(float deltaDeg) {
        applyChange(s -> s.tiltDeg = Math.round((s.tiltDeg + deltaDeg) * 10f) / 10f);
    }

    private void stepWidth(float deltaM) {
        applyChange(s -> s.widthMeters = Math.round((s.widthMeters + deltaM) * 20f) / 20f);
    }

    private void stepDistance(float deltaM) {
        applyChange(s -> s.distanceMeters = Math.round((s.distanceMeters + deltaM) * 20f) / 20f);
    }

    private void stepHeight(float deltaM) {
        applyChange(s -> s.heightOffsetM = Math.round((s.heightOffsetM + deltaM) * 20f) / 20f);
    }

    private void applyChange(SettingsUpdater updater) {
        final SpatialDisplayBridge.DisplaySettings s = SpatialDisplayBridge.getSettings();
        updater.update(s);
        s.clamp();
        SpatialDisplayBridge.applyFromUi(getContext(), s);
    }

    private void updateCurveLabel(SpatialDisplayBridge.DisplaySettings s) {
        if (curveLabel == null || s == null) return;
        if (s.curvePct <= 0) {
            curveLabel.setText("Curve: Flat (0%)");
        } else {
            int arcDeg = Math.round(s.cylinderArcDegrees());
            curveLabel.setText(String.format(Locale.US, "Curve: %d%% (%d\u00B0 arc, R=%.1fm)",
                    s.curvePct, arcDeg, s.cylinderRadiusMeters()));
        }
    }

    void syncFromSettings(SpatialDisplayBridge.DisplaySettings s) {
        if (s == null) return;
        updatingUi = true;
        try {
            if (!draggingCurve) {
                if (curveBar != null) curveBar.setProgress(s.curvePct);
                updateCurveLabel(s);
            }

            final int tiltProg = Math.round(s.tiltDeg + 45.0f);
            if (tiltBar != null) tiltBar.setProgress(Math.max(0, Math.min(90, tiltProg)));
            if (tiltLabel != null) {
                final String dir;
                if (s.tiltDeg < -0.5f) dir = "Toward You";
                else if (s.tiltDeg > 0.5f) dir = "Away";
                else dir = "Level";
                tiltLabel.setText(String.format(Locale.US, "Tilt: %+.0f\u00B0 (%s)", s.tiltDeg, dir));
            }

            final int sizeProg = Math.round((s.widthMeters - 1.0f) / 0.05f);
            if (sizeBar != null) sizeBar.setProgress(Math.max(0, Math.min(70, sizeProg)));
            if (sizeLabel != null) {
                sizeLabel.setText(String.format(Locale.US, "Screen Size (16:9): %.2f m", s.widthMeters));
            }

            final int depthProg = Math.round((s.distanceMeters - 1.0f) / 0.05f);
            if (depthBar != null) depthBar.setProgress(Math.max(0, Math.min(80, depthProg)));
            if (depthLabel != null) {
                depthLabel.setText(String.format(Locale.US, "Room Depth: %.2f m", s.distanceMeters));
            }

            final int heightProg = Math.round((s.heightOffsetM + 0.60f) / 0.05f);
            if (heightBar != null) heightBar.setProgress(Math.max(0, Math.min(28, heightProg)));
            if (heightLabel != null) {
                heightLabel.setText(String.format(Locale.US, "Height: %+.2f m", s.heightOffsetM));
            }

            final boolean inRoom = (s.backdropMode == SpatialDisplayBridge.BACKDROP_JIMMYS_ROOM);
            if (curveBox != null) curveBox.setAlpha(inRoom ? 0.42f : 1.0f);
            if (tiltBox != null) tiltBox.setAlpha(inRoom ? 0.42f : 1.0f);
            if (sizeBox != null) sizeBox.setAlpha(inRoom ? 0.42f : 1.0f);
            if (curveBar != null) curveBar.setEnabled(!inRoom);
            if (tiltBar != null) tiltBar.setEnabled(!inRoom);
            if (sizeBar != null) sizeBar.setEnabled(!inRoom);
            if (sizeLabel != null && inRoom) {
                sizeLabel.setText("Screen Size: Locked to Jimmy's TV (2.10 m)");
            }
            if (depthLabel != null && inRoom) {
                float tvDist = 2.42f + Math.max(-1.10f, Math.min(1.15f, s.distanceMeters - SpatialDisplayBridge.DEFAULT_DIST_M));
                depthLabel.setText(String.format(Locale.US, "Seat Distance to TV: %.2f m", tvDist));
            }
            if (noteBox != null) {
                if (inRoom) {
                    noteBox.setText("Jimmy\'s Room: Display is locked inside Jimmy\'s TV bezel.\n"
                            + "Use Seat Distance (Room Depth) and Height to slide closer/further or adjust eye level.\n"
                            + "GTA Hands: put the Touch controllers down (hand tracking on) and hold your real Xbox\n"
                            + "pad with both hands for ~0.25 s and a 3D pad spawns between them.\n"
                            + "Tap Recenter to align Jimmy\'s room & TV to where you are sitting.");
                } else {
                    noteBox.setText("Tip: Tilt downward/upward and adjust Height for reclined or couch play.\n"
                            + "Grab the bottom handle bar with Trigger or Grip/Pinch to move (thumbstick adjusts depth).\n"
                            + "When flat (0%), drag corners to resize 16:9. Tap Recenter if you redraw your boundary.");
                }
            }
            if (backdropBtn != null) {
                backdropBtn.setText("Backdrop: " + s.backdropLabel());
            }
            if (panelScaleBtn != null) {
                panelScaleBtn.setText("Res: " + SpatialDisplayBridge.panelScaleLabel(s.panelScale));
            }
            if (taaBtn != null) {
                taaBtn.setText("TAA: " + (s.taaEnabled ? "On" : "Off"));
            }
            if (flowBtn != null) {
                flowBtn.setText("Flow: " + (s.computeFlowEnabled ? "Compute" : "Frag"));
            }
            if (handsBtn != null) {
                handsBtn.setText("GTA Hands: " + (s.gtaHandsEnabled ? "On" : "Off"));
                handsBtn.setBackgroundColor(Color.parseColor(s.gtaHandsEnabled ? "#7C3AED" : "#334155"));
            }
        } finally {
            updatingUi = false;
        }
    }

    private Button makeButton(String text, String hexBg, OnClickListener listener) {
        final Button b = new Button(getContext());
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(hexBg));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setOnClickListener(listener);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private Button makeSmallStepButton(String text, OnClickListener listener) {
        final Button b = new Button(getContext());
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#334155"));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(listener);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
        b.setLayoutParams(lp);
        return b;
    }

    private void notifyInteraction() {
        if (onUserInteraction != null) onUserInteraction.run();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
