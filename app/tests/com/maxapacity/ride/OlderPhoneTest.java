package com.maxapacity.ride;

import static org.junit.Assert.*;

import android.content.Context;
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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The app on Android 13, which has no Health Connect built in. The app runs
 * on Android 12 and later, and its Health Connect code names parts of
 * Android that only exist from 14: on an older phone none of that may be
 * touched, or the home screen would not start at all. Every other test runs
 * as Android 15, where that could never show.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w393dp-h851dp-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OlderPhoneTest {

  Context c;
  ActivityController<RideActivity> controller;

  @Before public void setUp() {
    c = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
        android.Manifest.permission.ACCESS_FINE_LOCATION);
    // As a rider who has already answered the welcome, so the home screen starts bare.
    Units.prefs(c).edit().clear().putBoolean("auto_update", false).putBoolean(Tour.OFFERED, true).apply();
    Strava.setBuiltInApp(null, null);
    File[] rides = RideStore.dir(c).listFiles();
    if (rides != null) for (File f : rides) f.delete();
  }

  @After public void tearDown() {
    if (controller != null) controller.pause().stop().destroy();
  }

  @Test public void theAppRunsAndHealthConnectIsNotOffered() throws Exception {
    // A saved ride, and the setting left on as if by a backup restored from a newer phone.
    File dir = RideStore.dir(c);
    dir.mkdirs();
    long start = System.currentTimeMillis() - 86400000L;
    List<RideState.Point> track = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      android.location.Location l = ScreensTest.fix(40.56 + i * 0.0002, -111.63, 9f);
      l.setTime(start + i * 1000L);
      track.add(new RideState.Point(l));
    }
    File gpx = GpxWriter.write(dir, start, track);
    JSONArray rides = new JSONArray().put(new JSONObject().put("start", start).put("file", gpx.getName())
        .put("distanceM", 9650.0).put("movingMs", 55000L).put("elapsedMs", 59000L).put("topMps", 15.1)
        .put("climbM", 96.0).put("stops", 2).put("strava", ""));
    try (FileOutputStream out = new FileOutputStream(new File(dir, "index.json"))) {
      out.write(rides.toString().getBytes(StandardCharsets.UTF_8));
    }
    Units.prefs(c).edit().putBoolean("health", true).apply();

    controller = Robolectric.buildActivity(RideActivity.class);
    RideActivity a = controller.setup().get();     // the home screen starts, and looks for rides to share
    ScreensTest.idle(400);
    assertFalse(Health.available(c));
    assertFalse(Health.isOn(c));
    Health.sync(c);
    ScreensTest.idle(400);
    assertEquals("", RideStore.load(c).getJSONObject(0).optString("health"));

    java.lang.reflect.Field settings = RideActivity.class.getDeclaredField("settings");
    settings.setAccessible(true);
    ((Panel) settings.get(a)).open();
    ScreensTest.idle(400);
    assertNotNull(ScreensTest.find(a.getWindow().getDecorView(), TextView.class, "Strava"));
    assertNull(ScreensTest.find(a.getWindow().getDecorView(), TextView.class, "Health Connect"));

    a.showRide(RideStore.load(c).getJSONObject(0));
    ScreensTest.idle(400);
    assertNotNull(ScreensTest.find(a.getWindow().getDecorView(), TextView.class, "Send to Strava"));
    assertNull(ScreensTest.find(a.getWindow().getDecorView(), TextView.class, "Health Connect"));
  }
}
