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
    assertEquals(6, d.temps.length); assertTrue(d.chargeOn && d.dischargeOn); assertTrue(d.problems.isEmpty());
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
  static final String[] HA80 = {
    "aaa60000b0000000000000000000b3d9",
    "aaa7000000000000c70000000000a6f8",
    "aaa80180cb290000400052a81940e4f1",
    "aaa9008188135c4488132400ec020f1a",
    "aaaa8138000000000000000000008fdf",
    "aaab3203010000000000000000004085",
    "aaac032956000000000000000000cec2",
    "aaad0048005f00020002000100806677",
    "aaae55ff4b0d1800001a803a40001e99",
    "aaaf0861020b460c1a78a00f39048b65",
    "aab0813800000000000000000000a945",
    "aab1320301000000000000000000661f",
    "aab2032956000000000000000000e6dc",
    "aab3ffff00014030100400000e002cca",
    "aab40329ac09c03f6b24ce040000640f",
    "aab509004654471a05176508b100de96",
    "aab61e0820080000ffff012200004f51",
    "aa80813800000000000000000000e8b5",
    "aa8132030100000000000000000027ef",
    "aa82032956000000000000000000a72c",
    "aa830001000106010e0106018400508b",
    "aa840000090a164901003100504b489b",
    "aa8530018003e204010308005078280b",
    "aa868403f40104006810c409d0028736",
    "aa87813800000000000000000100e262",
    "aa883203010000000000000000003a26",
    "aa89032956000000000000000000bda7",
    "aa8a5e0c0802092b4901230020002e1a",
    "aa8b40005802574377541a091b07d36a",
    "aa8c0f208b030802a00508076801a5ea",
    "aa8df00a40000000780510274000f25b",
    "aa8e813800000000000000000000fe3b",
    "aa8f3203010000000000000000003161",
    "aa900329560000000000000000009efe",
    "aa91020004004d6680803200e880dac2",
    "aa92000000000000000000000000fbba",
    "aa935802540607202c01d002214f2044",
    "aa94f0f46af7fff570171300315aa2f3",
    "aa95813800000000000000000000da20",
    "aa963203010000000000000000001238",
    "aa9703295600000000000000000095b9",
    "aa98010104000000bb9b2cfc0000ed6d",
    "aa994f007703a08c6450004841506cf9",
    "aa9a6464625f5d5a5955524f4b018931",
    "aa9b0000000000000000f6f6f6f6f22f",
    "aa9c813800000000000000000000c7e9",
    "aa9d32030100000000000000000008b3",
    "aa9e033956000000000000000000dcb0",
    "aa9ff1f1f1ececec000000000000badb",
    "aaa0000000001016000035006a00eaa9",
    "aaa188044a535758324332363035657c",
    "aaa2323332373736414332380000f1d7",
    "aaa38138000000000000000000009216",
    "aaa4320301000000000000000000548a",
    "aaa503395600000000000000000087cb",
    "aaa60000b0000000000000000000b3d9",
    "aaa7000000000000c70000000000a6f8",
    "aaa80180cb290000400052a81940e4f1",
    "aaa9008188135c4488132400ec020f1a",
    "aaaa8138000000000000000000008fdf",
    "aaab3203010000000000000000004085",
    "aaac0339560000000000000000009a02",
    "aaad0048005f00020002000100806677",
    "aaae55ff4b0d1800001a803a40001e99",
    "aaaf0861020b460c1a78a00f39048b65",
    "aab0813800000000000000000000a945",
    "aab1320301000000000000000000661f",
    "aab2033856000000000000000000b6e0",
    "aab3ffff00014030100400000e002cca",
    "aab40338ac09c03f6b24ce0400003433",
    "aab509004754481a05176508b100cf13",
    "aab61e0820080000ffff012200004f51",
    "aa80813800000000000000000000e8b5",
    "aa8132030100000000000000000027ef",
    "aa82033956000000000000000000f3ec",
    "aa830001000106010e0106018400508b",
    "aa840000090a164901003100504b489b",
    "aa8530018003e204010308005078280b",
    "aa868403f40104006810c409d0028736",
    "aa87813800000000000000000000e3f2",
    "aa883203010000000000000000003a26",
    "aa89032856000000000000000000b95b",
    "aa8a5e0c0802092b4901230020002e1a",
    "aa8b40005802574377541a091b07d36a",
    "aa8c0f208b030802a00508076801a5ea",
    "aa8df00a40000000780510274000f25b",
    "aa8e813800000000000000000000fe3b",
    "aa8f3203010000000000000000003161",
    "aa90033956000000000000000000ca3e",
    "aa91020004004d6680803200e880dac2",
    "aa92000000000000000000000000fbba",
    "aa935802540607202c01d002214f2044",
    "aa94f0f46af7fff570171300315aa2f3",
    "aa95813800000000000000000000da20",
    "aa963203010000000000000000001238",
    "aa9703295600000000000000000095b9",
    "aa98010104000000bb9b2cfc0000ed6d",
    "aa994f007703a08c6450004841506cf9",
    "aa9a6464625f5d5a5955524f4b018931",
    "aa9b0000000000000000f6f6f6f6f22f",
    "aa9c813800000000000000000000c7e9",
    "aa9d32030100000000000000000008b3",
    "aa9e0329560000000000000000008870",
    "aa9ff1f1f1ececec000000000000badb",
    "aaa0000000001016000035006a00eaa9",
    "aaa188044a535758324332363035657c",
    "aaa2323332373736414332380000f1d7",
    "aaa38138000000000000000000009216",
    "aaa4320301000000000000000000548a",
    "aaa5032956000000000000000000d30b",
    "aaa60000b0000000000000000000b3d9",
    "aaa7000000000000c70000000000a6f8",
  };

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
    assertArrayEquals(hex("AAC62F2F410089A0"), fd.writeFrame(Fardriver.param("accel"), 65, 0));
    byte[][] save = Fardriver.saveFrames(2026, 11, 21, 21, 50, 1);
    assertArrayEquals(hex("AAC6A0A08804C50C"), save[1]);
    assertEquals(12, save[0].length); assertEquals(0xCA, save[0][1] & 0xFF); assertEquals(0x22, save[0][2] & 0xFF); assertEquals(26, save[0][4]);
    // bitfield write keeps neighbours: throttle release is the low byte of 0x2C
    Fardriver.Param rel = Fardriver.param("release");
    assertEquals(0x0040, fd.word(0x2C));
    byte[] r = fd.writeFrame(rel, 80, 0); assertEquals(80, r[4] & 0xFF); assertEquals(0x00, r[5] & 0xFF);
    try { fd.writeFrame(Fardriver.param("accel"), 500, 0); fail(); } catch (IllegalArgumentException e) { }
    try { fd.writeFrame(Fardriver.param("line"), 600, 520); fail(); } catch (IllegalArgumentException e) { }
    // two frames in one notification, and one frame split over two
    Fardriver g = new Fardriver(); byte[] one = hex("AA8132030100000000000000000027EF"); byte[] two = new byte[32];
    System.arraycopy(one, 0, two, 0, 16); System.arraycopy(one, 0, two, 16, 16);
    g.feed(two); assertEquals(2, g.goodFrames());
    g.feed(Arrays.copyOfRange(one, 0, 5)); g.feed(Arrays.copyOfRange(one, 5, 16)); assertEquals(3, g.goodFrames());
    g.feed(hex("18f880060060000600f89800186600001efe")); g.feed(one); assertEquals(4, g.goodFrames());
  }
}
