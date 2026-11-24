package com.maxapacity.ride;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * In-app message bar: what just happened, in the app's own type and colours,
 * with an optional Undo. Replaces system toasts, which cannot be styled and
 * cannot carry an action.
 */
final class Snack {

    private Snack() {
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View showing;
    private static Runnable hide;
    private static int topInsetPx;
    private static int bottomInsetPx;

    static void setInsets(int top, int bottom) {
        topInsetPx = top;
        bottomInsetPx = bottom;
    }

    /** Takes down whatever is showing, at once. A sheet calls this as it opens. */
    static void clear(ViewGroup host) {
        dismissNow(host);
    }

    static void show(ViewGroup host, String message) {
        show(host, message, null, null);
    }

    static void show(final ViewGroup host, String message, String action, final Runnable run) {
        Context c = host.getContext();
        Ui.init(c);
        dismissNow(host);

        final LinearLayout bar = Ui.row(c);
        bar.setBackground(Ui.panel(c, Ui.SURFACE_HI, Ui.HAIRLINE_HI, 12));
        bar.setPadding(Ui.dp(c, 16), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
        bar.setMinimumHeight(Ui.dp(c, 48));
        bar.setElevation(Ui.dp(c, 8));
        bar.setClickable(true);
        TextView text = Ui.small(c, message, Ui.TEXT);
        text.setMaxLines(3);
        text.setPadding(0, Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8));
        bar.addView(text, Ui.weighted());
        if (action != null) {
            TextView a = new TextView(c);
            a.setText(action);
            a.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.T_SMALL);
            a.setTypeface(Ui.sansBold);
            a.setLetterSpacing(0.06f);
            a.setTextColor(Ui.ACCENT);
            a.setGravity(Gravity.CENTER);
            a.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
            a.setBackground(Ui.ghost(c, 8));
            a.setOnClickListener(v -> {
                dismissNow(host);
                run.run();
            });
            bar.addView(a, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 40)));
        }

        // Normally along the bottom. A sheet keeps its buttons there, so a
        // message that arrives while one is open goes to the top instead.
        final boolean atTop = Sheet.current() != null;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                atTop ? Gravity.TOP : Gravity.BOTTOM);
        lp.setMargins(Ui.dp(c, 16), atTop ? Ui.dp(c, 16) + topInsetPx : 0, Ui.dp(c, 16),
                atTop ? 0 : Ui.dp(c, 16) + bottomInsetPx);
        host.addView(bar, lp);
        final float slide = Ui.dp(c, atTop ? -16 : 16);
        bar.setAlpha(0f);
        bar.setTranslationY(slide);
        bar.animate().alpha(1f).translationY(0f).setDuration(Ui.MOTION_MS).start();
        bar.announceForAccessibility(message);

        showing = bar;
        hide = () -> {
            if (showing != bar) return;
            showing = null;
            bar.animate().alpha(0f).translationY(slide).setDuration(150)
                    .withEndAction(() -> host.removeView(bar)).start();
        };
        // Long enough to read, longer when there is something to tap.
        MAIN.postDelayed(hide, action == null ? 3500 : 6000);
    }

    private static void dismissNow(ViewGroup host) {
        if (hide != null) MAIN.removeCallbacks(hide);
        if (showing != null) host.removeView(showing);
        showing = null;
    }
}
