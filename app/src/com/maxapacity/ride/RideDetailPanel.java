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
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import java.io.InputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One saved ride: its route, its numbers, and what can be done with it. */
final class RideDetailPanel extends Panel {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final Host host;
    private final LinearLayout content;
    private JSONObject ride;
    private DeckInbox.Ride received;
    private int generation;

    RideDetailPanel(Context context, Host host) {
        super(context, "Ride");
        this.host = host;
        content = addScrollContent();
    }

    void show(JSONObject r) {
        received = null;
        ride = r;
        build();
        open();
    }

    void showReceived(DeckInbox.Ride entry) {
        received = entry;
        ride = entry.stats;
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
        if (received != null) status.set("HOME DECK", Ui.OK);
        else if (RideStore.STRAVA_UPLOADED.equals(strava)) status.set("ON STRAVA", Ui.OK);
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
        final File gpx = received != null ? received.route : new File(RideStore.dir(c), file);
        final boolean home = received != null;
        final LinearLayout gearingCard = r.optJSONObject("gearing") == null ? null : gearingHeader(r);
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
            if (gearingCard != null) loadComparison(r, home, gen, gearingCard, points);
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

        if (gearingCard != null) content.addView(gearingCard, Ui.top(c, Ui.matchWrap(), 12));

        button(content, "Share ride card", Icons.ROUTE, () -> host.launch(RideShareActivity.forRide(c, r)));

        BikeLog.Summary bike = RideStore.bike(r);
        if (bike != null) addBike(bike, distance);

        LinearLayout meta = Ui.card(c);
        addMeta(meta, "Stops", String.valueOf(r.optInt("stops")), false);
        // Rides saved before lean was measured have none to show.
        if (r.has("leanLeft")) {
            addMeta(meta, "Max lean", "L " + r.optInt("leanLeft") + "\u00B0   R "
                    + r.optInt("leanRight") + "\u00B0", true);
        }
        if (received != null) {
            addMeta(meta, "From", received.source, true);
            content.addView(meta, Ui.top(c, Ui.matchWrap(), 12));
            return;
        }
        addMeta(meta, "Strava", RidesPanel.stravaLine(r), true);
        if (Health.isOn(c) || Health.SENT.equals(r.optString("health"))) {
            String health = r.optString("health");
            addMeta(meta, "Health Connect", Health.SENT.equals(health) ? "Added"
                    : Health.REFUSED.equals(health) ? "Not accepted" : "Waiting", true);
        }
        boolean onMac = RideStore.ON_MAC.equals(r.optString("deck"));
        if (onMac || Deck.get(c).everLinked()) {
            addMeta(meta, "Mac", onMac ? "Sent" : "Waiting to send", true);
        }
        addMeta(meta, "File", file, true);
        content.addView(meta, Ui.top(c, Ui.matchWrap(), 12));

        if (!RideStore.STRAVA_UPLOADED.equals(strava)
                && !RideStore.STRAVA_PENDING.equals(strava)) {
            final boolean connected = Strava.isConnected(c);
            LinearLayout send = Ui.button(c, "SEND TO STRAVA", Icons.CLOUD_UPLOAD,
                    connected ? Ui.PRIMARY : Ui.SECONDARY);
            send.setOnClickListener(v -> {
                if (!connected) {
                    // Connect first; this ride goes up as soon as that is done.
                    host.connectStrava(file);
                    return;
                }
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

    /**
     * What the linked bike did on this ride: the three figures a tune is
     * judged by on top, the rest underneath.
     */
    private void addBike(BikeLog.Summary s, double distanceM) {
        Context c = getContext();
        content.addView(Ui.label(c, "THE BIKE"), Ui.top(c, Ui.matchWrap(), 24));
        LinearLayout card = Ui.card(c);
        LinearLayout top = Ui.row(c);
        addStat(top, "PEAK", Ui.withUnit(fmt("%.1f", s.peakKw), "kW"));
        addStat(top, "USED", Ui.withUnit(fmt("%.0f", s.usedWh), "Wh"));
        float per = s.whPer(distanceM, Units.metric(c));
        addStat(top, "PER " + (Units.metric(c) ? "KM" : "MILE"),
                per > 0 ? Ui.withUnit(fmt("%.0f", per), "Wh") : "\u2013");
        card.addView(top, Ui.matchWrap());

        addMeta(card, "Most battery current", fmt("%.0f A", s.peakAmps), true);
        addMeta(card, "Lowest voltage", s.sagVolts >= 0.5f
                ? fmt("%.1f V, sag %.1f V", s.lowVolts, s.sagVolts)
                : fmt("%.1f V", s.lowVolts), true);
        // Two to a row at most: three would not fit a narrow phone.
        String hot = "";
        if (s.motorC != BikeLog.Summary.UNKNOWN) hot += "motor " + s.motorC + "\u00B0";
        if (s.controllerC != BikeLog.Summary.UNKNOWN) {
            hot += (hot.isEmpty() ? "" : "  ") + "controller " + s.controllerC + "\u00B0";
        }
        if (!hot.isEmpty()) addMeta(card, "Hottest, \u00B0C", hot, true);
        if (s.regenAmps >= 1f) {
            addMeta(card, "Regen", s.regenWh >= 1f
                    ? fmt("%.0f A peak, %.0f Wh back", s.regenAmps, s.regenWh)
                    : fmt("%.0f A peak", s.regenAmps), true);
        }
        if (s.socStart >= 0 && s.socEnd >= 0) {
            addMeta(card, "Battery", s.socStart + "% to " + s.socEnd + "%"
                    + (s.batteryC != BikeLog.Summary.UNKNOWN
                    ? ", " + s.batteryC + "\u00B0 at most" : ""), true);
        } else if (s.batteryC != BikeLog.Summary.UNKNOWN) {
            addMeta(card, "Battery, hottest", s.batteryC + " \u00B0C", true);
        }
        content.addView(card, Ui.top(c, Ui.matchWrap(), 8));
    }

    private static String fmt(String pattern, Object... values) {
        return String.format(Locale.US, pattern, values);
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

    private LinearLayout gearingHeader(JSONObject ride) {
        Context c = getContext();
        LinearLayout card = Ui.card(c);
        JSONObject setup = ride.optJSONObject("gearing");
        card.addView(Ui.label(c, "INSTALLED GEARING"));
        card.addView(Ui.title(c, teeth(setup)), Ui.top(c, Ui.matchWrap(), 8));
        card.addView(Ui.small(c, setup.optString("name"), Ui.TEXT_2));
        return card;
    }

    private void loadComparison(JSONObject ride, boolean home, int gen, LinearLayout card, List<double[]> points) {
        Context c = getContext();
        BenchmarkLoop loop = home ? new BenchmarkLoop(c, ride) : null;
        JSONObject pin = home ? loop.pin() : null;
        JSONObject shape = home ? BenchmarkLoop.shape(points) : null;
        if (home && !loop.bike.isEmpty()) {
            MAIN.post(() -> {
                if (gen == generation) benchmarkControls(card, loop, ride, pin, shape);
            });
        }
        if (pin != null) return;
        JSONObject previous = null;
        if (home) {
            for (DeckInbox.Ride other : DeckInbox.load(c)) {
                if (InstalledGearing.beforeSwap(ride, other.stats)) { previous = other.stats; break; }
            }
        } else {
            org.json.JSONArray history = RideStore.load(c);
            for (int i = 0; i < history.length(); i++) {
                JSONObject other = history.optJSONObject(i);
                if (other != null && InstalledGearing.beforeSwap(ride, other)
                        && (previous == null || previous.optLong("start") < other.optLong("start"))) previous = other;
            }
        }
        JSONObject before = previous;
        MAIN.post(() -> {
            if (gen != generation) return;
            if (before == null) {
                card.addView(Ui.small(c, "This ride can be compared after you record a ride on different sprockets.",
                        Ui.TEXT_2), Ui.top(c, Ui.matchWrap(), 8));
                return;
            }
            comparisonRows(card, ride, before, "BEFORE SWAP → THIS RIDE");
        });
    }

    private void comparisonRows(LinearLayout card, JSONObject ride, JSONObject before, String heading) {
        Context c = getContext();
        String date = android.text.format.DateUtils.formatDateTime(c, before.optLong("start"),
                android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_TIME);
        card.addView(Ui.label(c, heading), Ui.top(c, Ui.matchWrap(), 16));
        card.addView(Ui.body(c, "Compared with " + date + " on " + teeth(before.optJSONObject("gearing")), Ui.TEXT_2));
        addMeta(card, "Distance · " + Units.distanceLabel(c), Units.distance(c, before.optDouble("distanceM"))
                + " → " + Units.distance(c, ride.optDouble("distanceM")), true);
        if (before.has("climbM") && ride.has("climbM")) addMeta(card, "Climb · " + Units.climbLabel(c), Units.climb(c, (float) before.optDouble("climbM"))
                + " → " + Units.climb(c, (float) ride.optDouble("climbM")), true);
        addMeta(card, "Moving time", Units.duration(before.optLong("movingMs"))
                + " → " + Units.duration(ride.optLong("movingMs")), true);
        if (before.has("topMps") && ride.has("topMps")) addMeta(card, "Top speed · " + Units.speedLabel(c), Units.speed(c, (float) before.optDouble("topMps"))
                + " → " + Units.speed(c, (float) ride.optDouble("topMps")), true);
        double oldEnergy = energyPerDistance(c, before), newEnergy = energyPerDistance(c, ride);
        if (Double.isFinite(oldEnergy) && Double.isFinite(newEnergy)) {
            addMeta(card, "Wh/" + Units.distanceLabel(c), fmt("%.1f", oldEnergy) + " → " + fmt("%.1f", newEnergy), true);
        }
    }

    private void button(LinearLayout card, String title, String icon, Runnable click) {
        View action = Ui.button(getContext(), title, icon, Ui.SECONDARY);
        action.setOnClickListener(v -> click.run());
        card.addView(action, Ui.top(getContext(), Ui.matchWrap(), 8));
    }

    private void benchmarkControls(LinearLayout card, BenchmarkLoop loop, JSONObject ride, JSONObject pin, JSONObject shape) {
        Context c = getContext();
        JSONObject baseline = pin == null ? null : pin.optJSONObject("ride");
        String name = pin == null ? "" : pin.optString("name");
        String id = BenchmarkLoop.id(ride);
        if (baseline != null) {
            card.addView(Ui.label(c, "BENCHMARK LOOP"), Ui.top(c, Ui.matchWrap(), 16));
            card.addView(Ui.title(c, name));
            if (BenchmarkLoop.id(baseline).equals(id)) {
                card.addView(Ui.small(c, "Pinned baseline · " + teeth(baseline.optJSONObject("gearing")), Ui.TEXT_2));
                button(card, "Unpin benchmark", Icons.PIN, () -> {
                    loop.prefs.edit().remove("pin." + loop.bike).apply();
                    build();
                });
                return;
            }
            String matchKey = "match/" + BenchmarkLoop.id(baseline) + "/" + id;
            if (InstalledGearing.beforeSwap(ride, baseline) && BenchmarkLoop.matches(pin.optJSONObject("shape"), shape)) {
                String answer = loop.note(matchKey);
                if ("yes".equals(answer)) {
                    comparisonRows(card, ride, baseline, "SAME LOOP · BEFORE → AFTER");
                    verdict(card, loop, ride, baseline);
                } else if (!"no".equals(answer)) {
                    card.addView(Ui.body(c, "The GPS tracks look similar. Did you ride the same loop in the same direction?", Ui.TEXT_2));
                    button(card, "Confirm same loop", Icons.CHECK, () -> { loop.note(matchKey, "yes"); build(); });
                }
                button(card, "no".equals(answer) ? "Review route match" : "Different route", Icons.ROUTE, () -> {
                    loop.note(matchKey, "no".equals(answer) ? "" : "no"); build();
                });
            } else {
                card.addView(Ui.small(c, "A comparison needs a later ride on this loop with different sprockets.", Ui.TEXT_2));
            }
        }
        if (shape == null) {
            card.addView(Ui.small(c, "A benchmark needs a GPS track of at least 200 m.", Ui.TEXT_2));
            return;
        }
        button(card, pin == null ? "Pin benchmark loop" : "Use this ride as baseline", Icons.PIN, () -> {
            android.widget.EditText field = Ui.input(c, "Loop name", false);
            field.setText(pin == null ? "My benchmark loop" : name);
            field.selectAll();
            new Sheet(host.frame(), "Pin benchmark loop", "Use this ride as the comparison baseline for this bike.")
                    .view(field).action("Cancel", Ui.SECONDARY, true, null)
                    .action("Pin this ride", Ui.PRIMARY, true, () -> {
                        String label = field.getText().toString().trim();
                        loop.pin(label.isEmpty() ? "My benchmark loop" : label, ride, shape);
                        build();
                    }).show();
        });
    }

    private void verdict(LinearLayout card, BenchmarkLoop loop, JSONObject ride, JSONObject baseline) {
        Context c = getContext();
        String key = "feel/" + BenchmarkLoop.id(ride);
        String promptKey = "asked/" + BenchmarkLoop.id(baseline) + "/" + teeth(ride.optJSONObject("gearing"));
        String saved = loop.note(key);
        Runnable choices = () -> {
            for (String label : new String[]{"Climbed easier", "Cruised easier", "About the same", "Wanted more pull", "Skip"}) {
                button(card, label, Icons.CHECK, () -> {
                    loop.note(key, label);
                    loop.note(promptKey, "yes");
                    build();
                });
            }
        };
        card.addView(Ui.label(c, "HOW DID IT FEEL?"), Ui.top(c, Ui.matchWrap(), 16));
        if (saved.isEmpty() && loop.note(promptKey).isEmpty()) {
            choices.run();
        } else {
            if (!saved.isEmpty() && !"Skip".equals(saved)) card.addView(Ui.body(c, saved, Ui.TEXT));
            View edit = Ui.button(c, saved.isEmpty() || "Skip".equals(saved) ? "Add ride verdict" : "Change verdict",
                    Icons.FLAG, Ui.SECONDARY);
            edit.setOnClickListener(v -> { edit.setVisibility(GONE); choices.run(); });
            card.addView(edit);
        }
    }

    private static double energyPerDistance(Context c, JSONObject r) {
        JSONObject log = r.optJSONObject(RideStore.BIKE);
        double distance = r.optDouble("distanceM");
        double wh = log == null ? Double.NaN : log.optDouble("usedWh");
        return distance > 0 && wh > 0 ? wh * (Units.metric(c) ? 1000 : 1609.344) / distance : Double.NaN;
    }

    private static String teeth(JSONObject setup) {
        return setup.optInt("front") + "T front / " + setup.optInt("rear") + "T rear";
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
        try (InputStream input = Files.newInputStream(gpx.toPath())) {
            XmlPullParser xml = android.util.Xml.newPullParser();
            xml.setInput(input, null);
            long index = 0, stride = 1;
            double[] last = null;
            for (int event = xml.next(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                if (event != XmlPullParser.START_TAG || !"trkpt".equals(xml.getName())) continue;
                String lat = xml.getAttributeValue(null, "lat");
                String lon = xml.getAttributeValue(null, "lon");
                if (lat == null || lon == null) continue;
                double y = Double.parseDouble(lat), x = Double.parseDouble(lon);
                if (!(Math.abs(y) <= 90 && Math.abs(x) <= 180)) continue;
                last = new double[]{y, x};
                if (index++ % stride != 0) continue;
                points.add(last);
                if (points.size() == 4096) {
                    for (int i = 0; i < 2048; i++) points.set(i, points.get(i * 2));
                    points.subList(2048, 4096).clear();
                    stride *= 2;
                }
            }
            if (last != null && last != points.get(points.size() - 1)) points.add(last);
        } catch (IOException | XmlPullParserException | NumberFormatException e) {
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
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double metersPerPixel;
        private final android.graphics.RectF bounds = new android.graphics.RectF();

        RouteView(Context c, List<double[]> points) {
            super(c);
            this.points = points;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Ui.dp(c, 2.5f));
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(Ui.ACCENT);
            setContentDescription("Recorded route, from the open circle to the finish dot");
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
            float pad = Math.min(Ui.dp(getContext(), 22), Math.min(w, h) / 4f);
            double scale = Math.min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY);
            metersPerPixel = 111320.0 / scale;
            bounds.set(0, 0, w, h);
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
            int cell = Ui.dp(getContext(), 24);
            grid.setStyle(Paint.Style.FILL);
            grid.setColor(Ui.BG);
            float corner = Ui.dp(getContext(), 10);
            canvas.drawRoundRect(bounds, corner, corner, grid);
            grid.setColor(Ui.HAIRLINE);
            grid.setStrokeWidth(Ui.dp(getContext(), 1));
            for (int x = cell; x < getWidth(); x += cell) canvas.drawLine(x, 4, x, getHeight() - 4, grid);
            for (int y = cell; y < getHeight(); y += cell) canvas.drawLine(4, y, getWidth() - 4, y, grid);

            // Label a ground distance represented by the route projection.
            double target = metersPerPixel * getWidth() / 5;
            if (target >= 1 && Double.isFinite(target)) {
                double unit = Math.pow(10, Math.floor(Math.log10(target)));
                double distance = unit * (target >= 5 * unit ? 5 : target >= 2 * unit ? 2 : 1);
                float left = Ui.dp(getContext(), 10), bottom = getHeight() - left;
                grid.setColor(Ui.TEXT_2);
                grid.setTypeface(Ui.mono);
                grid.setTextSize(Ui.dp(getContext(), 10));
                String label = distance >= 1000 ? Math.round(distance / 1000) + " km" : Math.round(distance) + " m";
                canvas.drawText(label, left, bottom - Ui.dp(getContext(), 4), grid);
                canvas.drawLine(left, bottom, left + (float) (distance / metersPerPixel), bottom, grid);
            }
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
