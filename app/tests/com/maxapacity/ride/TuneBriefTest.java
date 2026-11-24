package com.maxapacity.ride;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.function.ToIntFunction;

/**
 * The tune brief, written from the demo bike's controller memory (a capture of
 * a real controller on firmware HA80) and its battery. What matters most: the
 * brief never tells an adviser the app will accept a value it would refuse.
 * Plain JUnit, no phone needed.
 */
public class TuneBriefTest {

  static final String GOAL = "More range. I want to go further on a charge.";

  /** A controller that has streamed its whole memory twice, standing still. */
  static Fardriver controller(int[] ram) {
    DemoBike.applyLive(ram, DemoBike.at(0));
    Fardriver fd = new Fardriver();
    for (int pass = 0; pass < 2; pass++) {
      for (int id = 0; id < Fardriver.BLOCK.length; id++) fd.feed(DemoBike.frame(ram, id));
    }
    assertNull(fd.tuningBlocker());
    return fd;
  }

  static AntBms.Data battery() {
    return AntBms.decodeNew(DemoBike.batteryFrame(DemoBike.at(0)));
  }

  /** The ceilings Bike works out for a controller it is meeting for the first time. */
  static ToIntFunction<Fardriver.Param> ceilings(final Fardriver fd) {
    // Taken now, as Bike keeps them: a ceiling stays where the controller first stood.
    final java.util.Map<String, Integer> first = new java.util.HashMap<>();
    for (Fardriver.Param p : Fardriver.PARAMS) first.put(p.key, fd.raw(p));
    return p -> {
      if (p.key.endsWith("_rpm")) return Math.min(first.get("high_rpm"), p.max);
      if (p.key.equals("regen") || p.key.equals("regen_max")) return Math.max(first.get(p.key), p.max);
      return p.cappedAtFactory ? Math.min(first.get(p.key), p.max) : p.max;
    };
  }

  static String brief(Fardriver fd, AntBms.Data battery) {
    return TuneBrief.build(GOAL, "", false, false, battery, fd.live(), fd, ceilings(fd));
  }

  @Test public void everySettingIsThereWithItsValueAndARangeTheAppWouldAccept() {
    Fardriver fd = controller(DemoBike.memory());
    ToIntFunction<Fardriver.Param> top = ceilings(fd);
    String brief = brief(fd, battery());
    for (Fardriver.Param p : Fardriver.PARAMS) {
      int now = fd.raw(p);
      String line = "- " + p.name + ": " + p.format(now) + ". ";
      assertTrue(p.name + " with its value", brief.contains(line));
      if (p.options != null) {
        StringBuilder choices = new StringBuilder();
        for (int i = p.min; i <= p.max; i++) {
          choices.append(i == p.min ? "" : ", ").append(p.options[i]);
          fd.writeFrame(p, i, top.applyAsInt(p));     // throws if the app would refuse it
        }
        assertTrue(p.name + " choices", brief.contains(line + "The app can set: " + choices + ". "));
      } else {
        int lo = TuneBrief.lowest(p, now);
        int hi = TuneBrief.highest(p, now, top.applyAsInt(p));
        assertTrue(p.name + " range", brief.contains(line + "Range " + p.format(lo) + " to " + p.format(hi)));
        assertTrue(p.name + " sits inside its own range", lo <= now && now <= hi);
        if (lo != now) fd.writeFrame(p, lo, top.applyAsInt(p));
        if (hi != now) fd.writeFrame(p, hi, top.applyAsInt(p));
      }
      assertTrue(p.name + " says what it does", brief.contains(p.help + "."));
    }
    assertFalse("nothing unread leaks through", brief.contains("null") || brief.contains("Unknown"));
    assertTrue("short enough to paste anywhere: " + brief.length(), brief.length() < 6000);
  }

  @Test public void aCurrentLimitIsNeverOfferedAboveWhereTheControllerStood() {
    Fardriver fd = controller(DemoBike.memory());
    String brief = brief(fd, battery());
    for (String key : new String[] {"line", "phase"}) {
      Fardriver.Param p = Fardriver.param(key);
      String now = p.format(fd.raw(p));
      assertTrue(key, brief.contains("- " + p.name + ": " + now + ". Range " + p.format(p.min) + " to " + now + ", "));
    }
    assertTrue(brief.contains("The two current limits can come down, but never go above"));
  }

  @Test public void theGoalAndTheRidersOwnNoteAreCarried() {
    Fardriver fd = controller(DemoBike.memory());
    String with = TuneBrief.build("  Less wheelspin on wet roots  ",
        " DRAX X-Pro 72V, 13/52 gearing, 190 lb rider ", false, false, battery(), fd.live(), fd, ceilings(fd));
    assertTrue(with.contains("WHAT I WANT\nLess wheelspin on wet roots\n\n"));
    assertTrue(with.contains("ABOUT MY BIKE AND ME\nDRAX X-Pro 72V, 13/52 gearing, 190 lb rider\n\n"));
    assertFalse("no heading for a note that was never written", brief(fd, battery()).contains("ABOUT MY BIKE"));
    for (TuneBrief.Goal g : TuneBrief.GOALS) {
      String asked = TuneBrief.build(g.ask, "", false, false, battery(), fd.live(), fd, ceilings(fd));
      assertTrue(g.name, asked.contains("WHAT I WANT\n" + g.ask + "\n"));
    }
  }

  @Test public void theBatteryIsDescribedOrSaidToBeMissing() {
    Fardriver fd = controller(DemoBike.memory());
    String linked = brief(fd, battery());
    assertTrue(linked, linked.contains("- Battery: 20 cells in series, "));
    assertTrue(linked, linked.contains(battery().soc + "% charged, 40 Ah pack. Cells within 18 mV of each other. "));
    assertTrue(linked, linked.contains("Health 98%. No protections active.\n"));
    String none = brief(fd, null);
    assertTrue(none.contains("- Battery: none linked to the app"));
    assertFalse(none.contains("cells in series"));
    // The controller's own voltage reading still gives the power the limit allows.
    float kw = fd.live().volts * fd.raw(Fardriver.param("line")) * 0.25f / 1000f;
    assertTrue(none, none.contains(String.format(java.util.Locale.US,
        "- Peak power the battery current limit allows at this voltage: about %.1f kW.", kw)));
  }

  @Test public void faultsAndProtectionsAreSaidPlainly() {
    Fardriver fd = controller(DemoBike.memory());
    assertTrue(brief(fd, battery()).contains("No faults showing."));
    Fardriver.Live hot = fd.live();
    hot.faults = 1 << 7;
    AntBms.Data b = battery();
    b.problems.add("Cell over-voltage");
    String brief = TuneBrief.build(GOAL, "", false, false, b, hot, fd, ceilings(fd));
    assertTrue(brief, brief.contains("Faults showing: Controller over-temperature."));
    assertTrue(brief, brief.contains("Protections active: Cell over-voltage."));
  }

  @Test public void aSettingTheAppWouldNotChooseIsShownButNotOffered() {
    int[] ram = DemoBike.memory();
    ram[0x1A] &= ~0x3;                  // regen mode "Follow": read, never written
    ram[0x30] = 50;                     // regen shipped stronger than the app's own limit
    ram[0x31] = 60;
    Fardriver fd = controller(ram);
    String brief = brief(fd, battery());
    assertTrue(brief, brief.contains("- Regen mode: Follow. The app can set: Off, On brake. "));
    assertTrue(brief, brief.contains("- Regen strength: 50 A. Range 0 A to 50 A, "));
    assertTrue(brief, brief.contains("- Max regen strength: 60 A. Range 0 A to 60 A, "));
  }

  @Test public void aDemoBikeSaysSoAndUnitsFollowTheRider() {
    Fardriver fd = controller(DemoBike.memory());
    String real = TuneBrief.build(GOAL, "", false, false, battery(), fd.live(), fd, ceilings(fd));
    assertFalse(real.contains("demo"));
    assertTrue(real.endsWith("I use US units (mph, miles).\n"));
    String demo = TuneBrief.build(GOAL, "", true, true, battery(), fd.live(), fd, ceilings(fd));
    assertTrue(demo.startsWith("Note: this comes from the app's demo bike."));
    assertTrue(demo.endsWith("I use metric units (km/h, km).\n"));
  }

  @Test public void aBatteryRatingTheRiderGaveIsPassedOnAsALimit() {
    Fardriver fd = controller(DemoBike.memory());
    String rated = TuneBrief.build(GOAL, "", false, false, battery(), fd.live(), fd, ceilings(fd), 220);
    assertTrue(rated, rated.contains("- By my own figure the battery is rated for 220 A continuous."
        + " Do not suggest a battery current limit above that.\n"));
    assertFalse("nothing is said of a rating nobody gave", brief(fd, battery()).contains("rated for"));
  }

  @Test public void topSpeedIsHeldToWhereTheControllerStoodWhateverItsSpeedLimitModeSays() {
    int[] ram = DemoBike.memory();
    Fardriver.Param high = Fardriver.param("high_rpm");
    assertEquals("the capture's speed-limit mode is set faster than its top gear", 6000, ram[0x6C]);
    for (int limitMode : new int[] {6000, 0, 2000}) {
      ram[0x6C] = limitMode;
      Fardriver fd = controller(ram);
      String brief = brief(fd, battery());
      assertTrue(brief, brief.contains("- High mode top speed can come down, but never goes above"
          + " where this controller stood when the app first met it, 4200 rpm.\n"));
      for (String key : new String[] {"low_rpm", "mid_rpm", "high_rpm"}) {
        Fardriver.Param p = Fardriver.param(key);
        assertTrue(key + " " + limitMode, brief.contains("- " + p.name + ": " + p.format(fd.raw(p))
            + ". Range 300 rpm to " + high.format(fd.raw(high)) + ", in steps of 100 rpm. "));
      }
      assertFalse("that word is never called a limiter", brief.contains("limiter"));
    }
  }
}
