package com.maxapacity.ride;

import static org.junit.Assert.*;
import org.junit.Test;

/**
 * The battery decoder against frames from real ANT BMS units of many shapes,
 * published by the open-source clients it was checked against. Each frame's
 * numbers were confirmed by decoding it with those clients' own code. The
 * two frames already in ProtocolTest are both healthy packs with both
 * switches on and current flowing out; these cover the rest: charging, a
 * switch held off, a full pack, missing sensors, 8 to 30 cells, the older
 * generation, and frames that arrive glued together.
 * Plain JUnit: runs on any JVM, no phone needed.
 */
public class AntFramesTest {

  static byte[] hex(String s) {
    return ProtocolTest.hex(s);
  }

  /** The numbers every frame carries, in one line per frame. */
  static AntBms.Data decoded(String frame, boolean legacy, float volts, float amps, int soc, int cells,
      float minCellV, float maxCellV) {
    AntBms.Data d = legacy ? AntBms.decodeLegacy(hex(frame)) : AntBms.decodeNew(hex(frame));
    assertNotNull(d);
    assertEquals(legacy, d.legacy);
    assertEquals(volts, d.volts, 0.006f);
    assertEquals(amps, d.amps, 0.06f);
    assertEquals(soc, d.soc);
    assertEquals(cells, d.cellVolts.length);
    assertEquals(minCellV, d.minCellV, 0.0006f);
    assertEquals(maxCellV, d.maxCellV, 0.0006f);
    assertTrue("lowest cell is a cell", d.minCell >= 1 && d.minCell <= cells);
    assertTrue("highest cell is a cell", d.maxCell >= 1 && d.maxCell <= cells);
    assertEquals("the cell named lowest is the lowest", d.minCellV, d.cellVolts[d.minCell - 1], 0.0006f);
    assertEquals("the cell named highest is the highest", d.maxCellV, d.cellVolts[d.maxCell - 1], 0.0006f);
    return d;
  }

  /** New 22s/4t lto discharging. https://github.com/patman15/aiobmsble/blob/main/tests/bms/test_ant_bms.py */
  static final String T1 =
      "7ea11100009e050404160000000000000000000088010000000000000000000000001e091b091e091d091d091e092009"
      + "1e098f088e0890082209eb08200920091f091e091f091e091f0921091f091d001d001d001d001e001e00e01315000a00"
      + "64000101000000b4c40486f197003aede8006a000000a1f2c0030000000022090c008e080a009400080900006d006a00"
      + "af02f3fa4c74e5002866ec00c35d6200ccb39200edc8aa55";

  /** New 22s discharge switched off by the vendor app. https://github.com/syssi/esphome-ant-bms/blob/main/docs/btsnoop_hci_ANT-BLE24BHUB-commands.log */
  static final String T2 =
      "7ea11100009e050104160000020000000000000080000000000000000000000000001f091c091f091e091e091f092109"
      + "1f098f088e0890082309eb082109210921091f0921091f092109210920091c001c001c001d001e001f00e01300000a00"
      + "64000f01000000b4c404725f97004cede8000000000027f3c0030000000023090c008e080a0095000909000001006800"
      + "af02f3fa7074e5002866ec00005e6200ccb39200d335aa55";

  /** New 22s both switched off. https://github.com/syssi/esphome-ant-bms/blob/main/docs/btsnoop_hci_ANT-BLE24BHUB-commands.log */
  static final String T2b =
      "7ea11100009e0501041600002200000000000000000000000000000000000000000020091d091f091f091f091f092109"
      + "1f0990088f0890082309eb082109210921091f0921091f092109220921091c001d001d001d001e001e00e21300000a00"
      + "64000f0f000000b4c404f89f970044ede80000000000cdf2c0030000000023090c008f080a0094000a09010001000000"
      + "af02f3fa6174e5002866ec00e75d6200ccb39200dfcbaa55";

  /** New 8s lfp full, charge off by cell over-voltage. https://github.com/fl4p/batmon-ha/blob/master/bmslib/test/data/ant_fixtures.py */
  static final String T3 =
      "7ea11100007e05010208020000000000000001004201000000000000000000000000d40dd50dd50dd50dd50dd40dd50d"
      + "d50dd8ffd8ff1c001d00110b0000640064000102000000e1f50500e1f505a532000000000000ff97010000000000d50d"
      + "0200d40d01000100d40df8ff82000000ab02f2fa100000003a6500001f000000fa62000011c3aa55";

  /** New 8s charging -6.2 a. https://github.com/fl4p/batmon-ha/blob/master/bmslib/test/data/ant_fixtures.py */
  static final String T4 =
      "7ea11100007e0502020800000000000000000000c411000000000000000000000000880d420d5a0d3f0d450d560da50d"
      + "4c0dd8ffd8ff14001700b00ac2ff5e0064000101020040679a0ee5c0930dd80c8c0157ffffff4efd8b0341000000a50d"
      + "07003f0d040066005d0dffff81007c00aa02f2faf170a101bfa8760122b97c010fb69d0014daaa55";

  /** New 30s/4t. https://github.com/syssi/esphome-ant-bms/blob/main/esp32-ble-example-faker.yaml */
  static final String T5 =
      "7ea1110000ae0501041e000000000000000000008001000000000000000000000000030f040f060f050f060f060f050f"
      + "060f040f060f060f040f020f030f030f030f030f040f060f060f020f050f030f050f040f060f060f000f000f050f0f00"
      + "10000f00d8ff100010000c2d00004600640001010000c09bc001ef20390198d6040000000000a5e4be0100000000060f"
      + "0300000f1c000600040f00006b006800ae02f1fada7a0400573205002ecb000027830300797baa55";

  /** New 14s/2t soc 2%. https://github.com/syssi/esphome-ant-bms/blob/main/esp32-ble-example-faker.yaml */
  static final String T6 =
      "7ea11100008a0501020e000000000000000000308001000000000000000000000000770d770d700d710d720d710d700d"
      + "700d6e0d710d710d710d710d700d1500140017001700d2120300020064000101000080d1f00839b4670013ee2d000900"
      + "00008fd4900000000000770d01006e0d09000900710d000080007a000e02f1fa6e992f00b7422c00a45a0c002a510a00"
      + "4008aa55";

  /** New 14s/4t one ntc absent, charge off by over-voltage. https://github.com/syssi/esphome-ant-bms/blob/main/docs/pdus/model2021-req-7ea1010000be1855aa55.txt */
  static final String T6b =
      "7ea11100008e0501040e0200000000000000000000010000000000000000000000001110111011101110111011101110"
      + "11101110111011101110111011101c001c00d8ff1c001c001c007e160000600064000102000080c3c9014f55b3010853"
      + "0000000000006b28120000000000111001001110010000001110020070000300ac02f1fa7d2e000094770000de070000"
      + "7776000035e2aa55";

  /** New 20s/4t fw 22aaub00-241008a, 178 bytes. Two cell entries were restored from the issue text; the frame passes its CRC. https://github.com/syssi/esphome-ant-bms/issues/174 */
  static final String T7 =
      "7ea1110000a8010304140000000000000000000080050000000000000000000000007810781078107810781078107810"
      + "781078107910771078107810781078107810781079107810781019001900190019001b001c00f3200100640064000101"
      + "000080a4bf07c917a80711bb000008000000546210000000000079100a0077100b0002007810000081007c00ab02f1fa"
      + "77840000acf10000922a00001e850000000000009a27040000008608000064ffaa55";

  /** Legacy 16s, charge off by over-voltage, balancer state 2. https://github.com/patman15/aiobmsble/blob/main/tests/bms/test_ant_leg_bms.py */
  static final String T8 =
      "aa55aaff021e0d0a0d0b0d0b0d0b0d550d3f0d6c0d480d880d760d460db20d0b0d0b0d0b0d0b00000000000000000000"
      + "0000000000000000000000000000000000000000000000000000640000000008581e020b3879530a13fbf3001a001dff"
      + "fb0015000000000201020000000000000000000c0db2010d0a0d3910ffef00800000000000000b5034090f0d";

  /** Legacy 8s charging -7.4 a. https://github.com/fl4p/batmon-ha/blob/master/bmslib/test/data/ant_uart_fixtures.py */
  static final String T9 =
      "aa55aaff010d0d2b0d2a0d2a0d290d2a0d2a0d2a0d2a0000000000000000000000000000000000000000000000000000"
      + "00000000000000000000000000000000000000000000ffffffb64f0000000005a2ca9401276d3101b30a900011001200"
      + "100011000000000101000000000000ffffff3c010d2b040d290d2a080000007e007a00000000000064050f73";

  /** Legacy from a 2021 board, power placeholder 9999. https://github.com/fl4p/batmon-ha/blob/master/bmslib/test/data/ant_uart_fixtures.py */
  static final String T10 =
      "aa55aaff010a0d060d060d0b0cf10cf30d0e0d100ce80000000000000000000000000000000000000000000000000000"
      + "00000000000000000000000000000000000000000000000000005b127a398010a22ca5000011f8000f44d00012001100"
      + "11000effd8ffd80f0f0000000000000000270f070d10100ce80d0008010e0000000002120000000000000e64";

  /** Legacy 16s from a 2021 board answering db db. https://github.com/syssi/esphome-ant-bms/blob/main/docs/pdus/model2021-req-dbdb00000000.txt */
  static final String T11 =
      "aa55aaff027d0f8f0f8f0f8e0f8e0f8d0f900f900f910f8f0f8f0f900f8d0f8c0f8e0f8c0f8f00000000000000000000"
      + "0000000000000000000000000000000000000000000000000000540df28e800ba390f8000434e20017b8d00017001900"
      + "150016ffd8ffd801010000000000ff00000000080f91110f8c0f8e100001007f007b02af0000000000001a5e";

  /** Legacy 14s discharging 12.0 a. https://github.com/syssi/esphome-ant-bms/blob/main/esp32-old-ble-example-faker.yaml */
  static final String T12 =
      "aa55aaff01e60d960d870d900d7b0d880d7d0da30d850da60da70d920d7e0d850d830000000000000000000000000000"
      + "0000000000000000000000000000000000000000000000000078290a21fe800417f63a00a9846900fa08570016001500"
      + "1500150015001501010003e8001700000002460a0da7040d7b0d8d0e0000006f006b02ac0000000050011571";

  /** Real notifications in which the tail of a status frame and the head of the next share one. https://github.com/syssi/esphome-ant-bms/blob/main/docs/btsnoop_hci_ANT-BLE24BHUB.log */
  static final String[] GLUED = {
      "7ea11100009e0501041600000000000000000000",
      "8001000000000000000000000000a608ae08a708",
      "a608ae08ac08ad08ac08ad08a508aa08a608ac08",
      "a708a908ac089c08a108ac08ae08ac08aa081600",
      "160016001700180017000d1302005b0064000101",
      "0000b8ec7e04b02707044f4ed40009000000a761",
      "e80200000000ae0802009c0811001200a9080000",
      "6c006700ad02f3fa3af8d00064a4d7005b1c5f00",
      "4c6299009249aa557ea1126c0220323442484130",
      "5442313130410000000032344248554230302d32",
      "323038323341f09cff0b000041f2aa55",
  };


  @Test public void dischargingPackOfTwentyTwoCells() {
    AntBms.Data d = decoded(T1, false, 50.88f, 2.1f, 10, 22, 2.19f, 2.338f);
    assertEquals(106f, d.powerW, 0.5f);
    assertEquals(80f, d.capacityAh, 0.01f);
    assertArrayEquals(new int[] {29, 29, 29, 29}, d.temps);
    assertEquals(30, d.mosfetTempC);
    assertTrue(d.chargeOn && d.dischargeOn);
    assertTrue(d.problems.toString(), d.problems.isEmpty());
  }

  @Test public void theSwitchBytesAreReadTheRightWayRound() {
    // The vendor's own app switched the output off, then both off: only the discharge byte moved first.
    AntBms.Data output = decoded(T2, false, 50.88f, 0.0f, 10, 22, 2.19f, 2.339f);
    assertTrue(output.chargeOn);
    assertFalse(output.dischargeOn);
    assertEquals(java.util.Collections.singletonList("Output off: switched off manually"), output.problems);
    AntBms.Data both = decoded(T2b, false, 50.9f, 0.0f, 10, 22, 2.191f, 2.339f);
    assertFalse(both.chargeOn);
    assertFalse(both.dischargeOn);
  }

  @Test public void aFullPackIsFullNotAtFault() {
    AntBms.Data lfp = decoded(T3, false, 28.33f, 0.0f, 100, 8, 3.54f, 3.541f);
    assertTrue(lfp.full);
    assertFalse(lfp.chargeOn);
    assertTrue(lfp.dischargeOn);
    assertTrue("the end of a charge is not a fault: " + lfp.problems, lfp.problems.isEmpty());
    assertEquals("both sensor inputs are empty", 0, lfp.temps.length);
    assertEquals(28, lfp.mosfetTempC);
    AntBms.Data liIon = decoded(T6b, false, 57.58f, 0.0f, 96, 14, 4.113f, 4.113f);
    assertTrue(liIon.full);
    assertTrue(liIon.problems.toString(), liIon.problems.isEmpty());
    assertArrayEquals("one of four sensors is absent", new int[] {28, 28, 28}, liIon.temps);
  }

  @Test public void chargingReadsNegativeAndTheBalancerIsWorking() {
    AntBms.Data d = decoded(T4, false, 27.36f, -6.2f, 94, 8, 3.391f, 3.493f);
    assertEquals(-169f, d.powerW, 0.5f);
    assertEquals(245f, d.capacityAh, 0.01f);
    assertEquals(227.787f, d.remainingAh, 0.01f);
    assertTrue("balancer state 2: evening out a spread while charging", d.balancing);
    assertFalse(d.full);
    assertTrue(d.problems.toString(), d.problems.isEmpty());
  }

  @Test public void thirtyCellsAndFourteenCells() {
    AntBms.Data thirty = decoded(T5, false, 115.32f, 0.0f, 70, 30, 3.84f, 3.846f);
    assertArrayEquals(new int[] {15, 16, 15}, thirty.temps);
    assertEquals(16, thirty.mosfetTempC);
    assertEquals(3.844f, thirty.avgCellV, 0.0006f);
    AntBms.Data fourteen = decoded(T6, false, 48.18f, 0.3f, 2, 14, 3.438f, 3.447f);
    assertArrayEquals(new int[] {21, 20}, fourteen.temps);
    assertEquals(9f, fourteen.powerW, 0.5f);
  }

  @Test public void aTwentyCellSeventyTwoVoltPack() {
    AntBms.Data d = decoded(T7, false, 84.35f, 0.1f, 100, 20, 4.215f, 4.217f);
    assertEquals(8f, d.powerW, 0.5f);
    assertEquals(130f, d.capacityAh, 0.01f);
    assertArrayEquals(new int[] {25, 25, 25, 25}, d.temps);
    assertEquals(27, d.mosfetTempC);
    assertEquals(100, d.soh);
  }

  @Test public void olderGenerationTemperaturesAreWhereTheyBelong() {
    AntBms.Data full = decoded(T8, true, 54.2f, 0.0f, 100, 16, 3.338f, 3.506f);
    assertEquals("the first of the six is the MOSFET", 26, full.mosfetTempC);
    assertArrayEquals("then the balancer, then the cells; unused slots are not sensors",
        new int[] {-5, 21}, full.temps);
    assertTrue(full.full);
    assertTrue(full.problems.toString(), full.problems.isEmpty());
    assertTrue(full.balancing);
    assertEquals(-1, full.soh);
    AntBms.Data charging = decoded(T9, true, 26.9f, -7.4f, 79, 8, 3.369f, 3.371f);
    assertEquals(-196f, charging.powerW, 0.5f);
    assertEquals(17, charging.mosfetTempC);
    assertArrayEquals(new int[] {16, 17}, charging.temps);
    AntBms.Data riding = decoded(T12, true, 48.6f, 12.0f, 41, 14, 3.451f, 3.495f);
    assertEquals(582f, riding.powerW, 0.5f);
    assertEquals(22, riding.mosfetTempC);
    assertArrayEquals(new int[] {21, 21, 21, 21}, riding.temps);
  }

  @Test public void aNewerBoardAnsweringTheOlderWayIsNotTakenAtItsWord() {
    // It reports 9999 W at rest and a lowest cell that is not a cell.
    AntBms.Data d = decoded(T10, true, 26.6f, 0.0f, 91, 8, 3.304f, 3.344f);
    assertEquals("power comes from volts and amps when the board only sends a placeholder", 0f, d.powerW, 0.5f);
    assertEquals(8, d.minCell);
    assertEquals(7, d.maxCell);
    assertEquals(18, d.mosfetTempC);
    assertArrayEquals(new int[] {17, 14}, d.temps);
    AntBms.Data sixteen = decoded(T11, true, 63.7f, 0.0f, 84, 16, 3.98f, 3.985f);
    assertEquals(8, sixteen.maxCell);
    assertEquals(13, sixteen.minCell);
  }

  @Test public void framesGluedTogetherAreStillRead() {
    AntBms.Assembler assembler = new AntBms.Assembler();
    int statuses = 0;
    AntBms.Data last = null;
    for (String notification : GLUED) {
      AntBms.Data d = assembler.feed(hex(notification));
      if (d != null) {
        statuses++;
        last = d;
      }
    }
    assertEquals("one status frame, with a device-info reply riding on its tail", 1, statuses);
    assertEquals(48.77f, last.volts, 0.006f);
    assertEquals(0.2f, last.amps, 0.06f);
    assertEquals(91, last.soc);
    assertEquals(22, last.cellVolts.length);
  }

  @Test public void aStreamOfFramesCutAnywhereIsReadWhole() {
    // Three status frames back to back, cut into pieces of every size from 1 to 64 bytes,
    // with a stray byte in front: every frame is found, whatever the cut.
    byte[] one = hex(T1), two = hex(T4), three = hex(T7);
    byte[] stream = new byte[1 + one.length + two.length + three.length];
    stream[0] = 0x55;
    System.arraycopy(one, 0, stream, 1, one.length);
    System.arraycopy(two, 0, stream, 1 + one.length, two.length);
    System.arraycopy(three, 0, stream, 1 + one.length + two.length, three.length);
    for (int size = 1; size <= 64; size++) {
      AntBms.Assembler assembler = new AntBms.Assembler();
      java.util.List<Integer> cells = new java.util.ArrayList<>();
      for (int i = 0; i < stream.length; i += size) {
        AntBms.Data d = assembler.feed(java.util.Arrays.copyOfRange(stream, i, Math.min(stream.length, i + size)));
        if (d != null) cells.add(d.cellVolts.length);
      }
      // A piece that holds the end of two frames reports the newer one, so the last is always seen.
      assertFalse("pieces of " + size, cells.isEmpty());
      assertEquals("pieces of " + size, Integer.valueOf(20), cells.get(cells.size() - 1));
      if (size <= 100) assertTrue("pieces of " + size + ": " + cells, cells.size() >= 2);
    }
  }

  @Test public void olderFramesAreReadFromAStreamToo() {
    byte[] frame = hex(T12);
    AntBms.Assembler assembler = new AntBms.Assembler();
    AntBms.Data d = null;
    for (int i = 0; i < frame.length; i += 20) {
      AntBms.Data got = assembler.feed(java.util.Arrays.copyOfRange(frame, i, Math.min(frame.length, i + 20)));
      if (got != null) d = got;
    }
    assertNotNull(d);
    assertTrue(d.legacy);
    assertEquals(12.0f, d.amps, 0.06f);
  }
}
