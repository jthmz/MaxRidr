package com.maxapacity.ride;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.*;

/**
 * The battery and controller protocol code against frames captured from real
 * hardware (published by syssi/esphome-ant-bms and Utah-E-Moto-Guide/trailtune),
 * plus the exact write and save frames those projects verified on a controller.
 * Plain JUnit: runs on any JVM, no phone needed.
 */
public class ProtocolTest {
  static byte[] hex(String s) {
    s = s.replaceAll("\\s", ""); byte[] b = new byte[s.length() / 2];
    for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
    return b;
  }
  static final String NEW16 = "7EA11100008E05010210000000000000000080008001000000000000000000000000E40CE40CE50CE50CE80CE70CE70CE60C"
    + "E80CE70CE70CE70CE70CE70CE60CE90C0100020002000700A41403005B006400010100000076B010D5670E0FBA324A000F00"
    + "000010582E0200000000E90C1000E40C01000500E60C000080007A000F02F2FAB98C3B00BBD85800DA2D4300E8B649000543AA55";
  static final String OLD14 = "AA55AAFF01E80DAA0D9C0DA40D8E0D9C0D900DB40D970DB50DB50DA80D910D9E0D8C00000000000000000000000000000000"
    + "000000000000000000000000000000000000000000000050290A21FE800419589300A9840F00FA0831001600150015001500"
    + "15001501010003E800170000000186090DB50E0D8C0D9F0E00000070006B02AC00000000400115F4";

  @Test public void antNew() {
    AntBms.Data d = AntBms.decodeNew(hex(NEW16));
    assertNotNull(d);
    assertEquals(52.84f, d.volts, 0.001f); assertEquals(0.3f, d.amps, 0.001f); assertEquals(91, d.soc); assertEquals(100, d.soh);
    assertEquals(16, d.cellVolts.length); assertEquals(3.300f, d.cellVolts[0], 0.0005f); assertEquals(3.305f, d.cellVolts[15], 0.0005f);
    assertEquals(280.0f, d.capacityAh, 0.01f); assertEquals(252.602f, d.remainingAh, 0.01f); assertEquals(15f, d.powerW, 0.01f);
    assertArrayEquals(new int[] {1, 2}, d.temps); assertEquals(2, d.mosfetTempC);
    assertEquals(3.305f, d.maxCellV, 0.0005f); assertEquals(16, d.maxCell); assertEquals(3.300f, d.minCellV, 0.0005f); assertEquals(1, d.minCell);
    assertTrue(d.chargeOn); assertTrue(d.dischargeOn); assertTrue(d.problems.toString(), d.problems.isEmpty());
    byte[] bad = hex(NEW16); bad[40] ^= 1; assertNull(AntBms.decodeNew(bad));
  }
  @Test public void antLegacy() {
    AntBms.Data d = AntBms.decodeLegacy(hex(OLD14));
    assertNotNull(d); assertTrue(d.legacy);
    assertEquals(48.8f, d.volts, 0.001f); assertEquals(8.0f, d.amps, 0.001f); assertEquals(41, d.soc); assertEquals(14, d.cellVolts.length);
    assertEquals(170.0f, d.capacityAh, 0.01f); assertEquals(68.770f, d.remainingAh, 0.01f); assertEquals(390f, d.powerW, 0.01f);
    assertEquals(3.509f, d.maxCellV, 0.0005f); assertEquals(9, d.maxCell); assertEquals(3.468f, d.minCellV, 0.0005f); assertEquals(14, d.minCell);
    // Six temperatures: the MOSFET, the balancer, then four on the cells.
    assertEquals(4, d.temps.length); assertTrue(d.mosfetTempC != Integer.MIN_VALUE);
    assertTrue(d.chargeOn && d.dischargeOn); assertTrue(d.problems.isEmpty());
  }
  @Test public void antAssemblerChunks() {
    for (String frame : new String[] {NEW16, OLD14}) {
      for (int size : new int[] {20, 19, 7, 244}) {
        AntBms.Assembler a = new AntBms.Assembler(); byte[] f = hex(frame); AntBms.Data got = null; int n = 0;
        // leading junk from a half-received earlier frame must not break it
        a.feed(new byte[] {0x01, 0x02, 0x03});
        for (int i = 0; i < f.length; i += size) { AntBms.Data d = a.feed(Arrays.copyOfRange(f, i, Math.min(f.length, i + size))); if (d != null) { got = d; n++; } }
        assertNotNull("chunk " + size, got); assertEquals(1, n);
        // and a second frame straight after
        AntBms.Data again = null;
        for (int i = 0; i < f.length; i += size) { AntBms.Data d = a.feed(Arrays.copyOfRange(f, i, Math.min(f.length, i + size))); if (d != null) again = d; }
        assertNotNull(again);
      }
    }
    assertEquals(0x5518, AntBms.crc16(AntBms.REQUEST_NEW, 1, 5));
    assertEquals(0x18, AntBms.REQUEST_NEW[6] & 0xFF); assertEquals(0x55, AntBms.REQUEST_NEW[7] & 0xFF);
  }

  /** Two full memory cycles streamed by a real controller (firmware HA80), at rest. */
  static final String[] HA80 = DemoBike.MEMORY;

  @Test public void fardriverCapture() {
    Fardriver fd = new Fardriver();
    for (String f : HA80) assertTrue(f, fd.feed(hex(f)));
    assertEquals(HA80.length, fd.goodFrames()); assertFalse(fd.isLegacy());
    Fardriver.Live l = fd.live(); assertNotNull(l);
    assertEquals("HA80", l.firmware); assertEquals(81.8f, l.volts, 0.5f); assertEquals(0, l.rpm);
    assertEquals(14, l.mosfetTempC); assertEquals(9, l.motorTempC); assertEquals(84, l.soc); assertEquals(0, l.faults);
    assertTrue(fd.settingsLoaded()); assertNull(fd.tuningBlocker(), fd.tuningBlocker());
    assertEquals(520, fd.raw(Fardriver.param("line"))); assertEquals(1400, fd.raw(Fardriver.param("phase")));
    assertEquals(4200, fd.raw(Fardriver.param("high_rpm"))); assertEquals(2800, fd.raw(Fardriver.param("mid_rpm"))); assertEquals(1800, fd.raw(Fardriver.param("low_rpm")));
    assertEquals(2, fd.raw(Fardriver.param("regen"))); assertEquals(4, fd.raw(Fardriver.param("regen_max")));
    assertEquals(0x4D, fd.raw(Fardriver.param("low_line"))); assertEquals(0x66, fd.raw(Fardriver.param("mid_line")));
    assertEquals("130 A", Fardriver.param("line").format(fd.raw(Fardriver.param("line"))));
  }
  @Test public void legacyReconnectWaitsForNewVoltage() {
    Fardriver fd = new Fardriver();
    byte[] voltage = hex("aa0102e4000000000000000000000191");
    byte[] phase = hex("aa0200000000000000000000000000ac");
    for (int i = 0; i < 3; i++) assertTrue(fd.feed(voltage));
    assertEquals(74f, fd.live().volts, 0f);
    fd.reset();
    for (int i = 0; i < 3; i++) assertTrue(fd.feed(phase));
    assertTrue(fd.isLegacy());
    assertNull(fd.live());
    assertTrue(fd.feed(voltage));
    assertEquals(-1, fd.live().soc);
    assertEquals(Integer.MIN_VALUE, fd.live().motorTempC);
  }

  @Test public void fardriverFrames() {
    // Known-good frames from the sources.
    Fardriver fd = new Fardriver();
    assertTrue(fd.feed(hex("AA8132030100000000000000000027EF")));
    assertEquals(0x0332, fd.word(0xE8));
    // seed word 0x2F = 64 then build "acceleration = 65": AA C6 2F 2F 41 00 89 A0
    byte[] f = new byte[16]; f[0] = (byte) 0xAA; f[1] = (byte) (0x80 | 13);
    int[] w = {2800, 48, 0x0040, 1400, 0, 64};
    for (int k = 0; k < 6; k++) { f[2 + 2 * k] = (byte) w[k]; f[3 + 2 * k] = (byte) (w[k] >> 8); }
    int crc = Fardriver.crc16(f, 0, 14); f[14] = (byte) crc; f[15] = (byte) (crc >> 8);
    assertTrue(fd.feed(f));
    assertArrayEquals(hex("AAC62F2F410089A0"), fd.writeFrame(Fardriver.param("accel"), 65, 224));
    byte[][] save = Fardriver.saveFrames(2026, 11, 21, 21, 50, 1);
    assertArrayEquals(hex("AAC6A0A08804C50C"), save[1]);
    assertEquals(12, save[0].length); assertEquals(0xCA, save[0][1] & 0xFF); assertEquals(0x22, save[0][2] & 0xFF); assertEquals(26, save[0][4]);
    // bitfield write keeps neighbours: throttle release is the low byte of 0x2C
    Fardriver.Param rel = Fardriver.param("release");
    assertEquals(0x0040, fd.word(0x2C));
    byte[] r = fd.writeFrame(rel, 80, rel.max); assertEquals(80, r[4] & 0xFF); assertEquals(0x00, r[5] & 0xFF);
    try { fd.writeFrame(Fardriver.param("accel"), 500, 224); fail(); } catch (IllegalArgumentException e) { }
    try { fd.writeFrame(Fardriver.param("line"), 600, 520); fail(); } catch (IllegalArgumentException e) { }
    // two frames in one notification, and one frame split over two
    Fardriver g = new Fardriver(); byte[] one = hex("AA8132030100000000000000000027EF"); byte[] two = new byte[32];
    System.arraycopy(one, 0, two, 0, 16); System.arraycopy(one, 0, two, 16, 16);
    g.feed(two); assertEquals(2, g.goodFrames());
    g.feed(Arrays.copyOfRange(one, 0, 5)); g.feed(Arrays.copyOfRange(one, 5, 16)); assertEquals(3, g.goodFrames());
    g.feed(hex("18f880060060000600f89800186600001efe")); g.feed(one); assertEquals(4, g.goodFrames());
  }
}
