package com.maxapacity.ride;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

import java.util.Random;

/**
 * Animated backdrop for the dashboard, drawn behind the controls:
 *
 *  - code rain in voltage yellow across the whole screen
 *  - a light-cycle floor grid under the speedometer that scrolls faster the
 *    faster the bike is going
 *  - a speed arc around the speed number
 *  - a lightning bolt spinning behind the number, also sped up by riding
 *
 * Purely decorative: nothing here carries information the numbers do not.
 * Stops animating when the launcher is not on screen, and holds a single
 * still frame when the system "remove animations" setting is on.
 */
final class FxView extends View {

    private static final int VOLTAGE = 0xFFFFEB1A;
    private static final int CYAN = 0xFF1FD5F9;
    private static final int TRACK = 0xFF2E2E36;

    /** Speed at which the arc is full and the effects run flat out (about 60 mph). */
    private static final float FULL_SPEED_MPS = 27f;

    private static final String GLYPHS =
            "0123456789ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ";

    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bolt = new Path();
    private final Path boltDrawn = new Path();
    private final android.graphics.Matrix boltMatrix = new android.graphics.Matrix();
    private final RectF focus = new RectF();
    private final RectF arcBox = new RectF();
    private final Random random = new Random();
    private final float density;

    // Rain: one falling stream per column.
    private float cell;
    private int columns;
    private int rows;
    private float[] head;
    private float[] fall;
    private int[] length;
    private char[][] glyph;

    private boolean running;
    private long lastFrameMs;
    private float targetSpeed;
    private float shownSpeed;
    private float gridPhase;
    private float boltAngle;

    FxView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;

        glyphPaint.setTypeface(Typeface.MONOSPACE);
        glyphPaint.setTextAlign(Paint.Align.CENTER);
        glyphPaint.setTextSize(13 * density);
        cell = 16 * density;

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(density);
        linePaint.setColor(CYAN);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        boltPaint.setStyle(Paint.Style.STROKE);
        boltPaint.setStrokeJoin(Paint.Join.ROUND);
        boltPaint.setStrokeWidth(2 * density);
        boltPaint.setColor(VOLTAGE);

        // Same outline as the app icon, on a 24-unit box centred on the origin.
        bolt.moveTo(1, -10);
        bolt.lineTo(-9, 2);
        bolt.lineTo(0, 2);
        bolt.lineTo(-1, 10);
        bolt.lineTo(9, -2);
        bolt.lineTo(0, -2);
        bolt.close();
    }

    /** Where the speedometer sits, in this view's coordinates. */
    void setFocus(float left, float top, float right, float bottom) {
        focus.set(left, top, right, bottom);
        invalidate();
    }

    void setSpeed(float mps) {
        targetSpeed = Math.max(0f, mps);
    }

    void setRunning(boolean run) {
        if (run == running) return;
        running = run;
        lastFrameMs = 0;
        invalidate();
    }

    private boolean animationsAllowed() {
        return Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        columns = Math.max(1, (int) (w / cell));
        rows = Math.max(1, (int) (h / cell)) + 1;
        head = new float[columns];
        fall = new float[columns];
        length = new int[columns];
        glyph = new char[columns][rows];
        for (int c = 0; c < columns; c++) {
            resetColumn(c);
            head[c] = random.nextInt(rows * 2) - rows; // start scattered
            for (int r = 0; r < rows; r++) glyph[c][r] = randomGlyph();
        }
    }

    private void resetColumn(int c) {
        head[c] = -random.nextInt(rows);
        fall[c] = 5f + random.nextFloat() * 9f; // rows per second
        length[c] = 6 + random.nextInt(14);
    }

    private char randomGlyph() {
        return GLYPHS.charAt(random.nextInt(GLYPHS.length()));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (head == null) return;

        boolean animate = running && animationsAllowed();
        long now = SystemClock.uptimeMillis();
        float dt = lastFrameMs == 0 ? 0f : Math.min((now - lastFrameMs) / 1000f, 0.1f);
        lastFrameMs = now;
        if (animate) step(dt);

        drawRain(canvas);
        if (!focus.isEmpty()) {
            drawGrid(canvas);
            drawBolt(canvas);
            drawArc(canvas);
        }

        if (animate) postInvalidateOnAnimation();
    }

    private void step(float dt) {
        // Ease the displayed speed so the arc and grid glide rather than jump
        // between one-second GPS fixes.
        shownSpeed += (targetSpeed - shownSpeed) * Math.min(1f, dt * 3f);
        float pace = Math.min(1f, shownSpeed / FULL_SPEED_MPS);

        gridPhase = (gridPhase + dt * (0.25f + pace * 3f)) % 1f;
        boltAngle = (boltAngle + dt * (90f + pace * 630f)) % 360f;

        for (int c = 0; c < columns; c++) {
            head[c] += fall[c] * dt * (1f + pace);
            if (head[c] - length[c] > rows) resetColumn(c);
            if (random.nextInt(12) == 0) glyph[c][random.nextInt(rows)] = randomGlyph();
        }
    }

    private void drawRain(Canvas canvas) {
        char[] one = new char[1];
        for (int c = 0; c < columns; c++) {
            float x = c * cell + cell / 2f;
            int headRow = (int) head[c];
            for (int i = 0; i < length[c]; i++) {
                int r = headRow - i;
                if (r < 0 || r >= rows) continue;
                // Bright leading glyph, tail fading out behind it. Kept faint
                // so labels drawn on top stay readable.
                int alpha = i == 0 ? 150 : (int) (60f * (1f - (float) i / length[c]));
                glyphPaint.setColor(i == 0 ? 0xFFFFFBD0 : VOLTAGE);
                glyphPaint.setAlpha(alpha);
                one[0] = glyph[c][r];
                canvas.drawText(one, 0, 1, x, (r + 1) * cell, glyphPaint);
            }
        }
    }

    /** Perspective floor receding to a horizon across the middle of the speedometer. */
    private void drawGrid(Canvas canvas) {
        float cx = focus.centerX();
        float horizon = focus.top + focus.height() * 0.62f;
        float depth = focus.bottom - horizon;
        if (depth <= 0) return;

        canvas.save();
        canvas.clipRect(focus.left, horizon, focus.right, focus.bottom);

        // Lines running away from the viewer, fanning out from the vanishing point.
        linePaint.setAlpha(70);
        float spread = focus.width() * 0.22f;
        for (int i = -8; i <= 8; i++) {
            canvas.drawLine(cx, horizon, cx + i * spread * 4f, focus.bottom + depth * 3f,
                    linePaint);
        }
        // Cross lines: evenly spaced in depth, so they bunch up toward the
        // horizon; the phase slides them toward the viewer.
        for (int i = 0; i < 9; i++) {
            float z = 1f / (i + 1 - gridPhase); // 1 at the near edge, small far away
            if (z > 1f) continue;
            float y = horizon + depth * z;
            linePaint.setAlpha((int) (30 + 110 * z));
            canvas.drawLine(focus.left, y, focus.right, y, linePaint);
        }
        canvas.restore();
    }

    /** Bolt spinning about its vertical axis, seen edge-on twice per turn. */
    private void drawBolt(Canvas canvas) {
        float size = Math.min(focus.width(), focus.height()) * 0.036f;
        float squash = (float) Math.cos(Math.toRadians(boltAngle));
        boltDrawn.rewind();
        android.graphics.Matrix m = boltMatrix;
        m.setScale(size * squash, size);
        m.postTranslate(focus.centerX(), focus.centerY());
        bolt.transform(m, boltDrawn);
        boltPaint.setAlpha(70);
        boltPaint.setShadowLayer(8 * density, 0, 0, VOLTAGE);
        canvas.drawPath(boltDrawn, boltPaint);
    }

    /** Three-quarter gauge arc that fills with speed. */
    private void drawArc(Canvas canvas) {
        float radius = Math.min(focus.width(), focus.height()) * 0.47f;
        arcBox.set(focus.centerX() - radius, focus.centerY() - radius,
                focus.centerX() + radius, focus.centerY() + radius);

        arcPaint.clearShadowLayer();
        arcPaint.setStrokeWidth(2 * density);
        arcPaint.setColor(TRACK);
        canvas.drawArc(arcBox, 135f, 270f, false, arcPaint);

        float fraction = Math.min(1f, shownSpeed / FULL_SPEED_MPS);
        if (fraction <= 0.004f) return;
        arcPaint.setStrokeWidth(4 * density);
        arcPaint.setColor(VOLTAGE);
        arcPaint.setShadowLayer(10 * density, 0, 0, VOLTAGE);
        canvas.drawArc(arcBox, 135f, 270f * fraction, false, arcPaint);
    }
}
