package com.maxapacity.ride;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.InsertRecordsResponse;
import android.health.connect.datatypes.DistanceRecord;
import android.health.connect.datatypes.ExerciseRoute;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.ExerciseSessionType;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.units.Length;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.OutcomeReceiver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Health Connect: Android's shared store of health and fitness records. With
 * the rider's leave, each saved ride is written to it as a cycling workout
 * with its route and distance, and any app the rider has let read workouts
 * there (Oura, Google Health, Samsung Health and others) picks it up. There
 * is no account and no key: the phone asks once, on its own screen.
 *
 * The app only writes. It never reads anything from Health Connect.
 *
 * Health Connect is part of Android from version 14. On older phones it is a
 * separate app that needs a library to reach, and this app uses none, so
 * there the setting is simply not offered.
 */
final class Health {

    /** The three things the app asks to write. Only the first is essential. */
    static final String WRITE_EXERCISE = "android.permission.health.WRITE_EXERCISE";
    static final String WRITE_ROUTE = "android.permission.health.WRITE_EXERCISE_ROUTE";
    static final String WRITE_DISTANCE = "android.permission.health.WRITE_DISTANCE";
    static final String[] PERMISSIONS = {WRITE_EXERCISE, WRITE_ROUTE, WRITE_DISTANCE};

    /** A ride's "health" mark once Health Connect has it. */
    static final String SENT = "sent";
    /** The mark for a ride Health Connect will never take, so it is not offered again. */
    static final String REFUSED = "refused";

    interface Done {
        void onResult(boolean ok);
    }

    /** Where records go. The tests put their own here. */
    interface Store {
        /** True when this phone has Health Connect built in. */
        boolean present(Context c);

        /** Writes the records; {@code done} is called on the main thread. */
        void insert(Context c, List<Record> records, Done done);
    }

    /** Health Connect itself. */
    static final Store SYSTEM = new SystemStore();
    static Store store = SYSTEM;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Rides on their way, so asking twice writes once. Main thread. */
    private static final Set<String> busy = new HashSet<>();
    private static final Pattern TRKPT = Pattern.compile(
            "<trkpt lat=\"(-?[0-9.]+)\" lon=\"(-?[0-9.]+)\">(?:<ele>(-?[0-9.]+)</ele>)?"
                    + "<time>([^<]+)</time>");

    private Health() {
    }

    static boolean available(Context c) {
        return Build.VERSION.SDK_INT >= 34 && store.present(c);
    }

    /** True when the rider has turned sharing on and the phone still allows it. */
    static boolean isOn(Context c) {
        return Units.prefs(c).getBoolean("health", false) && available(c) && allowed(c);
    }

    /** True when the phone lets the app write workouts, whatever the setting says. */
    static boolean allowed(Context c) {
        return granted(c, WRITE_EXERCISE);
    }

    private static boolean granted(Context c, String permission) {
        return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    static void setOn(Context c, boolean on) {
        Units.prefs(c).edit().putBoolean("health", on).apply();
        if (on) sync(c);
    }

    /** The phone's own screen for what this app may write to Health Connect. */
    static Intent manageIntent(Context c) {
        return new Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS")
                .putExtra(Intent.EXTRA_PACKAGE_NAME, c.getPackageName());
    }

    /**
     * Writes every saved ride Health Connect does not have yet. Safe to call
     * often, and a ride is written once: each carries its file name, which
     * Health Connect uses to tell a repeat from a new workout. Main thread.
     */
    static void sync(Context context) {
        final Context c = context.getApplicationContext();
        if (!isOn(c)) return;
        final boolean route = granted(c, WRITE_ROUTE);
        final boolean distance = granted(c, WRITE_DISTANCE);
        JSONArray rides = RideStore.load(c);
        for (int i = 0; i < rides.length(); i++) {
            final JSONObject r = rides.optJSONObject(i);
            if (r == null || !r.optString("health").isEmpty()) continue;
            final String file = r.optString("file");
            if (file.isEmpty() || !busy.add(file)) continue;
            IO.execute(() -> {
                List<Record> built;
                try {
                    List<double[]> track = route
                            ? readTrack(new File(RideStore.dir(c), file)) : new ArrayList<>();
                    built = records(r, track, distance);
                } catch (RuntimeException e) {
                    // Health Connect checks every value as a record is made.
                    built = null;
                }
                final List<Record> records = built;
                MAIN.post(() -> {
                    if (records == null) {
                        busy.remove(file);
                        RideStore.setHealth(c, file, REFUSED);
                        return;
                    }
                    store.insert(c, records, ok -> {
                        busy.remove(file);
                        // Left unmarked when it fails, to go again next time.
                        if (ok) RideStore.setHealth(c, file, SENT);
                    });
                });
            });
        }
    }

    /**
     * One ride as Health Connect records: the workout, with its route when a
     * track is given, and its distance.
     *
     * @param track points as {latitude, longitude, altitude or NaN, time in ms}
     * @throws IllegalArgumentException if Health Connect would not take it
     */
    static List<Record> records(JSONObject ride, List<double[]> track, boolean withDistance) {
        String file = ride.optString("file");
        long startMs = ride.optLong("start");
        long endMs = startMs + Math.max(1000L, ride.optLong("elapsedMs"));

        // A route must run forwards in time and sit inside its workout.
        List<ExerciseRoute.Location> route = new ArrayList<>();
        long last = Long.MIN_VALUE;
        for (double[] p : track) {
            long t = (long) p[3];
            if (t <= last) continue;
            last = t;
            ExerciseRoute.Location.Builder at = new ExerciseRoute.Location.Builder(
                    Instant.ofEpochMilli(t), p[0], p[1]);
            if (!Double.isNaN(p[2])) at.setAltitude(Length.fromMeters(p[2]));
            route.add(at.build());
        }
        // The track's clock is the GPS's and the ride's is the phone's, and
        // the two can be seconds apart: stretch the workout to hold its route.
        if (!route.isEmpty()) {
            startMs = Math.min(startMs, (long) track.get(0)[3]);
            endMs = Math.max(endMs, last);
        }
        Instant start = Instant.ofEpochMilli(startMs);
        Instant end = Instant.ofEpochMilli(endMs);

        List<Record> out = new ArrayList<>();
        ExerciseSessionRecord.Builder session = new ExerciseSessionRecord.Builder(
                metadata(file), start, end, ExerciseSessionType.EXERCISE_SESSION_TYPE_BIKING)
                .setTitle("MAXAPACITY ride");
        if (route.size() >= 2) session.setRoute(new ExerciseRoute(route));
        out.add(session.build());

        double meters = ride.optDouble("distanceM", 0);
        if (withDistance && meters > 0) {
            out.add(new DistanceRecord.Builder(metadata(file), start, end,
                    Length.fromMeters(meters)).build());
        }
        return out;
    }

    private static Metadata metadata(String file) {
        return new Metadata.Builder()
                .setClientRecordId(file)
                .setClientRecordVersion(1)
                .setRecordingMethod(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED)
                .build();
    }

    /** A saved GPX as {latitude, longitude, altitude or NaN, time in ms}; empty if unreadable. */
    static List<double[]> readTrack(File gpx) {
        List<double[]> points = new ArrayList<>();
        try {
            String text = new String(Files.readAllBytes(gpx.toPath()), StandardCharsets.UTF_8);
            Matcher m = TRKPT.matcher(text);
            while (m.find()) {
                points.add(new double[] {
                        Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                        m.group(3) == null ? Double.NaN : Double.parseDouble(m.group(3)),
                        Instant.parse(m.group(4)).toEpochMilli()});
            }
        } catch (IOException | NumberFormatException | DateTimeParseException e) {
            points.clear();
        }
        return points;
    }

    /** Health Connect itself, as built into Android 14 and later. */
    private static final class SystemStore implements Store {

        @Override
        public boolean present(Context c) {
            try {
                return Build.VERSION.SDK_INT >= 34
                        && c.getSystemService(HealthConnectManager.class) != null;
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public void insert(Context c, List<Record> records, final Done done) {
            try {
                c.getSystemService(HealthConnectManager.class).insertRecords(records,
                        c.getMainExecutor(),
                        new OutcomeReceiver<InsertRecordsResponse, HealthConnectException>() {
                            @Override
                            public void onResult(InsertRecordsResponse response) {
                                done.onResult(true);
                            }

                            @Override
                            public void onError(HealthConnectException e) {
                                done.onResult(false);
                            }
                        });
            } catch (RuntimeException e) {
                done.onResult(false);
            }
        }
    }
}
