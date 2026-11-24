package com.maxapacity.ride;

import java.util.ArrayList;
import java.util.List;

/**
 * Fardriver (ND-series) controller protocol: reads the controller's memory as
 * it streams over Bluetooth, decodes live telemetry, and builds the frames
 * that change a setting. No Android types, so it can be exercised with
 * captured frames on a desktop JVM.
 *
 * How it works: the controller continuously streams 16-byte frames. Each
 * carries six 16-bit words of its memory; live values come round about five
 * times a second and every setting about every 1.6 seconds. This class keeps
 * the latest copy of every word. A setting is changed by writing one word
 * back; the write lands in RAM and takes effect at once, and is only kept
 * across a power cycle after an explicit Save.
 *
 * Sources: the official app's own code as documented by
 * theabstractpyro/fardriver-reverse-engineering, jackhumbert/fardriver-controllers
 * and Utah-E-Moto-Guide/trailtune, whose author verified reads, writes and
 * Save on a real controller (firmware HA80 on an X-Pro Drax Apex).
 *
 * Safety rules built in here rather than left to the screen:
 *  - only the settings in {@link #PARAMS} can be written, each within a range;
 *  - older "legacy" controllers, whose write format is not fully known, are
 *    read-only;
 *  - the system-command word is never written except for the one Save value.
 */
final class Fardriver {

    static final int FRAME = 16;

    /** Starts the stream; the controller stops about 8 s after the last one. */
    static final byte[] HELLO = {
            (byte) 0xAA, 0x13, (byte) 0xEC, 0x07, 0x01, 0x5F, 0x10, (byte) 0xEF};

    /** Frame id to the address of the first of its six words. */
    private static final int[] BLOCK = {
            0xE2, 0xE8, 0xEE, 0x00, 0x06, 0x0C, 0x12,
            0xE2, 0xE8, 0xEE, 0x18, 0x1E, 0x24, 0x2A,
            0xE2, 0xE8, 0xEE, 0x30, 0x5D, 0x63, 0x69,
            0xE2, 0xE8, 0xEE, 0x7C, 0x82, 0x88, 0x8E,
            0xE2, 0xE8, 0xEE, 0x94, 0x9A, 0xA0, 0xA6,
            0xE2, 0xE8, 0xEE, 0xAC, 0xB2, 0xB8, 0xBE,
            0xE2, 0xE8, 0xEE, 0xC4, 0xCA, 0xD0,
            0xE2, 0xE8, 0xEE, 0xD6, 0xDC, 0xF4, 0xFA,
    };

    private static final int SYSTEM_COMMAND = 0xA0;

    /** Latest value of every word, and how many times each has been received. */
    private final int[] words = new int[256];
    private final int[] seen = new int[256];
    private final byte[] pending = new byte[256];
    private int pendingLength;
    private boolean legacy;
    private long goodFrames;
    private final Live legacyLive = new Live();

    void reset() {
        java.util.Arrays.fill(words, 0);
        java.util.Arrays.fill(seen, 0);
        pendingLength = 0;
        legacy = false;
        goodFrames = 0;
    }

    /** True once a frame in the older big-endian format has been received. */
    boolean isLegacy() {
        return legacy;
    }

    long goodFrames() {
        return goodFrames;
    }

    int word(int addr) {
        return words[addr & 0xFF];
    }

    /** How many times this word has arrived; grows each time it comes round. */
    int seenCount(int addr) {
        return seen[addr & 0xFF];
    }

    /** True once every setting in {@link #PARAMS} has been read at least twice. */
    boolean settingsLoaded() {
        if (legacy) return false;
        for (Param p : PARAMS) if (seen[p.addr] < 2) return false;
        return seen[0x86] >= 1;
    }

    // ---- Receiving ---------------------------------------------------------

    /**
     * Feeds notification bytes. Notifications are a byte stream: a frame can
     * span two of them and one can hold two frames.
     *
     * @return true if at least one valid frame was completed
     */
    boolean feed(byte[] chunk) {
        if (chunk == null) return false;
        boolean any = false;
        for (byte b : chunk) {
            if (pendingLength == pending.length) pendingLength = 0;
            pending[pendingLength++] = b;
        }
        int at = 0;
        while (pendingLength - at >= FRAME) {
            if ((pending[at] & 0xFF) != 0xAA) {
                at++;
                continue;
            }
            if (accept(pending, at)) {
                any = true;
                at += FRAME;
            } else {
                at++; // not a frame after all: resync on the next 0xAA
            }
        }
        System.arraycopy(pending, at, pending, 0, pendingLength - at);
        pendingLength -= at;
        return any;
    }

    private boolean accept(byte[] f, int o) {
        int id = f[o + 1] & 0xFF;
        if ((id & 0x80) != 0) {
            int crc = crc16(f, o, 14);
            if ((crc & 0xFF) != (f[o + 14] & 0xFF) || (crc >> 8) != (f[o + 15] & 0xFF)) {
                return false;
            }
            id &= 0x7F;
            goodFrames++;
            if (id >= BLOCK.length) return true; // extra blocks: valid, not used
            int addr = BLOCK[id];
            for (int k = 0; k < 6; k++) {
                int a = (addr + k) & 0xFF;
                words[a] = (f[o + 2 + 2 * k] & 0xFF) | ((f[o + 3 + 2 * k] & 0xFF) << 8);
                seen[a]++;
            }
            return true;
        }
        // Legacy generation: plain 16-bit sum, big-endian fields.
        int sum = 0;
        for (int i = 0; i < 14; i++) sum += f[o + i] & 0xFF;
        if ((sum & 0xFFFF) != (((f[o + 14] & 0xFF) << 8) | (f[o + 15] & 0xFF))) return false;
        legacy = true;
        goodFrames++;
        decodeLegacy(f, o, id);
        return true;
    }

    /** CRC-16, reflected 0xA001, initial value 0x7F3C; low byte goes first on the wire. */
    static int crc16(byte[] data, int from, int length) {
        int crc = 0x7F3C;
        for (int i = from; i < from + length; i++) {
            crc ^= data[i] & 0xFF;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
        }
        return crc & 0xFFFF;
    }

    // ---- Live telemetry ----------------------------------------------------

    /** A snapshot of what the controller is doing right now. */
    static final class Live {
        int rpm;
        float volts;
        /** Battery current; negative while regenerating. */
        float amps;
        float phaseAmps;
        float throttleVolts;
        int mosfetTempC;
        int motorTempC;
        int soc;
        /** Fault bits 0-14; zero means healthy. */
        int faults;
        boolean brake;
        /** 0 neutral, 1 forward, 2 reverse. */
        int direction;
        /** 0 low, 1 mid, 2 high. */
        int speedMode;
        boolean legacy;
        String firmware = "";

        float powerW() {
            return volts * amps;
        }

        String speedModeName() {
            return speedMode == 0 ? "Low" : speedMode == 1 ? "Mid" : "High";
        }

        /** Faults in plain words, most useful first. */
        List<String> faultNames() {
            List<String> out = new ArrayList<>();
            for (int bit = 0; bit < FAULTS.length; bit++) {
                if ((faults & (1 << bit)) != 0) out.add(FAULTS[bit]);
            }
            return out;
        }
    }

    private static final String[] FAULTS = {
            "Motor hall sensor error", "Throttle error", "Current protection restart",
            "Phase current surge", "Battery voltage out of range", "Anti-theft alarm",
            "Motor over-temperature", "Controller over-temperature", "Phase current overflow",
            "Phase zero-point error", "Phase short or phase loss",
            "Battery current zero-point error", "MOSFET high-side error",
            "MOSFET low-side error", "Peak current protection",
    };

    /** @return the current reading, or null until the live blocks have arrived */
    Live live() {
        if (legacy) return legacyLive.volts > 0 ? legacyLive : null;
        if (seen[0xE2] == 0 || seen[0xE8] == 0) return null;
        Live l = new Live();
        int state = words[0xE2];
        l.direction = state & 3;
        l.speedMode = (state >> 2) & 3;
        int fault = words[0xE3];
        l.faults = fault & 0x7FFF;
        l.brake = (fault & 0x8000) != 0;
        int rpm = (short) words[0xE5];
        int poles = words[0x14] & 0xFF;
        // The official app scales high pole-count (hub) motors this way.
        l.rpm = poles >= 16 ? rpm * 4 / poles : rpm;
        l.volts = (short) words[0xE8] / 10f;
        l.amps = (short) words[0xEA] / 4f;
        // 24-bit big-endian value spread over two little-endian words.
        int raw = ((words[0xF0] & 0xFF) << 16) | (words[0xF0] & 0xFF00) | (words[0xF1] & 0xFF);
        l.phaseAmps = (float) (1.953125 * Math.sqrt(raw));
        l.throttleVolts = seen[0x82] > 0 ? words[0x82] * 0.01f : 0f;
        l.mosfetTempC = seen[0xDB] > 0 ? (short) words[0xDB] : Integer.MIN_VALUE;
        l.motorTempC = seen[0xF4] > 0 ? (short) words[0xF4] : Integer.MIN_VALUE;
        l.soc = seen[0xF5] > 0 ? (words[0xF5] >> 8) & 0xFF : -1;
        l.firmware = firmware();
        return l;
    }

    /** "HA80"-style firmware tag, or "" until it has been read. */
    String firmware() {
        if (legacy || seen[0x86] == 0 || seen[0x87] == 0) return "";
        char hw = (char) ((words[0x86] >> 8) & 0xFF);
        char fw = (char) (words[0x87] & 0xFF);
        int n = (words[0x87] >> 8) & 0xFF;
        if (!Character.isLetterOrDigit(hw) || !Character.isLetterOrDigit(fw)) return "";
        return "" + hw + fw + n;
    }

    private void decodeLegacy(byte[] f, int o, int index) {
        Live l = legacyLive;
        l.legacy = true;
        switch (index) {
            case 0:
                l.direction = f[o + 4] & 3;
                l.speedMode = (((f[o + 4] & 0xFF) >> 2) ^ 2) & 3;
                l.rpm = (short) (((f[o + 6] & 0xFF) << 8) | (f[o + 7] & 0xFF));
                l.faults = (f[o + 8] & 0xFF) | ((f[o + 9] & 0x7F) << 8);
                l.brake = (f[o + 9] & 0x80) != 0;
                break;
            case 1:
                l.volts = (short) (((f[o + 2] & 0xFF) << 8) | (f[o + 3] & 0xFF)) / 10f;
                l.amps = (short) (((f[o + 4] & 0xFF) << 8) | (f[o + 5] & 0xFF)) / 4f;
                break;
            case 2:
                l.phaseAmps = (float) (1.953125 * Math.sqrt(((f[o + 2] & 0xFF) << 16)
                        | ((f[o + 3] & 0xFF) << 8) | (f[o + 4] & 0xFF)));
                break;
            case 4:
                l.mosfetTempC = f[o + 4];
                break;
            case 10:
                l.soc = f[o + 10] & 0xFF;
                break;
            case 13:
                l.motorTempC = f[o + 2];
                break;
            default:
                break;
        }
    }

    // ---- Settings ----------------------------------------------------------

    /** One tunable setting: where it lives, how to show it, how far it may go. */
    static final class Param {
        final String key;
        final String group;
        final String name;
        final String help;
        final int addr;
        final int shift;
        final int bits;
        /** Raw limits, inclusive. */
        final int min;
        final int max;
        final int step;
        /** Shown value = raw * scale. */
        final float scale;
        final String unit;
        /** Named choices for a small enum, else null. */
        final String[] options;
        /** The value on the controller when first linked is the ceiling. */
        final boolean cappedAtFactory;

        Param(String key, String group, String name, String help, int addr, int shift, int bits,
                int min, int max, int step, float scale, String unit, String[] options,
                boolean cappedAtFactory) {
            this.key = key;
            this.group = group;
            this.name = name;
            this.help = help;
            this.addr = addr;
            this.shift = shift;
            this.bits = bits;
            this.min = min;
            this.max = max;
            this.step = step;
            this.scale = scale;
            this.unit = unit;
            this.options = options;
            this.cappedAtFactory = cappedAtFactory;
        }

        /**
         * The lowest value the screen offers. The validity range starts at
         * zero so an unusual existing value can always be written back, but a
         * rider is not offered values that would leave the bike unrideable.
         */
        int floor() {
            switch (key) {
                case "accel":
                    return 2;
                case "decel":
                    return 4;
                case "line":
                    return 80;
                case "phase":
                    return 160;
                case "low_rpm":
                case "mid_rpm":
                case "high_rpm":
                    return 300;
                case "low_line":
                case "mid_line":
                    return 13;
                default:
                    return min;
            }
        }

        int mask() {
            return ((1 << bits) - 1) << shift;
        }

        /** This setting's raw value inside a memory word. */
        int extract(int word) {
            return (word & mask()) >> shift;
        }

        /** The word with only this setting's bits replaced. */
        int insert(int word, int raw) {
            return (word & ~mask() & 0xFFFF) | ((raw << shift) & mask());
        }

        String format(int raw) {
            if (options != null) {
                return raw >= 0 && raw < options.length ? options[raw] : "Unknown (" + raw + ")";
            }
            float v = raw * scale;
            if ("%".equals(unit)) return Math.round(v) + "%";
            String number = scale == 1f || Math.abs(v - Math.round(v)) < 0.005f
                    ? String.valueOf(Math.round(v))
                    : String.format(java.util.Locale.US, "%.1f", v);
            return unit.isEmpty() ? number : number + " " + unit;
        }
    }

    private static Param word(String key, String group, String name, String help, int addr,
            int min, int max, int step, float scale, String unit, boolean capped) {
        return new Param(key, group, name, help, addr, 0, 16, min, max, step, scale, unit, null,
                capped);
    }

    static final String G_THROTTLE = "THROTTLE FEEL";
    static final String G_POWER = "POWER";
    static final String G_MODES = "SPEED MODES";
    static final String G_REGEN = "REGEN BRAKING";

    /**
     * Every setting this app will write. Each address and scale is agreed by
     * at least two independent readings of the official app, and ranges are
     * the official app's own limits where it has them. Calibration, identity,
     * motor-matching and system-command words are deliberately absent.
     */
    static final Param[] PARAMS = {
            new Param("response", G_THROTTLE, "Throttle response",
                    "The shape of the throttle curve", 0x1A, 2, 2, 0, 2, 1, 1f, "",
                    new String[] {"Linear", "Sport", "Eco"}, false),
            word("accel", G_THROTTLE, "Acceleration",
                    "How quickly power builds when you open the throttle. Higher is snappier",
                    0x2F, 0, 224, 2, 1f, "", false),
            word("decel", G_THROTTLE, "Deceleration",
                    "How quickly power falls when you roll off. Higher is more abrupt",
                    0x2B, 0, 224, 2, 1f, "", false),
            new Param("release", G_THROTTLE, "Throttle release",
                    "How fast drive cuts when the throttle closes", 0x2C, 0, 8, 0, 192, 4, 1f,
                    "", null, false),

            word("line", G_POWER, "Battery current limit",
                    "Peak current drawn from the battery. Lower runs cooler and saves range",
                    0x19, 0, 4000, 20, 0.25f, "A", true),
            word("phase", G_POWER, "Motor current limit",
                    "Peak current sent to the motor, which sets low-speed torque",
                    0x2D, 0, 8000, 40, 0.25f, "A", true),

            word("low_rpm", G_MODES, "Low mode top speed",
                    "Motor speed limit in the low mode", 0x28, 0, 12000, 100, 1f, "rpm", false),
            new Param("low_line", G_MODES, "Low mode power",
                    "Share of the battery current limit in the low mode", 0x32, 0, 8, 0, 128,
                    6, 100f / 128f, "%", null, false),
            word("mid_rpm", G_MODES, "Mid mode top speed",
                    "Motor speed limit in the mid mode", 0x2A, 0, 12000, 100, 1f, "rpm", false),
            new Param("mid_line", G_MODES, "Mid mode power",
                    "Share of the battery current limit in the mid mode", 0x32, 8, 8, 0, 128,
                    6, 100f / 128f, "%", null, false),
            word("high_rpm", G_MODES, "High mode top speed",
                    "Motor speed limit in the high mode", 0x15, 0, 12000, 100, 1f, "rpm", false),

            new Param("regen_mode", G_REGEN, "Regen mode",
                    "When the motor brakes and feeds energy back to the battery", 0x1A, 0, 2,
                    0, 3, 1, 1f, "",
                    new String[] {"Coast", "Off", "On brake", "On throttle release"}, false),
            word("regen", G_REGEN, "Regen strength",
                    "Braking current in normal use", 0x30, 0, 10, 1, 1f, "A", false),
            word("regen_max", G_REGEN, "Max regen strength",
                    "Upper limit for braking current", 0x31, 0, 20, 1, 1f, "A", false),
    };

    static Param param(String key) {
        for (Param p : PARAMS) if (p.key.equals(key)) return p;
        return null;
    }

    /** The current raw value of a setting, from the latest word received. */
    int raw(Param p) {
        return p.extract(words[p.addr]);
    }

    /**
     * Why tuning must stay locked, or null if it may be offered.
     *
     * A setting that reads outside its documented range means this firmware
     * lays its memory out differently from the ones this map was built from,
     * and writing to it would be writing blind.
     */
    String tuningBlocker() {
        if (legacy) {
            return "This controller uses the older Fardriver protocol. Its settings can be"
                    + " viewed in the Fardriver app; changing them from here is not supported.";
        }
        if (!settingsLoaded()) return "Reading settings from the controller";
        for (Param p : PARAMS) {
            int v = raw(p);
            if (v < p.min || v > p.max) {
                return "This controller reports \"" + p.name + "\" as " + v + ", outside"
                        + " anything this app understands. Its firmware (" + firmware()
                        + ") stores settings differently, so tuning is locked to keep it safe.";
            }
        }
        return null;
    }

    /** Firmware on which writes and Save have been verified on real hardware. */
    static boolean verifiedFirmware(String firmware) {
        return "HA80".equals(firmware);
    }

    // ---- Writing -----------------------------------------------------------

    /**
     * Frame that sets one setting, built from the freshest copy of its word so
     * neighbouring bits are preserved.
     *
     * @throws IllegalArgumentException if the value is out of range or the
     *         controller is one this class will not write to
     */
    byte[] writeFrame(Param p, int raw, int ceiling) {
        if (legacy) throw new IllegalArgumentException("legacy controller");
        if (seen[p.addr] == 0) throw new IllegalArgumentException("setting not read yet");
        int max = p.cappedAtFactory ? Math.min(p.max, ceiling) : p.max;
        if (raw < p.min || raw > max) throw new IllegalArgumentException("out of range");
        return wordFrame(p.addr, p.insert(words[p.addr], raw));
    }

    /** The word a write of this value would produce, for read-back checks. */
    int expectedWord(Param p, int raw) {
        return p.insert(words[p.addr], raw);
    }

    private static byte[] wordFrame(int addr, int value) {
        if (addr == SYSTEM_COMMAND) throw new IllegalArgumentException("reserved address");
        return frame(addr, new byte[] {(byte) (value & 0xFF), (byte) ((value >> 8) & 0xFF)});
    }

    private static byte[] frame(int addr, byte[] payload) {
        byte[] f = new byte[4 + payload.length + 2];
        f[0] = (byte) 0xAA;
        f[1] = (byte) (0xC0 + payload.length + 4);
        f[2] = (byte) addr;
        f[3] = (byte) addr;
        System.arraycopy(payload, 0, f, 4, payload.length);
        int crc = crc16(f, 0, f.length - 2);
        f[f.length - 2] = (byte) (crc & 0xFF);
        f[f.length - 1] = (byte) (crc >> 8);
        return f;
    }

    /**
     * The two frames the official app's Save button sends: a timestamp, then
     * the "save" system command. Send them 10 ms apart.
     */
    static byte[][] saveFrames(int year, int month, int day, int hour, int minute, int second) {
        byte[] stamp = frame(0x22, new byte[] {(byte) (year - 2000), (byte) month, (byte) day,
                (byte) hour, (byte) minute, (byte) second});
        byte[] save = frame(SYSTEM_COMMAND, new byte[] {(byte) 0x88, 0x04});
        return new byte[][] {stamp, save};
    }
}
