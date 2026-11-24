package com.maxapacity.ride;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.location.Location;
import android.view.View;
import android.view.ViewGroup;

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
 *
 * Each screen is also kept as a picture at full phone size, under
 * build/screens/, so the design can be looked over without a phone in hand.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w393dp-h851dp-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreensTest {

  static final int[] HEIGHTS = {851, 700, 560, 520, 500, 480, 440, 400, 360, 300, 220, 120, 40};
  static final int[] WIDTHS = {393, 320, 280, 600};

  /** Where a picture of each screen is kept. */
  static final File PICTURES = new File(System.getProperty("ride.screens", "build/screens"));

  @org.junit.Rule public org.junit.rules.TestName test = new org.junit.rules.TestName();
  int pictures;

  Context c;
  ActivityController<RideActivity> controller;
  RideActivity a;
  BikeSimTest.FakeController ctl;
  BikeSimTest.FakeBms bms;
  FakeDeck fakeDeck;

  @Before public void setUp() throws Exception {
    c = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.BLUETOOTH_SCAN);
    // No update checks: the tests must not depend on, or download from, GitHub. And as a rider
    // who has already answered the welcome, so each test starts on the bare dashboard.
    Units.prefs(c).edit().clear().putBoolean("auto_update", false).putBoolean(Tour.OFFERED, true).apply();
    // As a build with no Strava application of its own, whatever this one was built with.
    Strava.setBuiltInApp(null, null);
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
    Deck.usb = DeckLink.USB;
    if (fakeDeck != null) fakeDeck.stop();
  }

  /** Real time for the background threads, and the main thread's queue along with it. */
  void await(String what, java.util.function.BooleanSupplier ok) throws Exception {
    for (int i = 0; i < 600 && !ok.getAsBoolean(); i++) {
      Thread.sleep(20);
      idle(100);
    }
    if (!ok.getAsBoolean()) throw new AssertionError("timed out waiting for " + what);
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
    drawAt(wDp, hDp, what, false);
  }

  void drawAt(int wDp, int hDp, String what, boolean keep) {
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
      if (keep) keep(b, what);
      b.recycle();
    } catch (RuntimeException e) {
      throw new AssertionError(what + " at " + wDp + "x" + hDp + "dp: " + e, e);
    }
  }

  void sweep(String what) {
    // The picture first: a message bar only stays a few seconds, and the sizes below take time.
    drawAt(393, 851, what, true);
    wellFormed(a.getWindow().getDecorView(), what);
    for (int w : WIDTHS) for (int h : HEIGHTS) drawAt(w, h, what);
    drawAt(393, 851, what);
  }

  /** Rules every screen keeps, checked on each one as it is drawn. */
  void wellFormed(View v, String what) {
    if (v.getVisibility() != View.VISIBLE) return;
    if (v instanceof android.widget.EditText) {
      float d = a.getResources().getDisplayMetrics().density;
      org.junit.Assert.assertTrue(what + ": the field \"" + ((android.widget.EditText) v).getHint()
          + "\" is " + Math.round(v.getHeight() / d) + "dp tall, too short to tap or read",
          v.getHeight() >= Math.round(48 * d) - 1);
    }
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) wellFormed(g.getChildAt(i), what);
    }
  }

  /** build/screens/<test>/<nn>-<what>.png. A convenience: failing to write one fails nothing. */
  void keep(Bitmap b, String what) {
    try {
      File dir = new File(PICTURES, test.getMethodName());
      dir.mkdirs();
      String name = String.format(java.util.Locale.US, "%02d-%s.png", ++pictures,
          what.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", ""));
      try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
    } catch (Exception e) {
      // The drawing is the test; the picture is for people.
    }
  }

  Panel panel(String name) throws Exception {
    Field f = RideActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return (Panel) f.get(a);
  }

  void everyPanel(String state) throws Exception {
    for (String n : new String[] {"drawer", "rides", "copilot", "bike", "tune", "deck", "fullDeck", "settings"}) {
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
          .put("climbM", 96.0 + 40 * r).put("stops", 2 + r).put("leanLeft", 31 - r).put("leanRight", 27 + r).put("strava", strava[r]));
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

  /** With no rides yet, the Rides screen's one button starts one. */
  @Test public void theEmptyRidesScreenStartsARide() throws Exception {
    start();
    panel("rides").open();
    run(400);
    org.junit.Assert.assertFalse(RideState.get().isRecording());
    while (Shadows.shadowOf(a).getNextStartedService() != null) { }   // whatever started earlier
    tap("Start a ride");
    run(400);
    android.content.Intent asked = Shadows.shadowOf(a).getNextStartedService();
    org.junit.Assert.assertNotNull("the ride recorder is asked to start", asked);
    org.junit.Assert.assertEquals(RideService.class.getName(), asked.getComponent().getClassName());
    org.junit.Assert.assertEquals(RideService.ACTION_START, asked.getAction());
    org.junit.Assert.assertFalse("and the dashboard is back on show", panel("rides").isOpen());
  }

  @Test public void everySheetAndTheMessageBar() throws Exception {
    start();
    a.editGeminiKey();
    idle(400);
    sweep("gemini key sheet");
    Sheet.dismissCurrent();
    idle(400);
    a.connectStrava(null);
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
    // Every other way in is one tap from there, and each comes back to it.
    tap("Type or paste the address");
    sweep("deck address sheet");
    tap("Back");
    org.junit.Assert.assertNotNull("back at the ways in", Sheet.current());
    tap("Use a USB cable");
    sweep("deck usb sheet");
    tap("Back");
    tap("What to check");
    sweep("deck checks sheet");
    tap("Back");
    tap("Link report");
    sweep("deck report sheet");
    tap("Share");
    String report = handedOver();
    org.junit.Assert.assertNotNull("the report can be sent to whoever is helping", report);
    org.junit.Assert.assertTrue(report, report.contains("Link: ") && report.contains("Network: "));
    a.editCrashAlert();
    idle(400);
    sweep("crash alert sheet");
    Sheet.dismissCurrent();
    idle(400);
    a.say("A message long enough to wrap onto a second line on a small phone", "UNDO", () -> { });
    idle(400);
    // On the dashboard it sits above the dock, so the other screens stay one tap away.
    drawAt(393, 851, "message bar");
    View words = find(a.getWindow().getDecorView(), android.widget.TextView.class,
        "A message long enough to wrap onto a second line on a small phone");
    org.junit.Assert.assertNotNull("still showing", words);
    Field dockField = RideActivity.class.getDeclaredField("dock");
    dockField.setAccessible(true);
    int[] bar = new int[2];
    int[] dock = new int[2];
    ((View) words.getParent()).getLocationInWindow(bar);
    ((View) dockField.get(a)).getLocationInWindow(dock);
    org.junit.Assert.assertTrue("the message bar ends at " + (bar[1] + ((View) words.getParent()).getHeight())
        + ", the dock starts at " + dock[1], bar[1] + ((View) words.getParent()).getHeight() <= dock[1]);
    sweep("message bar");
  }

  // ---- In use: riding, rides saved, bike linked ------------------------------

  /** The Deck link at each step: found and wanting its code, then paired and showing the desk. */
  @Test public void theDeckFoundThenPairedAndLinked() throws Exception {
    fakeDeck = new FakeDeck(true);
    fakeDeck.code = "424242";
    Deck.usb = "127.0.0.1:1";           // no cable
    FakeDeck.onWifi(c);
    Units.prefs(c).edit().putString("deck_host", fakeDeck.address()).apply();
    seedRides();
    start();
    await("the Deck to be found", () -> Deck.get(c).state() == Deck.FOUND);
    a.editDeckConnection();
    idle(400);
    sweep("deck code sheet");
    tap("Code not working?");
    sweep("deck code help sheet");
    tap("Back");
    org.junit.Assert.assertNotNull("back at the code", find(Sheet.current(), android.widget.EditText.class, null));
    Sheet.dismissCurrent();
    idle(400);
    panel("deck").open();
    idle(400);
    sweep("deck found, wanting its code");
    panel("settings").open();
    idle(400);
    sweep("settings with a deck found");
    panel("settings").close();
    idle(400);

    // Typing the sixth digit is enough: the code pairs by itself.
    a.editDeckConnection();
    idle(400);
    find(Sheet.current(), android.widget.EditText.class, null).setText("424242");
    await("pairing", () -> Deck.get(c).state() == Deck.LINKED);
    await("the rides to go home", () -> fakeDeck.stored.size() == 6);
    for (int i = 0; i < 20; i++) {      // the Deck screen reads the desk off the main thread
      Thread.sleep(30);
      idle(200);
    }
    sweep("deck linked, showing the desk");
    // The whole Deck is one tap from the glance, let in with the key this phone already holds.
    tap("Full Deck");
    org.junit.Assert.assertTrue(panel("fullDeck").isOpen());
    android.webkit.WebView page = find(panel("fullDeck"), android.webkit.WebView.class, null);
    org.junit.Assert.assertNotNull("the Deck's own page is on screen", page);
    org.junit.Assert.assertEquals("http://" + fakeDeck.address() + "/", Shadows.shadowOf(page).getLastLoadedUrl());
    org.junit.Assert.assertEquals(fakeDeck.key, Shadows.shadowOf(page).getLastAdditionalHttpHeaders().get("x-deck-token"));
    sweep("full deck, linked");
    panel("fullDeck").close();
    idle(400);
    org.junit.Assert.assertNull("the page is let go when the screen closes", find(panel("fullDeck"), android.webkit.WebView.class, null));
    a.editDeckConnection();
    idle(400);
    sweep("deck linked sheet");
    tap("Link report");
    sweep("deck report sheet, linked");
    tap("Share");
    String report = handedOver();
    org.junit.Assert.assertTrue(report, report.contains("Link: linked at " + fakeDeck.address()));
    org.junit.Assert.assertFalse("the key never leaves the phone in a report", report.contains(fakeDeck.key));
    // Forgetting can be taken back while the Deck still knows this phone.
    a.editDeckConnection();
    idle(400);
    tap("Forget this Deck");
    await("the Deck to want its code again", () -> Deck.get(c).state() == Deck.FOUND);
    tap("UNDO");
    await("the forget to be undone", () -> Deck.get(c).state() == Deck.LINKED);
    Sheet.dismissCurrent();
    idle(400);
    panel("deck").close();
    idle(400);
    a.showRide(RideStore.load(c).getJSONObject(0));
    idle(400);
    sweep("a ride that is on the Mac");
  }

  /** The pretend bike drives the same screens a real one does, and leaves no trace when it goes. */
  @Test public void theDemoBikeFillsEveryBikeScreenAndLeavesNoTrace() throws Exception {
    start();
    Bike bike = Bike.get(c);
    org.junit.Assert.assertFalse(bike.anyConfigured());
    panel("bike").open();
    idle(400);
    sweep("bike screen offering the demo");
    org.junit.Assert.assertTrue(bike.setDemo(true));
    for (int i = 0; i < 25; i++) idle(200);     // five seconds of the pretend ride
    org.junit.Assert.assertTrue(bike.demo());
    org.junit.Assert.assertNotNull("battery readings", bike.battery());
    org.junit.Assert.assertNotNull("controller readings", bike.controller());
    org.junit.Assert.assertEquals(20, bike.battery().cellVolts.length);
    org.junit.Assert.assertEquals("HA80", bike.controller().firmware);
    sweep("bike screen with the demo bike");
    panel("bike").close();
    idle(400);
    for (int i = 0; i < 40; i++) idle(200);     // on into the pull-away
    sweep("dashboard with the demo bike");
    panel("tune").open();
    idle(400);
    sweep("tune screen with the demo bike");
    org.junit.Assert.assertTrue("a brief written from the demo says it is one",
        TuneBrief.build(c, "More range.").startsWith("Note: this comes from the app's demo bike."));
    panel("tune").close();
    idle(400);

    org.junit.Assert.assertTrue(bike.setDemo(false));
    idle(400);
    org.junit.Assert.assertFalse(bike.anyConfigured());
    org.junit.Assert.assertNull(bike.battery());
    org.junit.Assert.assertNull(bike.controller());
    org.junit.Assert.assertEquals("", Units.prefs(c).getString("bike_bms_name", ""));
    org.junit.Assert.assertEquals("", Units.prefs(c).getString("bike_ctl_name", ""));
    sweep("dashboard after the demo");
  }

  @Test public void dashboardWhileRidingWithTheBikeLinked() throws Exception {
    start();
    Bike.get(c).link(Bike.KIND_BATTERY, "ANT-BLE16ZMUB");
    Bike.get(c).link(Bike.KIND_CONTROLLER, "CONTROLCN368");
    run(3000);
    org.junit.Assert.assertNotNull(Bike.get(c).battery());
    org.junit.Assert.assertNotNull(Bike.get(c).controller());
    RideState.get().start();
    try {
      double lat = 40.56;
      for (int i = 0; i < 40; i++) {
        float speed = 12f + i % 5;              // 27 to 36 mph
        lat += speed / 111320.0;                // a second's travel north at that speed
        RideState.get().onLocation(fix(lat, -111.63, speed));
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

  /**
   * A ride with the bike linked keeps what the bike did: it is saved with the ride, shown on the
   * ride's page, and goes into the next tune brief and to the Copilot.
   */
  @Test public void aRideWithTheBikeLinkedKeepsWhatTheBikeDid() throws Exception {
    start();
    Bike bike = Bike.get(c);
    bike.link(Bike.KIND_BATTERY, "ANT-BLE16ZMUB");
    bike.link(Bike.KIND_CONTROLLER, "CONTROLCN368");
    run(3000);
    org.junit.Assert.assertNotNull(bike.controller());
    RideState.get().start();
    double lat = 40.56;
    try {
      for (int i = 0; i < 60; i++) {
        float speed = 12f + i % 5;
        lat += speed / 111320.0;
        RideState.get().onLocation(fix(lat, -111.63, speed));
        // A minute of riding: rest, a hard pull that sags the pack, a roll-off into regen.
        boolean pull = i >= 10 && i < 40;
        boolean regen = i >= 45 && i < 50;
        ctl.ram[0xE8] = pull ? 752 : regen ? 818 : 812;        // volts x 10
        ctl.ram[0xEA] = (pull ? 120 * 4 : regen ? -24 * 4 : 4) & 0xFFFF;   // amps x 4
        ctl.ram[0xF4] = 40 + i / 2;                              // motor warming up
        ctl.ram[0xDB] = 30 + i / 4;
        run(1000);
      }
    } finally {
      String said = RideStore.finish(c);
      org.junit.Assert.assertTrue(said, said.startsWith("Ride saved"));
    }
    ctl.ram[0xEA] = 0;
    run(1000);

    JSONObject ride = RideStore.load(c).getJSONObject(RideStore.load(c).length() - 1);
    BikeLog.Summary s = RideStore.bike(ride);
    org.junit.Assert.assertNotNull("the bike's figures are saved with the ride", s);
    org.junit.Assert.assertEquals("120 A at 75.2 V", 9.02f, s.peakKw, 0.02f);
    org.junit.Assert.assertEquals(120f, s.peakAmps, 0.1f);
    org.junit.Assert.assertEquals(24f, s.regenAmps, 0.1f);
    org.junit.Assert.assertEquals(75.2f, s.lowVolts, 0.05f);
    org.junit.Assert.assertEquals("from 81.2 V at rest", 6.0f, s.sagVolts, 0.05f);
    org.junit.Assert.assertEquals(69, s.motorC);
    org.junit.Assert.assertEquals(44, s.controllerC);
    // Thirty seconds at 9.02 kW is 75 Wh, and the seconds either side add a little.
    org.junit.Assert.assertEquals(76f, s.usedWh, 2.5f);
    org.junit.Assert.assertEquals(2.6f, s.regenWh, 0.6f);
    org.junit.Assert.assertEquals("the battery's own charge reading", 91, s.socStart);

    a.showRide(ride);
    for (int i = 0; i < 10; i++) {
      Thread.sleep(30);
      idle(100);
    }
    sweep("ride with the bike linked");
    View page = a.getWindow().getDecorView();
    org.junit.Assert.assertNotNull(find(page, android.widget.TextView.class, "THE BIKE"));
    org.junit.Assert.assertNotNull(find(page, android.widget.TextView.class, "120 A"));
    org.junit.Assert.assertNotNull(find(page, android.widget.TextView.class, "75.2 V, sag 6.0 V"));
    org.junit.Assert.assertNotNull(find(page, android.widget.TextView.class, "24 A peak, 3 Wh back"));

    String brief = TuneBrief.build(c, "More range.");
    org.junit.Assert.assertTrue(brief, brief.contains("WHAT THE BIKE DID ON MY LAST RIDE WITH IT LINKED\n- The ride: "));
    org.junit.Assert.assertTrue(brief, brief.contains("- Most power drawn: 9.0 kW, at 120 A from the battery.\n"));
    org.junit.Assert.assertTrue(brief, brief.contains("- Lowest pack voltage: 75.2 V. Its deepest drop under load was 6.0 V.\n"));
    org.junit.Assert.assertTrue(brief, brief.contains("- Strongest regen: 24 A into the battery."));
    org.junit.Assert.assertTrue("still short enough to paste anywhere: " + brief.length(), brief.length() < 7000);
    String persona = Gemini.persona(c);
    org.junit.Assert.assertTrue(persona, persona.contains("What the bike did on the last ride it was linked for:"));
    org.junit.Assert.assertTrue(persona, persona.contains("- Most power drawn: 9.0 kW"));

    // A ride with no bike linked has none of it, and says nothing about a bike.
    Bike.get(c).unlink(Bike.KIND_CONTROLLER);
    Bike.get(c).unlink(Bike.KIND_BATTERY);
    run(1000);
    RideState.get().start();
    for (int i = 0; i < 5; i++) {
      lat += 14 / 111320.0;
      RideState.get().onLocation(fix(lat, -111.63, 14f));
      run(1000);
    }
    RideStore.finish(c);
    JSONObject plain = RideStore.load(c).getJSONObject(RideStore.load(c).length() - 1);
    org.junit.Assert.assertNull(RideStore.bike(plain));
    org.junit.Assert.assertFalse(plain.has(RideStore.BIKE));
  }

  @Test public void everyScreenPopulated() throws Exception {
    seedRides();
    Units.prefs(c).edit().putString("gemini_key", "test-key").apply();
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

  /** The first view of a kind under a root, or the one showing these words (buttons show theirs in capitals). */
  static <T extends View> T find(View root, Class<T> kind, String text) {
    if (kind.isInstance(root) && (text == null || (root instanceof android.widget.TextView
        && text.equalsIgnoreCase(((android.widget.TextView) root).getText().toString())))) {
      return kind.cast(root);
    }
    if (root instanceof android.view.ViewGroup) {
      android.view.ViewGroup g = (android.view.ViewGroup) root;
      for (int i = 0; i < g.getChildCount(); i++) {
        T hit = find(g.getChildAt(i), kind, text);
        if (hit != null) return hit;
      }
    }
    return null;
  }

  /** Taps whatever carries these words: on the sheet if one is up, else on the tour's card, else on the screen. */
  void tap(String words) {
    View root = Sheet.current() != null ? Sheet.current()
        : Tour.current() != null ? Tour.current() : a.getWindow().getDecorView();
    View v = find(root, android.widget.TextView.class, words);
    org.junit.Assert.assertNotNull("on screen: " + words, v);
    while (v != null && !v.hasOnClickListeners()) v = v.getParent() instanceof View ? (View) v.getParent() : null;
    org.junit.Assert.assertNotNull("tappable: " + words, v);
    v.performClick();
    idle(400);
  }

  /** The text another app was just handed, or null if none was. */
  String handedOver() {
    android.content.Intent started;
    do {
      started = Shadows.shadowOf(a).getNextStartedActivity();
    } while (started != null && !android.content.Intent.ACTION_CHOOSER.equals(started.getAction()));
    if (started == null) return null;
    android.content.Intent send = started.getParcelableExtra(android.content.Intent.EXTRA_INTENT);
    org.junit.Assert.assertEquals(android.content.Intent.ACTION_SEND, send.getAction());
    org.junit.Assert.assertEquals("text/plain", send.getType());
    return send.getStringExtra(android.content.Intent.EXTRA_TEXT);
  }

  /** Tune ideas writes the bike up and hands it to another app. The bike itself is never written to. */
  @Test public void tuneIdeasSendsABriefAndLeavesTheBikeAlone() throws Exception {
    start();
    Bike bike = Bike.get(c);
    bike.link(Bike.KIND_BATTERY, "ANT-BLE16ZMUB");
    bike.link(Bike.KIND_CONTROLLER, "CONTROLCN368");
    run(3000);
    a.showTune();
    run(600);
    int[] saved = ctl.flash.clone();
    sweep("tune, offering ideas");

    tap("Tune ideas");
    org.junit.Assert.assertNotNull("the goals come up", Sheet.current());
    sweep("tune ideas sheet");
    tap("More range");
    String brief = handedOver();
    org.junit.Assert.assertNotNull("another app is offered the brief", brief);
    org.junit.Assert.assertTrue(brief, brief.contains("WHAT I WANT\nMore range."));
    org.junit.Assert.assertTrue("the linked battery is in it: " + brief, brief.contains(" cells in series, "));
    org.junit.Assert.assertFalse(brief, brief.contains("demo bike") || brief.contains("ABOUT MY BIKE"));
    for (Fardriver.Param p : Fardriver.PARAMS) {
      String now = p.format(bike.controllerMemory().raw(p));
      org.junit.Assert.assertTrue(p.name, brief.contains("- " + p.name + ": " + now + ". "));
      if (p.options != null) continue;
      // The range it gives is the one the Tune screen holds this very controller to.
      String top = p.format(TuneBrief.highest(p, bike.controllerMemory().raw(p), bike.ceiling(p)));
      org.junit.Assert.assertTrue(p.name + " up to " + top, brief.contains("- " + p.name + ": " + now + ". Range "
          + p.format(TuneBrief.lowest(p, bike.controllerMemory().raw(p))) + " to " + top));
    }

    // A note about the bike, kept for every brief after it.
    ((TunePanel) panel("tune")).showIdeas();
    idle(400);
    tap("About my bike");
    sweep("about my bike sheet");
    find(Sheet.current(), android.widget.EditText.class, null).setText(" DRAX X-Pro 72V, 13/52 gearing, 190 lb rider ");
    tap("Save");
    org.junit.Assert.assertEquals("DRAX X-Pro 72V, 13/52 gearing, 190 lb rider", Units.prefs(c).getString(TuneBrief.ABOUT, ""));
    org.junit.Assert.assertNotNull("back at the goals", Sheet.current());
    sweep("tune ideas sheet, with the note");

    // In the rider's own words: nothing goes out until something has been said.
    tap("Something else");
    sweep("own words sheet");
    tap("Send");
    org.junit.Assert.assertNull("an empty wish is not sent", handedOver());
    org.junit.Assert.assertNotNull("and the sheet stays for another go", Sheet.current());
    find(Sheet.current(), android.widget.EditText.class, null).setText("Less wheelspin on wet roots");
    tap("Send");
    brief = handedOver();
    org.junit.Assert.assertNotNull(brief);
    org.junit.Assert.assertTrue(brief, brief.contains("WHAT I WANT\nLess wheelspin on wet roots\n"));
    org.junit.Assert.assertTrue(brief, brief.contains("ABOUT MY BIKE AND ME\nDRAX X-Pro 72V, 13/52 gearing, 190 lb rider\n"));

    // The answer comes back by way of the clipboard. Its SET lines load as changes to look over,
    // and one that asks for more than this controller came with is left out and said.
    c.getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
        android.content.ClipData.newPlainText("answer", "Lower these two for more range.\n"
            + "SET Battery current limit = 100 A\nSET Motor current limit = 500 A\n"));
    ((TunePanel) panel("tune")).showIdeas();
    idle(400);
    tap("Paste the answer");
    run(400);
    org.junit.Assert.assertNotNull("what was loaded and what was left out are both said",
        find(a.getWindow().getDecorView(), android.widget.TextView.class, "1 change loaded to look over. Left out:"
            + " Motor current limit 500 A is outside what this bike allows (40 A to 350 A)"));
    sweep("tune, an answer pasted in");

    org.junit.Assert.assertEquals("no setting was written", 0, ctl.settingWriteCount);
    org.junit.Assert.assertEquals(Bike.TUNE_IDLE, bike.tuneState());
    org.junit.Assert.assertArrayEquals("and nothing was saved", saved, ctl.flash);
  }

  /** A whole tune loads as changes to look over, is tried like any other change, and can be undone. */
  @Test public void aWholeTuneLoadsAsChangesAndGoesThroughTheSameTrial() throws Exception {
    start();
    Bike bike = Bike.get(c);
    bike.link(Bike.KIND_BATTERY, "ANT-BLE16ZMUB");
    bike.link(Bike.KIND_CONTROLLER, "CONTROLCN368");
    run(3000);
    a.showTune();
    run(600);

    tap("Tunes");
    org.junit.Assert.assertNotNull("the tunes come up", Sheet.current());
    sweep("tunes sheet");
    tap("Calm");
    run(400);
    org.junit.Assert.assertEquals("loading a tune sends nothing", 0, ctl.settingWriteCount);
    sweep("tune, calm loaded to look over");

    tap("Try on bike");
    run(14000);
    org.junit.Assert.assertEquals(Bike.TRYING, bike.tuneState());
    org.junit.Assert.assertEquals("75 A of the 130 A it came with", 300, ctl.ram[0x19]);
    org.junit.Assert.assertEquals("and a burst cannot draw more", 300, ctl.ram[0x26]);
    org.junit.Assert.assertEquals(840, ctl.ram[0x2D]);
    org.junit.Assert.assertEquals(880, ctl.ram[0x27]);
    sweep("tune, calm on trial");
    bike.revert((ok, m) -> { });
    run(10000);
    org.junit.Assert.assertEquals(Bike.TUNE_IDLE, bike.tuneState());
    org.junit.Assert.assertArrayEquals("undone: exactly as it came", ctl.flash, ctl.ram);

    // A tune published for another bike asks for more than this controller came with: not loaded.
    int writes = ctl.settingWriteCount;
    ((TunePanel) panel("tune")).showTunes();
    idle(400);
    tap("MAXAPACITY bikes");
    sweep("builds sheet");
    tap("Drax Bee Chill");
    sweep("build tune, which bike it is for");
    tap("Load");
    run(400);
    org.junit.Assert.assertNull(Sheet.current());
    org.junit.Assert.assertNotNull("it says why", find(a.getWindow().getDecorView(), android.widget.TextView.class,
        "Drax Bee Chill asks for 180 A as the battery current limit. This controller came set to 130 A and the app"
            + " never goes above that, so the tune does not fit this bike"));
    sweep("tune, a build tune refused");
    org.junit.Assert.assertEquals(writes, ctl.settingWriteCount);

    // The battery's own rating, when the rider knows it, holds the tunes at or under it.
    ((TunePanel) panel("tune")).showTunes();
    idle(400);
    tap("Battery rating");
    sweep("battery rating sheet");
    find(Sheet.current(), android.widget.EditText.class, null).setText("100");
    tap("Save");
    org.junit.Assert.assertEquals(100, Units.prefs(c).getInt(TunePlan.PACK_AMPS, 0));
    org.junit.Assert.assertNotNull("back at the tunes", Sheet.current());
    sweep("tunes sheet, with a rated battery");
    tap("Aggressive");
    run(400);
    tap("Try on bike");
    run(14000);
    org.junit.Assert.assertEquals("the pack's 100 A, not the controller's 130 A", 400, ctl.ram[0x19]);
    org.junit.Assert.assertEquals(400, ctl.ram[0x26]);
    bike.revert((ok, m) -> { });
    run(10000);
    org.junit.Assert.assertArrayEquals(ctl.flash, ctl.ram);
  }

  /** Copilot can answer tune questions once the settings are in: it is told them and their limits. */
  @Test public void copilotIsToldTheTuneOnceTheSettingsAreRead() throws Exception {
    start();
    org.junit.Assert.assertFalse(Gemini.persona(c).contains("Tune settings on the controller"));
    Bike.get(c).link(Bike.KIND_CONTROLLER, "CONTROLCN368");
    run(3000);
    String persona = Gemini.persona(c);
    org.junit.Assert.assertTrue(persona, persona.contains("Tune settings on the controller now"));
    for (Fardriver.Param p : Fardriver.PARAMS) {
      org.junit.Assert.assertTrue(p.name, persona.contains("- " + p.name + ": "
          + p.format(Bike.get(c).controllerMemory().raw(p)) + ". "));
    }
    org.junit.Assert.assertTrue(persona.contains("Rules the app enforces:"));
  }

  // ---- The welcome, the tour and what's new ------------------------------------

  /** As the phone of someone who has only just installed the app. */
  void asANewRider() {
    Units.prefs(c).edit().remove(Tour.OFFERED).remove(WhatsNew.SEEN).apply();
  }

  /** As a phone whose app has updated itself since it was installed. */
  void asAnUpdatedApp() {
    android.content.pm.PackageInfo info =
        Shadows.shadowOf(c.getPackageManager()).getInternalMutablePackageInfo(c.getPackageName());
    info.firstInstallTime = 1000L;
    info.lastUpdateTime = 2000L;
  }

  /** The home screen torn down and built again, as when the app is next opened. */
  void restart() {
    controller.pause().stop().destroy();
    controller = null;
    start();
  }

  View onScreen(String words) {
    return find(a.getWindow().getDecorView(), android.widget.TextView.class, words);
  }

  /** Where the tour's light is, as last drawn. */
  android.graphics.RectF lit() throws Exception {
    Field f = Tour.class.getDeclaredField("lit");
    f.setAccessible(true);
    return (android.graphics.RectF) f.get(Tour.current());
  }

  static final String[] STOPS = {"Speed", "The ride so far", "Recording a ride", "Your bike", "Ride apps",
      "Everything else", "Settings"};

  @Test public void aNewRiderIsWelcomedAndWalkedRoundTheDashboard() throws Exception {
    asANewRider();
    start();
    org.junit.Assert.assertNotNull("the welcome is up",
        find(Sheet.current(), android.widget.TextView.class, "Welcome to MAXAPACITY Ride"));
    sweep("welcome sheet");
    tap("Start tour");
    org.junit.Assert.assertNull(Sheet.current());
    org.junit.Assert.assertNotNull("the tour starts", Tour.current());
    float d = a.getResources().getDisplayMetrics().density;
    for (int i = 0; i < STOPS.length; i++) {
      org.junit.Assert.assertNotNull(STOPS[i], find(Tour.current(), android.widget.TextView.class, STOPS[i]));
      org.junit.Assert.assertNotNull(find(Tour.current(), android.widget.TextView.class, (i + 1) + " OF " + STOPS.length));
      sweep("tour, " + STOPS[i]);
      // At a phone's full size each stop lights a real part of the dashboard, and the card sits clear of it.
      android.graphics.RectF lit = lit();
      org.junit.Assert.assertTrue(STOPS[i] + " lights " + lit, lit.width() >= 40 * d && lit.height() >= 40 * d);
      View card = Tour.current().getChildAt(0);
      float top = card.getTop() + card.getTranslationY();
      org.junit.Assert.assertTrue(STOPS[i] + ": the card (" + top + " to " + (top + card.getHeight()) + ") is clear of " + lit,
          top >= lit.bottom || top + card.getHeight() <= lit.top);
      org.junit.Assert.assertTrue(STOPS[i] + ": the card is on the screen", top >= 0 && top + card.getHeight() <= 851 * d);
      tap(i == STOPS.length - 1 ? "Done" : "Next");
    }
    org.junit.Assert.assertNull("Done on the last stop ends it", Tour.current());
    org.junit.Assert.assertTrue(Units.prefs(c).getBoolean(Tour.OFFERED, false));
    sweep("dashboard after the tour");

    // Offered once: the next time the app opens it is the plain dashboard.
    restart();
    org.junit.Assert.assertNull(Sheet.current());
    org.junit.Assert.assertNull(Tour.current());
  }

  @Test public void aTourTurnedDownStaysInSettings() throws Exception {
    asANewRider();
    start();
    tap("Not now");
    org.junit.Assert.assertNull(Sheet.current());
    org.junit.Assert.assertNull(Tour.current());
    org.junit.Assert.assertTrue("no is an answer too", Units.prefs(c).getBoolean(Tour.OFFERED, false));
    restart();
    org.junit.Assert.assertNull("and it is not asked again", Sheet.current());

    panel("settings").open();
    idle(400);
    tap("Take the tour");
    org.junit.Assert.assertNotNull(Tour.current());
    org.junit.Assert.assertFalse("it is a tour of the dashboard, so Settings steps aside", panel("settings").isOpen());
    // Nothing underneath reacts while it is up: a swipe does not open the app drawer.
    View under = a.getWindow().getDecorView();
    long t = android.os.SystemClock.uptimeMillis();
    for (int i = 0; i <= 6; i++) {
      android.view.MotionEvent e = android.view.MotionEvent.obtain(t, t + i * 10,
          i == 0 ? android.view.MotionEvent.ACTION_DOWN : i == 6 ? android.view.MotionEvent.ACTION_UP
              : android.view.MotionEvent.ACTION_MOVE, under.getWidth() / 2f, under.getHeight() * 0.8f - i * 120, 0);
      a.dispatchTouchEvent(e);
      e.recycle();
    }
    idle(400);
    org.junit.Assert.assertNull("no screen opened under the tour", openScreen());

    // Skip on the first card ends it, as do the cross and the Back key from anywhere.
    tap("Skip");
    org.junit.Assert.assertNull(Tour.current());
    a.showTour();
    idle(400);
    tap("Next");
    tap("Next");
    tap("Back");
    org.junit.Assert.assertNotNull("Back goes one stop back", find(Tour.current(), android.widget.TextView.class, "2 OF " + STOPS.length));
    a.onBackPressed();
    idle(400);
    org.junit.Assert.assertNull("the Back key ends the tour", Tour.current());
    a.showTour();
    idle(400);
    View cross = null;
    java.util.ArrayDeque<View> look = new java.util.ArrayDeque<>();
    look.add(Tour.current());
    while (!look.isEmpty() && cross == null) {
      View v = look.poll();
      if ("End the tour".contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) cross = v;
      if (v instanceof android.view.ViewGroup) {
        for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) look.add(((android.view.ViewGroup) v).getChildAt(i));
      }
    }
    org.junit.Assert.assertNotNull("the cross is there", cross);
    cross.performClick();
    idle(400);
    org.junit.Assert.assertNull(Tour.current());
    // Home, or a launch that names a screen, takes it down as well.
    a.showTour();
    idle(400);
    a.onNewIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN).putExtra("open", "rides"));
    idle(400);
    org.junit.Assert.assertNull(Tour.current());
    org.junit.Assert.assertEquals("rides", openScreen());
  }

  /** The welcome keeps for a moment when it would be seen: not under a launch that names a screen, not mid-ride. */
  @Test public void theWelcomeWaitsForAPlainArrivalAtTheDashboard() throws Exception {
    asANewRider();
    // The on-phone test script opens the app this way, a second or so before it asks for the next screen.
    controller = Robolectric.buildActivity(RideActivity.class,
        new android.content.Intent(android.content.Intent.ACTION_MAIN).putExtra("open", "home"));
    a = controller.setup().get();
    idle(400);
    org.junit.Assert.assertNull("not while something else is driving the app", Sheet.current());
    org.junit.Assert.assertFalse(Units.prefs(c).getBoolean(Tour.OFFERED, false));
    controller.pause();
    a.onNewIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN).putExtra("open", "settings"));
    controller.resume();
    idle(400);
    org.junit.Assert.assertNull(Sheet.current());
    controller.pause();
    a.onNewIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN).putExtra("open", "home"));
    controller.resume();
    idle(400);
    org.junit.Assert.assertNull(Sheet.current());
    org.junit.Assert.assertFalse("still owed", Units.prefs(c).getBoolean(Tour.OFFERED, false));

    // Mid-ride is no time for it either.
    RideState.get().start();
    try {
      controller.pause();
      controller.resume();
      idle(400);
      org.junit.Assert.assertNull("not during a ride", Sheet.current());
    } finally {
      RideState.get().stop();
    }

    // The rider picks the phone up afterwards: there it is.
    controller.pause();
    controller.resume();
    idle(400);
    org.junit.Assert.assertNotNull(find(Sheet.current(), android.widget.TextView.class, "Welcome to MAXAPACITY Ride"));
    // Closing it any way is an answer. Here, the Back key.
    a.onBackPressed();
    idle(400);
    org.junit.Assert.assertTrue(Units.prefs(c).getBoolean(Tour.OFFERED, false));
  }

  @Test public void anUpdateSaysWhatItBroughtOnce() throws Exception {
    // A phone whose app was last a build from before there were notes.
    asAnUpdatedApp();
    asANewRider();
    start();
    org.junit.Assert.assertNotNull("headed with the version the phone now has",
        find(Sheet.current(), android.widget.TextView.class, "What's new in " + Updater.versionName(c)));
    for (WhatsNew.Item item : WhatsNew.NOTES[0].items) {
      org.junit.Assert.assertNotNull(item.title, find(Sheet.current(), android.widget.TextView.class, item.title));
      org.junit.Assert.assertNotNull(item.title, find(Sheet.current(), android.widget.TextView.class, item.detail));
    }
    org.junit.Assert.assertNull("only the newest note is news to them",
        find(Sheet.current(), android.widget.TextView.class, WhatsNew.NOTES[1].items[0].title));
    org.junit.Assert.assertTrue("someone who already has the app is not given the welcome",
        Units.prefs(c).getBoolean(Tour.OFFERED, false));
    sweep("what's new sheet");
    tap("Got it");
    org.junit.Assert.assertNull(Sheet.current());
    org.junit.Assert.assertEquals(WhatsNew.latest(), Units.prefs(c).getInt(WhatsNew.SEEN, -1));
    restart();
    org.junit.Assert.assertNull("said once", Sheet.current());

    // A phone two updates behind is told about both, newest first.
    Units.prefs(c).edit().putInt(WhatsNew.SEEN, WhatsNew.latest() - 2).apply();
    restart();
    for (int n = 0; n < 2; n++) {
      for (WhatsNew.Item item : WhatsNew.NOTES[n].items) {
        org.junit.Assert.assertNotNull(item.title, find(Sheet.current(), android.widget.TextView.class, item.title));
      }
    }
    sweep("what's new sheet, two updates at once");
    Sheet.dismissCurrent();
    idle(400);
    org.junit.Assert.assertEquals(WhatsNew.latest(), Units.prefs(c).getInt(WhatsNew.SEEN, -1));

    // Settings keeps every note, under the month it came in.
    panel("settings").open();
    idle(400);
    tap("What's new");
    org.junit.Assert.assertNotNull(find(Sheet.current(), android.widget.TextView.class, "What's new"));
    org.junit.Assert.assertNotNull(find(Sheet.current(), android.widget.TextView.class, WhatsNew.NOTES[0].when));
    for (WhatsNew.Note note : WhatsNew.NOTES) {
      for (WhatsNew.Item item : note.items) {
        org.junit.Assert.assertNotNull(item.title, find(Sheet.current(), android.widget.TextView.class, item.title));
      }
    }
    sweep("what's new, every note");
    tap("Done");
    org.junit.Assert.assertNull(Sheet.current());
  }

  @Test public void settingsHasTheWayToTheWebsite() throws Exception {
    start();
    panel("settings").open();
    idle(400);
    tap("MAXAPACITY website");
    android.content.Intent opened;
    do {
      opened = Shadows.shadowOf(a).getNextStartedActivity();
    } while (opened != null && !android.content.Intent.ACTION_VIEW.equals(opened.getAction()));
    org.junit.Assert.assertNotNull("the browser is asked for the site", opened);
    org.junit.Assert.assertEquals("https://maxapacitybikes.com", opened.getDataString());
  }

  /**
   * A Deck found while the welcome has the screen is not lost under it: it is offered once the welcome is
   * done. The Deck here answers at once, so it is found while the home screen is still starting: the
   * welcome has to come up all the same, and be the sheet that Back and the buttons act on.
   */
  @Test public void aDeckThatTurnsUpDuringTheWelcomeIsOfferedAfterIt() throws Exception {
    fakeDeck = new FakeDeck(true);
    fakeDeck.code = "424242";
    Deck.usb = "127.0.0.1:1";           // no cable
    FakeDeck.onWifi(c);
    Units.prefs(c).edit().putString("deck_host", fakeDeck.address()).apply();
    asANewRider();
    start();
    await("the Deck to be found", () -> Deck.get(c).state() == Deck.FOUND);
    org.junit.Assert.assertNotNull("the welcome still has the screen",
        find(Sheet.current(), android.widget.TextView.class, "Welcome to MAXAPACITY Ride"));
    org.junit.Assert.assertNull(onScreen("Your Deck is on this network"));
    org.junit.Assert.assertEquals("not yet counted as offered", "", Units.prefs(c).getString("deck_offered", ""));
    tap("Not now");
    org.junit.Assert.assertNull("the welcome has gone", onScreen("Welcome to MAXAPACITY Ride"));
    org.junit.Assert.assertNotNull("offered once the welcome has gone", onScreen("Your Deck is on this network"));
    org.junit.Assert.assertEquals(Deck.get(c).address(), Units.prefs(c).getString("deck_offered", ""));
  }

  /** Which screen, if any, is on top. */
  String openScreen() throws Exception {
    String open = null;
    for (String n : new String[] {"drawer", "rides", "copilot", "bike", "tune", "deck", "fullDeck", "settings"}) {
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
        {"bike", "bike"}, {"tune", "tune"}, {"deck", "deck"}, {"fulldeck", "fullDeck"}, {"settings", "settings"}};
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

  /** Settings ends with the way to the app's own page on the site, opened in the phone's browser. */
  @Test public void settingsOpensTheAppsPageOnTheSite() throws Exception {
    start();
    a.onNewIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN).putExtra("open", "settings"));
    idle(400);
    org.junit.Assert.assertEquals("settings", openScreen());
    // Anything started on the way here is not what this tap started.
    while (Shadows.shadowOf(a).getNextStartedActivity() != null) { }
    tap("About and help");
    android.content.Intent started = Shadows.shadowOf(a).getNextStartedActivity();
    org.junit.Assert.assertNotNull("a page was opened", started);
    org.junit.Assert.assertEquals(android.content.Intent.ACTION_VIEW, started.getAction());
    org.junit.Assert.assertEquals("https://maxapacitybikes.com/apps/ride", started.getDataString());
    org.junit.Assert.assertEquals("the screen stays where it was", "settings", openScreen());
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
