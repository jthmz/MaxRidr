package com.maxapacity.ride;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * What the bike did on a ride, worked out from made-up readings whose answers
 * can be checked by hand. Plain JUnit, no phone needed.
 */
public class BikeLogTest {

  static Fardriver.Live controller(float volts, float amps, int motorC, int controllerC) {
    Fardriver.Live l = new Fardriver.Live();
    l.volts = volts;
    l.amps = amps;
    l.motorTempC = motorC;
    l.mosfetTempC = controllerC;
    return l;
  }

  static AntBms.Data battery(float volts, float amps, int soc, float lowCell, int... temps) {
    AntBms.Data b = new AntBms.Data();
    b.volts = volts;
    b.amps = amps;
    b.soc = soc;
    b.minCellV = lowCell;
    b.temps = temps;
    return b;
  }

  /** The controller reporting five times a second for a while. */
  static long steady(BikeLog log, long fromMs, int seconds, float volts, float amps) {
    for (int i = 0; i <= seconds * 5; i++) {
      log.add(null, controller(volts, amps, 40, 30), true, fromMs + i * 200L);
    }
    return fromMs + seconds * 1000L;
  }

  @Test public void energyIsPowerOverTime() {
    BikeLog log = new BikeLog();
    long t = steady(log, 1000, 10, 72f, 100f);          // 7.2 kW for ten seconds
    BikeLog.Summary s = log.summary();
    assertEquals(7.2f, s.peakKw, 0.001f);
    assertEquals(100f, s.peakAmps, 0.001f);
    assertEquals("7200 W for 10 s is 20 Wh", 20f, s.usedWh, 0.01f);
    assertEquals(0f, s.regenWh, 0.001f);
    assertEquals(0f, s.regenAmps, 0.001f);

    steady(log, t + 200, 5, 80f, -20f);                 // 1.6 kW back for five seconds
    s = log.summary();
    assertEquals(20f, s.regenAmps, 0.001f);
    // The step from drawing to giving back passes through zero on the way.
    assertEquals(1600f * 5 / 3600f, s.regenWh, 0.06f);
    assertEquals("and the peak is still the most it drew", 7.2f, s.peakKw, 0.001f);
  }

  @Test public void aSilenceIsNotRiding() {
    BikeLog log = new BikeLog();
    long t = steady(log, 0, 10, 72f, 100f);
    // Out of range for a minute, then back at the same draw: nothing is made up for the gap.
    steady(log, t + 60000, 10, 72f, 100f);
    assertEquals(40f, log.summary().usedWh, 0.01f);
  }

  @Test public void aSagIsADropUnderLoadFromWhereThePackLastRested() {
    BikeLog log = new BikeLog();
    long t = steady(log, 0, 2, 80f, 0f);                // at rest, 80 V
    t = steady(log, t + 200, 3, 74f, 100f);             // 6 V down under load
    t = steady(log, t + 200, 2, 78f, 2f);               // rests lower: the pack has run down
    steady(log, t + 200, 3, 73f, 100f);                 // 5 V down from there
    BikeLog.Summary s = log.summary();
    assertEquals("the deepest drop, not the latest", 6f, s.sagVolts, 0.001f);
    assertEquals(73f, s.lowVolts, 0.001f);
  }

  @Test public void aPackThatOnlyRunsDownHasNotSagged() {
    BikeLog log = new BikeLog();
    for (int i = 0; i < 50; i++) log.add(null, controller(80f - i * 0.1f, 1f, 40, 30), true, i * 200L);
    assertEquals(0f, log.summary().sagVolts, 0.001f);
  }

  @Test public void tooFewReadingsSayNothing() {
    BikeLog log = new BikeLog();
    assertNull(log.summary());
    for (int i = 0; i < 9; i++) log.add(null, controller(72f, 10f, 40, 30), true, i * 200L);
    assertNull(log.summary());
    log.add(null, controller(72f, 10f, 40, 30), true, 9 * 200L);
    assertNotNull(log.summary());
    log.reset();
    assertNull("a new ride starts from nothing", log.summary());
  }

  @Test public void withAControllerLinkedTheBatteryAddsWhatOnlyItKnows() {
    BikeLog log = new BikeLog();
    Fardriver.Live l = controller(72f, 100f, 85, 52);
    for (int i = 0; i <= 50; i++) {
      log.add(null, l, true, i * 200L);
      // The battery answers once a second with its own, slower, figures.
      if (i % 5 == 0) log.add(battery(71.5f, 99f, 80 - i / 10, 3.52f - i * 0.001f, 31, 36, 33), l, false, i * 200L);
    }
    BikeLog.Summary s = log.summary();
    assertEquals("energy is counted once, from the controller", 20f, s.usedWh, 0.01f);
    assertEquals(80, s.socStart);
    assertEquals(75, s.socEnd);
    assertEquals(36, s.batteryC);
    assertEquals(85, s.motorC);
    assertEquals(52, s.controllerC);
    assertEquals(3.47f, s.lowCellV, 0.0005f);
  }

  @Test public void withNoControllerTheBatteryStandsIn() {
    BikeLog log = new BikeLog();
    for (int i = 0; i <= 10; i++) log.add(battery(72f, 100f, 80, 3.6f, 30), null, false, i * 1000L);
    BikeLog.Summary s = log.summary();
    assertEquals(7.2f, s.peakKw, 0.001f);
    assertEquals(20f, s.usedWh, 0.01f);
    assertEquals(BikeLog.Summary.UNKNOWN, s.motorC);
    assertEquals(BikeLog.Summary.UNKNOWN, s.controllerC);
    assertEquals(30, s.batteryC);
  }

  @Test public void aTemperatureNobodyReportedStaysUnknown() {
    BikeLog log = new BikeLog();
    for (int i = 0; i < 20; i++) {
      log.add(null, controller(72f, 10f, Integer.MIN_VALUE, Integer.MIN_VALUE), true, i * 200L);
    }
    BikeLog.Summary s = log.summary();
    assertEquals(BikeLog.Summary.UNKNOWN, s.motorC);
    assertEquals(BikeLog.Summary.UNKNOWN, s.controllerC);
    assertEquals(BikeLog.Summary.UNKNOWN, s.batteryC);
    assertEquals(-1, s.socStart);
    assertFalse(s.brief(5000, false, 0).contains("Hottest"));
    assertFalse(s.brief(5000, false, 0).contains("Battery went"));
  }

  @Test public void energyForEachMileOrKilometre() {
    BikeLog.Summary s = new BikeLog.Summary();
    s.usedWh = 430f;
    s.regenWh = 18f;
    assertEquals(412f / 6f, s.whPer(6 * 1609.344, false), 0.01f);
    assertEquals(412f / 10f, s.whPer(10000, true), 0.01f);
    assertEquals("too short a ride to say", 0f, s.whPer(100, false), 0f);
  }

  @Test public void theBriefSaysItInPlainLines() {
    BikeLog.Summary s = new BikeLog.Summary();
    s.peakKw = 9.62f;
    s.peakAmps = 129.6f;
    s.lowVolts = 68.9f;
    s.sagVolts = 6.1f;
    s.motorC = 94;
    s.controllerC = 61;
    s.batteryC = 38;
    s.usedWh = 430f;
    s.regenWh = 18f;
    s.regenAmps = 31f;
    s.socStart = 76;
    s.socEnd = 61;
    s.lowCellV = 3.42f;
    assertEquals(
        "- Most power drawn: 9.6 kW, at 130 A from the battery, which is the battery current limit.\n"
            + "- Lowest pack voltage: 68.9 V. Its deepest drop under load was 6.1 V.\n"
            + "- Hottest: motor 94 C, controller 61 C, battery 38 C.\n"
            + "- Energy used: 430 Wh, 69 Wh per mile after regen.\n"
            + "- Strongest regen: 31 A into the battery. It gave back 18 Wh.\n"
            + "- Battery went from 76% to 61%. Lowest cell 3.42 V.\n",
        s.brief(6 * 1609.344, false, 130f));
    // Well inside the limit, the limit is not blamed; and metric riders get kilometres.
    String metric = s.brief(10000, true, 200f);
    assertTrue(metric, metric.startsWith("- Most power drawn: 9.6 kW, at 130 A from the battery.\n"));
    assertTrue(metric, metric.contains("41 Wh per km after regen"));
  }
}
