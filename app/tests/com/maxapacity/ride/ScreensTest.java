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
  FakeDeck fakeDeck;

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
    a.editCrashAlert();
    idle(400);
    sweep("crash alert sheet");
    Sheet.dismissCurrent();
    idle(400);
    a.say("A message long enough to wrap onto a second line on a small phone", "UNDO", () -> { });
    idle(400);
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

    Deck.get(c).pair("424242", (ok, message) -> { });
    await("pairing", () -> Deck.get(c).state() == Deck.LINKED);
    await("the rides to go home", () -> fakeDeck.stored.size() == 6);
    for (int i = 0; i < 20; i++) {      // the Deck screen reads the desk off the main thread
      Thread.sleep(30);
      idle(200);
    }
    sweep("deck linked, showing the desk");
    a.editDeckConnection();
    idle(400);
    sweep("deck linked sheet");
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

  /** Taps whatever carries these words: on the sheet if one is up, else on the screen. */
  void tap(String words) {
    View root = Sheet.current() != null ? Sheet.current() : a.getWindow().getDecorView();
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
    org.junit.Assert.assertTrue(brief, brief.contains("- Battery: "));
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

    org.junit.Assert.assertEquals("no setting was written", 0, ctl.settingWriteCount);
    org.junit.Assert.assertEquals(Bike.TUNE_IDLE, bike.tuneState());
    org.junit.Assert.assertArrayEquals("and nothing was saved", saved, ctl.flash);
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
