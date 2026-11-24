package com.maxapacity.ride;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * MAXAPACITY home screen. The dashboard (speedometer, trip stats, the ride
 * button, the bike link and four ride-app shortcuts) is always underneath;
 * every other destination is a {@link Panel} layered over it, and anything
 * that interrupts is a {@link Sheet}.
 */
public final class RideActivity extends Activity
        implements RideState.Listener, LocationListener, Host, Strava.Screen {

    private static final String DEFAULT_MAPS_PACKAGE = "app.organicmaps";
    private static final int SLOT_COUNT = 4;
    private static final int REQ_LOCATION = 1;
    private static final int REQ_NOTIFY = 2;
    private static final int REQ_SPEECH = 3;
    private static final int REQ_BLUETOOTH = 4;
    private static final int REQ_SMS = 5;
    static final int REQ_HEALTH = 6;

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
    private boolean resumed;
    private boolean startAfterGrant;
    private Runnable afterBluetooth;
    private int fastFixes;
    /** Cleared by a manual End ride until the bike has come to a stop. */
    private boolean autoStartArmed = true;

    private FrameLayout frame;
    private Ui.Pill gpsStatus;
    private GaugeView gauge;
    private TextView tripValue;
    private TextView topValue;
    private TextView timeValue;
    private TextView avgValue;
    private TextView stopsValue;
    private TextView climbValue;
    private FrameLayout rideButtonSlot;
    private LinearLayout rideButton;
    private boolean rideButtonRecording;
    private LinearLayout bikeStrip;
    private String bikeStripKey = "";
    private LinearLayout slotRow;
    private GestureDetector gestures;

    private AppDrawer drawer;
    private RidesPanel rides;
    private RideDetailPanel rideDetail;
    private CopilotView copilot;
    private BikePanel bike;
    private TunePanel tune;
    private DeckView deck;
    /** The "looking for your Deck" sheet while it is open, so finding the Deck can move it on. */
    private Sheet deckLooking;
    private final Deck.Listener deckChanged = this::onDeckChanged;
    /** Keeps looking while that sheet is up: a cable plugged in announces nothing. */
    private final Runnable deckRelook = new Runnable() {
        @Override
        public void run() {
            if (deckLooking == null || Sheet.current() != deckLooking) return;
            Deck.get(RideActivity.this).refresh();
            handler.postDelayed(this, 4000);
        }
    };
    private SettingsPanel settings;
    /** Back to front. Back closes the front-most open one. */
    private final List<Panel> panels = new ArrayList<>();

    /** Keeps the drawer in step with installs, removals and updates. */
    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            drawer.invalidateApps();
            renderSlots();
        }
    };

    private final Runnable bikeChanged = this::renderBikeStrip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        prefs = Units.prefs(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        RideStore.purgeRemoved(this);
        setContentView(buildUi());
        Strava.setScreen(this);
        if (CrashLog.read(this) != null) {
            // Posted so the message bar is laid out over a finished screen.
            handler.post(() -> say("The app closed unexpectedly last time", "DETAILS",
                    this::showLastCrash));
        }
        // Only on a fresh start: a rebuilt screen should not jump back to
        // whatever it was first launched into.
        if (savedInstanceState == null) {
            Panel wanted = screenNamedBy(getIntent());
            if (wanted != null) wanted.open();
        }

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent down, MotionEvent up, float vx, float vy) {
                if (down == null || Math.abs(vy) < Math.abs(vx) * 1.5f) return false;
                if (Sheet.current() != null) return false;
                float dy = up.getY() - down.getY();
                Panel top = topPanel();
                if (top == null && dy < -dp(72)) {
                    drawer.open();
                    return true;
                }
                if (top == drawer && dy > dp(72) && drawer.atTop()) {
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
        // Screens that were open are listening to the bike link, the updater
        // or the network; let each of them let go before this one is discarded.
        for (Panel p : panels) if (p.isOpen()) p.onClose();
        Strava.clearScreen(this);
        copilot.shutdown();
        super.onDestroy();
    }

    /** Swipe up anywhere on the dashboard opens the drawer; swipe down closes it. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        gestures.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    /**
     * Home pressed while already home: back to the dashboard. A launch that
     * names a screen goes to that screen instead.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Sheet.dismissCurrent();
        Panel wanted = screenNamedBy(intent);
        for (Panel p : panels) if (p != wanted) p.close();
        if (wanted != null) wanted.open();
    }

    /**
     * The screen an intent asks for with the extra "open", or null for the
     * dashboard. Lets a shortcut, or the on-phone test script, go straight to
     * a screen:
     *
     *   adb shell am start -n com.maxapacity.ride/.RideActivity --es open settings
     *
     * It only shows a screen; nothing is changed or sent by opening one.
     */
    private Panel screenNamedBy(Intent intent) {
        String name = intent == null ? null : intent.getStringExtra("open");
        if (name == null) return null;
        switch (name) {
            case "apps":
                return drawer;
            case "rides":
                return rides;
            case "copilot":
                return copilot;
            case "bike":
                return bike;
            case "tune":
                return tune;
            case "deck":
                return deck;
            case "settings":
                return settings;
            default:
                return null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        RideState.get().addListener(this);
        startPassiveGps();
        // Rides recorded without signal go up once the phone is back online.
        Strava.uploadPending(this);
        Health.sync(this);
        // Restart the gesture service if the system stopped it.
        if (ChopService.isEnabled(this)) startChopService();
        renderSlots();
        gauge.setFx(prefs.getBoolean("fx", true));
        resumed = true;
        updateDial();
        Bike.get(this).addListener(bikeChanged);
        Bike.get(this).resume();
        Deck.get(this).addListener(deckChanged);
        Deck.get(this).attach(this);
        if (deckLooking != null) handler.postDelayed(deckRelook, 4000);
        for (Panel p : panels) if (p.isOpen()) p.onHostResume();
        Updater.autoCheck(this, this);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        resumed = false;
        updateDial();
        for (Panel p : panels) if (p.isOpen()) p.onHostPause();
        Deck.get(this).detach(this);
        Deck.get(this).removeListener(deckChanged);
        handler.removeCallbacks(deckRelook);
        Bike.get(this).removeListener(bikeChanged);
        // The bike link stays up during a ride so telemetry keeps flowing
        // with the screen off; otherwise it is dropped to save both batteries.
        if (!RideState.get().isRecording()) Bike.get(this).pause();
        Updater.installIfQuiet(this);
        RideState.get().removeListener(this);
        stopPassiveGps();
    }

    /** Home screen: Back steps out of a sheet, then a screen, and stops at the dashboard. */
    @Override
    public void onBackPressed() {
        if (Sheet.dismissCurrent()) return;
        Panel top = topPanel();
        if (top != null) top.close();
    }

    /** The dial animates only while it is actually on show. */
    private void updateDial() {
        gauge.setRunning(resumed && topPanel() == null);
    }

    private Panel topPanel() {
        for (int i = panels.size() - 1; i >= 0; i--) {
            if (panels.get(i).isOpen()) return panels.get(i);
        }
        return null;
    }

    // ---- Host --------------------------------------------------------------

    @Override
    public ViewGroup frame() {
        return frame;
    }

    @Override
    public void say(String message) {
        Snack.show(frame, message);
    }

    @Override
    public void say(String message, String action, Runnable run) {
        Snack.show(frame, message, action, run);
    }

    @Override
    public void launch(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            say("Nothing installed can open that");
        }
    }

    @Override
    public void settingsChanged() {
        gauge.setFx(prefs.getBoolean("fx", true));
        render();
    }

    @Override
    public void showRide(JSONObject ride) {
        rideDetail.show(ride);
    }

    @Override
    public void ridesChanged() {
        if (rides.isOpen()) rides.refresh();
    }

    @Override
    public void showBike() {
        bike.open();
    }

    void showTune() {
        tune.open();
    }

    @Override
    public void listen() {
        try {
            startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Copilot"), REQ_SPEECH);
        } catch (ActivityNotFoundException e) {
            say("Voice input needs a speech recognition app, such as the Google app");
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
    public void editGeminiKey() {
        final EditText key = Ui.input(this, "API key", true);
        key.setText(prefs.getString("gemini_key", ""));
        new Sheet(frame, "Gemini API key",
                "Copilot answers with Google Gemini. Create a free key at"
                        + " aistudio.google.com/apikey and paste it here. It is stored only on"
                        + " this phone.")
                .view(key)
                .action("Cancel", Ui.SECONDARY, true, null)
                .action("Save", Ui.PRIMARY, true, () -> {
                    prefs.edit().putString("gemini_key", key.getText().toString().trim())
                            .apply();
                    copilot.refreshEmptyState();
                    settings.refresh();
                    say(Gemini.hasKey(this) ? "Copilot is ready" : "Copilot key removed");
                })
                .show();
    }

    @Override
    public void showLastCrash() {
        final String note = CrashLog.read(this);
        if (note == null) return;
        // The first lines say what failed and where; the rest is for whoever fixes it.
        String[] lines = note.split("\n");
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 14); i++) head.append(lines[i]).append('\n');
        TextView text = Ui.small(this, head.toString().trim(), Ui.TEXT_2);
        text.setTypeface(Ui.mono);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.T_LABEL);
        text.setTextIsSelectable(true);
        text.setBackground(Ui.panel(this, Ui.BG, Ui.HAIRLINE, 10));
        text.setPadding(dp(12), dp(12), dp(12), dp(12));
        new Sheet(frame, "What went wrong",
                "The app stopped unexpectedly. This note says where. Share it with whoever"
                        + " looks after the app so it can be fixed. It holds the app version,"
                        + " the phone model and the error, nothing else.")
                .view(text)
                .action("Dismiss", Ui.SECONDARY, true, () -> {
                    CrashLog.clear(this);
                    settings.refresh();
                })
                .action("Share", Ui.PRIMARY, true, () -> launch(Intent.createChooser(
                        new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "MAXAPACITY Ride crash")
                                .putExtra(Intent.EXTRA_TEXT, note), null)))
                .show();
    }

    /**
     * The Deck link. Nothing is typed unless the network hides the Deck: it is
     * found by itself, and all it asks for is the 6-digit code it shows.
     */
    @Override
    public void editDeckConnection() {
        final Deck d = Deck.get(this);
        deckLooking = null;
        if (d.state() == Deck.LINKED) {
            Sheet sheet = new Sheet(frame, "Deck linked",
                    (d.viaUsb() ? "This phone reaches your Mac down the USB cable."
                            : "This phone reaches your Mac on this network.")
                            + (d.uploads() ? " Rides are sent home by themselves when you get"
                            + " back."
                            : " Ride upload is switched off on the Deck, so rides stay on this"
                            + " phone. Set uploads to true in the Deck's config.json to send"
                            + " them home."));
            // The cable needs no pairing, so there is nothing to forget.
            if (!d.viaUsb()) {
                sheet.action("Forget", Ui.SECONDARY, true, () -> {
                    d.forget();
                    say("This phone has forgotten the Deck");
                });
            }
            sheet.action("Done", Ui.PRIMARY, true, null).show();
        } else if (d.state() == Deck.FOUND) {
            deckCodeSheet();
        } else {
            d.refresh();
            handler.removeCallbacks(deckRelook);
            handler.postDelayed(deckRelook, 4000);
            deckLooking = new Sheet(frame, "Looking for your Deck",
                    "Nothing has answered yet. The phone finds the Deck by itself when both are"
                            + " on the same Wi-Fi and the Deck has other screens switched on, or"
                            + " when the phone is plugged into the Mac. This changes the moment"
                            + " it is found.")
                    .action("Type address", Ui.SECONDARY, true, this::deckAddressSheet)
                    .action("Close", Ui.PRIMARY, true, null)
                    .show();
        }
    }

    private void deckCodeSheet() {
        final EditText code = Ui.input(this, "6-digit code", false);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[] {new InputFilter.LengthFilter(6)});
        new Sheet(frame, "Deck found",
                "On the Deck, tap the clock, then Add a screen. Type the 6-digit code it shows.")
                .view(code)
                .action("Cancel", Ui.SECONDARY, true, null)
                .action("Pair", Ui.PRIMARY, true, () -> {
                    String digits = code.getText().toString().replaceAll("\\D", "");
                    if (digits.length() != 6) {
                        say("The code is six digits", "RETRY", this::deckCodeSheet);
                        return;
                    }
                    Deck.get(this).pair(digits, (ok, message) -> {
                        if (ok) say(message);
                        else say(message, "RETRY", this::editDeckConnection);
                    });
                })
                .show();
    }

    /** Only for a network that stops the phone hearing the Deck's announcement. */
    private void deckAddressSheet() {
        final EditText address = Ui.input(this, "Such as 192.168.1.20", false);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(prefs.getString("deck_host", ""));
        new Sheet(frame, "Type the Deck's address",
                "Only for a network that stops the phone finding the Deck by itself. The Deck"
                        + " shows its address under Add a screen.")
                .view(address)
                .action("Cancel", Ui.SECONDARY, true, null)
                .action("Look", Ui.PRIMARY, true, () ->
                        Deck.get(this).useAddress(address.getText().toString(), (ok, message) -> {
                            if (ok && message == null) deckCodeSheet();
                            else say(message);
                        }))
                .show();
    }

    /** The link came up, dropped or moved. */
    private void onDeckChanged() {
        settings.refresh();
        deck.linkChanged();
        Deck found = Deck.get(this);
        // The first time a Deck turns up, say so: nobody should have to go looking for it.
        if (found.state() == Deck.FOUND
                && !found.address().equals(prefs.getString("deck_offered", ""))) {
            prefs.edit().putString("deck_offered", found.address()).apply();
            if (Sheet.current() == null) {
                say("Your Deck is on this network", "PAIR", this::editDeckConnection);
            }
        }
        // Someone waiting on the "looking" sheet is taken straight to the next step.
        if (deckLooking == null || Sheet.current() != deckLooking) return;
        deckLooking = null;
        Deck d = Deck.get(this);
        if (d.state() == Deck.FOUND) {
            deckCodeSheet();
        } else if (d.state() == Deck.LINKED) {
            Sheet.dismissCurrent();
            say("Linked to your Deck");
        }
    }

    @Override
    public void editCrashAlert() {
        final EditText number = Ui.input(this, "Phone number", false);
        number.setInputType(InputType.TYPE_CLASS_PHONE);
        number.setText(prefs.getString(RideService.CRASH_CONTACT, ""));
        new Sheet(frame, "Crash alert",
                "If a hard hit during a ride is followed by the bike not moving, the phone"
                        + " sounds an alarm for 30 seconds. Tap I'm OK to cancel. Otherwise it"
                        + " texts your location to this number. It needs a SIM that can send"
                        + " texts, and it can miss a crash or raise a false alarm. Leave the"
                        + " number empty to turn it off.")
                .view(number)
                .action("Cancel", Ui.SECONDARY, true, null)
                .action("Save", Ui.PRIMARY, true, () -> {
                    String n = number.getText().toString().trim();
                    prefs.edit().putString(RideService.CRASH_CONTACT, n).apply();
                    settings.refresh();
                    if (n.isEmpty()) return;
                    List<String> wanted = new ArrayList<>();
                    if (checkSelfPermission(Manifest.permission.SEND_SMS)
                            != PackageManager.PERMISSION_GRANTED) {
                        wanted.add(Manifest.permission.SEND_SMS);
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                        wanted.add(Manifest.permission.POST_NOTIFICATIONS);
                    }
                    if (wanted.isEmpty()) say("Crash alert is on from your next ride");
                    else requestPermissions(wanted.toArray(new String[0]), REQ_SMS);
                })
                .show();
    }

    /**
     * One tap when the build carries its own Strava application: straight to
     * Strava's Authorize button, in the Strava app if it is installed. A
     * build without one asks for the rider's own application first.
     */
    @Override
    public void connectStrava(String thenSend) {
        Strava.sendOnceConnected(this, thenSend);
        if (Strava.hasBuiltInApp(this)) {
            launch(new Intent(Intent.ACTION_VIEW, Strava.begin(this, false)));
            return;
        }
        final EditText id = Ui.input(this, "Client ID", false);
        id.setInputType(InputType.TYPE_CLASS_NUMBER);
        id.setText(prefs.getString("strava_client_id", ""));
        final EditText secret = Ui.input(this, "Client Secret", true);
        secret.setText(prefs.getString("strava_client_secret", ""));
        LinearLayout form = Ui.column(this);
        form.addView(id);
        form.addView(secret, Ui.top(this, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)), 8));
        new Sheet(frame, "Connect Strava",
                "Create an API application at strava.com/settings/api with Authorization"
                        + " Callback Domain set to localhost, then enter its Client ID and"
                        + " Client Secret. Rides upload on their own after that.")
                .view(form)
                .action("Cancel", Ui.SECONDARY, true, null)
                .action("Connect", Ui.PRIMARY, false, () -> {
                    String clientId = id.getText().toString().trim();
                    String clientSecret = secret.getText().toString().trim();
                    if (clientId.isEmpty() || clientSecret.isEmpty()) {
                        say("Both the Client ID and Client Secret are needed");
                        return;
                    }
                    Sheet.dismissCurrent();
                    Strava.saveApp(this, clientId, clientSecret);
                    launch(new Intent(Intent.ACTION_VIEW, Strava.begin(this, true)));
                })
                .show();
    }

    @Override
    public void stravaChanged() {
        settings.refresh();
        ridesChanged();
    }

    @Override
    public void stravaSignIn(boolean ok, String message) {
        stravaChanged();
        final int earlier = ok ? Strava.unsentCount(this) : 0;
        if (earlier == 0) {
            say(message);
            return;
        }
        // Rides from before the rider connected: theirs to send or not.
        new Sheet(frame, "Strava connected",
                "New rides upload on their own from now on. You also have " + earlier
                        + (earlier == 1 ? " earlier ride" : " earlier rides")
                        + " on this phone. Send " + (earlier == 1 ? "it" : "them")
                        + " to Strava too?")
                .action("Not now", Ui.SECONDARY, true, null)
                .action(earlier == 1 ? "Send it" : "Send them", Ui.PRIMARY, true, () -> {
                    Strava.queueUnsent(this);
                    ridesChanged();
                    say("Sending " + earlier + (earlier == 1 ? " ride" : " rides")
                            + " to Strava");
                })
                .show();
    }

    /** The phone shows its own Health Connect screen; nothing is typed. */
    @Override
    public void connectHealth() {
        requestPermissions(Health.PERMISSIONS, REQ_HEALTH);
    }

    /** Pin from the drawer: first free ride-app slot, or ask which to replace. */
    @Override
    public void pin(final ComponentName app) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (slotIntent(i) == null) {
                setSlot(i, app.flattenToString(), "Pinned to ride apps");
                return;
            }
        }
        PackageManager pm = getPackageManager();
        Sheet sheet = new Sheet(frame, "Ride apps are full", "Choose one to replace.");
        for (int i = 0; i < SLOT_COUNT; i++) {
            final int slot = i;
            ResolveInfo info = pm.resolveActivity(slotIntent(i), 0);
            String name = info == null ? "Slot " + (i + 1) : info.loadLabel(pm).toString();
            sheet.item(Icons.PIN, "Replace " + name, null, false,
                    () -> setSlot(slot, app.flattenToString(), "Pinned to ride apps"));
        }
        sheet.show();
    }

    /** Changes a slot and offers to put back what was there. */
    private void setSlot(final int slot, String value, String message) {
        final String key = "slot" + slot;
        final boolean had = prefs.contains(key);
        final String before = prefs.getString(key, null);
        prefs.edit().putString(key, value).apply();
        renderSlots();
        say(message, "UNDO", () -> {
            if (had) prefs.edit().putString(key, before).apply();
            else prefs.edit().remove(key).apply();
            renderSlots();
        });
    }

    // ---- Permissions -------------------------------------------------------

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
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

    @Override
    public void withBluetooth(Runnable then) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED) {
            then.run();
            return;
        }
        afterBluetooth = then;
        requestPermissions(new String[] {Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode == REQ_BLUETOOTH) {
            Runnable then = afterBluetooth;
            afterBluetooth = null;
            boolean granted = grantResults.length > 0;
            for (int r : grantResults) granted &= r == PackageManager.PERMISSION_GRANTED;
            if (granted && then != null) then.run();
            else if (!granted) say("Allow Nearby devices to link the battery and controller");
            return;
        }
        if (requestCode == REQ_SMS) {
            if (checkSelfPermission(Manifest.permission.SEND_SMS)
                    == PackageManager.PERMISSION_GRANTED) {
                say("Crash alert is on from your next ride");
            } else {
                // Without it nothing could be sent, so do not leave it looking on.
                prefs.edit().remove(RideService.CRASH_CONTACT).apply();
                settings.refresh();
                say("Crash alert is off: allow SMS so it can text your contact");
            }
            return;
        }
        if (requestCode == REQ_HEALTH) {
            if (Health.allowed(this)) {
                Health.setOn(this, true);
                say(RideStore.load(this).length() > 0
                        ? "Health Connect is on. Your rides are being added"
                        : "Health Connect is on. Rides are added as you save them");
            } else {
                say("Health Connect was not allowed", "OPEN",
                        () -> launch(Health.manageIntent(this)));
            }
            settings.refresh();
            return;
        }
        if (requestCode != REQ_LOCATION) return;
        boolean granted = hasLocationPermission();
        if (granted && startAfterGrant) {
            startRide();
        } else if (granted) {
            startPassiveGps();
        } else {
            say("Location is needed for speed and ride recording");
        }
        startAfterGrant = false;
        render();
    }

    // ---- GPS ---------------------------------------------------------------

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
            say("Ride started automatically");
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

    // ---- Ride --------------------------------------------------------------

    private void toggleRide() {
        if (RideState.get().isRecording()) {
            confirmEndRide();
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
            say("Could not start ride recording");
            startPassiveGps();
        }
    }

    /** Ending cannot be undone, so it asks first, with what is about to be saved. */
    private void confirmEndRide() {
        RideState s = RideState.get();
        new Sheet(frame, "End this ride?",
                Units.distance(this, s.distanceM()) + " " + Units.distanceLabel(this)
                        + " in " + Units.duration(s.movingMs()) + " moving."
                        + (Strava.isConnected(this) ? " It will be saved and sent to Strava."
                        : " It will be saved to this phone."))
                .action("Keep riding", Ui.SECONDARY, true, null)
                .action("End ride", Ui.DANGER_OUTLINE, true, this::endRide)
                .show();
    }

    private void endRide() {
        String result = RideStore.finish(this);
        autoStartArmed = false;
        fastFixes = 0;
        stopService(new Intent(this, RideService.class));
        startPassiveGps();
        if (RideStore.load(this).length() > 0 && result.startsWith("Ride saved")) {
            say(result, "VIEW", () -> rides.open());
        } else {
            say(result);
        }
        render();
    }

    // ---- Flashlight gesture ------------------------------------------------

    @Override
    public void setChopEnabled(boolean enabled) {
        prefs.edit().putBoolean(ChopService.PREF, enabled).apply();
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            }
            startChopService();
            say("Chop the phone twice to switch the flashlight. Paused during rides");
        } else {
            stopService(new Intent(this, ChopService.class));
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

    // ---- Rendering ---------------------------------------------------------

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
            gpsStatus.set("LOCATION OFF", Ui.DANGER);
        } else if (state.isPaused()) {
            gpsStatus.set("PAUSED", Ui.WARN);
        } else if (state.hasFix()) {
            gpsStatus.set(recording ? "RECORDING" : "GPS LOCKED", Ui.OK);
        } else {
            gpsStatus.set("SEARCHING GPS", Ui.WARN);
        }

        gauge.setMetric(Units.metric(this));
        gauge.setSpeed(state.speedMps(), state.hasFix());
        gauge.setTopSpeed(recording ? state.topSpeedMps() : 0f);
        gauge.setContentDescription(state.hasFix()
                ? Units.speed(this, state.speedMps()) + " " + Units.speedLabel(this)
                : "Speed unavailable, no GPS fix");

        String speedUnit = Units.speedLabel(this).toLowerCase(java.util.Locale.US);
        tripValue.setText(Ui.withUnit(Units.distance(this, state.distanceM()),
                Units.distanceLabel(this)));
        timeValue.setText(Units.duration(state.movingMs()));
        topValue.setText(Ui.withUnit(Units.speed(this, state.topSpeedMps()), speedUnit));
        avgValue.setText(Ui.withUnit(Units.speed(this, state.avgSpeedMps()), speedUnit));
        stopsValue.setText(String.valueOf(state.stops()));
        climbValue.setText(Ui.withUnit(Units.climb(this, state.climbM()),
                Units.climbLabel(this)));

        // Also on the one-second tick, so a reading that went stale drops off.
        renderBikeStrip();

        if (rideButton == null || rideButtonRecording != recording) {
            rideButtonRecording = recording;
            rideButton = recording
                    ? Ui.button(this, "END RIDE", Icons.SQUARE, Ui.DANGER_OUTLINE)
                    : Ui.button(this, "START RIDE", Icons.PLAY, Ui.PRIMARY);
            rideButton.setOnClickListener(v -> toggleRide());
            rideButtonSlot.removeAllViews();
            rideButtonSlot.addView(rideButton, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /** The powertrain at a glance; rebuilt only when what it shows changes. */
    private void renderBikeStrip() {
        Bike link = Bike.get(this);
        AntBms.Data b = link.battery();
        Fardriver.Live c = link.controller();
        gauge.setPower(b != null || c != null, link.powerW(), link.peakPowerW());
        String key = link.summaryKey();
        if (key.equals(bikeStripKey)) return;
        bikeStripKey = key;
        bikeStrip.removeAllViews();

        if (b == null && c == null) {
            // Nothing live: say what to do, or what the link is doing.
            boolean linked = link.anyConfigured();
            bikeStrip.addView(Ui.icon(this, Icons.BLUETOOTH, 20, Ui.TEXT_2));
            LinearLayout text = Ui.column(this);
            TextView t = Ui.body(this, linked ? "Bike link" : "Link your bike", Ui.TEXT);
            t.setSingleLine(true);
            text.addView(t);
            TextView d = Ui.small(this, !linked ? "Live battery, power and tuning"
                    : link.batteryLinked() ? link.batteryStatus() : link.controllerStatus(),
                    Ui.TEXT_3);
            d.setSingleLine(true);
            d.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(d, Ui.top(this, Ui.matchWrap(), 2));
            bikeStrip.addView(text, Ui.left(this, Ui.weighted(), 12));
            bikeStrip.addView(Ui.icon(this, Icons.CHEVRON_RIGHT, 20, Ui.TEXT_3));
            return;
        }

        int soc = b != null ? b.soc : c.soc;
        addBikeCell("BATTERY", soc < 0 ? null : String.valueOf(soc), "%",
                b != null && b.hasProblem() ? Ui.DANGER
                        : soc >= 0 && soc <= 15 ? Ui.DANGER : soc >= 0 && soc <= 30 ? Ui.WARN
                        : Ui.TEXT, true);
        float kw = link.powerW() / 1000f;
        addBikeCell("POWER", String.format(java.util.Locale.US, "%.1f", Math.abs(kw)), "kW",
                kw < -0.05f ? Ui.OK : Ui.TEXT, false);
        // The hotter of controller and battery, since either can cut power.
        int temp = Integer.MIN_VALUE;
        if (c != null) temp = Math.max(temp, c.mosfetTempC);
        if (b != null) temp = Math.max(temp, Math.max(b.maxTempC(), b.mosfetTempC));
        addBikeCell("TEMP", temp == Integer.MIN_VALUE ? null : String.valueOf(temp), "\u00B0C",
                temp >= 85 ? Ui.DANGER : temp >= 70 ? Ui.WARN : Ui.TEXT, false);
        boolean fault = c != null && c.faults != 0;
        // The pretend bike says so in the cell that would otherwise name the mode.
        if (link.demo() && !fault) {
            addBikeCell("BIKE", "Demo", "", Ui.WARN, false);
        } else {
            addBikeCell(fault ? "FAULT" : "MODE",
                    c == null ? null : fault ? "!" : c.speedModeName(),
                    "", fault ? Ui.DANGER : Ui.TEXT, false);
        }
        bikeStrip.addView(Ui.icon(this, Icons.CHEVRON_RIGHT, 20, Ui.TEXT_3));
    }

    private void addBikeCell(String name, String value, String unit, int color, boolean first) {
        if (!first) {
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    Math.max(1, dp(1)), ViewGroup.LayoutParams.MATCH_PARENT);
            rlp.setMargins(0, dp(6), dp(12), dp(6));
            bikeStrip.addView(Ui.divider(this), rlp);
        }
        LinearLayout cell = Ui.column(this);
        cell.addView(Ui.label(this, name));
        TextView v = Ui.value(this, "", 17);
        if (value == null) {
            v.setText("--");
            v.setTextColor(Ui.TEXT_3);
        } else {
            v.setText(Ui.withUnit(value, unit));
            v.setTextColor(color);
        }
        cell.addView(v, Ui.top(this, Ui.matchWrap(), 6));
        bikeStrip.addView(cell, Ui.weighted());
    }

    // ---- UI construction ---------------------------------------------------

    private View buildUi() {
        final LinearLayout root = Ui.column(this);

        // Layers, back to front: dashboard, screens, then sheets and messages.
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BG);
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawer = new AppDrawer(this, this);
        rides = new RidesPanel(this, this);
        rideDetail = new RideDetailPanel(this, this);
        copilot = new CopilotView(this, this);
        bike = new BikePanel(this, this);
        tune = new TunePanel(this, this);
        deck = new DeckView(this, this);
        settings = new SettingsPanel(this, this);
        panels.add(drawer);
        panels.add(rides);
        panels.add(rideDetail);
        panels.add(copilot);
        panels.add(bike);
        panels.add(tune);
        panels.add(deck);
        panels.add(settings);
        for (Panel p : panels) {
            frame.addView(p, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            p.setOnToggle(this::updateDial);
        }

        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            int system = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
            Insets bars = insets.getInsets(system);
            Insets typing = insets.getInsets(system | WindowInsets.Type.ime());
            // The dashboard has nothing to type into, so it stays put behind
            // the keyboard; screens and sheets move up to stay reachable.
            root.setPadding(dp(16) + bars.left, dp(4) + bars.top,
                    dp(16) + bars.right, dp(12) + bars.bottom);
            for (Panel p : panels) {
                p.setPadding(dp(16) + typing.left, typing.top,
                        dp(16) + typing.right, typing.bottom);
            }
            Sheet.setInsets(typing.top, typing.bottom);
            Snack.setInsets(typing.top, typing.bottom);
            return insets;
        });

        // Header: time and date on the left, GPS status and settings on the right.
        LinearLayout header = Ui.row(this);
        LinearLayout time = Ui.column(this);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.T_TITLE);
        clock.setTextColor(Ui.TEXT);
        clock.setTypeface(Ui.monoBold);
        clock.setFontFeatureSettings("tnum");
        clock.setIncludeFontPadding(false);
        time.addView(clock);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEE d MMM");
        date.setFormat24Hour("EEE d MMM");
        date.setAllCaps(true);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.T_LABEL);
        date.setTextColor(Ui.TEXT_3);
        date.setTypeface(Ui.mono);
        date.setLetterSpacing(0.1f);
        date.setIncludeFontPadding(false);
        time.addView(date, Ui.top(this, Ui.wrap(), 4));
        header.addView(time, Ui.weighted());
        gpsStatus = new Ui.Pill(this);
        gpsStatus.setOnClickListener(v -> {
            if (!hasLocationPermission()) requestLocation(false);
        });
        header.addView(gpsStatus, Ui.wrap());
        View gear = Ui.iconButton(this, Icons.SETTINGS, "Settings");
        gear.setOnClickListener(v -> settings.open());
        header.addView(gear, Ui.left(this, new LinearLayout.LayoutParams(dp(48), dp(48)), 4));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        headerLp.rightMargin = -dp(12); // the gear's glyph sits on the page margin
        root.addView(header, headerLp);

        // Speedometer. Takes the spare height; tap switches mph / km/h.
        gauge = new GaugeView(this);
        gauge.setOnClickListener(v -> {
            final boolean was = Units.metric(this);
            prefs.edit().putBoolean("metric", !was).apply();
            render();
            settings.refresh();
        });
        root.addView(gauge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Trip stats: two rows of three inside one card.
        LinearLayout stats = Ui.column(this);
        stats.setBackground(Ui.panel(this, Ui.SURFACE, Ui.HAIRLINE, 16));
        stats.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout statsTop = Ui.row(this);
        tripValue = stat(statsTop, "TRIP");
        timeValue = stat(statsTop, "MOVING");
        topValue = stat(statsTop, "TOP");
        stats.addView(statsTop, Ui.matchWrap());
        View rule = Ui.divider(this);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        ruleLp.topMargin = dp(12);
        ruleLp.bottomMargin = dp(12);
        stats.addView(rule, ruleLp);
        LinearLayout statsBottom = Ui.row(this);
        avgValue = stat(statsBottom, "AVG");
        stopsValue = stat(statsBottom, "STOPS");
        climbValue = stat(statsBottom, "CLIMB");
        stats.addView(statsBottom, Ui.matchWrap());
        root.addView(stats, Ui.matchWrap());

        // Primary action.
        rideButtonSlot = new FrameLayout(this);
        root.addView(rideButtonSlot, Ui.top(this, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)), 12));

        // Bike link: battery and controller at a glance.
        bikeStrip = Ui.row(this);
        bikeStrip.setBackground(Ui.pressable(this, Ui.SURFACE, Ui.HAIRLINE, 16));
        bikeStrip.setPadding(dp(16), dp(8), dp(12), dp(8));
        bikeStrip.setOnClickListener(v -> bike.open());
        root.addView(bikeStrip, Ui.top(this, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)), 12));
        renderBikeStrip();

        // Ride apps: tap to open, long-press to change.
        slotRow = Ui.row(this);
        root.addView(slotRow, Ui.top(this, Ui.matchWrap(), 12));

        // Dock: the other destinations.
        LinearLayout dock = Ui.row(this);
        addDock(dock, Icons.LAYOUT_GRID, "Apps", v -> drawer.open());
        addDock(dock, Icons.ROUTE, "Rides", v -> rides.open());
        addDock(dock, Icons.SPARKLES, "Copilot", v -> copilot.open());
        addDock(dock, Icons.MONITOR, "Deck", v -> deck.open());
        root.addView(dock, Ui.top(this, Ui.matchWrap(), 4));

        return frame;
    }

    private TextView stat(LinearLayout parent, String name) {
        LinearLayout col = Ui.column(this);
        col.addView(Ui.label(this, name), Ui.matchWrap());
        TextView v = Ui.value(this, "", Ui.T_TITLE);
        col.addView(v, Ui.top(this, Ui.matchWrap(), 8));
        parent.addView(col, Ui.weighted());
        return v;
    }

    private void addDock(LinearLayout parent, String icon, String name,
            View.OnClickListener click) {
        LinearLayout item = Ui.column(this);
        item.setGravity(Gravity.CENTER);
        item.setBackground(Ui.ghost(this, 12));
        item.addView(Ui.icon(this, icon, 22, Ui.TEXT));
        TextView l = Ui.small(this, name, Ui.TEXT_2);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.T_LABEL);
        l.setIncludeFontPadding(false);
        item.addView(l, Ui.top(this, Ui.wrap(), 6));
        item.setOnClickListener(click);
        item.setContentDescription(name);
        Ui.press(item);
        parent.addView(item, new LinearLayout.LayoutParams(0, dp(56), 1f));
    }

    private void renderSlots() {
        slotRow.removeAllViews();
        PackageManager pm = getPackageManager();
        for (int i = 0; i < SLOT_COUNT; i++) {
            final int slot = i;
            final Intent launch = slotIntent(slot);
            ResolveInfo info = launch == null ? null : pm.resolveActivity(launch, 0);
            LinearLayout t;
            if (info == null) {
                t = slotTile(null, "Add app");
                t.setOnClickListener(v -> showAppPicker(slot));
            } else {
                t = slotTile(info.loadIcon(pm), info.loadLabel(pm).toString());
                t.setOnClickListener(v -> launch(launch));
            }
            t.setOnLongClickListener(v -> {
                showAppPicker(slot);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(72), 1f);
            if (i > 0) lp.leftMargin = dp(8);
            slotRow.addView(t, lp);
        }
    }

    /** A filled slot shows the app; an empty one is a dashed outline with a plus. */
    private LinearLayout slotTile(Drawable appIcon, String name) {
        LinearLayout t = Ui.column(this);
        t.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        if (appIcon != null) {
            icon.setImageDrawable(appIcon);
            t.setBackground(Ui.pressable(this, Ui.SURFACE, Ui.HAIRLINE, 16));
            t.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        } else {
            icon.setImageDrawable(Icons.of(this, Icons.PLUS, Ui.TEXT_3));
            GradientDrawable dashed = new GradientDrawable();
            dashed.setCornerRadius(dp(16));
            dashed.setStroke(Math.max(1, dp(1)), Ui.HAIRLINE_HI, dp(4), dp(4));
            t.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x24FFFFFF), dashed,
                    Ui.panel(this, 0xFFFFFFFF, 0, 16)));
            t.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        }
        TextView l = Ui.small(this, name, appIcon == null ? Ui.TEXT_3 : Ui.TEXT_2);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.T_LABEL);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        l.setGravity(Gravity.CENTER);
        l.setIncludeFontPadding(false);
        l.setPadding(dp(4), 0, dp(4), 0);
        t.addView(l, Ui.top(this, Ui.matchWrap(), appIcon == null ? 8 : 6));
        t.setContentDescription(name);
        Ui.press(t);
        return t;
    }

    /** The app assigned to a slot, or the bundled maps app for an unset slot 0. */
    private Intent slotIntent(int slot) {
        String saved = prefs.getString("slot" + slot, null);
        if (saved != null) {
            ComponentName cn = ComponentName.unflattenFromString(saved);
            return cn == null ? null : AppDrawer.launchIntent(cn);
        }
        if (slot == 0) {
            return getPackageManager().getLaunchIntentForPackage(DEFAULT_MAPS_PACKAGE);
        }
        return null;
    }

    /** Pick the app for a slot, or clear it. */
    private void showAppPicker(final int slot) {
        Sheet sheet = new Sheet(frame, "Ride app " + (slot + 1),
                "Shown on the dashboard for one-tap access while riding.");
        if (slotIntent(slot) != null) {
            // An empty value (rather than removal) keeps slot 0 from falling
            // back to the bundled maps app again.
            sheet.item(Icons.X, "Clear this slot", null, true,
                    () -> setSlot(slot, "", "Slot cleared"));
        }
        List<AppDrawer.App> apps = drawer.apps();
        for (final AppDrawer.App app : apps) {
            sheet.item(app.icon, app.label, () -> setSlot(slot,
                    app.component.flattenToString(), app.label + " pinned"));
        }
        if (apps.isEmpty()) sheet.view(Ui.body(this, "No apps installed yet", Ui.TEXT_3));
        sheet.show();
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}
