package com.maxapacity.ride;

import static org.junit.Assert.*;

import android.content.Context;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The link to the Deck, end to end against a stand-in Deck on this machine:
 * the phone is let in over the cable, pairs with the code on the network,
 * sends each ride home once, and waits when home is not in reach.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DeckTest {

  Context c;
  FakeDeck fake;

  @Before public void setUp() {
    c = RuntimeEnvironment.getApplication();
    Units.prefs(c).edit().clear().apply();
    File[] rides = RideStore.dir(c).listFiles();
    if (rides != null) for (File f : rides) f.delete();
  }

  @After public void tearDown() {
    if (fake != null) fake.stop();
    Deck.usb = DeckLink.USB;
  }

  /** Lets the background thread and the main thread's queue both move on. */
  static void until(String what, BooleanSupplier ok) throws Exception {
    for (int i = 0; i < 600 && !ok.getAsBoolean(); i++) {
      Thread.sleep(20);
      ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
    }
    assertTrue("timed out waiting for " + what, ok.getAsBoolean());
  }

  /** Saved rides, the way the recorder leaves them. */
  List<String> seedRides(int n) throws Exception {
    File dir = RideStore.dir(c);
    dir.mkdirs();
    JSONArray rides = new JSONArray();
    List<String> names = new ArrayList<>();
    for (int r = 0; r < n; r++) {
      List<RideState.Point> track = new ArrayList<>();
      for (int i = 0; i < 60; i++) {
        Location l = new Location("gps");
        l.setLatitude(40.56 + i * 0.0002);
        l.setLongitude(-111.63 + r * 0.01);
        l.setTime(1795478000000L + i * 1000L);
        track.add(new RideState.Point(l));
      }
      long start = 1795478000000L - (r + 1) * 86400000L - r * 61000L;
      File gpx = GpxWriter.write(dir, start, track);
      names.add(gpx.getName());
      rides.put(new JSONObject().put("start", start).put("file", gpx.getName())
          .put("distanceM", 9650.0 + 4100 * r).put("movingMs", 1490000L).put("elapsedMs", 1620000L)
          .put("topMps", 15.1).put("climbM", 96.0).put("stops", 2).put("leanLeft", 31).put("leanRight", 27)
          .put("strava", "uploaded"));
    }
    try (FileOutputStream out = new FileOutputStream(new File(dir, "index.json"))) {
      out.write(rides.toString().getBytes(StandardCharsets.UTF_8));
    }
    return names;
  }

  int onMac() {
    JSONArray rides = RideStore.load(c);
    int n = 0;
    for (int i = 0; i < rides.length(); i++) {
      if (RideStore.ON_MAC.equals(rides.optJSONObject(i).optString("deck"))) n++;
    }
    return n;
  }

  /** Runs an offload and waits for its answer: {ok, message}. */
  Object[] offload(Deck d) throws Exception {
    final Object[] answer = {null, null};
    d.offload((ok, message) -> {
      answer[0] = ok;
      answer[1] = message;
    });
    until("the offload to finish", () -> answer[0] != null);
    return answer;
  }

  @Test public void overTheCableNothingIsTypedAndEachRideGoesHomeOnce() throws Exception {
    fake = new FakeDeck(false);
    Deck.usb = fake.address();
    List<String> names = seedRides(3);
    Deck d = Deck.start(c);
    assertFalse(d.everLinked());

    Object[] first = offload(d);
    assertEquals("first offload: " + first[1], true, first[0]);
    assertEquals(Deck.LINKED, d.state());
    assertTrue(d.viaUsb());
    assertTrue(d.everLinked());
    assertEquals("a track and its numbers for each ride", 6, fake.stored.size());
    for (String name : names) {
      byte[] onPhone = Files.readAllBytes(new File(RideStore.dir(c), name).toPath());
      assertArrayEquals(name + " arrived byte for byte", onPhone, fake.stored.get(name));
      JSONObject numbers = new JSONObject(new String(
          fake.stored.get(name.replace(".gpx", ".json")), StandardCharsets.UTF_8));
      assertEquals(name, numbers.getString("file"));
      assertEquals(31, numbers.getInt("leanLeft"));
      assertFalse("how it fared on Strava is the phone's business", numbers.has("strava"));
    }
    for (String arrival : fake.arrivals) assertTrue(arrival, arrival.startsWith("ride from "));
    assertEquals(3, onMac());

    int before = fake.puts;
    Object[] second = offload(d);
    assertEquals(true, second[0]);
    assertEquals("nothing travels twice", before, fake.puts);
  }

  @Test public void onTheNetworkItAsksForTheCodeOnceThenSendsWithItsKey() throws Exception {
    fake = new FakeDeck(true);
    fake.code = "424242";
    Deck.usb = "127.0.0.1:1";           // no cable
    FakeDeck.onWifi(c);
    seedRides(2);
    final Deck d = Deck.start(c);

    final Object[] looked = {null, null};
    d.useAddress("http://" + fake.address() + "/#424242", (ok, message) -> {
      looked[0] = ok;
      looked[1] = message;
    });
    until("the Deck to be found", () -> looked[0] != null);
    assertEquals("found, and nothing to say but ask for the code: " + looked[1], true, looked[0]);
    assertNull(looked[1]);
    assertEquals(Deck.FOUND, d.state());
    assertFalse(d.everLinked());

    final Object[] wrong = {null, null};
    d.pair("000000", (ok, message) -> {
      wrong[0] = ok;
      wrong[1] = message;
    });
    until("the wrong code to be refused", () -> wrong[0] != null);
    assertEquals(false, wrong[0]);
    assertEquals("That is not the code on the Deck", wrong[1]);

    final Object[] right = {null, null};
    d.pair("424242", (ok, message) -> {
      right[0] = ok;
      right[1] = message;
    });
    until("pairing", () -> right[0] != null && d.state() == Deck.LINKED);
    assertEquals(true, right[0]);
    assertEquals(fake.key, d.requestKey());
    assertFalse(d.viaUsb());

    Object[] sent = offload(d);
    assertEquals("offload: " + sent[1], true, sent[0]);
    assertEquals(4, fake.stored.size());
    assertEquals(2, onMac());

    // Forgotten on the phone: the Deck is still there, and wants its code again.
    d.forget();
    until("the Deck to want its code again", () -> d.state() == Deck.FOUND);
    assertEquals("", d.requestKey());
  }

  @Test public void ridesWaitWhenUploadIsOffOrHomeIsOutOfReach() throws Exception {
    fake = new FakeDeck(false);
    fake.uploads = false;
    Deck.usb = fake.address();
    seedRides(2);
    Deck d = Deck.start(c);

    Object[] off = offload(d);
    assertEquals(false, off[0]);
    assertEquals("Ride upload is switched off on the Deck", off[1]);
    assertEquals(0, fake.stored.size());
    assertEquals(0, onMac());

    fake.stop();
    fake = null;
    Object[] away = offload(d);
    assertEquals(false, away[0]);
    assertNull("out of reach is not worth a message", away[1]);
    assertEquals(Deck.NOT_FOUND, d.state());
    assertEquals(0, onMac());
  }

  @Test public void mobileDataNeverReachesForTheDeck() throws Exception {
    fake = new FakeDeck(false);
    Deck.usb = "127.0.0.1:1";
    // Robolectric's phone starts on mobile data: the typed address is never tried.
    Units.prefs(c).edit().putString("deck_host", fake.address()).apply();
    seedRides(1);
    final Deck d = Deck.start(c);
    Object[] answer = offload(d);
    assertEquals(false, answer[0]);
    assertEquals(Deck.NOT_FOUND, d.state());
    assertEquals(0, fake.stored.size());
  }

  @Test public void anAddressTypedAnyWayComesOutTheSame() {
    assertEquals("192.168.1.20:8791", Deck.normalise("192.168.1.20"));
    assertEquals("192.168.1.20:8791", Deck.normalise(" http://192.168.1.20:8791/#123456 "));
    assertEquals("my-mac.local:8791", Deck.normalise("my-mac.local"));
    assertEquals("10.0.0.5:9000", Deck.normalise("10.0.0.5:9000"));
    assertEquals("", Deck.normalise("   "));
    assertEquals("", Deck.normalise(null));
  }
}
