package com.maxapacity.ride;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Reading an adviser's answer: only its SET lines count, and each is held to
 * exactly what the Tune screen would let a rider enter on this controller.
 * Plain JUnit, on the demo bike's memory (130 A and 350 A as it came).
 */
public class TuneAdviceTest {

  static Fardriver fd() {
    return TuneBriefTest.controller(DemoBike.memory());
  }

  static TuneAdvice.Read read(String answer) {
    Fardriver fd = fd();
    return TuneAdvice.read(answer, fd, TuneBriefTest.ceilings(fd));
  }

  @Test public void theSetLinesAreReadAndTheRestOfTheAnswerIsNot() {
    TuneAdvice.Read r = read("Sure! For more range I would lower the battery current to 100 A first.\n"
        + "Then set a calmer low mode. Do not set anything to 500 A.\n\n"
        + "SET Battery current limit = 100 A\n"
        + "SET Low mode power = 45%\n"
        + "SET Low mode top speed = 1,500 rpm\n"
        + "SET Throttle response = Linear\n");
    assertEquals(4, r.lines);
    assertTrue(r.leftOut.toString(), r.leftOut.isEmpty());
    assertEquals("{line=400, low_line=58, low_rpm=1500, response=0}", r.changes.toString());
    assertEquals("45%", Fardriver.param("low_line").format(r.changes.get("low_line")));
  }

  @Test public void anAnswerDressedUpByAChatAppStillReads() {
    TuneAdvice.Read r = read("**Changes**\r\n"
        + "- **SET Battery current limit = 100 A**\r\n"
        + "* `SET Motor current limit = 300 A`\r\n"
        + "  > set regen strength to 4 a.\r\n"
        + "• SET Acceleration: 80\r\n");
    assertEquals(4, r.lines);
    assertTrue(r.leftOut.toString(), r.leftOut.isEmpty());
    assertEquals("{line=400, phase=1200, regen=4, accel=80}", r.changes.toString());
  }

  @Test public void nothingAboveWhatThisControllerCameWithIsEverLoaded() {
    TuneAdvice.Read r = read("SET Battery current limit = 150 A\nSET Motor current limit = 400 A\nSET Acceleration = 300\n"
        + "SET Regen mode = On throttle release\nSET Regen strength = 11 A\nSET Deceleration = 64\n");
    assertEquals(6, r.lines);
    assertTrue("nothing out of range gets through: " + r.changes, r.changes.isEmpty());
    assertEquals(5, r.leftOut.size());
    assertEquals("Battery current limit 150 A is outside what this bike allows (20 A to 130 A)", r.leftOut.get(0));
    assertEquals("Motor current limit 400 A is outside what this bike allows (40 A to 350 A)", r.leftOut.get(1));
    assertEquals("Regen mode On throttle release is outside what this bike allows (Off or On brake)", r.leftOut.get(3));
  }

  @Test public void everythingLoadedIsSomethingTheAppWouldAccept() {
    Fardriver fd = fd();
    ToIntFunction<Fardriver.Param> top = TuneBriefTest.ceilings(fd);
    StringBuilder answer = new StringBuilder();
    for (Fardriver.Param p : Fardriver.PARAMS) {
      // Ask for each setting's lowest and highest in the words the brief itself uses.
      answer.append("SET ").append(p.name).append(" = ").append(p.format(p.min)).append('\n');
      TuneAdvice.Read low = TuneAdvice.read("SET " + p.name + " = " + p.format(p.min), fd, top);
      TuneAdvice.Read high = TuneAdvice.read("SET " + p.name + " = " + p.format(Math.min(p.max, top.applyAsInt(p))), fd, top);
      for (TuneAdvice.Read r : new TuneAdvice.Read[] {low, high}) {
        assertTrue(p.name + ": " + r.leftOut, r.leftOut.isEmpty());
        for (Map.Entry<String, Integer> e : r.changes.entrySet()) {
          assertEquals(p.key, e.getKey());
          fd.writeFrame(p, e.getValue(), top.applyAsInt(p));     // throws if the app would refuse it
        }
      }
    }
    assertEquals(Fardriver.PARAMS.length, TuneAdvice.read(answer.toString(), fd, top).lines);
  }

  @Test public void whatCannotBeReadIsLeftOutAndSaid() {
    TuneAdvice.Read r = read("SET Field weakening = 30 A\nSET Battery current limit = lower\n"
        + "SET Motor current limit = 350 A to 300 A\nSET Throttle response = Turbo\nSET Burst battery current = 100 A\n");
    assertEquals(5, r.lines);
    assertTrue(r.changes.toString(), r.changes.isEmpty());
    assertEquals("\"Field weakening\" is not a setting this app changes", r.leftOut.get(0));
    assertEquals("Battery current limit: \"lower\" could not be read as a number", r.leftOut.get(1));
    assertEquals("two values on one line is not taken as either",
        "Motor current limit: \"350 A to 300 A\" could not be read as a number", r.leftOut.get(2));
    assertEquals("Throttle response: \"Turbo\" could not be read. It can be set to Linear or Sport or Eco", r.leftOut.get(3));
    assertEquals("a setting that only follows another cannot be asked for by name",
        "\"Burst battery current\" is not a setting this app changes", r.leftOut.get(4));
  }

  @Test public void aValueTheBikeAlreadyHasIsNotAChangeAndAnEmptyAnswerIsNothing() {
    TuneAdvice.Read same = read("SET Battery current limit = 130 A\nSET Regen mode = Off\n");
    assertEquals(2, same.lines);
    assertTrue(same.changes.isEmpty());
    assertTrue(same.leftOut.isEmpty());
    assertEquals(0, read("I would not change anything on this bike.").lines);
    assertEquals(0, read("").lines);
    assertEquals(0, read(null).lines);
    assertEquals("a sentence that merely starts with the word is not a change", 0, read("Settings are fine as they are.").lines);
  }

  @Test public void theBriefAsksForTheLinesThisReads() {
    Fardriver fd = fd();
    String brief = TuneBrief.build("More range.", "", false, false, TuneBriefTest.battery(), fd.live(), fd, TuneBriefTest.ceilings(fd));
    assertTrue(brief, brief.contains("SET Battery current limit = 100 A"));
    // The example in the brief is itself a line this would read.
    assertEquals(1, read(brief.substring(brief.indexOf("SET Battery current limit"), brief.indexOf("SET Battery current limit") + 34)).lines);
  }
}
