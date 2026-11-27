package com.gtasixty.spatial.voice;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Compact, transparent 3D spatial pill for Jimmy AI, hosted inside {@link JimmySpatialPanel}
 * directly below the virtual screen. Stays tiny ("● Jimmy") while idle and expands cleanly
 * only while listening, thinking, speaking, or showing an alert.
 */
final class JimmyOverlayView extends FrameLayout {

    enum UiState {
        DISABLED,
        NEEDS_KEYS,
        IDLE,
        LISTENING,
        THINKING,
        SPEAKING,
        ERROR
    }

    interface Actions {
        void onPillClicked();
        void onSettingsClicked();
    }

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final LinearLayout pill;
    private final TextView dotView;
    private final TextView titleView;
    private final TextView subtitleView;
    private final Button settingsBtn;
    private final GradientDrawable pillBg;

    private UiState currentState = UiState.DISABLED;
    private int lastLevel = 0;

    private final Runnable hideSubtitleRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentState != UiState.SPEAKING && currentState != UiState.ERROR) {
                subtitleView.setVisibility(View.GONE);
            }
        }
    };

    JimmyOverlayView(Context context, Actions actions) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);

        pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.VERTICAL);
        pillBg = new GradientDrawable();
        pillBg.setColor(Color.parseColor("#C80F172A"));
        pillBg.setCornerRadius(dp(16));
        pillBg.setStroke(dp(1), Color.parseColor("#334155"));
        pill.setBackground(pillBg);
        pill.setPadding(dp(11), dp(5), dp(9), dp(5));
        pill.setClickable(true);
        pill.setOnClickListener(v -> {
            if (actions != null) actions.onPillClicked();
        });

        final LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        dotView = new TextView(context);
        dotView.setText("\u25CF");
        dotView.setTextColor(Color.parseColor("#22D3EE"));
        dotView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        dotView.setPadding(0, 0, dp(6), 0);
        topRow.addView(dotView);

        titleView = new TextView(context);
        titleView.setText("Jimmy");
        titleView.setTextColor(Color.WHITE);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        topRow.addView(titleView);

        settingsBtn = new Button(context);
        settingsBtn.setText("\u2699");
        settingsBtn.setAllCaps(false);
        settingsBtn.setTextColor(Color.parseColor("#CBD5E1"));
        settingsBtn.setBackgroundColor(Color.TRANSPARENT);
        settingsBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        settingsBtn.setPadding(dp(8), 0, dp(2), 0);
        settingsBtn.setMinimumWidth(0);
        settingsBtn.setMinWidth(0);
        settingsBtn.setMinimumHeight(0);
        settingsBtn.setMinHeight(0);
        settingsBtn.setOnClickListener(v -> {
            if (actions != null) actions.onSettingsClicked();
        });
        topRow.addView(settingsBtn);

        pill.addView(topRow);

        subtitleView = new TextView(context);
        subtitleView.setTextColor(Color.parseColor("#E2E8F0"));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitleView.setMaxWidth(dp(540));
        subtitleView.setMaxLines(2);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setPadding(0, dp(3), 0, 0);
        subtitleView.setVisibility(View.GONE);
        pill.addView(subtitleView);

        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.TOP);
        lp.topMargin = dp(4);
        lp.rightMargin = dp(4);
        addView(pill, lp);

        setVisibility(View.GONE);
    }

    void updateState(UiState state, String label, String subtitleText) {
        uiHandler.post(() -> {
            currentState = state;
            if (state == UiState.DISABLED) {
                setVisibility(View.GONE);
                return;
            }
            setVisibility(View.VISIBLE);
            titleView.setText(label);

            final int dotColor;
            final int strokeColor;
            final float pillAlpha;
            switch (state) {
                case LISTENING:
                    dotColor = Color.parseColor("#22C55E");
                    strokeColor = Color.parseColor("#22C55E");
                    pillAlpha = 1.0f;
                    break;
                case THINKING:
                    dotColor = Color.parseColor("#FBBF24");
                    strokeColor = Color.parseColor("#FBBF24");
                    pillAlpha = 1.0f;
                    break;
                case SPEAKING:
                    dotColor = Color.parseColor("#38BDF8");
                    strokeColor = Color.parseColor("#38BDF8");
                    pillAlpha = 1.0f;
                    break;
                case ERROR:
                case NEEDS_KEYS:
                    dotColor = Color.parseColor("#F87171");
                    strokeColor = Color.parseColor("#EF4444");
                    pillAlpha = 1.0f;
                    break;
                default: // IDLE -> tiny subtle pill
                    dotColor = Color.parseColor("#22D3EE");
                    strokeColor = Color.parseColor("#475569");
                    pillAlpha = 0.82f;
                    break;
            }
            pill.setAlpha(pillAlpha);
            dotView.setTextColor(dotColor);
            pillBg.setStroke(dp(1), strokeColor);

            uiHandler.removeCallbacks(hideSubtitleRunnable);
            if (subtitleText != null && !subtitleText.trim().isEmpty()) {
                subtitleView.setText(subtitleText.trim());
                subtitleView.setVisibility(View.VISIBLE);
                if (state != UiState.SPEAKING) {
                    uiHandler.postDelayed(hideSubtitleRunnable, state == UiState.ERROR ? 6000 : 4500);
                }
            } else {
                subtitleView.setVisibility(View.GONE);
            }
            pill.requestLayout();
            requestLayout();
            invalidate();
            try {
                Log.i("GTA60-Jimmy", "Overlay state=" + state + " label=\"" + label
                        + "\" vis=" + getVisibility() + " size=" + getWidth() + "x" + getHeight());
            } catch (Throwable ignored) {}
        });
    }

    void updateCountdownSeconds(int remainingSec) {
        uiHandler.post(() -> {
            if (currentState == UiState.LISTENING) {
                titleView.setText("Listening (" + remainingSec + "s)");
            }
        });
    }

    void updateAudioLevel(int level0To100) {
        if (Math.abs(level0To100 - lastLevel) < 6) return;
        lastLevel = level0To100;
        uiHandler.post(() -> {
            if (currentState == UiState.LISTENING || currentState == UiState.IDLE) {
                final float scale = 1.0f + Math.min(0.45f, level0To100 / 150.0f);
                dotView.setScaleX(scale);
                dotView.setScaleY(scale);
            } else {
                dotView.setScaleX(1.0f);
                dotView.setScaleY(1.0f);
            }
        });
    }

    void dispose() {
        uiHandler.removeCallbacksAndMessages(null);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
