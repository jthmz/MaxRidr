package com.maxapacity.ride;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Copilot backend: Google's Gemini API, called with the rider's own API key
 * (typed in on the phone, kept in app-private storage, never in the build).
 */
final class Gemini {

    interface Callback {
        void onReply(String text, boolean ok);
    }

    /** One turn of the conversation. */
    static final class Turn {
        final boolean fromRider;
        final String text;

        Turn(boolean fromRider, String text) {
            this.fromRider = fromRider;
            this.text = text;
        }
    }

    static final String DEFAULT_MODEL = "gemini-flash-latest";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Gemini() {
    }

    static boolean hasKey(Context c) {
        return !Units.prefs(c).getString("gemini_key", "").isEmpty();
    }

    /** Sends the conversation so far and delivers the reply on the main thread. */
    static void ask(Context context, List<Turn> conversation, Callback cb) {
        final Context c = context.getApplicationContext();
        final String key = Units.prefs(c).getString("gemini_key", "");
        final String model = Units.prefs(c).getString("gemini_model", DEFAULT_MODEL);
        final String body;
        try {
            JSONArray contents = new JSONArray();
            for (Turn t : conversation) {
                contents.put(new JSONObject()
                        .put("role", t.fromRider ? "user" : "model")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", t.text))));
            }
            body = new JSONObject()
                    .put("system_instruction", new JSONObject().put("parts",
                            new JSONArray().put(new JSONObject().put("text", persona(c)))))
                    .put("contents", contents)
                    .toString();
        } catch (JSONException e) {
            cb.onReply("Could not build the request", false);
            return;
        }

        IO.execute(() -> {
            String text;
            boolean ok = false;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(
                        "https://generativelanguage.googleapis.com/v1beta/models/"
                                + model + ":generateContent").openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-goog-api-key", key);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                String raw = read(code < 400 ? conn.getInputStream() : conn.getErrorStream());
                conn.disconnect();
                if (code >= 400) {
                    text = code == 400 || code == 403
                            ? "Gemini rejected the API key. Check it in Settings"
                            : code == 404
                            ? "Gemini does not know the model \"" + model + "\""
                            : code == 429
                            ? "Gemini is rate-limiting this key. Try again in a minute"
                            : "Gemini answered with error " + code;
                } else {
                    text = replyText(new JSONObject(raw));
                    ok = text != null;
                    if (!ok) text = "Gemini returned no answer for that";
                }
            } catch (IOException e) {
                text = "No connection. Copilot needs to be online";
            } catch (JSONException e) {
                text = "Could not read Gemini's answer";
            }
            final String fText = text;
            final boolean fOk = ok;
            MAIN.post(() -> cb.onReply(fText, fOk));
        });
    }

    private static String replyText(JSONObject json) {
        JSONArray candidates = json.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return null;
        JSONObject content = candidates.optJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            sb.append(parts.optJSONObject(i).optString("text"));
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** Who the copilot is, plus what the dashboard knows right now. */
    private static String persona(Context c) {
        RideState s = RideState.get();
        StringBuilder sb = new StringBuilder()
                .append("You are the MAXAPACITY Copilot, built into a ride phone mounted on an ")
                .append("electric dirt bike (Surron, Talaria, DRAX class). The rider reads you ")
                .append("at a glance, often in gloves: answer in plain text, no markdown, three ")
                .append("short sentences at most unless asked for detail. You know e-moto ")
                .append("setup, gearing, controllers, batteries, trail riding and maintenance. ")
                .append("When a question needs figures you do not have (this bike's exact ")
                .append("parts, live battery state), say so instead of guessing. If the rider ")
                .append("appears to be moving, keep it to one sentence.\n\nDashboard now: ");
        if (s.isRecording()) {
            sb.append("ride in progress, ")
                    .append(Units.distance(c, s.distanceM())).append(' ')
                    .append(Units.distanceLabel(c)).append(", moving time ")
                    .append(Units.duration(s.movingMs())).append(", top ")
                    .append(Units.speed(c, s.topSpeedMps())).append(' ')
                    .append(Units.speedLabel(c)).append(", current speed ")
                    .append(Units.speed(c, s.speedMps())).append(", stops ")
                    .append(s.stops()).append(", climb ")
                    .append(Units.climb(c, s.climbM())).append(' ')
                    .append(Units.climbLabel(c)).append('.');
        } else {
            sb.append("no ride in progress.");
        }
        Bike bike = Bike.get(c);
        AntBms.Data b = bike.battery();
        if (b != null) {
            sb.append(String.format(java.util.Locale.US,
                    " Battery (live from the BMS): %d%% charge, %.1f V, %.1f A, %d cells,"
                            + " cell spread %d mV, %.1f Ah remaining.",
                    b.soc, b.volts, b.amps, b.cellVolts.length,
                    Math.round(b.deltaCellV() * 1000), b.remainingAh));
            if (b.hasProblem()) sb.append(" BMS reports: ").append(b.problems).append('.');
        }
        Fardriver.Live l = bike.controller();
        if (l != null) {
            sb.append(String.format(java.util.Locale.US,
                    " Controller (live): %.1f kW, %d rpm, controller %d C, %s speed mode.",
                    l.powerW() / 1000f, l.rpm, l.mosfetTempC, l.speedModeName()));
            if (l.faults != 0) sb.append(" Controller faults: ").append(l.faultNames()).append('.');
        }
        JSONArray rides = RideStore.load(c);
        if (rides.length() > 0) {
            sb.append(" Recent rides, newest first: ");
            for (int i = rides.length() - 1; i >= Math.max(0, rides.length() - 5); i--) {
                JSONObject r = rides.optJSONObject(i);
                if (r != null) {
                    sb.append(RideStore.describe(c, r).replace('\n', ' ')).append("; ");
                }
            }
        }
        sb.append(" The rider uses ").append(Units.metric(c) ? "metric" : "US").append(" units.");
        return sb.toString();
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream stream = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = stream.read(chunk)) > 0) buf.write(chunk, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
