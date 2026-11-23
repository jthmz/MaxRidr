package com.maxapacity.ride;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * MAXAPACITY home screen: speedometer, trip stats, ride recorder, four
 * assignable ride-app slots, site tools and an app drawer.
 */
public final class RideActivity extends Activity
        implements RideState.Listener, LocationListener {

    // Brand palette: graphite surfaces, grayscale text ramp, one voltage accent,
    // semantic green/red/amber for status only.
    private static final int BG = 0xFF131316;
    private static final int SURFACE = 0xFF1C1C21;
    private static final int HAIRLINE = 0xFF2E2E36;
    private static final int TEXT = 0xFFF7F5F0;
    private static final int TEXT_DIM = 0xFF9A9AA3;
    private static final int ACCENT = 0xFFFFEB1A;
    private static final int OK = 0xFF4ADE80;
    private static final int WARN = 0xFFFBBF24;
    private static final int DANGER = 0xFFF87171;

    private static final String SITE = "https://maxapacity.com";
    private static final String DEFAULT_MAPS_PACKAGE = "app.organicmaps";
    private static final int SLOT_COUNT = 4;
    private static final int REQ_LOCATION = 1;
    private static final int REQ_NOTIFY = 2;

    private static final float MPS_TO_MPH = 2.23694f;
    private static final float MPS_TO_KMH = 3.6f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    private SharedPreferences prefs;
    private LocationManager locationManager;
    private boolean listening;
    private boolean startAfterGrant;

    private TextView gpsStatus;
    private TextView speedValue;
    private TextView speedUnit;
    private TextView tripValue;
    private TextView topValue;
    private TextView timeValue;
    private TextView avgValue;
    private TextView rideButton;
    private LinearLayout slotRow;
    private TextView chopToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("ride", Context.MODE_PRIVATE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        RideState.get().addListener(this);
        startPassiveGps();
        if (RideState.get().isRecording()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        // Restart the gesture service if the system stopped it.
        if (ChopService.isEnabled(this)) startChopService();
        renderSlots();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        RideState.get().removeListener(this);
        stopPassiveGps();
    }

    /** Home screen: Back has nowhere to go. */
    @Override
    public void onBackPressed() {
    }

    // ---- GPS -------------------------------------------------------------

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Live speed while no ride is recording; RideService feeds state otherwise. */
    private void startPassiveGps() {
        if (listening || RideState.get().isRecording() || !hasLocationPermission()) return;
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, this);
            listening = true;
        } catch (SecurityException | IllegalArgumentException e) {
            listening = false;
        }
    }

    private void stopPassiveGps() {
        if (!listening) return;
        locationManager.removeUpdates(this);
        listening = false;
    }

    @Override
    public void onLocationChanged(Location location) {
        RideState.get().onLocation(location);
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode != REQ_LOCATION) return;
        boolean granted = hasLocationPermission();
        if (granted && startAfterGrant) {
            startRide();
        } else if (granted) {
            startPassiveGps();
        } else {
            toast("Location is needed for speed and ride recording");
        }
        startAfterGrant = false;
        render();
    }

    private void requestLocation(boolean thenStart) {
        startAfterGrant = thenStart;
        List<String> wanted = new ArrayList<>();
        wanted.add(Manifest.permission.ACCESS_FINE_LOCATION);
        wanted.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(wanted.toArray(new String[0]), REQ_LOCATION);
    }

    // ---- Ride ------------------------------------------------------------

    private void toggleRide() {
        if (RideState.get().isRecording()) {
            endRide();
        } else if (hasLocationPermission()) {
            startRide();
        } else {
            requestLocation(true);
        }
    }

    private void startRide() {
        stopPassiveGps();
        startForegroundService(new Intent(this, RideService.class)
                .setAction(RideService.ACTION_START));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void endRide() {
        RideState state = RideState.get();
        stopService(new Intent(this, RideService.class));
        state.stop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startPassiveGps();

        if (state.track().size() < 2) {
            toast("Ride ended. No GPS track was recorded");
        } else {
            try {
                File dir = new File(getExternalFilesDir(null), "rides");
                File f = GpxWriter.write(dir, state.startWallMs(), state.track());
                toast("Ride saved: " + formatDistance(state.distanceM()) + " "
                        + distanceUnit() + ", " + f.getName());
            } catch (IOException e) {
                toast("Ride ended, but the GPX file could not be saved");
            }
        }
        render();
    }

    // ---- Flashlight gesture ---------------------------------------------

    private void setChopEnabled(boolean enabled) {
        prefs.edit().putBoolean(ChopService.PREF, enabled).apply();
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            }
            startChopService();
            toast("Chop the phone twice to switch the flashlight. Paused during rides");
        } else {
            stopService(new Intent(this, ChopService.class));
            toast("Flashlight gesture off");
        }
        render();
    }

    private void startChopService() {
        try {
            startForegroundService(new Intent(this, ChopService.class));
        } catch (RuntimeException e) {
            // Background-start restriction; retried on the next resume.
        }
    }

    @Override
    public void onRideStateChanged() {
        render();
    }

    // ---- Rendering -------------------------------------------------------

    private boolean metric() {
        if (prefs.contains("metric")) return prefs.getBoolean("metric", true);
        String c = Locale.getDefault().getCountry();
        return !("US".equals(c) || "GB".equals(c));
    }

    private String speedUnitLabel() {
        return metric() ? "KM/H" : "MPH";
    }

    private String distanceUnit() {
        return metric() ? "km" : "mi";
    }

    private String formatSpeed(float mps) {
        return String.valueOf(Math.round(mps * (metric() ? MPS_TO_KMH : MPS_TO_MPH)));
    }

    private String formatDistance(double meters) {
        double v = metric() ? meters / 1000.0 : meters / 1609.344;
        return String.format(Locale.US, v < 100 ? "%.2f" : "%.1f", v);
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private void render() {
        RideState state = RideState.get();
        boolean recording = state.isRecording();

        if (!hasLocationPermission()) {
            gpsStatus.setText("LOCATION OFF");
            gpsStatus.setTextColor(DANGER);
        } else if (state.hasFix()) {
            gpsStatus.setText(recording ? "RECORDING" : "GPS LOCKED");
            gpsStatus.setTextColor(OK);
        } else {
            gpsStatus.setText("SEARCHING GPS");
            gpsStatus.setTextColor(WARN);
        }

        speedValue.setText(state.hasFix() ? formatSpeed(state.speedMps()) : "--");
        speedUnit.setText(speedUnitLabel());
        tripValue.setText(formatDistance(state.distanceM()) + " " + distanceUnit());
        topValue.setText(formatSpeed(state.topSpeedMps()));
        timeValue.setText(formatDuration(state.durationMs()));
        avgValue.setText(formatSpeed(state.avgSpeedMps()));

        boolean chop = ChopService.isEnabled(this);
        chopToggle.setText(chop ? "Chop light: on" : "Chop light: off");
        chopToggle.setTextColor(chop ? ACCENT : TEXT_DIM);

        rideButton.setText(recording ? "END RIDE" : "START RIDE");
        rideButton.setTextColor(recording ? DANGER : BG);
        rideButton.setBackground(recording
                ? panel(BG, DANGER, dp(10))
                : panel(ACCENT, ACCENT, dp(10)));
    }

    // ---- UI construction -------------------------------------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            v.setPadding(dp(16) + bars.left, dp(8) + bars.top,
                    dp(16) + bars.right, dp(16) + bars.bottom);
            return insets;
        });

        // Header: clock on the left, GPS status on the right.
        LinearLayout header = row();
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        styleValue(clock, 20);
        header.addView(clock, weighted());
        gpsStatus = label("");
        gpsStatus.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        gpsStatus.setMinHeight(dp(48));
        gpsStatus.setOnClickListener(v -> {
            if (!hasLocationPermission()) requestLocation(false);
        });
        header.addView(gpsStatus, weighted());
        root.addView(header, matchWrap());

        // Speedometer. Takes the spare height; tap switches mph / km/h.
        LinearLayout speedo = new LinearLayout(this);
        speedo.setOrientation(LinearLayout.VERTICAL);
        speedo.setGravity(Gravity.CENTER);
        speedValue = new TextView(this);
        styleValue(speedValue, 128);
        speedValue.setIncludeFontPadding(false);
        speedValue.setGravity(Gravity.CENTER);
        speedo.addView(speedValue, matchWrap());
        speedUnit = label("");
        speedUnit.setGravity(Gravity.CENTER);
        speedo.addView(speedUnit, matchWrap());
        speedo.setOnClickListener(v -> {
            prefs.edit().putBoolean("metric", !metric()).apply();
            render();
        });
        root.addView(speedo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Trip stats.
        LinearLayout stats = row();
        stats.setBackground(panel(SURFACE, HAIRLINE, dp(16)));
        stats.setPadding(dp(4), dp(16), dp(4), dp(16));
        tripValue = stat(stats, "TRIP");
        topValue = stat(stats, "TOP");
        timeValue = stat(stats, "TIME");
        avgValue = stat(stats, "AVG");
        root.addView(stats, matchWrap());

        // Primary action.
        rideButton = new TextView(this);
        rideButton.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        rideButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        rideButton.setLetterSpacing(0.08f);
        rideButton.setGravity(Gravity.CENTER);
        rideButton.setOnClickListener(v -> toggleRide());
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        buttonLp.topMargin = dp(16);
        root.addView(rideButton, buttonLp);

        // Ride apps: tap to open, long-press to assign.
        root.addView(section("RIDE APPS"), matchWrap());
        slotRow = row();
        root.addView(slotRow, matchWrap());

        // Site tools.
        root.addView(section("MAXAPACITY"), matchWrap());
        LinearLayout tools = row();
        addTool(tools, "Garage", "/garage");
        addTool(tools, "Picker", "/picker");
        addTool(tools, "Guides", "/guides");
        addTool(tools, "Gearing", "/tools/sprocket-calculator");
        root.addView(tools, matchWrap());

        // Bottom row: app drawer and the flashlight gesture switch.
        LinearLayout bottom = row();
        TextView allApps = tile("All apps", null);
        allApps.setOnClickListener(v -> showAppPicker(-1));
        bottom.addView(allApps, new LinearLayout.LayoutParams(0, dp(48), 1f));
        chopToggle = tile("", null);
        chopToggle.setOnClickListener(v -> setChopEnabled(!ChopService.isEnabled(this)));
        LinearLayout.LayoutParams chopLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        chopLp.leftMargin = dp(8);
        bottom.addView(chopToggle, chopLp);
        LinearLayout.LayoutParams bottomLp = matchWrap();
        bottomLp.topMargin = dp(16);
        root.addView(bottom, bottomLp);

        return root;
    }

    private TextView stat(LinearLayout parent, String name) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        TextView l = label(name);
        l.setGravity(Gravity.CENTER);
        col.addView(l, matchWrap());
        TextView v = new TextView(this);
        styleValue(v, 20);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(4);
        col.addView(v, lp);
        parent.addView(col, weighted());
        return v;
    }

    private void addTool(LinearLayout parent, String name, String path) {
        TextView t = tile(name, null);
        t.setOnClickListener(v -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(SITE + path))));
        parent.addView(t, tileParams(parent.getChildCount()));
    }

    private void renderSlots() {
        slotRow.removeAllViews();
        PackageManager pm = getPackageManager();
        for (int i = 0; i < SLOT_COUNT; i++) {
            final int slot = i;
            final Intent launch = slotIntent(slot);
            ResolveInfo info = launch == null ? null : pm.resolveActivity(launch, 0);
            TextView t;
            if (info == null) {
                t = tile("Assign", null);
                t.setTextColor(TEXT_DIM);
                t.setOnClickListener(v -> showAppPicker(slot));
            } else {
                t = tile(info.loadLabel(pm).toString(), info.loadIcon(pm));
                t.setOnClickListener(v -> open(launch));
            }
            t.setOnLongClickListener(v -> {
                showAppPicker(slot);
                return true;
            });
            slotRow.addView(t, tileParams(i));
        }
    }

    /** The app assigned to a slot, or the bundled maps app for an unset slot 0. */
    private Intent slotIntent(int slot) {
        String saved = prefs.getString("slot" + slot, null);
        if (saved != null) {
            ComponentName cn = ComponentName.unflattenFromString(saved);
            return cn == null ? null : launchIntent(cn);
        }
        if (slot == 0) {
            return getPackageManager().getLaunchIntentForPackage(DEFAULT_MAPS_PACKAGE);
        }
        return null;
    }

    private static Intent launchIntent(ComponentName cn) {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    /** App list. slot >= 0 assigns the choice to that slot; -1 just launches it. */
    private void showAppPicker(final int slot) {
        final PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
            if (!getPackageName().equals(r.activityInfo.packageName)) apps.add(r);
        }
        Collections.sort(apps, new ResolveInfo.DisplayNameComparator(pm));

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return apps.size();
            }

            @Override
            public Object getItem(int position) {
                return apps.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout item = (LinearLayout) convertView;
                if (item == null) {
                    item = row();
                    item.setGravity(Gravity.CENTER_VERTICAL);
                    item.setMinimumHeight(dp(56));
                    item.setPadding(dp(24), dp(8), dp(24), dp(8));
                    item.addView(new ImageView(RideActivity.this),
                            new LinearLayout.LayoutParams(dp(32), dp(32)));
                    TextView name = new TextView(RideActivity.this);
                    name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    name.setTextColor(TEXT);
                    name.setPadding(dp(16), 0, 0, 0);
                    item.addView(name, weighted());
                }
                ResolveInfo r = apps.get(position);
                ((ImageView) item.getChildAt(0)).setImageDrawable(r.loadIcon(pm));
                ((TextView) item.getChildAt(1)).setText(r.loadLabel(pm));
                return item;
            }
        };

        AlertDialog.Builder b = new AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(slot < 0 ? "All apps" : "Assign ride app")
                .setAdapter(adapter, (d, which) -> {
                    ResolveInfo r = apps.get(which);
                    ComponentName cn = new ComponentName(
                            r.activityInfo.packageName, r.activityInfo.name);
                    if (slot < 0) {
                        open(launchIntent(cn));
                    } else {
                        prefs.edit().putString("slot" + slot, cn.flattenToString()).apply();
                        renderSlots();
                    }
                });
        if (slot >= 0 && slotIntent(slot) != null) {
            b.setNeutralButton("Clear slot", (d, w) -> {
                // An empty value (rather than removal) keeps slot 0 from
                // falling back to the bundled maps app again.
                prefs.edit().putString("slot" + slot, "").apply();
                renderSlots();
            });
        }
        if (apps.isEmpty()) b.setMessage("No apps installed yet");
        b.show();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast("Nothing installed can open that");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ---- View helpers ----------------------------------------------------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** Equal-width tile in a four-across row with an 8dp gutter. */
    private LinearLayout.LayoutParams tileParams(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(88), 1f);
        if (index > 0) lp.leftMargin = dp(8);
        return lp;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(TEXT_DIM);
        t.setLetterSpacing(0.12f);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private TextView section(String text) {
        TextView t = label(text);
        t.setPadding(0, dp(24), 0, dp(8));
        return t;
    }

    private void styleValue(TextView t, int sp) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        t.setFontFeatureSettings("tnum");
    }

    private TextView tile(String text, Drawable icon) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(TEXT);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        if (icon != null) {
            icon.setBounds(0, 0, dp(32), dp(32));
            t.setCompoundDrawables(null, icon, null, null);
            t.setCompoundDrawablePadding(dp(8));
        }
        t.setBackground(new RippleDrawable(
                ColorStateList.valueOf(HAIRLINE), panel(SURFACE, HAIRLINE, dp(16)), null));
        return t;
    }

    private GradientDrawable panel(int fill, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }
}
