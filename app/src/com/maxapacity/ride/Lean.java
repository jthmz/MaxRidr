package com.maxapacity.ride;

/**
 * Lean angle from GPS alone. A bike holding a turn leans by the angle whose
 * tangent is (speed x turn rate) / gravity, so speed and the change in
 * heading between fixes are enough; how the phone is mounted does not matter.
 * It is the lean the turn needed, so body position and bumps are not in it.
 * Plain arithmetic: no Android classes, runs in any test.
 */
final class Lean {

    /** Below this GPS heading is too jumpy to trust (about 11 mph). */
    static final float MIN_SPEED_MPS = 5f;
    /** Fixes further apart than this are not one continuous turn. */
    static final long MAX_GAP_MS = 3000;
    /** No bike leans further; anything above is a GPS glitch. */
    static final float MAX_DEG = 60f;

    private static final double G = 9.81;

    private boolean seeded;
    private float lastBearing;
    private long lastMs;
    private float smoothed;
    private float maxLeft;
    private float maxRight;

    void reset() {
        seeded = false;
        smoothed = 0f;
        maxLeft = 0f;
        maxRight = 0f;
    }

    /**
     * @param bearingDeg direction of travel, degrees clockwise from north
     * @return the current lean in degrees, positive to the right
     */
    float add(float speedMps, float bearingDeg, long timeMs) {
        if (speedMps < MIN_SPEED_MPS) {
            seeded = false;
            smoothed = 0f;
            return 0f;
        }
        long dt = timeMs - lastMs;
        boolean usable = seeded && dt > 0 && dt <= MAX_GAP_MS;
        float turned = bearingDeg - lastBearing;
        seeded = true;
        lastBearing = bearingDeg;
        lastMs = timeMs;
        if (!usable) return smoothed;

        // Shortest way round: 350 to 10 degrees is +20, not -340.
        while (turned > 180f) turned -= 360f;
        while (turned < -180f) turned += 360f;
        double rate = Math.toRadians(turned) / (dt / 1000.0);
        float lean = (float) Math.toDegrees(Math.atan(speedMps * rate / G));
        if (Math.abs(lean) > MAX_DEG) return smoothed;

        // One fix alone is noisy; a real lean lasts for several.
        smoothed = 0.5f * smoothed + 0.5f * lean;
        if (smoothed > maxRight) maxRight = smoothed;
        if (-smoothed > maxLeft) maxLeft = -smoothed;
        return smoothed;
    }

    float maxLeftDeg() {
        return maxLeft;
    }

    float maxRightDeg() {
        return maxRight;
    }
}
