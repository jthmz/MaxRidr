package com.maxapacity.ride;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Whole tunes: the three made to measure for a controller, and the ones
 * from MAXAPACITY's own bikes. What matters most is that no tune
 * ever asks for more than the controller in hand came with. Built from the
 * demo bike's memory, a capture of a real controller. Plain JUnit.
 */
public class TunePlanTest {

  static final Fardriver.Param LINE = Fardriver.param("line");
  static final Fardriver.Param PHASE = Fardriver.param("phase");

  static Fardriver stock() {
    return TuneBriefTest.controller(DemoBike.memory());
  }

  /** The same controller, set the way MAXAPACITY's Drax Bee build runs: 260 A and 600 A. */
  static Fardriver draxBee() {
    int[] ram = DemoBike.memory();
    ram[0x19] = 1040;
    ram[0x26] = 1040;
    ram[0x2D] = 2400;
    ram[0x27] = 2400;
    return TuneBriefTest.controller(ram);
  }

  static TunePlan.Tune build(String id) {
    for (TunePlan.Tune t : TunePlan.ourBikes()) if (t.id.equals(id)) return t;
    throw new AssertionError("no build tune " + id);
  }

  @Test public void theThreeLevelsAreSharesOfWhatTheControllerCameWith() {
    Fardriver fd = stock();                         // 130 A battery, 350 A motor
    List<TunePlan.Tune> t = TunePlan.measured(TuneBriefTest.ceilings(fd), 80f, 0);
    assertEquals(3, t.size());
    assertEquals("Calm", t.get(TunePlan.CALM).name);
    assertEquals("Balanced", t.get(TunePlan.BALANCED).name);
    assertEquals("Aggressive", t.get(TunePlan.AGGRESSIVE).name);

    assertEquals("everything it came with, and no more", 520, (int) t.get(TunePlan.AGGRESSIVE).values.get("line"));
    assertEquals(1400, (int) t.get(TunePlan.AGGRESSIVE).values.get("phase"));
    assertEquals("Sport", Fardriver.param("response").format(t.get(TunePlan.AGGRESSIVE).values.get("response")));

    assertEquals("100 A battery, 280 A motor, Linear throttle, about 8.0 kW", t.get(TunePlan.BALANCED).detail);
    assertEquals(400, (int) t.get(TunePlan.BALANCED).values.get("line"));
    assertEquals(1120, (int) t.get(TunePlan.BALANCED).values.get("phase"));

    assertEquals("75 A battery, 210 A motor, Eco throttle, about 6.0 kW", t.get(TunePlan.CALM).detail);
    assertEquals(300, (int) t.get(TunePlan.CALM).values.get("line"));
    assertEquals(840, (int) t.get(TunePlan.CALM).values.get("phase"));

    // With no voltage to go by, no power is guessed at.
    assertEquals("75 A battery, 210 A motor, Eco throttle",
        TunePlan.measured(TuneBriefTest.ceilings(fd), 0f, 0).get(TunePlan.CALM).detail);
  }

  @Test public void everyLevelFitsWhateverTheControllerCameWith() {
    for (int lineTop = LINE.min; lineTop <= LINE.max; lineTop += 37) {
      for (int phaseTop = PHASE.min; phaseTop <= PHASE.max; phaseTop += 191) {
        for (int pack : new int[] {0, 30, 100, 500}) {
          final int lt = lineTop;
          final int pt = phaseTop;
          ToIntFunction<Fardriver.Param> top = p -> p == LINE ? lt : p == PHASE ? pt : p.max;
          List<TunePlan.Tune> t = TunePlan.measured(top, 72f, pack);
          int lastLine = 0;
          int lastPhase = 0;
          for (TunePlan.Tune tune : t) {
            int l = tune.values.get("line");
            int p = tune.values.get("phase");
            String what = tune.name + " for " + lt + "/" + pt + " with a " + pack + " A pack: " + l + "/" + p;
            assertTrue(what, l <= lt && p <= pt);
            assertTrue(what, l >= Math.min(LINE.min, lt) && p >= Math.min(PHASE.min, pt));
            assertTrue("never calmer as the levels go up: " + what, l >= lastLine && p >= lastPhase);
            if (pack > 0 && pack * 4 >= LINE.min) assertTrue(what, l <= pack * 4);
            int response = tune.values.get("response");
            assertTrue(what, response >= 0 && response <= 2);
            lastLine = l;
            lastPhase = p;
          }
        }
      }
    }
  }

  @Test public void aRatedBatteryHoldsEveryLevelAtOrUnderIt() {
    Fardriver fd = stock();
    List<TunePlan.Tune> t = TunePlan.measured(TuneBriefTest.ceilings(fd), 80f, 100);   // a pack good for 100 A
    assertEquals(400, (int) t.get(TunePlan.AGGRESSIVE).values.get("line"));              // 100 A, not the controller's 130 A
    assertEquals("the motor limit comes down in step", 1076, (int) t.get(TunePlan.AGGRESSIVE).values.get("phase"));
    assertEquals(320, (int) t.get(TunePlan.BALANCED).values.get("line"));
    assertEquals(240, (int) t.get(TunePlan.CALM).values.get("line"));
    // A pack rated above what the controller came with changes nothing.
    List<TunePlan.Tune> generous = TunePlan.measured(TuneBriefTest.ceilings(fd), 80f, 500);
    List<TunePlan.Tune> unsaid = TunePlan.measured(TuneBriefTest.ceilings(fd), 80f, 0);
    for (int i = 0; i < 3; i++) assertEquals(unsaid.get(i).values, generous.get(i).values);
  }

  @Test public void loadingGivesOnlyWhatDiffersAndEveryChangeIsOneTheAppAccepts() {
    Fardriver fd = stock();
    ToIntFunction<Fardriver.Param> top = TuneBriefTest.ceilings(fd);
    for (TunePlan.Tune t : TunePlan.measured(top, 80f, 0)) {
      TunePlan.Loaded loaded = TunePlan.load(t, fd, top);
      assertNull(t.name, loaded.refused);
      for (Map.Entry<String, Integer> e : loaded.changes.entrySet()) {
        Fardriver.Param p = Fardriver.param(e.getKey());
        assertNotEquals(t.name + " " + p.name + " is a change", fd.raw(p), (int) e.getValue());
        fd.writeFrame(p, e.getValue(), top.applyAsInt(p));     // throws if the app would refuse it
      }
    }
    // This bike already has the calm throttle, so Calm changes the two currents and nothing else.
    assertEquals("Eco", Fardriver.param("response").format(fd.raw(Fardriver.param("response"))));
    TunePlan.Loaded calm = TunePlan.load(TunePlan.measured(top, 80f, 0).get(TunePlan.CALM), fd, top);
    assertEquals("[line, phase]", calm.changes.keySet().toString());
    // And it already has all its current, so Aggressive only sharpens the throttle.
    TunePlan.Loaded all = TunePlan.load(TunePlan.measured(top, 80f, 0).get(TunePlan.AGGRESSIVE), fd, top);
    assertEquals("[response]", all.changes.keySet().toString());
  }

  @Test public void thePublishedTunesHoldTheNumbersOnTheWebsite() {
    assertEquals("180 A", LINE.format(build("drax-chill").values.get("line")));
    assertEquals("450 A", PHASE.format(build("drax-chill").values.get("phase")));
    assertEquals("260 A", LINE.format(build("drax-daily").values.get("line")));
    assertEquals("600 A", PHASE.format(build("drax-daily").values.get("phase")));
    assertEquals("8 A", Fardriver.param("regen").format(build("drax-regen").values.get("regen")));
    assertEquals("12 A", Fardriver.param("regen_max").format(build("drax-regen").values.get("regen_max")));
    int drax = 0;
    for (TunePlan.Tune t : TunePlan.ourBikes()) {
      assertFalse(t.detail.isEmpty());
      if (!t.id.startsWith("drax-")) continue;
      drax++;
      assertTrue(t.name + " says which bike it is for", t.note.contains("Fardriver ND72680") && t.note.contains("Amorge"));
    }
    assertEquals("the tune the website says not to use is not on offer", 3, drax);
    // Each caution the guide gives goes with its tune.
    assertTrue(build("drax-chill").note.contains("not as a tested starting tune"));
    assertTrue(build("drax-daily").note.contains("above that pack's listed 220 A continuous rating"));
    assertTrue(build("drax-daily").note.contains("no test log"));
    assertTrue(build("drax-regen").note.contains("This app sets the two currents only"));
    assertTrue(build("drax-regen").note.contains("try it at 70 to 80% charge"));
  }

  @Test public void aPublishedTuneForAnotherBikeIsRefusedWhole() {
    Fardriver fd = stock();                         // came with 130 A
    ToIntFunction<Fardriver.Param> top = TuneBriefTest.ceilings(fd);
    for (String id : new String[] {"drax-chill", "drax-daily"}) {
      TunePlan.Loaded loaded = TunePlan.load(build(id), fd, top);
      assertNotNull(id, loaded.refused);
      assertTrue(loaded.refused, loaded.refused.contains("This controller came set to 130 A"));
      assertTrue(loaded.refused, loaded.refused.contains("does not fit this bike"));
      assertTrue("nothing is half loaded", loaded.changes.isEmpty());
    }
  }

  @Test public void onItsOwnBikeAPublishedTuneLoadsAndTheOneItAlreadyRunsChangesNothing() {
    Fardriver fd = draxBee();
    ToIntFunction<Fardriver.Param> top = TuneBriefTest.ceilings(fd);
    TunePlan.Loaded chill = TunePlan.load(build("drax-chill"), fd, top);
    assertNull(chill.refused);
    assertEquals("{line=720, phase=1800}", chill.changes.toString());
    TunePlan.Loaded daily = TunePlan.load(build("drax-daily"), fd, top);
    assertNull(daily.refused);
    assertTrue(daily.changes.isEmpty());
  }

  @Test public void theRegenTuneStaysInsideTheAppsLimitsAndSaysWhenRegenIsOff() {
    int[] ram = DemoBike.memory();
    Fardriver off = TuneBriefTest.controller(ram);
    ToIntFunction<Fardriver.Param> top = TuneBriefTest.ceilings(off);
    assertEquals("Off", Fardriver.param("regen_mode").format(off.raw(Fardriver.param("regen_mode"))));
    TunePlan.Loaded loaded = TunePlan.load(build("drax-regen"), off, top);
    assertNull(loaded.refused);
    assertEquals("{regen=8, regen_max=12}", loaded.changes.toString());
    assertNotNull("a rider is told the amps do nothing while regen is off", loaded.note);
    assertTrue(loaded.note, loaded.note.contains("Regen mode is Off"));
    for (Map.Entry<String, Integer> e : loaded.changes.entrySet()) {
      Fardriver.Param p = Fardriver.param(e.getKey());
      off.writeFrame(p, e.getValue(), top.applyAsInt(p));
    }

    ram[0x1A] = (ram[0x1A] & ~0x3) | 2;             // regen on the brake
    Fardriver on = TuneBriefTest.controller(ram);
    assertNull(TunePlan.load(build("drax-regen"), on, TuneBriefTest.ceilings(on)).note);
  }
}
