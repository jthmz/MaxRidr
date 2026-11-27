package com.maxapacity.ride;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.JavascriptInterface;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** The calculator owns drafts. Only a confirmed installation is available to the recorder. */
final class InstalledGearing {
    private final SharedPreferences prefs;

    InstalledGearing(Context context) {
        prefs = context.getSharedPreferences("installed-gearing", Context.MODE_PRIVATE);
    }

    static String contextKey(Context c) {
        String model = PickerBike.path(c);
        Bike bike = Bike.get(c);
        if (bike.demo()) return "demo";
        if (model.startsWith("/builder/")) return model.substring("/builder/".length());
        if (bike.anyConfigured()) {
            return "linked:" + Uri.encode(bike.controllerName()) + "|" + Uri.encode(bike.batteryName());
        }
        return "manual";
    }

    static String atStart(Context c) {
        InstalledGearing registry = new InstalledGearing(c);
        String key = contextKey(c);
        String profile = registry.prefs.getString("selected." + key, "auto:" + key);
        return registry.prefs.getString("installed." + profile, null);
    }

    @JavascriptInterface public String select(String context, String profile) {
        prefs.edit().putString("selected." + context, profile).apply();
        return prefs.getString("installed." + profile, "");
    }

    @JavascriptInterface public String install(String context, String profile, String name,
                                               int front, int rear, String measurements) {
        if (front < 1 || front > 300 || rear < 1 || rear > 300 || name.isEmpty()) return "";
        try {
            String previous = prefs.getString("installed." + profile, "{}");
            JSONObject old = new JSONObject(previous);
            String id = old.optString("bikeId");
            JSONObject setup = new JSONObject().put("bikeId", id.isEmpty() ? UUID.randomUUID().toString() : id)
                    .put("name", name).put("front", front).put("rear", rear);
            JSONObject inputs = new JSONObject(measurements);
            for (String key : new String[]{"dia", "ctr", "prim", "rpm", "pitchMm"}) {
                double v = inputs.optDouble(key);
                if (Double.isFinite(v) && v > 0) setup.put(key, v);
            }
            String chain = inputs.optString("pitch");
            if (!chain.isEmpty()) setup.put("pitch", chain);
            String saved = setup.toString();
            return prefs.edit().putString("installed." + profile, saved)
                    .putString("selected." + context, profile).commit() ? saved : "";
        } catch (JSONException e) { return ""; }
    }

    static boolean beforeSwap(JSONObject ride, JSONObject older) {
        JSONObject a = ride.optJSONObject("gearing"), b = older.optJSONObject("gearing");
        return a != null && b != null && !a.optString("bikeId").isEmpty()
                && a.optString("bikeId").equals(b.optString("bikeId"))
                && older.optLong("start") < ride.optLong("start")
                && (a.optInt("front") != b.optInt("front") || a.optInt("rear") != b.optInt("rear"));
    }
}
