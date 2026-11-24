package com.maxapacity.ride;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One saved ride: its route, its numbers, and what can be done with it. */
final class RideDetailPanel extends Panel {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern TRKPT =
            Pattern.compile("<trkpt lat=\"(-?[0-9.]+)\" lon=\"(-?[0-9.]+)\"");

    private final Host host;
    private final LinearLayout content;
    private JSONObject ride;
    private int generation;

    RideDetailPanel(Context context, Host host) {
        super(context, "Ride");
        this.host = host;
        content = addScrollContent();
    }

    void show(JSONObject r) {
        ride = r;
        build();
        open();
    }

    private void build() {
        final Context c = getContext();
        final JSONObject r = ride;
        final String file = r.optString("file");
        content.removeAllViews();
        titleView.setText(new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
                .format(new Date(r.optLong("start"))));
        String strava = r.optString("strava");
        if (RideStore.STRAVA_UPLOADED.equals(strava)) status.set("ON STRAVA", Ui.OK);
        else if (RideStore.STRAVA_PENDING.equals(strava)) status.set("SENDING", Ui.WARN);
        else if (RideStore.STRAVA_FAILED.equals(strava)) status.set("REJECTED", Ui.DANGER);
        else status.set("", Ui.TEXT_3);

        // Route: a skeleton until the GPX has been read off the main thread.
        final LinearLayout routeCard = Ui.card(c);
        routeCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        final View placeholder = Ui.skeleton(c, 168);
        placeholder.setBackground(null);
        routeCard.addView(placeholder);
        content.addView(routeCard, Ui.matchWrap());
        final int gen = ++generation;
        final File gpx = new File(RideStore.dir(c), file);
        IO.execute(() -> {
            final List<double[]> points = readTrack(gpx);
            MAIN.post(() -> {
                if (gen != generation) return;
                routeCard.removeAllViews();
                if (points.size() < 2) {
                    TextView none = Ui.small(c, "The route file for this ride could not be"
                            + " read, so only its numbers are shown.", Ui.TEXT_3);
                    routeCard.addView(none);
                } else {
                    routeCard.addView(new RouteView(c, points), new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(168)));
                }
            });
        });

        String speedUnit = Units.speedLabel(c).toLowerCase(Locale.US);
        long moving = r.optLong("movingMs");
        double distance = r.optDouble("distanceM", 0);
        float avg = moving < 1000 ? 0f : (float) (distance / (moving / 1000.0));
        LinearLayout stats = Ui.card(c);
        LinearLayout a = Ui.row(c);
        addStat(a, "DISTANCE", Ui.withUnit(Units.distance(c, distance), Units.distanceLabel(c)));
        addStat(a, "MOVING", Units.duration(moving));
        addStat(a, "ELAPSED", Units.duration(r.optLong("elapsedMs")));
        stats.addView(a, Ui.matchWrap());
        View rule = Ui.divider(c);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        rlp.topMargin = dp(12);
        rlp.bottomMargin = dp(12);
        stats.addView(rule, rlp);
        LinearLayout b = Ui.row(c);
        addStat(b, "TOP", Ui.withUnit(Units.speed(c, (float) r.optDouble("topMps", 0)),
                speedUnit));
        addStat(b, "AVG", Ui.withUnit(Units.speed(c, avg), speedUnit));
        addStat(b, "CLIMB", Ui.withUnit(Units.climb(c, r.optDouble("climbM", 0)),
                Units.climbLabel(c)));
        stats.addView(b, Ui.matchWrap());
        content.addView(stats, Ui.top(c, Ui.matchWrap(), 12));

        LinearLayout meta = Ui.card(c);
        addMeta(meta, "Stops", String.valueOf(r.optInt("stops")), false);
        // Rides saved before lean was measured have none to show.
        if (r.has("leanLeft")) {
            addMeta(meta, "Max lean", "L " + r.optInt("leanLeft") + "\u00B0   R "
                    + r.optInt("leanRight") + "\u00B0", true);
        }
        addMeta(meta, "Strava", RidesPanel.stravaLine(r), true);
        boolean onMac = RideStore.ON_MAC.equals(r.optString("deck"));
        if (onMac || Deck.get(c).everLinked()) {
            addMeta(meta, "Mac", onMac ? "Sent" : "Waiting to send", true);
        }
        addMeta(meta, "File", file, true);
        content.addView(meta, Ui.top(c, Ui.matchWrap(), 12));

        if (Strava.isConnected(c) && !RideStore.STRAVA_UPLOADED.equals(strava)
                && !RideStore.STRAVA_PENDING.equals(strava)) {
            LinearLayout send = Ui.button(c, "SEND TO STRAVA", Icons.CLOUD_UPLOAD, Ui.PRIMARY);
            send.setOnClickListener(v -> {
                Strava.queue(c, file);
                host.say("Sending to Strava");
                host.ridesChanged();
                close();
            });
            content.addView(send, Ui.top(c, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48)), 24));
        }
        LinearLayout delete = Ui.button(c, "DELETE RIDE", Icons.TRASH_2, Ui.DANGER_OUTLINE);
        delete.setOnClickListener(v -> {
            final RideStore.Removed removed = RideStore.remove(c, file);
            close();
            host.ridesChanged();
            host.say("Ride deleted", "UNDO", () -> {
                if (removed != null) RideStore.restore(c, removed);
                host.ridesChanged();
            });
        });
        content.addView(delete, Ui.top(c, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)), 12));
    }

    private void addStat(LinearLayout row, String name, CharSequence value) {
        Context c = getContext();
        LinearLayout col = Ui.column(c);
        col.addView(Ui.label(c, name));
        TextView v = Ui.value(c, "", Ui.T_TITLE);
        v.setText(value);
        col.addView(v, Ui.top(c, Ui.matchWrap(), 8));
        row.addView(col, Ui.weighted());
    }

    private void addMeta(LinearLayout card, String name, String value, boolean rule) {
        Context c = getContext();
        if (rule) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
            lp.topMargin = dp(12);
            lp.bottomMargin = dp(12);
            card.addView(Ui.divider(c), lp);
        }
        LinearLayout row = Ui.row(c);
        row.addView(Ui.body(c, name, Ui.TEXT_2), Ui.wrap());
        TextView v = Ui.small(c, value, Ui.TEXT);
        v.setTypeface(Ui.mono);
        v.setGravity(android.view.Gravity.END);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(v, Ui.left(c, Ui.weighted(), 16));
        card.addView(row, Ui.matchWrap());
    }

    /** Latitude / longitude pairs from a GPX file; empty if it cannot be read. */
    static List<double[]> readTrack(File gpx) {
        List<double[]> points = new ArrayList<>();
        try {
            String text = new String(Files.readAllBytes(gpx.toPath()), StandardCharsets.UTF_8);
            Matcher m = TRKPT.matcher(text);
            while (m.find()) {
                points.add(new double[] {
                        Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))});
            }
        } catch (IOException | NumberFormatException e) {
            points.clear();
        }
        return points;
    }

    /** The route as a line: where the ride began (ring) and ended (accent dot). */
    static final class RouteView extends View {
        private final List<double[]> points;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float startX;
        private float startY;
        private float endX;
        private float endY;

        RouteView(Context c, List<double[]> points) {
            super(c);
            this.points = points;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Ui.dp(c, 2.5f));
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(Ui.TEXT);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            double minLat = Double.MAX_VALUE;
            double maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE;
            double maxLon = -Double.MAX_VALUE;
            for (double[] p : points) {
                minLat = Math.min(minLat, p[0]);
                maxLat = Math.max(maxLat, p[0]);
                minLon = Math.min(minLon, p[1]);
                maxLon = Math.max(maxLon, p[1]);
            }
            // Equirectangular: shrink longitude by cos(latitude) so shapes are true.
            double kx = Math.cos(Math.toRadians((minLat + maxLat) / 2));
            double spanX = Math.max((maxLon - minLon) * kx, 1e-6);
            double spanY = Math.max(maxLat - minLat, 1e-6);
            float pad = Ui.dp(getContext(), 8);
            double scale = Math.min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY);
            double offX = (w - spanX * scale) / 2;
            double offY = (h - spanY * scale) / 2;
            path.rewind();
            for (int i = 0; i < points.size(); i++) {
                double[] p = points.get(i);
                float x = (float) (offX + (p[1] - minLon) * kx * scale);
                float y = (float) (offY + (maxLat - p[0]) * scale);
                if (i == 0) {
                    path.moveTo(x, y);
                    startX = x;
                    startY = y;
                } else {
                    path.lineTo(x, y);
                }
                endX = x;
                endY = y;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPath(path, line);
            float r = Ui.dp(getContext(), 5);
            dot.setStyle(Paint.Style.FILL);
            dot.setColor(Ui.SURFACE);
            canvas.drawCircle(startX, startY, r, dot);
            dot.setStyle(Paint.Style.STROKE);
            dot.setStrokeWidth(Ui.dp(getContext(), 2));
            dot.setColor(Ui.TEXT);
            canvas.drawCircle(startX, startY, r, dot);
            dot.setStyle(Paint.Style.FILL);
            dot.setColor(Ui.ACCENT);
            canvas.drawCircle(endX, endY, r, dot);
        }
    }
}
