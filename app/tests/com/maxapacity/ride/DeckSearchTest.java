package com.maxapacity.ride;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Finding a Deck by asking the network, for the many home networks that never
 * pass its announcement on: which addresses get asked. Plain JUnit. Who
 * counts as a Deck among everything that answers is in {@link DeckTest},
 * against stand-ins on this machine.
 */
public class DeckSearchTest {

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

  @Test public void nothingToAskIsNothingFound() {
    assertTrue(DeckLink.sweep(new ArrayList<String>(), DeckLink.SWEEP_CONNECT_MS).isEmpty());
  }
}
