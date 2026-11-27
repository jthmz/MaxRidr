package com.maxapacity.ride;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One bounded HTTP request using the Ride client's existing wire format. */
final class PhoneDeckRequest {
    // The check request declares both filenames and sizes before either body arrives.
    private static final Map<String, Map<File, File>> batches = new HashMap<>();
    private static volatile long receivingUntil;
    private static final Map<String, Integer> totals = new HashMap<>();

    static synchronized String syncProgress() {
        if (batches.isEmpty()) return receiving() ? "Finishing sync" : "";
        if (!receiving()) return "Sync paused · Waiting for riding phone";
        int remaining = 0, total = 0;
        for (String rider : batches.keySet()) {
            remaining += batches.get(rider).size() / 2;
            total += totals.get(rider);
        }
        return "Receiving ride " + (total - remaining + 1) + " of " + total;
    }
    private static final java.util.concurrent.atomic.AtomicInteger uploads = new java.util.concurrent.atomic.AtomicInteger();

    private static final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();

    static long revision() { return revision.get(); }

    static boolean receiving() {
        return uploads.get() > 0 || android.os.SystemClock.elapsedRealtime() < receivingUntil;
    }

    private static synchronized void expect(String rider, Map<String, File> files) {
        Map<File, File> companions = new HashMap<>();
        int total = 0;
        for (Map.Entry<String, File> entry : files.entrySet()) {
            String name = entry.getKey();
            if (!name.endsWith(".gpx")) continue;
            File route = entry.getValue();
            File summary = files.get(name.substring(0, name.length() - 4) + ".json");
            if (summary == null) continue;
            total++;
            if (route.isFile() && summary.isFile()) continue;
            companions.put(route, summary);
            companions.put(summary, route);
            receivingUntil = android.os.SystemClock.elapsedRealtime() + 15000;
        }
        if (companions.isEmpty()) {
            batches.remove(rider);
            totals.remove(rider);
        } else {
            batches.put(rider, companions);
            totals.put(rider, total);
        }
    }

    static void handle(Context context, Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(8000);
            InputStream input = new BufferedInputStream(client.getInputStream());
            OutputStream output = client.getOutputStream();
            try {
                String[] start = line(input).split(" ");
                if (start.length != 3) return;
                Map<String,String> headers = new HashMap<>();
                for (int i = 0; ; i++) {
                    String h = line(input);
                    if (h.isEmpty()) break;
                    if (i >= 48 || h.indexOf(':') < 1) throw new IOException();
                    int colon = h.indexOf(':');
                    headers.put(h.substring(0, colon).toLowerCase(Locale.US), h.substring(colon+1).trim());
                }
                // Native clients never send Origin. Leave browser requests out of pairing.
                if (headers.containsKey("origin")) {
                    reply(output, 403, new JSONObject().put("ok", false));
                    return;
                }
                long size = Long.parseLong(headers.getOrDefault("content-length", "0"));
                Uri uri = Uri.parse(start[1]);
                String route = start[0] + " " + uri.getPath();
                SharedPreferences prefs = context.getSharedPreferences("home-deck", 0);
                JSONObject result = new JSONObject().put("ok", true);
                String token = headers.getOrDefault("x-deck-token", "");
                if (route.equals("POST /api/pair")) {
                    if (PhoneDeck.acceptCode(json(input, size).optString("code"))) {
                        String key = UUID.randomUUID().toString();
                        if (!prefs.edit().putBoolean("paired-" + tokenId(key), true).commit()) throw new IOException();
                        result.put("key", key);
                    } else result.put("ok", false).put("why", "wrong");
                } else if (!prefs.getBoolean("paired-" + tokenId(token), false)) {
                    reply(output, 401, new JSONObject().put("error", "Pair with this Deck"));
                    return;
                } else if (route.equals("GET /api/state")) {
                    result.put("uploads", true).put("phoneDeck", true);
                } else if (route.equals("POST /api/upload/check")) {
                    JSONObject manifest = json(input, size);
                    String device = manifest.optString("device").trim();
                    if (!device.isEmpty()) prefs.edit().putString("device-" + tokenId(token),
                            device.substring(0, Math.min(80, device.length()))).apply();
                    JSONArray files = manifest.getJSONArray("files");
                    Map<String, File> offered = new HashMap<>();
                    JSONArray have = new JSONArray(), need = new JSONArray();
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject file = files.getJSONObject(i);
                        String name = file.getString("name");
                        File target = target(context, token, name, file.getLong("size"));
                        offered.put(name, target);
                        (target.isFile() ? have : need).put(name);
                    }
                    expect(tokenId(token), offered);
                    result.put("have", have).put("need", need);
                } else if (route.equals("PUT /api/upload")) {
                    File target = target(context, token, uri.getQueryParameter("name"), size);
                    File tmp = File.createTempFile("incoming-", ".part", target.getParentFile());
                    uploads.incrementAndGet();
                    try {
                        try (FileOutputStream file = new FileOutputStream(tmp)) {
                            copy(input, file, size);
                            file.getFD().sync();
                        }
                        synchronized (PhoneDeckRequest.class) {
                            boolean fresh = !target.exists();
                            if (fresh) {
                                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                                SharedPreferences.Editor update = prefs.edit().putString("last-file", target.getName());
                                String rider = tokenId(token);
                                Map<File, File> companions = batches.get(rider);
                                File companion = companions == null ? null : companions.get(target);
                                if (companion != null && companion.isFile()) {
                                    update.putLong("last-ride-at", System.currentTimeMillis());
                                    companions.remove(companion);
                                    companions.remove(target);
                                    if (companions.isEmpty()) { batches.remove(rider); totals.remove(rider); }
                                    if (batches.isEmpty()) receivingUntil = 0;
                                } else if (companion != null) {
                                    receivingUntil = android.os.SystemClock.elapsedRealtime() + 15000;
                                }
                                update.apply();
                                revision.incrementAndGet();
                            }
                            result.put("saved", fresh);
                        }
                    } finally { uploads.decrementAndGet(); tmp.delete(); }
                } else {
                    reply(output, 404, result.put("ok", false));
                    return;
                }
                reply(output, 200, result);
            } catch (Exception invalid) {
                reply(output, 400, new JSONObject().put("ok", false).put("why", "Could not receive request"));
            }
        } catch (Exception disconnected) { /* The sender retries incomplete transfers. */ }
    }

    private static String tokenId(String token) {
        try {
            java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
            return android.util.Base64.encodeToString(hash.digest(token.getBytes(StandardCharsets.UTF_8)),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static File target(Context c, String token, String name, long size) throws IOException {
        if (name == null || !name.matches("[a-zA-Z0-9_. -]{1,160}\\.(gpx|json)")
                || size < 0 || size > 128L*1024*1024) throw new IOException();
        File root = c.getExternalFilesDir("Received rides");
        if (root == null) throw new IOException();
        // Keep different sizes of the same filename, as the Mac Deck does.
        File dir = new File(root, tokenId(token)
                + "/" + size);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException();
        return new File(dir, name);
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        while (line.length() < 8192) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            if (b == '\n') return line.toString();
            if (b != '\r') line.append((char)b);
        }
        throw new IOException();
    }

    private static JSONObject json(InputStream input, long size) throws Exception {
        if (size < 0 || size > 1024*1024) throw new IOException();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        copy(input, body, size);
        return new JSONObject(body.toString("UTF-8"));
    }

    private static void copy(InputStream input, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[16384];
        while (size > 0) {
            int n = input.read(buffer, 0, (int)Math.min(buffer.length, size));
            if (n < 0) throw new EOFException();
            out.write(buffer, 0, n);
            size -= n;
        }
    }

    private static void reply(OutputStream out, int status, JSONObject json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " Response\r\nContent-Type: application/json\r\n"
                + "Connection: close\r\nContent-Length: " + bytes.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }
}
