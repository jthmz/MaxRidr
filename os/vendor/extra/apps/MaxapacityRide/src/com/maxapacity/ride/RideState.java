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
    private float topSpeedMps;
    private float speedMps;
    private long lastFixElapsedMs;
    private Location lastTrackFix;

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
        topSpeedMps = 0;
        lastTrackFix = null;
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
        lastFixElapsedMs = SystemClock.elapsedRealtime();
        float s = l.hasSpeed() ? l.getSpeed() : 0f;
        speedMps = s < MIN_SPEED_MPS ? 0f : s;

        boolean accurate = !l.hasAccuracy() || l.getAccuracy() <= MAX_ACCURACY_M;
        if (recording && accurate) {
            if (speedMps > topSpeedMps) topSpeedMps = speedMps;
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

    float avgSpeedMps() {
        long ms = durationMs();
        return ms < 1000 ? 0f : (float) (distanceM / (ms / 1000.0));
    }

    long startWallMs() {
        return startWallMs;
    }

    List<Point> track() {
        return track;
    }
}
