package com.maxapacity.ride;

import android.bluetooth.le.ScanCallback;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The bike: live battery data from an ANT BMS and live controller data from a
 * Fardriver, each linked once over Bluetooth and reconnected on their own
 * after that. The controller card leads on to tuning.
 */
final class BikePanel extends Panel {

    private final Host host;
    private final LinearLayout content;
    private final Runnable bikeChanged = this::refresh;
    private ScanCallback scan;
    /** What the screen was last built from, so readings that did not change cost nothing. */
    private String shown = "";

    BikePanel(Context context, Host host) {
        super(context, "Bike");
        this.host = host;
        content = addScrollContent();
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
        stopScan();
    }

    @Override
    void onHostPause() {
        stopScan();
    }

    private void refresh() {
        Context c = getContext();
        Bike bike = Bike.get(c);
        AntBms.Data b = bike.battery();
        Fardriver.Live l = bike.controller();
        boolean live = b != null || l != null;
        boolean problem = (b != null && b.hasProblem()) || (l != null && l.faults != 0);
        if (!bike.anyConfigured()) status.set("", Ui.TEXT_3);
        else if (problem) status.set("ATTENTION", Ui.DANGER);
        else status.set(live ? "LIVE" : "CONNECTING", live ? Ui.OK : Ui.WARN);

        String key = bike.summaryKey() + "|" + (b == null ? "" : Math.round(b.amps * 10) + "/"
                + Math.round(b.deltaCellV() * 1000) + "/" + b.maxTempC())
                + "|" + (l == null ? "" : l.rpm + "/" + Math.round(l.amps) + "/" + l.motorTempC
                + "/" + Math.round(l.volts * 10));
        if (key.equals(shown)) return;
        shown = key;

        content.removeAllViews();
        if (!bike.anyConfigured()) {
            content.addView(Ui.emptyState(c, Icons.BLUETOOTH, "See inside your bike",
                    "Link an ANT BMS battery and a Fardriver controller to watch charge, power"
                            + " and temperatures live, and to tune how the bike rides.",
                    "Link battery", Ui.PRIMARY, v -> pick(Bike.KIND_BATTERY)));
            LinearLayout ctl = Ui.button(c, "LINK CONTROLLER", null, Ui.SECONDARY);
            ctl.setOnClickListener(v -> pick(Bike.KIND_CONTROLLER));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            lp.setMargins(dp(24), -dp(12), dp(24), 0);
            content.addView(ctl, lp);
            return;
        }
        addBattery(bike, b);
        addController(bike, l);
    }

    // ---- Battery -----------------------------------------------------------

    private void addBattery(final Bike bike, AntBms.Data b) {
        Context c = getContext();
        LinearLayout card = card(Icons.BATTERY_MEDIUM, "BATTERY",
                bike.batteryLinked() ? bike.batteryName() : null, Bike.KIND_BATTERY);
        if (!bike.batteryLinked()) {
            prompt(card, "Charge, cell balance and temperature from an ANT BMS.",
                    "LINK BATTERY", Bike.KIND_BATTERY);
            return;
        }
        if (b == null) {
            waiting(card, bike.batteryStatus());
            return;
        }
        int socColor = b.soc <= 15 ? Ui.DANGER : b.soc <= 30 ? Ui.WARN : Ui.OK;
        LinearLayout top = Ui.row(c);
        TextView soc = Ui.value(c, "", 40);
        soc.setText(Ui.withUnit(String.valueOf(b.soc), "%"));
        top.addView(soc, Ui.weighted());
        String flow = b.amps < -0.2f ? "Charging" : b.amps > 0.2f ? "Discharging" : "Resting";
        top.addView(Ui.small(c, flow, b.amps < -0.2f ? Ui.OK : Ui.TEXT_2));
        card.addView(top, Ui.matchWrap());
        card.addView(Ui.bar(c, b.soc, socColor), Ui.top(c, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)), 12));

        LinearLayout r1 = Ui.row(c);
        stat(r1, "VOLTAGE", fmt("%.1f", b.volts), "V", Ui.TEXT);
        stat(r1, "CURRENT", fmt("%.1f", Math.abs(b.amps)), "A", Ui.TEXT);
        stat(r1, "POWER", fmt("%.2f", Math.abs(b.powerW) / 1000f), "kW", Ui.TEXT);
        card.addView(r1, Ui.top(c, Ui.matchWrap(), 16));
        rule(card);
        int spread = Math.round(b.deltaCellV() * 1000);
        int hottest = Math.max(b.maxTempC(), b.mosfetTempC);
        LinearLayout r2 = Ui.row(c);
        stat(r2, "REMAINING", fmt("%.1f", b.remainingAh), "Ah", Ui.TEXT);
        // A wide spread means the pack is out of balance: amber, then red.
        stat(r2, "CELL SPREAD", String.valueOf(spread), "mV",
                spread >= 100 ? Ui.DANGER : spread >= 50 ? Ui.WARN : Ui.TEXT);
        stat(r2, "TEMP", hottest == Integer.MIN_VALUE ? "--" : String.valueOf(hottest),
                "\u00B0C", hottest >= 60 ? Ui.DANGER : hottest >= 50 ? Ui.WARN : Ui.TEXT);
        card.addView(r2, Ui.matchWrap());

        if (b.cellVolts.length > 0) {
            LinearLayout head = Ui.row(c);
            head.addView(Ui.label(c, b.cellVolts.length + " CELLS"), Ui.weighted());
            head.addView(Ui.label(c, fmt("%.3f", b.minCellV) + " \u2013 "
                    + fmt("%.3f", b.maxCellV) + " V"));
            card.addView(head, Ui.top(c, Ui.matchWrap(), 16));
            card.addView(new CellsView(c, b), Ui.top(c, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(56)), 8));
        }
        for (String p : b.problems) alert(card, p);
    }

    // ---- Controller --------------------------------------------------------

    private void addController(final Bike bike, Fardriver.Live l) {
        Context c = getContext();
        LinearLayout card = card(Icons.CPU, "CONTROLLER",
                bike.controllerLinked() ? bike.controllerName() : null, Bike.KIND_CONTROLLER);
        if (!bike.controllerLinked()) {
            prompt(card, "Live power, temperatures and tuning for a Fardriver controller.",
                    "LINK CONTROLLER", Bike.KIND_CONTROLLER);
            return;
        }
        if (l == null) {
            waiting(card, bike.controllerStatus());
            return;
        }
        float kw = l.powerW() / 1000f;
        LinearLayout top = Ui.row(c);
        TextView power = Ui.value(c, "", 40);
        power.setText(Ui.withUnit(fmt("%.1f", Math.abs(kw)), "kW"));
        top.addView(power, Ui.weighted());
        top.addView(Ui.small(c, kw < -0.05f ? "Regenerating" : l.brake ? "Brake on"
                : l.direction == 2 ? "Reverse" : l.speedModeName() + " mode",
                kw < -0.05f ? Ui.OK : Ui.TEXT_2));
        card.addView(top, Ui.matchWrap());
        int pct = Math.round(100f * Math.abs(l.powerW()) / bike.peakPowerW());
        card.addView(Ui.bar(c, pct, kw < -0.05f ? Ui.OK : Ui.ACCENT),
                Ui.top(c, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(6)), 12));

        LinearLayout r1 = Ui.row(c);
        stat(r1, "MOTOR", String.valueOf(l.rpm), "rpm", Ui.TEXT);
        stat(r1, "VOLTAGE", fmt("%.1f", l.volts), "V", Ui.TEXT);
        stat(r1, "CURRENT", fmt("%.0f", Math.abs(l.amps)), "A", Ui.TEXT);
        card.addView(r1, Ui.top(c, Ui.matchWrap(), 16));
        rule(card);
        LinearLayout r2 = Ui.row(c);
        stat(r2, "CONTROLLER", temp(l.mosfetTempC), "\u00B0C",
                l.mosfetTempC >= 85 ? Ui.DANGER : l.mosfetTempC >= 70 ? Ui.WARN : Ui.TEXT);
        stat(r2, "MOTOR TEMP", temp(l.motorTempC), "\u00B0C",
                l.motorTempC >= 140 ? Ui.DANGER : l.motorTempC >= 110 ? Ui.WARN : Ui.TEXT);
        stat(r2, "PHASE", fmt("%.0f", l.phaseAmps), "A", Ui.TEXT);
        card.addView(r2, Ui.matchWrap());
        for (String f : l.faultNames()) alert(card, f);

        LinearLayout tune = Ui.button(c, "TUNE CONTROLLER", Icons.SLIDERS_HORIZONTAL,
                Ui.SECONDARY);
        tune.setOnClickListener(v -> ((RideActivity) host).showTune());
        card.addView(tune, Ui.top(c, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)), 16));
        if (!l.firmware.isEmpty()) {
            card.addView(Ui.label(c, "FIRMWARE " + l.firmware), Ui.top(c, Ui.matchWrap(), 12));
        }
    }

    // ---- Linking -----------------------------------------------------------

    private void pick(final int kind) {
        host.withBluetooth(() -> {
            String why = BleLink.unavailableReason(getContext());
            if (why != null) {
                host.say(why + ". Turn it on and try again");
                return;
            }
            showPicker(kind);
        });
    }

    private void showPicker(final int kind) {
        final Context c = getContext();
        final Bike bike = Bike.get(c);
        final boolean battery = kind == Bike.KIND_BATTERY;
        final Sheet sheet = new Sheet(host.frame(),
                battery ? "Link battery" : "Link controller",
                battery ? "Switch the bike on. ANT batteries nearby appear here."
                        : "Switch the bike on. Fardriver controllers nearby appear here. Close"
                        + " the Fardriver app first: the controller talks to one phone at"
                        + " a time.");
        final View looking = Ui.skeleton(c, 56);
        sheet.view(looking);
        sheet.action("Cancel", Ui.SECONDARY, true, null);
        sheet.onDismiss(this::stopScan);
        sheet.show();
        final Set<String> listed = new HashSet<>();
        stopScan();
        scan = bike.scan((name, address) -> {
            if (!Bike.matches(kind, name) || !listed.add(name)) return;
            looking.setVisibility(GONE);
            sheet.item(battery ? Icons.BATTERY_MEDIUM : Icons.CPU, name, address, false, () -> {
                bike.link(kind, name);
                host.say((battery ? "Battery" : "Controller") + " linked");
            });
        });
        if (scan == null) {
            sheet.dismiss();
            host.say("Bluetooth scanning could not start");
        }
    }

    private void stopScan() {
        if (scan == null) return;
        Bike.get(getContext()).stopScan(scan);
        scan = null;
    }

    private void confirmUnlink(final int kind) {
        final boolean battery = kind == Bike.KIND_BATTERY;
        final Bike bike = Bike.get(getContext());
        new Sheet(host.frame(), battery ? bike.batteryName() : bike.controllerName(), null)
                .item(Icons.UNLINK, battery ? "Unlink battery" : "Unlink controller",
                        "Nothing on the bike changes", true, () -> {
                            final String was = bike.unlink(kind);
                            host.say((battery ? "Battery" : "Controller") + " unlinked", "UNDO",
                                    () -> bike.link(kind, was));
                        })
                .show();
    }

    // ---- Pieces ------------------------------------------------------------

    private LinearLayout card(String icon, String name, String device, final int kind) {
        Context c = getContext();
        LinearLayout card = Ui.card(c);
        LinearLayout head = Ui.row(c);
        head.addView(Ui.icon(c, icon, 16, Ui.TEXT_3));
        head.addView(Ui.label(c, name), Ui.left(c, Ui.wrap(), 8));
        TextView d = Ui.label(c, device == null ? "" : device);
        d.setGravity(android.view.Gravity.END);
        head.addView(d, Ui.left(c, Ui.weighted(), 12));
        if (device != null) {
            View more = Ui.iconButton(c, Icons.ELLIPSIS, name + " options");
            more.setOnClickListener(v -> confirmUnlink(kind));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(48), dp(48));
            mlp.setMargins(0, -dp(16), -dp(12), -dp(16));
            head.addView(more, mlp);
        }
        LinearLayout.LayoutParams hlp = Ui.matchWrap();
        hlp.bottomMargin = dp(16);
        card.addView(head, hlp);
        content.addView(card, Ui.top(c, Ui.matchWrap(), content.getChildCount() == 0 ? 0 : 12));
        return card;
    }

    private void prompt(LinearLayout card, String pitch, String action, final int kind) {
        Context c = getContext();
        card.addView(Ui.body(c, pitch, Ui.TEXT_2));
        LinearLayout b = Ui.button(c, action, Icons.BLUETOOTH, Ui.SECONDARY);
        b.setOnClickListener(v -> pick(kind));
        card.addView(b, Ui.top(c, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)), 16));
    }

    /** Linked but no data yet: placeholders shaped like the readings, plus why. */
    private void waiting(LinearLayout card, String why) {
        Context c = getContext();
        View block = Ui.skeleton(c, 96);
        block.setBackground(Ui.panel(c, Ui.SURFACE_HI, 0, 10));
        card.addView(block);
        boolean trying = "Connecting".equals(why) || "Reading".equals(why);
        card.addView(Ui.small(c, trying ? why + ". Make sure the bike is switched on"
                : why + ". Trying again shortly", trying ? Ui.TEXT_2 : Ui.WARN),
                Ui.top(c, Ui.matchWrap(), 12));
    }

    private void stat(LinearLayout row, String name, String number, String unit, int color) {
        Context c = getContext();
        LinearLayout col = Ui.column(c);
        col.addView(Ui.label(c, name));
        TextView v = Ui.value(c, "", Ui.T_TITLE);
        v.setText(Ui.withUnit(number, unit));
        v.setTextColor(color);
        col.addView(v, Ui.top(c, Ui.matchWrap(), 8));
        row.addView(col, Ui.weighted());
    }

    private void rule(LinearLayout card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        card.addView(Ui.divider(getContext()), lp);
    }

    private void alert(LinearLayout card, String text) {
        Context c = getContext();
        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.panel(c, 0x14F87171, 0x66F87171, 10));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.addView(Ui.icon(c, Icons.TRIANGLE_ALERT, 18, Ui.DANGER));
        row.addView(Ui.small(c, text, Ui.DANGER), Ui.left(c, Ui.weighted(), 12));
        card.addView(row, Ui.top(c, Ui.matchWrap(), 12));
    }

    private static String fmt(String pattern, float v) {
        return String.format(Locale.US, pattern, v);
    }

    private static String temp(int c) {
        return c == Integer.MIN_VALUE ? "--" : String.valueOf(c);
    }

    /** One bar per cell, scaled between the pack's lowest and highest cell. */
    static final class CellsView extends View {
        private final AntBms.Data data;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();

        CellsView(Context c, AntBms.Data data) {
            super(c);
            this.data = data;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int n = data.cellVolts.length;
            if (n == 0) return;
            float gap = Ui.dp(getContext(), n > 24 ? 2 : 3);
            float w = (getWidth() - gap * (n - 1)) / n;
            float h = getHeight();
            // Show spread, not absolute level: 30 mV fills half the height at least.
            float span = Math.max(data.deltaCellV(), 0.03f);
            float mid = (data.maxCellV + data.minCellV) / 2f;
            for (int i = 0; i < n; i++) {
                float rel = 0.5f + (data.cellVolts[i] - mid) / (span * 2f);
                float top = h - Math.max(0.12f, Math.min(1f, rel)) * h;
                float x = i * (w + gap);
                bar.set(x, top, x + w, h);
                boolean low = i + 1 == data.minCell && data.deltaCellV() >= 0.05f;
                boolean high = i + 1 == data.maxCell && data.deltaCellV() >= 0.05f;
                paint.setColor(low ? Ui.WARN : high ? Ui.TEXT : Ui.HAIRLINE_HI);
                canvas.drawRoundRect(bar, w / 4f, w / 4f, paint);
            }
        }
    }
}
