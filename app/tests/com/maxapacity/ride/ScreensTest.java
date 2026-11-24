package com.maxapacity.ride;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.location.Location;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts the real home screen, opens every screen and sheet in turn, and lays
 * each out and draws it at a spread of sizes: full screen, keyboard up, a
 * small phone, split screen. Nothing may throw.
 *
 * This exists because the first version of the speedometer crashed the home
 * screen whenever the keyboard opened: squeezed to a few pixels, it tried to
 * build a glow with no radius. That is exactly the kind of fault a phone
 * finds in seconds and a compile never does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w393dp-h851dp-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreensTest {

  static final int[] HEIGHTS = {851, 700, 560, 520, 500, 480, 440, 400, 360, 300, 220, 120, 40};
  static final int[] WIDTHS = {393, 320, 280, 600};

  Context c;
  ActivityController<RideActivity> controller;
  RideActivity a;
  BikeSimTest.FakeController ctl;
  BikeSimTest.FakeBms bms;

  @Before public void setUp() throws Exception {
    c = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.BLUETOOTH_SCAN);
    // No update checks: the tests must not depend on, or download from, GitHub.
    Units.prefs(c).edit().clear().putBoolean("auto_update", false).apply();
    Bike.links = (ctx, uuid, l) -> {
      if (uuid.equals(BleLink.uuid(0xFFEC))) return ctl = new BikeSimTest.FakeController(ctx, uuid, l);
      return bms = new BikeSimTest.FakeBms(ctx, uuid, l);
    };
    Field inst = Bike.class.getDeclaredField("instance");
    inst.setAccessible(true);
    inst.set(null, null);
    File[] rides = RideStore.dir(c).listFiles();
    if (rides != null) for (File f : rides) f.delete();
  }

  @After public void tearDown() {
    if (controller != null) controller.pause().stop().destroy();
    Bike.links = BleLink::new;
  }

  void start() {
    controller = Robolectric.buildActivity(RideActivity.class);
    a = controller.setup().get();
    idle(400);
  }

  static void idle(long ms) {
    ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS);
  }

  /** Time passing with the simulated controller streaming, as the real one does. */
  void run(long ms) {
    for (long t = 0; t < ms; t += 200) {
      if (ctl != null) ctl.streamCycle();
      idle(200);
    }
  }

  void drawAt(int wDp, int hDp, String what) {
    float d = a.getResources().getDisplayMetrics().density;
    int w = Math.round(wDp * d);
    int h = Math.round(hDp * d);
    View v = a.getWindow().getDecorView();
    try {
      v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
      v.layout(0, 0, w, h);
      run(200);                         // the bike keeps talking while the screen changes
      Bitmap b = Bitmap.createBitmap(w, Math.max(1, h), Bitmap.Config.ARGB_8888);
      v.draw(new Canvas(b));
      b.recycle();
    } catch (RuntimeException e) {
      throw new AssertionError(what + " at " + wDp + "x" + hDp + "dp: " + e, e);
    }
  }

  void sweep(String what) {
    for (int w : WIDTHS) for (int h : HEIGHTS) drawAt(w, h, what);
    drawAt(393, 851, what);
  }

  Panel panel(String name) throws Exception {
    Field f = RideActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return (Panel) f.get(a);
  }

  void everyPanel(String state) throws Exception {
    for (String n : new String[] {"drawer", "rides", "copilot", "bike", "tune", "deck", "settings"}) {
      panel(n).open();
      run(400);
      sweep(n + " (" + state + ")");
      // Tune asks before closing when a trial is live; none is, so this closes.
      panel(n).close();
      idle(400);
    }
  }

  static Location fix(double lat, double lon, float speed) {
    Location l = new Location("gps");
    l.setLatitude(lat);
    l.setLongitude(lon);
    l.setSpeed(speed);
    l.setAccuracy(4f);
    l.setAltitude(1900);
    l.setTime(System.currentTimeMillis());
    return l;
  }

  /** Three saved rides with GPX files, one in each Strava state that changes the row. */
  void seedRides() throws Exception {
    File dir = RideStore.dir(c);
    dir.mkdirs();
    JSONArray rides = new JSONArray();
    String[] strava = {"uploaded", "failed", ""};
    for (int r = 0; r < 3; r++) {
      List<RideState.Point> track = new ArrayList<>();
      for (int i = 0; i < 120; i++) {
        track.add(new RideState.Point(fix(40.56 + i * 0.0002, -111.63 + Math.sin(i / 9.0) * 0.002, 9f)));
      }
      long start = System.currentTimeMillis() - (r + 1) * 86400000L - r * 61000L;
      File gpx = GpxWriter.write(dir, start, track);
      rides.put(new JSONObject().put("start", start).put("file", gpx.getName())
          .put("distanceM", 9650.0 + 4100 * r).put("movingMs", 1490000L + 600000L * r)
          .put("elapsedMs", 1620000L + 700000L * r).put("topMps", 15.1 + r)
          .put("climbM", 96.0 + 40 * r).put("stops", 2 + r).put("strava", strava[r]));
    }
    try (FileOutputStream out = new FileOutputStream(new File(dir, "index.json"))) {
      out.write(rides.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  // ---- First run: nothing set up ---------------------------------------------

  @Test public void dashboardFirstRun() {
    start();
    sweep("dashboard");
  }

  @Test public void everyScreenEmpty() throws Exception {
    start();
    everyPanel("empty");
  }

  @Test public void everySheetAndTheMessageBar() throws Exception {
    start();
    a.editGeminiKey();
    idle(400);
    sweep("gemini key sheet");
    Sheet.dismissCurrent();
    idle(400);
    a.connectStrava();
    idle(400);
    sweep("strava sheet");
    a.say("Both the Client ID and Client Secret are needed");
    idle(400);
    sweep("message over a sheet");
    Sheet.dismissCurrent();
    idle(400);
    a.editDeckConnection();
    idle(400);
    sweep("deck sheet");
    Sheet.dismissCurrent();
    idle(400);
    a.editUpdateToken();
    idle(400);
    sweep("update token sheet");
    Sheet.dismissCurrent();
    idle(400);
    a.say("A message long enough to wrap onto a second line on a small phone", "UNDO", () -> { });
    idle(400);
    sweep("message bar");
  }

  // ---- In use: riding, rides saved, bike linked ------------------------------

  @Test public void dashboardWhileRidingWithTheBikeLinked() throws Exception {
    start();
    Bike.get(c).link(Bike.KIND_BATTERY, "ANT-BLE16ZMUB");
    Bike.get(c).link(Bike.KIND_CONTROLLER, "CONTROLCN368");
    run(3000);
    org.junit.Assert.assertNotNull(Bike.get(c).battery());
    org.junit.Assert.assertNotNull(Bike.get(c).controller());
    RideState.get().start();
    try {
      for (int i = 0; i < 40; i++) {
        RideState.get().onLocation(fix(40.56 + i * 0.0003, -111.63, 12f + i % 5));
        run(1000);
      }
      sweep("dashboard, riding");
      ctl.ram[0xE3] |= 0x0080;          // controller over-temperature
      run(1000);
      sweep("dashboard, controller fault");
    } finally {
      RideState.get().stop();
    }
  }

  @Test public void everyScreenPopulated() throws Exception {
    seedRides();
    Units.prefs(c).edit().putString("gemini_key", "test-key").putString("deck_host", "192.168.1.20")
        .putString(Updater.TOKEN_PREF, "github_pat_test").apply();
    start();
    Bike.get(c).link(Bike.KIND_BATTERY, "ANT-BLE16ZMUB");
    Bike.get(c).link(Bike.KIND_CONTROLLER, "CONTROLCN368");
    run(3000);
    everyPanel("populated");
    a.showRide(RideStore.load(c).getJSONObject(0));
    for (int i = 0; i < 10; i++) {      // the route is read off the main thread
      Thread.sleep(30);
      idle(100);
    }
    sweep("ride detail");
  }

  @Test public void tuneWithEditsAndALiveTrial() throws Exception {
    start();
    Bike.get(c).link(Bike.KIND_CONTROLLER, "CONTROLCN368");
    run(3000);
    a.showTune();
    run(600);
    java.util.Map<String, Integer> changes = new java.util.LinkedHashMap<>();
    changes.put("accel", 72);
    changes.put("line", 440);
    Bike.get(c).tryValues(changes, (ok, m) -> { });
    run(9000);
    org.junit.Assert.assertEquals(Bike.TRYING, Bike.get(c).tuneState());
    sweep("tune, values on trial");
    // Leaving now must ask rather than strand the trial.
    panel("tune").close();
    idle(400);
    org.junit.Assert.assertNotNull("asks before leaving", Sheet.current());
    org.junit.Assert.assertTrue(panel("tune").isOpen());
    sweep("tune, keep-or-undo sheet");
    Sheet.dismissCurrent();
    idle(400);
    Bike.get(c).revert((ok, m) -> { });
    run(8000);
    org.junit.Assert.assertEquals(Bike.TUNE_IDLE, Bike.get(c).tuneState());
  }

  /** Which screen, if any, is on top. */
  String openScreen() throws Exception {
    String open = null;
    for (String n : new String[] {"drawer", "rides", "copilot", "bike", "tune", "deck", "settings"}) {
      if (panel(n).isOpen()) {
        org.junit.Assert.assertNull("only one screen open at a time", open);
        open = n;
      }
    }
    return open;
  }

  @Test public void aLaunchCanNameTheScreenToOpen() throws Exception {
    start();
    String[][] names = {{"apps", "drawer"}, {"rides", "rides"}, {"copilot", "copilot"},
        {"bike", "bike"}, {"tune", "tune"}, {"deck", "deck"}, {"settings", "settings"}};
    for (String[] n : names) {
      // The on-phone test script does this once a second or so, over adb.
      a.onNewIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN).putExtra("open", n[0]));
      idle(400);
      org.junit.Assert.assertEquals(n[0], n[1], openScreen());
    }
    // Asking for the screen already showing keeps it.
    a.onNewIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN).putExtra("open", "settings"));
    idle(400);
    org.junit.Assert.assertEquals("settings", openScreen());
    // Anything unknown, and plain Home, mean the dashboard.
    a.onNewIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN).putExtra("open", "home"));
    idle(400);
    org.junit.Assert.assertNull(openScreen());
    panel("rides").open();
    idle(400);
    a.onNewIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN));
    idle(400);
    org.junit.Assert.assertNull(openScreen());
  }

  @Test public void aFreshStartCanNameTheScreenToo() throws Exception {
    controller = Robolectric.buildActivity(RideActivity.class,
        new android.content.Intent(android.content.Intent.ACTION_MAIN).putExtra("open", "rides"));
    a = controller.setup().get();
    idle(400);
    org.junit.Assert.assertEquals("rides", openScreen());
  }

  @Test public void aScreenAskedForAgainWhileClosingComesBack() throws Exception {
    start();
    panel("rides").open();
    idle(400);
    panel("rides").close();
    org.junit.Assert.assertFalse("a closing screen no longer counts as open", panel("rides").isOpen());
    panel("rides").open();              // tapped again before the slide-out finished
    idle(600);
    org.junit.Assert.assertTrue(panel("rides").isOpen());
    org.junit.Assert.assertEquals(View.VISIBLE, panel("rides").getVisibility());
    // And a second close while one is in flight does not confuse it.
    panel("rides").close();
    panel("rides").close();
    idle(600);
    org.junit.Assert.assertEquals(View.GONE, panel("rides").getVisibility());
  }

  @Test public void survivesBeingRebuiltWithAScreenAndASheetOpen() throws Exception {
    start();
    panel("settings").open();
    idle(400);
    a.editGeminiKey();
    idle(400);
    // Android tears the activity down and rebuilds it on a font-size or language change.
    controller.pause().stop().destroy();
    controller = null;
    start();
    org.junit.Assert.assertNull("no sheet carried over", Sheet.current());
    sweep("after rebuild");
    panel("drawer").open();
    idle(400);
    a.onBackPressed();
    idle(400);
    org.junit.Assert.assertFalse("Back closes the screen on the first press", panel("drawer").isOpen());
  }
}
