package com.maxapacity.ride;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.health.connect.datatypes.DistanceRecord;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.ExerciseSessionType;
import android.health.connect.datatypes.Record;
import android.location.Location;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Hooking the app up to other services, as a rider does it: Strava with one
 * tap when the build carries the app's own Strava application, and Health
 * Connect with the phone's one permission screen. Strava is a stand-in on
 * this machine that answers the way the real one does for sign-in and
 * uploads; Health Connect's records are made by Android's own code, so a
 * ride it would refuse fails here, and only the store they go into is ours.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w393dp-h851dp-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ConnectTest {

  @org.junit.Rule public org.junit.rules.TestName test = new org.junit.rules.TestName();
  int pictures;

  Context c;
  ActivityController<RideActivity> controller;
  RideActivity a;
  FakeStrava strava;
  FakeHealth health;

  @Before public void setUp() throws Exception {
    c = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
        android.Manifest.permission.ACCESS_FINE_LOCATION);
    // As a rider who has already answered the welcome, so the home screen starts bare.
    Units.prefs(c).edit().clear().putBoolean("auto_update", false).putBoolean(Tour.OFFERED, true).apply();
    File[] rides = RideStore.dir(c).listFiles();
    if (rides != null) for (File f : rides) f.delete();
    strava = new FakeStrava();
    Strava.tokenUrl = strava.url("/oauth/token");
    Strava.uploadUrl = strava.url("/api/v3/uploads");
    Strava.setBuiltInApp("4242", "the-apps-secret");
    health = new FakeHealth();
    Health.store = health;
  }

  @After public void tearDown() {
    if (controller != null) controller.pause().stop().destroy();
    strava.stop();
    Strava.tokenUrl = "https://www.strava.com/oauth/token";
    Strava.uploadUrl = "https://www.strava.com/api/v3/uploads";
    Strava.setBuiltInApp(null, null);
    if (a != null) Strava.clearScreen(a);
    Health.store = Health.SYSTEM;
    Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Health.PERMISSIONS);
  }

  // ---- Helpers ---------------------------------------------------------------

  void start() {
    controller = Robolectric.buildActivity(RideActivity.class);
    a = controller.setup().get();
    ScreensTest.idle(400);
  }

  static void until(String what, BooleanSupplier ok) throws Exception {
    for (int i = 0; i < 600 && !ok.getAsBoolean(); i++) {
      Thread.sleep(20);
      ScreensTest.idle(100);
    }
    assertTrue("timed out waiting for " + what, ok.getAsBoolean());
  }

  /**
   * Lays the screen out and draws it at a small phone, with the keyboard up,
   * and at full phone size, keeping that last one as a picture beside the
   * ones ScreensTest keeps.
   */
  void draw(String what) {
    float d = a.getResources().getDisplayMetrics().density;
    View v = a.getWindow().getDecorView();
    int[][] sizes = {{320, 560}, {393, 400}, {393, 851}};
    for (int[] size : sizes) {
      int w = Math.round(size[0] * d);
      int h = Math.round(size[1] * d);
      try {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        ScreensTest.idle(200);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        if (size == sizes[sizes.length - 1]) keep(b, what);
        b.recycle();
      } catch (RuntimeException e) {
        throw new AssertionError(what + " at " + size[0] + "x" + size[1] + "dp: " + e, e);
      }
    }
  }

  /** build/screens/<test>/<nn>-<what>.png. A convenience: failing to write one fails nothing. */
  void keep(Bitmap b, String what) {
    try {
      File dir = new File(ScreensTest.PICTURES, test.getMethodName());
      dir.mkdirs();
      String name = String.format(Locale.US, "%02d-%s.png", ++pictures,
          what.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", ""));
      try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
    } catch (Exception e) {
      // The drawing is the test; the picture is for people.
    }
  }

  /** Taps whatever carries these words: on the sheet if one is up, else on the screen. */
  void tap(String words) {
    tap(Sheet.current() != null ? Sheet.current() : a.getWindow().getDecorView(), words);
  }

  void tap(View root, String words) {
    View v = ScreensTest.find(root, TextView.class, words);
    assertNotNull("on screen: " + words, v);
    while (v != null && !v.hasOnClickListeners()) v = v.getParent() instanceof View ? (View) v.getParent() : null;
    assertNotNull("tappable: " + words, v);
    v.performClick();
    ScreensTest.idle(400);
  }

  boolean shows(String words) {
    View root = Sheet.current() != null ? Sheet.current() : a.getWindow().getDecorView();
    return ScreensTest.find(root, TextView.class, words) != null;
  }

  Panel panel(String name) throws Exception {
    java.lang.reflect.Field f = RideActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return (Panel) f.get(a);
  }

  /** The page the app just sent the rider to, or null if it sent them nowhere. */
  Uri sentTo() {
    Intent started;
    Uri page = null;
    while ((started = Shadows.shadowOf(a).getNextStartedActivity()) != null) {
      if (Intent.ACTION_VIEW.equals(started.getAction())) page = started.getData();
    }
    return page;
  }

  /** Strava sending the rider back, as it does once they tap Authorize. */
  void stravaSendsThemBack(String query) {
    Robolectric.buildActivity(StravaActivity.class,
        new Intent(Intent.ACTION_VIEW, Uri.parse(Strava.REDIRECT + "?" + query))).create();
    ScreensTest.idle(200);
  }

  /** Saved rides, a day apart, none of them offered to Strava or Health Connect yet. */
  List<String> seedRides(int count) throws Exception {
    File dir = RideStore.dir(c);
    dir.mkdirs();
    JSONArray rides = new JSONArray();
    List<String> files = new ArrayList<>();
    for (int r = 0; r < count; r++) {
      long start = System.currentTimeMillis() - (r + 1) * 86400000L - r * 61000L;
      List<RideState.Point> track = new ArrayList<>();
      for (int i = 0; i < 120; i++) {
        Location l = ScreensTest.fix(40.56 + i * 0.0002, -111.63 + Math.sin(i / 9.0) * 0.002, 9f);
        l.setTime(start + i * 1000L);     // one fix a second, as a phone gives them
        track.add(new RideState.Point(l));
      }
      File gpx = GpxWriter.write(dir, start, track);
      files.add(gpx.getName());
      rides.put(new JSONObject().put("start", start).put("file", gpx.getName())
          .put("distanceM", 9650.0 + 4100 * r).put("movingMs", 110000L)
          .put("elapsedMs", 119000L).put("topMps", 15.1 + r)
          .put("climbM", 96.0).put("stops", 2).put("leanLeft", 31).put("leanRight", 27).put("strava", ""));
    }
    try (FileOutputStream out = new FileOutputStream(new File(dir, "index.json"))) {
      out.write(rides.toString().getBytes(StandardCharsets.UTF_8));
    }
    return files;
  }

  String mark(String file, String key) {
    JSONArray rides = RideStore.load(c);
    for (int i = 0; i < rides.length(); i++) {
      JSONObject r = rides.optJSONObject(i);
      if (file.equals(r.optString("file"))) return r.optString(key);
    }
    throw new AssertionError("no ride " + file);
  }

  int marked(String key, String value) {
    int n = 0;
    JSONArray rides = RideStore.load(c);
    for (int i = 0; i < rides.length(); i++) if (value.equals(rides.optJSONObject(i).optString(key))) n++;
    return n;
  }

  // ---- Strava ----------------------------------------------------------------

  /** Settings, Connect, Authorize: no keys, nothing typed, and the earlier rides are offered. */
  @Test public void withTheAppsOwnStravaApplicationConnectingIsOneTap() throws Exception {
    seedRides(3);
    start();
    panel("settings").open();
    ScreensTest.idle(400);
    tap(panel("settings"), "Connect");
    assertNull("nothing is asked of the rider", Sheet.current());
    Uri page = sentTo();
    assertNotNull("the rider is sent to Strava", page);
    assertEquals("www.strava.com", page.getHost());
    assertEquals("/oauth/mobile/authorize", page.getPath());
    assertEquals("4242", page.getQueryParameter("client_id"));
    assertEquals(Strava.REDIRECT, page.getQueryParameter("redirect_uri"));
    assertEquals("activity:write", page.getQueryParameter("scope"));
    String state = page.getQueryParameter("state");
    assertTrue("a sign-in carries its own mark", state != null && state.length() >= 32);
    assertFalse(Strava.isConnected(c));

    stravaSendsThemBack("state=" + state + "&code=one-time-code&scope=read,activity:write");
    until("the sign-in to finish", () -> Strava.isConnected(c));
    assertEquals("4242", strava.tokenRequests.get(0).get("client_id"));
    assertEquals("the-apps-secret", strava.tokenRequests.get(0).get("client_secret"));
    assertEquals("one-time-code", strava.tokenRequests.get(0).get("code"));
    assertEquals("authorization_code", strava.tokenRequests.get(0).get("grant_type"));
    Intent back = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
    assertNotNull("the rider is put back in the app", back);
    assertEquals(RideActivity.class.getName(), back.getComponent().getClassName());
    assertEquals("settings", back.getStringExtra("open"));

    until("the earlier rides to be offered", () -> Sheet.current() != null);
    assertTrue(shows("Strava connected"));
    assertEquals("nothing goes up unasked", 0, strava.uploads.size());
    draw("strava connected, earlier rides offered");
    tap("Send them");
    until("three uploads", () -> marked("strava", "uploaded") == 3);
    assertEquals(3, strava.uploads.size());
    for (String auth : strava.uploadAuth) assertEquals("Bearer access-1", auth);
    assertTrue(shows("Connected"));
    draw("settings, strava connected");
  }

  /** Send to Strava on a ride, before ever connecting: that ride goes up, the others are offered. */
  @Test public void aRideSentBeforeConnectingGoesUpOnceConnected() throws Exception {
    List<String> files = seedRides(3);
    start();
    a.showRide(RideStore.load(c).getJSONObject(0));
    ScreensTest.idle(400);
    draw("ride detail, strava not connected");
    tap("Send to Strava");
    Uri page = sentTo();
    assertNotNull("the rider is sent to Strava", page);
    stravaSendsThemBack("state=" + page.getQueryParameter("state") + "&code=c&scope=read,activity:write");
    until("the ride to go up", () -> "uploaded".equals(mark(files.get(0), "strava")));
    assertEquals(1, strava.uploads.size());
    assertTrue(strava.uploads.get(0).contains("filename=\"" + files.get(0) + "\""));
    assertTrue("it goes up as an e-bike ride", strava.uploads.get(0).contains("EBikeRide"));
    assertEquals("", mark(files.get(1), "strava"));
    until("the other rides to be offered", () -> Sheet.current() != null);
    tap("Not now");
    ScreensTest.idle(400);
    assertEquals("declined, they stay on the phone", 1, strava.uploads.size());
    assertEquals(2, marked("strava", ""));
  }

  /** Only the sign-in this phone started can finish, and only with leave to upload. */
  @Test public void aSignInThatDidNotStartHereOrCannotUploadIsRefused() throws Exception {
    start();
    String[] said = new String[1];
    // Out of the blue: any app or web page can open the app's sign-in address.
    Strava.finish(c, Uri.parse(Strava.REDIRECT + "?code=someone-elses&scope=read,activity:write"),
        (ok, message) -> said[0] = message);
    assertTrue(said[0], said[0].startsWith("That Strava sign-in did not start on this phone"));

    a.connectStrava(null);
    String state = sentTo().getQueryParameter("state");
    Strava.finish(c, Uri.parse(Strava.REDIRECT + "?state=not-" + state + "&code=x&scope=read,activity:write"),
        (ok, message) -> said[0] = message);
    assertTrue(said[0], said[0].startsWith("That Strava sign-in did not start on this phone"));
    // A stray answer does not spoil the real one: here the rider pressed Cancel on Strava's page.
    Strava.finish(c, Uri.parse(Strava.REDIRECT + "?state=" + state + "&error=access_denied"),
        (ok, message) -> said[0] = message);
    assertEquals("Strava sign-in was cancelled", said[0]);
    // And a mark is good once.
    Strava.finish(c, Uri.parse(Strava.REDIRECT + "?state=" + state + "&code=x&scope=read,activity:write"),
        (ok, message) -> said[0] = message);
    assertTrue(said[0], said[0].startsWith("That Strava sign-in did not start on this phone"));

    a.connectStrava(null);
    state = sentTo().getQueryParameter("state");
    Strava.finish(c, Uri.parse(Strava.REDIRECT + "?state=" + state + "&code=x&scope=read"),
        (ok, message) -> said[0] = message);
    assertTrue(said[0], said[0].startsWith("Strava was not allowed to take uploads"));

    ScreensTest.idle(400);
    assertFalse(Strava.isConnected(c));
    assertEquals("Strava was never asked for tokens", 0, strava.tokenRequests.size());
    draw("a refused sign-in said on the screen");
  }

  /** A build with no application of its own asks for the rider's, and a phone set up that way keeps it. */
  @Test public void withoutOneTheRidersOwnApplicationIsAskedForAndKept() throws Exception {
    Strava.setBuiltInApp(null, null);
    List<String> files = seedRides(1);
    start();
    a.connectStrava(null);
    ScreensTest.idle(400);
    assertNotNull("the rider is asked for their application", Sheet.current());
    EditText id = ScreensTest.find(Sheet.current(), EditText.class, null);
    id.setText("777");
    for (View v : each(Sheet.current(), EditText.class)) if (v != id) ((EditText) v).setText("riders-own-secret");
    tap("Connect");
    Uri page = sentTo();
    assertEquals("777", page.getQueryParameter("client_id"));
    Strava.finish(c, Uri.parse(Strava.REDIRECT + "?state=" + page.getQueryParameter("state")
        + "&code=c&scope=read,activity:write"), null);
    until("the sign-in to finish", () -> Strava.isConnected(c));
    assertEquals("777", strava.tokenRequests.get(0).get("client_id"));
    assertEquals("riders-own-secret", strava.tokenRequests.get(0).get("client_secret"));

    // An update arrives that carries the app's own application. Tokens only
    // work with the application that issued them, so this phone stays on its own.
    Strava.setBuiltInApp("4242", "the-apps-secret");
    Units.prefs(c).edit().putLong("strava_expires", 0).apply();
    Strava.queue(c, files.get(0));
    until("the ride to go up", () -> "uploaded".equals(mark(files.get(0), "strava")));
    Map<String, String> refresh = strava.tokenRequests.get(strava.tokenRequests.size() - 1);
    assertEquals("refresh_token", refresh.get("grant_type"));
    assertEquals("777", refresh.get("client_id"));
    assertEquals("riders-own-secret", refresh.get("client_secret"));
  }

  static <T extends View> List<T> each(View root, Class<T> kind) {
    List<T> out = new ArrayList<>();
    if (kind.isInstance(root)) out.add(kind.cast(root));
    if (root instanceof android.view.ViewGroup) {
      android.view.ViewGroup g = (android.view.ViewGroup) root;
      for (int i = 0; i < g.getChildCount(); i++) out.addAll(each(g.getChildAt(i), kind));
    }
    return out;
  }

  // ---- Health Connect --------------------------------------------------------

  /** The phone answering its own Health Connect permission screen. */
  void thePhoneAnswers(String... granted) {
    Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(granted);
    int[] results = new int[Health.PERMISSIONS.length];
    for (int i = 0; i < results.length; i++) {
      results[i] = c.checkSelfPermission(Health.PERMISSIONS[i]);
    }
    a.onRequestPermissionsResult(RideActivity.REQ_HEALTH, Health.PERMISSIONS, results);
    ScreensTest.idle(400);
  }

  /** One tap and the phone's own screen: every ride goes in once, with its route and distance. */
  @Test public void healthConnectTakesEveryRideOnceWithItsRouteAndDistance() throws Exception {
    List<String> files = seedRides(3);
    start();
    panel("settings").open();
    ScreensTest.idle(400);
    assertTrue("offered where the phone has it", shows("Health Connect"));
    draw("settings, health connect offered");
    assertFalse(Health.isOn(c));
    tap(panel("settings"), "Turn on");
    assertEquals("nothing is written before the phone says yes", 0, health.written.size());
    thePhoneAnswers(Health.PERMISSIONS);
    assertTrue(Health.isOn(c));
    until("three rides to be written", () -> marked("health", Health.SENT) == 3);
    assertEquals(3, health.written.size());

    JSONObject ride = RideStore.load(c).getJSONObject(0);
    List<Record> records = health.written.get(files.get(0));
    assertEquals("a workout and its distance", 2, records.size());
    ExerciseSessionRecord session = (ExerciseSessionRecord) records.get(0);
    assertEquals(ExerciseSessionType.EXERCISE_SESSION_TYPE_BIKING, session.getExerciseType());
    assertEquals(files.get(0), session.getMetadata().getClientRecordId());
    // To the second: a saved track keeps whole seconds, and the workout starts with its route.
    assertTrue(Math.abs(ride.getLong("start") - session.getStartTime().toEpochMilli()) < 1000);
    assertEquals(120, session.getRoute().getRouteLocations().size());
    assertEquals(40.56, session.getRoute().getRouteLocations().get(0).getLatitude(), 1e-6);
    assertEquals(1900, session.getRoute().getRouteLocations().get(0).getAltitude().getInMeters(), 0.1);
    assertFalse("the route sits inside the workout", session.getRoute().getRouteLocations().get(119)
        .getTime().isAfter(session.getEndTime()));
    DistanceRecord distance = (DistanceRecord) records.get(1);
    assertEquals(9650.0, distance.getDistance().getInMeters(), 0.01);
    assertEquals(session.getStartTime(), distance.getStartTime());

    // Asked again, nothing is written twice.
    Health.sync(c);
    Health.sync(c);
    Thread.sleep(100);
    ScreensTest.idle(400);
    assertEquals(3, health.inserts);
    assertTrue(shows("Rides are shared with your health apps"));
    draw("settings, health connect on");

    a.showRide(RideStore.load(c).getJSONObject(0));
    ScreensTest.idle(400);
    assertTrue(shows("Added"));
    draw("ride detail, in health connect");
    panel("rideDetail").close();
    ScreensTest.idle(400);

    // Turned off: the next ride stays on the phone.
    tap(panel("settings"), "Health Connect");
    assertNotNull(Sheet.current());
    draw("stop sharing sheet");
    tap("Stop");
    assertFalse(Health.isOn(c));
    RideStore.setHealth(c, files.get(0), "");
    Health.sync(c);
    Thread.sleep(100);
    ScreensTest.idle(400);
    assertEquals("nothing more is written", 3, health.inserts);
  }

  /** A ride saved with sharing on goes in as it is saved. */
  @Test public void aNewRideGoesToHealthConnectAsItIsSaved() throws Exception {
    start();
    a.connectHealth();
    thePhoneAnswers(Health.PERMISSIONS);
    assertTrue(Health.isOn(c));
    RideState.get().start();
    for (int i = 0; i < 30; i++) {
      Location l = ScreensTest.fix(40.56 + i * 0.0003, -111.63, 12f);
      l.setTime(System.currentTimeMillis() - (30 - i) * 1000L);
      RideState.get().onLocation(l);
    }
    String said = RideStore.finish(c);
    assertTrue(said, said.startsWith("Ride saved"));
    until("the ride to be written", () -> marked("health", Health.SENT) == 1);
    assertEquals(1, health.written.size());
  }

  /** The rider may allow the workout but not where it went: it goes in without a route. */
  @Test public void withoutLeaveForTheRouteOnlyTheWorkoutIsWritten() throws Exception {
    List<String> files = seedRides(1);
    start();
    a.connectHealth();
    thePhoneAnswers(Health.WRITE_EXERCISE);
    assertTrue(Health.isOn(c));
    until("the ride to be written", () -> marked("health", Health.SENT) == 1);
    List<Record> records = health.written.get(files.get(0));
    assertEquals("no distance without leave for it", 1, records.size());
    assertNull("no route without leave for it", ((ExerciseSessionRecord) records.get(0)).getRoute());
  }

  /** Refused, it stays off and says where to allow it. A ride Health Connect cannot take is not retried. */
  @Test public void refusedItStaysOffAndARideItCannotTakeIsSetAside() throws Exception {
    List<String> files = seedRides(2);
    start();
    a.connectHealth();
    thePhoneAnswers();
    assertFalse(Health.isOn(c));
    assertTrue(shows("Health Connect was not allowed"));
    draw("health connect refused");
    assertEquals(0, health.inserts);

    // Health Connect holds no single distance over 1,000 km: a ride that claims one is refused whole.
    JSONArray rides = RideStore.load(c);
    rides.getJSONObject(0).put("distanceM", 2.0e6);
    try (FileOutputStream out = new FileOutputStream(new File(RideStore.dir(c), "index.json"))) {
      out.write(rides.toString().getBytes(StandardCharsets.UTF_8));
    }
    a.connectHealth();
    thePhoneAnswers(Health.PERMISSIONS);
    until("the good ride to be written", () -> marked("health", Health.SENT) == 1);
    until("the other to be set aside", () -> marked("health", Health.REFUSED) == 1);
    assertEquals(Health.REFUSED, mark(files.get(0), "health"));
    Health.sync(c);
    Thread.sleep(100);
    ScreensTest.idle(400);
    assertEquals("and it is not tried again", 1, health.inserts);
  }

  /** A write that fails (Health Connect busy, say) is tried again later. */
  @Test public void aWriteThatFailsIsTriedAgain() throws Exception {
    seedRides(1);
    start();
    health.failing = true;
    a.connectHealth();
    thePhoneAnswers(Health.PERMISSIONS);
    until("the first try", () -> health.inserts == 1);
    ScreensTest.idle(200);
    assertEquals(0, marked("health", Health.SENT));
    health.failing = false;
    Health.sync(c);
    until("the second try", () -> marked("health", Health.SENT) == 1);
    assertEquals(2, health.inserts);
  }

  /** On a phone without Health Connect built in, the setting is not there at all. */
  @Test public void whereThePhoneHasNoHealthConnectItIsNotOffered() throws Exception {
    health.present = false;
    seedRides(1);
    start();
    panel("settings").open();
    ScreensTest.idle(400);
    assertFalse(shows("Health Connect"));
    Health.sync(c);
    ScreensTest.idle(400);
    assertEquals(0, health.inserts);
  }

  /** The page Android shows from its permission screen's privacy link. */
  @Test public void thePageSayingWhatIsSharedOpensAndDraws() {
    ActivityController<HealthActivity> page = Robolectric.buildActivity(HealthActivity.class).setup();
    View v = page.get().getWindow().getDecorView();
    assertNotNull(ScreensTest.find(v, TextView.class, "Rides and Health Connect"));
    for (int[] size : new int[][] {{1080, 2340}, {720, 600}}) {
      v.measure(View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
      v.layout(0, 0, size[0], size[1]);
      Bitmap b = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
      v.draw(new Canvas(b));
      b.recycle();
    }
    page.pause().stop().destroy();
    // The app's manifest must name it for Android to offer the permissions at all.
    Intent privacy = new Intent("android.intent.action.VIEW_PERMISSION_USAGE")
        .addCategory("android.intent.category.HEALTH_PERMISSIONS").setPackage(c.getPackageName());
    assertFalse("declared in the manifest", c.getPackageManager()
        .queryIntentActivities(privacy, 0).isEmpty());
  }

  // ---- Stand-ins -------------------------------------------------------------

  /** Health Connect's store: keeps what it is given, by the ride's file name. */
  static final class FakeHealth implements Health.Store {
    final Map<String, List<Record>> written = Collections.synchronizedMap(new LinkedHashMap<>());
    volatile boolean present = true;
    volatile boolean failing;
    volatile int inserts;

    @Override public boolean present(Context c) {
      return present;
    }

    @Override public void insert(Context c, List<Record> records, Health.Done done) {
      inserts++;
      if (!failing) written.put(records.get(0).getMetadata().getClientRecordId(), records);
      done.onResult(!failing);
    }
  }

  /**
   * Strava's sign-in and upload addresses on this machine: tokens for a
   * one-time code or a refresh token, and 201 for an upload that carries the
   * token it handed out. A few lines of HTTP over a plain socket.
   */
  static final class FakeStrava {
    private final ServerSocket socket;
    private volatile boolean running = true;
    /** The form fields of each request for tokens, in order. */
    final List<Map<String, String>> tokenRequests = Collections.synchronizedList(new ArrayList<>());
    /** Each upload's body, and the Authorization header it came with. */
    final List<String> uploads = Collections.synchronizedList(new ArrayList<>());
    final List<String> uploadAuth = Collections.synchronizedList(new ArrayList<>());

    FakeStrava() throws IOException {
      socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
      Thread accept = new Thread(() -> {
        while (running) {
          try {
            final Socket s = socket.accept();
            Thread t = new Thread(() -> serve(s));
            t.setDaemon(true);
            t.start();
          } catch (IOException e) {
            return;
          }
        }
      });
      accept.setDaemon(true);
      accept.start();
    }

    String url(String path) {
      return "http://127.0.0.1:" + socket.getLocalPort() + path;
    }

    void stop() {
      running = false;
      try {
        socket.close();
      } catch (IOException e) {
        // Already closed.
      }
    }

    private void serve(Socket s) {
      try (Socket sock = s) {
        InputStream in = new BufferedInputStream(sock.getInputStream());
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
          head.write(b);
          byte[] h = head.toByteArray();
          int n = h.length;
          if (n >= 4 && h[n - 4] == 13 && h[n - 3] == 10 && h[n - 2] == 13 && h[n - 1] == 10) break;
        }
        String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) return;
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
          int colon = lines[i].indexOf(':');
          if (colon > 0) {
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US), lines[i].substring(colon + 1).trim());
          }
        }
        String announced = headers.get("content-length");
        String body = new String(announced == null ? new byte[0] : in.readNBytes(Integer.parseInt(announced)),
            StandardCharsets.UTF_8);
        int status = 404;
        String answer = "{}";
        if (first[1].equals("/oauth/token")) {
          Map<String, String> form = new LinkedHashMap<>();
          for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) form.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
          }
          tokenRequests.add(form);
          int n = tokenRequests.size();
          status = 200;
          answer = "{\"token_type\":\"Bearer\",\"access_token\":\"access-" + n + "\",\"refresh_token\":\"refresh-" + n
              + "\",\"expires_at\":" + (System.currentTimeMillis() / 1000 + 21600) + "}";
        } else if (first[1].equals("/api/v3/uploads")) {
          String auth = headers.get("authorization");
          if (auth == null || !auth.equals("Bearer access-" + tokenRequests.size())) {
            status = 401;
            answer = "{\"message\":\"Authorization Error\"}";
          } else {
            uploads.add(body);
            uploadAuth.add(auth);
            status = 201;
            answer = "{\"id\":" + uploads.size() + ",\"status\":\"Your activity is still being processed.\"}";
          }
        }
        byte[] out = answer.getBytes(StandardCharsets.UTF_8);
        OutputStream o = sock.getOutputStream();
        o.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: "
            + out.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        o.write(out);
        o.flush();
      } catch (IOException e) {
        // The app hung up.
      }
    }
  }
}
