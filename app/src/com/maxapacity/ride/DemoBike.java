package com.maxapacity.ride;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.UUID;

/**
 * A pretend bike, for seeing every battery and controller screen with no bike
 * nearby: a 72 V pack and a Fardriver controller that play out the same short
 * ride over and over. No radio is involved and nothing leaves the phone.
 *
 * Nothing above this class knows the difference: the two links below speak
 * the real protocols, byte for byte, so the screens, the dashboard and even
 * tuning run exactly the code a real bike runs. The controller starts from
 * the memory of a real one, so the settings on show are real settings.
 */
final class DemoBike {

    private DemoBike() {
    }

    static final String BATTERY_NAME = "Demo battery";
    static final String CONTROLLER_NAME = "Demo controller";

    private static final int CELLS = 20;
    private static final float CAPACITY_AH = 40f;
    private static final long LOOP_MS = 40000;
    private static final long TICK_MS = 200;
    /** Cruising speed as a share of the top speed the demo controller is set to. */
    private static final float CRUISE = 0.97f;
    /** Just under the battery current limit the demo controller is set to. */
    private static final float PEAK_AMPS = 126f;

    /** Stands in for both Bluetooth links. */
    static BleLink make(Context c, UUID characteristic, BleLink.Listener listener) {
        return characteristic.equals(BleLink.uuid(0xFFE1))
                ? new Battery(c, characteristic, listener)
                : new Controller(c, characteristic, listener);
    }

    // ---- The ride both halves play out ----------------------------------------

    /** What the pretend bike is doing at one moment, so battery and controller agree. */
    static final class Moment {
        float amps;
        float volts;
        int rpm;
        float phaseAmps;
        float throttleVolts;
        int mosfetTempC;
        int motorTempC;
        int packTempC;
        int soc;
        boolean braking;
    }

    /**
     * Forty seconds, repeating: six at a standstill (long enough to try a
     * setting), a hard pull away, a cruise, a regenerative stop.
     */
    static Moment at(long ms) {
        float t = (ms % LOOP_MS) / 1000f;
        Moment m = new Moment();
        float speed;
        if (t < 6f) {
            speed = 0f;
            m.amps = 0.4f;
        } else if (t < 14f) {
            float k = (t - 6f) / 8f;
            speed = CRUISE * k * (2f - k);
            m.amps = PEAK_AMPS - 70f * k;
        } else if (t < 26f) {
            speed = CRUISE - 0.03f * (float) Math.sin((t - 14f) * 1.3f);
            m.amps = 46f + 7f * (float) Math.sin((t - 14f) * 1.7f);
        } else if (t < 32f) {
            float k = (t - 26f) / 6f;
            speed = CRUISE * (1f - k) * (1f - k);
            m.amps = -3f - 20f * (1f - k);
            m.braking = true;
        } else {
            speed = 0f;
            m.amps = 0.4f;
        }
        m.rpm = Math.round(speed * 4200f);
        // The pack sags under load and lifts while regenerating.
        m.volts = 80.2f - 0.035f * m.amps;
        m.phaseAmps = m.amps > 1f ? Math.min(450f, m.amps * 2.3f) : Math.abs(m.amps) * 1.4f;
        m.throttleVolts = m.amps > 1f ? 0.9f + 2.9f * Math.min(1f, m.amps / PEAK_AMPS) : 0.85f;
        // Heat lags the work: warmest just after the cruise.
        float heat = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * (t - 8f) / (LOOP_MS / 1000f));
        m.mosfetTempC = 38 + Math.round(13f * heat);
        m.motorTempC = 57 + Math.round(21f * heat);
        m.packTempC = 29 + Math.round(5f * heat);
        // A percent every two minutes, starting again from 76 after forty.
        m.soc = 76 - (int) ((ms / 120000L) % 20);
        return m;
    }

    // ---- Battery: an ANT BMS, current protocol ---------------------------------

    /** The status reply a 20-cell ANT BMS would send for this moment. */
    static byte[] batteryFrame(Moment m) {
        int temps = 2;
        int base = 34 + 2 * CELLS + 2 * temps;
        int len = base + 78 - 6;
        byte[] f = new byte[10 + len];
        f[0] = 0x7E;
        f[1] = (byte) 0xA1;
        f[2] = 0x11;
        f[5] = (byte) len;
        f[6] = 0x05;
        f[7] = 0x01;
        f[8] = (byte) temps;
        f[9] = (byte) CELLS;
        // Cells sit within a few millivolts of each other; number 7 runs a little low.
        int mean = Math.round(m.volts * 1000f / CELLS);
        int min = Integer.MAX_VALUE;
        int max = 0;
        int minCell = 1;
        int maxCell = 1;
        int sum = 0;
        for (int i = 0; i < CELLS; i++) {
            int mv = mean + (i == 6 ? -14 : (i * 7) % 9 - 4);
            le16(f, 34 + 2 * i, mv);
            sum += mv;
            if (mv < min) {
                min = mv;
                minCell = i + 1;
            }
            if (mv > max) {
                max = mv;
                maxCell = i + 1;
            }
        }
        le16(f, 34 + 2 * CELLS, m.packTempC);
        le16(f, 34 + 2 * CELLS + 2, m.packTempC + 1);
        le16(f, base, m.packTempC + 4);                    // MOSFET
        le16(f, base + 2, m.packTempC + 2);                // balancer
        le16(f, base + 4, Math.round(m.volts * 100f));
        le16(f, base + 6, Math.round(m.amps * 10f));
        le16(f, base + 8, m.soc);
        le16(f, base + 10, 98);                            // state of health
        f[base + 12] = 1;                                  // discharge on
        f[base + 13] = 1;                                  // charge on
        le32(f, base + 16, Math.round(CAPACITY_AH * 1e6f));
        le32(f, base + 20, Math.round(CAPACITY_AH * 1e6f * m.soc / 100f));
        le32(f, base + 28, Math.round(m.volts * m.amps));
        le16(f, base + 40, max);
        le16(f, base + 42, maxCell);
        le16(f, base + 44, min);
        le16(f, base + 46, minCell);
        le16(f, base + 48, max - min);
        le16(f, base + 50, sum / CELLS);
        le16(f, 6 + len, AntBms.crc16(f, 1, 5 + len));
        f[8 + len] = (byte) 0xAA;
        f[9 + len] = 0x55;
        return f;
    }

    private static void le16(byte[] f, int at, int v) {
        f[at] = (byte) v;
        f[at + 1] = (byte) (v >> 8);
    }

    private static void le32(byte[] f, int at, int v) {
        le16(f, at, v);
        le16(f, at + 2, v >> 16);
    }

    static final class Battery extends BleLink {
        private final Listener listener;
        private final Handler main = new Handler(Looper.getMainLooper());
        private boolean ready;

        Battery(Context c, UUID characteristic, Listener listener) {
            super(c, characteristic, listener);
            this.listener = listener;
        }

        @Override
        boolean isReady() {
            return ready;
        }

        @Override
        boolean isOpen() {
            return ready;
        }

        @Override
        void open(String name) {
            ready = true;
            listener.onReady();
        }

        @Override
        void close() {
            ready = false;
        }

        @Override
        void write(byte[] request) {
            if (!ready || !Arrays.equals(request, AntBms.REQUEST_NEW)) return;
            // The answer comes a moment after the question, never inside it.
            main.post(() -> {
                byte[] reply = batteryFrame(at(SystemClock.elapsedRealtime()));
                // Twenty bytes to a notification, as the real module sends it.
                for (int i = 0; ready && i < reply.length; i += 20) {
                    listener.onData(Arrays.copyOfRange(reply, i, Math.min(reply.length, i + 20)));
                }
            });
        }
    }

    // ---- Controller: a Fardriver -----------------------------------------------

    /**
     * Sets the live words of a controller's memory for this moment. The
     * settings around them are left as they are.
     */
    static void applyLive(int[] ram, Moment m) {
        // Forward, and the controller's own "wheel is rolling" flag, while it moves.
        ram[0xE2] = (ram[0xE2] & ~0x23) | (m.rpm > 0 ? 0x21 : 0);
        ram[0xE3] = m.braking ? 0x8000 : 0;
        int poles = ram[0x14] & 0xFF;
        ram[0xE5] = (poles >= 16 ? m.rpm * poles / 4 : m.rpm) & 0xFFFF;
        ram[0xE8] = Math.round(m.volts * 10f) & 0xFFFF;
        ram[0xEA] = Math.round(m.amps * 4f) & 0xFFFF;
        int raw = (int) Math.round(Math.pow(m.phaseAmps / 1.953125, 2));
        ram[0xF0] = ((raw >> 16) & 0xFF) | (raw & 0xFF00);
        ram[0xF1] = (ram[0xF1] & 0xFF00) | (raw & 0xFF);
        ram[0x82] = Math.round(m.throttleVolts * 100f);
        ram[0xDB] = m.mosfetTempC;
        ram[0xF4] = m.motorTempC;
        ram[0xF5] = (ram[0xF5] & 0xFF) | (m.soc << 8);
    }

    /** One 16-byte frame of the controller's stream: six words and their check. */
    static byte[] frame(int[] ram, int id) {
        byte[] f = new byte[16];
        f[0] = (byte) 0xAA;
        f[1] = (byte) (0x80 | id);
        for (int k = 0; k < 6; k++) {
            int w = ram[(Fardriver.BLOCK[id] + k) & 0xFF];
            f[2 + 2 * k] = (byte) w;
            f[3 + 2 * k] = (byte) (w >> 8);
        }
        int crc = Fardriver.crc16(f, 0, 14);
        f[14] = (byte) crc;
        f[15] = (byte) (crc >> 8);
        return f;
    }

    /** The memory of the real controller the demo starts from. */
    static int[] memory() {
        Fardriver real = new Fardriver();
        for (String frame : MEMORY) real.feed(hex(frame));
        int[] ram = new int[256];
        for (int a = 0; a < 256; a++) ram[a] = real.word(a);
        return ram;
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    static final class Controller extends BleLink {
        private final Listener listener;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final int[] ram = memory();
        private final int[] flash = ram.clone();
        private boolean ready;
        private boolean streaming;
        private int next;

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!ready || !streaming) return;
                applyLive(ram, at(SystemClock.elapsedRealtime()));
                // Seven frames a tick takes the whole memory round in 1.6 s,
                // with the live blocks coming by about five times a second.
                for (int i = 0; ready && i < 7; i++) {
                    listener.onData(frame(ram, next));
                    next = (next + 1) % Fardriver.BLOCK.length;
                }
                main.postDelayed(this, TICK_MS);
            }
        };

        Controller(Context c, UUID characteristic, Listener listener) {
            super(c, characteristic, listener);
            this.listener = listener;
        }

        @Override
        boolean isReady() {
            return ready;
        }

        @Override
        boolean isOpen() {
            return ready;
        }

        @Override
        void open(String name) {
            ready = true;
            streaming = false;
            listener.onReady();
        }

        @Override
        void close() {
            ready = false;
            streaming = false;
            main.removeCallbacks(tick);
        }

        /** Obeys the same frames a real controller does: hello, write a word, save. */
        @Override
        void write(byte[] f) {
            if (!ready) return;
            if (Arrays.equals(f, Fardriver.HELLO)) {
                if (!streaming) {
                    streaming = true;
                    main.removeCallbacks(tick);
                    main.post(tick);
                }
                return;
            }
            if (f.length < 8 || (f[0] & 0xFF) != 0xAA) return;
            int n = (f[1] & 0xFF) - 0xC0 - 4;
            if (n < 2 || f.length != 4 + n + 2 || f[2] != f[3]) return;
            int crc = Fardriver.crc16(f, 0, f.length - 2);
            if ((crc & 0xFF) != (f[f.length - 2] & 0xFF)
                    || (crc >> 8) != (f[f.length - 1] & 0xFF)) {
                return;
            }
            int addr = f[2] & 0xFF;
            for (int k = 0; k < n / 2; k++) {
                ram[(addr + k) & 0xFF] = (f[4 + 2 * k] & 0xFF) | ((f[5 + 2 * k] & 0xFF) << 8);
            }
            if (addr == 0xA0 && ram[0xA0] == 0x0488) {      // the Save command
                ram[0x05]++;
                System.arraycopy(ram, 0, flash, 0, 256);
            }
        }
    }

    /** Two full memory cycles streamed by a real controller (firmware HA80), at rest. */
    static final String[] MEMORY = {
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
}
