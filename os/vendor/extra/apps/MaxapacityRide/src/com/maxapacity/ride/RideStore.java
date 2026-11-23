package com.maxapacity.ride;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Saved rides: one GPX file per ride plus an index of summaries
 * (rides/index.json). Main thread only.
 */
final class RideStore {

    static final String STRAVA_NONE = "";
    static final String STRAVA_PENDING = "pending";
    static final String STRAVA_UPLOADED = "uploaded";
    static final String STRAVA_FAILED = "failed";

    private RideStore() {
    }

    static File dir(Context c) {
        return new File(c.getExternalFilesDir(null), "rides");
    }

    private static File indexFile(Context c) {
        return new File(dir(c), "index.json");
    }

    /** Ride summaries, oldest first. Empty when nothing is saved or unreadable. */
    static JSONArray load(Context c) {
        File f = indexFile(c);
        if (!f.isFile()) return new JSONArray();
        try {
            return new JSONArray(new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONArray();
        }
    }

    private static void save(Context c, JSONArray rides) {
        File f = indexFile(c);
        File tmp = new File(f.getPath() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(rides.toString().getBytes(StandardCharsets.UTF_8));
            }
            // Write-then-rename so a crash cannot leave a half-written index.
            if (!tmp.renameTo(f)) throw new IOException("rename failed");
        } catch (IOException e) {
            tmp.delete();
        }
    }

    /**
     * Ends the current ride, saves it and queues the Strava upload.
     *
     * @return a one-line result to show the rider
     */
    static String finish(Context c) {
        RideState state = RideState.get();
        if (!state.isRecording()) return "No ride in progress";
        state.stop();

        if (state.track().size() < 2) {
            return "Ride ended. No GPS track was recorded";
        }
        File gpx;
        try {
            gpx = GpxWriter.write(dir(c), state.startWallMs(), state.track());
        } catch (IOException e) {
            return "Ride ended, but the GPX file could not be saved";
        }

        boolean strava = Strava.isConnected(c);
        try {
            JSONObject ride = new JSONObject()
                    .put("start", state.startWallMs())
                    .put("file", gpx.getName())
                    .put("distanceM", state.distanceM())
                    .put("movingMs", state.movingMs())
                    .put("elapsedMs", state.durationMs())
                    .put("topMps", state.topSpeedMps())
                    .put("climbM", state.climbM())
                    .put("stops", state.stops())
                    .put("strava", strava ? STRAVA_PENDING : STRAVA_NONE);
            JSONArray rides = load(c);
            rides.put(ride);
            save(c, rides);
        } catch (JSONException e) {
            // The GPX is saved; only the history entry is missing.
        }
        if (strava) Strava.uploadPending(c);

        return "Ride saved: " + Units.distance(c, state.distanceM()) + " "
                + Units.distanceLabel(c) + " in " + Units.duration(state.movingMs())
                + (strava ? ". Sending to Strava" : "");
    }

    static void setStravaStatus(Context c, String file, String status) {
        JSONArray rides = load(c);
        for (int i = 0; i < rides.length(); i++) {
            JSONObject r = rides.optJSONObject(i);
            if (r != null && file.equals(r.optString("file"))) {
                try {
                    r.put("strava", status);
                } catch (JSONException e) {
                    return;
                }
                save(c, rides);
                return;
            }
        }
    }

    /** "Nov 23, 08:14  12.40 mi  0:42:10" for the history list. */
    static String describe(Context c, JSONObject r) {
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());
        String line = fmt.format(new Date(r.optLong("start")))
                + "   " + Units.distance(c, r.optDouble("distanceM")) + " "
                + Units.distanceLabel(c)
                + "   " + Units.duration(r.optLong("movingMs"));
        String strava = r.optString("strava");
        if (STRAVA_UPLOADED.equals(strava)) line += "\nOn Strava";
        else if (STRAVA_PENDING.equals(strava)) line += "\nWaiting to send to Strava";
        else if (STRAVA_FAILED.equals(strava)) line += "\nStrava rejected this ride";
        return line;
    }
}
