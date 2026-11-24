package com.maxapacity.ride;

import static org.junit.Assert.*;
import org.junit.Test;

/** Lean angle and crash detection arithmetic. Plain JUnit, no phone needed. */
public class MotionTest {

  /** Rides a steady turn: one fix a second, heading changing by {@code degPerSec}. */
  static float turn(Lean lean, float mps, float degPerSec, int seconds) {
    float out = 0f, bearing = 350f;
    for (int t = 0; t <= seconds; t++) {
      out = lean.add(mps, ((bearing % 360f) + 360f) % 360f, 1000L * (t + 1));
      bearing += degPerSec;
    }
    return out;
  }

  @Test public void aSteadyRightTurnReadsItsLean() {
    Lean lean = new Lean();
    // 15 m/s turning 20 degrees a second: atan(15 * 0.349 / 9.81) = 28.1 degrees.
    assertEquals(28.1f, turn(lean, 15f, 20f, 12), 0.3f);
    assertEquals(28.1f, lean.maxRightDeg(), 0.3f);
    assertEquals(0f, lean.maxLeftDeg(), 0.001f);
  }

  @Test public void aLeftTurnCountsAsLeftAndCrossesNorthCleanly() {
    Lean lean = new Lean();
    assertEquals(-28.1f, turn(lean, 15f, -20f, 12), 0.3f);
    assertEquals(28.1f, lean.maxLeftDeg(), 0.3f);
    assertEquals(0f, lean.maxRightDeg(), 0.001f);
  }

  @Test public void straightSlowAndGlitchyFixesReadNothing() {
    Lean lean = new Lean();
    assertEquals(0f, turn(lean, 15f, 0f, 5), 0.001f);
    // Walking pace: heading spins but it is not a lean.
    assertEquals(0f, turn(lean, 2f, 90f, 5), 0.001f);
    // A heading that flips right round in one fix at speed is a GPS glitch.
    lean.add(20f, 0f, 100000);
    lean.add(20f, 170f, 101000);
    assertEquals(0f, lean.maxRightDeg(), 0.001f);
    // A gap in fixes does not join two headings into one turn.
    lean.add(20f, 0f, 200000);
    lean.add(20f, 30f, 210000);
    assertEquals(0f, lean.maxRightDeg(), 0.001f);
  }

  @Test public void resetClearsTheRecord() {
    Lean lean = new Lean();
    turn(lean, 15f, 20f, 6);
    lean.reset();
    assertEquals(0f, lean.maxRightDeg(), 0.001f);
  }

  @Test public void aHitThenStayingDownIsACrash() {
    CrashDetector d = new CrashDetector();
    d.speed(12f, 1000);
    d.accel(55f, 1500);
    assertTrue(d.isPending());
    d.speed(6f, 2000);   // GPS still catching up with the stop
    d.speed(0f, 5000);
    d.speed(0f, 9000);
    assertFalse(d.confirmed(9000));
    assertTrue(d.confirmed(1500 + CrashDetector.CONFIRM_MS));
    assertFalse("only raised once", d.confirmed(1500 + CrashDetector.CONFIRM_MS + 1000));
  }

  @Test public void aHitThenRidingOnIsOnlyABump() {
    CrashDetector d = new CrashDetector();
    d.speed(12f, 1000);
    d.accel(55f, 1500);
    d.speed(11f, 2500);
    assertTrue("too soon to tell", d.isPending());
    d.speed(10f, 5000);
    assertFalse(d.isPending());
    assertFalse(d.confirmed(20000));
  }

  @Test public void aHitWhileParkedOrALightKnockIsIgnored() {
    CrashDetector d = new CrashDetector();
    d.speed(0f, 1000);
    d.accel(80f, 1500);      // phone dropped while standing still
    assertFalse(d.isPending());
    d.speed(12f, 2000);
    d.accel(25f, 2500);      // trail chatter
    assertFalse(d.isPending());
    d.accel(80f, 20000);     // speed reading long out of date
    assertFalse(d.isPending());
  }

  @Test public void walkingTheBikeAwayDoesNotCancelButRidingDoes() {
    CrashDetector d = new CrashDetector();
    d.speed(12f, 1000);
    d.accel(55f, 1500);
    d.speed(1.2f, 6000);
    assertTrue(d.isPending());
    d.reset();
    assertFalse(d.confirmed(60000));
  }
}
