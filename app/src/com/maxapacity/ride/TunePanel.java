package com.maxapacity.ride;

import android.content.Context;
import android.content.Intent;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller tuning. Change values here first; nothing reaches the bike until
 * "Try on bike", which makes them live but temporary. From there either Save
 * keeps them or Undo puts the old values back. Switching the bike off also
 * discards anything that was not saved.
 *
 * Two shortcuts sit above the settings for the rider who does not know what
 * to change. "Tunes" loads a whole {@link TunePlan} tune, made to measure for
 * this controller or taken from one of MAXAPACITY's own bikes, as changes to
 * look over. "Tune ideas" writes the bike up as a {@link TuneBrief} to send to an
 * AI app or a tuner. Neither sends anything to the bike: what they produce
 * is tried and kept here like any other change.
 */
final class TunePanel extends Panel {

    private final Host host;
    private final LinearLayout content;
    private final LinearLayout actions;
    /** Edited but not yet sent: setting key to raw value. */
    private final Map<String, Integer> draft = new LinkedHashMap<>();
    private final Runnable bikeChanged = this::refresh;
    private String shown = "";
    /** Room for the widest value, "12500 rpm", and for a choice such as "On brake". */
    private static final int VALUE_DP = 88;
    /**
     * The values of a loaded tune that goes above what the controller came
     * with, or null. They are sent together or not at all, so their rows
     * cannot be stepped while it is loaded.
     */
    private Map<String, Integer> whole;
    private String wholeName = "";
    /** The rider has just read what regen on throttle release does, and chose to go on. */
    private boolean releaseWarned;

    TunePanel(Context context, Host host) {
        super(context, "Tune");
        this.host = host;
        content = addScrollContent();
        actions = Ui.row(context);
        LayoutParams lp = Ui.matchWrap();
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(12);
        addView(actions, lp);
    }

    @Override
    protected void onOpen() {
        shown = "";
        Bike.get(getContext()).addListener(bikeChanged);
        refresh();
    }

    @Override
    protected void onClose() {
        Bike.get(getContext()).removeListener(bikeChanged);
    }

    /** Leaving with live, unsaved values would strand them: ask first. */
    @Override
    void close() {
        final Bike bike = Bike.get(getContext());
        if (isOpen() && bike.hasUnsavedTry()) {
            new Sheet(host.frame(), "Keep these settings?",
                    "The new values are live on the bike but not saved. Save keeps them."
                            + " Undo puts the previous values back.")
                    .action("Undo", Ui.SECONDARY, true, () -> bike.revert((ok, m) -> {
                        host.say(m);
                        if (ok) super.close();
                    }))
                    .action("Save", Ui.PRIMARY, true, () -> bike.save((ok, m) -> {
                        host.say(m);
                        if (ok) super.close();
                    }))
                    .show();
            return;
        }
        draft.clear();
        whole = null;
        super.close();
    }

    private void refresh() {
        final Context c = getContext();
        final Bike bike = Bike.get(c);
        final Fardriver memory = bike.controllerMemory();
        final Fardriver.Live live = bike.controller();
        int state = bike.tuneState();
        String locked = live == null ? (bike.controllerLinked()
                ? "Waiting for the controller. Switch the bike on"
                : "Link a controller on the Bike screen first") : memory.tuningBlocker();

        // Settings stream in continuously; rebuild only when something shown changed.
        StringBuilder key = new StringBuilder().append(state).append('|').append(locked)
                .append('|').append(draft).append('|').append(live != null && live.rpm != 0);
        if (locked == null) {
            for (Fardriver.Param p : Fardriver.PARAMS) key.append(memory.raw(p)).append(',');
        }
        if (key.toString().equals(shown)) return;
        shown = key.toString();

        boolean busy = state == Bike.APPLYING || state == Bike.SAVING || state == Bike.REVERTING;
        if (bike.demo() && locked == null && state != Bike.TRYING && !busy && draft.isEmpty()) {
            status.set("DEMO", Ui.WARN);
        } else if (locked != null) status.set(live == null ? "OFFLINE" : "LOCKED", Ui.TEXT_3);
        else if (state == Bike.TRYING) status.set("LIVE, NOT SAVED", Ui.WARN);
        else if (busy) status.set("WORKING", Ui.WARN);
        else status.set(draft.isEmpty() ? "IN SYNC" : draft.size() + " CHANGED",
                    draft.isEmpty() ? Ui.OK : Ui.ACCENT);

        content.removeAllViews();
        actions.removeAllViews();
        if (locked != null) {
            boolean reading = "Reading settings from the controller".equals(locked);
            if (reading) {
                for (int i = 0; i < 4; i++) {
                    content.addView(Ui.skeleton(c, 88), Ui.top(c, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(88)), i == 0 ? 0 : 12));
                }
            } else {
                content.addView(Ui.emptyState(c, live == null ? Icons.BLUETOOTH : Icons.LOCK,
                        live == null ? "Controller not connected" : "Tuning is locked", locked,
                        live == null ? "Open bike" : null, Ui.SECONDARY, v -> {
                            super.close();
                            host.showBike();
                        }));
            }
            return;
        }

        if (state == Bike.TRYING) {
            note(Icons.TRIANGLE_ALERT, Ui.WARN, "These values are live on the bike but not"
                    + " saved. Ride it, then Save to keep them or Undo to go back. Switching"
                    + " the bike off also discards them.");
        } else {
            // Said on every firmware: it is about the bike, not about this app.
            String wheel = " Keep the driven wheel off the ground: on some bikes neutral does"
                    + " not stop the motor while a phone is connected.";
            note(Icons.INFO, Ui.TEXT_2, Fardriver.verifiedFirmware(memory.firmware())
                    ? "Changes are tried on the bike first and kept only when you save." + wheel
                    : "Firmware " + memory.firmware() + " has not been tested with this app."
                    + " Each change is read back to confirm it, and nothing is kept until you"
                    + " save." + wheel);
        }

        content.addView(shortcuts(), Ui.top(c, Ui.matchWrap(), 12));

        String group = "";
        LinearLayout card = null;
        for (final Fardriver.Param p : Fardriver.PARAMS) {
            if (!p.group.equals(group)) {
                group = p.group;
                section(content, group);
                card = Ui.column(c);
                card.setBackground(Ui.panel(c, Ui.SURFACE, Ui.HAIRLINE, 16));
                content.addView(card, Ui.matchWrap());
            }
            addParam(card, p, memory.raw(p), bike.ceiling(p), !busy);
        }

        if (state == Bike.TRYING && draft.isEmpty()) {
            LinearLayout undo = Ui.button(c, "UNDO", Icons.ROTATE_CCW, Ui.SECONDARY);
            undo.setOnClickListener(v -> bike.revert((ok, m) -> host.say(m)));
            actions.addView(undo, new LinearLayout.LayoutParams(0, dp(48), 1f));
            LinearLayout save = Ui.button(c, "SAVE TO CONTROLLER", Icons.SAVE, Ui.PRIMARY);
            save.setOnClickListener(v -> confirmSave());
            actions.addView(save, Ui.left(c, new LinearLayout.LayoutParams(0, dp(48), 2f), 8));
        } else if (!draft.isEmpty()) {
            LinearLayout discard = Ui.button(c, "DISCARD", null, Ui.SECONDARY);
            discard.setOnClickListener(v -> {
                final Map<String, Integer> was = new LinkedHashMap<>(draft);
                final Map<String, Integer> wasWhole = whole;
                draft.clear();
                whole = null;
                refresh();
                host.say("Changes discarded", "UNDO", () -> {
                    draft.putAll(was);
                    whole = wasWhole;
                    refresh();
                });
            });
            actions.addView(discard, new LinearLayout.LayoutParams(0, dp(48), 1f));
            LinearLayout go = Ui.button(c, busy ? "SENDING" : "TRY ON BIKE", Icons.ZAP,
                    Ui.PRIMARY);
            go.setAlpha(busy ? 0.5f : 1f);
            if (!busy) go.setOnClickListener(v -> tryOnBike());
            actions.addView(go, Ui.left(c, new LinearLayout.LayoutParams(0, dp(48), 2f), 8));
        }
    }

    private void tryOnBike() {
        final Bike bike = Bike.get(getContext());
        String blocked = bike.writeBlocker();
        if (blocked != null) {
            host.say(blocked);
            return;
        }
        final String fw = bike.controllerMemory().firmware();
        final String ack = "bike_fw_ack_" + fw;
        if (!Fardriver.verifiedFirmware(fw) && !Units.prefs(getContext()).getBoolean(ack, false)) {
            new Sheet(host.frame(), "Untested firmware",
                    "This app's tuning has been proven on firmware HA80. Yours is " + fw
                            + ". The settings read sensibly, each change is read back to"
                            + " confirm it, and nothing is saved until you choose to, but it"
                            + " has not been tried on this exact firmware before.")
                    .action("Cancel", Ui.SECONDARY, true, null)
                    .action("Continue", Ui.PRIMARY, true, () -> {
                        Units.prefs(getContext()).edit().putBoolean(ack, true).apply();
                        tryOnBike();
                    })
                    .show();
            return;
        }
        Integer regenMode = draft.get("regen_mode");
        if (regenMode != null && regenMode == 3 && !releaseWarned) {
            new Sheet(host.frame(), "Regen when you roll off",
                    "With regen on throttle release, the motor brakes the rear wheel every time"
                            + " you close the throttle, as hard as Regen strength is set. On"
                            + " loose ground or a steep descent that can slide the rear tyre."
                            + " Start with a low strength and try it slowly, somewhere open.")
                    .action("Cancel", Ui.SECONDARY, true, null)
                    .action("Try it", Ui.PRIMARY, true, () -> {
                        releaseWarned = true;
                        tryOnBike();
                    })
                    .show();
            return;
        }
        releaseWarned = false;
        final Map<String, Integer> sending = new LinkedHashMap<>(draft);
        // A tune that goes above what the controller came with is let through as itself
        // only: the bike link takes these values together or refuses them all.
        if (whole != null) bike.allowWhole(whole);
        bike.tryValues(sending, (ok, message) -> {
            if (ok) {
                draft.clear();
                whole = null;
            }
            shown = "";
            refresh();
            host.say(message);
        });
        shown = "";
        refresh();
    }

    private void confirmSave() {
        final Bike bike = Bike.get(getContext());
        new Sheet(host.frame(), "Save to the controller?",
                "The values you are trying become the bike's saved settings and survive a"
                        + " power cycle. The previous settings stay in a backup file on this"
                        + " phone.")
                .action("Not yet", Ui.SECONDARY, true, null)
                .action("Save", Ui.PRIMARY, true, () -> bike.save((ok, m) -> {
                    shown = "";
                    refresh();
                    host.say(m);
                }))
                .show();
    }

    // ---- Tunes and tune ideas ------------------------------------------------

    /** Two ways to a tune without knowing what to change: load a whole one, or ask for ideas. */
    private View shortcuts() {
        Context c = getContext();
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.panel(c, Ui.SURFACE, Ui.HAIRLINE, 16));
        card.addView(shortcut(Icons.SLIDERS_HORIZONTAL, "Tunes",
                "Load a whole tune made for this bike", v -> showTunes()), Ui.matchWrap());
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        line.leftMargin = dp(16);
        card.addView(Ui.divider(c), line);
        card.addView(shortcut(Icons.SPARKLES, "Tune ideas",
                "Get suggestions written for this bike", v -> showIdeas()), Ui.matchWrap());
        return card;
    }

    private View shortcut(String icon, String name, String detail, View.OnClickListener tap) {
        Context c = getContext();
        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.ghost(c, 16));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.addView(Ui.icon(c, icon, 18, Ui.TEXT_2));
        LinearLayout text = Ui.column(c);
        text.addView(Ui.body(c, name, Ui.TEXT));
        text.addView(Ui.even(Ui.small(c, detail, Ui.TEXT_3)));
        row.addView(text, Ui.left(c, Ui.weighted(), 12));
        ImageView go = Ui.icon(c, Icons.CHEVRON_RIGHT, 18, Ui.TEXT_3);
        ((LinearLayout.LayoutParams) go.getLayoutParams()).leftMargin = dp(8);
        row.addView(go);
        row.setOnClickListener(tap);
        Ui.press(row);
        return row;
    }

    /**
     * Whole tunes to load: three made to measure for this controller, and the
     * ones from MAXAPACITY's own bikes. Picking one only fills in the changes;
     * the rider still looks them over and taps Try on bike.
     */
    void showTunes() {
        final Context c = getContext();
        final Bike bike = Bike.get(c);
        if (!TuneBrief.ready(bike)) {
            host.say("The controller's settings have not been read yet");
            return;
        }
        Fardriver.Live live = bike.controller();
        final int pack = Units.prefs(c).getInt(TunePlan.PACK_AMPS, 0);
        Fardriver.Param line = Fardriver.param("line");
        int drawNow = Math.round(bike.controllerMemory().raw(line) * line.scale);
        Sheet sheet = new Sheet(host.frame(), "Tunes",
                "Three made to measure for this controller, from calm to everything it came"
                        + " set to. A tune loads as changes to look over, and nothing reaches"
                        + " the bike until you tap Try on bike."
                        + (pack > 0 && drawNow > pack ? " This controller is set to draw "
                        + drawNow + " A now, more than the " + pack + " A you gave for the"
                        + " battery." : ""));
        for (final TunePlan.Tune t : TunePlan.measured(bike::ceiling,
                live == null ? 0f : live.volts, pack)) {
            sheet.item(t.icon, t.name, t.detail, false, () -> load(t));
        }
        sheet.item(Icons.BATTERY, "Battery rating", pack > 0
                ? pack + " A. These tunes stay at or under it"
                : "Not set. Add it and these tunes stay under it", false,
                this::editPackRating);
        sheet.item(Icons.WAREHOUSE, "MAXAPACITY bikes", "Our build tunes and stock settings",
                false, this::showOurBikes);
        sheet.show();
    }

    private void showOurBikes() {
        Sheet sheet = new Sheet(host.frame(), "MAXAPACITY bikes",
                "Each belongs to one exact bike. On a different controller or battery they"
                        + " are not safe to copy, and the app will not load one that asks for"
                        + " more than this controller came with.");
        for (final TunePlan.Tune t : TunePlan.ourBikes()) {
            sheet.item(t.icon, t.name, t.detail, false, () -> confirmOurs(t));
        }
        sheet.action("Back", Ui.SECONDARY, true, this::showTunes).show();
    }

    /** A tune from one of our bikes says which bike it is for before it is loaded. */
    private void confirmOurs(final TunePlan.Tune t) {
        new Sheet(host.frame(), t.name, t.detail.replace("\n", ". ") + ". " + t.note)
                .action("Back", Ui.SECONDARY, true, this::showOurBikes)
                .action("Load", Ui.PRIMARY, true, () -> load(t))
                .show();
    }

    /** Fills in a tune's changes for looking over. What was being edited can be had back. */
    private void load(TunePlan.Tune t) {
        Bike bike = Bike.get(getContext());
        TunePlan.Loaded loaded = TunePlan.load(t, bike.controllerMemory(), bike::ceiling);
        if (loaded.refused != null) {
            host.say(loaded.refused);
            return;
        }
        if (loaded.changes.isEmpty()) {
            host.say("This bike is already set to " + t.name);
            return;
        }
        final Map<String, Integer> was = new LinkedHashMap<>(draft);
        final Map<String, Integer> wasWhole = whole;
        draft.clear();
        draft.putAll(loaded.changes);
        whole = loaded.whole;
        wholeName = t.name;
        refresh();
        int n = loaded.changes.size();
        host.say(t.name + " loaded: " + (n == 1 ? "1 change" : n + " changes") + " to look over"
                + (loaded.note == null ? "" : ". " + loaded.note), "UNDO", () -> {
                    draft.clear();
                    draft.putAll(was);
                    whole = wasWhole;
                    refresh();
                });
    }

    private void editPackRating() {
        final Context c = getContext();
        final EditText amps = Ui.input(c, "Amps, such as 220", false);
        amps.setInputType(InputType.TYPE_CLASS_NUMBER);
        amps.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4)});
        int pack = Units.prefs(c).getInt(TunePlan.PACK_AMPS, 0);
        if (pack > 0) amps.setText(String.valueOf(pack));
        new Sheet(host.frame(), "Battery rating",
                "What your battery can give continuously, in amps. It is on the pack's label"
                        + " or from whoever sold it; the app cannot read it from the battery."
                        + " Leave it empty if you do not know, and the tunes go by the"
                        + " controller alone.")
                .view(amps)
                .action("Back", Ui.SECONDARY, true, this::showTunes)
                .action("Save", Ui.PRIMARY, true, () -> {
                    int given = 0;
                    try {
                        given = Integer.parseInt(amps.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        // Empty: no rating.
                    }
                    Units.prefs(c).edit().putInt(TunePlan.PACK_AMPS, Math.max(0, given)).apply();
                    showTunes();
                })
                .show();
    }

    /**
     * What does the rider want from the bike? Each answer goes out as a brief
     * for an AI app or a tuner to work from. Nothing here reaches the bike.
     */
    void showIdeas() {
        final Context c = getContext();
        if (!TuneBrief.ready(Bike.get(c))) {
            host.say("The controller's settings have not been read yet");
            return;
        }
        Sheet sheet = new Sheet(host.frame(), "Tune ideas",
                "Pick what you want. The app writes up this bike's settings and limits for you"
                        + " to send to an AI app on this phone, or to a tuner. Nothing on the"
                        + " bike changes.");
        for (final TuneBrief.Goal g : TuneBrief.GOALS) {
            sheet.item(g.icon, g.name, g.detail, false, () -> send(g.ask));
        }
        sheet.item(Icons.ELLIPSIS, "Something else", "Say it in your own words", false,
                this::ownWords);
        sheet.item(Icons.DOWNLOAD, "Paste the answer",
                "Copy the reply, then tap here to load its changes", false, this::pasteAnswer);
        String about = Units.prefs(c).getString(TuneBrief.ABOUT, "");
        sheet.item(Icons.LIST, "About my bike", about.isEmpty()
                ? "Add your bike, gearing and weight for better answers" : shorten(about),
                false, this::editAbout);
        sheet.show();
    }

    /**
     * Loads the changes out of an answer the rider has copied, to look over.
     * Each is held to what this bike allows; what is not is left out and said.
     */
    private void pasteAnswer() {
        Context c = getContext();
        Bike bike = Bike.get(c);
        CharSequence copied = null;
        try {
            android.content.ClipboardManager clipboard =
                    c.getSystemService(android.content.ClipboardManager.class);
            android.content.ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) copied = clip.getItemAt(0).coerceToText(c);
        } catch (RuntimeException e) {
            // Nothing to read.
        }
        load(TuneAdvice.read(copied == null ? null : copied.toString(), bike.controllerMemory(),
                bike::ceiling));
    }

    /** What an answer came to: loaded to look over, with anything left out said plainly. */
    void load(TuneAdvice.Read read) {
        if (read.lines == 0) {
            host.say("Nothing to load in what was copied. Copy the whole reply, down to its"
                    + " SET lines");
            return;
        }
        String leftOut = read.leftOut.isEmpty() ? "" : ". Left out: " + read.leftOut.get(0)
                + (read.leftOut.size() > 1 ? ", and " + (read.leftOut.size() - 1) + " more" : "");
        if (read.changes.isEmpty()) {
            host.say(read.leftOut.isEmpty() ? "This bike is already set the way the answer says"
                    : "Nothing could be loaded" + leftOut);
            return;
        }
        final Map<String, Integer> was = new LinkedHashMap<>(draft);
        draft.clear();
        draft.putAll(read.changes);
        whole = null;
        refresh();
        int n = read.changes.size();
        host.say((n == 1 ? "1 change" : n + " changes") + " loaded to look over" + leftOut,
                "UNDO", () -> {
                    draft.clear();
                    draft.putAll(was);
                    refresh();
                });
    }

    /** Hands the brief to whichever app the rider picks: an AI, a message to a tuner, or Copy. */
    private void send(String goal) {
        host.launch(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "Tune ideas for my bike")
                .putExtra(Intent.EXTRA_SUBJECT, "Tune ideas for my bike")
                .putExtra(Intent.EXTRA_TEXT, TuneBrief.build(getContext(), goal)), null));
    }

    private void ownWords() {
        final EditText goal = prose("What do you want from the bike?");
        new Sheet(host.frame(), "In your own words",
                "For example: less wheelspin on wet roots, or keep up with a friend on fire"
                        + " roads.")
                .view(goal)
                .action("Back", Ui.SECONDARY, true, this::showIdeas)
                .action("Send", Ui.PRIMARY, false, () -> {
                    String wanted = goal.getText().toString().trim();
                    if (wanted.isEmpty()) {
                        host.say("Say what you want first");
                        return;
                    }
                    Sheet.dismissCurrent();
                    send(wanted);
                })
                .show();
    }

    private void editAbout() {
        final Context c = getContext();
        final EditText about = prose("Bike, battery, gearing, your weight, where you ride");
        about.setText(Units.prefs(c).getString(TuneBrief.ABOUT, ""));
        new Sheet(host.frame(), "About my bike",
                "The app reads the battery and the controller, but not the rest of the bike or"
                        + " who rides it. A line or two here goes into every brief. It stays on"
                        + " this phone until you send one.")
                .view(about)
                .action("Back", Ui.SECONDARY, true, this::showIdeas)
                .action("Save", Ui.PRIMARY, true, () -> {
                    Units.prefs(c).edit().putString(TuneBrief.ABOUT,
                            about.getText().toString().trim()).apply();
                    showIdeas();
                })
                .show();
    }

    /** A field for a sentence or two, rather than a code or a name. */
    private EditText prose(String hint) {
        EditText e = Ui.input(getContext(), hint, false);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setSingleLine(false);
        e.setMinLines(2);
        e.setMaxLines(4);
        e.setGravity(Gravity.TOP);
        e.setPadding(dp(16), dp(12), dp(16), dp(12));
        return e;
    }

    /** One line of a longer note, for the row that leads to it. */
    private static String shorten(String note) {
        String line = note.replace('\n', ' ');
        return line.length() <= 60 ? line : line.substring(0, 57).trim() + "...";
    }

    private void note(String icon, int color, String text) {
        Context c = getContext();
        LinearLayout row = Ui.row(c);
        row.setGravity(android.view.Gravity.TOP);
        row.setBackground(Ui.panel(c, Ui.SURFACE, color == Ui.WARN ? 0x66FBBF24 : Ui.HAIRLINE,
                16));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.addView(Ui.icon(c, icon, 18, color));
        row.addView(Ui.small(c, text, Ui.TEXT_2), Ui.left(c, Ui.weighted(), 12));
        content.addView(row, Ui.matchWrap());
    }

    private void addParam(LinearLayout card, final Fardriver.Param p, final int onBike,
            final int ceiling, boolean enabled) {
        final Context c = getContext();
        if (card.getChildCount() > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
            lp.leftMargin = dp(16);
            card.addView(Ui.divider(c), lp);
        }
        final boolean edited = draft.containsKey(p.key);
        final int value = edited ? draft.get(p.key) : onBike;
        // Part of a tune that only goes in whole: shown, but not stepped on its own.
        final boolean held = whole != null && whole.containsKey(p.key);
        if (held) enabled = false;
        // What is on the bike can always be shown and gone back to, even where
        // it lies outside what this app would choose.
        final int max = Math.max(onBike, Math.min(p.top(ceiling), above(p)));
        final int min = Math.min(onBike, Math.max(p.min, below(p)));

        // Name and stepper on one line; the explanation gets the full width below.
        LinearLayout block = Ui.column(c);
        block.setPadding(dp(16), dp(8), dp(8), dp(14));
        LinearLayout row = Ui.row(c);
        // A long name on a narrow phone takes a second line rather than losing its end.
        TextView n = Ui.body(c, p.name, Ui.TEXT);
        n.setMaxLines(2);
        n.setEllipsize(TextUtils.TruncateAt.END);
        // Room of its own, which only shows when the name takes two lines: the row then
        // grows, and the explanation underneath does not crowd the second line.
        n.setPadding(0, dp(6), 0, dp(10));
        row.addView(n, Ui.weighted());

        ImageView minus = stepper(Icons.CHEVRON_LEFT, "Lower " + p.name, enabled && value > min);
        TextView v = Ui.value(c, "", Ui.T_BODY);
        v.setText(p.options != null ? p.format(value)
                : Ui.withUnit(p.number(value), p.unit, (float) Ui.T_LABEL / Ui.T_BODY));
        v.setContentDescription(p.format(value));
        v.setGravity(android.view.Gravity.CENTER);
        v.setTextColor(edited ? Ui.ACCENT : Ui.TEXT);
        if (p.options != null) {
            // A choice is a word or three: it may take two lines, a number never does.
            v.setTypeface(Ui.sansBold);
            v.setSingleLine(false);
            v.setMaxLines(2);
        }
        ImageView plus = stepper(Icons.CHEVRON_RIGHT, "Raise " + p.name, enabled && value < max);
        minus.setOnClickListener(x -> set(p, Math.max(min, value - p.step), onBike));
        plus.setOnClickListener(x -> set(p, Math.min(max, value + p.step), onBike));
        row.addView(minus, Ui.left(c, new LinearLayout.LayoutParams(dp(40), dp(48)), 8));
        // One width for every row, so the arrows line up down the screen.
        row.addView(v, new LinearLayout.LayoutParams(dp(VALUE_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(plus, new LinearLayout.LayoutParams(dp(40), dp(48)));
        block.addView(row, Ui.matchWrap());

        String help = p.help;
        if (p.cappedAtFactory && value >= max) {
            help += ". At this controller's own limit of " + p.format(max);
        } else if (p.key.equals("high_rpm") && value >= max) {
            help += ". That is " + p.format(max);
        }
        TextView h = Ui.small(c, !edited ? help : "On the bike now: " + p.format(onBike)
                + (held ? ". Part of " + wholeName + ", which only goes in whole" : ""),
                edited ? Ui.ACCENT : Ui.TEXT_3);
        LinearLayout.LayoutParams hlp = Ui.matchWrap();
        hlp.rightMargin = dp(8);
        hlp.topMargin = -dp(4); // the stepper's touch target pads the row above
        block.addView(h, hlp);
        card.addView(block, Ui.matchWrap());
    }

    /** The value a setting would have if the draft were sent. */
    private int proposed(String key) {
        Integer drafted = draft.get(key);
        return drafted != null ? drafted
                : Bike.get(getContext()).controllerMemory().raw(Fardriver.param(key));
    }

    /** A setting may not go above the one that must stay above it. */
    private int above(Fardriver.Param p) {
        switch (p.key) {
            case "low_rpm":
                return proposed("mid_rpm");
            case "mid_rpm":
                return proposed("high_rpm");
            case "regen":
                return proposed("regen_max");
            default:
                return Integer.MAX_VALUE;
        }
    }

    /** Nor below the one that must stay below it. */
    private int below(Fardriver.Param p) {
        switch (p.key) {
            case "mid_rpm":
                return proposed("low_rpm");
            case "high_rpm":
                return proposed("mid_rpm");
            case "regen_max":
                return proposed("regen");
            default:
                return 0;
        }
    }

    private ImageView stepper(String icon, String description, boolean enabled) {
        Context c = getContext();
        ImageView b = new ImageView(c);
        b.setImageDrawable(Icons.of(c, icon, enabled ? Ui.TEXT : Ui.HAIRLINE_HI));
        b.setPadding(dp(10), dp(14), dp(10), dp(14));
        b.setContentDescription(description);
        b.setEnabled(enabled);
        if (enabled) {
            b.setBackground(Ui.ghost(c, 10));
            Ui.press(b);
        }
        return b;
    }

    private void set(Fardriver.Param p, int raw, int onBike) {
        if (raw == onBike) draft.remove(p.key);
        else draft.put(p.key, raw);
        refresh();
    }
}
