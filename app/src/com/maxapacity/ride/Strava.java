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
 * Strava connection: OAuth with the rider's own Strava API application, and
 * upload of finished rides as GPX.
 *
 * The API application's client id and secret are typed in on the phone and
 * kept in app-private storage; nothing is baked into the build.
 */
final class Strava {

    interface Callback {
        void onResult(boolean ok, String message);
    }

    /** Must match the redirect intent filter on StravaActivity. */
    static final String REDIRECT = "maxapacity://localhost";

    private static final String TOKEN_URL = "https://www.strava.com/oauth/token";
    private static final String UPLOAD_URL = "https://www.strava.com/api/v3/uploads";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Strava() {
    }

    static boolean isConnected(Context c) {
        return Units.prefs(c).contains("strava_refresh");
    }

    static void saveApp(Context c, String clientId, String clientSecret) {
        Units.prefs(c).edit()
                .putString("strava_client_id", clientId)
                .putString("strava_client_secret", clientSecret)
                .apply();
    }

    static void disconnect(Context c) {
        Units.prefs(c).edit()
                .remove("strava_access")
                .remove("strava_refresh")
                .remove("strava_expires")
                .apply();
    }

    /** Page where the rider approves access; Strava then redirects to REDIRECT. */
    static Uri authorizeUri(Context c) {
        return Uri.parse("https://www.strava.com/oauth/mobile/authorize").buildUpon()
                .appendQueryParameter("client_id",
                        Units.prefs(c).getString("strava_client_id", ""))
                .appendQueryParameter("redirect_uri", REDIRECT)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("approval_prompt", "auto")
                .appendQueryParameter("scope", "activity:write")
                .build();
    }

    /** Second half of sign-in: trade the one-time code for tokens. */
    static void exchangeCode(Context context, String code, Callback cb) {
        final Context c = context.getApplicationContext();
        IO.execute(() -> {
            boolean ok;
            String message;
            try {
                SharedPreferences p = Units.prefs(c);
                storeTokens(c, postForm(TOKEN_URL,
                        "client_id", p.getString("strava_client_id", ""),
                        "client_secret", p.getString("strava_client_secret", ""),
                        "code", code,
                        "grant_type", "authorization_code"));
                ok = true;
                message = "Strava connected. Rides upload automatically";
            } catch (IOException | JSONException e) {
                ok = false;
                message = "Strava sign-in failed: " + e.getMessage();
            }
            final boolean fOk = ok;
            final String fMessage = message;
            MAIN.post(() -> {
                if (fOk) uploadPending(c);
                cb.onResult(fOk, fMessage);
            });
        });
    }

    /** Sends every ride still waiting for Strava. Safe to call often. */
    static void uploadPending(Context context) {
        final Context c = context.getApplicationContext();
        if (!isConnected(c)) return;
        JSONArray rides = RideStore.load(c);
        for (int i = 0; i < rides.length(); i++) {
            JSONObject r = rides.optJSONObject(i);
            if (r == null || !RideStore.STRAVA_PENDING.equals(r.optString("strava"))) continue;
            final String file = r.optString("file");
            IO.execute(() -> {
                final String status = upload(c, file);
                if (status != null) {
                    MAIN.post(() -> RideStore.setStravaStatus(c, file, status));
                }
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
            HttpURLConnection conn = (HttpURLConnection) new URL(UPLOAD_URL).openConnection();
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
        storeTokens(c, postForm(TOKEN_URL,
                "client_id", p.getString("strava_client_id", ""),
                "client_secret", p.getString("strava_client_secret", ""),
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
