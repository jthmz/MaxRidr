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
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
  /** Other things on the network that pick up but are not a Deck. */
  final List<ServerSocket> others = new ArrayList<>();

  @Before public void setUp() {
    c = RuntimeEnvironment.getApplication();
    Units.prefs(c).edit().clear().apply();
    File[] rides = RideStore.dir(c).listFiles();
    if (rides != null) for (File f : rides) f.delete();
  }

  @After public void tearDown() throws IOException {
    if (fake != null) fake.stop();
    for (ServerSocket s : others) s.close();
    Deck.usb = DeckLink.USB;
    Deck.neighbours = null;
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
          .put("battery", 64).put("strava", "uploaded"));
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
      assertEquals("the battery as the ride ended", 64, numbers.getInt("battery"));
      assertEquals("the units the rider reads", Units.metric(c) ? "km" : "mi", numbers.getString("units"));
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
    final String key = d.key();
    d.forget();
    until("the Deck to want its code again", () -> d.state() == Deck.FOUND);
    assertEquals("", d.requestKey());
    // And taken back while the Deck still knows this phone: linked again, no new code.
    d.remember(key);
    until("the forget to be undone", () -> d.state() == Deck.LINKED);
    assertEquals(fake.key, d.requestKey());
  }

  @Test public void aDeckTheNetworkHidesIsFoundByAskingTheNetwork() throws Exception {
    fake = new FakeDeck(true);
    fake.code = "424242";
    Deck.usb = "127.0.0.1:1";           // no cable
    FakeDeck.onWifi(c);
    final int[] asked = {0};
    Deck.neighbours = () -> {
      asked[0]++;
      return java.util.Arrays.asList("127.0.0.1:2", fake.address(), "127.0.0.1:3");
    };
    final Deck d = Deck.start(c);
    d.refresh();
    until("the first look to find nothing", () -> d.state() == Deck.NOT_FOUND);
    assertEquals("the network is not asked until someone wants the Deck", 0, asked[0]);

    d.search(true);
    assertTrue(d.searching());
    until("the search to find it", () -> d.state() == Deck.FOUND);
    assertFalse(d.searching());
    assertEquals(fake.address(), d.address());
    assertEquals("Found a Deck at " + fake.address(), d.searchNote());
    assertEquals(1, asked[0]);

    final Object[] right = {null};
    d.pair("424242", (ok, message) -> right[0] = ok);
    until("pairing", () -> right[0] != null && d.state() == Deck.LINKED);

    String report = d.report();
    assertTrue(report, report.contains("Link: linked at " + fake.address()));
    assertTrue(report, report.contains("Searching 3 addresses on this network"));
    assertTrue(report, report.contains("Paired key on this phone: yes"));
    assertFalse("the key itself is never in the report", report.contains(fake.key));
  }

  @Test public void aDeckThatMovedIsFoundAgainByItselfButOnlyOncePerNetwork() throws Exception {
    fake = new FakeDeck(true);
    fake.code = "424242";
    Deck.usb = "127.0.0.1:1";
    FakeDeck.onWifi(c);
    final Deck d = Deck.start(c);
    final Object[] looked = {null};
    d.useAddress(fake.address(), (ok, message) -> looked[0] = ok);
    until("the Deck to be found", () -> looked[0] != null);
    final Object[] paired = {null};
    d.pair("424242", (ok, message) -> paired[0] = ok);
    until("pairing", () -> paired[0] != null && d.state() == Deck.LINKED);
    final String key = fake.key;

    // The Mac is handed a new address: the old one goes quiet and the same Deck answers elsewhere.
    fake.stop();
    final FakeDeck moved = new FakeDeck(true);
    moved.key = key;
    final int[] asked = {0};
    Deck.neighbours = () -> {
      asked[0]++;
      return java.util.Arrays.asList(moved.address());
    };
    try {
      d.attach(null);                   // the app comes to the front
      until("the Deck to be found where it now is", () -> d.state() == Deck.LINKED && d.address().equals(moved.address()));
      assertEquals(1, asked[0]);
      assertEquals(key, d.requestKey());

      // Away from home, the same care must not turn into knocking on a network at every look.
      moved.stop();
      d.refresh();
      until("the Deck to go out of reach", () -> d.state() == Deck.NOT_FOUND);
      for (int i = 0; i < 5; i++) {
        d.refresh();
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
        Thread.sleep(30);
      }
      until("the looks to settle", () -> d.state() == Deck.NOT_FOUND);
      assertEquals("asked once per network unasked, not at every look", 1, asked[0]);
    } finally {
      moved.stop();
      d.detach(null);
    }
  }

  @Test public void searchingIsSpacedOutUnlessAskedForAndNeverHappensOnMobileData() throws Exception {
    Deck.usb = "127.0.0.1:1";
    final int[] asked = {0};
    Deck.neighbours = () -> {
      asked[0]++;
      return java.util.Arrays.asList("127.0.0.1:2");
    };
    final Deck d = Deck.start(c);
    d.search(true);                     // Robolectric's phone starts on mobile data
    until("the look", () -> d.state() == Deck.NOT_FOUND);
    assertEquals("mobile data never asks a network", 0, asked[0]);
    assertFalse(d.onNetwork());

    FakeDeck.onWifi(c);
    assertTrue(d.onNetwork());
    d.search(false);
    until("the first search", () -> !d.searching() && asked[0] == 1);
    for (int i = 0; i < 4; i++) {
      d.search(false);
      until("an unasked search to be let go", () -> !d.searching());
    }
    assertEquals("unasked, the network is asked once in a while, not every time", 1, asked[0]);
    d.search(true);
    until("the search that was asked for", () -> !d.searching() && asked[0] == 2);
    assertEquals("Asked 1 address on this network. No Deck answered", d.searchNote());
    assertEquals(Deck.NOT_FOUND, d.state());
  }

  @Test public void aLinkCopiedFromTheDeckCarriesItsCode() {
    assertEquals("123456", Deck.codeIn("http://192.168.1.20:8791/#123456"));
    assertEquals("123456", Deck.codeIn(" http://192.168.1.20:8791/#123 456 "));
    assertEquals("", Deck.codeIn("192.168.1.20"));
    assertEquals("", Deck.codeIn("http://192.168.1.20:8791/#12345"));
    assertEquals("", Deck.codeIn(null));
    assertEquals("192.168.1.", Deck.sharedStart("192.168.1.57"));
    assertEquals("", Deck.sharedStart(""));
    assertEquals("", Deck.sharedStart(null));
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

  // ---- Who counts as a Deck ----------------------------------------------------

  /** Something else on the network that picks up: it answers every request with these bytes, or never. */
  String other(final String answer) throws IOException {
    final ServerSocket s = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    others.add(s);
    Thread t = new Thread(() -> {
      while (!s.isClosed()) {
        try {
          final Socket c = s.accept();
          if (answer == null) continue;             // picks up and says nothing, ever
          byte[] body = answer.getBytes(StandardCharsets.UTF_8);
          OutputStream o = c.getOutputStream();
          c.getInputStream().read(new byte[2048]);
          o.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length
              + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
          o.write(body);
          o.flush();
          c.close();
        } catch (IOException e) {
          return;
        }
      }
    });
    t.setDaemon(true);
    t.start();
    return "127.0.0.1:" + s.getLocalPort();
  }

  @Test public void onlyTheDeckIsFoundAmongEverythingElseThatPicksUp() throws Exception {
    fake = new FakeDeck(true);                      // on the network: wants its code
    List<String> ask = new ArrayList<>();
    for (int port = 1; port <= 60; port++) ask.add("127.0.0.1:" + port);   // nobody home
    ask.add(other("{\"hello\":\"I am a printer\"}"));
    ask.add(other("<html>a router's own page</html>"));
    ask.add(fake.address());
    ask.add(other(null));
    ask.add(other("{\"error\":\"not paired\"}"));  // says a Deck's words, but not the way a Deck says them (200, not 401)

    long began = System.currentTimeMillis();
    List<DeckLink.Hit> hits = DeckLink.sweep(ask, DeckLink.SWEEP_CONNECT_MS);
    long took = System.currentTimeMillis() - began;

    assertEquals("exactly the Deck", 1, hits.size());
    assertEquals(fake.address(), hits.get(0).address);
    assertEquals(DeckLink.UNPAIRED, hits.get(0).result);
    assertTrue("something that picks up and says nothing does not hold the search up: " + took + " ms", took < 6000);
  }

  @Test public void aDeckOnTheCableSideAnswersWithoutAKeyAndIsStillFound() throws Exception {
    fake = new FakeDeck(false);                     // trusts whoever reaches it
    List<DeckLink.Hit> hits = DeckLink.sweep(java.util.Arrays.asList("127.0.0.1:1", fake.address()), DeckLink.SWEEP_CONNECT_MS);
    assertEquals(1, hits.size());
    assertEquals(DeckLink.HOME, hits.get(0).result);
  }

  @Test public void noKeyIsEverSentWhileSearching() throws Exception {
    // The stand-in records nothing about headers, so ask a listener that keeps what it was sent.
    final ServerSocket s = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    others.add(s);
    final StringBuilder heard = new StringBuilder();
    Thread t = new Thread(() -> {
      try {
        while (!s.isClosed()) {
          Socket c = s.accept();
          byte[] buf = new byte[4096];
          int n = c.getInputStream().read(buf);
          if (n > 0) synchronized (heard) { heard.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1)); }
          c.getOutputStream().write("HTTP/1.1 401 X\r\nContent-Type: application/json\r\nContent-Length: 22\r\nConnection: close\r\n\r\n{\"error\":\"not paired\"}".getBytes(StandardCharsets.ISO_8859_1));
          c.close();
        }
      } catch (IOException e) {
        // closed
      }
    });
    t.setDaemon(true);
    t.start();
    List<DeckLink.Hit> hits = DeckLink.sweep(java.util.Arrays.asList("127.0.0.1:" + s.getLocalPort()), DeckLink.SWEEP_CONNECT_MS);
    assertEquals(1, hits.size());
    synchronized (heard) {
      assertTrue(heard.toString(), heard.toString().contains("GET /api/state"));
      assertFalse("no key goes to an address that has only just been found", heard.toString().toLowerCase().contains("x-deck-token"));
    }
  }
}
