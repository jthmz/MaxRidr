package com.maxapacity.ride;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ANT BMS protocol, read side only: frames a status request, reassembles the
 * reply from Bluetooth notifications and decodes it. No Android types, so it
 * can be exercised with captured frames on a desktop JVM.
 *
 * Two generations exist and both are handled: the current "7E A1" framed
 * protocol (little-endian, CRC-16/MODBUS) and the legacy fixed 140-byte
 * "AA 55 AA FF" frame (big-endian, 16-bit sum). Field offsets follow the
 * open-source clients syssi/esphome-ant-bms, patman15/aiobmsble and
 * fl4p/batmon-ha, and were checked against the real frames those projects
 * publish.
 *
 * Nothing here can change a setting on the BMS: the only bytes ever sent are
 * the two read requests below.
 */
final class AntBms {

    private AntBms() {
    }

    /** Status request, current protocol. Reply is function 0x11. */
    static final byte[] REQUEST_NEW = {
            0x7E, (byte) 0xA1, 0x01, 0x00, 0x00, (byte) 0xBE, 0x18, 0x55, (byte) 0xAA, 0x55};
    /** Status request, legacy protocol. Reply is the fixed 140-byte frame. */
    static final byte[] REQUEST_LEGACY = {(byte) 0xDB, (byte) 0xDB, 0x00, 0x00, 0x00, 0x00};

    static final int LEGACY_LENGTH = 140;

    /** One decoded status reply. */
    static final class Data {
        boolean legacy;
        float volts;
        /** Positive while discharging (riding), negative while charging. */
        float amps;
        float powerW;
        int soc;
        /** State of health in percent; -1 on legacy units, which do not report it. */
        int soh = -1;
        float capacityAh;
        float remainingAh;
        float[] cellVolts = new float[0];
        float minCellV;
        float maxCellV;
        int minCell;
        int maxCell;
        float avgCellV;
        /** Pack temperature sensors, deg C. Disconnected sensors are left out. */
        int[] temps = new int[0];
        /** MOSFET temperature, deg C; Integer.MIN_VALUE when not reported. */
        int mosfetTempC = Integer.MIN_VALUE;
        boolean chargeOn;
        boolean dischargeOn;
        boolean balancing;
        /** Active protections, in plain words. Empty when the pack is healthy. */
        List<String> problems = new ArrayList<>();

        float deltaCellV() {
            return maxCellV - minCellV;
        }

        boolean hasProblem() {
            return !problems.isEmpty();
        }

        int maxTempC() {
            int max = Integer.MIN_VALUE;
            for (int t : temps) max = Math.max(max, t);
            return max;
        }
    }

    // ---- Reassembly --------------------------------------------------------

    /**
     * Collects notification chunks into whole frames. A frame is split across
     * several notifications whatever the MTU, so nothing is assumed about
     * chunk size.
     */
    static final class Assembler {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        void reset() {
            buf.reset();
        }

        /** @return a decoded status when this chunk completed a valid frame, else null */
        Data feed(byte[] chunk) {
            if (chunk == null || chunk.length == 0) return null;
            byte[] have = buf.toByteArray();
            boolean startsNew = chunk.length >= 2
                    && (chunk[0] & 0xFF) == 0x7E && (chunk[1] & 0xFF) == 0xA1;
            boolean startsLegacy = chunk.length >= 4
                    && (chunk[0] & 0xFF) == 0xAA && (chunk[1] & 0xFF) == 0x55
                    && (chunk[2] & 0xFF) == 0xAA && (chunk[3] & 0xFF) == 0xFF;
            // A start marker begins a new frame unless a new-protocol frame is
            // still mid-payload (payload bytes may themselves look like a marker).
            if ((startsNew || startsLegacy) && !midNewFrame(have)) buf.reset();
            if (buf.size() + chunk.length > 512) {
                buf.reset();
                return null;
            }
            buf.write(chunk, 0, chunk.length);
            byte[] f = buf.toByteArray();

            if (f.length >= 4 && (f[0] & 0xFF) == 0xAA && (f[1] & 0xFF) == 0x55
                    && (f[2] & 0xFF) == 0xAA && (f[3] & 0xFF) == 0xFF) {
                if (f.length < LEGACY_LENGTH) return null;
                buf.reset();
                return f.length == LEGACY_LENGTH ? decodeLegacy(f) : null;
            }
            if (f.length >= 6 && (f[0] & 0xFF) == 0x7E && (f[1] & 0xFF) == 0xA1) {
                int need = 10 + (f[5] & 0xFF);
                if (f.length < need) return null;
                buf.reset();
                // Only the status reply is decoded; anything else is dropped.
                return (f[2] & 0xFF) == 0x11 && f.length == need ? decodeNew(f) : null;
            }
            if (f.length >= 6) buf.reset(); // not a frame we know: start over
            return null;
        }

        private static boolean midNewFrame(byte[] have) {
            return have.length >= 6 && (have[0] & 0xFF) == 0x7E && (have[1] & 0xFF) == 0xA1
                    && have.length < 10 + (have[5] & 0xFF);
        }
    }

    // ---- Checks ------------------------------------------------------------

    /** CRC-16/MODBUS: reflected 0xA001, initial 0xFFFF. */
    static int crc16(byte[] data, int from, int length) {
        int crc = 0xFFFF;
        for (int i = from; i < from + length; i++) {
            crc ^= data[i] & 0xFF;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
        }
        return crc & 0xFFFF;
    }

    private static int u16le(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }

    private static long u32le(byte[] d, int i) {
        return (u16le(d, i) & 0xFFFFL) | ((u16le(d, i + 2) & 0xFFFFL) << 16);
    }

    private static int u16be(byte[] d, int i) {
        return ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
    }

    private static long u32be(byte[] d, int i) {
        return ((u16be(d, i) & 0xFFFFL) << 16) | (u16be(d, i + 2) & 0xFFFFL);
    }

    // ---- Current protocol --------------------------------------------------

    /** @return the decoded status, or null if the frame fails its checks */
    static Data decodeNew(byte[] f) {
        if (f.length < 10) return null;
        int len = f[5] & 0xFF;
        if (f.length != 10 + len) return null;
        if ((f[f.length - 2] & 0xFF) != 0xAA || (f[f.length - 1] & 0xFF) != 0x55) return null;
        if (crc16(f, 1, 5 + len) != u16le(f, 6 + len)) return null;

        int temps = f[8] & 0xFF;
        int cells = f[9] & 0xFF;
        if (cells == 0 || cells > 32 || temps > 8) return null;
        int base = 34 + 2 * cells + 2 * temps;
        // Everything up to the power field must be inside the payload.
        if (6 + len < base + 32) return null;

        Data d = new Data();
        long protect = u32le(f, 10) | (u32le(f, 14) << 32);
        long warning = u32le(f, 18) | (u32le(f, 22) << 32);

        d.cellVolts = new float[cells];
        for (int i = 0; i < cells; i++) {
            // The top three bits flag balancer state on some models.
            d.cellVolts[i] = (u16le(f, 34 + 2 * i) & 0x1FFF) / 1000f;
        }
        List<Integer> t = new ArrayList<>();
        for (int i = 0; i < temps; i++) {
            int v = (short) u16le(f, 34 + 2 * cells + 2 * i);
            if (v != -40) t.add(v); // -40 means no sensor on that input
        }
        d.temps = new int[t.size()];
        for (int i = 0; i < d.temps.length; i++) d.temps[i] = t.get(i);

        d.mosfetTempC = (short) u16le(f, base);
        d.volts = u16le(f, base + 4) / 100f;
        d.amps = (short) u16le(f, base + 6) / 10f;
        d.soc = clampPct(u16le(f, base + 8));
        d.soh = clampPct(u16le(f, base + 10));
        int dischargeCode = f[base + 12] & 0xFF;
        int chargeCode = f[base + 13] & 0xFF;
        int balanceCode = f[base + 14] & 0xFF;
        d.capacityAh = u32le(f, base + 16) / 1e6f;
        d.remainingAh = u32le(f, base + 20) / 1e6f;
        d.powerW = (int) u32le(f, base + 28);
        d.dischargeOn = dischargeCode == 1;
        d.chargeOn = chargeCode == 1;
        d.balancing = (balanceCode & 0x04) != 0;

        if (6 + len >= base + 52) {
            d.maxCellV = u16le(f, base + 40) / 1000f;
            d.maxCell = u16le(f, base + 42);
            d.minCellV = u16le(f, base + 44) / 1000f;
            d.minCell = u16le(f, base + 46);
            d.avgCellV = u16le(f, base + 50) / 1000f;
        } else {
            fillCellStats(d);
        }

        // Bits 17-24 only say a switch is being held off on purpose.
        long faults = protect & ~(0xFFL << 17);
        for (int bit = 0; bit < PROTECTION.length; bit++) {
            if ((faults & (1L << bit)) != 0 && PROTECTION[bit] != null) {
                d.problems.add(PROTECTION[bit]);
            }
        }
        if (d.problems.isEmpty()) {
            if (dischargeCode > 1 && dischargeCode < DISCHARGE.length) {
                d.problems.add("Output off: " + DISCHARGE[dischargeCode]);
            }
            // Charge codes above 0x0A differ between firmware families.
            if (chargeCode > 1 && chargeCode <= 0x0A && chargeCode != 4) {
                d.problems.add("Charging off: " + CHARGE[chargeCode]);
            }
        }
        return d;
    }

    private static final String[] PROTECTION = {
            null, "Cell over-voltage", "Cell over-voltage", "Pack over-voltage",
            "Cell under-voltage", "Cell under-voltage", "Pack under-voltage",
            "Cell voltage difference too large", "Too hot to charge", "Too hot to discharge",
            "MOSFET over-temperature", "Too cold to charge", "Too cold to discharge",
            "Charge over-current", "Discharge over-current", "Discharge over-current",
            "Short circuit", null, null, null, null, null, null, null, null,
            "Balance wire open", "Current sensor error", "Discharge MOSFET error",
            "Charge MOSFET error", "Internal communication error", "Precharge failed",
    };

    private static final String[] DISCHARGE = {
            "off", "on", "cell under-voltage", "over-current", "over-current",
            "pack under-voltage", "battery over-temperature", "MOSFET over-temperature",
            "abnormal current", "balance wire open", "board over-temperature",
            "charger connected", "short circuit", "discharge MOSFET fault", "failed to switch on",
            "switched off manually", "pack under-voltage", "too cold",
            "cell voltage difference too large", "self-test error",
    };

    private static final String[] CHARGE = {
            "off", "on", "cell over-voltage", "over-current", "battery full",
            "pack over-voltage", "battery over-temperature", "MOSFET over-temperature",
            "abnormal current", "balance wire open", "board over-temperature",
    };

    // ---- Legacy protocol ---------------------------------------------------

    /** @return the decoded status, or null if the frame fails its checks */
    static Data decodeLegacy(byte[] f) {
        if (f.length != LEGACY_LENGTH) return null;
        int sum = 0;
        for (int i = 4; i < 138; i++) sum += f[i] & 0xFF;
        if ((sum & 0xFFFF) != u16be(f, 138)) return null;

        int cells = f[123] & 0xFF;
        if (cells == 0 || cells > 32) return null;
        Data d = new Data();
        d.legacy = true;
        d.volts = u16be(f, 4) / 10f;
        d.cellVolts = new float[cells];
        for (int i = 0; i < cells; i++) d.cellVolts[i] = u16be(f, 6 + 2 * i) / 1000f;
        d.amps = (int) u32be(f, 70) / 10f;
        d.soc = clampPct(f[74] & 0xFF);
        d.capacityAh = u32be(f, 75) / 1e6f;
        d.remainingAh = u32be(f, 79) / 1e6f;
        List<Integer> t = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int v = (short) u16be(f, 91 + 2 * i);
            if (v != -40) t.add(v);
        }
        d.temps = new int[t.size()];
        for (int i = 0; i < d.temps.length; i++) d.temps[i] = t.get(i);
        int chargeCode = f[103] & 0xFF;
        int dischargeCode = f[104] & 0xFF;
        d.chargeOn = chargeCode == 1;
        d.dischargeOn = dischargeCode == 1;
        d.balancing = (f[105] & 0x04) != 0;
        d.powerW = (int) u32be(f, 111);
        d.maxCell = f[115] & 0xFF;
        d.maxCellV = u16be(f, 116) / 1000f;
        d.minCell = f[118] & 0xFF;
        d.minCellV = u16be(f, 119) / 1000f;
        d.avgCellV = u16be(f, 121) / 1000f;
        if (dischargeCode > 1 && dischargeCode < LEGACY_DISCHARGE.length
                && LEGACY_DISCHARGE[dischargeCode] != null) {
            d.problems.add("Output off: " + LEGACY_DISCHARGE[dischargeCode]);
        }
        if (chargeCode > 1 && chargeCode <= 0x0A && chargeCode != 4) {
            d.problems.add("Charging off: " + CHARGE[chargeCode]);
        }
        return d;
    }

    private static final String[] LEGACY_DISCHARGE = {
            "off", "on", "cell under-voltage", "over-current", null, "pack under-voltage",
            "battery over-temperature", "MOSFET over-temperature", "abnormal current",
            "balance wire open", "board over-temperature", "charger connected", "short circuit",
            "discharge MOSFET fault", "start-up fault", "switched off manually",
    };

    private static void fillCellStats(Data d) {
        float min = Float.MAX_VALUE;
        float max = 0;
        float sum = 0;
        for (int i = 0; i < d.cellVolts.length; i++) {
            float v = d.cellVolts[i];
            sum += v;
            if (v < min) {
                min = v;
                d.minCell = i + 1;
            }
            if (v > max) {
                max = v;
                d.maxCell = i + 1;
            }
        }
        d.minCellV = min;
        d.maxCellV = max;
        d.avgCellV = d.cellVolts.length == 0 ? 0 : sum / d.cellVolts.length;
    }

    private static int clampPct(int v) {
        return Math.max(0, Math.min(100, v));
    }
}
