package com.maxapacity.ride;

import static org.junit.Assert.*;

import android.content.Context;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The bike link against a simulated battery and controller. The simulated
 * controller starts from the memory of a real one (firmware HA80), streams it
 * the way the real one does, and obeys the same write and save frames. Every
 * byte the app sends is recorded, so each test can assert not just that the
 * right thing happened but that nothing else was sent.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BikeSimTest {

  /** Frame id to the address of its first word, as documented for the protocol. */
  static final int[] BLOCK = {
      0xE2, 0xE8, 0xEE, 0x00, 0x06, 0x0C, 0x12, 0xE2, 0xE8, 0xEE, 0x18, 0x1E, 0x24, 0x2A,
      0xE2, 0xE8, 0xEE, 0x30, 0x5D, 0x63, 0x69, 0xE2, 0xE8, 0xEE, 0x7C, 0x82, 0x88, 0x8E,
      0xE2, 0xE8, 0xEE, 0x94, 0x9A, 0xA0, 0xA6, 0xE2, 0xE8, 0xEE, 0xAC, 0xB2, 0xB8, 0xBE,
      0xE2, 0xE8, 0xEE, 0xC4, 0xCA, 0xD0, 0xE2, 0xE8, 0xEE, 0xD6, 0xDC, 0xF4, 0xFA};

  static final class FakeController extends BleLink {
    final Listener listener;
    final int[] ram = new int[256];
    final int[] flash = new int[256];
    final List<byte[]> written = new ArrayList<>();
    boolean ready;
    boolean streaming;
    /** Swallow this many setting writes, as a lost radio packet would. */
    int dropWrites;
    /** Swallow the setting write with this number (1 = the first). */
    int dropNth;
    int settingWriteCount;

    FakeController(Context c, UUID u, Listener l) {
      super(c, u, l);
      listener = l;
      Fardriver real = new Fardriver();
      for (String f : ProtocolTest.HA80) real.feed(ProtocolTest.hex(f));
      for (int a = 0; a < 256; a++) ram[a] = real.word(a);
      System.arraycopy(ram, 0, flash, 0, 256);
    }

    @Override boolean isReady() { return ready; }
    @Override boolean isOpen() { return ready; }
    @Override void open(String name) { ready = true; streaming = false; listener.onReady(); }
    @Override void close() { ready = false; streaming = false; }

    /** The link drops by itself, as when the bike goes out of range. */
    void drop() { ready = false; streaming = false; listener.onClosed("The link dropped"); }

    void powerCycle() { System.arraycopy(flash, 0, ram, 0, 256); }

    @Override void write(byte[] f) {
      if (!ready) return;
      written.add(f.clone());
      if (Arrays.equals(f, Fardriver.HELLO)) { streaming = true; return; }
      if ((f[0] & 0xFF) != 0xAA) return;
      int n = (f[1] & 0xFF) - 0xC0 - 4;
      if (n < 2 || f.length != 4 + n + 2 || f[2] != f[3]) return;
      int crc = Fardriver.crc16(f, 0, f.length - 2);
      if ((crc & 0xFF) != (f[f.length - 2] & 0xFF) || (crc >> 8) != (f[f.length - 1] & 0xFF)) return;
      int addr = f[2] & 0xFF;
      if (addr != 0xA0 && addr != 0x22) {
        settingWriteCount++;
        if (settingWriteCount == dropNth) return;
        if (dropWrites > 0) { dropWrites--; return; }
      }
      for (int k = 0; k < n / 2; k++) {
        ram[(addr + k) & 0xFF] = (f[4 + 2 * k] & 0xFF) | ((f[5 + 2 * k] & 0xFF) << 8);
      }
      if (addr == 0xA0 && ram[0xA0] == 0x0488) {      // "save" system command
        ram[0x05]++;
        System.arraycopy(ram, 0, flash, 0, 256);
      }
    }

    /** One pass of the whole 55-frame cycle, two frames to a notification like the real one. */
    void streamCycle() {
      if (!ready || !streaming) return;
      for (int id = 0; id + 1 < 55; id += 2) {
        byte[] two = new byte[32];
        System.arraycopy(frame(id), 0, two, 0, 16);
        System.arraycopy(frame(id + 1), 0, two, 16, 16);
        listener.onData(two);
      }
      listener.onData(frame(54));
    }

    byte[] frame(int id) {
      byte[] f = new byte[16];
      f[0] = (byte) 0xAA;
      f[1] = (byte) (0x80 | id);
      for (int k = 0; k < 6; k++) {
        int w = ram[(BLOCK[id] + k) & 0xFF];
        f[2 + 2 * k] = (byte) w;
        f[3 + 2 * k] = (byte) (w >> 8);
      }
      int crc = Fardriver.crc16(f, 0, 14);
      f[14] = (byte) crc;
      f[15] = (byte) (crc >> 8);
      return f;
    }

    /** Setting writes only (not the keep-alive), as "addr=value". */
    List<String> settingWrites() {
      List<String> out = new ArrayList<>();
      for (byte[] f : written) {
        if (Arrays.equals(f, Fardriver.HELLO)) continue;
        if (f.length == 8 && (f[1] & 0xFF) == 0xC6) {
          out.add(String.format("%02X=%d", f[2] & 0xFF, (f[4] & 0xFF) | ((f[5] & 0xFF) << 8)));
        } else {
          out.add("other:" + hex(f));
        }
      }
      return out;
    }
  }

  /** A pretend ANT BMS that answers one protocol only. */
  static final class FakeBms extends BleLink {
    final Listener listener;
    final List<byte[]> written = new ArrayList<>();
    boolean ready;
    boolean legacyOnly;
    int chunk = 20;

    FakeBms(Context c, UUID u, Listener l) { super(c, u, l); listener = l; }
    @Override boolean isReady() { return ready; }
    @Override boolean isOpen() { return ready; }
    @Override void open(String name) { ready = true; listener.onReady(); }
    @Override void close() { ready = false; }
    @Override void write(byte[] f) {
      if (!ready) return;
      written.add(f.clone());
      byte[] reply = null;
      if (!legacyOnly && Arrays.equals(f, AntBms.REQUEST_NEW)) reply = ProtocolTest.hex(ProtocolTest.NEW16);
      if (legacyOnly && Arrays.equals(f, AntBms.REQUEST_LEGACY)) reply = ProtocolTest.hex(ProtocolTest.OLD14);
      if (reply == null) return;
      for (int i = 0; i < reply.length; i += chunk) {
        listener.onData(Arrays.copyOfRange(reply, i, Math.min(reply.length, i + chunk)));
      }
    }
  }

  static String hex(byte[] b) {
    StringBuilder s = new StringBuilder();
    for (byte x : b) s.append(String.format("%02X", x));
    return s.toString();
  }

  static final class Result implements Bike.TuneCallback {
    final List<String> calls = new ArrayList<>();
    Boolean ok;
    String message;
    @Override public void done(boolean ok, String message) {
      this.ok = ok; this.message = message; calls.add((ok ? "ok: " : "FAIL: ") + message);
    }
  }

  Context c;
  Bike bike;
  FakeController ctl;
  FakeBms bms;

  @Before public void setUp() throws Exception {
    c = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
        android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN);
    Units.prefs(c).edit().clear().apply();
    Bike.links = (ctx, uuid, l) -> {
      if (uuid.equals(BleLink.uuid(0xFFEC))) return ctl = new FakeController(ctx, uuid, l);
      return bms = new FakeBms(ctx, uuid, l);
    };
    Field inst = Bike.class.getDeclaredField("instance");
    inst.setAccessible(true);
    inst.set(null, null);
    bike = Bike.get(c);
    File backups = new File(c.getExternalFilesDir(null), "controller-backups");
    File[] old = backups.listFiles();
    if (old != null) for (File f : old) f.delete();
  }

  @After public void tearDown() {
    Bike.links = BleLink::new;
  }

  /** Lets time pass with the controller streaming, as it does continuously. */
  void run(long ms) {
    for (long t = 0; t < ms; t += 200) {
      if (ctl != null) ctl.streamCycle();
      ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
    }
  }

  void linkController() {
    bike.link(Bike.KIND_CONTROLLER, "CONTROLCN368");
    run(1500);
    assertNotNull("controller live", bike.controller());
    assertNull(bike.writeBlocker(), bike.writeBlocker());
    assertEquals("HA80", bike.controller().firmware);
  }

  static Map<String, Integer> change(Object... kv) {
    Map<String, Integer> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
    return m;
  }

  // ---- Controller: reading ---------------------------------------------------

  @Test public void readsLiveDataAndOnlySendsKeepAlive() {
    linkController();
    run(5000);
    assertEquals(81.8f, bike.controller().volts, 0.05f);
    assertEquals(130f * 81.8f, bike.peakPowerW(), 50f);
    assertTrue(ctl.written.size() >= 5);                       // about one a second
    for (byte[] f : ctl.written) assertArrayEquals(Fardriver.HELLO, f);
  }

  // ---- Controller: try, undo, save -------------------------------------------

  @Test public void tryThenUndoRestoresEverything() throws Exception {
    linkController();
    Result r = new Result();
    bike.tryValues(change("accel", 72, "line", 440), r);
    assertEquals(Bike.APPLYING, bike.tuneState());
    run(9000);
    assertEquals(r.calls.toString(), Boolean.TRUE, r.ok);
    assertEquals(Bike.TRYING, bike.tuneState());
    assertEquals(72, ctl.ram[0x2F]);
    assertEquals(440, ctl.ram[0x19]);
    assertEquals("the burst allowance comes down with the limit", 440, ctl.ram[0x26]);
    assertEquals("not saved", 64, ctl.flash[0x2F]);
    assertEquals(Arrays.asList("2F=72", "19=440", "26=440"), ctl.settingWrites());

    // The backup was written before the first change and holds the old values.
    File[] backups = new File(c.getExternalFilesDir(null), "controller-backups").listFiles();
    assertEquals(1, backups.length);
    JSONObject b = new JSONObject(new String(Files.readAllBytes(backups[0].toPath()), StandardCharsets.UTF_8));
    assertEquals(64, b.getJSONObject("settings").getInt("accel"));
    assertEquals(520, b.getJSONObject("settings").getInt("line"));
    assertEquals(520, b.getJSONObject("words").getInt("0x19"));
    assertEquals("HA80", b.getString("firmware"));

    Result u = new Result();
    bike.revert(u);
    run(8000);
    assertEquals(u.calls.toString(), Boolean.TRUE, u.ok);
    assertEquals(Bike.TUNE_IDLE, bike.tuneState());
    assertArrayEquals(ctl.flash, ctl.ram);
    // Going back up, the burst rises before the limit, so it is never the lower of the two.
    assertEquals(Arrays.asList("2F=72", "19=440", "26=440", "26=520", "19=520", "2F=64"), ctl.settingWrites());
  }

  @Test public void saveSendsExactlyTheStampAndTheSaveCommand() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("accel", 72), r);
    run(9000);
    assertEquals(Bike.TRYING, bike.tuneState());
    int savesBefore = ctl.flash[0x05];
    Result s = new Result();
    bike.save(s);
    run(6000);
    assertEquals(s.calls.toString(), Boolean.TRUE, s.ok);
    assertEquals(Bike.TUNE_IDLE, bike.tuneState());
    assertEquals(72, ctl.flash[0x2F]);
    assertEquals(savesBefore + 1, ctl.flash[0x05]);
    List<String> w = ctl.settingWrites();
    assertEquals(3, w.size());
    assertEquals("2F=72", w.get(0));
    assertTrue(w.get(1), w.get(1).startsWith("other:AACA2222"));   // 6-byte timestamp
    assertEquals("A0=1160", w.get(2));                              // 0x0488: save, and only save
    byte[] save = ctl.written.get(ctl.written.size() - 1);
    for (int i = ctl.written.size() - 1; i >= 0; i--) {
      if (!Arrays.equals(ctl.written.get(i), Fardriver.HELLO)) { save = ctl.written.get(i); break; }
    }
    assertEquals("AAC6A0A08804C50C", hex(save));
    // survives a power cycle
    ctl.powerCycle();
    assertEquals(72, ctl.ram[0x2F]);
  }

  @Test public void twoSettingsInOneWordKeepTheirNeighbours() {
    linkController();
    int word = ctl.ram[0x1A];
    Fardriver.Param response = Fardriver.param("response");
    Fardriver.Param regen = Fardriver.param("regen_mode");
    int newResponse = (response.extract(word) + 1) % 3;
    int newRegen = (regen.extract(word) + 1) % 4;
    Result r = new Result();
    bike.tryValues(change("response", newResponse, "regen_mode", newRegen), r);
    run(12000);
    assertEquals(r.calls.toString(), Boolean.TRUE, r.ok);
    int now = ctl.ram[0x1A];
    assertEquals(newResponse, response.extract(now));
    assertEquals(newRegen, regen.extract(now));
    int others = ~(response.mask() | regen.mask()) & 0xFFFF;
    assertEquals("bits belonging to other settings untouched", word & others, now & others);
  }


  // ---- Controller: the rules a change has to pass ----------------------------

  int raw(String key) {
    Fardriver.Param p = Fardriver.param(key);
    return p.extract(ctl.ram[p.addr]);
  }

  @Test public void aStalledStreamBlocksAChangeEvenIfTheLastReadingSaidStopped() {
    linkController();
    // Notifications stop; meanwhile the wheel starts turning, unseen.
    ShadowLooper.idleMainLooper(4000, TimeUnit.MILLISECONDS);
    ctl.ram[0xE5] = 480;
    assertNotNull("the last reading is still on show", bike.controller());
    Result r = new Result();
    bike.tryValues(change("accel", 72), r);
    assertEquals(Boolean.FALSE, r.ok);
    assertTrue(r.message, r.message.contains("fresh reading"));
    assertTrue(ctl.settingWrites().isEmpty());
  }

  @Test public void theControllersOwnRollingFlagAndAnOpenThrottleBlockAChange() {
    linkController();
    ctl.ram[0xE2] |= 0x20;            // rolling, though rpm still reads 0
    run(600);
    assertTrue(bike.writeBlocker(), bike.writeBlocker().contains("standstill"));
    ctl.ram[0xE2] &= ~0x20;
    ctl.ram[0x82] = 380;              // 3.8 V of throttle, held against the brake
    ctl.ram[0xE3] = 0x8000;
    run(600);
    assertEquals("Close the throttle first", bike.writeBlocker());
    Result r = new Result();
    bike.tryValues(change("accel", 72), r);
    assertEquals(Boolean.FALSE, r.ok);
    assertTrue(ctl.settingWrites().isEmpty());
  }

  @Test public void aFaultThatFlickersDuringTheWatchStillUndoesTheChange() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("accel", 72), r);
    run(2000);
    assertEquals(r.calls.toString(), Boolean.TRUE, r.ok);
    ctl.ram[0xE3] = 0x0100;           // one pass of the stream with a fault, then gone
    ctl.streamCycle();
    ctl.ram[0xE3] = 0;
    run(10000);
    assertEquals(r.calls.toString(), 2, r.calls.size());
    assertTrue(r.message, r.message.contains("so it was undone"));
    assertEquals(64, raw("accel"));
    assertEquals(Bike.TUNE_IDLE, bike.tuneState());
  }

  @Test public void aFaultPartWayThroughSeveralChangesPutsThemAllBack() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("accel", 72, "decel", 80, "release", 8), r);
    run(1000);
    assertEquals(Bike.APPLYING, bike.tuneState());
    ctl.ram[0xE3] = 0x0100;
    run(400);
    ctl.ram[0xE3] = 0;
    run(30000);
    assertEquals(r.calls.toString(), Boolean.FALSE, r.ok);
    assertTrue(r.message, r.message.contains("raised a fault during the change"));
    assertTrue(r.message, r.message.contains("Nothing was changed"));
    assertEquals(Bike.TUNE_IDLE, bike.tuneState());
    assertArrayEquals("every setting is as it was", ctl.flash, withoutLive(ctl.ram));
  }

  /** The controller's memory with its live words set to what the saved copy holds, to compare settings. */
  int[] withoutLive(int[] ram) {
    int[] copy = ram.clone();
    for (int a = 0xE2; a <= 0xF9; a++) copy[a] = ctl.flash[a];
    copy[0x82] = ctl.flash[0x82];
    return copy;
  }

  @Test public void aFaultWithTheWheelTurningIsUndoneAtOnce() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("phase", 1200), r);
    run(2000);
    assertEquals(r.calls.toString(), Boolean.TRUE, r.ok);
    ctl.ram[0xE5] = 900;              // riding off, and then a fault
    ctl.ram[0xE3] = 0x0100;
    run(8000);
    assertTrue(r.message, r.message.contains("so it was undone"));
    assertEquals(1400, raw("phase"));
    assertEquals(Bike.TUNE_IDLE, bike.tuneState());
  }

  @Test public void aWriteThatLandsWithoutBeingReadBackIsStillPutBack() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("line", 440), r);
    assertEquals("the write landed", 440, ctl.ram[0x19]);
    // ...but notifications stop before it can be read back.
    ShadowLooper.idleMainLooper(7000, TimeUnit.MILLISECONDS);
    run(8000);
    assertEquals(r.calls.toString(), Boolean.FALSE, r.ok);
    assertTrue(r.message, r.message.contains("Nothing was changed"));
    assertEquals("and this time that is true", 520, ctl.ram[0x19]);
    assertEquals(Bike.TUNE_IDLE, bike.tuneState());
  }

  @Test public void saveOnlyKeepsWhatIsKnownToBeOnTrial() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("line", 440), r);
    run(9000);
    assertEquals(r.calls.toString(), Boolean.TRUE, r.ok);
    int saves = ctl.ram[0x05];
    // The bike is switched off and on behind the app's back: the trial is gone.
    ctl.powerCycle();
    run(1000);
    Result s = new Result();
    bike.save(s);
    run(6000);
    assertEquals(Boolean.FALSE, s.ok);
    assertTrue(s.message, s.message.contains("no longer holds the values on trial"));
    assertEquals("nothing was saved", saves, ctl.ram[0x05]);
  }

  @Test public void saveIsRefusedWhileARideIsRecording() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("accel", 72), r);
    run(9000);
    assertEquals(r.calls.toString(), Boolean.TRUE, r.ok);
    int saves = ctl.ram[0x05];
    RideState.get().start();
    try {
      Result s = new Result();
      bike.save(s);
      run(3000);
      assertEquals(Boolean.FALSE, s.ok);
      assertTrue(s.message, s.message.contains("End the ride"));
      assertEquals(saves, ctl.ram[0x05]);
    } finally {
      RideState.get().stop();
    }
  }

  @Test public void whatAVerifiedTunerRefusesIsRefusedHere() {
    linkController();
    Object[][] refused = {
        {"faster than the top gear was when the app first met it", change("high_rpm", 4300)},
        {"as fast as its speed-limit mode is set, which is no ceiling", change("high_rpm", 6000)},
        {"low gear above mid", change("low_rpm", 3000)},
        {"high gear below mid", change("high_rpm", 2000)},
        {"regen above its own maximum", change("regen", 10)},
        {"the undocumented regen mode", change("regen_mode", 0)},
        {"acceleration below the official app's floor", change("accel", 0)},
        {"deceleration below the official app's floor", change("decel", 2)},
        {"no battery current at all", change("line", 0)},
    };
    for (Object[] c : refused) {
      Result r = new Result();
      @SuppressWarnings("unchecked") Map<String, Integer> change = (Map<String, Integer>) c[1];
      bike.tryValues(change, r);
      run(1000);
      assertEquals((String) c[0], Boolean.FALSE, r.ok);
      assertTrue((String) c[0] + ": " + r.message, r.message.contains("Nothing was changed"));
      assertTrue((String) c[0], ctl.settingWrites().isEmpty());
      assertEquals(Bike.TUNE_IDLE, bike.tuneState());
    }
    assertEquals(6000, ctl.ram[0x6C]);
    for (String gear : new String[] {"low_rpm", "mid_rpm", "high_rpm"}) {
      assertEquals("the top speed it came with is the ceiling for every gear", 4200,
          bike.ceiling(Fardriver.param(gear)));
    }
  }

  @Test public void regenOnThrottleReleaseCanBeSetAndPutBack() {
    linkController();
    Fardriver.Param mode = Fardriver.param("regen_mode");
    int word = ctl.ram[0x1A];
    assertEquals("regen is off on this bike", 1, mode.extract(word));
    Result r = new Result();
    bike.tryValues(change("regen_mode", 3, "regen", 8, "regen_max", 12), r);
    run(15000);
    assertEquals(r.calls.toString(), Boolean.TRUE, r.ok);
    assertEquals(3, mode.extract(ctl.ram[0x1A]));
    assertEquals("only its own two bits changed", word & ~mode.mask(), ctl.ram[0x1A] & ~mode.mask());
    assertEquals(8, ctl.ram[0x30]);
    assertEquals(12, ctl.ram[0x31]);
    Result u = new Result();
    bike.revert(u);
    run(12000);
    assertEquals(u.calls.toString(), Boolean.TRUE, u.ok);
    assertArrayEquals("undone: exactly as it was", ctl.flash, ctl.ram);
  }

  /**
   * The one tune above what a controller came with: all four currents, on the controller it
   * was proven on (this simulated one is a capture of it), together or not at all.
   */
  @Test public void theApexOwnerTuneGoesInWholeOrNotAtAll() {
    linkController();
    assertTrue(TunePlan.apexController(bike.controllerMemory()));
    Fardriver.Param line = Fardriver.param("line");
    Fardriver.Param phase = Fardriver.param("phase");
    Map<String, Integer> tune = change("boost_line", 560, "line", 560, "boost_phase", 1520, "phase", 1520);

    // Without leave it is what it always was: above what the controller came with, refused.
    Result plain = new Result();
    bike.tryValues(tune, plain);
    run(1000);
    assertEquals(Boolean.FALSE, plain.ok);
    // With leave for the whole tune, half of it is refused, and so is anything else.
    Object[][] refused = {
        {"half of it", change("boost_line", 560, "line", 560)},
        {"more than it", change("boost_line", 580, "line", 580, "boost_phase", 1600, "phase", 1600)},
        {"one value more", change("boost_line", 560, "line", 560, "boost_phase", 1520, "phase", 1540)},
    };
    for (Object[] c : refused) {
      Result no = new Result();
      bike.allowWhole(tune);
      @SuppressWarnings("unchecked") Map<String, Integer> asked = (Map<String, Integer>) c[1];
      bike.tryValues(asked, no);
      run(1000);
      assertEquals((String) c[0], Boolean.FALSE, no.ok);
      assertTrue((String) c[0] + ": " + no.message, no.message.contains("Nothing was changed"));
    }
    assertTrue("nothing was sent for any of that", ctl.settingWrites().isEmpty());
    assertEquals(Bike.TUNE_IDLE, bike.tuneState());

    // Whole, it goes in: each burst allowance before its limit, so a limit is never above it.
    Result whole = new Result();
    bike.allowWhole(tune);
    bike.tryValues(tune, whole);
    run(20000);
    assertEquals(whole.calls.toString(), Boolean.TRUE, whole.ok);
    assertEquals(Arrays.asList("26=560", "19=560", "27=1520", "2D=1520"), ctl.settingWrites());
    assertEquals("on trial, the ceiling has not moved", 520, bike.ceiling(line));
    assertEquals(1400, bike.ceiling(phase));

    // Undone, the bike is as it came and so are its ceilings.
    Result undo = new Result();
    bike.revert(undo);
    run(15000);
    assertEquals(undo.calls.toString(), Boolean.TRUE, undo.ok);
    assertArrayEquals(ctl.flash, ctl.ram);
    assertEquals(520, bike.ceiling(line));
    ctl.written.clear();
    // Leave is for one attempt: the next one needs it given again.
    Result again = new Result();
    bike.tryValues(tune, again);
    run(1000);
    assertEquals(Boolean.FALSE, again.ok);
    assertTrue(ctl.settingWrites().isEmpty());

    // Tried again and saved, that is where the controller now stands.
    Result kept = new Result();
    bike.allowWhole(tune);
    bike.tryValues(tune, kept);
    run(20000);
    assertEquals(kept.calls.toString(), Boolean.TRUE, kept.ok);
    Result saved = new Result();
    bike.save(saved);
    run(6000);
    assertEquals(saved.calls.toString(), Boolean.TRUE, saved.ok);
    assertEquals(560, ctl.flash[0x19]);
    assertEquals(1520, ctl.flash[0x2D]);
    assertEquals(560, ctl.flash[0x26]);
    assertEquals(1520, ctl.flash[0x27]);
    assertEquals(560, bike.ceiling(line));
    assertEquals(1520, bike.ceiling(phase));
    // And nothing above it is on offer.
    Result more = new Result();
    bike.tryValues(change("line", 580), more);
    run(1000);
    assertEquals(Boolean.FALSE, more.ok);
  }

  @Test public void gearSpeedsNeverPassThroughAForbiddenOrderOnTheWay() {
    linkController();
    // All three come down past where the one below stood: bottom first, or high would
    // drop under mid.
    Result down = new Result();
    bike.tryValues(change("high_rpm", 1700, "mid_rpm", 1400, "low_rpm", 1000), down);
    run(12000);
    assertEquals(down.calls.toString(), Boolean.TRUE, down.ok);
    assertEquals(Arrays.asList("28=1000", "2A=1400", "15=1700"), ctl.settingWrites());
    ctl.written.clear();
    // And back up to where they stood: top first, or low would overtake mid.
    Result up = new Result();
    bike.tryValues(change("low_rpm", 1800, "mid_rpm", 2800, "high_rpm", 4200), up);
    run(12000);
    assertEquals(up.calls.toString(), Boolean.TRUE, up.ok);
    assertEquals(Arrays.asList("15=4200", "2A=2800", "28=1800"), ctl.settingWrites());
  }

  /** The simulated controller becomes a stock Arctic Leopard XE Pro R, then links. */
  void linkXeProR() {
    XeProRTest.asDelivered(ctl.ram);
    XeProRTest.asDelivered(ctl.flash);
    bike.link(Bike.KIND_CONTROLLER, "CONTROLCN368");
    run(1500);
    assertNotNull("controller live", bike.controller());
    assertNull("a stock XE Pro R is not locked out", bike.writeBlocker());
  }

  @Test public void aStockXeProRIsTunedDownAndBroughtBackToHowItWasDelivered() {
    linkXeProR();
    // What it came with is the most it can be given, speed-limit mode or not.
    assertEquals(3500, ctl.ram[0x6C]);
    assertEquals(12500, bike.ceiling(Fardriver.param("high_rpm")));
    assertEquals(12500, bike.ceiling(Fardriver.param("low_rpm")));
    assertEquals(1320, bike.ceiling(Fardriver.param("line")));
    assertEquals(2796, bike.ceiling(Fardriver.param("phase")));
    assertEquals("more regen than the app offers, because it shipped with it", 30,
        bike.ceiling(Fardriver.param("regen")));
    assertEquals(50, bike.ceiling(Fardriver.param("regen_max")));
    for (Map<String, Integer> over : Arrays.asList(change("high_rpm", 12600), change("regen", 31),
        change("regen_max", 51), change("line", 1340), change("phase", 2800))) {
      Result no = new Result();
      bike.tryValues(over, no);
      run(1000);
      assertEquals(over.toString(), Boolean.FALSE, no.ok);
      assertTrue(over.toString(), ctl.settingWrites().isEmpty());
    }

    // A calmer bike with a softer brake: every drop goes in from the bottom up.
    Result calmer = new Result();
    bike.tryValues(change("regen", 15, "regen_max", 20, "high_rpm", 10000, "line", 1040), calmer);
    run(30000);
    assertEquals(calmer.calls.toString(), Boolean.TRUE, calmer.ok);
    assertEquals(Arrays.asList("19=1040", "26=1040", "30=15", "31=20", "15=10000"), ctl.settingWrites());
    Result kept = new Result();
    bike.save(kept);
    run(6000);
    assertEquals(kept.calls.toString(), Boolean.TRUE, kept.ok);
    assertEquals(1040, ctl.flash[0x19]);
    ctl.written.clear();

    // The way back stays open: the factory tune puts all of it back, burst included.
    TunePlan.Tune factory = null;
    for (TunePlan.Tune t : TunePlan.ourBikes()) if (t.id.equals("xe-pro-r-factory")) factory = t;
    TunePlan.Loaded loaded = TunePlan.load(factory, bike.controllerMemory(), bike::ceiling);
    assertNull(loaded.refused, loaded.refused);
    assertEquals("{line=1320, high_rpm=12500, regen=30, regen_max=50}", loaded.changes.toString());
    Result back = new Result();
    bike.tryValues(loaded.changes, back);
    run(30000);
    assertEquals(back.calls.toString(), Boolean.TRUE, back.ok);
    assertEquals(Arrays.asList("15=12500", "31=50", "30=30", "26=1320", "19=1320"), ctl.settingWrites());
    int[] delivered = new int[256];
    System.arraycopy(ctl.flash, 0, delivered, 0, 256);
    XeProRTest.asDelivered(delivered);
    for (Fardriver.Param[] group : new Fardriver.Param[][] {Fardriver.PARAMS, Fardriver.FOLLOWERS}) {
      for (Fardriver.Param p : group) {
        assertEquals(p.name, p.extract(delivered[p.addr]), p.extract(ctl.ram[p.addr]));
      }
    }
  }

  @Test public void ceilingsBelongToTheControllerNotToItsBluetoothName() {
    // A big controller first: 300 A from the battery.
    ctl.ram[0x19] = 1200;
    ctl.flash[0x19] = 1200;
    linkController();
    Fardriver.Param line = Fardriver.param("line");
    assertEquals(1200, bike.ceiling(line));
    // The same Bluetooth module on a smaller controller: its own serial, its own 130 A.
    ctl.drop();
    ctl.ram[0x19] = 520;
    ctl.flash[0x19] = 520;
    ctl.ram[0xA9] = 0x3939;
    ctl.flash[0xA9] = 0x3939;
    run(20000);
    assertNotNull("linked again", bike.controller());
    assertNull(bike.writeBlocker(), bike.writeBlocker());
    assertEquals("the small one keeps its own ceiling", 520, bike.ceiling(line));
    Result r = new Result();
    bike.tryValues(change("line", 1200), r);
    run(2000);
    assertEquals(Boolean.FALSE, r.ok);
    assertTrue(ctl.settingWrites().isEmpty());
  }

  @Test public void aControllerShippedWithMoreRegenThanTheAppOffersStillTunes() {
    ctl.ram[0x30] = 25;               // seen on a stock BJ72V250
    ctl.flash[0x30] = 25;
    ctl.ram[0x31] = 30;
    ctl.flash[0x31] = 30;
    linkController();                 // which asserts tuning is not locked
    assertEquals("it may keep what it came with", 25, bike.ceiling(Fardriver.param("regen")));
    assertEquals(30, bike.ceiling(Fardriver.param("regen_max")));
    Result r = new Result();
    bike.tryValues(change("regen", 26), r);
    run(1000);
    assertEquals("but not be raised past it", Boolean.FALSE, r.ok);
    Result lower = new Result();
    bike.tryValues(change("regen", 12), lower);
    run(9000);
    assertEquals(lower.calls.toString(), Boolean.TRUE, lower.ok);
    assertEquals(12, ctl.ram[0x30]);
  }

  @Test public void endingTheDemoPartWayThroughAChangeSendsNothingToARealController() {
    linkController();
    assertTrue(bike.setDemo(true));
    // Wait for the pretend bike to be read in and to come to a standstill in its loop.
    for (int i = 0; i < 300 && bike.writeBlocker() != null; i++) {
      ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
    }
    assertNull("the pretend bike is live and at a standstill", bike.writeBlocker());
    Result r = new Result();
    bike.tryValues(change("accel", 72), r);
    ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS);
    assertEquals(Bike.APPLYING, bike.tuneState());
    assertTrue(bike.setDemo(false));
    run(20000);
    assertFalse(bike.demo());
    assertNotNull("the real controller is back", bike.controller());
    assertEquals("nothing is left on trial", Bike.TUNE_IDLE, bike.tuneState());
    Result s = new Result();
    bike.save(s);
    run(3000);
    assertEquals(Boolean.FALSE, s.ok);
    assertTrue("only the keep-alive reached the real controller: " + ctl.settingWrites(),
        ctl.settingWrites().isEmpty());
    assertArrayEquals(ctl.flash, withoutLive(ctl.ram));
  }

  @Test public void theDemoIsRefusedWhileARealChangeIsOnTrial() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("accel", 72), r);
    run(9000);
    assertEquals(r.calls.toString(), Boolean.TRUE, r.ok);
    assertFalse("the real controller's trial has to be settled first", bike.setDemo(true));
    assertFalse(bike.demo());
    assertEquals(Bike.TRYING, bike.tuneState());
  }

  // ---- Controller: things going wrong ----------------------------------------

  @Test public void aLostWriteIsNoticedAndNothingIsLeftChanged() {
    linkController();
    ctl.dropWrites = 1;
    Result r = new Result();
    bike.tryValues(change("accel", 72), r);
    run(10000);
    assertEquals(Boolean.FALSE, r.ok);
    assertTrue(r.message, r.message.contains("did not accept Acceleration"));
    assertTrue(r.message, r.message.contains("Nothing was changed"));
    assertEquals(Bike.TUNE_IDLE, bike.tuneState());
    assertArrayEquals(ctl.flash, ctl.ram);
  }

  @Test public void aHalfAppliedChangeIsPutBack() {
    linkController();
    ctl.dropNth = 2;                // the first write lands, the second is lost
    Result r = new Result();
    bike.tryValues(change("accel", 72, "line", 440), r);
    run(16000);
    // Both are written back, last first: the second may have landed without being read back.
    assertEquals("the first one was written, then everything in the batch was put back",
        Arrays.asList("2F=72", "19=440", "26=520", "19=520", "2F=64"), ctl.settingWrites());
    assertEquals(Boolean.FALSE, r.ok);
    assertTrue(r.message, r.message.contains("Nothing was changed"));
    assertEquals(Bike.TUNE_IDLE, bike.tuneState());
    assertArrayEquals("first setting restored", ctl.flash, ctl.ram);
  }

  @Test public void refusesWhileTheWheelTurns() {
    linkController();
    ctl.ram[0xE5] = 480;
    run(600);
    Result r = new Result();
    bike.tryValues(change("accel", 72), r);
    run(2000);
    assertEquals(Boolean.FALSE, r.ok);
    assertTrue(r.message, r.message.contains("standstill"));
    assertTrue(ctl.settingWrites().isEmpty());
    assertEquals(Bike.TUNE_IDLE, bike.tuneState());
  }

  @Test public void refusesValuesOutsideTheRangeAndAboveTheCeiling() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("accel", 72, "line", 600), r);     // 150 A on a 130 A controller
    run(2000);
    assertEquals(Boolean.FALSE, r.ok);
    assertTrue(r.message, r.message.contains("Battery current limit"));
    assertTrue("nothing sent, not even the valid half", ctl.settingWrites().isEmpty());
    Result r2 = new Result();
    bike.tryValues(change("regen", 11), r2);                  // official app's limit is 10
    run(2000);
    assertEquals(Boolean.FALSE, r2.ok);
    assertTrue(ctl.settingWrites().isEmpty());
    Result r3 = new Result();
    bike.tryValues(change("no_such_setting", 1), r3);
    assertEquals(Boolean.FALSE, r3.ok);
    assertTrue(ctl.settingWrites().isEmpty());
  }

  @Test public void theCeilingStaysAtTheOriginalAfterLoweringAndSaving() {
    linkController();
    Fardriver.Param line = Fardriver.param("line");
    assertEquals(520, bike.ceiling(line));
    Result r = new Result();
    bike.tryValues(change("line", 440), r);
    run(9000);
    Result s = new Result();
    bike.save(s);
    run(6000);
    assertEquals(Boolean.TRUE, s.ok);
    assertEquals("can still go back up to where it started", 520, bike.ceiling(line));
    Result up = new Result();
    bike.tryValues(change("line", 520), up);
    run(9000);
    assertEquals(up.calls.toString(), Boolean.TRUE, up.ok);
    assertEquals(520, ctl.ram[0x19]);
    Result over = new Result();
    bike.tryValues(change("line", 540), over);
    assertEquals(Boolean.FALSE, over.ok);
  }

  @Test public void aFaultRightAfterAChangeUndoesIt() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("accel", 72), r);
    run(3000);
    assertEquals(Bike.TRYING, bike.tuneState());
    ctl.ram[0xE3] |= 0x0008;        // phase current surge
    run(9000);
    assertEquals(64, ctl.ram[0x2F]);
    assertEquals(Bike.TUNE_IDLE, bike.tuneState());
    assertEquals(Boolean.FALSE, r.ok);
    assertTrue(r.message, r.message.contains("fault"));
    assertTrue(r.message, r.message.contains("undone"));
  }

  @Test public void aDroppedLinkKeepsTheTrialSoUndoStillWorks() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("accel", 72), r);
    run(9000);
    assertEquals(Bike.TRYING, bike.tuneState());
    ctl.drop();
    run(1000);
    assertEquals("trial remembered", Bike.TRYING, bike.tuneState());
    assertNull(bike.controller());
    Result early = new Result();
    bike.revert(early);                                  // link is down
    run(1000);
    assertEquals(Boolean.FALSE, early.ok);
    assertEquals("still open, can retry", Bike.TRYING, bike.tuneState());
    run(16000);                                          // the app reconnects by itself
    assertNotNull(bike.controller());
    assertEquals(Bike.TRYING, bike.tuneState());
    Result u = new Result();
    bike.revert(u);
    run(8000);
    assertEquals(u.calls.toString(), Boolean.TRUE, u.ok);
    assertEquals(64, ctl.ram[0x2F]);
    assertEquals(Bike.TUNE_IDLE, bike.tuneState());
  }

  @Test public void aPowerCycleEndsTheTrialByItself() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("accel", 72), r);
    run(9000);
    assertEquals(Bike.TRYING, bike.tuneState());
    ctl.drop();
    ctl.powerCycle();                                    // bike switched off and on
    run(18000);
    assertNotNull(bike.controller());
    assertEquals("nothing left on trial", Bike.TUNE_IDLE, bike.tuneState());
    assertEquals(64, ctl.ram[0x2F]);
  }

  @Test public void aSecondBatchThatFailsLeavesTheFirstTrialAlone() {
    linkController();
    Result first = new Result();
    bike.tryValues(change("accel", 72), first);
    run(9000);
    assertEquals(Bike.TRYING, bike.tuneState());
    ctl.dropWrites = 1;
    Result second = new Result();
    bike.tryValues(change("decel", 80), second);
    run(10000);
    assertEquals(Boolean.FALSE, second.ok);
    assertEquals("first trial still live", 72, ctl.ram[0x2F]);
    assertEquals(Bike.TRYING, bike.tuneState());
    Result u = new Result();
    bike.revert(u);
    run(8000);
    assertEquals(Boolean.TRUE, u.ok);
    assertArrayEquals(ctl.flash, ctl.ram);
  }

  @Test public void neverSendsAnythingButKeepAliveSettingWritesAndSave() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("accel", 80, "decel", 60, "release", 96, "response", 1, "line", 400,
        "phase", 1200, "low_rpm", 1500, "low_line", 70, "mid_rpm", 2600, "mid_line", 96,
        "high_rpm", 4000, "regen_mode", 2, "regen", 4, "regen_max", 8), r);
    run(60000);
    assertEquals(r.calls.toString(), Boolean.TRUE, r.ok);
    Result s = new Result();
    bike.save(s);
    run(6000);
    assertEquals(Boolean.TRUE, s.ok);
    List<Integer> allowed = new ArrayList<>();
    for (Fardriver.Param p : Fardriver.PARAMS) allowed.add(p.addr);
    // The two burst allowances move with the current limits and are written with them.
    for (Fardriver.Param p : Fardriver.FOLLOWERS) allowed.add(p.addr);
    assertEquals("and nothing else follows anything", 2, Fardriver.FOLLOWERS.length);
    int saves = 0;
    for (byte[] f : ctl.written) {
      if (Arrays.equals(f, Fardriver.HELLO)) continue;
      int crc = Fardriver.crc16(f, 0, f.length - 2);
      assertEquals("checksum", crc & 0xFF, f[f.length - 2] & 0xFF);
      assertEquals("checksum", crc >> 8, f[f.length - 1] & 0xFF);
      assertEquals(f[2], f[3]);
      int addr = f[2] & 0xFF;
      if (f.length == 12) { assertEquals(0xCA, f[1] & 0xFF); assertEquals(0x22, addr); continue; }
      assertEquals(8, f.length);
      assertEquals(0xC6, f[1] & 0xFF);
      if (addr == 0xA0) {
        assertEquals("only the save command may go to the system register", "AAC6A0A08804C50C", hex(f));
        saves++;
      } else {
        assertTrue("address " + Integer.toHexString(addr), allowed.contains(addr));
      }
    }
    assertEquals(1, saves);
    for (Fardriver.Param p : Fardriver.PARAMS) {
      assertTrue(p.key, p.extract(ctl.flash[p.addr]) >= p.min && p.extract(ctl.flash[p.addr]) <= p.max);
    }
    assertEquals(80, ctl.flash[0x2F]);
    assertEquals(400, ctl.flash[0x19]);
    assertEquals("burst battery current follows to the limit", 400, ctl.flash[0x26]);
    assertEquals("burst motor current keeps its 10 A above the limit", 1240, ctl.flash[0x27]);
  }

  // ---- Controller: the burst allowance follows the limits --------------------

  /** Replays the setting writes and checks that a burst allowance is never below its limit on the way. */
  void assertBurstsNeverBelowTheirLimits() {
    int line = ctl.flash[0x19], burstLine = ctl.flash[0x26], phase = ctl.flash[0x2D], burstPhase = ctl.flash[0x27];
    for (String w : ctl.settingWrites()) {
      if (w.startsWith("19=")) line = Integer.parseInt(w.substring(3));
      if (w.startsWith("26=")) burstLine = Integer.parseInt(w.substring(3));
      if (w.startsWith("2D=")) phase = Integer.parseInt(w.substring(3));
      if (w.startsWith("27=")) burstPhase = Integer.parseInt(w.substring(3));
      assertTrue("after " + w + ": burst " + burstLine + " under limit " + line, burstLine >= line);
      assertTrue("after " + w + ": burst " + burstPhase + " under limit " + phase, burstPhase >= phase);
    }
  }

  @Test public void aLoweredLimitTakesItsBurstWithItAndBringsItBack() {
    linkController();
    assertEquals("came with a burst equal to the battery limit", 520, ctl.ram[0x26]);
    assertEquals("and 10 A above the motor limit", 1440, ctl.ram[0x27]);
    Result down = new Result();
    bike.tryValues(change("line", 300, "phase", 840), down);
    run(14000);
    assertEquals(down.calls.toString(), Boolean.TRUE, down.ok);
    assertEquals(300, ctl.ram[0x19]);
    assertEquals("a burst may not draw more than the limit now allows", 300, ctl.ram[0x26]);
    assertEquals(840, ctl.ram[0x2D]);
    assertEquals("the motor burst keeps the gap it came with", 880, ctl.ram[0x27]);
    // On the way down each limit goes first and its burst after.
    assertEquals(Arrays.asList("2D=840", "27=880", "19=300", "26=300"), ctl.settingWrites());

    Result up = new Result();
    bike.tryValues(change("line", 520, "phase", 1400), up);
    run(14000);
    assertEquals(up.calls.toString(), Boolean.TRUE, up.ok);
    // On the way up each burst goes first and its limit after.
    assertEquals(Arrays.asList("2D=840", "27=880", "19=300", "26=300", "26=520", "19=520", "27=1440", "2D=1400"),
        ctl.settingWrites());
    assertArrayEquals("back exactly as it came", ctl.flash, ctl.ram);
    assertBurstsNeverBelowTheirLimits();
  }

  @Test public void aFaultPutsTheBurstBackWithTheLimit() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("line", 300), r);
    run(4000);
    assertEquals(Bike.TRYING, bike.tuneState());
    assertEquals(300, ctl.ram[0x26]);
    ctl.ram[0xE3] |= 0x0008;        // phase current surge
    run(9000);
    assertEquals(Bike.TUNE_IDLE, bike.tuneState());
    int[] ram = ctl.ram.clone();
    ram[0xE3] = ctl.flash[0xE3];
    assertArrayEquals("limit and burst both back", ctl.flash, ram);
    assertBurstsNeverBelowTheirLimits();
  }

  @Test public void savingKeepsTheBurstWithTheLimitAndTheWayBackStaysOpen() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("line", 300), r);
    run(9000);
    Result s = new Result();
    bike.save(s);
    run(6000);
    assertEquals(s.calls.toString(), Boolean.TRUE, s.ok);
    assertEquals(300, ctl.flash[0x19]);
    assertEquals(300, ctl.flash[0x26]);
    assertEquals("still allowed back to what it came with", 520, bike.ceiling(Fardriver.param("line")));
    assertEquals(520, bike.ceiling(Fardriver.param("boost_line")));
    Result back = new Result();
    bike.tryValues(change("line", 520), back);
    run(9000);
    assertEquals(back.calls.toString(), Boolean.TRUE, back.ok);
    assertEquals(520, ctl.ram[0x19]);
    assertEquals(520, ctl.ram[0x26]);
  }

  @Test public void aControllerThatCameWithItsBurstBelowItsLimitIsLeftAsItIs() {
    linkController();
    ctl.ram[0x26] = ctl.flash[0x26] = 400;          // 100 A burst under a 130 A limit: odd, but its own
    run(1500);
    Result r = new Result();
    bike.tryValues(change("line", 440), r);
    run(9000);
    assertEquals(r.calls.toString(), Boolean.TRUE, r.ok);
    assertEquals("only what was asked for is written", Arrays.asList("19=440"), ctl.settingWrites());
    assertEquals(400, ctl.ram[0x26]);
  }

  @Test public void aBurstCannotBeAskedForOnItsOwnAboveWhatItCameWith() {
    linkController();
    Result r = new Result();
    bike.tryValues(change("boost_line", 560), r);    // 140 A on a controller that came with 130 A
    run(2000);
    assertEquals(Boolean.FALSE, r.ok);
    assertTrue(ctl.settingWrites().isEmpty());
    Result under = new Result();
    bike.tryValues(change("boost_line", 480), under);   // below the 130 A limit it sits above
    run(2000);
    assertEquals(Boolean.FALSE, under.ok);
    assertTrue(under.message, under.message.contains("cannot be above"));
    assertTrue(ctl.settingWrites().isEmpty());
  }

  // ---- Battery ---------------------------------------------------------------

  @Test public void batteryNewProtocolInSmallChunks() {
    bike.link(Bike.KIND_BATTERY, "ANT-BLE16ZMUB");
    for (int i = 0; i < 20; i++) ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
    assertNotNull(bike.battery());
    assertEquals(91, bike.battery().soc);
    assertEquals(52.84f, bike.battery().volts, 0.01f);
    for (byte[] f : bms.written) assertArrayEquals(AntBms.REQUEST_NEW, f);
  }

  @Test public void batteryFallsBackToTheLegacyProtocol() {
    bike.link(Bike.KIND_BATTERY, "ANT-BLE14S");
    bms.legacyOnly = true;
    for (int i = 0; i < 100 && bike.battery() == null; i++) ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
    assertNotNull("found the protocol by itself", bike.battery());
    assertTrue(bike.battery().legacy);
    assertEquals(41, bike.battery().soc);
    // Only ever the two read requests, never a write command.
    for (byte[] f : bms.written) {
      assertTrue(hex(f), Arrays.equals(f, AntBms.REQUEST_NEW) || Arrays.equals(f, AntBms.REQUEST_LEGACY));
    }
    // And it is remembered for next time.
    assertTrue(Units.prefs(c).getBoolean("bike_bms_legacy", false));
  }
}
