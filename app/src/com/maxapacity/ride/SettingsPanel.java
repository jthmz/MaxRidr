package com.maxapacity.ride;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Settings: riding behaviour, phone gestures, connected services and app
 * updates. Switches apply immediately; anything destructive asks first.
 */
final class SettingsPanel extends Panel {

    private final Host host;
    private final SharedPreferences prefs;
    private final LinearLayout content;
    private final Runnable updaterChanged = this::refresh;

    SettingsPanel(Context context, Host host) {
        super(context, "Settings");
        this.host = host;
        prefs = Units.prefs(context);
        content = addScrollContent();
    }

    @Override
    protected void onOpen() {
        Updater.addListener(updaterChanged);
        build();
    }

    @Override
    protected void onClose() {
        Updater.removeListener(updaterChanged);
    }

    /** Something shown here changed elsewhere. Nothing to do while closed. */
    void refresh() {
        if (isOpen()) build();
    }

    /** Rebuilds the list from current values. Cheap: a few dozen views. */
    private void build() {
        final Context c = getContext();
        content.removeAllViews();

        LinearLayout riding = group("RIDING");
        toggle(riding, Icons.PLAY, "Start rides automatically",
                "Records once you are moving", "auto_start", true, null);
        toggle(riding, Icons.CIRCLE_STOP, "End rides automatically",
                "After 10 minutes stopped", "auto_end", true, null);
        choice(riding, Icons.RULER, "Units", "MPH", "KM/H", Units.metric(c) ? 1 : 0, picked -> {
            prefs.edit().putBoolean("metric", picked == 1).apply();
            host.settingsChanged();
        });

        LinearLayout phone = group("PHONE");
        final boolean chop = ChopService.isEnabled(c);
        switchRow(phone, Icons.FLASHLIGHT, "Chop for flashlight",
                "Chop twice. Paused during rides", chop,
                on -> host.setChopEnabled(on));
        toggle(phone, Icons.WAND_SPARKLES, "Visual effects",
                "Glow and motion on the dial", "fx", true, host::settingsChanged);

        LinearLayout links = group("CONNECTIONS");
        Bike bike = Bike.get(c);
        link(links, Icons.BLUETOOTH, "Bike", bike.anyConfigured()
                ? bike.configuredSummary() : "Battery and controller",
                bike.anyConfigured() ? "Linked" : "Set up", bike.anyConfigured(),
                v -> host.showBike());
        final boolean strava = Strava.isConnected(c);
        link(links, Icons.CLOUD_UPLOAD, "Strava", strava
                ? "Rides upload on their own" : "Upload rides to your account",
                strava ? "Connected" : "Connect", strava, v -> {
                    if (!strava) {
                        host.connectStrava();
                        return;
                    }
                    new Sheet(host.frame(), "Disconnect Strava?",
                            "Rides stay on this phone and stop uploading. Rides already on"
                                    + " Strava are not touched.")
                            .action("Cancel", Ui.SECONDARY, true, null)
                            .action("Disconnect", Ui.DANGER_OUTLINE, true, () -> {
                                Strava.disconnect(c);
                                build();
                                host.say("Strava disconnected");
                            })
                            .show();
                });
        final boolean key = Gemini.hasKey(c);
        link(links, Icons.SPARKLES, "Copilot", key
                ? "Answers with Google Gemini" : "Needs a free Gemini API key",
                key ? "Key saved" : "Add key", key, v -> host.editGeminiKey());
        final String deckHost = prefs.getString("deck_host", "");
        link(links, Icons.MONITOR, "Deck", deckHost.isEmpty()
                ? "Your Mac's desk dashboard" : deckHost,
                deckHost.isEmpty() ? "Connect" : "Linked", !deckHost.isEmpty(),
                v -> host.editDeckConnection());

        LinearLayout app = group("APP");
        toggle(app, Icons.DOWNLOAD, "Update automatically",
                "Installs new versions, never mid-ride", "auto_update", true,
                null);
        int state = Updater.state();
        boolean busy = state == Updater.CHECKING || state == Updater.DOWNLOADING
                || state == Updater.INSTALLING;
        link(app, Icons.REFRESH_CW, "Version " + Updater.versionName(c),
                Updater.statusLine(c),
                state == Updater.READY ? "Install" : busy ? "" : "Check",
                false, busy ? null : v -> {
                    if (Updater.state() == Updater.READY) Updater.install(c);
                    else Updater.check(c, host, true);
                });
        // Only a private repository needs one, so it is shown only once set.
        if (Updater.hasToken(c)) {
            link(app, Icons.KEY_ROUND, "Update token", "Read-only access to the app's releases",
                    "Saved", true, v -> host.editUpdateToken());
        }
        if (CrashLog.read(c) != null) {
            link(app, Icons.TRIANGLE_ALERT, "Last crash", "The app closed unexpectedly",
                    "View", false, v -> host.showLastCrash());
        }
        if (Updater.hasProblem(c)) {
            // The status line is the error; say so in colour.
            LinearLayout row = (LinearLayout) app.getChildAt(2);
            ((TextView) ((LinearLayout) row.getChildAt(1)).getChildAt(1)).setTextColor(Ui.DANGER);
        }

        TextView foot = Ui.label(c, "MAXAPACITY RIDE  \u00B7  BUILD " + Updater.versionCode(c));
        foot.setGravity(Gravity.CENTER);
        content.addView(foot, Ui.top(c, Ui.matchWrap(), 32));
    }

    // ---- Rows --------------------------------------------------------------

    private interface OnSwitch {
        void changed(boolean on);
    }

    private interface OnPick {
        void picked(int index);
    }

    private LinearLayout group(String name) {
        Context c = getContext();
        TextView l = Ui.label(c, name);
        l.setPadding(dp(4), content.getChildCount() == 0 ? dp(8) : dp(24), 0, dp(8));
        content.addView(l, Ui.matchWrap());
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.panel(c, Ui.SURFACE, Ui.HAIRLINE, 16));
        card.setClipToOutline(true);
        content.addView(card, Ui.matchWrap());
        return card;
    }

    /** Icon, name and detail; returns the row so a trailing control can be added. */
    private LinearLayout row(LinearLayout group, String icon, String name, String detail) {
        Context c = getContext();
        if (group.getChildCount() > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
            lp.leftMargin = dp(52);
            group.addView(Ui.divider(c), lp);
        }
        LinearLayout row = Ui.row(c);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.addView(Ui.icon(c, icon, 20, Ui.TEXT_2));
        LinearLayout text = Ui.column(c);
        TextView n = Ui.body(c, name, Ui.TEXT);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(n);
        TextView d = Ui.small(c, detail, Ui.TEXT_3);
        d.setMaxLines(2);
        d.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(d, Ui.top(c, Ui.matchWrap(), 2));
        LinearLayout.LayoutParams tlp = Ui.weighted();
        tlp.leftMargin = dp(16);
        tlp.rightMargin = dp(12);
        row.addView(text, tlp);
        group.addView(row, Ui.matchWrap());
        return row;
    }

    private void toggle(LinearLayout group, String icon, String name, String detail,
            final String pref, boolean fallback, final Runnable after) {
        switchRow(group, icon, name, detail, prefs.getBoolean(pref, fallback), on -> {
            prefs.edit().putBoolean(pref, on).apply();
            if (after != null) after.run();
        });
    }

    private void switchRow(LinearLayout group, String icon, String name, String detail,
            boolean on, final OnSwitch change) {
        LinearLayout row = row(group, icon, name, detail);
        final Ui.Toggle t = new Ui.Toggle(getContext(), on);
        row.addView(t);
        row.setBackground(Ui.ghost(getContext(), 0));
        row.setContentDescription(name + ", " + (on ? "on" : "off"));
        row.setOnClickListener(v -> {
            t.set(!t.isOn(), true);
            v.setContentDescription(name + ", " + (t.isOn() ? "on" : "off"));
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            change.changed(t.isOn());
        });
    }

    /** Two-way segmented control for a setting with exactly two values. */
    private void choice(LinearLayout group, String icon, String name, String a, String b,
            int selected, final OnPick pick) {
        Context c = getContext();
        LinearLayout row = row(group, icon, name, selected == 0 ? "Miles and feet"
                : "Kilometres and metres");
        final LinearLayout seg = Ui.row(c);
        seg.setBackground(Ui.panel(c, Ui.BG, Ui.HAIRLINE_HI, 10));
        seg.setPadding(dp(2), dp(2), dp(2), dp(2));
        final String[] names = {a, b};
        for (int i = 0; i < 2; i++) {
            final int index = i;
            TextView s = new TextView(c);
            s.setText(names[i]);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.T_LABEL);
            s.setTypeface(Ui.monoBold);
            s.setLetterSpacing(0.06f);
            s.setGravity(Gravity.CENTER);
            s.setTextColor(i == selected ? Ui.BG : Ui.TEXT_2);
            s.setBackground(i == selected ? Ui.panel(c, Ui.TEXT, 0, 8) : null);
            s.setOnClickListener(v -> {
                if (index == selected) return;
                pick.picked(index);
                build();
            });
            seg.addView(s, new LinearLayout.LayoutParams(dp(52), dp(32)));
        }
        row.addView(seg);
    }

    private void link(LinearLayout group, String icon, String name, String detail,
            String action, boolean ok, View.OnClickListener click) {
        Context c = getContext();
        LinearLayout row = row(group, icon, name, detail);
        if (!action.isEmpty()) {
            TextView a = Ui.small(c, action, ok ? Ui.OK : Ui.TEXT);
            a.setTypeface(Ui.sansBold);
            a.setSingleLine(true);
            row.addView(a);
            row.addView(Ui.icon(c, Icons.CHEVRON_RIGHT, 18, Ui.TEXT_3),
                    Ui.left(c, new LinearLayout.LayoutParams(dp(18), dp(18)), 4));
        }
        if (click != null) {
            row.setBackground(Ui.ghost(c, 0));
            row.setOnClickListener(click);
        }
    }
}
