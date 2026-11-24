package com.maxapacity.ride;

import android.content.Context;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * This bike, written up for anyone who can give tune advice: an AI app the
 * rider already has, or a tuner. It carries what the app reads (battery,
 * controller, every setting with the range the Tune screen will accept) and
 * what the rider wants from the bike.
 *
 * Writing a brief only reads. Whatever advice comes back still has to be
 * entered on the Tune screen, where every change is checked, tried on the bike
 * first and kept only when saved.
 */
final class TuneBrief {

    private TuneBrief() {
    }

    /** Where the rider's own note about the bike is kept. */
    static final String ABOUT = "tune_about";

    /** Something riders commonly want, offered as one tap. */
    static final class Goal {
        final String icon;
        final String name;
        final String detail;
        /** How it is put in the brief. */
        final String ask;

        Goal(String icon, String name, String detail, String ask) {
            this.icon = icon;
            this.name = name;
            this.detail = detail;
            this.ask = ask;
        }
    }

    static final Goal[] GOALS = {
            new Goal(Icons.BATTERY_FULL, "More range", "Go further on a charge",
                    "More range. I want to go further on a charge without the bike feeling"
                            + " flat."),
            new Goal(Icons.HAND, "Smoother throttle", "Easier to control at low speed",
                    "A smoother throttle. I want the bike easier to control at low speed and on"
                            + " technical trail."),
            new Goal(Icons.ROCKET, "More punch", "Stronger pull, inside this bike's limits",
                    "More punch. I want a stronger pull off the line and out of corners, inside"
                            + " the limits listed below."),
            new Goal(Icons.SHIELD_CHECK, "New rider", "Calmer power and a lower top speed",
                    "A set-up for a new or young rider: calm power and a lower top speed."),
            new Goal(Icons.REFRESH_CW, "Stronger regen", "More slowing when you brake",
                    "Stronger regen braking. I want the motor to slow the bike more when I"
                            + " brake."),
    };

    /** True when the controller's settings have been read and can be written up. */
    static boolean ready(Bike bike) {
        return bike.controller() != null && bike.controllerMemory().tuningBlocker() == null;
    }

    /** The brief for the bike linked to this phone. Call only when {@link #ready}. */
    static String build(Context c, String goal) {
        Bike bike = Bike.get(c);
        return build(goal, Units.prefs(c).getString(ABOUT, ""), bike.demo(), Units.metric(c),
                bike.battery(), bike.controller(), bike.controllerMemory(), bike::ceiling,
                Units.prefs(c).getInt(TunePlan.PACK_AMPS, 0));
    }

    /**
     * @param about the rider's own note about the bike and themselves, or empty
     * @param battery the latest battery reading, or null when none is linked
     * @param live the latest controller reading, or null
     * @param ceiling the highest value each setting may be given on this
     *        controller, as {@link Bike#ceiling} works it out
     */
    static String build(String goal, String about, boolean demo, boolean metric,
            AntBms.Data battery, Fardriver.Live live, Fardriver memory,
            ToIntFunction<Fardriver.Param> ceiling) {
        return build(goal, about, demo, metric, battery, live, memory, ceiling, 0);
    }

    /**
     * @param packAmps what the rider says the battery gives continuously, in
     *        amps, or 0 if they have not said. An adviser is told to stay under it
     */
    static String build(String goal, String about, boolean demo, boolean metric,
            AntBms.Data battery, Fardriver.Live live, Fardriver memory,
            ToIntFunction<Fardriver.Param> ceiling, int packAmps) {
        StringBuilder sb = new StringBuilder();
        if (demo) {
            sb.append("Note: this comes from the app's demo bike. The readings are made up and"
                    + " no real bike is connected.\n\n");
        }
        sb.append("I ride an electric dirt bike and tune its Fardriver controller with the"
                + " MAXAPACITY Ride app. Help me choose settings.\n\n");
        sb.append("WHAT I WANT\n").append(goal.trim()).append("\n\n");
        if (about != null && !about.trim().isEmpty()) {
            sb.append("ABOUT MY BIKE AND ME\n").append(about.trim()).append("\n\n");
        }
        sb.append("HOW TO ANSWER\n"
                + "- Suggest changes only to the settings listed below, and keep every new value"
                + " inside the range shown for it. The app refuses anything outside.\n"
                + "- Give up to five changes, most useful first. For each one: the setting, its"
                + " value now, the value to try, and one plain sentence on what I will feel.\n"
                + "- I try one or two changes at a time and test ride in between, so tell me"
                + " which to start with.\n"
                + "- If what I want needs something these settings cannot do, such as gearing,"
                + " tyres or a different battery, say so plainly.\n"
                + "- If the readings below show a problem to sort out first, start there.\n"
                + "- Use plain words. I am not an engineer.\n"
                + "- Finish with the changes on lines of their own, each written exactly like"
                + " this, using the setting names below: SET Battery current limit = 100 A."
                + " The app reads those lines so I do not have to type them in.\n\n");
        sb.append("WHAT THE APP READS FROM THE BIKE\n").append(readings(battery, live, memory));
        if (packAmps > 0) {
            sb.append("- By my own figure the battery is rated for ").append(packAmps)
                    .append(" A continuous. Do not suggest a battery current limit above"
                            + " that.\n");
        }
        sb.append("\nSETTINGS NOW, AND THE RANGE THE APP ALLOWS ON THIS BIKE\n")
                .append(settings(memory, ceiling));
        sb.append("\nRULES THE APP ENFORCES\n").append(rules(memory));
        sb.append("\nI use ").append(metric ? "metric units (km/h, km)" : "US units (mph, miles)")
                .append(".\n");
        return sb.toString();
    }

    /**
     * The settings and the rules, for the app's own Copilot. Empty until the
     * settings have been read.
     */
    static String forCopilot(Bike bike) {
        if (!ready(bike)) return "";
        Fardriver memory = bike.controllerMemory();
        return " Tune settings on the controller now, each with the range the app's Tune screen"
                + " will accept:\n" + settings(memory, bike::ceiling)
                + "Rules the app enforces:\n" + rules(memory)
                + "When asked about tuning, suggest values inside those ranges, each as the"
                + " value now and the value to try, one or two changes at a time, in up to five"
                + " short lines. Changes are made on the app's Tune screen, tried on the bike"
                + " first and kept only when saved.";
    }

    // ---- The parts ----------------------------------------------------------

    /** What the battery and controller say about themselves right now. */
    static String readings(AntBms.Data b, Fardriver.Live l, Fardriver memory) {
        StringBuilder sb = new StringBuilder("- Controller: Fardriver");
        String firmware = memory.firmware();
        if (!firmware.isEmpty()) sb.append(", firmware ").append(firmware);
        List<String> faults = l == null ? Collections.<String>emptyList() : l.faultNames();
        sb.append(faults.isEmpty() ? ". No faults showing.\n"
                : ". Faults showing: " + join(faults) + ".\n");
        if (l != null) {
            String temps = l.mosfetTempC == Integer.MIN_VALUE ? ""
                    : "controller " + l.mosfetTempC + " C";
            if (l.motorTempC != Integer.MIN_VALUE) {
                temps += (temps.isEmpty() ? "" : ", ") + "motor " + l.motorTempC + " C";
            }
            if (!temps.isEmpty()) sb.append("- Temperatures now: ").append(temps).append(".\n");
        }
        if (b == null) {
            sb.append("- Battery: none linked to the app, so nothing is known about the pack.\n");
        } else {
            sb.append(String.format(Locale.US, "- Battery: %d cells in series, %.1f V now, %d%%"
                    + " charged", b.cellVolts.length, b.volts, b.soc));
            if (b.capacityAh > 0) sb.append(", ").append(number(b.capacityAh)).append(" Ah pack");
            sb.append(". ");
            if (b.cellVolts.length > 1) {
                sb.append("Cells within ").append(Math.round(b.deltaCellV() * 1000))
                        .append(" mV of each other. ");
            }
            if (b.temps.length > 0) sb.append("Hottest sensor ").append(b.maxTempC()).append(" C. ");
            if (b.soh >= 0) sb.append("Health ").append(b.soh).append("%. ");
            sb.append(b.hasProblem() ? "Protections active: " + join(b.problems) + ".\n"
                    : "No protections active.\n");
        }
        Fardriver.Param line = Fardriver.param("line");
        float volts = l != null && l.volts > 0 ? l.volts : b != null ? b.volts : 0f;
        float amps = memory.raw(line) * line.scale;
        if (volts > 0 && amps > 0) {
            sb.append(String.format(Locale.US, "- Peak power the battery current limit allows at"
                    + " this voltage: about %.1f kW.\n", volts * amps / 1000f));
        }
        return sb.toString();
    }

    /** Every setting: its value now, what the Tune screen can set it to, and what it does. */
    static String settings(Fardriver memory, ToIntFunction<Fardriver.Param> ceiling) {
        StringBuilder sb = new StringBuilder();
        String group = "";
        for (Fardriver.Param p : Fardriver.PARAMS) {
            if (!p.group.equals(group)) {
                group = p.group;
                sb.append(group.charAt(0)).append(group.substring(1).toLowerCase(Locale.US))
                        .append('\n');
            }
            int now = memory.raw(p);
            int top = ceiling.applyAsInt(p);
            sb.append("- ").append(p.name).append(": ").append(p.format(now)).append(". ");
            if (p.options != null) {
                sb.append("The app can set: ");
                int last = Math.min(Math.min(p.max, top), p.options.length - 1);
                for (int i = p.min; i <= last; i++) {
                    sb.append(i == p.min ? "" : ", ").append(p.options[i]);
                }
                sb.append(". ");
            } else {
                sb.append("Range ").append(p.format(lowest(p, now))).append(" to ")
                        .append(p.format(highest(p, now, top)));
                // A percentage moves in steps too fine to be worth naming.
                if (!"%".equals(p.unit)) sb.append(", in steps of ").append(p.format(p.step));
                sb.append(". ");
            }
            sb.append(p.help).append('.');
            if (p.cappedAtFactory) {
                sb.append(" The app never raises this above where the controller stood when it"
                        + " was first linked.");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** The lowest value the Tune screen offers for a setting that now holds {@code onBike}. */
    static int lowest(Fardriver.Param p, int onBike) {
        return Math.min(onBike, p.min);
    }

    /** The highest: what is on the bike can always be kept, even above what the app would choose. */
    static int highest(Fardriver.Param p, int onBike, int ceiling) {
        return Math.max(onBike, Math.min(p.max, ceiling));
    }

    /** What the app holds every change to, whoever suggested it. */
    static String rules(Fardriver memory) {
        StringBuilder sb = new StringBuilder(
                "- Low mode top speed stays at or below Mid, and Mid at or below High.\n");
        int limiter = memory.limiterRpm();
        if (limiter > 0) {
            sb.append("- High mode top speed never goes above the controller's own speed"
                    + " limiter, ").append(limiter).append(" rpm.\n");
        }
        sb.append("- Regen strength stays at or below Max regen strength.\n"
                + "- The two current limits can come down, but never go above where this"
                + " controller stood when the app first met it.\n"
                + "- The controller's short burst (boost) currents move with the two limits by"
                + " themselves, so a burst never draws more than a limit allows.\n"
                + "- Each change is tried on the bike first and only kept when I save it, so"
                + " anything that feels wrong can be undone.\n");
        return sb.toString();
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) sb.append(sb.length() == 0 ? "" : ", ").append(s);
        return sb.toString();
    }

    /** 40 for 40.0, 58.5 for 58.5. */
    private static String number(float v) {
        return Math.abs(v - Math.round(v)) < 0.05f ? String.valueOf(Math.round(v))
                : String.format(Locale.US, "%.1f", v);
    }
}
