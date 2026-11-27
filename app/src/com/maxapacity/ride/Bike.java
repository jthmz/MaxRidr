package com.maxapacity.ride;

import android.bluetooth.le.ScanCallback;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The bike link: one ANT BMS (battery) and one Fardriver controller, each
 * over its own Bluetooth connection. Holds the latest readings for the
 * dashboard and the Bike screen, and carries out controller tuning.
 *
 * The battery side is read-only. The controller side can change the settings
 * listed in {@link Fardriver#PARAMS}, in three deliberate steps:
 *
 *   1. try:    write the new values to the controller's RAM and read each one
 *              back. Live at once, gone at the next power-off.
 *   2. save:   store what is in RAM to the controller's flash.
 *   3. revert: write the values from before step 1 back.
 *
 * Writes are refused while the wheel is turning, a backup of every setting is
 * saved to the phone before the first write, and a fault that appears right
 * after a change undoes it automatically.
 *
 * Main thread only.
 */
final class Bike {

    /** Makes the two Bluetooth links. Tests swap in a simulated bike here. */
    interface Links {
        BleLink make(Context c, java.util.UUID characteristic, BleLink.Listener listener);
    }

    static Links links = BleLink::new;

    private static Bike instance;

    static Bike get(Context c) {
        if (instance == null) instance = new Bike(c.getApplicationContext());
        return instance;
    }

    private static final long BMS_POLL_MS = 1000;
    private static final long BMS_REPLY_TIMEOUT_MS = 2500;
    private static final long HELLO_MS = 1000;
    private static final long STALE_MS = 6000;
    private static final long RETRY_MS = 15000;
    private static final long VERIFY_TIMEOUT_MS = 6000;
    private static final long FAULT_WATCH_MS = 6000;
    /**
     * A setting is only changed on a reading this fresh. The live block comes
     * round about five times a second, so anything older means the stream
     * has stalled and the wheel may no longer be where it was last seen.
     */
    private static final long FRESH_MS = 2000;

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean active;
    /** The pretend bike is standing in for a real one. Never saved. */
    private boolean demo;

    // ---- Battery -----------------------------------------------------------

    private final AntBms.Assembler bmsFrames = new AntBms.Assembler();
    private BleLink bmsLink;
    private AntBms.Data battery;
    private long batteryAtMs;
    private String batteryStatus = "Not linked";
    private boolean bmsLegacy;
    /** This unit answered the older request last time. */
    private boolean bmsOlderUnit;
    private int bmsMisses;
    private long bmsAskedAtMs;

    // ---- Controller --------------------------------------------------------

    private final Fardriver controllerMemory = new Fardriver();
    private BleLink controllerLink;
    private Fardriver.Live controller;
    private long controllerAtMs;
    /** When the live block last arrived, and how many had arrived by then. */
    private long liveAtMs;
    private int liveSeen;
    /**
     * Counts up whenever the controller on the other end may have changed
     * (the demo swapped in or out, a controller linked or unlinked). Steps
     * still waiting from before that give up without touching anything.
     */
    private int linkEpoch;
    private String controllerStatus = "Not linked";
    private boolean backedUp;

    private Bike(Context c) {
        context = c;
        prefs = Units.prefs(c);
        makeLinks();
    }

    /** The two links, to the real bike or to the pretend one. */
    private void makeLinks() {
        final Context c = context;
        Links make = demo ? DemoBike::make : links;
        bmsLink = make.make(c, BleLink.uuid(0xFFE1), new BleLink.Listener() {
            @Override
            public void onReady() {
                batteryStatus = "Reading";
                bmsFrames.reset();
                bmsMisses = 0;
                bmsAskedAtMs = 0;
                // Every session asks in the current protocol first: boards
                // from 2021 on answer both, and the older answer says less.
                // A unit known to be older is asked its own way after one
                // silence instead of two.
                bmsLegacy = false;
                bmsOlderUnit = !demo && prefs.getBoolean("bike_bms_legacy", false);
                main.removeCallbacks(bmsPoll);
                main.post(bmsPoll);
                changed();
            }

            @Override
            public void onData(byte[] value) {
                AntBms.Data d = bmsFrames.feed(value);
                if (d == null) return;
                if (prefs.getBoolean("bike_bms_invert", false)) {
                    d.amps = -d.amps;
                    d.powerW = -d.powerW;
                }
                battery = d;
                batteryAtMs = SystemClock.elapsedRealtime();
                bmsAskedAtMs = 0;
                bmsMisses = 0;
                batteryStatus = "Live";
                if (!demo && (!prefs.contains("bike_bms_legacy")
                        || d.legacy != prefs.getBoolean("bike_bms_legacy", false))) {
                    prefs.edit().putBoolean("bike_bms_legacy", d.legacy).apply();
                }
                logForRide(false);
                changed();
            }

            @Override
            public void onClosed(String why) {
                main.removeCallbacks(bmsPoll);
                bmsAskedAtMs = 0;
                battery = null;
                batteryStatus = why;
                changed();
                retryLater();
            }
        });

        controllerLink = make.make(c, BleLink.uuid(0xFFEC), new BleLink.Listener() {
            @Override
            public void onReady() {
                controllerStatus = "Reading";
                controller = null;
                controllerMemory.reset();
                liveSeen = 0;
                liveAtMs = 0;
                backedUp = false;
                // Values on trial when the link went away may or may not have
                // survived (a power cycle discards them): look again once the
                // settings have been read back.
                tryNeedsRecheck = tuneState == TRYING;
                main.removeCallbacks(hello);
                main.post(hello);
                changed();
            }

            @Override
            public void onData(byte[] value) {
                if (!controllerMemory.feed(value)) return;
                int live = controllerMemory.seenCount(0xE2);
                if (live != liveSeen) {
                    liveSeen = live;
                    liveAtMs = SystemClock.elapsedRealtime();
                }
                if (tryNeedsRecheck && controllerMemory.settingsLoaded()) recheckTry();
                Fardriver.Live l = controllerMemory.live();
                if (l == null) return;
                controller = l;
                controllerAtMs = SystemClock.elapsedRealtime();
                controllerStatus = "Live";
                logForRide(true);
                changed();
            }

            @Override
            public void onClosed(String why) {
                main.removeCallbacks(hello);
                controller = null;
                controllerStatus = why;
                // A change in flight notices the dead link itself and reports
                // it. Values already on trial are kept, so Undo still knows
                // what to put back when the link returns.
                changed();
                retryLater();
            }
        });
    }

    /**
     * While a ride is recording, every reading goes into its log of what the
     * bike did. The pretend bike's made-up numbers never do.
     */
    private void logForRide(boolean fromController) {
        RideState ride = RideState.get();
        if (demo || !ride.isRecording()) return;
        ride.bikeLog().add(battery(), controller(), fromController, SystemClock.elapsedRealtime());
    }

    // ---- Listeners ---------------------------------------------------------

    void addListener(Runnable r) {
        if (!listeners.contains(r)) listeners.add(r);
    }

    void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void changed() {
        for (int i = listeners.size() - 1; i >= 0; i--) listeners.get(i).run();
    }

    // ---- Lifecycle ---------------------------------------------------------

    /** Connect whatever is linked. Safe to call often. */
    void resume() {
        active = true;
        connectMissing();
    }

    /** Drop both links (the launcher left the screen and no ride is recording). */
    void pause() {
        if (tuneState == APPLYING || tuneState == SAVING || tuneState == REVERTING) return;
        active = false;
        main.removeCallbacks(retry);
        main.removeCallbacks(bmsPoll);
        main.removeCallbacks(hello);
        bmsLink.close();
        controllerLink.close();
        battery = null;
        controller = null;
        if (batteryLinked()) batteryStatus = "Paused";
        if (controllerLinked()) controllerStatus = "Paused";
    }

    private final Runnable retry = this::connectMissing;

    private void retryLater() {
        if (!active) return;
        main.removeCallbacks(retry);
        main.postDelayed(retry, RETRY_MS);
    }

    private void connectMissing() {
        if (!active) return;
        // The pretend bike uses no radio, so it needs no permission.
        if (!demo && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (batteryLinked() && !bmsLink.isOpen()) {
            batteryStatus = "Connecting";
            bmsLink.open(prefs.getString("bike_bms_name", ""));
        }
        if (controllerLinked() && !controllerLink.isOpen()) {
            controllerStatus = "Connecting";
            controllerLink.open(prefs.getString("bike_ctl_name", ""));
        }
        changed();
    }

    // ---- Linking -----------------------------------------------------------

    static final int KIND_BATTERY = 0;
    static final int KIND_CONTROLLER = 1;

    /** Whether an advertised name looks like the given kind of device. */
    static boolean matches(int kind, String name) {
        String n = name.toUpperCase(Locale.US);
        if (kind == KIND_BATTERY) return n.startsWith("ANT-BLE") || n.startsWith("ANT@BLE");
        return n.contains("YUANQU") || n.contains("CONTROL");
    }

    ScanCallback scan(BleLink.Finder finder) {
        return BleLink.scan(context, finder);
    }

    void stopScan(ScanCallback cb) {
        BleLink.stopScan(context, cb);
    }

    boolean batteryLinked() {
        return demo || !prefs.getString("bike_bms_name", "").isEmpty();
    }

    boolean controllerLinked() {
        return demo || !prefs.getString("bike_ctl_name", "").isEmpty();
    }

    // ---- The pretend bike --------------------------------------------------

    /** True while the pretend bike stands in for a real one. */
    boolean demo() {
        return demo;
    }

    /**
     * Swaps the real links for the pretend bike, or back. It lives only in
     * memory: restarting the app is back to the real bike, and nothing saved
     * about a real bike, on the phone or on the bike, is touched.
     *
     * @return false when a change to a real controller is under way and this must wait
     */
    boolean setDemo(boolean on) {
        if (on == demo) return true;
        // A real controller with values on trial is not walked away from.
        if (on && tuneState != TUNE_IDLE) return false;
        main.removeCallbacks(retry);
        main.removeCallbacks(bmsPoll);
        main.removeCallbacks(hello);
        bmsLink.close();
        controllerLink.close();
        battery = null;
        controller = null;
        // Whatever was on trial belonged to the bike that is going away.
        abortTuning();
        backedUp = false;
        demo = on;
        makeLinks();
        batteryStatus = batteryLinked() ? "Connecting" : "Not linked";
        controllerStatus = controllerLinked() ? "Connecting" : "Not linked";
        active = true;
        connectMissing();
        changed();
        return true;
    }

    boolean anyConfigured() {
        return batteryLinked() || controllerLinked();
    }

    String batteryName() {
        return demo ? DemoBike.BATTERY_NAME : prefs.getString("bike_bms_name", "");
    }

    String controllerName() {
        return demo ? DemoBike.CONTROLLER_NAME : prefs.getString("bike_ctl_name", "");
    }

    void link(int kind, String name) {
        // Linking a real bike ends the pretend one.
        setDemo(false);
        if (kind == KIND_BATTERY) {
            bmsLink.close();
            battery = null;
            prefs.edit().putString("bike_bms_name", name).remove("bike_bms_legacy").apply();
        } else {
            controllerLink.close();
            abortTuning();
            prefs.edit().putString("bike_ctl_name", name).apply();
        }
        active = true;
        connectMissing();
    }

    /** @return the name that was linked, so the caller can offer to undo */
    String unlink(int kind) {
        String was;
        if (kind == KIND_BATTERY) {
            was = batteryName();
            main.removeCallbacks(bmsPoll);
            bmsLink.close();
            battery = null;
            batteryStatus = "Not linked";
            prefs.edit().remove("bike_bms_name").apply();
        } else {
            was = controllerName();
            main.removeCallbacks(hello);
            controllerLink.close();
            controller = null;
            controllerStatus = "Not linked";
            abortTuning();
            prefs.edit().remove("bike_ctl_name").apply();
        }
        changed();
        return was;
    }

    String configuredSummary() {
        if (batteryLinked() && controllerLinked()) return "Battery and controller linked";
        return batteryLinked() ? "Battery linked" : "Controller linked";
    }

    // ---- Readings ----------------------------------------------------------

    /** Latest battery reading, or null when there is none fresh enough to show. */
    AntBms.Data battery() {
        if (battery != null && SystemClock.elapsedRealtime() - batteryAtMs > STALE_MS) {
            return null;
        }
        return battery;
    }

    Fardriver.Live controller() {
        if (controller != null && SystemClock.elapsedRealtime() - controllerAtMs > STALE_MS) {
            return null;
        }
        return controller;
    }

    String batteryProtocol() {
        if (battery != null) return battery.legacy ? "ANT · Legacy" : "ANT · Current";
        if (!demo && batteryLinked() && prefs.contains("bike_bms_legacy")) {
            return prefs.getBoolean("bike_bms_legacy", false) ? "ANT · Legacy" : "ANT · Current";
        }
        return "Detecting ANT protocol";
    }

    String batteryStatus() {
        return batteryStatus;
    }

    String controllerStatus() {
        return controllerStatus;
    }

    Fardriver controllerMemory() {
        return controllerMemory;
    }

    /** Live power in watts: from the controller if linked, else the battery. */
    float powerW() {
        Fardriver.Live c = controller();
        if (c != null) return c.powerW();
        AntBms.Data b = battery();
        return b == null ? 0f : b.powerW;
    }

    /** The most power the current tune can draw, for scaling a power meter. */
    float peakPowerW() {
        Fardriver.Live c = controller();
        if (c != null && !controllerMemory.isLegacy() && controllerMemory.seenCount(0x19) > 0) {
            float amps = controllerMemory.word(0x19) / 4f;
            if (amps > 0 && c.volts > 0) return amps * c.volts;
        }
        return Math.max(prefs.getFloat("bike_peak_w", 6000f), 1000f);
    }

    /** Changes whenever anything the dashboard strip shows changes. */
    String summaryKey() {
        AntBms.Data b = battery();
        Fardriver.Live c = controller();
        return batteryName() + "|" + controllerName() + "|" + batteryStatus + "|"
                + controllerStatus + "|"
                + (b == null ? "" : b.soc + "/" + Math.round(b.volts * 10) + "/" + b.hasProblem())
                + "|" + (c == null ? "" : Math.round(c.powerW() / 100f) + "/" + c.mosfetTempC
                + "/" + c.faults + "/" + c.speedMode)
                + "|" + Math.round(powerW() / 100f);
    }

    private final Runnable bmsPoll = new Runnable() {
        @Override
        public void run() {
            if (!bmsLink.isReady()) return;
            long now = SystemClock.elapsedRealtime();
            if (bmsAskedAtMs != 0 && now - bmsAskedAtMs > BMS_REPLY_TIMEOUT_MS) {
                bmsAskedAtMs = 0;
                bmsMisses++;
                // A unit that never answers one protocol is asked in the other.
                if (battery == null && (bmsMisses % 2 == 0 || (bmsMisses == 1 && bmsOlderUnit))) {
                    bmsLegacy = !bmsLegacy;
                }
                if (bmsMisses >= 10) {
                    bmsLink.close();
                    battery = null;
                    batteryStatus = "The battery stopped answering";
                    changed();
                    retryLater();
                    return;
                }
            }
            if (bmsAskedAtMs == 0) {
                bmsFrames.reset();
                bmsLink.write(bmsLegacy ? AntBms.REQUEST_LEGACY : AntBms.REQUEST_NEW);
                bmsAskedAtMs = now;
            }
            main.postDelayed(this, BMS_POLL_MS);
        }
    };

    private final Runnable hello = new Runnable() {
        @Override
        public void run() {
            if (!controllerLink.isReady()) return;
            controllerLink.write(Fardriver.HELLO);
            main.postDelayed(this, HELLO_MS);
        }
    };

    // ---- Tuning ------------------------------------------------------------

    static final int TUNE_IDLE = 0;
    static final int APPLYING = 1;
    /** New values are live in the controller's RAM, not yet saved. */
    static final int TRYING = 2;
    static final int SAVING = 3;
    static final int REVERTING = 4;

    interface TuneCallback {
        void done(boolean ok, String message);
    }

    private int tuneState = TUNE_IDLE;
    private boolean tryNeedsRecheck;
    /** Values on the controller before the current try, by setting key. */
    private final Map<String, Integer> before = new LinkedHashMap<>();
    /** The values now on trial, by setting key: what a Save would keep. */
    private final Map<String, Integer> tried = new LinkedHashMap<>();
    /** Ceilings for the pretend bike, which never touch what is saved on the phone. */
    private final Map<String, Integer> demoCaps = new LinkedHashMap<>();

    int tuneState() {
        return tuneState;
    }

    boolean hasUnsavedTry() {
        return tuneState == TRYING;
    }

    /**
     * The highest value a setting may be given on the controller in hand.
     *
     * Currents never go above what this unit was first seen with: the phone
     * cannot know what the hardware was built for, so it never raises them.
     * For the same reason no gear speed goes above the top gear's speed as
     * this unit was first seen. Regen keeps the official app's limits, unless
     * the unit shipped with more.
     */
    int ceiling(Fardriver.Param p) {
        if (p.key.endsWith("_rpm")) {
            return Math.min(firstSeen(Fardriver.param("high_rpm")), p.max);
        }
        boolean regen = p.key.equals("regen") || p.key.equals("regen_max");
        if (!p.cappedAtFactory && !regen) return p.max;
        int cap = firstSeen(p);
        return regen ? Math.max(cap, p.max) : Math.min(cap, p.max);
    }

    /**
     * The value this very controller held when the app first met it, kept by
     * serial number so that another unit with the same Bluetooth name cannot
     * borrow its ceilings.
     */
    private int firstSeen(Fardriver.Param p) {
        int now = controllerMemory.raw(p);
        // A value on trial is not yet how the controller stands: it may be undone.
        boolean settled = tuneState == TUNE_IDLE;
        if (demo) {
            Integer cap = demoCaps.get(p.key);
            if (cap == null || (now > cap && settled)) demoCaps.put(p.key, cap = now);
            return cap;
        }
        String serial = controllerMemory.serial();
        String key = "bike_cap_" + (serial.isEmpty() ? controllerName() : serial) + "_" + p.key;
        int cap = prefs.getInt(key, -1);
        if (cap < 0 || (now > cap && settled)) {
            // First sight of this controller, or it now stands higher: raised elsewhere, or
            // by the one whole tune this app allows above what a controller came with.
            cap = now;
            prefs.edit().putInt(key, cap).apply();
        }
        return cap;
    }

    /** Why a change cannot be sent right now, or null if it can. */
    String writeBlocker() {
        String blocked = controllerMemory.tuningBlocker();
        if (blocked != null) return blocked;
        Fardriver.Live l = controller();
        if (l == null || !controllerLink.isReady()) return "The controller is not connected";
        if (SystemClock.elapsedRealtime() - liveAtMs > FRESH_MS) {
            return "Waiting for a fresh reading from the controller";
        }
        if (l.rpm != 0 || controllerMemory.rolling()) {
            return "Stop the wheel first. Settings only change at a standstill";
        }
        if (controllerMemory.throttleOpen()) return "Close the throttle first";
        if (l.faults != 0) return "Clear the controller fault first: " + l.faultNames().get(0);
        if (RideState.get().isRecording()) return "End the ride before changing settings";
        return null;
    }

    /**
     * What is wrong with how the settings would sit against each other once
     * these changes are in, or null. Only a relation a change touches is
     * judged, so a bike that came with an odd pairing can still have
     * something unrelated changed.
     */
    private String orderProblem(Map<String, Integer> changes) {
        String[][] pairs = {{"low_rpm", "mid_rpm"}, {"mid_rpm", "high_rpm"},
                {"regen", "regen_max"}, BURSTS[0], BURSTS[1]};
        for (String[] pair : pairs) {
            if (!changes.containsKey(pair[0]) && !changes.containsKey(pair[1])) continue;
            Fardriver.Param lower = Fardriver.param(pair[0]);
            Fardriver.Param upper = Fardriver.param(pair[1]);
            // A controller that came with its burst below its limit is not held to the rule
            // it never kept: lowering its limit must stay possible.
            if (burstsApart(pair)) continue;
            int lo = changes.containsKey(pair[0]) ? changes.get(pair[0])
                    : controllerMemory.raw(lower);
            int hi = changes.containsKey(pair[1]) ? changes.get(pair[1])
                    : controllerMemory.raw(upper);
            if (lo > hi) return lower.name + " cannot be above " + upper.name;
        }
        return null;
    }

    /** Each current limit, and the burst allowance that must never sit below it. */
    private static final String[][] BURSTS = {{"line", "boost_line"}, {"phase", "boost_phase"}};

    /** True for a burst pair on a controller that came with the burst below the limit. */
    private boolean burstsApart(String[] pair) {
        if (pair != BURSTS[0] && pair != BURSTS[1]) return false;
        return ceiling(Fardriver.param(pair[1])) < ceiling(Fardriver.param(pair[0]));
    }

    /**
     * A change to a current limit takes its burst allowance with it. The
     * burst stays as far above the limit as this controller came with, so it
     * is never below the limit and never above where it started. Without
     * this, a limit lowered for a calmer bike could still be exceeded for
     * seconds at a time on a bike that uses bursts.
     *
     * @return the changes asked for, plus the burst settings that go with them
     */
    Map<String, Integer> withBursts(Map<String, Integer> asked) {
        Map<String, Integer> out = new LinkedHashMap<>(asked);
        for (String[] pair : BURSTS) {
            Integer limit = asked.get(pair[0]);
            if (limit == null || asked.containsKey(pair[1]) || burstsApart(pair)) continue;
            Fardriver.Param burst = Fardriver.param(pair[1]);
            int cameLimit = ceiling(Fardriver.param(pair[0]));
            int cameBurst = ceiling(burst);
            int wanted = Math.min(cameBurst, limit + (cameBurst - cameLimit));
            if (wanted != controllerMemory.raw(burst)) out.put(pair[1], wanted);
        }
        return out;
    }

    /**
     * The order to write a set of values in so that the settings never pass
     * through a pairing the rules forbid on the way there: rises go in from
     * the top down, then everything that has no order, then drops from the
     * bottom up.
     */
    private List<String> safeOrder(Map<String, Integer> target) {
        String[] topDown = {"high_rpm", "mid_rpm", "low_rpm", "regen_max", "regen",
                "boost_line", "line", "boost_phase", "phase"};
        List<String> out = new ArrayList<>();
        for (String key : topDown) {
            Integer v = target.get(key);
            if (v != null && v > controllerMemory.raw(Fardriver.param(key))) out.add(key);
        }
        for (String key : target.keySet()) {
            boolean ordered = false;
            for (String o : topDown) ordered |= o.equals(key);
            if (!ordered) out.add(key);
        }
        for (int i = topDown.length - 1; i >= 0; i--) {
            if (target.containsKey(topDown[i]) && !out.contains(topDown[i])) out.add(topDown[i]);
        }
        return out;
    }

    /** A whole tune allowed above this controller's ceilings on the next attempt, or null. */
    private Map<String, Integer> allowance;

    /**
     * Lets the next {@link #tryValues} take these exact values even where they
     * are above what this controller came with, provided they all go in
     * together. For the one tune proven on its own controller; the caller has
     * checked that this is that controller. Good for one attempt.
     */
    void allowWhole(Map<String, Integer> exact) {
        allowance = new LinkedHashMap<>(exact);
    }

    /**
     * Step 1: write the changed settings to RAM, verifying each by reading it
     * back, then watch for a fault.
     *
     * If any part fails, every setting is written back to what it was and
     * read back again; only then does the attempt count as not having
     * happened. If even that cannot be done (the link dropped, the wheel
     * started turning), the values stay "on trial" so Undo can finish the
     * job later.
     */
    void tryValues(final Map<String, Integer> asked, final TuneCallback cb) {
        final Map<String, Integer> whole = allowance;
        allowance = null;
        String blocked = writeBlocker();
        if (blocked != null) {
            cb.done(false, blocked);
            return;
        }
        for (Map.Entry<String, Integer> e : asked.entrySet()) {
            if (Fardriver.param(e.getKey()) == null || e.getValue() == null) {
                cb.done(false, "That setting is not one this app can change");
                return;
            }
        }
        final Map<String, Integer> changes = withBursts(asked);
        if (tuneState != TUNE_IDLE && tuneState != TRYING) {
            cb.done(false, "Another change is still in progress");
            return;
        }
        // Check the whole request before sending any of it, so a bad value
        // late in the list cannot leave the earlier ones half applied.
        for (Map.Entry<String, Integer> e : changes.entrySet()) {
            Fardriver.Param p = Fardriver.param(e.getKey());
            if (p == null || e.getValue() == null) {
                cb.done(false, "That setting is not one this app can change");
                return;
            }
            boolean allowed = whole != null && e.getValue().equals(whole.get(e.getKey()));
            if (e.getValue() < p.min || (e.getValue() > ceiling(p) && !allowed)) {
                cb.done(false, p.name + " is outside its safe range. Nothing was changed");
                return;
            }
        }
        if (whole != null) {
            // Above what the controller came with only as the whole tune: every one of its
            // values must be on the bike once this is done.
            for (Map.Entry<String, Integer> e : whole.entrySet()) {
                Fardriver.Param p = Fardriver.param(e.getKey());
                Integer to = changes.get(e.getKey());
                if (p == null || (to != null ? to : controllerMemory.raw(p)) != e.getValue()) {
                    cb.done(false, "That tune only goes in whole. Nothing was changed");
                    return;
                }
            }
        }
        String order = orderProblem(changes);
        if (order != null) {
            cb.done(false, order + ". Nothing was changed");
            return;
        }
        if (!backedUp) {
            // A pretend controller has nothing worth keeping a copy of.
            if (!demo && !saveBackup()) {
                cb.done(false, "A backup of the current settings could not be saved, so"
                        + " nothing was changed");
                return;
            }
            backedUp = true;
        }
        final boolean wasTrying = tuneState == TRYING;
        final List<String> keys = safeOrder(changes);
        // Undone in the opposite order, for the same reason.
        final List<String> backwards = new ArrayList<>(keys);
        java.util.Collections.reverse(backwards);
        // What each setting holds right now, to put a failed attempt back
        // exactly; and what it held before any trial began, for Undo.
        final Map<String, Integer> prior = new LinkedHashMap<>();
        final List<String> newlyTracked = new ArrayList<>();
        for (String key : keys) {
            int now = controllerMemory.raw(Fardriver.param(key));
            prior.put(key, now);
            if (!before.containsKey(key)) {
                before.put(key, now);
                newlyTracked.add(key);
            }
        }
        final Map<String, Integer> triedBefore = new LinkedHashMap<>(tried);
        final int faultsBefore = controllerMemory.word(0xE3) & 0x7FFF;
        controllerMemory.clearFaultLatch();
        final int epoch = linkEpoch;
        tuneState = APPLYING;
        changed();
        Steps steps = new Steps(keys, changes, epoch).watching(faultsBefore);
        steps.whole = whole;
        writeSequence(steps, 0, (ok, message) -> {
            if (ok) {
                tried.putAll(changes);
                tuneState = TRYING;
                changed();
                cb.done(true, "Live on the bike. Not saved yet");
                watchForFault(faultsBefore, epoch, cb);
                return;
            }
            final String why = message;
            tuneState = REVERTING;
            changed();
            // Every setting is written back whatever the phone last read of
            // it: a write can land without its read-back ever arriving, and
            // a fault is put right at once even if the wheel is turning.
            writeSequence(new Steps(backwards, prior, epoch).restoring(), 0, (ok2, m2) -> {
                if (ok2) {
                    for (String key : newlyTracked) before.remove(key);
                    tuneState = wasTrying ? TRYING : TUNE_IDLE;
                    changed();
                    cb.done(false, why + ". Nothing was changed");
                } else {
                    tried.clear();
                    tried.putAll(triedBefore);
                    tried.putAll(changes);
                    tuneState = TRYING;
                    changed();
                    cb.done(false, why + ". Part of the change may be live on the bike. Tap"
                            + " Undo once it is stopped and connected, or switch the bike off"
                            + " and on");
                }
            });
        });
    }

    /** A fault raised at any moment of the watch undoes the change, wheel turning or not. */
    private void watchForFault(final int faultsBefore, final int epoch, final TuneCallback cb) {
        final long until = SystemClock.elapsedRealtime() + FAULT_WATCH_MS;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (epoch != linkEpoch || tuneState != TRYING) return;
                if ((controllerMemory.faultLatch() & ~faultsBefore) != 0) {
                    revert(true, (ok, m) -> cb.done(false, ok
                            ? "The controller raised a fault after the change, so it was undone"
                            : "The controller raised a fault after the change. " + m));
                    return;
                }
                if (SystemClock.elapsedRealtime() < until) main.postDelayed(this, 200);
            }
        }, 200);
    }

    /** A run of settings to write, and how carefully. */
    private static final class Steps {
        final List<String> order;
        final Map<String, Integer> values;
        final int epoch;
        /**
         * Putting old values back: each is sent even if the phone believes
         * it is already there, may lie outside what this app would choose,
         * and does not wait for the wheel to stop.
         */
        boolean restoring;
        /** Fault bits that were already set; any new one stops the run. -1: not watched. */
        int faultsBefore = -1;
        /** Values that may go above the controller's ceilings, as {@link #allowWhole} gave them. */
        Map<String, Integer> whole;

        Steps(List<String> order, Map<String, Integer> values, int epoch) {
            this.order = order;
            this.values = values;
            this.epoch = epoch;
        }

        Steps restoring() {
            restoring = true;
            return this;
        }

        Steps watching(int faults) {
            faultsBefore = faults;
            return this;
        }
    }

    /** Writes one setting, waits until it reads back twice, then moves to the next. */
    private void writeSequence(final Steps steps, final int index, final TuneCallback cb) {
        // The controller this was meant for has gone: do nothing more, say nothing.
        if (steps.epoch != linkEpoch) return;
        if (index >= steps.order.size()) {
            cb.done(true, null);
            return;
        }
        if (!controllerLink.isReady()) {
            cb.done(false, "The controller link dropped");
            return;
        }
        final Fardriver.Param p = Fardriver.param(steps.order.get(index));
        final Integer target = steps.values.get(steps.order.get(index));
        if (p == null || target == null
                || (!steps.restoring && controllerMemory.raw(p) == target)) {
            writeSequence(steps, index + 1, cb);
            return;
        }
        if (!steps.restoring) {
            Fardriver.Live l = controller();
            if (l == null || SystemClock.elapsedRealtime() - liveAtMs > FRESH_MS) {
                cb.done(false, "The controller stopped answering");
                return;
            }
            if (l.rpm != 0 || controllerMemory.rolling()) {
                cb.done(false, "The wheel started turning");
                return;
            }
            if (steps.faultsBefore >= 0
                    && (controllerMemory.faultLatch() & ~steps.faultsBefore) != 0) {
                cb.done(false, "The controller raised a fault during the change");
                return;
            }
        }
        final byte[] frame;
        final int expected;
        try {
            int top = ceiling(p);
            if (steps.whole != null && target.equals(steps.whole.get(p.key))) {
                top = Math.max(top, target);
            }
            frame = controllerMemory.writeFrame(p, target, top, steps.restoring);
            expected = controllerMemory.expectedWord(p, target);
        } catch (IllegalArgumentException e) {
            cb.done(false, p.name + " is outside its safe range");
            return;
        }
        controllerLink.write(frame);
        final int seenAtWrite = controllerMemory.seenCount(p.addr);
        final long deadline = SystemClock.elapsedRealtime() + VERIFY_TIMEOUT_MS;
        main.postDelayed(new Runnable() {
            private int matches;
            private int lastSeen = seenAtWrite;

            @Override
            public void run() {
                if (steps.epoch != linkEpoch) return;
                if (!controllerLink.isReady()) {
                    cb.done(false, "The controller link dropped");
                    return;
                }
                if (!steps.restoring && steps.faultsBefore >= 0
                        && (controllerMemory.faultLatch() & ~steps.faultsBefore) != 0) {
                    cb.done(false, "The controller raised a fault during the change");
                    return;
                }
                int seen = controllerMemory.seenCount(p.addr);
                if (seen != lastSeen) {
                    lastSeen = seen;
                    matches = controllerMemory.word(p.addr) == expected ? matches + 1 : 0;
                }
                if (matches >= 2) {
                    writeSequence(steps, index + 1, cb);
                } else if (SystemClock.elapsedRealtime() > deadline) {
                    cb.done(false, "The controller did not accept " + p.name);
                } else {
                    main.postDelayed(this, 200);
                }
            }
        }, 200);
    }

    /**
     * After a reconnect: if every setting on trial reads as it did before the
     * trial, the controller was restarted and the trial is over.
     */
    private void recheckTry() {
        tryNeedsRecheck = false;
        if (tuneState != TRYING) return;
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            Fardriver.Param p = Fardriver.param(e.getKey());
            if (p != null && controllerMemory.raw(p) != e.getValue()) return; // still live
        }
        before.clear();
        tried.clear();
        tuneState = TUNE_IDLE;
    }

    /**
     * Step 3: put back the values from before the trial. If that cannot be
     * completed the trial stays open, so it can be tried again.
     */
    void revert(final TuneCallback cb) {
        revert(false, cb);
    }

    /** @param atOnce true after a fault: the old values go back without waiting for a standstill */
    private void revert(boolean atOnce, final TuneCallback cb) {
        if (tuneState != TRYING) {
            cb.done(false, "There is nothing to undo");
            return;
        }
        if (!atOnce) {
            Fardriver.Live l = controller();
            if (l != null && (l.rpm != 0 || controllerMemory.rolling())) {
                cb.done(false, "Could not undo: stop the wheel first. Or switch the bike off"
                        + " and on to restore its saved settings");
                return;
            }
        }
        tuneState = REVERTING;
        changed();
        final Map<String, Integer> back = new LinkedHashMap<>(before);
        writeSequence(new Steps(safeOrder(back), back, linkEpoch).restoring(), 0,
                (ok, message) -> {
                    if (ok) {
                        before.clear();
                        tried.clear();
                        tuneState = TUNE_IDLE;
                        changed();
                        cb.done(true, "Undone. The bike is back to how it was");
                    } else {
                        tuneState = TRYING;
                        changed();
                        cb.done(false, "Could not undo: " + message + ". Try again, or switch"
                                + " the bike off and on to restore its saved settings");
                    }
                });
    }

    /**
     * Why a Save cannot go ahead, or null. A Save keeps whatever is in the
     * controller's working memory, so it only goes ahead when that is known,
     * right now, to be the values on trial and nothing else.
     */
    private String saveBlocker() {
        String blocked = writeBlocker();
        if (blocked != null) return blocked;
        if (tryNeedsRecheck) return "Still reading the controller after it reconnected";
        for (Map.Entry<String, Integer> e : tried.entrySet()) {
            Fardriver.Param p = Fardriver.param(e.getKey());
            if (p != null && controllerMemory.raw(p) != e.getValue()) {
                return "The controller no longer holds the values on trial (" + p.name
                        + " reads " + p.format(controllerMemory.raw(p)) + "). Undo, then try"
                        + " them again";
            }
        }
        return null;
    }

    /** Step 2: store what is live in RAM to the controller's flash. */
    void save(final TuneCallback cb) {
        if (tuneState != TRYING) {
            cb.done(false, "There is nothing to save");
            return;
        }
        String blocked = saveBlocker();
        if (blocked != null) {
            cb.done(false, blocked);
            return;
        }
        final int epoch = linkEpoch;
        tuneState = SAVING;
        changed();
        final int countBefore = controllerMemory.word(0x05);
        Calendar now = Calendar.getInstance();
        final byte[][] frames = Fardriver.saveFrames(now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
                now.get(Calendar.SECOND));
        controllerLink.write(frames[0]);
        main.postDelayed(() -> {
            if (epoch == linkEpoch) controllerLink.write(frames[1]);
        }, 20);
        final long deadline = SystemClock.elapsedRealtime() + 8000;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (epoch != linkEpoch) return;
                // The controller counts its saves in word 0x05.
                if (controllerMemory.word(0x05) != countBefore) {
                    before.clear();
                    tried.clear();
                    tuneState = TUNE_IDLE;
                    changed();
                    cb.done(true, "Saved to the controller");
                } else if (SystemClock.elapsedRealtime() > deadline
                        || !controllerLink.isReady()) {
                    tuneState = TRYING;
                    changed();
                    cb.done(false, "The controller did not confirm the save. The new values"
                            + " are still live; try Save again. If it keeps happening, switch"
                            + " the bike off and on and see whether they stayed");
                } else {
                    main.postDelayed(this, 300);
                }
            }
        }, 1500);
    }

    /**
     * Forget any trial in progress: the controller is being unlinked or
     * swapped. Steps still waiting on the old one are retired with it.
     */
    private void abortTuning() {
        linkEpoch++;
        before.clear();
        tried.clear();
        demoCaps.clear();
        tryNeedsRecheck = false;
        tuneState = TUNE_IDLE;
    }

    /** Every word read so far, as JSON, so a tune can always be reconstructed. */
    private boolean saveBackup() {
        try {
            JSONObject words = new JSONObject();
            for (int a = 0; a < 256; a++) {
                if (controllerMemory.seenCount(a) > 0) {
                    words.put(String.format(Locale.US, "0x%02X", a), controllerMemory.word(a));
                }
            }
            JSONObject settings = new JSONObject();
            for (Fardriver.Param p : Fardriver.PARAMS) {
                settings.put(p.key, controllerMemory.raw(p));
            }
            for (Fardriver.Param p : Fardriver.FOLLOWERS) {
                settings.put(p.key, controllerMemory.raw(p));
            }
            JSONObject out = new JSONObject()
                    .put("controller", controllerName())
                    .put("firmware", controllerMemory.firmware())
                    .put("savedAt", System.currentTimeMillis())
                    .put("settings", settings)
                    .put("words", words);
            File dir = new File(context.getExternalFilesDir(null), "controller-backups");
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date());
            File f = new File(dir, "backup_" + stamp + ".json");
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(out.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            prefs.edit().putString("bike_last_backup", f.getName()).apply();
            return true;
        } catch (IOException | JSONException e) {
            return false;
        }
    }
}
