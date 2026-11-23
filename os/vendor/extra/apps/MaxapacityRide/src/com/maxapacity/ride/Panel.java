package com.maxapacity.ride;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A full-screen destination layered over the dashboard: back arrow, title,
 * status pill, then the screen's own content. Every screen other than the
 * dashboard is one of these, so they all open, close and line up the same way.
 */
abstract class Panel extends LinearLayout {

    protected final LinearLayout header;
    protected final TextView titleView;
    protected final Ui.Pill status;
    /** Slot on the right of the header for one screen-level action. */
    protected final FrameLayout headerAction;

    Panel(Context context, String title) {
        super(context);
        Ui.init(context);
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);
        setClickable(true); // swallow touches so the dashboard underneath stays put
        setVisibility(GONE);

        header = Ui.row(context);
        View back = Ui.iconButton(context, Icons.CHEVRON_LEFT, "Back");
        back.setOnClickListener(v -> close());
        header.addView(back);
        titleView = Ui.title(context, title);
        header.addView(titleView, Ui.left(context, Ui.weighted(), 4));
        status = new Ui.Pill(context);
        header.addView(status, Ui.wrap());
        headerAction = new FrameLayout(context);
        header.addView(headerAction, Ui.wrap());
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        // The back arrow's own padding puts its glyph on the 16dp page margin.
        lp.leftMargin = -dp(12);
        addView(header, lp);
    }

    /** Vertical scroll area filling the rest of the screen; returns its content column. */
    protected LinearLayout addScrollContent() {
        LinearLayout content = Ui.column(getContext());
        content.setPadding(0, dp(8), 0, dp(24));
        ScrollView scroll = new ScrollView(getContext());
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return content;
    }

    protected void setHeaderAction(View action) {
        headerAction.removeAllViews();
        if (action != null) {
            headerAction.addView(action, new FrameLayout.LayoutParams(
                    dp(48), dp(48), Gravity.CENTER));
            ((LayoutParams) status.getLayoutParams()).rightMargin = 0;
        }
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    void open() {
        if (isOpen()) return;
        onOpen();
        animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(0f);
        setTranslationY(dp(24));
        animate().alpha(1f).translationY(0f).setDuration(Ui.MOTION_MS)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(null).start();
    }

    void close() {
        if (!isOpen()) return;
        onClose();
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        animate().cancel();
        animate().alpha(0f).translationY(dp(24)).setDuration(150)
                .withEndAction(() -> setVisibility(GONE)).start();
    }

    /** Called just before the panel becomes visible. */
    protected void onOpen() {
    }

    /** Called when the panel starts closing. */
    protected void onClose() {
    }

    /** The launcher left the foreground / came back while this panel is open. */
    void onHostPause() {
    }

    void onHostResume() {
    }

    protected LinearLayout section(LinearLayout parent, String name) {
        TextView l = Ui.label(getContext(), name);
        l.setPadding(dp(4), dp(24), 0, dp(8));
        parent.addView(l, Ui.matchWrap());
        return parent;
    }

    protected int dp(float v) {
        return Ui.dp(getContext(), v);
    }
}
