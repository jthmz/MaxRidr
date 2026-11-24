package com.maxapacity.ride;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Arrays;

/**
 * The pretend bike speaks the real protocols: every frame it makes is read
 * back here by the same decoders a real battery and controller go through,
 * across its whole forty-second ride. Plain JUnit, no phone needed.
 */
public class DemoBikeTest {

  @Test public void theBatteryFramesDecodeToTheMomentTheyWereMadeFor() {
    for (long t = 0; t < 40000; t += 250) {
      DemoBike.Moment m = DemoBike.at(t + 3L * 120000);
      byte[] f = DemoBike.batteryFrame(m);
      AntBms.Data d = AntBms.decodeNew(f);
      assertNotNull("at " + t, d);
      assertEquals(m.volts, d.volts, 0.006f);
      assertEquals(m.amps, d.amps, 0.06f);
      assertEquals(73, d.soc);
      assertEquals(98, d.soh);
      assertEquals(20, d.cellVolts.length);
      assertEquals("one cell runs a little low", 18, Math.round(d.deltaCellV() * 1000));
      assertEquals(7, d.minCell);
      assertArrayEquals(new int[] {m.packTempC, m.packTempC + 1}, d.temps);
      assertEquals(m.packTempC + 4, d.mosfetTempC);
      assertEquals(40f, d.capacityAh, 0.01f);
      assertEquals(40f * 0.73f, d.remainingAh, 0.01f);
      assertEquals(m.volts * m.amps, d.powerW, 1f);
      assertTrue(d.problems.toString(), d.problems.isEmpty());
      assertTrue(d.dischargeOn && d.chargeOn);

      // The same frame the way the radio would deliver it: twenty bytes at a time.
      AntBms.Assembler assembler = new AntBms.Assembler();
      AntBms.Data whole = null;
      for (int i = 0; i < f.length; i += 20) {
        whole = assembler.feed(Arrays.copyOfRange(f, i, Math.min(f.length, i + 20)));
      }
      assertNotNull(whole);
      assertEquals(m.soc, whole.soc);
    }
  }

  @Test public void theControllerStreamDecodesToTheSameMoment() {
    for (long t = 0; t < 40000; t += 250) {
      DemoBike.Moment m = DemoBike.at(t);
      int[] ram = DemoBike.memory();
      DemoBike.applyLive(ram, m);
      Fardriver fd = new Fardriver();
      for (int pass = 0; pass < 2; pass++) {
        for (int id = 0; id < Fardriver.BLOCK.length; id++) assertTrue(fd.feed(DemoBike.frame(ram, id)));
      }
      Fardriver.Live l = fd.live();
      assertNotNull("at " + t, l);
      assertEquals(m.rpm, l.rpm);
      assertEquals(m.volts, l.volts, 0.06f);
      assertEquals(m.amps, l.amps, 0.13f);
      assertEquals(m.phaseAmps, l.phaseAmps, 1.5f);
      assertEquals(m.mosfetTempC, l.mosfetTempC);
      assertEquals(m.motorTempC, l.motorTempC);
      assertEquals(m.soc, l.soc);
      assertEquals(m.braking, l.brake);
      assertEquals(0, l.faults);
      assertEquals(m.rpm > 0 ? 1 : 0, l.direction);
      assertEquals("HA80", l.firmware);
      assertTrue(fd.settingsLoaded());
      assertFalse(fd.isLegacy());
    }
  }

  @Test public void theRideStaysInsideWhatTheDemoControllerIsSetTo() {
    int[] ram = DemoBike.memory();
    Fardriver fd = new Fardriver();
    for (int pass = 0; pass < 2; pass++) {
      for (int id = 0; id < Fardriver.BLOCK.length; id++) fd.feed(DemoBike.frame(ram, id));
    }
    float batteryLimitAmps = fd.word(0x19) / 4f;
    boolean stoodStill = false, pulled = false, regenerated = false;
    for (long t = 0; t < 40000; t += 100) {
      DemoBike.Moment m = DemoBike.at(t);
      assertTrue("draws " + m.amps + " A against a limit of " + batteryLimitAmps, m.amps <= batteryLimitAmps);
      assertTrue(m.rpm >= 0 && m.rpm <= 4200);
      assertTrue(m.volts > 72f && m.volts < 84f);
      if (m.rpm == 0) stoodStill = true;
      if (m.amps > 100f) pulled = true;
      if (m.amps < -10f && m.braking) regenerated = true;
    }
    assertTrue("a standstill, so a setting can be tried", stoodStill);
    assertTrue(pulled);
    assertTrue(regenerated);
  }
}
