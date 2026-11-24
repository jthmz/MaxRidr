package com.maxapacity.ride;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Whole tunes a rider can load in one tap: three made to measure for the
 * controller in hand, from calm to everything it came with, and the ones
 * from MAXAPACITY's own bikes: the tunes published for its builds and the
 * settings a stock bike was delivered with.
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
        /**
         * True for a tune that goes above what its controller came with. There
         * is one: it was proven on one controller, loads on that controller
         * only, and only whole.
         */
        final boolean whole;

        Tune(String id, String icon, String name, String detail, String note,
                Map<String, Integer> values) {
            this(id, icon, name, detail, note, values, false);
        }

        Tune(String id, String icon, String name, String detail, String note,
                Map<String, Integer> values, boolean whole) {
            this.id = id;
            this.icon = icon;
            this.name = name;
            this.detail = detail;
            this.note = note;
            this.values = values;
            this.whole = whole;
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
        /**
         * Every value of a tune that goes above what the controller came with,
         * or null. The bike link takes them only together, see
         * {@link Bike#allowWhole}.
         */
        Map<String, Integer> whole;
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
            // Two lines by design, so no row ends in a stray unit on a narrow phone.
            String detail = line.format(l) + " battery, " + phase.format(p) + " motor\n"
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

    // ---- MAXAPACITY's own bikes ----------------------------------------------

    /**
     * Tunes from MAXAPACITY's own bikes, each for one exact bike, with the
     * values and the cautions as MAXAPACITY has them on file. Nothing here is
     * worked out by this app: a number is either published in a MAXAPACITY
     * guide or was read off the bike.
     */
    static List<Tune> ourBikes() {
        String drax = "For the Drax Bee build: a Fardriver ND72680 with the Amorge 72V 30Ah"
                + " pack.";
        List<Tune> out = new ArrayList<>();
        out.add(build("drax-chill", Icons.HAND, "Drax Bee Chill", "180 A battery, 450 A motor",
                drax + " Inside that pack's listed 220 A continuous rating. Our guide gives it"
                        + " as a lower-current reference, not as a tested starting tune.",
                "line", 720, "phase", 1800));
        out.add(build("drax-daily", Icons.ROCKET, "Drax Bee Daily", "260 A battery, 600 A motor",
                drax + " It is what MAXAPACITY runs on that bike. 260 A is above that pack's"
                        + " listed 220 A continuous rating, and no test log yet backs its"
                        + " temperatures or how long a hard pull is safe.",
                "line", 1040, "phase", 2400));
        out.add(build("drax-regen", Icons.REFRESH_CW, "Drax Bee Trail regen",
                "Regen when you roll off\n8 A, 12 A at most",
                drax + " Trail tested there. The motor slows the bike whenever the throttle"
                        + " closes. FreeThrottle and the RPM regen map from our guide are set in"
                        + " the Fardriver app; this app sets the rest. A battery's BMS sets how"
                        + " much charge it will take, so never set regen above that, and try it"
                        + " at 70 to 80% charge rather than on a full pack.",
                "regen_mode", 3, "regen", 8, "regen_max", 12));
        // Read from the Fardriver app on one of MAXAPACITY's own bikes the day it was
        // delivered, 5 November 2026, before anything was changed. The app gave the
        // controller as BJ72V330AB_27_1KMA88. Mode power is stored in 128ths and the
        // official app rounds, so its 40% is 51 and its 70% is 90.
        out.add(build("xe-pro-r-factory", Icons.ROTATE_CCW, "XE Pro R Factory",
                "330 A battery, 699 A motor\n30 A regen on the brake",
                "For the Arctic Leopard XE Pro R on its stock Fardriver controller"
                        + " (BJ72V330AB). These are the settings MAXAPACITY read from one of its"
                        + " own bikes on the day it was delivered. Loading it puts back every"
                        + " setting this app can change and nothing else. A bike from another"
                        + " batch may have been delivered with different values. As delivered,"
                        + " regen comes on with the brake lever and is strong, so go easy on"
                        + " the lever.",
                "response", 0, "accel", 224, "decel", 224, "release", 0,
                "line", 1320, "phase", 2796,
                "low_rpm", 6000, "low_line", 51, "mid_rpm", 7000, "mid_line", 90,
                "high_rpm", 12500,
                "regen_mode", 2, "regen", 30, "regen_max", 50));
        out.add(build("xe-pro-r-regen", Icons.REFRESH_CW, "XE Pro R Off-throttle regen",
                "Regen when you roll off\n8 A, 12 A at most",
                "For the Arctic Leopard XE Pro R. Regen moves from the brake lever to the"
                        + " throttle: the motor slows the bike whenever you roll off. The"
                        + " currents are the mild ones MAXAPACITY trail tested on its Drax Bee"
                        + " build, far below the 30 A this bike came with and below what its"
                        + " own charger gives. It has not been ridden on the XE Pro R yet, so"
                        + " try it at low speed on open ground first. The bike's FreeThrottle"
                        + " and RPM regen map stay as they are; those are set in the Fardriver"
                        + " app.",
                "regen_mode", 3, "regen", 8, "regen_max", 12));
        // The only tune here that goes above what a controller came with. From our Apex guide:
        // an owner's bike, all four currents raised together, ridden daily; 145 A / 400 A
        // failed on the same controller, and its case says 150 A / 405 A.
        Map<String, Integer> apex = new LinkedHashMap<>();
        apex.put("boost_line", 560);
        apex.put("line", 560);
        apex.put("boost_phase", 1520);
        apex.put("phase", 1520);
        out.add(new Tune("apex-140", Icons.ROCKET, "Apex 140 A",
                "140 A battery, 380 A motor\nBurst allowances the same",
                "For the X-PRO DRAX Apex on its own controller, a Fardriver WC72V150 on firmware"
                        + " HA80, and nothing else. It raises all four currents together, above"
                        + " the 130 A and 350 A the bike came with. An Apex owner rides it daily"
                        + " without faults. MAXAPACITY has not tested it, and nobody has"
                        + " measured how hot it runs. 145 A and 400 A failed on the same"
                        + " controller with Error 09, so do not go past this. It is the one"
                        + " step above what a controller came with that this app allows.",
                apex, true));
        return out;
    }

    /**
     * True for the DRAX Apex's own controller, by what it says of itself: the
     * maker's code WC, produced for 72 V, second limits of 150 A and 405 A as
     * on its case, firmware HA80. It is the controller the one tune that goes
     * above stock was proven on.
     */
    static boolean apexController(Fardriver memory) {
        return "WC".equals(memory.customCode()) && memory.productionDecivolts() == 720
                && memory.secondLineRaw() == 600 && memory.secondPhaseRaw() == 1620
                && "HA80".equals(memory.firmware());
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
        if (t.whole) {
            if (!apexController(memory)) {
                out.refused = t.name + " is only for the DRAX Apex's own controller, a"
                        + " Fardriver WC72V150 on firmware HA80. This is not that controller,"
                        + " so the tune is not loaded";
                return out;
            }
            for (Map.Entry<String, Integer> e : t.values.entrySet()) {
                if (e.getValue() != memory.raw(Fardriver.param(e.getKey()))) {
                    out.changes.put(e.getKey(), e.getValue());
                }
            }
            out.whole = t.values;
            return out;
        }
        for (Map.Entry<String, Integer> e : t.values.entrySet()) {
            Fardriver.Param p = Fardriver.param(e.getKey());
            int want = e.getValue();
            int now = memory.raw(p);
            // A share is kept in 128ths and shown as a whole percent, so two neighbours
            // that read the same are the same setting.
            if (want == now || ("%".equals(p.unit) && p.format(want).equals(p.format(now)))) {
                continue;
            }
            int top = p.top(ceiling.applyAsInt(p));
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
        if (t.values.containsKey("regen") && !t.values.containsKey("regen_mode")
                && memory.raw(Fardriver.param("regen_mode")) == 1) {
            out.note = "Regen mode is Off on this bike, so regen does nothing until that is"
                    + " switched on";
        }
        return out;
    }
}
