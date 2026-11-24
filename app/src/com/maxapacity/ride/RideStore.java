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
    /** A ride's "deck" mark once the Mac has it. */
    static final String ON_MAC = "sent";

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
                    .put("leanLeft", Math.round(state.maxLeanLeftDeg()))
                    .put("leanRight", Math.round(state.maxLeanRightDeg()))
                    .put("strava", strava ? STRAVA_PENDING : STRAVA_NONE);
            JSONArray rides = load(c);
            rides.put(ride);
            save(c, rides);
        } catch (JSONException e) {
            // The GPX is saved; only the history entry is missing.
        }
        if (strava) Strava.uploadPending(c);
        Deck.get(c).rideSaved();

        return "Ride saved: " + Units.distance(c, state.distanceM()) + " "
                + Units.distanceLabel(c) + " in " + Units.duration(state.movingMs())
                + (strava ? ". Sending to Strava" : "");
    }

    static void setStravaStatus(Context c, String file, String status) {
        mark(c, file, "strava", status);
    }

    /** The Mac has this ride: it is not offered to the Deck again. */
    static void setOnMac(Context c, String file) {
        mark(c, file, "deck", ON_MAC);
    }

    private static void mark(Context c, String file, String key, String value) {
        JSONArray rides = load(c);
        for (int i = 0; i < rides.length(); i++) {
            JSONObject r = rides.optJSONObject(i);
            if (r != null && file.equals(r.optString("file"))) {
                try {
                    r.put(key, value);
                } catch (JSONException e) {
                    return;
                }
                save(c, rides);
                return;
            }
        }
    }

    /**
     * Takes a ride out of the history. The GPX file is only set aside, so
     * {@link #restore} can put the ride back; {@link #purgeRemoved} deletes
     * set-aside files for good.
     *
     * @return the removed entry and its position, or null if it was not found
     */
    static Removed remove(Context c, String file) {
        JSONArray rides = load(c);
        for (int i = 0; i < rides.length(); i++) {
            JSONObject r = rides.optJSONObject(i);
            if (r == null || !file.equals(r.optString("file"))) continue;
            rides.remove(i);
            save(c, rides);
            new File(dir(c), file).renameTo(new File(dir(c), file + REMOVED_SUFFIX));
            return new Removed(r, i);
        }
        return null;
    }

    static void restore(Context c, Removed removed) {
        String file = removed.ride.optString("file");
        new File(dir(c), file + REMOVED_SUFFIX).renameTo(new File(dir(c), file));
        JSONArray rides = load(c);
        JSONArray out = new JSONArray();
        int at = Math.min(removed.index, rides.length());
        for (int i = 0; i <= rides.length(); i++) {
            if (i == at) out.put(removed.ride);
            if (i < rides.length()) out.put(rides.opt(i));
        }
        save(c, out);
    }

    /** Deletes the files of rides removed in an earlier session. */
    static void purgeRemoved(Context c) {
        File[] files = dir(c).listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().endsWith(REMOVED_SUFFIX)) f.delete();
        }
    }

    static final class Removed {
        final JSONObject ride;
        final int index;

        Removed(JSONObject ride, int index) {
            this.ride = ride;
            this.index = index;
        }
    }

    private static final String REMOVED_SUFFIX = ".removed";

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
