package com.maxapacity.ride;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Deck mode: the MAXAPACITY Deck (the Mac service behind the Car Thing desk
 * dashboard) on the phone. Shows whether the Car Thing is linked, the channel,
 * the video pipeline, what Claude is working on and recent alerts, and sends
 * the Deck's own actions: Mac volume, mute and the Launch tiles.
 *
 * Talks to the Deck's /api/state and /api/action over the local network (with
 * the Deck's phone token) or over USB through `adb reverse`.
 */
final class DeckView extends LinearLayout {

    interface Host {
        void connectDeck();
    }

    private static final int SCRIM = 0xF2131316;
    private static final int BG = 0xFF131316;
    private static final int SURFACE = 0xFF1C1C21;
    private static final int HAIRLINE = 0xFF2E2E36;
    private static final int TEXT = 0xFFF7F5F0;
    private static final int TEXT_DIM = 0xFF9A9AA3;
    private static final int ACCENT = 0xFFFFEB1A;
    private static final int OK = 0xFF4ADE80;
    private static final int WARN = 0xFFFBBF24;
    private static final int DANGER = 0xFFF87171;

    private static final long POLL_MS = 2000;

    private final Host host;
    private final float density;
    private final TextView status;
    private final LinearLayout content;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean loadedOnce;
    private int generation;

    private final Runnable poll = this::fetchState;

    DeckView(Context context, Host host) {
        super(context);
        this.host = host;
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(SCRIM);
        setClickable(true);
        setVisibility(GONE);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label("DECK"), new LayoutParams(0, dp(32), 1f));
        status = label("");
        status.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(status, new LayoutParams(0, dp(32), 2f));
        addView(header, matchWrap());

        content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    void open() {
        if (isOpen()) return;
        setVisibility(VISIBLE);
        setAlpha(0f);
        setTranslationY(dp(48));
        animate().alpha(1f).translationY(0f).setDuration(180).start();
        restart();
    }

    void close() {
        if (!isOpen()) return;
        stopPolling();
        animate().alpha(0f).translationY(dp(48)).setDuration(150)
                .withEndAction(() -> setVisibility(GONE)).start();
    }

    /** Stop talking to the Mac while the launcher is in the background. */
    void pause() {
        stopPolling();
    }

    void resume() {
        if (isOpen()) restart();
    }

    /** Called after the address or token changes. */
    void restart() {
        stopPolling();
        loadedOnce = false;
        if (address().isEmpty()) {
            showEmpty();
            return;
        }
        showLoading();
        fetchState();
    }

    private void stopPolling() {
        generation++; // replies to requests already in flight are dropped
        main.removeCallbacks(poll);
    }

    private String address() {
        return Units.prefs(getContext()).getString("deck_host", "").trim();
    }

    private String baseUrl() {
        String a = address();
        return "http://" + (a.contains(":") ? a : a + ":8791");
    }

    // ---- Network ---------------------------------------------------------

    private void fetchState() {
        final int gen = generation;
        final String url = baseUrl() + "/api/state";
        final String token = Units.prefs(getContext()).getString("deck_token", "");
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
                    error = "The Deck refused the token. Check it matches phoneToken in the"
                            + " Deck's config.json";
                } else if (code != 200) {
                    error = "The Deck answered with error " + code;
                } else {
                    state = new JSONObject(read(conn.getInputStream()));
                }
                conn.disconnect();
            } catch (IOException | IllegalArgumentException e) {
                error = "Cannot reach the Deck at " + address()
                        + ". Is the Mac on, on this network, and the Deck running?";
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
                } else if (!loadedOnce) {
                    showError(fError);
                } else {
                    // Keep the last good screen; just say the link dropped.
                    status.setText("RECONNECTING");
                    status.setTextColor(WARN);
                }
                main.postDelayed(poll, POLL_MS);
            });
        });
    }

    private void act(final JSONObject action, String toast) {
        final String url = baseUrl() + "/api/action";
        final String token = Units.prefs(getContext()).getString("deck_token", "");
        if (toast != null) Toast.makeText(getContext(), toast, Toast.LENGTH_SHORT).show();
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
                main.post(() -> Toast.makeText(getContext(),
                        "The Mac did not get that. Check the Deck connection",
                        Toast.LENGTH_LONG).show());
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

    // ---- States ----------------------------------------------------------

    private void showEmpty() {
        status.setText("NOT CONNECTED");
        status.setTextColor(TEXT_DIM);
        content.removeAllViews();
        content.addView(message("Your desk dashboard on the phone: channel, video pipeline,"
                + " Claude's progress and Mac controls."), topMargin(48));
        TextView connect = button("CONNECT DECK", true);
        connect.setOnClickListener(v -> host.connectDeck());
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(16);
        content.addView(connect, lp);
    }

    /** Layout-matched placeholders rather than a spinner. */
    private void showLoading() {
        status.setText("CONNECTING");
        status.setTextColor(WARN);
        content.removeAllViews();
        int[] heights = {112, 72, 96, 72};
        for (int h : heights) {
            View block = new View(getContext());
            block.setBackground(panel(SURFACE, HAIRLINE, 16));
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h));
            lp.topMargin = dp(8);
            content.addView(block, lp);
        }
    }

    private void showError(String text) {
        status.setText("OFFLINE");
        status.setTextColor(DANGER);
        content.removeAllViews();
        content.addView(message(text), topMargin(48));
        TextView change = button("CHANGE CONNECTION", false);
        change.setOnClickListener(v -> host.connectDeck());
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(16);
        content.addView(change, lp);
    }

    private void showState(JSONObject s) {
        // The Deck's own link to the Car Thing: "connected", "no Car Thing on USB", ...
        String link = s.optString("link", "unknown");
        boolean linked = "connected".equals(link);
        status.setText(("CAR THING: " + link).toUpperCase(java.util.Locale.US));
        status.setTextColor(linked ? OK : WARN);

        content.removeAllViews();
        addChannel(s.optJSONObject("yt"));
        addPipeline(s.optJSONObject("board"));
        addClaude(s.optJSONArray("claude"));
        addAlerts(s.optJSONArray("alerts"));
        addMac(s);
        addApps(s.optJSONArray("apps"));
    }

    private void addChannel(JSONObject yt) {
        LinearLayout card = card("CHANNEL");
        if (yt == null || !yt.optBoolean("ok")) {
            card.addView(body("Channel numbers are not available right now", TEXT_DIM));
            return;
        }
        card.addView(value(yt.optString("subs") + " subscribers", 20));
        if (!yt.isNull("toGoal")) {
            card.addView(bar(yt.optInt("goalPct")), barParams());
            card.addView(body(yt.optInt("toGoal") + " to " + yt.optInt("goal"), TEXT_DIM));
        }
        card.addView(body("+" + yt.optInt("day") + " views in the last "
                + yt.optDouble("daySpan", 24) + " h", TEXT));
    }

    private void addPipeline(JSONObject board) {
        LinearLayout card = card("PIPELINE");
        if (board == null || board.has("error")) {
            card.addView(body("The board file could not be read on the Mac", TEXT_DIM));
            return;
        }
        JSONArray owed = board.optJSONArray("owed");
        JSONArray inflight = board.optJSONArray("inflight");
        JSONArray blocked = board.optJSONArray("blocked");
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.addView(stat("VIDEOS", owed == null ? 0 : owed.length()), weighted());
        row.addView(stat("IN FLIGHT", inflight == null ? 0 : inflight.length()), weighted());
        row.addView(stat("WAITING ON YOU", blocked == null ? 0 : blocked.length()), weighted());
        card.addView(row, matchWrap());
        for (int i = 0; owed != null && i < Math.min(owed.length(), 4); i++) {
            JSONObject o = owed.optJSONObject(i);
            if (o == null) continue;
            card.addView(body(o.optString("title"), TEXT), topMargin(8));
            card.addView(bar(o.optInt("pct")), barParams());
            card.addView(body(o.optString("status"), TEXT_DIM));
        }
    }

    private void addClaude(JSONArray sessions) {
        LinearLayout card = card("CLAUDE");
        if (sessions == null || sessions.length() == 0) {
            card.addView(body("No recent Claude sessions", TEXT_DIM));
            return;
        }
        for (int i = 0; i < Math.min(sessions.length(), 4); i++) {
            JSONObject c = sessions.optJSONObject(i);
            if (c == null) continue;
            card.addView(body(c.optString("title"), TEXT), topMargin(i == 0 ? 0 : 8));
            int total = c.optInt("total");
            if (total > 0) {
                card.addView(bar(100 * c.optInt("done") / total), barParams());
            }
            String line = c.optBoolean("working") ? "Working" : "Idle " + c.optString("ago");
            if (total > 0) line += "  " + c.optInt("done") + "/" + total;
            String active = c.optString("active");
            if (!active.isEmpty() && !"null".equals(active)) line += "  " + active;
            card.addView(body(line, c.optBoolean("working") ? OK : TEXT_DIM));
        }
    }

    private void addAlerts(JSONArray alerts) {
        if (alerts == null || alerts.length() == 0) return;
        LinearLayout card = card("JUST HAPPENED");
        for (int i = 0; i < Math.min(alerts.length(), 4); i++) {
            JSONObject a = alerts.optJSONObject(i);
            if (a == null) continue;
            card.addView(body(a.optString("text") + "  " + a.optString("ago"), TEXT),
                    topMargin(i == 0 ? 0 : 4));
        }
    }

    private void addMac(JSONObject s) {
        LinearLayout card = card("MAC");
        JSONObject vol = s.optJSONObject("vol");
        boolean muted = vol != null && vol.optBoolean("muted");
        String level = vol == null || vol.isNull("v") ? "--" : String.valueOf(vol.optInt("v"));
        card.addView(body("Volume " + level + (muted ? "  muted" : "")
                + "   Load " + s.optInt("load") + "%", TEXT));

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        TextView down = button("VOL -", false);
        down.setOnClickListener(v -> act(action("vol", "d", -6), null));
        row.addView(down, new LayoutParams(0, dp(48), 1f));
        TextView mute = button(muted ? "UNMUTE" : "MUTE", false);
        mute.setOnClickListener(v -> act(action("mute", null, null), null));
        row.addView(mute, gap(new LayoutParams(0, dp(48), 1f)));
        TextView up = button("VOL +", false);
        up.setOnClickListener(v -> act(action("vol", "d", 6), null));
        row.addView(up, gap(new LayoutParams(0, dp(48), 1f)));
        card.addView(row, topMargin(8));
    }

    private void addApps(JSONArray apps) {
        if (apps == null || apps.length() == 0) return;
        LinearLayout card = card("LAUNCH ON MAC");
        LinearLayout row = null;
        for (int i = 0; i < apps.length(); i++) {
            final JSONObject app = apps.optJSONObject(i);
            if (app == null) continue;
            if (i % 3 == 0) {
                row = new LinearLayout(getContext());
                row.setOrientation(HORIZONTAL);
                card.addView(row, topMargin(i == 0 ? 0 : 8));
            }
            final String name = app.optString("label");
            TextView tile = button(name.toUpperCase(java.util.Locale.US), false);
            tile.setOnClickListener(v -> act(action("app", "key", app.optString("key")),
                    "Opening " + name + " on the Mac"));
            LayoutParams lp = new LayoutParams(0, dp(48), 1f);
            row.addView(tile, i % 3 == 0 ? lp : gap(lp));
        }
        // Pad the last row so tiles keep the same width.
        for (int i = apps.length(); i % 3 != 0; i++) {
            row.addView(new View(getContext()), gap(new LayoutParams(0, dp(48), 1f)));
        }
    }

    // ---- View helpers ----------------------------------------------------

    private LinearLayout card(String title) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setBackground(panel(SURFACE, HAIRLINE, 16));
        card.setPadding(dp(16), dp(12), dp(16), dp(16));
        TextView t = label(title);
        t.setPadding(0, 0, 0, dp(8));
        card.addView(t);
        content.addView(card, topMargin(8));
        return card;
    }

    private LinearLayout stat(String name, int count) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.addView(label(name));
        col.addView(value(String.valueOf(count), 20));
        return col;
    }

    private View bar(int pct) {
        final int clamped = Math.max(0, Math.min(100, pct));
        LinearLayout track = new LinearLayout(getContext());
        track.setBackground(panel(HAIRLINE, HAIRLINE, 3));
        View fill = new View(getContext());
        fill.setBackground(panel(ACCENT, ACCENT, 3));
        track.addView(fill, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, clamped));
        track.addView(new View(getContext()),
                new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - clamped));
        return track;
    }

    private LayoutParams barParams() {
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(4);
        return lp;
    }

    private TextView label(String text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(TEXT_DIM);
        t.setLetterSpacing(0.12f);
        t.setTypeface(Typeface.MONOSPACE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private TextView value(String text, int sp) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        t.setFontFeatureSettings("tnum");
        return t;
    }

    private TextView body(String text, int color) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(color);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private TextView message(String text) {
        TextView t = body(text, TEXT_DIM);
        t.setMaxLines(6);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView button(String text, boolean primary) {
        TextView b = new TextView(getContext());
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setLetterSpacing(0.08f);
        b.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setTextColor(primary ? BG : TEXT);
        b.setBackground(primary ? panel(ACCENT, ACCENT, 10) : panel(BG, HAIRLINE, 10));
        return b;
    }

    private GradientDrawable panel(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    private static LayoutParams matchWrap() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LayoutParams weighted() {
        return new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LayoutParams topMargin(int marginDp) {
        LayoutParams lp = matchWrap();
        lp.topMargin = dp(marginDp);
        return lp;
    }

    private LayoutParams gap(LayoutParams lp) {
        lp.leftMargin = dp(8);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
