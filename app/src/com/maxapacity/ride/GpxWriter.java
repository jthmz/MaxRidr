package com.maxapacity.ride;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Writes a recorded track as GPX 1.1, importable by OsmAnd, Organic Maps, Strava. */
final class GpxWriter {

    private GpxWriter() {
    }

    static File write(File dir, long startWallMs, List<RideState.Point> track)
            throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        SimpleDateFormat fileFmt = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US);
        String base = "ride_" + fileFmt.format(new Date(startWallMs));
        File out = new File(dir, base + ".gpx");
        for (int suffix = 2; !out.createNewFile(); suffix++) {
            out = new File(dir, base + "_" + suffix + ".gpx");
        }

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));

        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(out), StandardCharsets.UTF_8))) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            w.write("<gpx version=\"1.1\" creator=\"MAXAPACITY Ride\""
                    + " xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
            w.write("  <trk>\n    <name>Ride " + iso.format(new Date(startWallMs))
                    + "</name>\n    <trkseg>\n");
            for (RideState.Point p : track) {
                w.write(String.format(Locale.US,
                        "      <trkpt lat=\"%.7f\" lon=\"%.7f\">", p.lat, p.lon));
                if (p.hasEle) {
                    w.write(String.format(Locale.US, "<ele>%.1f</ele>", p.ele));
                }
                w.write("<time>" + iso.format(new Date(p.timeMs)) + "</time></trkpt>\n");
            }
            w.write("    </trkseg>\n  </trk>\n</gpx>\n");
        }
        return out;
    }
}
