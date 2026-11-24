package com.maxapacity.ride;

import java.util.Locale;

/**
 * What the bike did on one ride: the most it drew, how far the pack sagged,
 * how hot it ran, and the energy that went out and came back. It is kept
 * while a ride records with the bike linked, saved with the ride, shown on
 * the ride's page, and written into the tune brief, so a tune is judged by
 * what the bike did and not by how it felt.
 *
 * Current and voltage come from the controller when it is linked, since it
 * reports several times a second, and from the battery otherwise. No Android
 * types, so it runs on a desktop JVM.
 */
final class BikeLog {

    /** A longer silence than this is a dropout, not riding: no energy is counted across it. */
    private static final long GAP_MS = 3000;
    /** Below this current the pack is at rest, and its voltage is what a sag is measured from. */
    private static final float REST_AMPS = 5f;
    /** Fewer readings than this say nothing worth keeping. */
    private static final int ENOUGH = 10;
    private static final int NONE = Integer.MIN_VALUE;

    private int readings;
    private float peakW;
    private float peakAmps;
    private float regenAmps;
    private float restVolts;
    private float lowVolts;
    private float sagVolts;
    private int motorC = NONE;
    private int controllerC = NONE;
    private int batteryC = NONE;
    private double usedWs;
    private double regenWs;
    private int socStart = -1;
    private int socEnd = -1;
    private float lowCellV;
    private long lastMs;
    private float lastW;
    private boolean flowing;

    void reset() {
        readings = 0;
        peakW = 0;
        peakAmps = 0;
        regenAmps = 0;
        restVolts = 0;
        lowVolts = 0;
        sagVolts = 0;
        motorC = NONE;
        controllerC = NONE;
        batteryC = NONE;
        usedWs = 0;
        regenWs = 0;
        socStart = -1;
        socEnd = -1;
        lowCellV = 0;
        lastMs = 0;
        lastW = 0;
        flowing = false;
    }

    /**
     * A new reading from the bike.
     *
     * @param battery the latest battery reading, or null
     * @param controller the latest controller reading, or null
     * @param fromController true when it was the controller that just reported
     * @param nowMs a clock that only runs forward, in milliseconds
     */
    void add(AntBms.Data battery, Fardriver.Live controller, boolean fromController, long nowMs) {
        if (fromController) {
            if (controller == null) return;
            flow(controller.volts, controller.amps, nowMs);
            if (controller.motorTempC != NONE) motorC = Math.max(motorC, controller.motorTempC);
            if (controller.mosfetTempC != NONE) {
                controllerC = Math.max(controllerC, controller.mosfetTempC);
            }
            return;
        }
        if (battery == null) return;
        // With no controller to go by, the battery's own slower reading stands in.
        if (controller == null) flow(battery.volts, battery.amps, nowMs);
        if (battery.soc >= 0 && battery.soc <= 100) {
            if (socStart < 0) socStart = battery.soc;
            socEnd = battery.soc;
        }
        if (battery.temps.length > 0) batteryC = Math.max(batteryC, battery.maxTempC());
        if (battery.minCellV > 0 && (lowCellV == 0 || battery.minCellV < lowCellV)) {
            lowCellV = battery.minCellV;
        }
    }

    /** One reading of what is flowing: positive amps out of the pack, negative back into it. */
    private void flow(float volts, float amps, long nowMs) {
        if (volts <= 0) return;
        readings++;
        float w = volts * amps;
        if (w > peakW) peakW = w;
        if (amps > peakAmps) peakAmps = amps;
        if (-amps > regenAmps) regenAmps = -amps;
        if (Math.abs(amps) < REST_AMPS) restVolts = volts;
        if (lowVolts == 0 || volts < lowVolts) lowVolts = volts;
        // A sag is a drop under load from where the pack last rested, not the slow fall of a
        // pack running down.
        if (restVolts > 0 && amps >= REST_AMPS && restVolts - volts > sagVolts) {
            sagVolts = restVolts - volts;
        }
        if (flowing && nowMs > lastMs && nowMs - lastMs <= GAP_MS) {
            double ws = (w + lastW) / 2.0 * (nowMs - lastMs) / 1000.0;
            if (ws >= 0) usedWs += ws;
            else regenWs -= ws;
        }
        lastMs = nowMs;
        lastW = w;
        flowing = true;
    }

    /** What the ride came to, or null if the bike said too little to keep. */
    Summary summary() {
        if (readings < ENOUGH) return null;
        Summary s = new Summary();
        s.peakKw = peakW / 1000f;
        s.peakAmps = peakAmps;
        s.regenAmps = regenAmps;
        s.lowVolts = lowVolts;
        s.sagVolts = sagVolts;
        s.motorC = motorC;
        s.controllerC = controllerC;
        s.batteryC = batteryC;
        s.usedWh = (float) (usedWs / 3600.0);
        s.regenWh = (float) (regenWs / 3600.0);
        s.socStart = socStart;
        s.socEnd = socEnd;
        s.lowCellV = lowCellV;
        return s;
    }

    /** One ride's figures. A temperature nobody reported is {@link #UNKNOWN}. */
    static final class Summary {
        static final int UNKNOWN = NONE;

        float peakKw;
        /** Most current drawn from the battery, in amps. */
        float peakAmps;
        /** Most current sent back into the battery, in amps; 0 if none was. */
        float regenAmps;
        float lowVolts;
        /** Deepest drop under load from where the pack last rested, in volts. */
        float sagVolts;
        int motorC = UNKNOWN;
        int controllerC = UNKNOWN;
        int batteryC = UNKNOWN;
        float usedWh;
        float regenWh;
        /** Charge when the ride began and ended, in percent; -1 with no battery linked. */
        int socStart = -1;
        int socEnd = -1;
        /** Lowest single cell seen, in volts; 0 with no battery linked. */
        float lowCellV;

        /** Energy used for each mile or kilometre, or 0 for a ride too short to say. */
        float whPer(double distanceM, boolean metric) {
            double units = distanceM / (metric ? 1000.0 : 1609.344);
            return units < 0.1 ? 0f : (float) ((usedWh - regenWh) / units);
        }

        /**
         * The ride in plain lines, for whoever is asked to advise on a tune.
         *
         * @param lineLimitAmps the battery current limit the controller is set to
         *        now, in amps, or 0 if unknown: a peak right at it says the limit
         *        is what held the bike back
         */
        String brief(double distanceM, boolean metric, float lineLimitAmps) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "- Most power drawn: %.1f kW, at %.0f A from the"
                    + " battery", peakKw, peakAmps));
            if (lineLimitAmps > 0 && peakAmps >= lineLimitAmps * 0.97f) {
                sb.append(", which is the battery current limit");
            }
            sb.append(".\n");
            sb.append(String.format(Locale.US, "- Lowest pack voltage: %.1f V", lowVolts));
            if (sagVolts >= 0.5f) {
                sb.append(String.format(Locale.US, ". Its deepest drop under load was %.1f V",
                        sagVolts));
            }
            sb.append(".\n");
            String hot = "";
            if (motorC != UNKNOWN) hot += "motor " + motorC + " C";
            if (controllerC != UNKNOWN) hot += (hot.isEmpty() ? "" : ", ") + "controller "
                    + controllerC + " C";
            if (batteryC != UNKNOWN) hot += (hot.isEmpty() ? "" : ", ") + "battery "
                    + batteryC + " C";
            if (!hot.isEmpty()) sb.append("- Hottest: ").append(hot).append(".\n");
            if (usedWh >= 1f) {
                sb.append(String.format(Locale.US, "- Energy used: %.0f Wh", usedWh));
                float per = whPer(distanceM, metric);
                if (per > 0) {
                    sb.append(String.format(Locale.US, ", %.0f Wh per %s after regen", per,
                            metric ? "km" : "mile"));
                }
                sb.append(".\n");
            }
            if (regenAmps >= 1f) {
                sb.append(String.format(Locale.US, "- Strongest regen: %.0f A into the battery",
                        regenAmps));
                if (regenWh >= 1f) {
                    sb.append(String.format(Locale.US, ". It gave back %.0f Wh", regenWh));
                }
                sb.append(".\n");
            }
            if (socStart >= 0 && socEnd >= 0) {
                sb.append("- Battery went from ").append(socStart).append("% to ").append(socEnd)
                        .append("%");
                if (lowCellV > 0) {
                    sb.append(String.format(Locale.US, ". Lowest cell %.2f V", lowCellV));
                }
                sb.append(".\n");
            }
            return sb.toString();
        }
    }
}
