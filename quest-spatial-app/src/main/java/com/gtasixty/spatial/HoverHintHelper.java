package com.gtasixty.spatial;

import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Manages a shared hover/focus description bar placed below a group of controls.
 *
 * <p>Tracks {@code activeView} so an {@code ACTION_HOVER_EXIT} arriving from a previously hovered
 * button after the pointer has already entered the next button never clobbers the new hint.
 */
final class HoverHintHelper {

    private static final int COLOR_ACTIVE = Color.parseColor("#38BDF8");
    private static final int COLOR_DEFAULT = Color.parseColor("#94A3B8");

    private final TextView target;
    private String defaultText;
    private View activeView;

    HoverHintHelper(TextView target, String defaultText) {
        this.target = target;
        this.defaultText = defaultText == null ? "" : defaultText;
        applyDefault();
    }

    void setDefaultText(String text) {
        this.defaultText = text == null ? "" : text;
        if (activeView == null) {
            applyDefault();
        }
    }

    void attach(View view, String hint) {
        if (view == null || hint == null) return;
        view.setOnHoverListener((v, event) -> {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
                showHint(v, hint);
            } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                if (activeView == v) {
                    clear();
                }
            }
            return false;
        });
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showHint(v, hint);
            } else if (activeView == v) {
                clear();
            }
        });
    }

    void attachSubtree(View root, String hint) {
        if (root == null || hint == null) return;
        attach(root, hint);
        if (root instanceof ViewGroup) {
            final ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                attachSubtree(vg.getChildAt(i), hint);
            }
        }
    }

    void clear() {
        activeView = null;
        applyDefault();
    }

    private void showHint(View owner, String hint) {
        activeView = owner;
        if (target != null) {
            target.setText(hint);
            target.setTextColor(COLOR_ACTIVE);
        }
    }

    private void applyDefault() {
        if (target != null) {
            target.setText(defaultText);
            target.setTextColor(COLOR_DEFAULT);
        }
    }
}
