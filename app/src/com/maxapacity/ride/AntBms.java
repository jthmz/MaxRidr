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
        /** Charging has stopped because the pack is full: how every charge ends, not a fault. */
        boolean full;
        /** Active protections, in plain words. Empty when the pack is healthy. */
        final List<String> warnings = new ArrayList<>();
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
     * Collects notification bytes into whole frames. The bytes are a stream:
     * a frame is split across several notifications whatever the MTU, the
     * tail of one frame and the head of the next can share a notification,
     * and some firmware keeps sending frames after a single request. So
     * nothing is assumed about where a frame starts or ends.
     */
    static final class Assembler {
        private final byte[] buf = new byte[1024];
        private int length;

        void reset() {
            length = 0;
        }

        /** @return the newest status decoded from the frames this chunk completed, else null */
        Data feed(byte[] chunk) {
            if (chunk == null || chunk.length == 0) return null;
            if (chunk.length > buf.length - length) {
                // Far more than any frame without one turning up: start over
                // with as much of the newest bytes as fits.
                length = 0;
            }
            int take = Math.min(chunk.length, buf.length);
            System.arraycopy(chunk, chunk.length - take, buf, length, take);
            length += take;

            Data newest = null;
            int at = 0;
            while (true) {
                at = nextHeader(at);
                if (at < 0) {
                    // Nothing that starts a frame: keep only what could be the
                    // first bytes of a header split across two notifications.
                    at = Math.max(0, length - 3);
                    break;
                }
                boolean old = (buf[at] & 0xFF) == 0xAA;
                if (!old && length - at < 6) break;
                int need = old ? LEGACY_LENGTH : 10 + (buf[at + 5] & 0xFF);
                if (length - at < need) break;
                byte[] f = java.util.Arrays.copyOfRange(buf, at, at + need);
                Data d = old ? decodeLegacy(f) : (f[2] & 0xFF) == 0x11 ? decodeNew(f) : null;
                if (d != null) {
                    newest = d;
                    at += need;
                } else if (!old && soundNew(f)) {
                    at += need; // a whole frame of some other kind: step over it
                } else {
                    at++;       // not a frame after all: look for the next start
                }
            }
            System.arraycopy(buf, at, buf, 0, length - at);
            length -= at;
            return newest;
        }

        /** Where the next frame of either generation starts at or after {@code from}, or -1. */
        private int nextHeader(int from) {
            for (int i = from; i + 1 < length; i++) {
                int b = buf[i] & 0xFF;
                if (b == 0x7E && (buf[i + 1] & 0xFF) == 0xA1) return i;
                if (b == 0xAA && i + 3 < length && (buf[i + 1] & 0xFF) == 0x55
                        && (buf[i + 2] & 0xFF) == 0xAA && (buf[i + 3] & 0xFF) == 0xFF) {
                    return i;
                }
            }
            return -1;
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

    /** True for a whole current-protocol frame of any kind: length, tail and CRC all agree. */
    static boolean soundNew(byte[] f) {
        if (f.length < 10) return false;
        int len = f[5] & 0xFF;
        if (f.length != 10 + len) return false;
        if ((f[f.length - 2] & 0xFF) != 0xAA || (f[f.length - 1] & 0xFF) != 0x55) return false;
        return crc16(f, 1, 5 + len) == u16le(f, 6 + len);
    }

    /** @return the decoded status, or null if the frame fails its checks */
    static Data decodeNew(byte[] f) {
        if (!soundNew(f)) return null;
        int len = f[5] & 0xFF;

        int temps = f[8] & 0xFF;
        int cells = f[9] & 0xFF;
        if (cells == 0 || cells > 32 || temps > 8) return null;
        int base = 34 + 2 * cells + 2 * temps;
        // Everything up to the power field must be inside the payload.
        if (6 + len < base + 32) return null;

        Data d = new Data();
        long protect = u32le(f, 10) | (u32le(f, 14) << 32);
        long warning = u32le(f, 18) | (u32le(f, 22) << 32);

        for (int bit = 0; bit < WARNINGS.length; bit++) {
            if (WARNINGS[bit] != null && (warning & (1L << bit)) != 0) {
                d.warnings.add(WARNINGS[bit]);
            }
        }

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
        int health = u16le(f, base + 10);
        if (health <= 100) d.soh = health;
        int dischargeCode = f[base + 12] & 0xFF;
        int chargeCode = f[base + 13] & 0xFF;
        int balanceCode = f[base + 14] & 0xFF;
        d.capacityAh = u32le(f, base + 16) / 1e6f;
        d.remainingAh = u32le(f, base + 20) / 1e6f;
        d.powerW = (int) u32le(f, base + 28);
        d.dischargeOn = dischargeCode == 1;
        d.chargeOn = chargeCode == 1;
        d.balancing = balancing(balanceCode);

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
        // Over-voltage with the pack full and the output still on is the end
        // of a charge. Every charge ends that way, so it is not raised as a fault.
        d.full = endOfCharge(d, chargeCode) || (d.dischargeOn && d.soc >= 95
                && (faults & 0b1110L) != 0 && (faults & ~0b1110L) == 0);
        if (d.full) faults &= ~0b1110L;
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
            if (chargeCode > 1 && chargeCode <= 0x0A && chargeCode != 4 && !d.full) {
                d.problems.add("Charging off: " + CHARGE[chargeCode]);
            }
        }
        return d;
    }

    /** The balancer is working in states 1 and 2 (triggered by a limit) as well as 4 (automatic). */
    private static boolean balancing(int code) {
        return code == 1 || code == 2 || code == 4;
    }

    /** Charging switched off for over-voltage or "battery full", near full, with the output on. */
    private static boolean endOfCharge(Data d, int chargeCode) {
        return d.dischargeOn && d.soc >= 95
                && (chargeCode == 2 || chargeCode == 4 || chargeCode == 5);
    }

    // ANT realtime protocol, table 5.3.1. This word also carries normal operating
    // states (charge MOS on, balancing, standby, etc.). Only warnings are named here.
    private static final String[] WARNINGS = {
            /* 0 */ "High cell voltage", "High pack voltage", "Low cell voltage", "Low pack voltage",
            /* 4 */ "Cell voltage difference too large", "High charging temperature",
            "High discharge temperature", "Low charging temperature", "Low discharge temperature",
            /* 9 */ "High MOSFET temperature", "High charging current", "High discharge current",
            "Low charge (level 1)", "Low charge (level 2)", "Cell voltage check failed",
            "Pack voltage check failed", "Precharge failed",
            /* 17–29: operating states */ null, null, null, null, null, null, null, null,
            null, null, null, null, null,
            /* 30 */ "Balancer over-temperature", "Voltage protection reported",
            "Temperature protection reported", "System error", "GPS communication lost",
            /* 35–42: operating states */ null, null, null, null, null, null, null, null,
            /* 43 */ "Clock error", null, null, "Usage period ended", "Usage period ending",
            null, "Low-voltage shutdown approaching", null, null, null,
            /* 53 */ "Internal communication unstable", "Heater fault",
    };

    private static final String[] PROTECTION = {
            null, "Cell over-voltage", "Cell over-voltage", "Pack over-voltage",
            "Cell under-voltage", "Cell under-voltage", "Pack under-voltage",
            "Cell voltage difference too large", "Too hot to charge", "Too hot to discharge",
            "MOSFET over-temperature", "Too cold to charge", "Too cold to discharge",
            "Charge over-current", "Discharge over-current", "Discharge over-current",
            "Short circuit", null, null, null, null, null, null, null, null,
            "Balance wire open", "Current sensor error", "Discharge MOSFET error",
            "Charge MOSFET error", "Internal communication error", "Precharge failed",
            // 31-39 are states (starting up, self-tests, update wanted), not faults.
            null, null, null, null, null, null, null, null, null,
            "Relay welded", "Discharge fuse fault", "Charge fuse fault", "Module lost",
            "Usage period expired", "Pre-discharge MOSFET fault", "Precharge MOSFET fault",
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
        // Six temperatures: the MOSFET, the balancer, then up to four on the
        // cells. A slot with no sensor reads -40, or 0 on boards from 2020,
        // which pad the unused slots at the end with zeros.
        int mosfet = (short) u16be(f, 91);
        if (mosfet != -40) d.mosfetTempC = mosfet;
        List<Integer> t = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int v = (short) u16be(f, 95 + 2 * i);
            if (v != -40) t.add(v);
        }
        while (!t.isEmpty() && t.get(t.size() - 1) == 0) t.remove(t.size() - 1);
        d.temps = new int[t.size()];
        for (int i = 0; i < d.temps.length; i++) d.temps[i] = t.get(i);
        int chargeCode = f[103] & 0xFF;
        int dischargeCode = f[104] & 0xFF;
        d.chargeOn = chargeCode == 1;
        d.dischargeOn = dischargeCode == 1;
        d.balancing = balancing(f[105] & 0xFF);
        int power = (int) u32be(f, 111);
        // Boards from 2021 that still answer this request put 9999 here.
        d.powerW = power == 9999 ? d.volts * d.amps : power;
        d.maxCell = f[115] & 0xFF;
        d.maxCellV = u16be(f, 116) / 1000f;
        d.minCell = f[118] & 0xFF;
        d.minCellV = u16be(f, 119) / 1000f;
        d.avgCellV = u16be(f, 121) / 1000f;
        // The same boards report cell numbers that are not cells (16 on an
        // 8-cell pack): then the cells themselves say which is lowest and highest.
        if (d.minCell < 1 || d.minCell > cells || d.maxCell < 1 || d.maxCell > cells) {
            fillCellStats(d);
        }
        d.full = endOfCharge(d, chargeCode);
        if (dischargeCode > 1 && dischargeCode < LEGACY_DISCHARGE.length
                && LEGACY_DISCHARGE[dischargeCode] != null) {
            d.problems.add("Output off: " + LEGACY_DISCHARGE[dischargeCode]);
        }
        if (chargeCode > 1 && chargeCode <= 0x0A && chargeCode != 4 && !d.full) {
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
