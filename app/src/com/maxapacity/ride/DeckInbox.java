package com.maxapacity.ride;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** The receiver already stores completed files atomically. Rebuild the inbox from those
 * files so older installations and a process restart use the same source of truth. */
final class DeckInbox {
    static final class Ride {
        final JSONObject stats;
        final File route;
        final String source;
        Ride(JSONObject stats, File route, String source) {
            this.stats = stats;
            this.route = route;
            this.source = source;
        }
    }

    static List<Ride> load(Context c) {
        List<Ride> rides = new ArrayList<>();
        File root = c.getExternalFilesDir("Received rides");
        File[] riders = root == null ? null : root.listFiles(File::isDirectory);
        if (riders == null) return rides;
        SharedPreferences prefs = c.getSharedPreferences("home-deck", 0);
        for (File rider : riders) {
            Map<String,File> files = new HashMap<>();
            File[] sizes = rider.listFiles(File::isDirectory);
            if (sizes == null) continue;
            for (File size : sizes) {
                File[] entries = size.listFiles();
                if (entries == null) continue;
                for (File f : entries) {
                    if (!f.isFile() || !size.getName().equals(Long.toString(f.length()))) continue;
                    File old = files.get(f.getName());
                    if (old == null || old.lastModified() < f.lastModified()) files.put(f.getName(), f);
                }
            }
            for (File summary : files.values()) {
                String name = summary.getName();
                if (!name.endsWith(".json") || summary.length() > 65536) continue;
                File route = files.get(name.substring(0, name.length() - 5) + ".gpx");
                if (route == null) continue;
                try {
                    JSONObject stats = new JSONObject(new String(Files.readAllBytes(summary.toPath()),
                            StandardCharsets.UTF_8));
                    if (!route.getName().equals(stats.optString("file")) || stats.optLong("start") <= 0) continue;
                    String device = prefs.getString("device-" + rider.getName(), "Riding phone");
                    rides.add(new Ride(stats, route, stats.optString("bikeName", device)));
                } catch (IOException | JSONException ignored) { /* Incomplete or unrelated sidecar. */ }
            }
        }
        rides.sort((a,b) -> Long.compare(b.stats.optLong("start"), a.stats.optLong("start")));
        return rides;
    }
}
