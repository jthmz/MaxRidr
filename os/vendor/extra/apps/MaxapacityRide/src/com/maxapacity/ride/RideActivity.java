package com.maxapacity.ride;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * MAXAPACITY home screen: speedometer, trip stats, ride recorder, four
 * assignable ride-app slots, site tools and an app drawer.
 */
public final class RideActivity extends Activity
        implements RideState.Listener, LocationListener, AppDrawer.Host, CopilotView.Host {

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
    private static final int REQ_SPEECH = 3;

    /** Sustained speed that starts a ride on its own (about 11 km/h, 7 mph). */
    private static final float AUTO_START_MPS = 3f;
    private static final int AUTO_START_FIXES = 5;

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
    private int fastFixes;
    /** Cleared by a manual End ride until the bike has come to a stop. */
    private boolean autoStartArmed = true;

    private TextView gpsStatus;
    private TextView speedValue;
    private TextView speedUnit;
    private TextView tripValue;
    private TextView topValue;
    private TextView timeValue;
    private TextView avgValue;
    private TextView rideButton;
    private LinearLayout slotRow;
    private TextView stopsValue;
    private FxView fx;
    private AppDrawer drawer;
    private CopilotView copilot;
    private GestureDetector gestures;

    /** Keeps the drawer in step with installs, removals and updates. */
    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            drawer.invalidateApps();
            renderSlots();
        }
    };
    private TextView climbValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("ride", Context.MODE_PRIVATE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        setContentView(buildUi());

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent down, MotionEvent up, float vx, float vy) {
                if (down == null || Math.abs(vy) < Math.abs(vx) * 1.5f) return false;
                float dy = up.getY() - down.getY();
                if (copilot.isOpen()) return false;
                if (!drawer.isOpen() && dy < -dp(72)) {
                    drawer.open();
                    return true;
                }
                if (drawer.isOpen() && dy > dp(72) && drawer.atTop()) {
                    drawer.close();
                    return true;
                }
                return false;
            }
        });

        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageChanges, packages, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageChanges, packages);
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageChanges);
        copilot.shutdown();
        super.onDestroy();
    }

    /** Swipe up anywhere on the dashboard opens the drawer; swipe down closes it. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        gestures.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    /** Home pressed while already home: back to the dashboard. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        drawer.close();
        copilot.close();
    }

    @Override
    protected void onResume() {
        super.onResume();
        RideState.get().addListener(this);
        startPassiveGps();
        // Rides recorded without signal go up once the phone is back online.
        Strava.uploadPending(this);
        // Restart the gesture service if the system stopped it.
        if (ChopService.isEnabled(this)) startChopService();
        renderSlots();
        fx.setRunning(prefs.getBoolean("fx", true));
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        fx.setRunning(false);
        RideState.get().removeListener(this);
        stopPassiveGps();
    }

    /** Home screen: Back only closes the drawer. */
    @Override
    public void onBackPressed() {
        drawer.close();
        copilot.close();
    }

    // ---- CopilotView.Host ------------------------------------------------

    @Override
    public void listen() {
        try {
            startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Copilot"), REQ_SPEECH);
        } catch (ActivityNotFoundException e) {
            toast("Voice input needs the Google app installed");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SPEECH || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (heard != null && !heard.isEmpty()) copilot.ask(heard.get(0), true);
    }

    @Override
    public void addGeminiKey() {
        final EditText key = new EditText(this);
        key.setHint("API key");
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(prefs.getString("gemini_key", ""));
        LinearLayout form = new LinearLayout(this);
        form.setPadding(dp(24), dp(8), dp(24), 0);
        form.addView(key, matchWrap());
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Gemini API key")
                .setMessage("Create a free key at aistudio.google.com/apikey and paste it"
                        + " here. It is stored only on this phone.")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.edit().putString("gemini_key", key.getText().toString().trim())
                            .apply();
                    copilot.refreshEmptyState();
                    toast(Gemini.hasKey(this) ? "Copilot is ready" : "Copilot key removed");
                })
                .show();
    }

    // ---- AppDrawer.Host --------------------------------------------------

    @Override
    public void launch(Intent intent) {
        open(intent);
    }

    /** Pin from the drawer: first free ride-app slot, or ask which to replace. */
    @Override
    public void pin(final ComponentName app) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (slotIntent(i) == null) {
                setSlot(i, app);
                return;
            }
        }
        PackageManager pm = getPackageManager();
        String[] names = new String[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            ResolveInfo info = pm.resolveActivity(slotIntent(i), 0);
            names[i] = "Replace " + (info == null ? "slot " + (i + 1) : info.loadLabel(pm));
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Ride apps are full")
                .setItems(names, (d, which) -> setSlot(which, app))
                .show();
    }

    private void setSlot(int slot, ComponentName app) {
        prefs.edit().putString("slot" + slot, app.flattenToString()).apply();
        renderSlots();
        toast("Pinned to ride apps");
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
        maybeAutoStart(location);
    }

    /** Starts recording after a few seconds of sustained riding speed. */
    private void maybeAutoStart(Location l) {
        boolean fast = l.hasSpeed() && l.getSpeed() >= AUTO_START_MPS
                && (!l.hasAccuracy() || l.getAccuracy() <= 25f);
        fastFixes = fast ? fastFixes + 1 : 0;
        if (!l.hasSpeed() || l.getSpeed() < 1f) autoStartArmed = true;
        if (fastFixes >= AUTO_START_FIXES && autoStartArmed
                && !RideState.get().isRecording()
                && prefs.getBoolean("auto_start", true)) {
            fastFixes = 0;
            startRide();
            toast("Ride started automatically");
        }
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
        try {
            startForegroundService(new Intent(this, RideService.class)
                    .setAction(RideService.ACTION_START));
        } catch (RuntimeException e) {
            toast("Could not start ride recording");
            startPassiveGps();
        }
    }

    private void endRide() {
        String result = RideStore.finish(this);
        autoStartArmed = false;
        fastFixes = 0;
        stopService(new Intent(this, RideService.class));
        startPassiveGps();
        toast(result);
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

    private void render() {
        RideState state = RideState.get();
        boolean recording = state.isRecording();

        // The service can end a ride on its own, so the flag follows the state.
        if (recording) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            startPassiveGps();
        }

        if (!hasLocationPermission()) {
            gpsStatus.setText("LOCATION OFF");
            gpsStatus.setTextColor(DANGER);
        } else if (state.isPaused()) {
            gpsStatus.setText("PAUSED");
            gpsStatus.setTextColor(WARN);
        } else if (state.hasFix()) {
            gpsStatus.setText(recording ? "RECORDING" : "GPS LOCKED");
            gpsStatus.setTextColor(OK);
        } else {
            gpsStatus.setText("SEARCHING GPS");
            gpsStatus.setTextColor(WARN);
        }

        fx.setSpeed(state.speedMps());
        speedValue.setText(state.hasFix() ? Units.speed(this, state.speedMps()) : "--");
        speedUnit.setText(Units.speedLabel(this));
        tripValue.setText(Units.distance(this, state.distanceM()) + " "
                + Units.distanceLabel(this));
        timeValue.setText(Units.duration(state.movingMs()));
        topValue.setText(Units.speed(this, state.topSpeedMps()));
        avgValue.setText(Units.speed(this, state.avgSpeedMps()));
        stopsValue.setText(String.valueOf(state.stops()));
        climbValue.setText(Units.climb(this, state.climbM()) + " " + Units.climbLabel(this));

        rideButton.setText(recording ? "END RIDE" : "START RIDE");
        rideButton.setTextColor(recording ? DANGER : BG);
        rideButton.setBackground(recording
                ? panel(BG, DANGER, dp(10))
                : panel(ACCENT, ACCENT, dp(10)));
    }

    // ---- Rides, settings, Strava -----------------------------------------

    private void showRides() {
        final JSONArray rides = RideStore.load(this);
        if (rides.length() == 0) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Rides")
                    .setMessage("No rides yet. Tap Start ride, or just set off: recording"
                            + " starts on its own once you are moving.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        // Newest first.
        final List<JSONObject> list = new ArrayList<>();
        for (int i = rides.length() - 1; i >= 0; i--) {
            JSONObject r = rides.optJSONObject(i);
            if (r != null) list.add(r);
        }
        String[] lines = new String[list.size()];
        for (int i = 0; i < lines.length; i++) lines[i] = RideStore.describe(this, list.get(i));

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Rides")
                .setItems(lines, (d, which) -> showRide(list.get(which)))
                .show();
    }

    private void showRide(JSONObject r) {
        final String file = r.optString("file");
        String detail = "Distance  " + Units.distance(this, r.optDouble("distanceM")) + " "
                + Units.distanceLabel(this)
                + "\nMoving  " + Units.duration(r.optLong("movingMs"))
                + "\nElapsed  " + Units.duration(r.optLong("elapsedMs"))
                + "\nTop  " + Units.speed(this, (float) r.optDouble("topMps")) + " "
                + Units.speedLabel(this).toLowerCase(Locale.US)
                + "\nClimb  " + Units.climb(this, r.optDouble("climbM")) + " "
                + Units.climbLabel(this)
                + "\nStops  " + r.optInt("stops")
                + "\n\n" + file;
        AlertDialog.Builder b = new AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Ride")
                .setMessage(detail)
                .setPositiveButton("Close", null);
        if (Strava.isConnected(this)
                && !RideStore.STRAVA_UPLOADED.equals(r.optString("strava"))) {
            b.setNeutralButton("Send to Strava", (d, w) -> {
                Strava.queue(this, file);
                toast("Sending to Strava");
            });
        }
        b.show();
    }

    private void showSettings() {
        final boolean autoStart = prefs.getBoolean("auto_start", true);
        final boolean autoEnd = prefs.getBoolean("auto_end", true);
        final boolean chop = ChopService.isEnabled(this);
        final boolean strava = Strava.isConnected(this);
        String[] items = {
                "Start rides automatically: " + (autoStart ? "on" : "off"),
                "End ride after 10 min stopped: " + (autoEnd ? "on" : "off"),
                "Chop twice for flashlight: " + (chop ? "on" : "off"),
                "Units: " + (Units.metric(this) ? "km/h" : "mph"),
                "Visual effects: " + (prefs.getBoolean("fx", true) ? "on" : "off"),
                strava ? "Strava: connected (tap to disconnect)" : "Connect Strava",
                Gemini.hasKey(this) ? "Copilot: change Gemini key" : "Copilot: add Gemini key",
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Settings")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            prefs.edit().putBoolean("auto_start", !autoStart).apply();
                            break;
                        case 1:
                            prefs.edit().putBoolean("auto_end", !autoEnd).apply();
                            break;
                        case 2:
                            setChopEnabled(!chop);
                            break;
                        case 3:
                            prefs.edit().putBoolean("metric", !Units.metric(this)).apply();
                            break;
                        case 4:
                            boolean fxOn = !prefs.getBoolean("fx", true);
                            prefs.edit().putBoolean("fx", fxOn).apply();
                            fx.setRunning(fxOn);
                            fx.setVisibility(fxOn ? View.VISIBLE : View.INVISIBLE);
                            break;
                        case 5:
                            if (strava) {
                                Strava.disconnect(this);
                                toast("Strava disconnected");
                            } else {
                                showStravaConnect();
                            }
                            return;
                        default:
                            addGeminiKey();
                            return;
                    }
                    render();
                    showSettings();
                })
                .setPositiveButton("Done", null)
                .show();
    }

    private void showStravaConnect() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);
        final EditText id = new EditText(this);
        id.setHint("Client ID");
        id.setInputType(InputType.TYPE_CLASS_NUMBER);
        id.setText(prefs.getString("strava_client_id", ""));
        form.addView(id, matchWrap());
        final EditText secret = new EditText(this);
        secret.setHint("Client Secret");
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setText(prefs.getString("strava_client_secret", ""));
        form.addView(secret, matchWrap());

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Connect Strava")
                .setMessage("Create an API application at strava.com/settings/api with"
                        + " Authorization Callback Domain set to localhost, then enter its"
                        + " Client ID and Client Secret.")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", (d, w) -> {
                    String clientId = id.getText().toString().trim();
                    String clientSecret = secret.getText().toString().trim();
                    if (clientId.isEmpty() || clientSecret.isEmpty()) {
                        toast("Both the Client ID and Client Secret are needed");
                        return;
                    }
                    Strava.saveApp(this, clientId, clientSecret);
                    open(new Intent(Intent.ACTION_VIEW, Strava.authorizeUri(this)));
                })
                .show();
    }

    // ---- UI construction -------------------------------------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Layers, back to front: animated backdrop, dashboard, app drawer.
        final FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        fx = new FxView(this);
        fx.setVisibility(prefs.getBoolean("fx", true) ? View.VISIBLE : View.INVISIBLE);
        drawer = new AppDrawer(this, this);
        frame.addView(fx, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(drawer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        copilot = new CopilotView(this, this);
        frame.addView(copilot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            root.setPadding(dp(16) + bars.left, dp(8) + bars.top,
                    dp(16) + bars.right, dp(16) + bars.bottom);
            drawer.setPadding(dp(16) + bars.left, dp(16) + bars.top,
                    dp(16) + bars.right, dp(8) + bars.bottom);
            copilot.setPadding(dp(16) + bars.left, dp(8) + bars.top,
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
        styleValue(speedValue, 112);
        speedValue.setIncludeFontPadding(false);
        speedValue.setGravity(Gravity.CENTER);
        speedo.addView(speedValue, matchWrap());
        speedUnit = label("");
        speedUnit.setGravity(Gravity.CENTER);
        speedo.addView(speedUnit, matchWrap());
        speedo.setOnClickListener(v -> {
            prefs.edit().putBoolean("metric", !Units.metric(this)).apply();
            render();
        });
        root.addView(speedo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // The backdrop centres its gauge arc, bolt and grid on the speedometer.
        speedo.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                fx.setFocus(l, t, r, b));

        // Trip stats: two rows of three inside one panel.
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.VERTICAL);
        stats.setBackground(panel(SURFACE, HAIRLINE, dp(16)));
        stats.setPadding(dp(4), dp(16), dp(4), dp(16));
        LinearLayout statsTop = row();
        tripValue = stat(statsTop, "TRIP");
        timeValue = stat(statsTop, "MOVING");
        topValue = stat(statsTop, "TOP");
        stats.addView(statsTop, matchWrap());
        LinearLayout statsBottom = row();
        avgValue = stat(statsBottom, "AVG");
        stopsValue = stat(statsBottom, "STOPS");
        climbValue = stat(statsBottom, "CLIMB");
        LinearLayout.LayoutParams statsBottomLp = matchWrap();
        statsBottomLp.topMargin = dp(16);
        stats.addView(statsBottom, statsBottomLp);
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

        // Bottom row: app drawer, ride history, settings.
        LinearLayout bottom = row();
        addBottomTile(bottom, "Apps", v -> drawer.open());
        addBottomTile(bottom, "Rides", v -> showRides());
        addBottomTile(bottom, "Copilot", v -> copilot.open());
        addBottomTile(bottom, "Settings", v -> showSettings());
        LinearLayout.LayoutParams bottomLp = matchWrap();
        bottomLp.topMargin = dp(16);
        root.addView(bottom, bottomLp);

        return frame;
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

    private void addBottomTile(LinearLayout parent, String name, View.OnClickListener click) {
        TextView t = tile(name, null);
        t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        if (parent.getChildCount() > 0) lp.leftMargin = dp(8);
        parent.addView(t, lp);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(80), 1f);
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
