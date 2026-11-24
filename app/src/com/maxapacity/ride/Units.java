package com.maxapacity.ride;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Unit preference and number formatting shared by the dashboard and history. */
final class Units {

    private static final float MPS_TO_MPH = 2.23694f;
    private static final float MPS_TO_KMH = 3.6f;

    private Units() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ride", Context.MODE_PRIVATE);
    }

    static boolean metric(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains("metric")) return p.getBoolean("metric", true);
        String country = Locale.getDefault().getCountry();
        return !("US".equals(country) || "GB".equals(country));
    }

    static String speedLabel(Context c) {
        return metric(c) ? "KM/H" : "MPH";
    }

    static String distanceLabel(Context c) {
        return metric(c) ? "km" : "mi";
    }

    static String climbLabel(Context c) {
        return metric(c) ? "m" : "ft";
    }

    static String speed(Context c, float mps) {
        return String.valueOf(Math.round(mps * (metric(c) ? MPS_TO_KMH : MPS_TO_MPH)));
    }

    static String distance(Context c, double meters) {
        double v = metric(c) ? meters / 1000.0 : meters / 1609.344;
        return String.format(Locale.US, v < 100 ? "%.2f" : "%.1f", v);
    }

    static String climb(Context c, double meters) {
        return String.valueOf(Math.round(metric(c) ? meters : meters * 3.28084));
    }

    static String duration(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
