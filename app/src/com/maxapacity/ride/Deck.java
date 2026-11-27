package com.maxapacity.ride;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
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
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The phone's link to the MAXAPACITY Deck on the Mac. It finds the Deck by
 * itself, pairs with the 6-digit code the Deck shows, and sends rides home
 * when the Deck is in reach. Nobody types an address.
 *
 * Finding it, in the order deck/APP-LINK.md gives: down a USB cable (the Deck
 * keeps that link up from the Mac's side), then by its announcement on the
 * home network, then at the last address that worked, then at one typed by
 * hand. Plenty of home networks never pass the announcement on, so when
 * nothing answers the phone asks every address on its own network instead
 * ({@link #search}). Everything but the cable is tried only on Wi-Fi or
 * Ethernet, never on mobile data.
 *
 * What it tried and what answered is kept as a short log ({@link #report}),
 * so a link that will not come up can be explained without guessing.
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
    /** Unasked, the whole network is searched this often at most, and only a few times. */
    private static final long SEARCH_GAP_MS = 45000;
    private static final long NEVER = Long.MIN_VALUE / 2;
    private static final int UNASKED_SEARCHES = 3;
    private static final int RESOLVE_TRIES = 3;
    private static final int MAX_NOTES = 80;
    private static final long SEND_GAP_MS = 20000;
    private static final int MAX_RIDES_A_RUN = 100;

    /** Where the Deck answers down a USB cable. Tests point this at a stand-in. */
    static String usb = DeckLink.USB;
    /** Tests name the addresses a search asks. Otherwise they are the phone's own network. */
    static Supplier<List<String>> neighbours;

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
    /** Searching a network takes seconds; it has its own thread so looks are not held up. */
    private final ExecutorService searchIo = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Runnable> afterLook = new ArrayList<>();
    private final List<Done> afterSend = new ArrayList<>();

    private int state = LOOKING;
    private String address = "";
    private boolean uploads;
    private boolean phoneDeck;
    /** The last look met a Deck that is closed to other screens. */
    private boolean shut;
    private boolean looking;
    private boolean lookAgain;
    private boolean sending;
    private long lastSendAt;
    private volatile String progress = "";
    private String lastProblem = "";
    private String problem = "";

    private Host host;
    private boolean foreground;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private boolean resolving;
    /** Announcements heard and not yet turned into an address. */
    private final List<NsdServiceInfo> toResolve = new ArrayList<>();
    private int resolveTries;
    /** Where the Deck's own announcement says it is, while one is being heard. */
    private String found = "";
    private String lastNetwork = "";

    /** Decks a search of this network turned up. */
    private final List<String> swept = new ArrayList<>();
    private boolean searching;
    /** When this network was last searched. Far in the past until it has been. */
    private long searchedAt = NEVER;
    private int unaskedSearches;
    /** A phone that has been let in before looks harder once per network, unasked. */
    private boolean lookedHarder;
    private String searchNote = "";

    /** What was tried and what answered, newest last. */
    private final ArrayDeque<String> notes = new ArrayDeque<>();
    private String lastNote = "";

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
    boolean phoneDeck() { return phoneDeck; }

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

    /** True while every address on this network is being asked. */
    boolean searching() {
        return searching;
    }

    /** What the last search of this network came to, ready to show, or empty. */
    String searchNote() {
        return searchNote;
    }

    /** Whether this phone is on Wi-Fi or a cabled network at all. */
    boolean onNetwork() {
        return onLan();
    }

    /** Something answered as a Deck but turned this phone away. */
    boolean shut() {
        return shut;
    }

    /** How many rides are still only on this phone. */
    int ridesWaiting() {
        int count = 0;
        JSONArray rides = RideStore.load(app);
        for (int i = 0; i < rides.length(); i++) {
            JSONObject r = rides.optJSONObject(i);
            if (r != null && !RideStore.ON_MAC.equals(r.optString("deck"))
                    && new File(RideStore.dir(app), r.optString("file")).isFile()) count++;
        }
        return count;
    }

    String syncStatus() {
        if (sending) return progress;
        int n = ridesWaiting();
        if (n == 0) return "Everything synced";
        String queued = n + (n == 1 ? " ride" : " rides");
        if (state != LINKED) return queued + " waiting for your home Deck";
        if (!uploads) return queued + " queued · Deck uploads are off";
        if (!problem.isEmpty()) return queued + " queued · Sync interrupted";
        return queued + " ready to sync";
    }

    long lastSync() { return prefs.getLong("deck_last_sync", 0); }

    /** This phone's own address on that network, such as "192.168.1.57", or empty. */
    String phoneAddress() {
        String own = ownAddress();
        int slash = own.indexOf('/');
        return slash < 0 ? own : own.substring(0, slash);
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
        boolean phoneDeck;
        /** Each place asked and what it said, for the log. */
        final String tried;

        Look(int state, String address, boolean uploads, boolean shut, String tried) {
            this.state = state;
            this.address = address;
            this.uploads = uploads;
            this.shut = shut;
            this.tried = tried;
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
            List<String> known = new ArrayList<>();
            known.add(found);
            known.add(prefs.getString(PREF_LAST, ""));
            known.addAll(swept);
            known.add(prefs.getString(PREF_TYPED, ""));
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
                // A phone that has been let in before looks harder, once per network: the
                // Mac may have been handed a new address, and plenty of networks never pass
                // its announcement on.
                if (state == NOT_FOUND && foreground && everLinked() && !lookedHarder && onLan()) {
                    lookedHarder = true;
                    search(false);
                }
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
        StringBuilder tried = new StringBuilder();
        for (String a : places) {
            // The cable needs no key, and whatever else might be listening on
            // the phone's own port is not handed one.
            DeckLink.Probe p = DeckLink.probe(a, a.equals(usb) ? "" : key);
            tried.append(tried.length() == 0 ? "" : "; ").append(a.equals(usb) ? "the cable" : a)
                    .append(p.result == DeckLink.HOME ? " let this phone in"
                            : p.result == DeckLink.UNPAIRED ? " is a Deck and wants its code"
                            : p.result == DeckLink.SHUT ? " turned this phone away"
                            : " did not answer");
            if (p.result == DeckLink.HOME) {
                Look linked = new Look(LINKED, a, p.uploads, false, tried.toString());
                linked.phoneDeck = p.state.optBoolean("phoneDeck");
                return linked;
            }
            if (p.result == DeckLink.UNPAIRED && wantsCode == null) wantsCode = a;
            if (p.result == DeckLink.SHUT) shut = true;
        }
        if (wantsCode != null) return new Look(FOUND, wantsCode, false, shut, tried.toString());
        return new Look(NOT_FOUND, "", false, shut, tried.toString());
    }

    private void apply(Look look) {
        boolean different = look.state != state || !look.address.equals(address)
                || look.uploads != uploads || look.phoneDeck != phoneDeck;
        state = look.state;
        address = look.address;
        uploads = look.uploads;
        phoneDeck = look.phoneDeck;
        shut = look.shut;
        if (state != NOT_FOUND && !address.equals(usb)) {
            prefs.edit().putString(PREF_LAST, address).apply();
        }
        if (state == LINKED && !everLinked()) prefs.edit().putBoolean(PREF_LINKED, true).apply();
        if (state != LINKED) problem = "";
        note("Looked: " + look.tried);
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
        // What was heard and found on the last network says nothing about this one.
        found = "";
        swept.clear();
        toResolve.clear();
        searchedAt = NEVER;
        unaskedSearches = 0;
        lookedHarder = false;
        searchNote = "";
        String own = ownAddress();
        note(now.isEmpty() ? "Off the network" : !now.endsWith(" lan") ? "On mobile data"
                : own.isEmpty() ? "Joined a network" : "Joined a network: this phone is at " + own);
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
                public void onStartDiscoveryFailed(String type, final int error) {
                    final NsdManager.DiscoveryListener me = this;
                    main.post(() -> {
                        if (discovery != me) return;
                        discovery = null;
                        note("Listening for the Deck's announcement could not start (error "
                                + error + ")");
                        // Without an announcement to wait for, ask the network directly.
                        if (foreground && state == NOT_FOUND) search(false);
                    });
                }

                @Override
                public void onStopDiscoveryFailed(String type, int error) {
                }

                @Override
                public void onServiceFound(final NsdServiceInfo info) {
                    main.post(() -> {
                        note("Heard an announcement: " + info.getServiceName());
                        toResolve.add(info);
                        resolveNext();
                    });
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
            note("Listening for the Deck's announcement");
        } catch (RuntimeException e) {
            discovery = null;
            note("Listening for the Deck's announcement is not possible on this phone");
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
        toResolve.clear();
    }

    /**
     * Turns the next announcement heard into an address. One at a time: older
     * phones refuse a second while the first is under way. An announcement
     * that cannot be turned into a usable address still says a Deck is on
     * this network, so the network is asked directly instead.
     */
    private void resolveNext() {
        if (resolving || discovery == null || toResolve.isEmpty()) return;
        final NsdServiceInfo info = toResolve.remove(0);
        resolving = true;
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo i, final int error) {
                    main.post(() -> {
                        resolving = false;
                        if (discovery == null) return;
                        if (++resolveTries < RESOLVE_TRIES) {
                            toResolve.add(info);
                            main.postDelayed(Deck.this::resolveNext, 700);
                            return;
                        }
                        resolveTries = 0;
                        note("The announcement gave no address (error " + error + ")");
                        if (state == NOT_FOUND) search(false);
                        resolveNext();
                    });
                }

                @Override
                public void onServiceResolved(NsdServiceInfo i) {
                    final String at = addressOf(i);
                    main.post(() -> {
                        resolving = false;
                        resolveTries = 0;
                        if (at.isEmpty()) {
                            note("The announcement gave an address this phone cannot use");
                            if (state == NOT_FOUND) search(false);
                        } else if (!at.equals(found)) {
                            found = at;
                            note("The announcement says the Deck is at " + at);
                            refresh();
                        }
                        resolveNext();
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

    // ---- Asking the whole network ------------------------------------------------

    /**
     * Looks everywhere, and when nothing answers asks every address on this
     * phone's own network. That finds a Deck on the many home networks that
     * never pass its announcement on, and one whose address has changed.
     *
     * @param asked true when someone asked for it. Unasked searches are
     *        spaced out and stop after a few, so a phone left on the Deck
     *        screen away from home does not keep knocking on a network.
     */
    void search(boolean asked) {
        if (searching) return;
        if (!onLan()) {
            refresh();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!asked && (unaskedSearches >= UNASKED_SEARCHES || now - searchedAt < SEARCH_GAP_MS)) {
            refresh();
            return;
        }
        final List<String> ask = neighbours != null ? neighbours.get() : neighbourhood();
        if (ask.isEmpty()) {
            searchNote = "This network gives the phone no address to search from";
            note("Search: " + searchNote);
            refresh();
            return;
        }
        if (!asked) unaskedSearches++;
        searching = true;
        searchedAt = now;
        final String asking = ask.size() == 1 ? "1 address" : ask.size() + " addresses";
        note("Searching " + asking + " on this network");
        changed();
        searchIo.execute(() -> {
            final List<DeckLink.Hit> hits = DeckLink.sweep(ask, DeckLink.SWEEP_CONNECT_MS);
            main.post(() -> {
                searching = false;
                swept.clear();
                for (DeckLink.Hit h : hits) swept.add(h.address);
                searchNote = hits.isEmpty()
                        ? "Asked " + asking + " on this network. No Deck answered"
                        : "Found a Deck at " + hits.get(0).address;
                note("Search: " + searchNote);
                changed();
                refresh();
            });
        });
    }

    /** Every other address on this phone's network, or none when it is not a home network. */
    private List<String> neighbourhood() {
        String own = ownAddress();
        int slash = own.indexOf('/');
        if (slash < 0) return new ArrayList<>();
        int prefix;
        try {
            prefix = Integer.parseInt(own.substring(slash + 1));
        } catch (NumberFormatException e) {
            prefix = 24;
        }
        return DeckLink.neighbours(own.substring(0, slash), prefix, DeckLink.PORT);
    }

    /** This phone's IPv4 address and prefix on the network in use, as "192.168.1.57/24", or empty. */
    private String ownAddress() {
        try {
            ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
            Network n = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp == null) return "";
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) {
                    return la.getAddress().getHostAddress() + "/" + la.getPrefixLength();
                }
            }
        } catch (RuntimeException e) {
            // Not told: there is then nothing to search from.
        }
        return "";
    }

    // ---- The log ----------------------------------------------------------------

    /** Keeps one line of what happened. The same thing twice running is kept once. */
    private void note(String text) {
        if (text.equals(lastNote)) return;
        lastNote = text;
        notes.add(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + text);
        while (notes.size() > MAX_NOTES) notes.removeFirst();
    }

    /**
     * Where the link stands and what led there, in plain lines, for showing
     * and sharing. It names addresses on the home network and the phone's
     * model. The key this phone was handed is never in it.
     */
    String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("MAXAPACITY Ride ").append(Updater.versionName(app)).append(" on ")
                .append(device()).append(", Android ").append(Build.VERSION.RELEASE).append('\n');
        sb.append("Link: ").append(state == LINKED ? "linked " + (viaUsb() ? "over the cable"
                : "at " + address) : state == FOUND ? "Deck found at " + address
                + ", waiting for its code" : state == LOOKING ? "still looking" : "no Deck found")
                .append('\n');
        sb.append("Paired key on this phone: ")
                .append(prefs.getString(PREF_KEY, "").isEmpty() ? "no" : "yes").append('\n');
        sb.append("Let in before: ").append(everLinked() ? "yes" : "no").append('\n');
        if (state == LINKED) {
            sb.append("Ride upload on the Deck: ").append(uploads ? "on" : "off").append('\n');
        }
        sb.append("Rides waiting to go home: ").append(ridesWaiting()).append('\n');
        String own = ownAddress();
        sb.append("Network: ").append(!onLan() ? "not on Wi-Fi or a cabled network"
                : own.isEmpty() ? "on Wi-Fi, address not known" : "on Wi-Fi, this phone at " + own)
                .append('\n');
        sb.append("Listening for the Deck's announcement: ")
                .append(discovery != null ? "yes" : "no").append('\n');
        if (!found.isEmpty()) sb.append("Announced at: ").append(found).append('\n');
        if (!searchNote.isEmpty()) sb.append("Last search: ").append(searchNote).append('\n');
        if (!problem.isEmpty()) sb.append("Last trouble sending: ").append(problem).append('\n');
        sb.append('\n');
        for (String n : notes) sb.append(n).append('\n');
        return sb.toString().trim();
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
                note("Pairing at " + to + ": " + (p.ok ? "paired" : p.why));
                if (p.ok) {
                    if (p.key != null) prefs.edit().putString(PREF_KEY, p.key).apply();
                    done.done(true, "Paired with your Deck");
                    // Rides recorded before pairing go home now, not at the next look.
                    lookThen(() -> sendIfHome(null));
                } else if ("wrong".equals(p.why)) {
                    done.done(false, "That is not the code on the Deck");
                } else if ("closed".equals(p.why)) {
                    done.done(false, "That code is used up or has run out. On the Deck, open Add"
                            + " a screen again for a new one");
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
                done.done(false, "A Deck answered there but turned this phone away. Use its"
                        + " address in numbers, the way Add a screen shows it");
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
        note("This phone forgot the Deck");
        prefs.edit().remove(PREF_KEY).remove(PREF_LINKED).apply();
        refresh();
    }

    /** The key held now, so a forget can be undone while the Deck still knows this phone. */
    String key() {
        return prefs.getString(PREF_KEY, "");
    }

    /** Takes back a {@link #forget}: the key goes back where it was. */
    void remember(String key) {
        if (key == null || key.isEmpty()) return;
        note("Forget undone");
        prefs.edit().putString(PREF_KEY, key).apply();
        refresh();
    }

    /** The six digits after a '#' in a link copied from the Deck's Add a screen, or empty. */
    static String codeIn(String typed) {
        if (typed == null) return "";
        int hash = typed.indexOf('#');
        if (hash < 0) return "";
        String digits = typed.substring(hash + 1).replaceAll("\\D", "");
        return digits.length() == 6 ? digits : "";
    }

    /** "192.168.1." for "192.168.1.57": the part of its address a Deck shares with this phone. */
    static String sharedStart(String own) {
        int dot = own == null ? -1 : own.lastIndexOf('.');
        return dot < 0 ? "" : own.substring(0, dot + 1);
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
                    "climbM", "stops", "leanLeft", "leanRight", RideStore.BIKE, "bikeName"};
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
        progress = "Checking " + work.size() + (work.size() == 1 ? " ride" : " rides");
        sending = true;
        changed();
        final String to = address;
        final String key = requestKey();
        final String device = device();
        io.execute(() -> {
            List<DeckLink.Item> items = new ArrayList<>();
            int index = 0;
            for (final Work w : work) {
                final String label = "Sending ride " + (++index) + " of " + work.size();
                items.add(new DeckLink.Item(w.file, w.gpx.length(), w.mtimeSec, "ride",
                        () -> { progress = label; return new FileInputStream(w.gpx); }));
                items.add(new DeckLink.Item(w.summaryName, w.summary.length, w.mtimeSec, "ride",
                        () -> { progress = label; return new ByteArrayInputStream(w.summary); }));
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
                if (arrived > 0) prefs.edit().putLong("deck_last_sync", System.currentTimeMillis()).apply();
                String trouble = "";
                if (o.stopped != null) {
                    trouble = o.stopped;
                } else if (!o.refused.isEmpty()) {
                    Map.Entry<String, String> first = o.refused.entrySet().iterator().next();
                    trouble = "The Deck did not take " + first.getKey() + ": " + first.getValue();
                }
                problem = trouble;
                if (arrived > 0 && o.sentNow > 0) {
                    say(arrived == 1 ? "1 ride sent to your Deck"
                            : arrived + " rides sent to your Deck", true);
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
