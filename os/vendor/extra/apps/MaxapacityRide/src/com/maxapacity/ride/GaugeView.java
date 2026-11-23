package com.maxapacity.ride;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

/**
 * The speedometer: a 270 degree ring of ticks that light up with speed, the
 * number itself in the middle, the unit in the gap at the bottom.
 *
 * Everything on it reads as data: lit ticks are the current speed, the white
 * notch is this ride's top speed, the numerals are the scale. The only
 * ornament is a soft accent glow behind the dial and a slow sweep ring, both
 * of which switch off with the "Visual effects" setting and hold still when
 * the system "remove animations" setting is on.
 */
final class GaugeView extends View {

    private static final float START = 135f;
    private static final float SWEEP = 270f;
    private static final int TICKS = 80;

    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scale = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float density;

    private boolean metric;
    private boolean hasFix;
    private boolean fx = true;
    private boolean running;
    private float targetMps;
    private float shownMps;
    private float topMps;
    private float sweepAngle;
    private long lastFrameMs;
    private float glowRadius;
    private boolean hasPower;
    private float powerW;
    private float peakW = 1f;

    GaugeView(Context context) {
        super(context);
        Ui.init(context);
        density = getResources().getDisplayMetrics().density;

        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeCap(Paint.Cap.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);

        // The grotesk's plain zero reads cleaner than the mono's dotted one at this size.
        number.setTypeface(Ui.sansBold);
        number.setTextAlign(Paint.Align.CENTER);
        number.setFontFeatureSettings("tnum");
        number.setColor(Ui.TEXT);

        caption.setTypeface(Ui.mono);
        caption.setTextAlign(Paint.Align.CENTER);
        caption.setTextSize(11 * density);
        caption.setLetterSpacing(0.12f);
        caption.setColor(Ui.TEXT_2);

        scale.setTypeface(Ui.mono);
        scale.setTextAlign(Paint.Align.CENTER);
        scale.setTextSize(11 * density);
        scale.setColor(Ui.TEXT_3);
    }

    void setMetric(boolean metric) {
        if (this.metric == metric) return;
        this.metric = metric;
        invalidate();
    }

    void setFx(boolean on) {
        if (fx == on) return;
        fx = on;
        invalidate();
    }

    /** Animate only while the dashboard is on screen. */
    void setRunning(boolean run) {
        if (running == run) return;
        running = run;
        lastFrameMs = 0;
        invalidate();
    }

    void setSpeed(float mps, boolean hasFix) {
        targetMps = Math.max(0f, mps);
        if (this.hasFix != hasFix) {
            this.hasFix = hasFix;
            invalidate();
        }
        if (running && Math.abs(targetMps - shownMps) > 0.01f) invalidate();
    }

    /**
     * Live power from the bike link. When present the inner ring becomes a
     * power meter (white while driving, green while regenerating) and the
     * figure is shown above the speed.
     */
    void setPower(boolean available, float watts, float peakWatts) {
        if (hasPower == available && Math.abs(powerW - watts) < 50f) return;
        hasPower = available;
        powerW = watts;
        peakW = Math.max(peakWatts, 1f);
        invalidate();
    }

    void setTopSpeed(float mps) {
        if (topMps == mps) return;
        topMps = mps;
        invalidate();
    }

    /** Full scale: 80 mph or 120 km/h, which covers a 72 V light e-moto. */
    private float fullScaleUnits() {
        return metric ? 120f : 80f;
    }

    private float toUnits(float mps) {
        return mps * (metric ? 3.6f : 2.23694f);
    }

    private boolean animationsAllowed() {
        return Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean animate = running && animationsAllowed();
        long now = SystemClock.uptimeMillis();
        float dt = lastFrameMs == 0 ? 0f : Math.min((now - lastFrameMs) / 1000f, 0.1f);
        lastFrameMs = now;
        boolean settling = Math.abs(targetMps - shownMps) > 0.01f;
        if (animate) {
            // Ease between one-second GPS fixes so the ring glides, not steps.
            shownMps += (targetMps - shownMps) * Math.min(1f, dt * 4f);
            sweepAngle = (sweepAngle + dt * 24f) % 360f;
        } else {
            shownMps = targetMps;
        }

        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        // The dial is a 270 degree arc, so its visual centre sits above the
        // middle of its bounding circle; nudge down to centre what is drawn.
        float radius = Math.min(w / 2f - 8 * density, h / 1.72f - 8 * density);
        // The dial gets whatever height the dashboard has left over, which is
        // next to nothing while the keyboard is up. Below this size there is
        // no room for a readable dial, so draw nothing rather than a smudge
        // (and a gradient cannot be built with no radius at all).
        if (radius < 40 * density) return;
        // A cramped dial keeps only the ticks and the number.
        boolean roomy = radius >= 96 * density;
        float cy = h / 2f + radius * 0.14f;
        float fraction = Math.min(1f, toUnits(shownMps) / fullScaleUnits());

        if (fx) drawGlow(canvas, cx, cy, radius, fraction);
        drawTicks(canvas, cx, cy, radius, fraction);
        if (roomy) {
            drawScale(canvas, cx, cy, radius);
            if (hasPower) drawPower(canvas, cx, cy, radius);
            else if (fx) drawSweep(canvas, cx, cy, radius);
        }
        drawReadout(canvas, cx, cy, radius);

        // Redraw continuously only while something on the dial is actually moving.
        if (animate && (settling || (fx && !hasPower))) postInvalidateOnAnimation();
    }

    /** The one glow: faint at rest, a little stronger with speed. */
    private void drawGlow(Canvas canvas, float cx, float cy, float radius, float fraction) {
        if (glowRadius != radius) {
            glowRadius = radius;
            glow.setShader(new RadialGradient(cx, cy, radius * 1.15f,
                    new int[] {0x30FFEB1A, 0x10FFEB1A, 0x00FFEB1A},
                    new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        }
        glow.setAlpha((int) (255 * (0.35f + 0.65f * fraction)));
        canvas.drawCircle(cx, cy, radius * 1.15f, glow);
    }

    private void drawTicks(Canvas canvas, float cx, float cy, float radius, float fraction) {
        float outer = radius;
        float lit = fraction * TICKS;
        for (int i = 0; i <= TICKS; i++) {
            boolean major = i % 10 == 0;
            float angle = (float) Math.toRadians(START + SWEEP * i / TICKS);
            float len = (major ? 14 : 8) * density;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            boolean on = hasFix && i <= lit && lit > 0.5f;
            tick.setStrokeWidth((major ? 2.5f : 1.5f) * density);
            tick.setColor(on ? Ui.ACCENT : (major ? Ui.HAIRLINE_HI : Ui.HAIRLINE));
            canvas.drawLine(cx + cos * (outer - len), cy + sin * (outer - len),
                    cx + cos * outer, cy + sin * outer, tick);
        }
        // Top speed this ride: a white notch just outside the ring.
        if (topMps > 0.5f) {
            float f = Math.min(1f, toUnits(topMps) / fullScaleUnits());
            float angle = (float) Math.toRadians(START + SWEEP * f);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            tick.setStrokeWidth(2.5f * density);
            tick.setColor(Ui.TEXT);
            canvas.drawLine(cx + cos * (outer + 3 * density), cy + sin * (outer + 3 * density),
                    cx + cos * (outer + 9 * density), cy + sin * (outer + 9 * density), tick);
        }
    }

    private void drawScale(Canvas canvas, float cx, float cy, float radius) {
        float r = radius - 30 * density;
        float full = fullScaleUnits();
        Paint.FontMetrics fm = scale.getFontMetrics();
        for (int i = 0; i <= 8; i++) {
            if (i % 2 != 0) continue;
            float angle = (float) Math.toRadians(START + SWEEP * i / 8f);
            String s = String.valueOf(Math.round(full * i / 8f));
            canvas.drawText(s, cx + (float) Math.cos(angle) * r,
                    cy + (float) Math.sin(angle) * r - (fm.ascent + fm.descent) / 2f, scale);
        }
    }

    /** Thin inner ring with two short arcs that orbit slowly. */
    private void drawSweep(Canvas canvas, float cx, float cy, float radius) {
        float r = radius - 50 * density;
        if (r <= 0) return;
        box.set(cx - r, cy - r, cx + r, cy + r);
        ring.setStrokeWidth(density);
        ring.setColor(Ui.HAIRLINE);
        canvas.drawArc(box, START, SWEEP, false, ring);
        ring.setStrokeWidth(1.5f * density);
        ring.setColor(Ui.TEXT_3);
        float a = START + (sweepAngle / 360f) * (SWEEP - 28f);
        canvas.drawArc(box, a, 28f, false, ring);
    }

    /** Inner ring as a power meter. */
    private void drawPower(Canvas canvas, float cx, float cy, float radius) {
        float r = radius - 50 * density;
        if (r <= 0) return;
        box.set(cx - r, cy - r, cx + r, cy + r);
        ring.setStrokeWidth(3 * density);
        ring.setColor(Ui.HAIRLINE);
        canvas.drawArc(box, START, SWEEP, false, ring);
        float f = Math.min(1f, Math.abs(powerW) / peakW);
        if (f > 0.005f) {
            ring.setColor(powerW < 0 ? Ui.OK : Ui.TEXT);
            canvas.drawArc(box, START, SWEEP * f, false, ring);
        }
        caption.setColor(powerW < -50f ? Ui.OK : Ui.TEXT_2);
        canvas.drawText(String.format(java.util.Locale.US, "%.1f KW", Math.abs(powerW) / 1000f),
                cx, cy - radius * 0.40f, caption);
        caption.setColor(Ui.TEXT_2);
    }

    private void drawReadout(Canvas canvas, float cx, float cy, float radius) {
        number.setTextSize(radius * 0.74f);
        Paint.FontMetrics fm = number.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f - radius * 0.04f;
        if (hasFix) {
            number.setColor(Ui.TEXT);
            canvas.drawText(String.valueOf(Math.round(toUnits(shownMps))), cx, baseline, number);
        } else {
            // No fix: two dashes rather than a zero that looks like a reading.
            number.setColor(Ui.TEXT_3);
            canvas.drawText("--", cx, baseline, number);
        }
        canvas.drawText(metric ? "KM/H" : "MPH", cx, cy + radius * 0.62f, caption);
    }
}
