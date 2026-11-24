package com.maxapacity.ride;

import android.location.Location;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide ride state. Fed by RideService while a ride is recording and by
 * RideActivity's own GPS listener otherwise, so the speedometer is always live.
 * All access happens on the main thread.
 */
final class RideState {

    interface Listener {
        void onRideStateChanged();
    }

    static final class Point {
        final double lat;
        final double lon;
        final double ele;
        final boolean hasEle;
        final long timeMs;

        Point(Location l) {
            lat = l.getLatitude();
            lon = l.getLongitude();
            hasEle = l.hasAltitude();
            ele = l.getAltitude();
            timeMs = l.getTime();
        }
    }

    /** Fixes worse than this are ignored for distance and track. */
    private static final float MAX_ACCURACY_M = 25f;
    /** Below this the bike is treated as stopped (GPS jitter). */
    private static final float MIN_SPEED_MPS = 0.5f;
    /** A fix older than this means we no longer have a usable signal. */
    private static final long FIX_STALE_MS = 5000;
    /** Stationary this long shows as paused. */
    private static final long PAUSE_AFTER_MS = 5000;
    /** Stationary this long counts as one stop. */
    private static final long STOP_MIN_MS = 10000;

    private static final RideState INSTANCE = new RideState();

    static RideState get() {
        return INSTANCE;
    }

    private final List<Listener> listeners = new ArrayList<>();
    private final List<Point> track = new ArrayList<>();

    private boolean recording;
    private long startElapsedMs;
    private long startWallMs;
    private long endElapsedMs;
    private double distanceM;
    private long movingMs;
    private long lastMovingElapsedMs;
    private boolean stopCounted;
    private int stops;
    private double climbM;
    private double smoothedAlt;
    private double climbRefAlt;
    private boolean altSeeded;
    private float topSpeedMps;
    private float speedMps;
    private long lastFixElapsedMs;
    private Location lastTrackFix;
    private Location lastFix;
    private final Lean lean = new Lean();

    private RideState() {
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        for (int i = listeners.size() - 1; i >= 0; i--) {
            listeners.get(i).onRideStateChanged();
        }
    }

    void start() {
        recording = true;
        startElapsedMs = SystemClock.elapsedRealtime();
        startWallMs = System.currentTimeMillis();
        endElapsedMs = 0;
        distanceM = 0;
        movingMs = 0;
        lastMovingElapsedMs = startElapsedMs;
        stopCounted = false;
        stops = 0;
        climbM = 0;
        altSeeded = false;
        topSpeedMps = 0;
        lastTrackFix = null;
        lean.reset();
        track.clear();
        notifyListeners();
    }

    void stop() {
        if (!recording) return;
        recording = false;
        endElapsedMs = SystemClock.elapsedRealtime();
        notifyListeners();
    }

    void onLocation(Location l) {
        long now = SystemClock.elapsedRealtime();
        // Time since the previous fix, capped so a GPS dropout is not counted
        // as riding time.
        long dt = lastFixElapsedMs == 0 ? 0
                : Math.max(0, Math.min(now - lastFixElapsedMs, 5000));
        lastFixElapsedMs = now;
        float s = l.hasSpeed() ? l.getSpeed() : 0f;
        speedMps = s < MIN_SPEED_MPS ? 0f : s;

        if (recording) {
            if (speedMps > 0f) {
                movingMs += dt;
                lastMovingElapsedMs = now;
                stopCounted = false;
            } else if (!stopCounted && distanceM > 0
                    && now - lastMovingElapsedMs >= STOP_MIN_MS) {
                stops++;
                stopCounted = true;
            }
        }

        boolean accurate = !l.hasAccuracy() || l.getAccuracy() <= MAX_ACCURACY_M;
        if (accurate) lastFix = l;
        if (recording && accurate) {
            if (speedMps > topSpeedMps) topSpeedMps = speedMps;
            if (l.hasBearing()) {
                lean.add(speedMps, l.getBearing(), l.getElapsedRealtimeNanos() / 1000000L);
            }
            if (l.hasAltitude()) addAltitude(l.getAltitude());
            if (lastTrackFix == null) {
                lastTrackFix = l;
                track.add(new Point(l));
            } else {
                float d = lastTrackFix.distanceTo(l);
                // Only advance when we moved further than the fix uncertainty,
                // otherwise a parked bike slowly accumulates distance.
                float floor = Math.max(3f, l.hasAccuracy() ? l.getAccuracy() / 2f : 3f);
                if (d >= floor) {
                    distanceM += d;
                    lastTrackFix = l;
                    track.add(new Point(l));
                }
            }
        }
        notifyListeners();
    }

    /** Climb with smoothing and a 3 m dead band, so GPS altitude noise is not counted. */
    private void addAltitude(double alt) {
        if (!altSeeded) {
            smoothedAlt = alt;
            climbRefAlt = alt;
            altSeeded = true;
            return;
        }
        smoothedAlt = 0.8 * smoothedAlt + 0.2 * alt;
        if (smoothedAlt > climbRefAlt + 3) {
            climbM += smoothedAlt - climbRefAlt;
            climbRefAlt = smoothedAlt;
        } else if (smoothedAlt < climbRefAlt - 3) {
            climbRefAlt = smoothedAlt;
        }
    }

    boolean isRecording() {
        return recording;
    }

    boolean hasFix() {
        return lastFixElapsedMs != 0
                && SystemClock.elapsedRealtime() - lastFixElapsedMs < FIX_STALE_MS;
    }

    float speedMps() {
        return hasFix() ? speedMps : 0f;
    }

    float topSpeedMps() {
        return topSpeedMps;
    }

    double distanceM() {
        return distanceM;
    }

    /** Elapsed ride time; frozen at the final value once the ride has ended. */
    long durationMs() {
        if (startElapsedMs == 0) return 0;
        long end = recording ? SystemClock.elapsedRealtime() : endElapsedMs;
        return Math.max(0, end - startElapsedMs);
    }

    /** Time actually spent moving; stops and GPS dropouts are excluded. */
    long movingMs() {
        return movingMs;
    }

    /** How long the bike has been stationary (or without GPS) during a ride. */
    long idleMs() {
        if (!recording) return 0;
        return SystemClock.elapsedRealtime() - lastMovingElapsedMs;
    }

    /** Recording but stopped: the moving clock is paused. */
    boolean isPaused() {
        return recording && idleMs() >= PAUSE_AFTER_MS;
    }

    int stops() {
        return stops;
    }

    double climbM() {
        return climbM;
    }

    /** Average over moving time, the way ride apps report it. */
    float avgSpeedMps() {
        return movingMs < 1000 ? 0f : (float) (distanceM / (movingMs / 1000.0));
    }

    /** Furthest lean to the left this ride, in degrees. */
    float maxLeanLeftDeg() {
        return lean.maxLeftDeg();
    }

    float maxLeanRightDeg() {
        return lean.maxRightDeg();
    }

    /** The last trustworthy position, or null before the first fix. */
    Location lastLocation() {
        return lastFix;
    }

    long startWallMs() {
        return startWallMs;
    }

    List<Point> track() {
        return track;
    }
}
