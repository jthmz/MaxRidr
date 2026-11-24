package com.maxapacity.ride;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Deck mode: the MAXAPACITY Deck (the Mac service behind the Car Thing desk
 * dashboard) on the phone. Shows whether the Car Thing is linked, the channel,
 * the video pipeline, what Claude is working on and recent alerts, and sends
 * the Deck's own actions: Mac volume, mute and the Launch tiles.
 *
 * Talks to the Deck's /api/state and /api/action wherever {@link Deck} has found
 * it: on the home network once paired, or down a USB cable.
 */
final class DeckView extends Panel {

    private static final long POLL_MS = 2000;
    private static final long RELOOK_MS = 4000;

    private final Host host;
    private final LinearLayout content;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean loadedOnce;
    private int generation;
    /** What is on screen, so a poll that changes nothing does not rebuild it. */
    private String shownState = "";
    /** The link this screen was last built for. */
    private String showing = "";

    private final Runnable poll = this::fetchState;
    /** While no Deck is linked, keep looking: a cable plugged in announces nothing. */
    private final Runnable relook = new Runnable() {
        @Override
        public void run() {
            Deck.get(getContext()).refresh();
            main.postDelayed(this, RELOOK_MS);
        }
    };

    DeckView(Context context, Host host) {
        super(context, "Deck");
        this.host = host;
        View edit = Ui.iconButton(context, Icons.LINK, "Deck connection");
        edit.setOnClickListener(v -> host.editDeckConnection());
        setHeaderAction(edit);
        content = addScrollContent();
    }

    @Override
    protected void onOpen() {
        Deck.get(getContext()).refresh();
        restart();
    }

    @Override
    protected void onClose() {
        stopPolling();
    }

    /** Stop talking to the Mac while the launcher is in the background. */
    @Override
    void onHostPause() {
        stopPolling();
    }

    @Override
    void onHostResume() {
        restart();
    }

    /** Starts again from what the link is now. */
    void restart() {
        stopPolling();
        loadedOnce = false;
        shownState = "";
        Deck d = Deck.get(getContext());
        showing = d.state() + " " + d.address();
        if (d.state() == Deck.LINKED) {
            showLoading();
            fetchState();
            return;
        }
        if (d.state() == Deck.FOUND) showPair();
        else showEmpty();
        main.postDelayed(relook, RELOOK_MS);
    }

    /** The link came up, dropped or moved to another address. */
    void linkChanged() {
        Deck d = Deck.get(getContext());
        if (isOpen() && !showing.equals(d.state() + " " + d.address())) restart();
    }

    private void stopPolling() {
        generation++; // replies to requests already in flight are dropped
        main.removeCallbacks(poll);
        main.removeCallbacks(relook);
    }

    private String baseUrl() {
        return "http://" + Deck.get(getContext()).address();
    }

    // ---- Network ---------------------------------------------------------

    private void fetchState() {
        final int gen = generation;
        final String url = baseUrl() + "/api/state";
        final String token = Deck.get(getContext()).requestKey();
        io.execute(() -> {
            JSONObject state = null;
            String error = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(6000);
                if (!token.isEmpty()) conn.setRequestProperty("x-deck-token", token);
                int code = conn.getResponseCode();
                if (code == 401) {
                    error = "This phone is no longer paired with the Deck";
                } else if (code != 200) {
                    error = "The Deck answered with error " + code;
                } else {
                    state = new JSONObject(read(conn.getInputStream()));
                }
                conn.disconnect();
            } catch (IOException | IllegalArgumentException e) {
                error = "The Deck stopped answering. Is the Mac awake and on this network?";
            } catch (JSONException e) {
                error = "The Deck sent something this app could not read";
            }
            final JSONObject fState = state;
            final String fError = error;
            main.post(() -> {
                if (gen != generation) return;
                if (fState != null) {
                    loadedOnce = true;
                    showState(fState);
                } else {
                    if (!loadedOnce) {
                        showError(fError);
                    } else {
                        // Keep the last good screen; just say the link dropped.
                        status.set("RECONNECTING", Ui.WARN);
                    }
                    // Find out where the Deck is now; this screen follows the answer.
                    Deck.get(getContext()).refresh();
                }
                main.postDelayed(poll, POLL_MS);
            });
        });
    }

    private void act(final JSONObject action, String toast) {
        final String url = baseUrl() + "/api/action";
        final String token = Deck.get(getContext()).requestKey();
        if (toast != null) host.say(toast);
        io.execute(() -> {
            boolean ok;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(6000);
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                if (!token.isEmpty()) conn.setRequestProperty("x-deck-token", token);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(action.toString().getBytes(StandardCharsets.UTF_8));
                }
                ok = conn.getResponseCode() == 200;
                conn.disconnect();
            } catch (IOException | IllegalArgumentException e) {
                ok = false;
            }
            if (!ok) {
                main.post(() -> host.say("The Mac did not get that. Check the Deck connection"));
            }
        });
    }

    private static JSONObject action(String type, String key, Object value) {
        try {
            JSONObject a = new JSONObject().put("t", type);
            if (key != null) a.put(key, value);
            return a;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = stream.read(chunk)) > 0) buf.write(chunk, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---- States ---------------------------------------------------------

    private void showEmpty() {
        boolean looking = Deck.get(getContext()).state() == Deck.LOOKING;
        status.set(looking ? "LOOKING" : "OUT OF REACH", Ui.TEXT_3);
        content.removeAllViews();
        content.addView(Ui.emptyState(getContext(), Icons.MONITOR, "Your desk, in your pocket",
                "Channel numbers, the video pipeline, Claude's progress and Mac controls, from"
                        + " the Deck on your Mac. It links by itself when this phone is on the"
                        + " same Wi-Fi as the Mac, or plugged into it.",
                "Deck connection", Ui.PRIMARY, v -> host.editDeckConnection()));
    }

    /** A Deck is in reach and only wants its code. */
    private void showPair() {
        status.set("FOUND", Ui.WARN);
        content.removeAllViews();
        content.addView(Ui.emptyState(getContext(), Icons.LINK, "Deck found",
                "On the Deck, tap the clock, then Add a screen, and enter the 6-digit code it"
                        + " shows.",
                "Enter code", Ui.PRIMARY, v -> host.editDeckConnection()));
    }

    /** Layout-matched placeholders rather than a spinner. */
    private void showLoading() {
        status.set("CONNECTING", Ui.WARN);
        content.removeAllViews();
        int[] heights = {132, 148, 120, 112};
        for (int i = 0; i < heights.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(heights[i]));
            if (i > 0) lp.topMargin = dp(12);
            content.addView(Ui.skeleton(getContext(), heights[i]), lp);
        }
    }

    private void showError(String text) {
        status.set("OFFLINE", Ui.DANGER);
        content.removeAllViews();
        content.addView(Ui.emptyState(getContext(), Icons.UNLINK, "Deck is out of reach", text,
                "Deck connection", Ui.SECONDARY, v -> host.editDeckConnection()));
    }

    private void showState(JSONObject s) {
        // The Deck's own link to the Car Thing: "connected", "no Car Thing on USB", ...
        String link = s.optString("link", "unknown");
        boolean linked = "connected".equals(link);
        status.set(linked ? "CAR THING LINKED" : "CAR THING OFF", linked ? Ui.OK : Ui.WARN);

        // Polled every two seconds: rebuild only when something actually changed,
        // so the screen does not flicker or lose its scroll position.
        String now = s.toString();
        if (now.equals(shownState)) return;
        shownState = now;

        content.removeAllViews();
        addChannel(s.optJSONObject("yt"));
        addPipeline(s.optJSONObject("board"));
        addClaude(s.optJSONArray("claude"));
        addAlerts(s.optJSONArray("alerts"));
        addMac(s);
        addApps(s.optJSONArray("apps"));
    }

    private void addChannel(JSONObject yt) {
        LinearLayout card = card(Icons.YOUTUBE, "CHANNEL");
        if (yt == null || !yt.optBoolean("ok")) {
            card.addView(note("Channel numbers are not available right now"));
            return;
        }
        Context c = getContext();
        TextView subs = Ui.value(c, "", 28);
        subs.setText(Ui.withUnit(yt.optString("subs"), "subscribers"));
        card.addView(subs);
        if (!yt.isNull("toGoal")) {
            card.addView(Ui.bar(c, yt.optInt("goalPct"), Ui.ACCENT), Ui.top(c, barParams(), 12));
            card.addView(Ui.small(c, String.format(Locale.US, "%,d to go to %,d",
                    yt.optInt("toGoal"), yt.optInt("goal")),
                    Ui.TEXT_3), Ui.top(c, Ui.matchWrap(), 8));
        }
        int day = yt.optInt("day");
        TextView views = Ui.small(c, (day >= 0 ? "+" : "") + String.format(Locale.US, "%,d", day)
                + " views in the last "
                + Math.round(yt.optDouble("daySpan", 24)) + " h", day > 0 ? Ui.OK : Ui.TEXT_2);
        card.addView(views, Ui.top(c, Ui.matchWrap(), 8));
    }

    private void addPipeline(JSONObject board) {
        LinearLayout card = card(Icons.LIST_CHECKS, "PIPELINE");
        if (board == null || board.has("error")) {
            card.addView(note("The board file could not be read on the Mac"));
            return;
        }
        Context c = getContext();
        JSONArray owed = board.optJSONArray("owed");
        JSONArray inflight = board.optJSONArray("inflight");
        JSONArray blocked = board.optJSONArray("blocked");
        int waiting = blocked == null ? 0 : blocked.length();
        LinearLayout row = Ui.row(c);
        row.addView(stat("VIDEOS", owed == null ? 0 : owed.length(), Ui.TEXT), Ui.weighted());
        row.addView(stat("IN FLIGHT", inflight == null ? 0 : inflight.length(), Ui.TEXT),
                Ui.weighted());
        // Amber only when something is actually waiting on the rider.
        row.addView(stat("WAITING ON YOU", waiting, waiting > 0 ? Ui.WARN : Ui.TEXT),
                Ui.weighted());
        card.addView(row, Ui.matchWrap());
        for (int i = 0; owed != null && i < Math.min(owed.length(), 4); i++) {
            JSONObject o = owed.optJSONObject(i);
            if (o == null) continue;
            card.addView(line(o.optString("title"), Ui.TEXT), Ui.top(c, Ui.matchWrap(), 16));
            card.addView(Ui.bar(c, o.optInt("pct"), Ui.ACCENT), Ui.top(c, barParams(), 8));
            card.addView(Ui.small(c, o.optString("status"), Ui.TEXT_3),
                    Ui.top(c, Ui.matchWrap(), 8));
        }
    }

    private void addClaude(JSONArray sessions) {
        LinearLayout card = card(Icons.BOT, "CLAUDE");
        if (sessions == null || sessions.length() == 0) {
            card.addView(note("No recent Claude sessions"));
            return;
        }
        Context c = getContext();
        for (int i = 0; i < Math.min(sessions.length(), 4); i++) {
            JSONObject s = sessions.optJSONObject(i);
            if (s == null) continue;
            card.addView(line(s.optString("title"), Ui.TEXT),
                    Ui.top(c, Ui.matchWrap(), i == 0 ? 0 : 16));
            int total = s.optInt("total");
            boolean working = s.optBoolean("working");
            if (total > 0) {
                card.addView(Ui.bar(c, 100 * s.optInt("done") / total,
                        working ? Ui.ACCENT : Ui.TEXT_3), Ui.top(c, barParams(), 8));
            }
            String text = working ? "Working" : "Idle " + s.optString("ago");
            if (total > 0) text += "  \u00B7  " + s.optInt("done") + "/" + total;
            String active = s.optString("active");
            if (!active.isEmpty() && !"null".equals(active)) text += "  \u00B7  " + active;
            TextView t = Ui.small(c, text, working ? Ui.OK : Ui.TEXT_3);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(t, Ui.top(c, Ui.matchWrap(), 8));
        }
    }

    private void addAlerts(JSONArray alerts) {
        if (alerts == null || alerts.length() == 0) return;
        LinearLayout card = card(Icons.BELL, "JUST HAPPENED");
        Context c = getContext();
        for (int i = 0; i < Math.min(alerts.length(), 4); i++) {
            JSONObject a = alerts.optJSONObject(i);
            if (a == null) continue;
            LinearLayout row = Ui.row(c);
            row.addView(line(a.optString("text"), Ui.TEXT), Ui.weighted());
            row.addView(Ui.small(c, a.optString("ago"), Ui.TEXT_3), Ui.left(c, Ui.wrap(), 12));
            card.addView(row, Ui.top(c, Ui.matchWrap(), i == 0 ? 0 : 8));
        }
    }

    private void addMac(JSONObject s) {
        LinearLayout card = card(Icons.LAPTOP, "MAC");
        Context c = getContext();
        JSONObject vol = s.optJSONObject("vol");
        boolean muted = vol != null && vol.optBoolean("muted");
        String level = vol == null || vol.isNull("v") ? "--" : String.valueOf(vol.optInt("v"));
        LinearLayout stats = Ui.row(c);
        LinearLayout v = Ui.column(c);
        v.addView(Ui.label(c, muted ? "VOLUME (MUTED)" : "VOLUME"));
        TextView vv = Ui.value(c, level, Ui.T_TITLE);
        if (muted) vv.setTextColor(Ui.TEXT_3);
        v.addView(vv, Ui.top(c, Ui.wrap(), 8));
        stats.addView(v, Ui.weighted());
        LinearLayout l = Ui.column(c);
        l.addView(Ui.label(c, "LOAD"));
        TextView lv = Ui.value(c, "", Ui.T_TITLE);
        lv.setText(Ui.withUnit(String.valueOf(s.optInt("load")), "%"));
        l.addView(lv, Ui.top(c, Ui.wrap(), 8));
        stats.addView(l, Ui.weighted());
        card.addView(stats, Ui.matchWrap());

        LinearLayout row = Ui.row(c);
        LinearLayout down = Ui.button(c, "DOWN", Icons.VOLUME_1, Ui.SECONDARY);
        down.setOnClickListener(x -> act(action("vol", "d", -6), null));
        row.addView(down, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout mute = Ui.button(c, muted ? "UNMUTE" : "MUTE", Icons.VOLUME_X, Ui.SECONDARY);
        mute.setOnClickListener(x -> act(action("mute", null, null), null));
        row.addView(mute, Ui.left(c, new LinearLayout.LayoutParams(0, dp(48), 1f), 8));
        LinearLayout up = Ui.button(c, "UP", Icons.VOLUME_2, Ui.SECONDARY);
        up.setOnClickListener(x -> act(action("vol", "d", 6), null));
        row.addView(up, Ui.left(c, new LinearLayout.LayoutParams(0, dp(48), 1f), 8));
        card.addView(row, Ui.top(c, Ui.matchWrap(), 16));
    }

    private void addApps(JSONArray apps) {
        if (apps == null || apps.length() == 0) return;
        LinearLayout card = card(Icons.ROCKET, "LAUNCH ON MAC");
        Context c = getContext();
        LinearLayout row = null;
        for (int i = 0; i < apps.length(); i++) {
            final JSONObject app = apps.optJSONObject(i);
            if (app == null) continue;
            if (i % 3 == 0) {
                row = Ui.row(c);
                card.addView(row, Ui.top(c, Ui.matchWrap(), i == 0 ? 0 : 8));
            }
            final String name = app.optString("label");
            LinearLayout tile = Ui.button(c, name.toUpperCase(Locale.US), null, Ui.SECONDARY);
            tile.setPadding(dp(8), 0, dp(8), 0);
            tile.setOnClickListener(v -> act(action("app", "key", app.optString("key")),
                    "Opening " + name + " on the Mac"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            if (i % 3 != 0) lp.leftMargin = dp(8);
            row.addView(tile, lp);
        }
        // Pad the last row so tiles keep the same width.
        for (int i = apps.length(); i % 3 != 0; i++) {
            row.addView(new View(c), Ui.left(c,
                    new LinearLayout.LayoutParams(0, dp(48), 1f), 8));
        }
    }

    // ---- View helpers ----------------------------------------------------

    private LinearLayout card(String icon, String name) {
        Context c = getContext();
        LinearLayout card = Ui.card(c);
        LinearLayout head = Ui.row(c);
        head.addView(Ui.icon(c, icon, 16, Ui.TEXT_3));
        head.addView(Ui.label(c, name), Ui.left(c, Ui.weighted(), 8));
        LinearLayout.LayoutParams hlp = Ui.matchWrap();
        hlp.bottomMargin = dp(16);
        card.addView(head, hlp);
        content.addView(card, Ui.top(c, Ui.matchWrap(), content.getChildCount() == 0 ? 0 : 12));
        return card;
    }

    private LinearLayout stat(String name, int count, int color) {
        Context c = getContext();
        LinearLayout col = Ui.column(c);
        col.addView(Ui.label(c, name));
        TextView v = Ui.value(c, String.valueOf(count), Ui.T_TITLE);
        v.setTextColor(color);
        col.addView(v, Ui.top(c, Ui.wrap(), 8));
        return col;
    }

    private TextView line(String text, int color) {
        TextView t = Ui.body(getContext(), text, color);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private TextView note(String text) {
        return Ui.small(getContext(), text, Ui.TEXT_3);
    }

    private LinearLayout.LayoutParams barParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
    }
}
