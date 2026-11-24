package com.maxapacity.ride;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone's link to the MAXAPACITY Deck on the Mac. It finds the Deck by
 * itself, pairs with the 6-digit code the Deck shows, and sends rides home
 * when the Deck is in reach. Nobody types an address.
 *
 * Finding it, in the order deck/APP-LINK.md gives: down a USB cable (the Deck
 * keeps that link up from the Mac's side), then by its announcement on the
 * home network, then at the last address that worked, then at one typed by
 * hand for networks that block announcements. Everything but the cable is
 * tried only on Wi-Fi or Ethernet, never on mobile data.
 *
 * Main thread only; the talking happens on one background thread.
 */
final class Deck {

    interface Listener {
        void onDeckChanged();
    }

    /** How something asked of the Deck ended. {@code message} is ready to show, or null. */
    interface Done {
        void done(boolean ok, String message);
    }

    /** The first look has not finished. */
    static final int LOOKING = 0;
    static final int NOT_FOUND = 1;
    /** A Deck answers, but wants its 6-digit code before it lets this phone in. */
    static final int FOUND = 2;
    static final int LINKED = 3;

    /** The key this phone was handed when it paired. */
    private static final String PREF_KEY = "deck_token";
    /** An address typed by hand. */
    private static final String PREF_TYPED = "deck_host";
    /** The last network address a Deck answered at. */
    private static final String PREF_LAST = "deck_last";
    /** Set once this phone has been let in, so rides are only queued for people with a Deck. */
    private static final String PREF_LINKED = "deck_linked";

    private static final String SERVICE = "_maxdeck._tcp";
    private static final long LISTEN_MS = 6000;
    private static final long SEND_GAP_MS = 20000;
    private static final int MAX_RIDES_A_RUN = 100;

    /** Where the Deck answers down a USB cable. Tests point this at a stand-in. */
    static String usb = DeckLink.USB;

    private static Deck instance;

    /** Called once as the process starts. */
    static Deck start(Context c) {
        instance = new Deck(c.getApplicationContext());
        instance.watchNetwork();
        return instance;
    }

    static Deck get(Context c) {
        return instance != null ? instance : start(c);
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Runnable> afterLook = new ArrayList<>();
    private final List<Done> afterSend = new ArrayList<>();

    private int state = LOOKING;
    private String address = "";
    private boolean uploads;
    /** The last look met a Deck that is closed to other screens. */
    private boolean shut;
    private boolean looking;
    private boolean lookAgain;
    private boolean sending;
    private long lastSendAt;
    private String lastProblem = "";
    private String problem = "";

    private Host host;
    private boolean foreground;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private boolean resolving;
    /** Where the Deck's own announcement says it is, while one is being heard. */
    private String found = "";
    private String lastNetwork = "";

    private Deck(Context app) {
        this.app = app;
        this.prefs = Units.prefs(app);
    }

    // ---- What the screens ask -------------------------------------------------

    int state() {
        return state;
    }

    /** host:port of the Deck in reach, or empty. */
    String address() {
        return address;
    }

    boolean viaUsb() {
        return state == LINKED && address.equals(usb);
    }

    /** Whether the linked Deck takes rides. */
    boolean uploads() {
        return uploads;
    }

    /** True once this phone has ever been let in by a Deck. */
    boolean everLinked() {
        return prefs.getBoolean(PREF_LINKED, false);
    }

    /** What stopped the last send, in the Deck's own words, or empty. */
    String problem() {
        return problem;
    }

    /** The key to send to the Deck in reach. The cable needs none. */
    String requestKey() {
        return address.equals(usb) ? "" : prefs.getString(PREF_KEY, "");
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        for (int i = listeners.size() - 1; i >= 0; i--) listeners.get(i).onDeckChanged();
    }

    /** The app is on screen: listen for the Deck and keep the link fresh. */
    void attach(Host h) {
        host = h;
        foreground = true;
        startListening();
        refresh();
    }

    void detach(Host h) {
        if (host == h) host = null;
        foreground = false;
        stopListening();
    }

    // ---- Finding the Deck -----------------------------------------------------

    private static final class Look {
        final int state;
        final String address;
        final boolean uploads;
        final boolean shut;

        Look(int state, String address, boolean uploads, boolean shut) {
            this.state = state;
            this.address = address;
            this.uploads = uploads;
            this.shut = shut;
        }
    }

    /** Looks for the Deck again. Safe to call often; looks never overlap. */
    void refresh() {
        if (looking) {
            lookAgain = true;
            return;
        }
        looking = true;
        final String key = prefs.getString(PREF_KEY, "");
        final List<String> places = new ArrayList<>();
        places.add(usb);
        if (onLan()) {
            String[] known = {found, prefs.getString(PREF_LAST, ""), prefs.getString(PREF_TYPED, "")};
            for (String a : known) {
                String n = normalise(a);
                if (!n.isEmpty() && !places.contains(n)) places.add(n);
            }
        }
        io.execute(() -> {
            final Look look = look(places, key);
            main.post(() -> {
                looking = false;
                apply(look);
                if (lookAgain) {
                    lookAgain = false;
                    refresh();
                    return;
                }
                List<Runnable> waiting = new ArrayList<>(afterLook);
                afterLook.clear();
                for (Runnable r : waiting) r.run();
                // In reach with rides still on the phone: send them home.
                if (state == LINKED && uploads && !sending
                        && SystemClock.elapsedRealtime() - lastSendAt > SEND_GAP_MS
                        && !unsent().isEmpty()) {
                    send(null);
                }
            });
        });
    }

    private void lookThen(Runnable then) {
        afterLook.add(then);
        refresh();
    }

    /** Background thread: the first place that answers as a Deck wins. */
    private static Look look(List<String> places, String key) {
        String wantsCode = null;
        boolean shut = false;
        for (String a : places) {
            // The cable needs no key, and whatever else might be listening on
            // the phone's own port is not handed one.
            DeckLink.Probe p = DeckLink.probe(a, a.equals(usb) ? "" : key);
            if (p.result == DeckLink.HOME) return new Look(LINKED, a, p.uploads, false);
            if (p.result == DeckLink.UNPAIRED && wantsCode == null) wantsCode = a;
            if (p.result == DeckLink.SHUT) shut = true;
        }
        if (wantsCode != null) return new Look(FOUND, wantsCode, false, shut);
        return new Look(NOT_FOUND, "", false, shut);
    }

    private void apply(Look look) {
        boolean different = look.state != state || !look.address.equals(address)
                || look.uploads != uploads;
        state = look.state;
        address = look.address;
        uploads = look.uploads;
        shut = look.shut;
        if (state != NOT_FOUND && !address.equals(usb)) {
            prefs.edit().putString(PREF_LAST, address).apply();
        }
        if (state == LINKED && !everLinked()) prefs.edit().putBoolean(PREF_LINKED, true).apply();
        if (state != LINKED) problem = "";
        if (different) changed();
    }

    /** "192.168.1.20", "http://192.168.1.20:8791/#123456": all become host:port. */
    static String normalise(String typed) {
        String a = typed == null ? "" : typed.trim();
        int scheme = a.indexOf("://");
        if (scheme >= 0) a = a.substring(scheme + 3);
        int slash = a.indexOf('/');
        if (slash >= 0) a = a.substring(0, slash);
        if (a.isEmpty()) return "";
        return a.contains(":") ? a : a + ":" + DeckLink.PORT;
    }

    private boolean onLan() {
        try {
            ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
            Network n = cm == null ? null : cm.getActiveNetwork();
            return n != null && lan(cm.getNetworkCapabilities(n));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean lan(NetworkCapabilities caps) {
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    /** Joining or leaving a network is when the Deck comes into or goes out of reach. */
    private void watchNetwork() {
        try {
            ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    final String now = network + (lan(caps) ? " lan" : "");
                    main.post(() -> networkIs(now));
                }

                @Override
                public void onLost(Network network) {
                    main.post(() -> networkIs(""));
                }
            });
        } catch (RuntimeException e) {
            // This phone does not allow it; the other moments the link is checked still do.
        }
    }

    private final Runnable settled = () -> {
        if (foreground) {
            stopListening();
            startListening();
            refresh();
        } else if (everLinked() && !unsent().isEmpty()) {
            offload(null);
        }
    };

    private void networkIs(String now) {
        if (now.equals(lastNetwork)) return;
        lastNetwork = now;
        // What was heard on the last network says nothing about this one.
        found = "";
        main.removeCallbacks(settled);
        main.postDelayed(settled, 3000);
    }

    // ---- Hearing the Deck's announcement ---------------------------------------

    private void startListening() {
        // Only on Wi-Fi: out on a ride there is nothing to hear and a battery to save.
        if (discovery != null || !onLan()) return;
        try {
            if (nsd == null) nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            final NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String type) {
                }

                @Override
                public void onDiscoveryStopped(String type) {
                }

                @Override
                public void onStartDiscoveryFailed(String type, int error) {
                    final NsdManager.DiscoveryListener me = this;
                    main.post(() -> {
                        if (discovery == me) discovery = null;
                    });
                }

                @Override
                public void onStopDiscoveryFailed(String type, int error) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    main.post(() -> resolve(info));
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                    main.post(() -> {
                        found = "";
                        refresh();
                    });
                }
            };
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l);
            discovery = l;
        } catch (RuntimeException e) {
            discovery = null;
        }
    }

    private void stopListening() {
        if (discovery == null) return;
        try {
            nsd.stopServiceDiscovery(discovery);
        } catch (RuntimeException e) {
            // Already stopped.
        }
        discovery = null;
    }

    private void resolve(NsdServiceInfo info) {
        if (resolving || discovery == null) return;
        resolving = true;
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo i, int error) {
                    main.post(() -> resolving = false);
                }

                @Override
                public void onServiceResolved(NsdServiceInfo i) {
                    final String at = addressOf(i);
                    main.post(() -> {
                        resolving = false;
                        if (!at.isEmpty() && !at.equals(found)) {
                            found = at;
                            refresh();
                        }
                    });
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
        }
    }

    /** The Deck only answers to an IPv4 address or a .local name, so IPv6 is passed over. */
    private static String addressOf(NsdServiceInfo info) {
        InetAddress pick = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            for (InetAddress a : info.getHostAddresses()) {
                if (a instanceof Inet4Address) {
                    pick = a;
                    break;
                }
            }
        } else if (info.getHost() instanceof Inet4Address) {
            pick = info.getHost();
        }
        if (pick == null || info.getPort() <= 0) return "";
        return pick.getHostAddress() + ":" + info.getPort();
    }

    // ---- Pairing ---------------------------------------------------------------

    /** Trades the 6-digit code on the Deck's Add a screen for this phone's key. */
    void pair(final String code, final Done done) {
        final String to = state == FOUND ? address : "";
        if (to.isEmpty()) {
            done.done(false, "No Deck is in reach to pair with");
            return;
        }
        final String name = device();
        io.execute(() -> {
            final DeckLink.Pairing p = DeckLink.pair(to, code, name);
            main.post(() -> {
                if (p.ok) {
                    if (p.key != null) prefs.edit().putString(PREF_KEY, p.key).apply();
                    done.done(true, "Paired with your Deck");
                    // Rides recorded before pairing go home now, not at the next look.
                    lookThen(() -> sendIfHome(null));
                } else if ("wrong".equals(p.why)) {
                    done.done(false, "That is not the code on the Deck");
                } else if ("closed".equals(p.why)) {
                    done.done(false, "No code is showing on the Deck. Open Add a screen there first");
                } else {
                    done.done(false, "The Deck did not answer. Check it is still in reach");
                }
            });
        });
    }

    /** For a network that hides the Deck's announcement: look at a typed address. */
    void useAddress(String typed, final Done done) {
        final String a = normalise(typed);
        prefs.edit().putString(PREF_TYPED, a).apply();
        lookThen(() -> {
            if (state == LINKED) {
                done.done(true, "Linked to your Deck");
            } else if (state == FOUND) {
                done.done(true, null);
            } else if (shut) {
                done.done(false, "A Deck is there, but it is closed to other screens. Set remote"
                        + " to true in its config.json");
            } else {
                done.done(false, a.isEmpty() ? "No Deck in reach" : "No Deck answered at " + a);
            }
        });
    }

    /**
     * Unpairs this phone: its key is thrown away. Where the Deck is stays
     * known, so it shows as found and can be paired again with a new code.
     */
    void forget() {
        prefs.edit().remove(PREF_KEY).remove(PREF_LINKED).apply();
        refresh();
    }

    // ---- Sending rides home -----------------------------------------------------

    /** A ride that is not on the Mac yet, with what will be sent for it. */
    private static final class Work {
        String file;
        File gpx;
        long mtimeSec;
        String summaryName;
        byte[] summary;
    }

    private List<Work> unsent() {
        List<Work> out = new ArrayList<>();
        JSONArray rides = RideStore.load(app);
        for (int i = 0; i < rides.length() && out.size() < MAX_RIDES_A_RUN; i++) {
            JSONObject r = rides.optJSONObject(i);
            if (r == null || RideStore.ON_MAC.equals(r.optString("deck"))) continue;
            Work w = new Work();
            w.file = r.optString("file");
            w.gpx = new File(RideStore.dir(app), w.file);
            if (w.file.isEmpty() || !w.gpx.isFile() || w.gpx.length() == 0) continue;
            w.mtimeSec = w.gpx.lastModified() / 1000;
            w.summaryName = w.file.replaceAll("\\.[A-Za-z0-9]+$", "") + ".json";
            w.summary = summary(r);
            out.add(w);
        }
        return out;
    }

    /** The ride's numbers, sent beside its track so the Mac need not work them out again. */
    static byte[] summary(JSONObject ride) {
        JSONObject s = new JSONObject();
        try {
            s.put("app", "MAXAPACITY Ride");
            String[] keep = {"file", "start", "distanceM", "movingMs", "elapsedMs", "topMps",
                    "climbM", "stops", "leanLeft", "leanRight"};
            for (String k : keep) {
                if (ride.has(k)) s.put(k, ride.get(k));
            }
        } catch (JSONException e) {
            // Sent with whatever was gathered.
        }
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The folder name the Mac files this phone under: the same one its USB offload uses. */
    private static String device() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        return model.isEmpty() ? "Android phone" : model;
    }

    /** A ride was just saved: send it now if home, and otherwise as soon as home is reached. */
    void rideSaved() {
        if (!everLinked()) return;
        DeckJob.schedule(app);
        offload(null);
    }

    /**
     * Finds the Deck and sends every ride it does not have. {@code done} hears
     * ok only when nothing is left on the phone to send.
     */
    void offload(final Done done) {
        if (unsent().isEmpty()) {
            if (done != null) done.done(true, null);
            return;
        }
        lookThen(() -> {
            if (state == NOT_FOUND && discovery == null && onLan()) {
                // With the app off screen nothing has been listening for the
                // Deck's announcement, so listen for a moment and look again.
                startListening();
                main.postDelayed(() -> {
                    if (!foreground) stopListening();
                    lookThen(() -> sendIfHome(done));
                }, LISTEN_MS);
            } else {
                sendIfHome(done);
            }
        });
    }

    private void sendIfHome(Done done) {
        if (state != LINKED) {
            if (done != null) done.done(false, null);
        } else if (!uploads) {
            if (done != null) done.done(false, "Ride upload is switched off on the Deck");
        } else {
            send(done);
        }
    }

    private void send(Done done) {
        if (done != null) afterSend.add(done);
        if (sending) return;
        final List<Work> work = unsent();
        if (work.isEmpty()) {
            sent(true, null);
            return;
        }
        sending = true;
        final String to = address;
        final String key = requestKey();
        final String device = device();
        io.execute(() -> {
            List<DeckLink.Item> items = new ArrayList<>();
            for (final Work w : work) {
                items.add(new DeckLink.Item(w.file, w.gpx.length(), w.mtimeSec, "ride",
                        () -> new FileInputStream(w.gpx)));
                items.add(new DeckLink.Item(w.summaryName, w.summary.length, w.mtimeSec, "ride",
                        () -> new ByteArrayInputStream(w.summary)));
            }
            final DeckLink.Outcome o = DeckLink.send(to, key, device, items);
            main.post(() -> {
                sending = false;
                lastSendAt = SystemClock.elapsedRealtime();
                int arrived = 0;
                for (Work w : work) {
                    if (o.onMac.contains(w.file) && o.onMac.contains(w.summaryName)) {
                        RideStore.setOnMac(app, w.file);
                        arrived++;
                    }
                }
                String trouble = "";
                if (o.stopped != null) {
                    trouble = o.stopped;
                } else if (!o.refused.isEmpty()) {
                    Map.Entry<String, String> first = o.refused.entrySet().iterator().next();
                    trouble = "The Deck did not take " + first.getKey() + ": " + first.getValue();
                }
                problem = trouble;
                if (arrived > 0 && o.sentNow > 0) {
                    say(arrived == 1 ? "1 ride sent to your Mac"
                            : arrived + " rides sent to your Mac", true);
                }
                // The same trouble is said once, not at every look.
                if (!trouble.isEmpty() && !trouble.equals(lastProblem)) say(trouble, false);
                lastProblem = trouble;
                if (arrived > 0 && host != null) host.ridesChanged();
                changed();
                boolean nothingLeft = trouble.isEmpty() && unsent().isEmpty();
                if (!nothingLeft) DeckJob.schedule(app);
                sent(nothingLeft, trouble.isEmpty() ? null : trouble);
            });
        });
    }

    private void sent(boolean ok, String message) {
        List<Done> waiting = new ArrayList<>(afterSend);
        afterSend.clear();
        for (Done d : waiting) d.done(ok, message);
    }

    /** On the screen when the app is showing; good news also as a toast when it is not. */
    private void say(String message, boolean good) {
        if (host != null) {
            host.say(message);
        } else if (good) {
            Toast.makeText(app, message, Toast.LENGTH_LONG).show();
        }
    }
}
