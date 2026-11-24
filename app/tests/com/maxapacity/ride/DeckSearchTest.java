package com.maxapacity.ride;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Finding a Deck by asking the network, for the many home networks that never
 * pass its announcement on: which addresses get asked, and that only a Deck
 * counts as one. Plain JUnit against stand-ins on this machine.
 */
public class DeckSearchTest {

  FakeDeck fake;
  final List<ServerSocket> others = new ArrayList<>();

  @After public void tearDown() throws IOException {
    if (fake != null) fake.stop();
    for (ServerSocket s : others) s.close();
  }

  // ---- Which addresses are asked ---------------------------------------------

  @Test public void aHomeNetworkIsAskedWholeAndThePhoneItselfIsLeftOut() {
    List<String> ask = DeckLink.neighbours("192.168.1.57", 24, 8791);
    assertEquals(253, ask.size());
    assertEquals("192.168.1.1:8791", ask.get(0));
    assertEquals("192.168.1.254:8791", ask.get(ask.size() - 1));
    assertFalse(ask.contains("192.168.1.57:8791"));
    assertFalse("not the network's own address", ask.contains("192.168.1.0:8791"));
    assertFalse("nor everyone at once", ask.contains("192.168.1.255:8791"));
    assertEquals("each asked once", ask.size(), new HashSet<>(ask).size());
  }

  @Test public void aMeshNetworkOfAThousandIsAskedNearestFirst() {
    List<String> ask = DeckLink.neighbours("192.168.68.57", 22, 8791);
    assertEquals(1021, ask.size());
    for (int i = 0; i < 253; i++) assertTrue(ask.get(i), ask.get(i).startsWith("192.168.68."));
    assertTrue(ask.contains("192.168.71.254:8791"));
    assertFalse(ask.contains("192.168.72.1:8791"));
  }

  @Test public void aVeryLargeNetworkIsAskedOnlyAroundThePhone() {
    List<String> ask = DeckLink.neighbours("10.0.5.9", 16, 8791);
    assertEquals("never more than a thousand or so", 1021, ask.size());
    assertEquals("the phone's own block of 256 first", "10.0.5.0:8791", ask.get(0));
    assertTrue(ask.contains("10.0.4.1:8791"));
    assertTrue(ask.contains("10.0.7.254:8791"));
    assertFalse(ask.contains("10.0.8.1:8791"));
    assertFalse(ask.contains("10.0.3.254:8791"));
  }

  @Test public void onlyAHomeOrOfficeNetworkIsEverAsked() {
    assertTrue("the open internet", DeckLink.neighbours("8.8.8.8", 24, 8791).isEmpty());
    assertTrue("a phone carrier's network", DeckLink.neighbours("100.72.1.5", 24, 8791).isEmpty());
    assertTrue(DeckLink.neighbours("172.32.0.5", 24, 8791).isEmpty());
    assertEquals(253, DeckLink.neighbours("172.16.0.5", 24, 8791).size());
    assertEquals(253, DeckLink.neighbours("10.11.12.13", 24, 8791).size());
    assertTrue(DeckLink.neighbours("", 24, 8791).isEmpty());
    assertTrue(DeckLink.neighbours(null, 24, 8791).isEmpty());
    assertTrue(DeckLink.neighbours("192.168.1", 24, 8791).isEmpty());
    assertTrue(DeckLink.neighbours("192.168.1.300", 24, 8791).isEmpty());
    assertTrue(DeckLink.neighbours("fe80::1", 64, 8791).isEmpty());
  }

  @Test public void smallAndOddNetworksAreHandled() {
    assertTrue("a link with two ends", DeckLink.neighbours("192.168.1.57", 31, 8791).isEmpty());
    assertTrue(DeckLink.neighbours("192.168.1.57", 32, 8791).isEmpty());
    assertEquals("one neighbour", 1, DeckLink.neighbours("192.168.1.57", 30, 8791).size());
    assertEquals("192.168.1.58:8791", DeckLink.neighbours("192.168.1.57", 30, 8791).get(0));
    assertEquals("a prefix nobody gave is taken as a home network", 253,
        DeckLink.neighbours("192.168.1.57", 0, 8791).size());
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

  @Test public void nothingToAskIsNothingFound() {
    assertTrue(DeckLink.sweep(new ArrayList<String>(), DeckLink.SWEEP_CONNECT_MS).isEmpty());
  }
}
