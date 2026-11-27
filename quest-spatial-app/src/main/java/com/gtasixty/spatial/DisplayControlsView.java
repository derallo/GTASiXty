package com.gtasixty.spatial;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * In-panel overlay drawer for spatial screen geometry, VR environment controls, and collapsible
 * advanced graphics toggles, with a bottom hover-hint bar describing every option.
 */
final class DisplayControlsView extends FrameLayout implements SpatialDisplayBridge.UiListener {

    interface IntConsumer {
        void accept(int value);
    }

    interface SettingsUpdater {
        void update(SpatialDisplayBridge.DisplaySettings s);
    }

    private static final String DEFAULT_TIP_NORMAL =
            "Point at any setting above to see what it does.\n"
            + "Tip: Grab the bottom handle bar with Trigger or Grip/Pinch to move the screen "
            + "(push thumbstick up/down while holding Trigger to adjust depth).";

    private static final String DEFAULT_TIP_ROOM =
            "Point at any setting above to see what it does.\n"
            + "Jimmy's Room: Screen Size, Curve, and Tilt lock inside Jimmy's TV bezel. "
            + "Use Seat Distance and Height to adjust your view, or tap Recenter.";

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
    private View presetsBox;
    private TextView noteBox;
    private HoverHintHelper hoverHints;

    private Button backdropBtn;
    private Button handsBtn;
    private CheckBox autoHideCheck;
    private Button advancedToggleBtn;
    private LinearLayout advancedRow;
    private Button panelScaleBtn;
    private Button taaBtn;
    private Button flowBtn;

    private boolean updatingUi = false;
    private boolean draggingCurve = false;
    private boolean curveDragStartedFlat = false;
    private boolean advancedExpanded = false;

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

        // Initialize noteBox & HoverHintHelper first so every control can attach its hover hint.
        noteBox = new TextView(getContext());
        noteBox.setTextColor(Color.parseColor("#94A3B8"));
        noteBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        noteBox.setMinLines(3);
        hoverHints = new HoverHintHelper(noteBox, DEFAULT_TIP_NORMAL);

        // 1. Header row
        final LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = new TextView(getContext());
        title.setText("Display & VR Settings");
        title.setTextColor(Color.parseColor("#22D3EE"));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        final Button recenterBtn = makeButton("Recenter", "#2563EB", v -> {
            notifyInteraction();
            SpatialDisplayBridge.requestRecenter();
        });
        hoverHints.attach(recenterBtn,
                "Recenter: Snap the screen (or Jimmy's Room) directly in front of where you are looking.");
        header.addView(recenterBtn);

        final Button resetBtn = makeButton("Reset", "#475569", v -> {
            notifyInteraction();
            SpatialDisplayBridge.applyFromUi(getContext(), new SpatialDisplayBridge.DisplaySettings());
            SpatialDisplayBridge.requestRecenter();
        });
        hoverHints.attach(resetBtn,
                "Reset: Restore default screen size, depth, height, tilt, curve, and VR settings.");
        header.addView(resetBtn);

        final Button closeBtn = makeButton("Close", "#DC2626", v -> {
            if (onCloseRequested != null) onCloseRequested.run();
        });
        hoverHints.attach(closeBtn,
                "Close: Dismiss Display & VR Settings and return to the game.");
        header.addView(closeBtn);
        card.addView(header);

        // 2. Environment & Comfort row
        final LinearLayout envRow = new LinearLayout(getContext());
        envRow.setOrientation(LinearLayout.HORIZONTAL);
        envRow.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams envLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        envLp.topMargin = dp(10);
        envRow.setLayoutParams(envLp);

        backdropBtn = makeButton("Backdrop: Passthrough", "#0F766E", v -> {
            notifyInteraction();
            applyChange(s -> s.backdropMode = (s.backdropMode + 1) % SpatialDisplayBridge.BACKDROP_COUNT);
        });
        ((LinearLayout.LayoutParams) backdropBtn.getLayoutParams()).leftMargin = 0;
        hoverHints.attach(backdropBtn,
                "Backdrop: Cycle Passthrough (your real room), Dark theater, or Jimmy's Room 3D VR bedroom.");
        envRow.addView(backdropBtn);

        handsBtn = makeButton("GTA Hands: On", "#7C3AED", v -> {
            notifyInteraction();
            applyChange(s -> s.gtaHandsEnabled = !s.gtaHandsEnabled);
        });
        hoverHints.attach(handsBtn,
                "GTA Hands: Render GTA V hands & auto-spawn a 3D Xbox controller when holding your real pad with hand tracking on.");
        envRow.addView(handsBtn);

        autoHideCheck = new CheckBox(getContext());
        autoHideCheck.setText("Auto-hide menu w/ controller");
        autoHideCheck.setTextColor(Color.parseColor("#E2E8F0"));
        autoHideCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        autoHideCheck.setButtonTintList(ColorStateList.valueOf(Color.parseColor("#22D3EE")));
        final LinearLayout.LayoutParams chkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chkLp.leftMargin = dp(10);
        autoHideCheck.setLayoutParams(chkLp);
        autoHideCheck.setOnCheckedChangeListener((btn, isChecked) -> {
            if (updatingUi) return;
            notifyInteraction();
            applyChange(s -> s.autoHideMenuOnController = isChecked);
        });
        hoverHints.attach(autoHideCheck,
                "Auto-hide menu w/ controller: Automatically hide menus 6 seconds after the virtual Xbox controller appears.");
        envRow.addView(autoHideCheck);
        card.addView(envRow);

        // 3. Screen Size & Position Sliders (logical order)
        // Row 1: Screen Size (16:9) + Room Depth
        final LinearLayout row1 = makePairRow();
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
        hoverHints.attachSubtree(sizeBox,
                "Screen Size (16:9): Adjust physical screen width from 1.0 m to 4.5 m while keeping exact 16:9 aspect ratio.");
        row1.addView(sizeBox);

        final SliderRefs depthRefs = createSliderBlock(
                "Room Depth: 2.2 m",
                80,
                "#FBBF24",
                progress -> applyChange(s -> s.distanceMeters = 1.0f + progress * 0.05f),
                () -> stepDistance(-0.2f),
                () -> stepDistance(0.2f));
        depthLabel = depthRefs.label;
        depthBar = depthRefs.bar;
        hoverHints.attachSubtree(depthRefs.container,
                "Room Depth / Seat Distance: Move the screen (or your seat in Jimmy's Room) closer or farther away.");
        row1.addView(depthRefs.container);
        card.addView(row1);

        // Row 2: Height + Tilt
        final LinearLayout row2 = makePairRow();
        final SliderRefs heightRefs = createSliderBlock(
                "Height: 0.00 m",
                28,
                "#22D3EE",
                progress -> applyChange(s -> s.heightOffsetM = -0.60f + progress * 0.05f),
                () -> stepHeight(-0.10f),
                () -> stepHeight(0.10f));
        heightLabel = heightRefs.label;
        heightBar = heightRefs.bar;
        hoverHints.attachSubtree(heightRefs.container,
                "Height: Raise or lower the screen (-0.60 m to +0.80 m) for desk, couch, or reclined play.");
        row2.addView(heightRefs.container);

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
        hoverHints.attachSubtree(tiltBox,
                "Tilt: Angle the screen toward or away from you (-45\u00B0 to +45\u00B0) for reclined or standing comfort.");
        row2.addView(tiltBox);
        card.addView(row2);

        // Row 3: Curve slider + Curve Presets
        final LinearLayout row3 = makePairRow();
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
        hoverHints.attachSubtree(curveBox,
                "Curve: Wrap the display smoothly into a curved theater arc (0% = flat with corner resize, up to 100%).");
        row3.addView(curveBox);

        final LinearLayout presetsContainer = new LinearLayout(getContext());
        presetsContainer.setOrientation(LinearLayout.VERTICAL);
        presetsContainer.setBackgroundColor(Color.parseColor("#991E293B"));
        final int p = dp(8);
        presetsContainer.setPadding(p, p, p, p);
        presetsContainer.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        presetsBox = presetsContainer;

        final TextView preTitle = new TextView(getContext());
        preTitle.setText("Curve Presets");
        preTitle.setTextColor(Color.parseColor("#38BDF8"));
        preTitle.setTypeface(Typeface.DEFAULT_BOLD);
        preTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        presetsContainer.addView(preTitle);

        final LinearLayout presetsBtns = new LinearLayout(getContext());
        presetsBtns.setOrientation(LinearLayout.HORIZONTAL);
        presetsBtns.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.topMargin = dp(4);
        presetsBtns.setLayoutParams(pbLp);

        final Button flatBtn = makeButton("Flat", "#334155", v -> setCurvePreset(0));
        ((LinearLayout.LayoutParams) flatBtn.getLayoutParams()).leftMargin = 0;
        hoverHints.attach(flatBtn, "Flat (0%): Flat 16:9 screen with corner drag resizing enabled.");
        presetsBtns.addView(flatBtn);

        final Button c25Btn = makeButton("25%", "#0369A1", v -> setCurvePreset(25));
        hoverHints.attach(c25Btn, "25% Curve: Gentle cinema arc centered at your current distance.");
        presetsBtns.addView(c25Btn);

        final Button c50Btn = makeButton("50%", "#0369A1", v -> setCurvePreset(50));
        hoverHints.attach(c50Btn, "50% Curve: Immersive wrap-around theater arc.");
        presetsBtns.addView(c50Btn);

        final Button c80Btn = makeButton("80%", "#0369A1", v -> setCurvePreset(80));
        hoverHints.attach(c80Btn, "80% Curve: Deep cockpit/imax wrap-around screen.");
        presetsBtns.addView(c80Btn);

        presetsContainer.addView(presetsBtns);
        row3.addView(presetsContainer);
        card.addView(row3);

        // 4. Collapsible Advanced Graphics section
        final LinearLayout advHeader = new LinearLayout(getContext());
        advHeader.setOrientation(LinearLayout.HORIZONTAL);
        advHeader.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams advHdrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        advHdrLp.topMargin = dp(8);
        advHeader.setLayoutParams(advHdrLp);

        advancedToggleBtn = makeButton("Advanced Graphics \u25B8", "#1E293B", v -> {
            notifyInteraction();
            advancedExpanded = !advancedExpanded;
            advancedRow.setVisibility(advancedExpanded ? View.VISIBLE : View.GONE);
            advancedToggleBtn.setText(advancedExpanded
                    ? "Advanced Graphics \u25BE"
                    : "Advanced Graphics \u25B8");
        });
        ((LinearLayout.LayoutParams) advancedToggleBtn.getLayoutParams()).leftMargin = 0;
        hoverHints.attach(advancedToggleBtn,
                "Advanced Graphics: Show or hide panel supersampling resolution, foliage TAA, and compute flow options.");
        advHeader.addView(advancedToggleBtn);

        advancedRow = new LinearLayout(getContext());
        advancedRow.setOrientation(LinearLayout.HORIZONTAL);
        advancedRow.setGravity(Gravity.CENTER_VERTICAL);
        advancedRow.setVisibility(View.GONE);
        final LinearLayout.LayoutParams advRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        advRowLp.leftMargin = dp(6);
        advancedRow.setLayoutParams(advRowLp);

        panelScaleBtn = makeButton("Res: 1800p SR", "#0F766E", v -> {
            notifyInteraction();
            applyChange(s -> s.panelScale = (s.panelScale + 1) % 3);
        });
        hoverHints.attach(panelScaleBtn,
                "Panel Res: Cycle Quest OpenXR layer resolution (1440p native, 1800p SR, or 4K Ultra supersampled).");
        advancedRow.addView(panelScaleBtn);

        taaBtn = makeButton("TAA: On", "#0F766E", v -> {
            notifyInteraction();
            applyChange(s -> s.taaEnabled = !s.taaEnabled);
        });
        hoverHints.attach(taaBtn,
                "TAA: Toggle temporal foliage anti-aliasing to reduce shimmer on trees, grass, and power lines.");
        advancedRow.addView(taaBtn);

        flowBtn = makeButton("Flow: Compute", "#0F766E", v -> {
            notifyInteraction();
            applyChange(s -> s.computeFlowEnabled = !s.computeFlowEnabled);
        });
        hoverHints.attach(flowBtn,
                "Flow: Switch optical-flow backend between GLES 3.1 Compute shaders (faster) and Fragment fallback.");
        advancedRow.addView(flowBtn);

        advHeader.addView(advancedRow);
        card.addView(advHeader);

        // 5. Bottom Hover Description / Tip Bar
        final LinearLayout noteWrap = new LinearLayout(getContext());
        noteWrap.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable noteBg = new GradientDrawable();
        noteBg.setColor(Color.parseColor("#CC0B1324"));
        noteBg.setCornerRadius(dp(8));
        noteBg.setStroke(dp(1), Color.parseColor("#1E293B"));
        noteWrap.setBackground(noteBg);
        final int np = dp(10);
        noteWrap.setPadding(np, dp(8), np, dp(8));
        final LinearLayout.LayoutParams noteWrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteWrapLp.topMargin = dp(10);
        noteWrap.setLayoutParams(noteWrapLp);
        noteWrap.addView(noteBox);
        card.addView(noteWrap);

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
            if (presetsBox != null) presetsBox.setAlpha(inRoom ? 0.42f : 1.0f);
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
            if (hoverHints != null) {
                hoverHints.setDefaultText(inRoom ? DEFAULT_TIP_ROOM : DEFAULT_TIP_NORMAL);
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
            if (autoHideCheck != null && autoHideCheck.isChecked() != s.autoHideMenuOnController) {
                autoHideCheck.setChecked(s.autoHideMenuOnController);
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
