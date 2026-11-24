package com.maxapacity.ride;

/**
 * Decides when a ride has probably ended in a crash: a hard hit while moving,
 * followed by the bike not moving again. A hit alone is not enough, because a
 * bar-mounted phone takes hard knocks on any trail; what marks a crash is that
 * the rider does not ride on.
 * Plain arithmetic: no Android classes, runs in any test.
 */
final class CrashDetector {

    /** Acceleration beyond gravity that counts as a hit, in m/s^2 (about 4 g). */
    static final float IMPACT = 40f;
    /** Only a hit at riding speed counts (about 10 mph). */
    static final float MIN_SPEED_MPS = 4.5f;
    /** How far back "at riding speed" looks; GPS speed lags a sudden stop. */
    static final long SPEED_MEMORY_MS = 5000;
    /** After a hit, speed readings this soon still show the old motion. */
    static final long SETTLE_MS = 3000;
    /** Riding at this speed after a hit means it was only a bump. */
    static final float RIDING_AGAIN_MPS = 2.5f;
    /** Still down this long after the hit: raise the alarm. */
    static final long CONFIRM_MS = 12000;

    private float recentSpeed;
    private long recentSpeedMs;
    private boolean pending;
    private long impactMs;

    void reset() {
        pending = false;
        recentSpeed = 0f;
    }

    void speed(float mps, long nowMs) {
        if (mps >= recentSpeed || nowMs - recentSpeedMs > SPEED_MEMORY_MS) {
            recentSpeed = mps;
            recentSpeedMs = nowMs;
        }
        if (pending && nowMs - impactMs >= SETTLE_MS && mps >= RIDING_AGAIN_MPS) {
            pending = false;
        }
    }

    /** @param linear acceleration with gravity removed, in m/s^2 */
    void accel(float linear, long nowMs) {
        if (pending || linear < IMPACT) return;
        if (nowMs - recentSpeedMs > SPEED_MEMORY_MS || recentSpeed < MIN_SPEED_MPS) return;
        pending = true;
        impactMs = nowMs;
    }

    /** True once, when a hit has been followed by long enough without riding on. */
    boolean confirmed(long nowMs) {
        if (!pending || nowMs - impactMs < CONFIRM_MS) return false;
        pending = false;
        recentSpeed = 0f;
        return true;
    }

    boolean isPending() {
        return pending;
    }
}
