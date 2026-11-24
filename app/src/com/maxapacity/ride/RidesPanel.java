package com.maxapacity.ride;

import android.content.Context;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Ride history: lifetime totals on top, then every saved ride, newest first.
 * Tapping a ride opens its detail screen.
 */
final class RidesPanel extends Panel {

    private final Host host;
    private final LinearLayout content;

    RidesPanel(Context context, Host host) {
        super(context, "Rides");
        this.host = host;
        content = addScrollContent();
    }

    @Override
    protected void onOpen() {
        refresh();
    }

    void refresh() {
        Context c = getContext();
        content.removeAllViews();
        JSONArray rides = RideStore.load(c);
        if (rides.length() == 0) {
            status.set("", Ui.TEXT_3);
            content.addView(Ui.emptyState(c, Icons.ROUTE, "No rides yet",
                    "Every ride is saved here with its route, speed and climb. Tap Start"
                            + " ride on the dashboard, or just set off: recording starts on"
                            + " its own once you are moving.",
                    "Back to dashboard", Ui.PRIMARY, v -> close()));
            return;
        }
        status.set(rides.length() + (rides.length() == 1 ? " RIDE" : " RIDES"), Ui.TEXT_3);

        double distance = 0;
        long moving = 0;
        float top = 0;
        for (int i = 0; i < rides.length(); i++) {
            JSONObject r = rides.optJSONObject(i);
            if (r == null) continue;
            distance += r.optDouble("distanceM", 0);
            moving += r.optLong("movingMs");
            top = Math.max(top, (float) r.optDouble("topMps", 0));
        }
        LinearLayout totals = Ui.card(c);
        totals.addView(Ui.label(c, "ALL TIME"));
        LinearLayout row = Ui.row(c);
        addTotal(row, "DISTANCE", Ui.withUnit(Units.distance(c, distance),
                Units.distanceLabel(c)));
        addTotal(row, "MOVING", Units.duration(moving));
        addTotal(row, "TOP", Ui.withUnit(Units.speed(c, top),
                Units.speedLabel(c).toLowerCase(Locale.US)));
        totals.addView(row, Ui.top(c, Ui.matchWrap(), 16));
        content.addView(totals, Ui.matchWrap());

        section(content, "HISTORY");
        // Newest first.
        boolean first = true;
        for (int i = rides.length() - 1; i >= 0; i--) {
            final JSONObject r = rides.optJSONObject(i);
            if (r == null) continue;
            content.addView(rideRow(r), Ui.top(c, Ui.matchWrap(), first ? 0 : 8));
            first = false;
        }
    }

    private void addTotal(LinearLayout row, String name, CharSequence value) {
        Context c = getContext();
        LinearLayout col = Ui.column(c);
        col.addView(Ui.label(c, name));
        TextView v = Ui.value(c, "", Ui.T_TITLE);
        v.setText(value);
        col.addView(v, Ui.top(c, Ui.matchWrap(), 8));
        row.addView(col, Ui.weighted());
    }

    private LinearLayout rideRow(final JSONObject r) {
        Context c = getContext();
        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.HAIRLINE, 16));
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        row.setMinimumHeight(dp(72));

        LinearLayout text = Ui.column(c);
        TextView when = Ui.body(c, new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
                .format(new Date(r.optLong("start"))), Ui.TEXT);
        when.setSingleLine(true);
        when.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(when);
        // Only the status half carries colour, and only when it needs attention.
        String moving = Units.duration(r.optLong("movingMs")) + " moving  \u00B7  ";
        android.text.SpannableString line =
                new android.text.SpannableString(moving + stravaLine(r));
        line.setSpan(new android.text.style.ForegroundColorSpan(stravaColor(r)),
                moving.length(), line.length(), 0);
        TextView facts = Ui.small(c, "", Ui.TEXT_3);
        facts.setText(line);
        facts.setSingleLine(true);
        facts.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(facts, Ui.top(c, Ui.matchWrap(), 4));
        row.addView(text, Ui.weighted());

        TextView dist = Ui.value(c, "", Ui.T_TITLE);
        dist.setText(Ui.withUnit(Units.distance(c, r.optDouble("distanceM")),
                Units.distanceLabel(c)));
        row.addView(dist, Ui.left(c, Ui.wrap(), 12));
        row.addView(Ui.icon(c, Icons.CHEVRON_RIGHT, 20, Ui.TEXT_3),
                Ui.left(c, new LinearLayout.LayoutParams(dp(20), dp(20)), 8));
        row.setOnClickListener(v -> host.showRide(r));
        return row;
    }

    static String stravaLine(JSONObject r) {
        String strava = r.optString("strava");
        if (RideStore.STRAVA_UPLOADED.equals(strava)) return "On Strava";
        if (RideStore.STRAVA_PENDING.equals(strava)) return "Uploading";
        if (RideStore.STRAVA_FAILED.equals(strava)) return "Strava refused it";
        return "On this phone";
    }

    static int stravaColor(JSONObject r) {
        String strava = r.optString("strava");
        if (RideStore.STRAVA_PENDING.equals(strava)) return Ui.WARN;
        if (RideStore.STRAVA_FAILED.equals(strava)) return Ui.DANGER;
        return Ui.TEXT_3;
    }
}
