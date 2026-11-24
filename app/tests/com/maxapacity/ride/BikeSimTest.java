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
    assertEquals("not saved", 64, ctl.flash[0x2F]);
    assertEquals(Arrays.asList("2F=72", "19=440"), ctl.settingWrites());

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
    assertEquals(Arrays.asList("2F=72", "19=440", "2F=64", "19=520"), ctl.settingWrites());
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
    assertEquals("the first one was written, then put back",
        Arrays.asList("2F=72", "19=440", "2F=64"), ctl.settingWrites());
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
