package com.maxapacity.ride;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
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
 */
final class TunePanel extends Panel {

    private final Host host;
    private final LinearLayout content;
    private final LinearLayout actions;
    /** Edited but not yet sent: setting key to raw value. */
    private final Map<String, Integer> draft = new LinkedHashMap<>();
    private final Runnable bikeChanged = this::refresh;
    private String shown = "";

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
        } else if (!Fardriver.verifiedFirmware(memory.firmware())) {
            note(Icons.INFO, Ui.TEXT_2, "Firmware " + memory.firmware() + " has not been"
                    + " tested with this app. Changes are checked by reading them back, and"
                    + " nothing is saved until you say so.");
        } else {
            note(Icons.INFO, Ui.TEXT_2, "Changes are tried on the bike first and only kept"
                    + " when you save. A backup of the current settings is stored on this"
                    + " phone before anything is changed.");
        }

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
                draft.clear();
                refresh();
                host.say("Changes discarded", "UNDO", () -> {
                    draft.putAll(was);
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
        final Map<String, Integer> sending = new LinkedHashMap<>(draft);
        bike.tryValues(sending, (ok, message) -> {
            if (ok) draft.clear();
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
        final int max = Math.min(p.max, ceiling);
        final int min = Math.min(p.floor(), onBike);

        // Name and stepper on one line; the explanation gets the full width below.
        LinearLayout block = Ui.column(c);
        block.setPadding(dp(16), dp(8), dp(8), dp(14));
        LinearLayout row = Ui.row(c);
        TextView n = Ui.body(c, p.name, Ui.TEXT);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(n, Ui.weighted());

        ImageView minus = stepper(Icons.CHEVRON_LEFT, "Lower " + p.name, enabled && value > min);
        TextView v = Ui.value(c, p.format(value), Ui.T_BODY);
        v.setGravity(android.view.Gravity.CENTER);
        v.setTextColor(edited ? Ui.ACCENT : Ui.TEXT);
        if (p.options != null) v.setTypeface(Ui.sansBold);
        ImageView plus = stepper(Icons.CHEVRON_RIGHT, "Raise " + p.name, enabled && value < max);
        minus.setOnClickListener(x -> set(p, Math.max(min, value - p.step), onBike));
        plus.setOnClickListener(x -> set(p, Math.min(max, value + p.step), onBike));
        row.addView(minus, Ui.left(c, new LinearLayout.LayoutParams(dp(40), dp(48)), 8));
        row.addView(v, new LinearLayout.LayoutParams(dp(p.options != null ? 112 : 76),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(plus, new LinearLayout.LayoutParams(dp(40), dp(48)));
        block.addView(row, Ui.matchWrap());

        String help = p.help;
        if (p.cappedAtFactory && value >= max) {
            help += ". At this controller's own limit of " + p.format(max);
        }
        TextView h = Ui.small(c, edited ? "On the bike now: " + p.format(onBike) : help,
                edited ? Ui.ACCENT : Ui.TEXT_3);
        LinearLayout.LayoutParams hlp = Ui.matchWrap();
        hlp.rightMargin = dp(8);
        hlp.topMargin = -dp(4); // the stepper's touch target pads the row above
        block.addView(h, hlp);
        card.addView(block, Ui.matchWrap());
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
