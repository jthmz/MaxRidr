package com.maxapacity.ride;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Bottom sheet for anything that interrupts: a choice, a confirmation or a
 * short form. Slides up over a scrim; tapping the scrim or Back dismisses it.
 * One sheet at a time: showing a second replaces the first.
 */
final class Sheet extends FrameLayout {

    private static Sheet current;
    private static int insetTop;
    private static int insetBottom;

    private final ViewGroup host;
    private final LinearLayout panel;
    private final LinearLayout content;
    private final LinearLayout actions;
    private TextView messageView;
    private Runnable onDismiss;
    private boolean closing;

    Sheet(ViewGroup host, String title, String message) {
        super(host.getContext());
        this.host = host;
        Context c = getContext();
        Ui.init(c);
        setBackgroundColor(Ui.SCRIM);
        setOnClickListener(v -> dismiss());

        panel = Ui.column(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.SURFACE);
        float r = Ui.dp(c, 24);
        bg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(Math.max(1, Ui.dp(c, 1)), Ui.HAIRLINE);
        panel.setBackground(bg);
        panel.setClickable(true);
        panel.setPadding(Ui.dp(c, 24), Ui.dp(c, 12), Ui.dp(c, 24), Ui.dp(c, 24));

        View handle = new View(c);
        handle.setBackground(Ui.panel(c, Ui.HAIRLINE_HI, 0, 2));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(handle, hlp);

        TextView t = Ui.title(c, title);
        t.setSingleLine(false);
        panel.addView(t, Ui.top(c, Ui.matchWrap(), 20));
        if (message != null) {
            messageView = Ui.body(c, message, Ui.TEXT_2);
            panel.addView(messageView, Ui.top(c, Ui.matchWrap(), 8));
        }

        content = Ui.column(c);
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        actions = Ui.row(c);
        panel.addView(actions, Ui.top(c, Ui.matchWrap(), 24));
        actions.setVisibility(GONE);

        // Wrap-content with a weighted scroll area: short sheets hug their
        // content, tall ones stop 72dp from the top and scroll inside.
        LayoutParams plp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        plp.topMargin = Ui.dp(c, 72);
        addView(panel, plp);
    }

    Sheet view(View v) {
        content.addView(v, Ui.top(getContext(), Ui.matchWrap(), 16));
        return this;
    }

    /** A tappable row: icon, name, optional detail. Dismisses, then runs. */
    Sheet item(String iconData, String name, String detail, boolean danger, Runnable run) {
        return item(iconData, null, name, detail, danger, run);
    }

    /** As above, with an app's own icon in place of a line icon. */
    Sheet item(android.graphics.drawable.Drawable image, String name, Runnable run) {
        return item(null, image, name, null, false, run);
    }

    private Sheet item(String iconData, android.graphics.drawable.Drawable image, String name,
            String detail, boolean danger, Runnable run) {
        Context c = getContext();
        LinearLayout row = Ui.row(c);
        row.setMinimumHeight(Ui.dp(c, 56));
        row.setBackground(Ui.ghost(c, 10));
        row.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        int color = danger ? Ui.DANGER : Ui.TEXT;
        if (iconData != null) {
            row.addView(Ui.icon(c, iconData, 20, danger ? Ui.DANGER : Ui.TEXT_2));
        } else if (image != null) {
            android.widget.ImageView iv = new android.widget.ImageView(c);
            // Its own copy: the same icon object may already be on show in the
            // app drawer at another size, and a drawable has only one size.
            android.graphics.drawable.Drawable.ConstantState state = image.getConstantState();
            iv.setImageDrawable(state == null ? image : state.newDrawable(c.getResources()));
            row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32)));
        }
        LinearLayout text = Ui.column(c);
        TextView n = Ui.body(c, name, color);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(n);
        if (detail != null) text.addView(Ui.small(c, detail, Ui.TEXT_3));
        row.addView(text, Ui.left(c, Ui.weighted(), iconData == null && image == null ? 0 : 16));
        row.setOnClickListener(v -> {
            dismiss();
            run.run();
        });
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.leftMargin = -Ui.dp(c, 8);
        lp.rightMargin = -Ui.dp(c, 8);
        if (content.getChildCount() == 0) lp.topMargin = Ui.dp(c, 8);
        content.addView(row, lp);
        return this;
    }

    /** Adds a button to the action row. Buttons share the width equally. */
    Sheet action(String label, int kind, boolean dismissFirst, Runnable run) {
        Context c = getContext();
        LinearLayout b = Ui.button(c, label, null, kind);
        b.setOnClickListener(v -> {
            if (dismissFirst) dismiss();
            if (run != null) run.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f);
        if (actions.getChildCount() > 0) lp.leftMargin = Ui.dp(c, 8);
        actions.addView(b, lp);
        actions.setVisibility(VISIBLE);
        return this;
    }

    /** Changes the words under the title while the sheet is up, for a state that moves on. */
    void setMessage(String message) {
        if (messageView != null && !message.contentEquals(messageView.getText())) {
            messageView.setText(message);
        }
    }

    Sheet onDismiss(Runnable r) {
        onDismiss = r;
        return this;
    }

    Sheet show() {
        if (current() != null) current.remove();
        // A message bar left over from the last action would sit on the buttons.
        Snack.clear(host);
        current = this;
        applyInsets();
        host.addView(this, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setAlpha(0f);
        animate().alpha(1f).setDuration(Ui.MOTION_MS).start();
        panel.post(() -> {
            panel.setTranslationY(panel.getHeight());
            panel.animate().translationY(0f).setDuration(Ui.MOTION_MS)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
        return this;
    }

    /** System bars and keyboard, passed on by the activity as they change. */
    static void setInsets(int top, int bottom) {
        insetTop = top;
        insetBottom = bottom;
        if (current() != null) current.applyInsets();
    }

    private void applyInsets() {
        setPadding(0, insetTop, 0, 0);
        panel.setPadding(panel.getPaddingLeft(), panel.getPaddingTop(),
                panel.getPaddingRight(), Ui.dp(getContext(), 24) + insetBottom);
    }

    void dismiss() {
        if (closing) return;
        closing = true;
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        panel.animate().translationY(panel.getHeight()).setDuration(150).start();
        animate().alpha(0f).setDuration(150).withEndAction(this::remove).start();
    }

    private void remove() {
        if (current == this) current = null;
        host.removeView(this);
        if (onDismiss != null) {
            Runnable r = onDismiss;
            onDismiss = null;
            r.run();
        }
    }

    /** The sheet on screen, if any. */
    static Sheet current() {
        // The screen it belonged to may have been torn down and rebuilt (a
        // font-size or language change does that); a sheet left over from
        // before is no longer showing anywhere.
        if (current != null && !current.isAttachedToWindow()) current = null;
        return current;
    }

    /** @return true if a sheet was showing and has been dismissed */
    static boolean dismissCurrent() {
        if (current() == null) return false;
        current.dismiss();
        return true;
    }
}
