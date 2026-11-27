package com.maxapacity.ride;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;

/** Distance-spaced GPS samples keep the comparison independent of recording speed. */
final class BenchmarkLoop {
    private static final int INTERVALS = 64;
    final SharedPreferences prefs;
    final String bike;

    BenchmarkLoop(Context c, JSONObject ride) {
        prefs = c.getSharedPreferences("deck-benchmarks", Context.MODE_PRIVATE);
        bike = ride.optJSONObject("gearing").optString("bikeId");
    }

    static JSONObject shape(List<double[]> points) {
        if (points.size() < 3) return null;
        double[] along = new double[points.size()];
        for (int i = 1; i < points.size(); i++) along[i] = along[i-1] + meters(points.get(i-1), points.get(i));
        double length = along[along.length-1];
        if (length < 200) return null;
        JSONArray samples = new JSONArray();
        try {
            int end = 1;
            for (int i = 0; i <= INTERVALS; i++) {
                double target = length * i / INTERVALS;
                while (end < along.length - 1 && along[end] < target) end++;
                double span = along[end] - along[end-1];
                double f = span == 0 ? 0 : (target - along[end-1]) / span;
                double[] a = points.get(end-1), b = points.get(end);
                samples.put(new JSONArray().put(a[0] + f * (b[0]-a[0])).put(a[1] + f * (longitudeDelta(a[1], b[1]))));
            }
            return new JSONObject().put("length", length).put("samples", samples);
        } catch (JSONException invalid) { return null; }
    }

    private static double longitudeDelta(double from, double to) {
        return Math.IEEEremainder(to - from, 360.0);
    }

    private static double meters(double[] a, double[] b) {
        double east = (longitudeDelta(a[1], b[1])) * Math.cos(Math.toRadians((a[0]+b[0])/2));
        return 111320 * Math.hypot(east, b[0]-a[0]);
    }

    static boolean matches(JSONObject a, JSONObject b) {
        if (a == null || b == null) return false;
        try {
            double length = a.getDouble("length");
            if (Math.abs(b.getDouble("length") - length) > .10 * length) return false;
            JSONArray left = a.getJSONArray("samples"), right = b.getJSONArray("samples");
            double tolerance = Math.min(90, Math.max(25, length * .015));
            int outside = 0;
            for (int i = 0; i <= INTERVALS; i++) {
                JSONArray p = left.getJSONArray(i), q = right.getJSONArray(i);
                double d = meters(new double[]{p.getDouble(0), p.getDouble(1)}, new double[]{q.getDouble(0), q.getDouble(1)});
                if ((i == 0 || i == INTERVALS) && d > tolerance) return false;
                if (d > 2 * tolerance || (d > tolerance && ++outside > 4)) return false;
            }
            return true;
        } catch (JSONException invalid) { return false; }
    }

    JSONObject pin() {
        try { return new JSONObject(prefs.getString("pin." + bike, "")); }
        catch (JSONException missing) { return null; }
    }

    void pin(String name, JSONObject ride, JSONObject shape) {
        try {
            prefs.edit().putString("pin." + bike, new JSONObject().put("name", name)
                    .put("ride", ride).put("shape", shape).toString()).apply();
        } catch (JSONException invalid) { throw new IllegalArgumentException(invalid); }
    }

    static String id(JSONObject r) { return r.optLong("start") + "/" + r.optString("file"); }

    String note(String key) { return prefs.getString(bike + "/" + key, ""); }
    void note(String key, String value) { prefs.edit().putString(bike + "/" + key, value).apply(); }
}
