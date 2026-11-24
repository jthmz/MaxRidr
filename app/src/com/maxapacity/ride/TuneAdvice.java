package com.maxapacity.ride;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the changes out of an adviser's answer, so they need not be entered
 * by hand. The {@link TuneBrief} asks whoever answers it to finish with one
 * line per change:
 *
 *   SET Battery current limit = 100 A
 *
 * Only those lines are read, and each is held to exactly what the Tune
 * screen would let a rider enter: a setting the app knows, a value in the
 * range this controller allows. Anything else is left out and said. What is
 * read is only ever loaded as changes to look over; nothing is sent to the
 * bike from here.
 */
final class TuneAdvice {

    private TuneAdvice() {
    }

    /** What an answer came to. */
    static final class Read {
        /** How many SET lines the answer held. */
        int lines;
        /** The changes that can be loaded, by setting key. */
        final Map<String, Integer> changes = new LinkedHashMap<>();
        /** Lines that were left out, each with why, ready to show. */
        final List<String> leftOut = new ArrayList<>();
    }

    /** A SET line, however it was dressed up: a bullet, bold marks or code ticks around it. */
    private static final Pattern LINE = Pattern.compile(
            "(?im)^[\\s>*\\-•`]*SET\\s+(.+?)\\s*(?:=|:|\\bto\\b)\\s*(.+?)[\\s*`.]*$");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:[.,]\\d+)?");

    /**
     * @param ceiling the highest value each setting may be given on this
     *        controller, as {@link Bike#ceiling} works it out
     */
    static Read read(String answer, Fardriver memory, ToIntFunction<Fardriver.Param> ceiling) {
        Read out = new Read();
        if (answer == null) return out;
        Matcher m = LINE.matcher(answer);
        while (m.find()) {
            out.lines++;
            String name = tidy(m.group(1));
            String value = tidy(m.group(2));
            Fardriver.Param p = named(name);
            if (p == null) {
                out.leftOut.add("\"" + name + "\" is not a setting this app changes");
                continue;
            }
            int top = p.top(ceiling.applyAsInt(p));
            Integer raw = p.options != null ? choice(p, value) : amount(p, value);
            if (raw == null) {
                out.leftOut.add(p.name + ": \"" + value + "\" could not be read"
                        + (p.options == null ? " as a number" : ". It can be set to "
                        + choices(p, top)));
            } else if (raw < p.min || raw > top) {
                out.leftOut.add(p.name + " " + p.format(raw) + " is outside what this bike"
                        + " allows (" + (p.options != null ? choices(p, top)
                        : p.format(p.min) + " to " + p.format(top)) + ")");
            } else if (raw != memory.raw(p)) {
                out.changes.put(p.key, raw);
            }
        }
        return out;
    }

    /** The setting an adviser named, by the name the brief gave it. */
    private static Fardriver.Param named(String name) {
        for (Fardriver.Param p : Fardriver.PARAMS) {
            if (p.name.equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private static Integer choice(Fardriver.Param p, String value) {
        for (int i = 0; i < p.options.length; i++) {
            if (p.options[i].equalsIgnoreCase(value)) return i;
        }
        return null;
    }

    /** "100 A", "45%", "2,800 rpm", "64": the number, turned into the setting's own units. */
    private static Integer amount(Fardriver.Param p, String value) {
        // A thousands comma is not a decimal point: "2,800" is 2800.
        Matcher m = NUMBER.matcher(value.replaceAll("(\\d),(\\d{3})(?!\\d)", "$1$2"));
        if (!m.find()) return null;
        String number = m.group();
        // "130 A to 100 A" could mean either: one value, or it is not taken.
        if (m.find()) return null;
        try {
            return (int) Math.round(Double.parseDouble(number.replace(',', '.')) / p.scale);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String choices(Fardriver.Param p, int top) {
        StringBuilder sb = new StringBuilder();
        for (int i = p.min; i <= Math.min(top, p.options.length - 1); i++) {
            sb.append(sb.length() == 0 ? "" : " or ").append(p.options[i]);
        }
        return sb.toString();
    }

    /** Without the marks an answer may wrap a name or a value in. */
    private static String tidy(String s) {
        return s.replaceAll("[*`\"]", "").replaceAll("\\s+", " ").trim();
    }
}
