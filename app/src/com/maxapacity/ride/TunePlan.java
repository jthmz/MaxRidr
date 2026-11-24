package com.maxapacity.ride;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Whole tunes a rider can load in one tap: three made to measure for the
 * controller in hand, from calm to everything it came with, and the tunes
 * MAXAPACITY has published for its own builds.
 *
 * Loading a tune only works out a set of changes. They are shown on the Tune
 * screen like any others and go through the same checks: nothing above what
 * this controller came with, tried on the bike first, kept only on Save.
 */
final class TunePlan {

    private TunePlan() {
    }

    /** Where the rider's own figure for the battery's continuous current is kept, in amps. */
    static final String PACK_AMPS = "tune_pack_amps";

    /** One tune on offer. */
    static final class Tune {
        final String id;
        final String icon;
        final String name;
        /** The numbers it sets, in words. */
        final String detail;
        /** What it is for and what to know before using it. Empty for a made-to-measure tune. */
        final String note;
        /** Setting key to raw value. */
        final Map<String, Integer> values;

        Tune(String id, String icon, String name, String detail, String note,
                Map<String, Integer> values) {
            this.id = id;
            this.icon = icon;
            this.name = name;
            this.detail = detail;
            this.note = note;
            this.values = values;
        }
    }

    /** What loading a tune comes to on the controller in hand. */
    static final class Loaded {
        /** The settings that would change. Empty when the bike is already set this way. */
        final Map<String, Integer> changes = new LinkedHashMap<>();
        /** Why the tune cannot be loaded on this bike, or null. */
        String refused;
        /** Something to tell the rider alongside, or null. */
        String note;
    }

    // ---- Made to measure ------------------------------------------------------

    static final int CALM = 0;
    static final int BALANCED = 1;
    static final int AGGRESSIVE = 2;

    /** Each level's share of the controller's own limits, and its throttle response. */
    private static final int[] SHARE = {60, 80, 100};
    private static final int[] RESPONSE = {2, 0, 1}; // Eco, Linear, Sport
    private static final String[] NAMES = {"Calm", "Balanced", "Aggressive"};
    private static final String[] ICONS = {Icons.HAND, Icons.SLIDERS_HORIZONTAL, Icons.ROCKET};

    /**
     * Three tunes worked out from what this controller came set to: a share
     * of its two current limits, and a throttle response to match. The top
     * one is everything the controller came with and nothing more.
     *
     * Only what the app is sure of is touched. Speed modes, regen and the
     * throttle steps are left as they are.
     *
     * @param ceiling the highest value each setting may be given on this
     *        controller, as {@link Bike#ceiling} works it out
     * @param volts the battery voltage now, for an idea of the power each
     *        level allows; 0 if unknown
     * @param packAmps what the rider says the battery gives continuously, in
     *        amps; 0 if they have not said. The tunes then stay at or under it
     */
    static List<Tune> measured(ToIntFunction<Fardriver.Param> ceiling, float volts,
            int packAmps) {
        Fardriver.Param line = Fardriver.param("line");
        Fardriver.Param phase = Fardriver.param("phase");
        Fardriver.Param response = Fardriver.param("response");
        int lineTop = ceiling.applyAsInt(line);
        int phaseTop = ceiling.applyAsInt(phase);
        if (packAmps > 0) {
            // A figure below the least the app will set cannot be met; the least is used.
            int rated = Math.max(line.min, Math.round(packAmps / line.scale));
            if (rated < lineTop) {
                // The motor limit comes down in step, keeping the balance the controller
                // came with, but not below the least the app will set.
                int inStep = (int) ((long) phaseTop * rated / lineTop);
                phaseTop = Math.min(phaseTop, Math.max(phase.min, inStep));
                lineTop = rated;
            }
        }
        List<Tune> out = new ArrayList<>();
        for (int level = CALM; level <= AGGRESSIVE; level++) {
            int l = share(lineTop, SHARE[level], line);
            int p = share(phaseTop, SHARE[level], phase);
            Map<String, Integer> values = new LinkedHashMap<>();
            values.put("line", l);
            values.put("phase", p);
            values.put("response", RESPONSE[level]);
            String detail = line.format(l) + " battery, " + phase.format(p) + " motor, "
                    + response.format(RESPONSE[level]) + " throttle";
            if (volts > 0) {
                detail += String.format(Locale.US, ", about %.1f kW",
                        volts * l * line.scale / 1000f);
            }
            out.add(new Tune("measured-" + level, ICONS[level], NAMES[level], detail, "",
                    values));
        }
        return out;
    }

    /** A share of a limit, on the setting's own steps, and never above the limit. */
    private static int share(int top, int percent, Fardriver.Param p) {
        if (percent >= 100) return top;
        int v = (int) ((long) top * percent / 100);
        v -= v % p.step;
        return Math.min(top, Math.max(p.min, v));
    }

    // ---- MAXAPACITY builds ----------------------------------------------------

    /**
     * The tunes MAXAPACITY has published for its own builds, with the values
     * and the cautions exactly as published. Each belongs to one bike.
     */
    static List<Tune> builds() {
        String drax = "For the Drax Bee build: a Fardriver ND72680 with the Amorge 72V 30Ah"
                + " pack.";
        List<Tune> out = new ArrayList<>();
        out.add(build("drax-chill", Icons.HAND, "Drax Bee Chill", "180 A battery, 450 A motor",
                drax + " Inside that pack's listed 220 A continuous rating.",
                "line", 720, "phase", 1800));
        out.add(build("drax-daily", Icons.ROCKET, "Drax Bee Daily", "260 A battery, 600 A motor",
                drax + " It is what MAXAPACITY runs on that bike. 260 A is above that pack's"
                        + " listed 220 A continuous rating.",
                "line", 1040, "phase", 2400));
        out.add(build("drax-regen", Icons.REFRESH_CW, "Drax Bee Trail regen",
                "8 A regen, 12 A at most",
                drax + " Trail tested there. A battery's BMS sets how much charge it will"
                        + " take, so never set regen above that, and try it at 70 to 80%"
                        + " charge rather than on a full pack.",
                "regen", 8, "regen_max", 12));
        return out;
    }

    private static Tune build(String id, String icon, String name, String detail, String note,
            Object... keysAndValues) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put((String) keysAndValues[i], (Integer) keysAndValues[i + 1]);
        }
        return new Tune(id, icon, name, detail, note, values);
    }

    // ---- Loading one -----------------------------------------------------------

    /**
     * What this tune would change on the controller in hand. A tune is
     * loaded whole or not at all: if any value is more than this controller
     * allows, it is a tune for a different bike and nothing is offered.
     */
    static Loaded load(Tune t, Fardriver memory, ToIntFunction<Fardriver.Param> ceiling) {
        Loaded out = new Loaded();
        for (Map.Entry<String, Integer> e : t.values.entrySet()) {
            Fardriver.Param p = Fardriver.param(e.getKey());
            int want = e.getValue();
            if (want == memory.raw(p)) continue;
            int top = Math.min(p.max, ceiling.applyAsInt(p));
            if (want > top || want < p.min) {
                out.changes.clear();
                String asks = t.name + " asks for " + p.format(want) + " as the "
                        + p.name.toLowerCase(Locale.US);
                out.refused = want < p.min ? asks + ", less than this app will set"
                        : p.cappedAtFactory ? asks + ". This controller came set to "
                        + p.format(top) + " and the app never goes above that, so the tune"
                        + " does not fit this bike"
                        : asks + ", more than this bike allows (" + p.format(top) + ")";
                return out;
            }
            out.changes.put(e.getKey(), want);
        }
        if (t.values.containsKey("regen") && memory.raw(Fardriver.param("regen_mode")) == 1) {
            out.note = "Regen mode is Off on this bike, so regen does nothing until that is"
                    + " switched on";
        }
        return out;
    }
}
