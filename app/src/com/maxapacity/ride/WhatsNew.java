package com.maxapacity.ride;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What an update brought, in the rider's words. The app updates itself
 * quietly, so the first time the home screen opens on a version with
 * something new in it, a sheet says what. Settings keeps the earlier notes.
 *
 * TO ADD A NOTE: put a new one at the top of {@link #NOTES}, numbered one
 * above the note below it. Say what the rider can now do and where to find
 * it. Not every build needs one: a build with no new note says nothing.
 *
 * The notes are numbered on their own rather than by version, because a
 * build's version number is only given to it when it is built. The phone
 * remembers the newest note it has shown, and the sheet is headed with the
 * version the phone now has.
 */
final class WhatsNew {

    private WhatsNew() {
    }

    /** The number of the newest note this phone has shown. */
    static final String SEEN = "whats_new_seen";

    /** A phone many updates behind is told about the latest few; Settings has the rest. */
    private static final int MOST_AT_ONCE = 3;

    /** One thing that changed: a heading and a sentence or two. */
    static final class Item {
        final String icon;
        final String title;
        final String detail;

        Item(String icon, String title, String detail) {
            this.icon = icon;
            this.title = title;
            this.detail = detail;
        }
    }

    /** What one update brought. */
    static final class Note {
        final int id;
        /** Month and year, to head the note in the list of earlier ones. */
        final String when;
        final Item[] items;

        Note(int id, String when, Item... items) {
            this.id = id;
            this.when = when;
            this.items = items;
        }
    }

    /** Newest first. */
    static final Note[] NOTES = {
        new Note(8, "November 2026",
                new Item(Icons.BATTERY_MEDIUM, "Battery warnings and protections",
                        "Bike shows ANT warnings separately from active protections, and battery health"
                                + " when reported. Open battery options to see the detected protocol.")),
        new Note(7, "November 2026",
                new Item(Icons.MONITOR, "A home Deck for your rides",
                        "Open Deck to set up a home phone or connect your riding phone. Pair once"
                                + " on Wi-Fi. Your home Deck shows when each ride finishes arriving.")),
        new Note(6, "November 2026",
                new Item(Icons.REFRESH_CW, "Regen when you roll off",
                        "Regen mode can now be set to On throttle release, so the motor slows"
                                + " the bike when the throttle closes. The app warns before it"
                                + " is tried. Tunes has it for the Drax Bee build and the XE"
                                + " Pro R."),
                new Item(Icons.ROCKET, "Apex 140 A",
                        "For the DRAX Apex on its own controller only: the owner-tested step"
                                + " above stock, all four currents together. Tunes, MAXAPACITY"
                                + " bikes.")),
        new Note(5, "November 2026",
                new Item(Icons.ZAP, "What the bike did on each ride",
                        "With the bike linked, a ride keeps its peak power, the energy it used,"
                                + " how far the battery sagged and how hot things ran. It is on"
                                + " the ride's page, and Tune ideas sends the last ride's"
                                + " figures along, so the advice is about what happened.")),
        new Note(4, "November 2026",
                new Item(Icons.ROTATE_CCW, "Arctic Leopard XE Pro R",
                        "The app can now tune a stock XE Pro R. On the Tune screen, Tunes, then"
                                + " MAXAPACITY bikes has its settings as delivered, to put a"
                                + " changed bike back."),
                new Item(Icons.GAUGE, "Top speed keeps to what the bike came with",
                        "A speed mode can be lowered and brought back up, but never set faster"
                                + " than the controller was when the app first met it."),
                new Item(Icons.CIRCLE_STOP, "Gentler regen",
                        "Tune ideas has a new wish, for a brake that grabs less."),
                new Item(Icons.SLIDERS_HORIZONTAL, "A tidier Tune screen",
                        "The arrows line up, units read at a glance, and the fields you type"
                                + " in are full size again.")),
        new Note(3, "November 2026",
                new Item(Icons.MAP, "A tour of the home screen",
                        "A minute on where everything is, for a new rider or a refresher."
                                + " Settings, Take the tour."),
                new Item(Icons.BELL, "What's new",
                        "After an update that brings something new, a note like this one says"
                                + " what. Settings keeps the earlier ones."),
                new Item(Icons.EXTERNAL_LINK, "Help and the MAXAPACITY website",
                        "Settings ends with a button to the website and About and help, the"
                                + " app's own page of setup and answers.")),
        new Note(2, "November 2026",
                new Item(Icons.SLIDERS_HORIZONTAL, "Whole tunes in one tap",
                        "On the Tune screen, Tunes loads Calm, Balanced or Aggressive, made to"
                                + " measure for your controller, or a tune published for a"
                                + " MAXAPACITY build. Each loads as changes to look over and try"
                                + " first."),
                new Item(Icons.SPARKLES, "Tune ideas",
                        "The app writes up your bike's settings and limits to send to an AI app"
                                + " or a tuner. Paste the reply back and it loads as changes to"
                                + " look over."),
                new Item(Icons.ACTIVITY, "Health Connect",
                        "On Android 14 and later, rides can be shared with Oura, Google Health"
                                + " and other health apps. Settings, Health Connect."),
                new Item(Icons.MONITOR, "The whole Deck in the app",
                        "Full Deck on the Deck screen opens every tab and control the Car Thing"
                                + " shows.")),
        new Note(1, "November 2026",
                new Item(Icons.BLUETOOTH, "Demo bike",
                        "No bike nearby? The Bike screen has a demo bike that fills every"
                                + " battery and controller screen with made-up numbers."),
                new Item(Icons.TRIANGLE_ALERT, "Crash alert",
                        "Optional. After a hard hit followed by the bike not moving, the phone"
                                + " texts your location to a contact. Settings, Crash alert."),
                new Item(Icons.ROUTE, "Lean angle",
                        "Each ride records its furthest lean left and right."),
                new Item(Icons.LAPTOP, "Rides go home to the Deck",
                        "The phone finds your MAXAPACITY Deck by itself and sends each ride to"
                                + " your Mac.")),
    };

    static int latest() {
        return NOTES[0].id;
    }

    /**
     * Called as the home screen starts. The first time on a given phone it
     * settles who is holding it. Someone who has just installed the app has
     * no news to catch up on, and is still to be offered the tour. Someone
     * whose app updated itself from a build without notes knows their way
     * round, and to them the newest note is news.
     */
    static void arrive(Context c) {
        SharedPreferences p = Units.prefs(c);
        if (p.contains(SEEN)) return;
        if (freshInstall(c)) {
            p.edit().putInt(SEEN, latest()).apply();
        } else {
            p.edit().putInt(SEEN, NOTES.length > 1 ? NOTES[1].id : 0)
                    .putBoolean(Tour.OFFERED, true).apply();
        }
    }

    /** True when the app on this phone has never been updated: it is as it was first installed. */
    private static boolean freshInstall(Context c) {
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return info.lastUpdateTime <= info.firstInstallTime;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    /** The notes this phone has not shown yet, newest first. Empty when there is no news. */
    static List<Note> unseen(Context c) {
        int seen = Units.prefs(c).getInt(SEEN, latest());
        List<Note> news = new ArrayList<>();
        for (Note n : NOTES) {
            if (n.id > seen && news.size() < MOST_AT_ONCE) news.add(n);
        }
        return news;
    }

    static void markSeen(Context c) {
        Units.prefs(c).edit().putInt(SEEN, latest()).apply();
    }

    /** What the update that just arrived brought. */
    static Sheet news(Host host, List<Note> notes) {
        Context c = host.frame().getContext();
        return new Sheet(host.frame(), "What's new in " + Updater.versionName(c), null)
                .view(list(c, notes, false))
                .action("Got it", Ui.PRIMARY, true, null);
    }

    /** Every note, newest first, each under the month it came in. */
    static Sheet history(Host host) {
        Context c = host.frame().getContext();
        List<Note> all = new ArrayList<>();
        for (Note n : NOTES) all.add(n);
        return new Sheet(host.frame(), "What's new",
                "This phone has version " + Updater.versionName(c) + ". The newest changes are"
                        + " first.")
                .view(list(c, all, true))
                .action("Done", Ui.SECONDARY, true, null);
    }

    private static LinearLayout list(Context c, List<Note> notes, boolean dated) {
        LinearLayout list = Ui.column(c);
        String heading = null;
        for (Note n : notes) {
            // Notes from the same month sit under one heading.
            if (dated && !n.when.equals(heading)) {
                heading = n.when;
                TextView h = Ui.label(c, heading.toUpperCase(Locale.US));
                h.setPadding(0, list.getChildCount() == 0 ? 0 : Ui.dp(c, 16), 0, Ui.dp(c, 4));
                list.addView(h, Ui.matchWrap());
            }
            for (Item item : n.items) list.addView(row(c, item), Ui.matchWrap());
        }
        return list;
    }

    /** Icon, heading and the sentence under it. Nothing to tap: it is there to be read. */
    private static LinearLayout row(Context c, Item item) {
        LinearLayout row = Ui.row(c);
        // Against the heading, not the middle of a three-line paragraph.
        row.setGravity(Gravity.TOP);
        row.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
        row.addView(Ui.icon(c, item.icon, 20, Ui.TEXT_2));
        LinearLayout text = Ui.column(c);
        text.addView(Ui.body(c, item.title, Ui.TEXT));
        text.addView(Ui.small(c, item.detail, Ui.TEXT_3), Ui.top(c, Ui.matchWrap(), 2));
        row.addView(text, Ui.left(c, Ui.weighted(), 16));
        return row;
    }
}
