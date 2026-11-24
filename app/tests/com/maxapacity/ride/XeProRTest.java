package com.maxapacity.ride;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * A stock Arctic Leopard XE Pro R, as MAXAPACITY read one of its own in the
 * Fardriver app on the day it was delivered: a 330 A controller on a 74 V
 * 60 Ah pack, whose top gear is set to 12500 rpm and whose speed-limit mode
 * is set to 3500 rpm. Both numbers used to trip this app up, so the bike is
 * rebuilt here from those readings and everything the Tune screen does is
 * run against it. Plain JUnit, no phone needed.
 */
public class XeProRTest {

  static final Fardriver.Param LINE = Fardriver.param("line");
  static final Fardriver.Param PHASE = Fardriver.param("phase");

  /**
   * Turns a controller's memory into the XE Pro R's as delivered. Only the
   * words this app reads as settings are changed, each to the value on the
   * app's screen that day; the rest stay as the captured controller had them.
   */
  static void asDelivered(int[] ram) {
    ram[0x19] = 330 * 4;                       // MaxLineCurr 330A
    ram[0x26] = 330 * 4;                       // BoostLineCurr 330A
    ram[0x2D] = 699 * 4;                       // MaxPhaseCurr 699A
    ram[0x27] = 699 * 4;                       // BoostPhaseCurr 699A
    ram[0x1A] = (ram[0x1A] & ~0xF) | 2;        // ThrottleResponse 0-Line, Follow 2-EABSWhenBrake
    ram[0x2F] = 224;                           // Throttle Acc Step
    ram[0x2B] = 224;                           // Throttle Dec Step
    ram[0x2C] = ram[0x2C] & ~0xFF;             // Release Throttle 0
    ram[0x28] = 6000;                          // LowSpeed
    ram[0x2A] = 7000;                          // MiddleSpeed
    ram[0x15] = 12500;                         // MaxSpeed
    ram[0x32] = (90 << 8) | 51;                // MidSpeedLineRatio 70%, LowSpeedLineRatio 40%
    ram[0x30] = 30;                            // StopBackCurr 30A
    ram[0x31] = 50;                            // MaxBackCurr 50A
    ram[0x6C] = 3500;                          // LimitSpeed, the speed-limit mode's own speed
    ram[0x08] = (74 << 8) | 18;                // Throttle Low 0.9V, Throttle High 3.7V
    ram[0x14] = (ram[0x14] & ~0xFF) | 4;       // PolePairs 4
    ram[0x86] = (ram[0x86] & 0xFF) | ('M' << 8);
    ram[0x87] = (88 << 8) | 'A';               // the tag at the end of BJ72V330AB_27_1KMA88
  }

  static Fardriver delivered() {
    int[] ram = DemoBike.memory();
    asDelivered(ram);
    return TuneBriefTest.controller(ram);       // which asserts tuning is not locked
  }

  static TunePlan.Tune factory() {
    for (TunePlan.Tune t : TunePlan.ourBikes()) if (t.id.equals("xe-pro-r-factory")) return t;
    throw new AssertionError("no factory tune");
  }

  static String shown(Fardriver fd, String key) {
    Fardriver.Param p = Fardriver.param(key);
    return p.format(fd.raw(p));
  }

  @Test public void itReadsTheWayTheFardriverAppShowedIt() {
    Fardriver fd = delivered();
    assertNull("12500 rpm is a real top gear, not a sign of unknown firmware", fd.tuningBlocker());
    assertEquals("MA88", fd.firmware());
    assertFalse(Fardriver.verifiedFirmware(fd.firmware()));
    String[][] screen = {
        {"line", "330 A"}, {"phase", "699 A"}, {"boost_line", "330 A"}, {"boost_phase", "699 A"},
        {"response", "Linear"}, {"accel", "224"}, {"decel", "224"}, {"release", "0"},
        {"low_rpm", "6000 rpm"}, {"low_line", "40%"}, {"mid_rpm", "7000 rpm"}, {"mid_line", "70%"},
        {"high_rpm", "12500 rpm"}, {"regen_mode", "On brake"}, {"regen", "30 A"}, {"regen_max", "50 A"},
    };
    for (String[] row : screen) assertEquals(row[0], row[1], shown(fd, row[0]));
    assertFalse("a closed throttle reads closed against its own 0.9 V", fd.throttleOpen());
  }

  @Test public void aReadingNoGearSpeedCouldBeStillLocksTuning() {
    int[] ram = DemoBike.memory();
    asDelivered(ram);
    ram[0x15] = 20100;
    Fardriver fd = new Fardriver();
    DemoBike.applyLive(ram, DemoBike.at(0));
    for (int pass = 0; pass < 2; pass++) {
      for (int id = 0; id < Fardriver.BLOCK.length; id++) fd.feed(DemoBike.frame(ram, id));
    }
    assertNotNull(fd.tuningBlocker());
    assertTrue(fd.tuningBlocker(), fd.tuningBlocker().contains("High mode top speed"));
  }

  @Test public void theBriefGivesAnAdviserThisBikesOwnRanges() {
    Fardriver fd = delivered();
    String brief = TuneBrief.build("Gentler regen braking.", "", false, false, null, fd.live(), fd,
        TuneBriefTest.ceilings(fd));
    for (String line : new String[] {
        "- Battery current limit: 330 A. Range 20 A to 330 A, in steps of 5 A. ",
        "- Motor current limit: 699 A. Range 40 A to 699 A, in steps of 10 A. ",
        "- Low mode top speed: 6000 rpm. Range 300 rpm to 12500 rpm, in steps of 100 rpm. ",
        "- High mode top speed: 12500 rpm. Range 300 rpm to 12500 rpm, in steps of 100 rpm. ",
        "- Regen mode: On brake. The app can set: Off, On brake, On throttle release. ",
        "- Regen strength: 30 A. Range 0 A to 30 A, in steps of 1 A. ",
        "- Max regen strength: 50 A. Range 0 A to 50 A, in steps of 1 A. ",
        "- High mode top speed can come down, but never goes above where this controller stood when"
            + " the app first met it, 12500 rpm.\n",
        "- Controller: Fardriver, firmware MA88. No faults showing.\n",
    }) {
      assertTrue(line + "\n" + brief, brief.contains(line));
    }
    assertFalse("3500 rpm is its speed-limit mode, and is no limit on the gears", brief.contains("3500"));
  }

  @Test public void theThreeLevelsComeOutOfItsOwnLimits() {
    Fardriver fd = delivered();
    List<TunePlan.Tune> t = TunePlan.measured(TuneBriefTest.ceilings(fd), 0f, 0);
    assertEquals("195 A battery, 410 A motor\nEco throttle", t.get(TunePlan.CALM).detail);
    assertEquals("260 A battery, 550 A motor\nLinear throttle", t.get(TunePlan.BALANCED).detail);
    assertEquals("330 A battery, 699 A motor\nSport throttle", t.get(TunePlan.AGGRESSIVE).detail);
    // Aggressive is everything it came with, so on a stock bike only the throttle changes.
    TunePlan.Loaded all = TunePlan.load(t.get(TunePlan.AGGRESSIVE), fd, TuneBriefTest.ceilings(fd));
    assertEquals("{response=1}", all.changes.toString());
  }

  @Test public void theFactoryTuneIsTheBikeAsDelivered() {
    Fardriver fd = delivered();
    TunePlan.Tune factory = factory();
    assertEquals("it covers every setting this app can change", Fardriver.PARAMS.length, factory.values.size());
    for (Fardriver.Param p : Fardriver.PARAMS) {
      assertEquals(p.name, fd.raw(p), (int) factory.values.get(p.key));
    }
    TunePlan.Loaded loaded = TunePlan.load(factory, fd, TuneBriefTest.ceilings(fd));
    assertNull(loaded.refused);
    assertTrue("a stock bike is already set this way", loaded.changes.isEmpty());
    assertNull(loaded.note);
    assertTrue(factory.note.contains("Arctic Leopard XE Pro R"));
    assertTrue(factory.note.contains("BJ72V330AB"));
    assertTrue(factory.note.contains("another batch may have been delivered with different values"));
  }

  @Test public void aModePowerOneStepOffButShownTheSameIsLeftAlone() {
    int[] ram = DemoBike.memory();
    asDelivered(ram);
    ram[0x32] = (89 << 8) | 51;                // 89/128 also reads 70%
    Fardriver fd = TuneBriefTest.controller(ram);
    assertEquals("70%", shown(fd, "mid_line"));
    assertTrue(TunePlan.load(factory(), fd, TuneBriefTest.ceilings(fd)).changes.isEmpty());
  }

  @Test public void afterAnyChangeMadeHereItPutsTheBikeBack() {
    ToIntFunction<Fardriver.Param> top = TuneBriefTest.ceilings(delivered());
    int[] ram = DemoBike.memory();
    asDelivered(ram);
    ram[0x19] = 260 * 4;                       // a calmer battery limit, its burst with it
    ram[0x26] = 260 * 4;
    ram[0x1A] = (ram[0x1A] & ~0xF) | (1 << 2) | 1;   // Sport throttle, regen off
    ram[0x15] = 10000;
    ram[0x28] = 3000;
    ram[0x30] = 8;
    ram[0x31] = 12;
    ram[0x32] = (64 << 8) | 32;
    Fardriver changed = TuneBriefTest.controller(ram);
    TunePlan.Loaded loaded = TunePlan.load(factory(), changed, top);
    assertNull(loaded.refused, loaded.refused);
    assertEquals("{response=0, line=1320, low_rpm=6000, low_line=51, mid_line=90, high_rpm=12500,"
        + " regen_mode=2, regen=30, regen_max=50}", loaded.changes.toString());
    assertNull("it switches regen back on itself, so there is nothing to warn of", loaded.note);
    for (java.util.Map.Entry<String, Integer> e : loaded.changes.entrySet()) {
      Fardriver.Param p = Fardriver.param(e.getKey());
      changed.writeFrame(p, e.getValue(), top.applyAsInt(p));   // throws if the app would refuse it
    }
  }

  @Test public void offThrottleRegenLoadsWithTheMildCurrentsAndTheMode() {
    Fardriver fd = delivered();
    TunePlan.Tune regen = null;
    for (TunePlan.Tune t : TunePlan.ourBikes()) if (t.id.equals("xe-pro-r-regen")) regen = t;
    assertEquals("On throttle release", Fardriver.param("regen_mode").format(regen.values.get("regen_mode")));
    TunePlan.Loaded loaded = TunePlan.load(regen, fd, TuneBriefTest.ceilings(fd));
    assertNull(loaded.refused);
    assertEquals("far below the 30 A and 50 A it came with", "{regen_mode=3, regen=8, regen_max=12}",
        loaded.changes.toString());
    assertNull(loaded.whole);
    assertTrue(regen.note.contains("has not been ridden on the XE Pro R yet"));
    // The Apex owner tune is for another controller, and says so.
    for (TunePlan.Tune t : TunePlan.ourBikes()) {
      if (!t.whole) continue;
      TunePlan.Loaded apex = TunePlan.load(t, fd, TuneBriefTest.ceilings(fd));
      assertTrue(apex.refused, apex.refused.contains("only for the DRAX Apex's own controller"));
      assertTrue(apex.changes.isEmpty());
    }
  }

  @Test public void onAnotherBikeItIsRefusedWhole() {
    Fardriver apex = TuneBriefTest.controller(DemoBike.memory());   // came with 130 A
    TunePlan.Loaded loaded = TunePlan.load(factory(), apex, TuneBriefTest.ceilings(apex));
    assertEquals("XE Pro R Factory asks for 330 A as the battery current limit. This controller came"
        + " set to 130 A and the app never goes above that, so the tune does not fit this bike",
        loaded.refused);
    assertTrue(loaded.changes.isEmpty());
  }

  @Test public void advicePastedInIsHeldToWhatThisBikeCameWith() {
    Fardriver fd = delivered();
    TuneAdvice.Read read = TuneAdvice.read(
        "SET Regen strength = 15 A\n"
            + "SET Max regen strength = 20 A\n"
            + "SET High mode top speed = 10000 rpm\n"
            + "SET Regen strength = 35 A\n"
            + "SET High mode top speed = 13000 rpm\n"
            + "SET Motor current limit = 700 A\n",
        fd, TuneBriefTest.ceilings(fd));
    assertEquals(6, read.lines);
    assertEquals("{regen=15, regen_max=20, high_rpm=10000}", read.changes.toString());
    assertEquals(3, read.leftOut.size());
    assertEquals("Regen strength 35 A is outside what this bike allows (0 A to 30 A)", read.leftOut.get(0));
    assertEquals("High mode top speed 13000 rpm is outside what this bike allows (300 rpm to 12500 rpm)",
        read.leftOut.get(1));
    assertEquals("Motor current limit 700 A is outside what this bike allows (40 A to 699 A)",
        read.leftOut.get(2));
  }
}
