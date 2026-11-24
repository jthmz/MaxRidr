package com.maxapacity.ride;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A walk round the screen for someone new to it. One part of the screen is
 * lit at a time with the rest dimmed, and a card beside it says what that
 * part is for. Next and Back step through; the cross, Skip, Done or the Back
 * key end it.
 *
 * Nothing underneath can be touched while it is up, so a tap meant for the
 * card cannot start a ride. One tour at a time: showing a second replaces
 * the first.
 */
final class Tour extends FrameLayout {

    /** Set once a new rider has answered the offer of the tour, either way. */
    static final String OFFERED = "tour_offered";

    private static Tour current;
    private static int insetTop;
    private static int insetBottom;

    private static final class Stop {
        final View target;
        final String title;
        final String text;

        Stop(View target, String title, String text) {
            this.target = target;
            this.title = title;
            this.text = text;
        }
    }

    private final ViewGroup host;
    private final List<Stop> stops = new ArrayList<>();
    private final LinearLayout card;
    private final TextView count;
    private final TextView title;
    private final TextView text;
    private final LinearLayout back;
    private final LinearLayout next;

    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path dim = new Path();
    private final Rect bounds = new Rect();
    /** Where the lit part belongs, where the light was when this stop began, and where it is drawn. */
    private final RectF aim = new RectF();
    private final RectF from = new RectF();
    private final RectF lit = new RectF();
    /** 0 to 1 as the light travels from the last stop to this one. */
    private float glide = 1f;
    private ValueAnimator gliding;

    private int at;
    private boolean closing;
    private Runnable onEnd;

    /** The screen underneath was laid out again: the lit part may have moved. */
    private final ViewTreeObserver.OnGlobalLayoutListener moved = this::invalidate;

    Tour(ViewGroup host) {
        super(host.getContext());
        this.host = host;
        Context c = getContext();
        Ui.init(c);
        setClickable(true); // swallow touches so the screen underneath stays put

        dimPaint.setStyle(Paint.Style.FILL);
        dimPaint.setColor(Ui.SCRIM);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Ui.dp(c, 2));
        ringPaint.setColor(Ui.ACCENT);

        card = Ui.column(c);
        card.setBackground(Ui.panel(c, Ui.SURFACE_HI, Ui.HAIRLINE_HI, 16));
        card.setPadding(Ui.dp(c, 16), Ui.dp(c, 4), Ui.dp(c, 16), Ui.dp(c, 16));
        card.setElevation(Ui.dp(c, 8));
        card.setClickable(true);

        LinearLayout head = Ui.row(c);
        count = Ui.label(c, "");
        head.addView(count, Ui.weighted());
        ImageView end = Ui.iconButton(c, Icons.X, "End the tour");
        end.setOnClickListener(v -> close());
        head.addView(end, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        end.setPadding(Ui.dp(c, 11), Ui.dp(c, 11), Ui.dp(c, 11), Ui.dp(c, 11));
        LinearLayout.LayoutParams hlp = Ui.matchWrap();
        hlp.rightMargin = -Ui.dp(c, 12); // the cross sits on the card's own margin
        card.addView(head, hlp);

        title = Ui.title(c, "");
        title.setSingleLine(false);
        card.addView(title, Ui.matchWrap());
        text = Ui.body(c, "", Ui.TEXT_2);
        card.addView(text, Ui.top(c, Ui.matchWrap(), 8));

        LinearLayout actions = Ui.row(c);
        back = Ui.button(c, "Back", null, Ui.SECONDARY);
        back.setOnClickListener(v -> {
            if (at == 0) close();
            else go(at - 1);
        });
        actions.addView(back, new LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f));
        next = Ui.button(c, "Next", null, Ui.PRIMARY);
        next.setOnClickListener(v -> {
            if (at >= stops.size() - 1) close();
            else go(at + 1);
        });
        actions.addView(next, Ui.left(c,
                new LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f), 8));
        card.addView(actions, Ui.top(c, Ui.matchWrap(), 16));

        // Laid out at the top; where it sits is decided against the lit part, as it is drawn.
        LayoutParams clp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        clp.leftMargin = Ui.dp(c, 16);
        clp.rightMargin = Ui.dp(c, 16);
        addView(card, clp);
    }

    /** Adds a stop: the part of the screen to light, and what to say about it. */
    Tour stop(View target, String title, String text) {
        stops.add(new Stop(target, title, text));
        return this;
    }

    /** Run once the tour has gone, however it ended. */
    Tour onEnd(Runnable r) {
        onEnd = r;
        return this;
    }

    Tour show() {
        if (stops.isEmpty()) return this;
        if (current() != null) current.remove();
        // A message bar left over from the last action would sit under the dimming.
        Snack.clear(host);
        current = this;
        host.addView(this, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        at = 0;
        fill();
        setAlpha(0f);
        animate().alpha(1f).setDuration(Ui.MOTION_MS).start();
        return this;
    }

    /** Moves to another stop: the light travels there and the card follows. */
    private void go(int index) {
        from.set(lit);
        at = index;
        fill();
        if (gliding != null) gliding.cancel();
        glide = 0f;
        gliding = ValueAnimator.ofFloat(0f, 1f);
        gliding.setDuration(Ui.MOTION_MS);
        gliding.setInterpolator(new DecelerateInterpolator());
        gliding.addUpdateListener(a -> {
            glide = (float) a.getAnimatedValue();
            invalidate();
        });
        gliding.start();
        // The card changes place in one step, so it fades in where it lands.
        card.setAlpha(0f);
        card.animate().alpha(1f).setDuration(Ui.MOTION_MS).start();
        invalidate();
    }

    private void fill() {
        Stop stop = stops.get(at);
        boolean last = at == stops.size() - 1;
        count.setText((at + 1) + " OF " + stops.size());
        title.setText(stop.title);
        text.setText(stop.text);
        Ui.setButtonText(back, at == 0 ? "Skip" : "Back");
        Ui.setButtonText(next, last ? "Done" : "Next");
        announceForAccessibility(stop.title + ". " + stop.text);
    }

    void close() {
        if (closing) return;
        closing = true;
        animate().alpha(0f).setDuration(150).withEndAction(this::remove).start();
    }

    private void remove() {
        if (current == this) current = null;
        host.removeView(this);
        if (onEnd != null) {
            Runnable r = onEnd;
            onEnd = null;
            r.run();
        }
    }

    /** The tour on screen, if any. */
    static Tour current() {
        // As with a sheet: one left over from a screen that was torn down
        // and rebuilt is no longer showing anywhere.
        if (current != null && !current.isAttachedToWindow()) current = null;
        return current;
    }

    /** @return true if a tour was showing and has been ended */
    static boolean dismissCurrent() {
        if (current() == null) return false;
        current.close();
        return true;
    }

    /** System bars, passed on by the activity as they change. */
    static void setInsets(int top, int bottom) {
        insetTop = top;
        insetBottom = bottom;
        if (current() != null) current.invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalLayoutListener(moved);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalLayoutListener(moved);
        if (gliding != null) gliding.cancel();
        super.onDetachedFromWindow();
    }

    // ---- Drawing -----------------------------------------------------------

    /**
     * Where everything goes is worked out here, as it is drawn, from where
     * the lit part is at that moment: the screen underneath keeps living
     * (a ride can start, the keyboard can come and go) and may move it.
     */
    @Override
    protected void dispatchDraw(Canvas canvas) {
        locate(stops.isEmpty() ? null : stops.get(at).target, aim);
        if (aim.isEmpty() || from.isEmpty() || glide >= 1f) {
            lit.set(aim);
        } else {
            lit.set(from.left + (aim.left - from.left) * glide,
                    from.top + (aim.top - from.top) * glide,
                    from.right + (aim.right - from.right) * glide,
                    from.bottom + (aim.bottom - from.bottom) * glide);
        }
        placeCard();

        // The dimming is the whole screen with the lit part cut out of it.
        float r = Ui.dp(getContext(), 20);
        dim.reset();
        dim.setFillType(Path.FillType.EVEN_ODD);
        dim.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        if (!lit.isEmpty()) dim.addRoundRect(lit, r, r, Path.Direction.CW);
        canvas.drawPath(dim, dimPaint);
        if (!lit.isEmpty()) canvas.drawRoundRect(lit, r, r, ringPaint);

        super.dispatchDraw(canvas);
    }

    /** The outline round a part of the screen, in this view's own coordinates. Empty if it is not on show. */
    private void locate(View target, RectF into) {
        into.setEmpty();
        if (target == null || target.getWidth() <= 0 || target.getHeight() <= 0) return;
        bounds.set(0, 0, target.getWidth(), target.getHeight());
        try {
            host.offsetDescendantRectToMyCoords(target, bounds);
        } catch (IllegalArgumentException e) {
            return; // no longer part of this screen
        }
        bounds.offset(-getLeft(), -getTop());
        float pad = Ui.dp(getContext(), 6);
        // A part that reaches the edge of the screen still gets its whole outline.
        float edge = Ui.dp(getContext(), 2);
        into.set(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad);
        if (!into.intersect(edge, edge, getWidth() - edge, getHeight() - edge)) into.setEmpty();
    }

    /** Under the lit part if there is room, else above it, else over whichever end is further from it. */
    private void placeCard() {
        int margin = Ui.dp(getContext(), 16);
        int gap = Ui.dp(getContext(), 12);
        int height = card.getHeight();
        float highest = insetTop + margin;
        float lowest = getHeight() - insetBottom - margin - height;
        float y;
        if (aim.isEmpty()) {
            y = (highest + lowest) / 2f;
        } else if (aim.bottom + gap <= lowest) {
            y = aim.bottom + gap;
        } else if (aim.top - gap - height >= highest) {
            y = aim.top - gap - height;
        } else {
            y = aim.centerY() < getHeight() / 2f ? lowest : highest;
        }
        // On a screen too short for the card, its top stays in view.
        y = Math.max(highest, Math.min(y, lowest));
        card.setTranslationY(Math.round(y) - card.getTop());
    }
}
