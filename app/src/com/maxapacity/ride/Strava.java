package com.maxapacity.ride;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Strava connection: sign-in, and upload of finished rides as GPX.
 *
 * Strava only lets a registered API application sign riders in. A build made
 * with that application's client id and secret in its environment carries
 * them (see build.gradle), and connecting is then one tap and Strava's own
 * Authorize button. A build without them asks the rider for the id and
 * secret of an application of their own, as every build once did; a phone
 * connected that way keeps using its own.
 */
final class Strava {

    interface Callback {
        void onResult(boolean ok, String message);
    }

    /** What the screen showing Strava's state is told. Main thread. */
    interface Screen {
        /** Something it shows has changed: a sign-in under way, a ride sent. */
        void stravaChanged();

        /** A sign-in ended, well or badly, with a line for the rider. */
        void stravaSignIn(boolean ok, String message);
    }

    /** Must match the redirect intent filter on StravaActivity. */
    static final String REDIRECT = "maxapacity://localhost";

    /** Not final so the tests can stand in for Strava on this machine. */
    static String tokenUrl = "https://www.strava.com/oauth/token";
    static String uploadUrl = "https://www.strava.com/api/v3/uploads";

    /** The scope uploads need. The rider can untick it on Strava's page. */
    private static final String SCOPE = "activity:write";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Rides on their way up, so asking twice uploads once. Main thread. */
    private static final java.util.Set<String> sending = new java.util.HashSet<>();

    /** The API application this build carries: {id, secret}, or null. */
    private static String[] builtIn;
    private static boolean builtInRead;
    /** The screen showing Strava's state, while there is one. */
    private static Screen screen;
    private static boolean connecting;

    private Strava() {
    }

    static boolean isConnected(Context c) {
        return Units.prefs(c).contains("strava_refresh");
    }

    /** True from the moment Strava hands the rider back until the sign-in is settled. */
    static boolean isConnecting() {
        return connecting;
    }

    /** True when this build can sign a rider in with nothing typed. */
    static boolean hasBuiltInApp(Context c) {
        return builtInApp(c) != null;
    }

    private static synchronized String[] builtInApp(Context c) {
        if (!builtInRead) {
            builtInRead = true;
            builtIn = null;
            // Written into the build's assets by build.gradle; absent otherwise.
            try (InputStream in = c.getAssets().open("strava.properties")) {
                java.util.Properties p = new java.util.Properties();
                p.load(in);
                String id = p.getProperty("client_id", "").trim();
                String secret = p.getProperty("client_secret", "").trim();
                if (!id.isEmpty() && !secret.isEmpty()) builtIn = new String[] {id, secret};
            } catch (IOException e) {
                // No application in this build.
            }
        }
        return builtIn;
    }

    /** For the tests: the application the build carries, or null for none. */
    static synchronized void setBuiltInApp(String clientId, String clientSecret) {
        builtInRead = true;
        builtIn = clientId == null ? null : new String[] {clientId, clientSecret};
    }

    /**
     * The application to talk to Strava as: the rider's own if this phone was
     * set up with one, else the build's. Tokens only work with the
     * application that issued them, so a phone keeps the one it connected with.
     */
    private static String[] app(Context c) {
        SharedPreferences p = Units.prefs(c);
        String id = p.getString("strava_client_id", "");
        String secret = p.getString("strava_client_secret", "");
        if (!id.isEmpty() && !secret.isEmpty()) return new String[] {id, secret};
        String[] built = builtInApp(c);
        return built != null ? built : new String[] {"", ""};
    }

    /** The rider's own API application, for a build that carries none. */
    static void saveApp(Context c, String clientId, String clientSecret) {
        Units.prefs(c).edit()
                .putString("strava_client_id", clientId)
                .putString("strava_client_secret", clientSecret)
                .apply();
    }

    /** The screen to keep up to date. */
    static void setScreen(Screen s) {
        screen = s;
    }

    /** That screen has gone. A newer one that took its place is left alone. */
    static void clearScreen(Screen s) {
        if (screen == s) screen = null;
    }

    /** True while a screen is up that will show how a sign-in went. */
    static boolean hasScreen() {
        return screen != null;
    }

    /** A ride to send as soon as the rider has connected, or null for none. */
    static void sendOnceConnected(Context c, String file) {
        SharedPreferences.Editor e = Units.prefs(c).edit();
        if (file == null) e.remove("strava_then");
        else e.putString("strava_then", file);
        e.apply();
    }

    static void disconnect(Context c) {
        Units.prefs(c).edit()
                .remove("strava_access")
                .remove("strava_refresh")
                .remove("strava_expires")
                .apply();
    }

    /**
     * First half of sign-in: the page where the rider approves access. It
     * opens in the Strava app when that is installed, so there is nothing to
     * type; Strava then sends the rider back to REDIRECT.
     *
     * @param ownApp true when the rider has just entered an application of
     *               their own; false to use the one the build carries
     */
    static Uri begin(Context c, boolean ownApp) {
        SharedPreferences.Editor e = Units.prefs(c).edit();
        if (!ownApp) e.remove("strava_client_id").remove("strava_client_secret");
        // Sent out and expected back, so that only the sign-in this phone
        // started can finish: without it any page or app could hand the app
        // a code for somebody else's Strava account.
        byte[] random = new byte[16];
        new java.security.SecureRandom().nextBytes(random);
        StringBuilder state = new StringBuilder();
        for (byte b : random) state.append(String.format("%02x", b));
        e.putString("strava_state", state.toString()).commit();
        return Uri.parse("https://www.strava.com/oauth/mobile/authorize").buildUpon()
                .appendQueryParameter("client_id", app(c)[0])
                .appendQueryParameter("redirect_uri", REDIRECT)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("approval_prompt", "auto")
                .appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("state", state.toString())
                .build();
    }

    /**
     * Second half of sign-in: Strava has sent the rider back. Checks what came
     * back and trades the one-time code for tokens. The result goes to the
     * screen set with {@link #setScreen}, and to {@code cb} if one is given.
     * Main thread.
     */
    static void finish(Context context, Uri redirect, final Callback cb) {
        final Context c = context.getApplicationContext();
        SharedPreferences p = Units.prefs(c);
        String expected = p.getString("strava_state", "");
        if (redirect == null || expected.isEmpty()
                || !expected.equals(redirect.getQueryParameter("state"))) {
            // Not the answer to a sign-in started here. It changes nothing:
            // what is connected stays, and a sign-in under way can still finish.
            String stray = "That Strava sign-in did not start on this phone. Tap Connect to"
                    + " try again";
            if (cb != null) cb.onResult(false, stray);
            if (screen != null) screen.stravaSignIn(false, stray);
            return;
        }
        // The answer to ours, and it is good once.
        p.edit().remove("strava_state").apply();
        String code = redirect.getQueryParameter("code");
        String scope = redirect.getQueryParameter("scope");
        String problem = null;
        if (code == null || code.isEmpty()) {
            problem = "Strava sign-in was cancelled";
        } else if (scope != null && !java.util.Arrays.asList(scope.split(",")).contains(SCOPE)) {
            problem = "Strava was not allowed to take uploads. Tap Connect again and leave"
                    + " the upload box ticked";
        }
        if (problem != null) {
            settle(c, false, problem, cb);
            return;
        }
        connecting = true;
        if (screen != null) screen.stravaChanged();
        IO.execute(() -> {
            boolean ok;
            String message;
            try {
                String[] app = app(c);
                storeTokens(c, postForm(tokenUrl,
                        "client_id", app[0],
                        "client_secret", app[1],
                        "code", code,
                        "grant_type", "authorization_code"));
                ok = true;
                message = "Strava connected. Rides upload on their own";
            } catch (IOException | JSONException e) {
                ok = false;
                message = "Strava sign-in failed: " + e.getMessage();
            }
            final boolean fOk = ok;
            final String fMessage = message;
            MAIN.post(() -> settle(c, fOk, fMessage, cb));
        });
    }

    private static void settle(Context c, boolean ok, String message, Callback cb) {
        connecting = false;
        SharedPreferences p = Units.prefs(c);
        String then = p.getString("strava_then", "");
        p.edit().remove("strava_then").apply();
        if (ok) {
            // The ride the rider asked to send is why they connected.
            if (!then.isEmpty()) RideStore.setStravaStatus(c, then, RideStore.STRAVA_PENDING);
            uploadPending(c);
        }
        if (cb != null) cb.onResult(ok, message);
        if (screen != null) screen.stravaSignIn(ok, message);
    }

    /** How many saved rides have never been offered to Strava. */
    static int unsentCount(Context c) {
        int n = 0;
        JSONArray rides = RideStore.load(c);
        for (int i = 0; i < rides.length(); i++) {
            JSONObject r = rides.optJSONObject(i);
            if (r != null && RideStore.STRAVA_NONE.equals(r.optString("strava"))) n++;
        }
        return n;
    }

    /** Sends every ride recorded before the rider connected. */
    static void queueUnsent(Context c) {
        JSONArray rides = RideStore.load(c);
        for (int i = 0; i < rides.length(); i++) {
            JSONObject r = rides.optJSONObject(i);
            if (r != null && RideStore.STRAVA_NONE.equals(r.optString("strava"))) {
                RideStore.setStravaStatus(c, r.optString("file"), RideStore.STRAVA_PENDING);
            }
        }
        uploadPending(c);
    }

    /** Sends every ride still waiting for Strava. Safe to call often. Main thread. */
    static void uploadPending(Context context) {
        final Context c = context.getApplicationContext();
        if (!isConnected(c)) return;
        JSONArray rides = RideStore.load(c);
        for (int i = 0; i < rides.length(); i++) {
            JSONObject r = rides.optJSONObject(i);
            if (r == null || !RideStore.STRAVA_PENDING.equals(r.optString("strava"))) continue;
            final String file = r.optString("file");
            if (!sending.add(file)) continue;
            IO.execute(() -> {
                String result;
                try {
                    result = upload(c, file);
                } catch (RuntimeException e) {
                    result = null;
                }
                final String status = result;
                MAIN.post(() -> {
                    sending.remove(file);
                    if (status == null) return;
                    RideStore.setStravaStatus(c, file, status);
                    if (screen != null) screen.stravaChanged();
                });
            });
        }
    }

    /** Marks a ride for (re)sending and starts the upload. */
    static void queue(Context c, String file) {
        RideStore.setStravaStatus(c, file, RideStore.STRAVA_PENDING);
        uploadPending(c);
    }

    /**
     * @return the new status for the ride, or null to leave it pending
     *         (offline, or Strava unreachable) and retry later
     */
    private static String upload(Context c, String file) {
        File gpx = new File(RideStore.dir(c), file);
        if (!gpx.isFile()) return RideStore.STRAVA_FAILED;
        try {
            String token = accessToken(c);
            String boundary = "----maxapacity" + System.nanoTime();
            HttpURLConnection conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream out = conn.getOutputStream()) {
                writeField(out, boundary, "data_type", "gpx");
                writeField(out, boundary, "name", "MAXAPACITY ride");
                writeField(out, boundary, "sport_type", "EBikeRide");
                writeField(out, boundary, "external_id", file);
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\";"
                        + " filename=\"" + file + "\"\r\nContent-Type: application/gpx+xml\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                Files.copy(gpx.toPath(), out);
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code == 201) return RideStore.STRAVA_UPLOADED;
            // 401 and 429 (expired sign-in, rate limit) and server errors are
            // worth another try later; any other 4xx is a rejection.
            if (code == 401 || code == 429 || code >= 500) return null;
            return RideStore.STRAVA_FAILED;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private static void writeField(OutputStream out, String boundary, String name, String value)
            throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    /** A valid access token, refreshed first when it is about to expire. */
    private static String accessToken(Context c) throws IOException, JSONException {
        SharedPreferences p = Units.prefs(c);
        long now = System.currentTimeMillis() / 1000;
        if (p.getLong("strava_expires", 0) - 120 > now) {
            return p.getString("strava_access", "");
        }
        String[] app = app(c);
        storeTokens(c, postForm(tokenUrl,
                "client_id", app[0],
                "client_secret", app[1],
                "refresh_token", p.getString("strava_refresh", ""),
                "grant_type", "refresh_token"));
        return Units.prefs(c).getString("strava_access", "");
    }

    private static void storeTokens(Context c, JSONObject json) throws JSONException {
        Units.prefs(c).edit()
                .putString("strava_access", json.getString("access_token"))
                .putString("strava_refresh", json.getString("refresh_token"))
                .putLong("strava_expires", json.getLong("expires_at"))
                .commit();
    }

    private static JSONObject postForm(String url, String... pairs)
            throws IOException, JSONException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) body.append('&');
            body.append(URLEncoder.encode(pairs[i], "UTF-8")).append('=')
                    .append(URLEncoder.encode(pairs[i + 1], "UTF-8"));
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        try (InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (in != null) {
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            }
            if (code >= 400) throw new IOException("Strava answered " + code);
            return new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            conn.disconnect();
        }
    }
}
