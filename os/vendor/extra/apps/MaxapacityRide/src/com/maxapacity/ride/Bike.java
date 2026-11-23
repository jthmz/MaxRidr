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

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean active;

    // ---- Battery -----------------------------------------------------------

    private final AntBms.Assembler bmsFrames = new AntBms.Assembler();
    private final BleLink bmsLink;
    private AntBms.Data battery;
    private long batteryAtMs;
    private String batteryStatus = "Not linked";
    private boolean bmsLegacy;
    private int bmsMisses;
    private long bmsAskedAtMs;

    // ---- Controller --------------------------------------------------------

    private final Fardriver controllerMemory = new Fardriver();
    private final BleLink controllerLink;
    private Fardriver.Live controller;
    private long controllerAtMs;
    private String controllerStatus = "Not linked";
    private boolean backedUp;

    private Bike(Context c) {
        context = c;
        prefs = Units.prefs(c);

        bmsLink = new BleLink(c, BleLink.uuid(0xFFE1), new BleLink.Listener() {
            @Override
            public void onReady() {
                batteryStatus = "Reading";
                bmsFrames.reset();
                bmsMisses = 0;
                bmsLegacy = prefs.getBoolean("bike_bms_legacy", false);
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
                if (d.legacy != prefs.getBoolean("bike_bms_legacy", false)) {
                    prefs.edit().putBoolean("bike_bms_legacy", d.legacy).apply();
                }
                changed();
            }

            @Override
            public void onClosed(String why) {
                main.removeCallbacks(bmsPoll);
                battery = null;
                batteryStatus = why;
                changed();
                retryLater();
            }
        });

        controllerLink = new BleLink(c, BleLink.uuid(0xFFEC), new BleLink.Listener() {
            @Override
            public void onReady() {
                controllerStatus = "Reading";
                controllerMemory.reset();
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
                if (tryNeedsRecheck && controllerMemory.settingsLoaded()) recheckTry();
                Fardriver.Live l = controllerMemory.live();
                if (l == null) return;
                controller = l;
                controllerAtMs = SystemClock.elapsedRealtime();
                controllerStatus = "Live";
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
        if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
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
        return !prefs.getString("bike_bms_name", "").isEmpty();
    }

    boolean controllerLinked() {
        return !prefs.getString("bike_ctl_name", "").isEmpty();
    }

    boolean anyConfigured() {
        return batteryLinked() || controllerLinked();
    }

    String batteryName() {
        return prefs.getString("bike_bms_name", "");
    }

    String controllerName() {
        return prefs.getString("bike_ctl_name", "");
    }

    void link(int kind, String name) {
        if (kind == KIND_BATTERY) {
            bmsLink.close();
            prefs.edit().putString("bike_bms_name", name).remove("bike_bms_legacy").apply();
        } else {
            controllerLink.close();
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
                if (bmsMisses % 2 == 0 && battery == null) bmsLegacy = !bmsLegacy;
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

    int tuneState() {
        return tuneState;
    }

    boolean hasUnsavedTry() {
        return tuneState == TRYING;
    }

    /** The highest value a capped setting may take: what the controller shipped with. */
    int ceiling(Fardriver.Param p) {
        if (!p.cappedAtFactory) return p.max;
        String key = "bike_cap_" + controllerName() + "_" + p.key;
        int now = controllerMemory.raw(p);
        int cap = prefs.getInt(key, -1);
        if (cap < 0 || now > cap) {
            // First sight of this controller (or it was raised elsewhere).
            cap = now;
            prefs.edit().putInt(key, cap).apply();
        }
        return Math.min(cap, p.max);
    }

    /** Why a change cannot be sent right now, or null if it can. */
    String writeBlocker() {
        String blocked = controllerMemory.tuningBlocker();
        if (blocked != null) return blocked;
        Fardriver.Live l = controller();
        if (l == null || !controllerLink.isReady()) return "The controller is not connected";
        if (l.rpm != 0) return "Stop the wheel first. Settings only change at a standstill";
        if (l.faults != 0) return "Clear the controller fault first: " + l.faultNames().get(0);
        if (RideState.get().isRecording()) return "End the ride before changing settings";
        return null;
    }

    /**
     * Step 1: write the changed settings to RAM, verifying each by reading it
     * back, then watch for a fault.
     */
    void tryValues(final Map<String, Integer> changes, final TuneCallback cb) {
        String blocked = writeBlocker();
        if (blocked != null) {
            cb.done(false, blocked);
            return;
        }
        if (tuneState != TUNE_IDLE && tuneState != TRYING) {
            cb.done(false, "Another change is still in progress");
            return;
        }
        if (!backedUp) {
            if (!saveBackup()) {
                cb.done(false, "A backup of the current settings could not be saved, so"
                        + " nothing was changed");
                return;
            }
            backedUp = true;
        }
        final List<String> order = new ArrayList<>(changes.keySet());
        for (String key : order) {
            Fardriver.Param p = Fardriver.param(key);
            if (p != null && !before.containsKey(key)) before.put(key, controllerMemory.raw(p));
        }
        final int faultsBefore = controllerMemory.word(0xE3) & 0x7FFF;
        tuneState = APPLYING;
        changed();
        writeSequence(order, changes, 0, (ok, message) -> {
            if (!ok) {
                // Put back whatever did get written.
                final String why = message;
                tuneState = REVERTING;
                changed();
                writeSequence(new ArrayList<>(before.keySet()), new LinkedHashMap<>(before), 0,
                        (ok2, m2) -> {
                            before.clear();
                            tuneState = TUNE_IDLE;
                            changed();
                            cb.done(false, why + (ok2 ? ". Nothing was changed"
                                    : ". Switch the bike off and on to restore its settings"));
                        });
                return;
            }
            tuneState = TRYING;
            changed();
            cb.done(true, "Live on the bike. Not saved yet");
            watchForFault(faultsBefore, cb);
        });
    }

    private void watchForFault(final int faultsBefore, final TuneCallback cb) {
        final long until = SystemClock.elapsedRealtime() + FAULT_WATCH_MS;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tuneState != TRYING) return;
                int now = controllerMemory.word(0xE3) & 0x7FFF;
                if ((now & ~faultsBefore) != 0) {
                    revert((ok, m) -> cb.done(false, "The controller raised a fault after the"
                            + " change, so it was undone"));
                    return;
                }
                if (SystemClock.elapsedRealtime() < until) main.postDelayed(this, 500);
            }
        }, 500);
    }

    /** Writes one setting, waits until it reads back twice, then moves to the next. */
    private void writeSequence(final List<String> order, final Map<String, Integer> values,
            final int index, final TuneCallback cb) {
        if (index >= order.size()) {
            cb.done(true, null);
            return;
        }
        if (!controllerLink.isReady()) {
            cb.done(false, "The controller link dropped");
            return;
        }
        final Fardriver.Param p = Fardriver.param(order.get(index));
        final Integer target = values.get(order.get(index));
        if (p == null || target == null || controllerMemory.raw(p) == target) {
            writeSequence(order, values, index + 1, cb);
            return;
        }
        final byte[] frame;
        final int expected;
        try {
            // Reverting may need to go back above a lowered ceiling, so the
            // only ceiling enforced here is the one read from the controller.
            frame = controllerMemory.writeFrame(p, target, ceiling(p));
            expected = controllerMemory.expectedWord(p, target);
        } catch (IllegalArgumentException e) {
            cb.done(false, p.name + " is outside its safe range");
            return;
        }
        Fardriver.Live l = controller();
        if (l == null || l.rpm != 0) {
            cb.done(false, "The wheel started turning");
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
                if (!controllerLink.isReady()) {
                    cb.done(false, "The controller link dropped");
                    return;
                }
                int seen = controllerMemory.seenCount(p.addr);
                if (seen != lastSeen) {
                    lastSeen = seen;
                    matches = controllerMemory.word(p.addr) == expected ? matches + 1 : 0;
                }
                if (matches >= 2) {
                    writeSequence(order, values, index + 1, cb);
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
        tuneState = TUNE_IDLE;
    }

    /** Step 3: put back the values from before the try. */
    void revert(final TuneCallback cb) {
        if (tuneState != TRYING) {
            cb.done(false, "There is nothing to undo");
            return;
        }
        tuneState = REVERTING;
        changed();
        final Map<String, Integer> back = new LinkedHashMap<>(before);
        writeSequence(new ArrayList<>(back.keySet()), back, 0, (ok, message) -> {
            before.clear();
            tuneState = TUNE_IDLE;
            changed();
            cb.done(ok, ok ? "Back to the saved settings"
                    : "Could not confirm the undo. Switch the bike off and on to restore its"
                    + " saved settings");
        });
    }

    /** Step 2: store what is live in RAM to the controller's flash. */
    void save(final TuneCallback cb) {
        if (tuneState != TRYING) {
            cb.done(false, "There is nothing to save");
            return;
        }
        Fardriver.Live l = controller();
        if (l == null || l.rpm != 0 || l.faults != 0) {
            cb.done(false, "Save needs the wheel stopped and no controller fault");
            return;
        }
        tuneState = SAVING;
        changed();
        final int countBefore = controllerMemory.word(0x05);
        Calendar now = Calendar.getInstance();
        final byte[][] frames = Fardriver.saveFrames(now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
                now.get(Calendar.SECOND));
        controllerLink.write(frames[0]);
        main.postDelayed(() -> controllerLink.write(frames[1]), 20);
        final long deadline = SystemClock.elapsedRealtime() + 8000;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                // The controller counts its saves in word 0x05.
                if (controllerMemory.word(0x05) != countBefore) {
                    before.clear();
                    tuneState = TUNE_IDLE;
                    changed();
                    cb.done(true, "Saved to the controller");
                } else if (SystemClock.elapsedRealtime() > deadline
                        || !controllerLink.isReady()) {
                    tuneState = TRYING;
                    changed();
                    cb.done(false, "The controller did not confirm the save. The new values"
                            + " are still live; try Save again");
                } else {
                    main.postDelayed(this, 300);
                }
            }
        }, 1500);
    }

    /** Forget any trial in progress (the controller is being unlinked). */
    private void abortTuning() {
        before.clear();
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
