package com.maxapacity.ride;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The design system: one place for colour, type, spacing and the handful of
 * components every screen is built from.
 *
 * Colour carries meaning or is absent: graphite surfaces, a grayscale text
 * ramp, one voltage accent for the primary action and live values, and
 * green / amber / red for status only. Spacing sits on the 4 / 8 / 12 / 16 /
 * 24 / 32 / 48 scale. Cards are 16dp radius, controls 10dp, pills full.
 */
final class Ui {

    private Ui() {
    }

    // ---- Colour ----------------------------------------------------------

    static final int BG = 0xFF0B0B0D;
    static final int SURFACE = 0xFF151518;
    static final int SURFACE_HI = 0xFF1E1E23;
    static final int HAIRLINE = 0xFF27272E;
    static final int HAIRLINE_HI = 0xFF3A3A44;
    static final int TEXT = 0xFFF5F5F2;
    static final int TEXT_2 = 0xFFA3A3AD;
    static final int TEXT_3 = 0xFF6E6E7A;
    static final int ACCENT = 0xFFFFEB1A;
    static final int ACCENT_DIM = 0x29FFEB1A;
    static final int OK = 0xFF4ADE80;
    static final int WARN = 0xFFFBBF24;
    static final int DANGER = 0xFFF87171;
    static final int SCRIM = 0xB3000000;

    // ---- Type ------------------------------------------------------------

    static final int T_LABEL = 11;
    static final int T_SMALL = 13;
    static final int T_BODY = 15;
    static final int T_TITLE = 20;

    static final int MOTION_MS = 180;

    // Loaded once from assets/fonts; the platform faces stand in if that fails.
    static Typeface sans;
    static Typeface sansBold;
    static Typeface mono;
    static Typeface monoBold;

    static void init(Context c) {
        if (sans != null) return;
        sans = font(c, "fonts/SpaceGrotesk.ttf", 500, Typeface.DEFAULT);
        sansBold = font(c, "fonts/SpaceGrotesk.ttf", 700, Typeface.DEFAULT_BOLD);
        mono = font(c, "fonts/JetBrainsMono.ttf", 500, Typeface.MONOSPACE);
        monoBold = font(c, "fonts/JetBrainsMono.ttf", 700,
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }

    private static Typeface font(Context c, String asset, int weight, Typeface fallback) {
        try {
            Typeface t = new Typeface.Builder(c.getAssets(), asset)
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
            return t == null ? fallback : t;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ---- Metrics ---------------------------------------------------------

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    static LinearLayout.LayoutParams top(Context c, LinearLayout.LayoutParams lp, int marginDp) {
        lp.topMargin = dp(c, marginDp);
        return lp;
    }

    static LinearLayout.LayoutParams left(Context c, LinearLayout.LayoutParams lp, int marginDp) {
        lp.leftMargin = dp(c, marginDp);
        return lp;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    // ---- Text ------------------------------------------------------------

    /** Small caps mono caption: section names, stat names, status. */
    static TextView label(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, T_LABEL);
        t.setTextColor(TEXT_3);
        t.setLetterSpacing(0.1f);
        t.setTypeface(mono);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView body(Context c, String text, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, T_BODY);
        t.setTextColor(color);
        t.setTypeface(sans);
        t.setLineSpacing(0f, 1.15f);
        return t;
    }

    static TextView small(Context c, String text, int color) {
        TextView t = body(c, text, color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, T_SMALL);
        return t;
    }

    static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, T_TITLE);
        t.setTextColor(TEXT);
        t.setTypeface(sansBold);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A number: mono, tabular, bold. */
    static TextView value(Context c, String text, int sp) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(TEXT);
        t.setTypeface(monoBold);
        t.setFontFeatureSettings("tnum");
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** "12.4 mi": the number at full size, its unit small and dim after it. */
    static CharSequence withUnit(String number, String unit) {
        if (unit == null || unit.isEmpty()) return number;
        // A percent sign hugs its number; other units stand off by a space.
        String gap = "%".equals(unit) ? "" : " ";
        android.text.SpannableString s = new android.text.SpannableString(number + gap + unit);
        int from = number.length();
        s.setSpan(new android.text.style.RelativeSizeSpan(0.6f), from, s.length(), 0);
        s.setSpan(new android.text.style.ForegroundColorSpan(TEXT_3), from, s.length(), 0);
        if (mono != null) {
            s.setSpan(new android.text.style.TypefaceSpan(mono), from, s.length(), 0);
        }
        return s;
    }

    // ---- Surfaces --------------------------------------------------------

    static GradientDrawable panel(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) d.setStroke(Math.max(1, dp(c, 1)), stroke);
        return d;
    }

    /** Card surface with a touch ripple clipped to its corners. */
    static Drawable pressable(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable mask = panel(c, 0xFFFFFFFF, 0, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(0x24FFFFFF),
                panel(c, fill, stroke, radiusDp), mask);
    }

    /** Ripple for a control with no surface of its own (icon buttons, rows). */
    static Drawable ghost(Context c, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null,
                panel(c, 0xFFFFFFFF, 0, radiusDp));
    }

    static LinearLayout card(Context c) {
        LinearLayout card = column(c);
        card.setBackground(panel(c, SURFACE, HAIRLINE, 16));
        card.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        return card;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(HAIRLINE);
        return v;
    }

    // ---- Icons -----------------------------------------------------------

    static ImageView icon(Context c, String data, int sizeDp, int color) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(Icons.of(c, data, color));
        int s = dp(c, sizeDp);
        v.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return v;
    }

    /** 48dp touch target around a 22dp icon. */
    static ImageView iconButton(Context c, String data, String description) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(Icons.of(c, data, TEXT));
        int pad = dp(c, 13);
        v.setPadding(pad, pad, pad, pad);
        v.setBackground(ghost(c, 24));
        v.setContentDescription(description);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        press(v);
        return v;
    }

    // ---- Buttons ---------------------------------------------------------

    static final int PRIMARY = 0;
    static final int SECONDARY = 1;
    static final int DANGER_OUTLINE = 2;
    static final int QUIET = 3;

    /** 48dp button: optional leading icon, caps label. */
    static LinearLayout button(Context c, String text, String iconData, int kind) {
        LinearLayout b = row(c);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        b.setMinimumHeight(dp(c, 48));
        int fg;
        switch (kind) {
            case PRIMARY:
                fg = BG;
                b.setBackground(pressable(c, ACCENT, 0, 10));
                break;
            case DANGER_OUTLINE:
                fg = DANGER;
                b.setBackground(pressable(c, 0x14F87171, 0x66F87171, 10));
                break;
            case QUIET:
                fg = TEXT_2;
                b.setBackground(ghost(c, 10));
                break;
            default:
                fg = TEXT;
                b.setBackground(pressable(c, SURFACE_HI, HAIRLINE_HI, 10));
                break;
        }
        if (iconData != null) {
            ImageView i = icon(c, iconData, 18, fg);
            ((LinearLayout.LayoutParams) i.getLayoutParams()).rightMargin = dp(c, 8);
            b.addView(i);
        }
        TextView t = new TextView(c);
        t.setText(text.toUpperCase(java.util.Locale.getDefault()));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, T_SMALL);
        t.setTypeface(sansBold);
        t.setLetterSpacing(0.06f);
        t.setTextColor(fg);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        b.addView(t);
        b.setClickable(true);
        b.setFocusable(true);
        press(b);
        return b;
    }

    static void setButtonText(LinearLayout button, String text) {
        ((TextView) button.getChildAt(button.getChildCount() - 1))
                .setText(text.toUpperCase(java.util.Locale.getDefault()));
    }

    /** Subtle press-in on touch, with a tick of haptic feedback on release. */
    static void press(final View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    // ---- Status pill -----------------------------------------------------

    /** Dot + caps label on a tinted pill; the colour is the status. */
    static final class Pill extends LinearLayout {
        private final View dot;
        private final TextView text;

        Pill(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 12), 0);
            setMinimumHeight(Ui.dp(c, 28));
            dot = new View(c);
            LayoutParams lp = new LayoutParams(Ui.dp(c, 6), Ui.dp(c, 6));
            lp.rightMargin = Ui.dp(c, 8);
            addView(dot, lp);
            text = label(c, "");
            addView(text);
            set("", TEXT_3);
        }

        void set(String status, int color) {
            text.setText(status);
            text.setTextColor(color);
            // A neutral caption (a count, say) is not a status: no dot, no tint.
            boolean neutral = color == TEXT_3;
            dot.setVisibility(neutral ? GONE : VISIBLE);
            dot.setBackground(panel(getContext(), color, 0, 3));
            setBackground(neutral ? null
                    : panel(getContext(), (color & 0x00FFFFFF) | 0x1F000000, 0, 14));
            setPadding(neutral ? 0 : Ui.dp(getContext(), 10), 0,
                    neutral ? Ui.dp(getContext(), 4) : Ui.dp(getContext(), 12), 0);
            setVisibility(status.isEmpty() ? INVISIBLE : VISIBLE);
        }
    }

    // ---- Switch ----------------------------------------------------------

    /** On/off switch. Accent when on: it marks a setting that is active. */
    static final class Toggle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private boolean on;
        private float position;
        private ValueAnimator animator;

        Toggle(Context c, boolean on) {
            super(c);
            this.on = on;
            position = on ? 1f : 0f;
            setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 26)));
        }

        boolean isOn() {
            return on;
        }

        void set(boolean value, boolean animate) {
            if (on == value) return;
            on = value;
            if (animator != null) animator.cancel();
            if (!animate) {
                position = on ? 1f : 0f;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(position, on ? 1f : 0f);
            animator.setDuration(MOTION_MS);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                position = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight();
            float w = getWidth();
            track.set(0, 0, w, h);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blend(SURFACE_HI, ACCENT, position));
            canvas.drawRoundRect(track, h / 2f, h / 2f, paint);
            if (position < 1f) {
                float inset = Ui.dp(getContext(), 1) / 2f;
                track.inset(inset, inset);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(getContext(), 1));
                paint.setColor(HAIRLINE_HI);
                paint.setAlpha((int) (255 * (1f - position)));
                canvas.drawRoundRect(track, h / 2f, h / 2f, paint);
                paint.setAlpha(255);
            }
            float r = h / 2f - Ui.dp(getContext(), 4);
            float cx = h / 2f + (w - h) * position;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blend(TEXT_2, BG, position));
            canvas.drawCircle(cx, h / 2f, r, paint);
        }
    }

    static int blend(int from, int to, float t) {
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ---- Progress bar ----------------------------------------------------

    /** 6dp bar. The fill colour is passed in so it can carry status. */
    static View bar(Context c, int pct, int color) {
        int clamped = Math.max(0, Math.min(100, pct));
        LinearLayout trackView = new LinearLayout(c);
        trackView.setBackground(panel(c, SURFACE_HI, 0, 3));
        View fill = new View(c);
        fill.setBackground(panel(c, color, 0, 3));
        trackView.addView(fill, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, clamped));
        trackView.addView(new View(c), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - clamped));
        trackView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 6)));
        return trackView;
    }

    // ---- Inputs ----------------------------------------------------------

    static EditText input(Context c, String hint, boolean secret) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(TEXT_3);
        e.setTextColor(TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, T_BODY);
        e.setTypeface(secret ? mono : sans);
        e.setSingleLine(true);
        e.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        e.setBackground(panel(c, BG, HAIRLINE_HI, 10));
        e.setOnFocusChangeListener((v, focused) -> v.setBackground(
                panel(c, BG, focused ? ACCENT : HAIRLINE_HI, 10)));
        e.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 48)));
        return e;
    }

    // ---- Skeleton --------------------------------------------------------

    /** Layout-matched placeholder block that breathes while data loads. */
    static View skeleton(Context c, int heightDp) {
        final View block = new View(c);
        block.setBackground(panel(c, SURFACE, HAIRLINE, 16));
        block.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        block.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            private ValueAnimator shimmer;

            @Override
            public void onViewAttachedToWindow(View v) {
                shimmer = ValueAnimator.ofFloat(1f, 0.45f);
                shimmer.setDuration(800);
                shimmer.setRepeatMode(ValueAnimator.REVERSE);
                shimmer.setRepeatCount(ValueAnimator.INFINITE);
                shimmer.addUpdateListener(a -> block.setAlpha((float) a.getAnimatedValue()));
                shimmer.start();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (shimmer != null) shimmer.cancel();
            }
        });
        return block;
    }

    // ---- Empty / error state --------------------------------------------

    /** Icon, one-line pitch and (optionally) the action that fixes it. */
    static LinearLayout emptyState(Context c, String iconData, String headline, String detail,
            String action, int kind, View.OnClickListener click) {
        LinearLayout box = column(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(c, 24), dp(c, 48), dp(c, 24), dp(c, 24));
        LinearLayout badge = new LinearLayout(c);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(panel(c, SURFACE, HAIRLINE, 28));
        badge.addView(icon(c, iconData, 24, TEXT_2));
        box.addView(badge, new LinearLayout.LayoutParams(dp(c, 56), dp(c, 56)));
        TextView h = title(c, headline);
        h.setGravity(Gravity.CENTER);
        h.setSingleLine(false);
        box.addView(h, top(c, matchWrap(), 16));
        if (detail != null) {
            TextView d = body(c, detail, TEXT_2);
            d.setGravity(Gravity.CENTER);
            box.addView(d, top(c, matchWrap(), 8));
        }
        if (action != null) {
            LinearLayout b = button(c, action, null, kind);
            b.setOnClickListener(click);
            box.addView(b, top(c, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 48)), 24));
        }
        return box;
    }
}
