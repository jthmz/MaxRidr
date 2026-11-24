package com.maxapacity.ride;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The wire side of the link to the MAXAPACITY Deck (the Mac service in
 * deck/), exactly as deck/APP-LINK.md describes it: plain HTTP with JSON, on
 * the home network or down a USB cable. Finding the Deck and deciding when to
 * talk to it is {@link Deck}'s job; this class only speaks.
 *
 * No Android classes, so it runs against a real Deck on any computer. Every
 * method blocks: never call from the main thread.
 */
final class DeckLink {

    private DeckLink() {
    }

    static final int PORT = 8791;
    /** The phone's own end of a USB cable; the Deck keeps the link up from the Mac. */
    static final String USB = "127.0.0.1:" + PORT;

    /** It answered and lets this phone in. */
    static final int HOME = 0;
    /** A Deck is there, but this phone has to be paired with its 6-digit code. */
    static final int UNPAIRED = 1;
    /** A Deck is there, but it is not open to other screens on the network. */
    static final int SHUT = 2;
    /** Nothing answered, or what answered is not a Deck. */
    static final int ABSENT = 3;

    private static final int QUICK_MS = 2500;
    private static final int PATIENT_MS = 15000;

    /** What one look at an address found. */
    static final class Probe {
        final int result;
        /** Whether this Deck takes rides and media right now. */
        final boolean uploads;
        final JSONObject state;

        Probe(int result, boolean uploads, JSONObject state) {
            this.result = result;
            this.uploads = uploads;
            this.state = state;
        }
    }

    /** "Am I home?": does the Deck at this address answer within a couple of seconds. */
    static Probe probe(String address, String key) {
        try {
            Reply r = request("GET", address, "/api/state", key, null, QUICK_MS);
            if (r.code == 200 && r.json != null && r.json.has("uploads")) {
                return new Probe(HOME, r.json.optBoolean("uploads"), r.json);
            }
            // Only a Deck answers "not paired"; anything else on that port is not one.
            if (r.code == 401 && r.json != null && r.json.has("error")) {
                return new Probe(UNPAIRED, false, null);
            }
            if (r.code == 403 && "not allowed".equals(r.text.trim())) {
                return new Probe(SHUT, false, null);
            }
        } catch (IOException | RuntimeException e) {
            // Not there, or not an address at all. Either way: no Deck here.
        }
        return new Probe(ABSENT, false, null);
    }

    /** How pairing went. */
    static final class Pairing {
        final boolean ok;
        /** Sent with every later request; null when the Deck already lets this phone in. */
        final String key;
        /** "wrong" (code), "closed" (no code is showing), or what went wrong. */
        final String why;

        Pairing(boolean ok, String key, String why) {
            this.ok = ok;
            this.key = key;
            this.why = why;
        }
    }

    /** Trades the 6-digit code showing under Add a screen for this phone's key. */
    static Pairing pair(String address, String code, String name) {
        try {
            JSONObject body = new JSONObject().put("code", code).put("app", true).put("name", name);
            Reply r = request("POST", address, "/api/pair", null, body, PATIENT_MS);
            if (r.code != 200 || r.json == null) return new Pairing(false, null, "unreachable");
            if (!r.json.optBoolean("ok")) {
                return new Pairing(false, null, r.json.optString("why", "wrong"));
            }
            String key = r.json.optString("key", "");
            return new Pairing(true, key.isEmpty() ? null : key, null);
        } catch (IOException | JSONException | RuntimeException e) {
            return new Pairing(false, null, "unreachable");
        }
    }

    /** Where a file's bytes come from when it is time to send them. */
    interface Source {
        InputStream open() throws IOException;
    }

    /** One file offered to the Deck. */
    static final class Item {
        final String name;
        final long size;
        final long mtimeSec;
        /** "ride" or "media": decides the folder it lands in on the Mac. */
        final String kind;
        final Source source;

        Item(String name, long size, long mtimeSec, String kind, Source source) {
            this.name = name;
            this.size = size;
            this.mtimeSec = mtimeSec;
            this.kind = kind;
            this.source = source;
        }
    }

    /** What became of a batch of files. */
    static final class Outcome {
        /** On the Mac now, whether sent this time or already there. */
        final Set<String> onMac = new HashSet<>();
        /** How many of those actually travelled this time. */
        int sentNow;
        /** Not taken, with the Deck's own reason, by file name. */
        final Map<String, String> refused = new LinkedHashMap<>();
        /** The run stopped early: say why, and try again later. */
        String stopped;
    }

    /**
     * Asks the Deck which of these files it still wants, then sends those one
     * at a time. Nothing is sent twice: a file the Mac already holds comes
     * back as on the Mac without travelling.
     */
    static Outcome send(String address, String key, String device, List<Item> items) {
        Outcome out = new Outcome();
        if (items.isEmpty()) return out;
        List<String> need = new ArrayList<>();
        try {
            JSONArray files = new JSONArray();
            for (Item it : items) {
                files.put(new JSONObject().put("name", it.name).put("size", it.size)
                        .put("mtime", it.mtimeSec).put("kind", it.kind));
            }
            JSONObject body = new JSONObject().put("device", device).put("files", files);
            Reply r = request("POST", address, "/api/upload/check", key, body, PATIENT_MS);
            if (r.code != 200 || r.json == null || !r.json.optBoolean("ok")) {
                out.stopped = r.code == 401 ? "This phone is no longer paired with the Deck"
                        : why(r, "The Deck did not say which files it wants");
                return out;
            }
            JSONArray have = r.json.optJSONArray("have");
            for (int i = 0; have != null && i < have.length(); i++) out.onMac.add(have.optString(i));
            JSONArray wanted = r.json.optJSONArray("need");
            for (int i = 0; wanted != null && i < wanted.length(); i++) need.add(wanted.optString(i));
            JSONArray no = r.json.optJSONArray("refused");
            for (int i = 0; no != null && i < no.length(); i++) {
                JSONObject f = no.optJSONObject(i);
                if (f != null) out.refused.put(f.optString("name"), f.optString("why"));
            }
        } catch (IOException | JSONException | RuntimeException e) {
            out.stopped = "The Deck stopped answering";
            return out;
        }

        int n = need.size();
        int i = 0;
        for (Item it : items) {
            if (!need.contains(it.name)) continue;
            i++;
            try {
                Reply r = put(address, key, device, it, i, n);
                if (r.code == 200 && r.json != null && r.json.optBoolean("ok")) {
                    out.onMac.add(it.name);
                    if (r.json.optBoolean("saved")) out.sentNow++;
                } else if (r.code == 400 || r.code == 507 || r.code >= 500 || r.code == 401) {
                    // Incomplete, drive missing or full, or the Deck in trouble:
                    // nothing wrong with the file, so stop and try again later.
                    out.stopped = r.code == 401 ? "This phone is no longer paired with the Deck"
                            : why(r, "The Deck could not take " + it.name);
                    return out;
                } else {
                    out.refused.put(it.name, why(r, "The Deck did not accept it"));
                }
            } catch (IOException | RuntimeException e) {
                out.stopped = "The connection to the Deck dropped part-way";
                return out;
            }
        }
        return out;
    }

    private static String why(Reply r, String fallback) {
        String why = r.json == null ? "" : r.json.optString("why", "");
        if (why.isEmpty()) return fallback;
        return Character.toUpperCase(why.charAt(0)) + why.substring(1);
    }

    // ---- HTTP ---------------------------------------------------------------

    private static final class Reply {
        int code;
        String text = "";
        JSONObject json;
    }

    private static HttpURLConnection open(String method, String address, String path, String key,
            int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://" + address + path)
                .openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(Math.max(timeoutMs, 6000));
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(method);
        if (key != null && !key.isEmpty()) conn.setRequestProperty("x-deck-token", key);
        return conn;
    }

    private static Reply request(String method, String address, String path, String key,
            JSONObject body, int timeoutMs) throws IOException {
        HttpURLConnection conn = open(method, address, path, key, timeoutMs);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
            }
            return read(conn);
        } finally {
            conn.disconnect();
        }
    }

    /** One file as the plain body of a PUT, its exact length announced first. */
    private static Reply put(String address, String key, String device, Item it, int i, int n)
            throws IOException {
        String query = "?name=" + enc(it.name) + "&mtime=" + it.mtimeSec + "&device=" + enc(device)
                + "&kind=" + enc(it.kind) + "&i=" + i + "&n=" + n;
        HttpURLConnection conn = open("PUT", address, "/api/upload" + query, key, PATIENT_MS);
        // The Deck answers only once the whole file is safely written.
        conn.setReadTimeout(120000);
        try {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setFixedLengthStreamingMode(it.size);
            try (OutputStream out = conn.getOutputStream(); InputStream in = it.source.open()) {
                byte[] chunk = new byte[1 << 16];
                long left = it.size;
                int got;
                while (left > 0 && (got = in.read(chunk, 0, (int) Math.min(chunk.length, left))) > 0) {
                    out.write(chunk, 0, got);
                    left -= got;
                }
                if (left > 0) throw new IOException("the file is shorter than it was");
            }
            return read(conn);
        } finally {
            conn.disconnect();
        }
    }

    private static Reply read(HttpURLConnection conn) throws IOException {
        Reply r = new Reply();
        r.code = conn.getResponseCode();
        InputStream in = r.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in != null) {
            try (InputStream stream = in) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int got;
                while ((got = stream.read(chunk)) > 0) {
                    buf.write(chunk, 0, got);
                    if (buf.size() > (4 << 20)) throw new IOException("the answer is too long");
                }
                r.text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        if (r.text.startsWith("{")) {
            try {
                r.json = new JSONObject(r.text);
            } catch (JSONException e) {
                // Left null: the caller treats it as not a Deck.
            }
        }
        return r;
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }
}
