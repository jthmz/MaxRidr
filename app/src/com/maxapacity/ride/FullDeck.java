package com.maxapacity.ride;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.HashMap;
import java.util.Map;

/**
 * The whole Deck on the phone: the same page the Car Thing shows, with its
 * tabs, sheets and controls, drawn by the Deck itself. Where {@link DeckView}
 * is a glance in the app's own style, this is the Deck. A phone left here on
 * a charger is a desk display.
 *
 * The page is let in with the key this phone was handed when it paired: the
 * Deck turns that into its own cookie on the first request. Down a USB cable
 * it needs nothing. Only the Deck's own address is ever shown here, and
 * whatever the Deck opens, it opens on the Mac.
 */
final class FullDeck extends Panel {

    /** How often the link is looked at again, so a Deck that moved or dropped is noticed. */
    private static final long CHECK_MS = 10000;
    /** The Deck's own black, so nothing flashes white while its page loads. */
    private static final int DECK_BLACK = 0xFF02060A;

    private final Host host;
    private final FrameLayout slot;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    /** The address the page on show came from. */
    private String shown = "";

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            Deck.get(getContext()).refresh();
            main.postDelayed(this, CHECK_MS);
        }
    };

    FullDeck(Context context, Host host) {
        super(context, "Deck");
        this.host = host;
        View link = Ui.iconButton(context, Icons.LINK, "Deck connection");
        link.setOnClickListener(v -> host.editDeckConnection());
        setHeaderAction(link);
        slot = new FrameLayout(context);
        // The Deck's page runs edge to edge: it is drawn for a small screen and wants every
        // pixel, so the page margin every other screen keeps is given back to it.
        setClipToPadding(false);
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.leftMargin = -dp(16);
        lp.rightMargin = -dp(16);
        addView(slot, lp);
    }

    @Override
    protected void onOpen() {
        stayAwake(true);
        show();
        main.removeCallbacks(check);
        main.postDelayed(check, CHECK_MS);
    }

    @Override
    protected void onClose() {
        stayAwake(false);
        main.removeCallbacks(check);
        drop();
    }

    /** Nothing is fetched or drawn while the launcher is in the background. */
    @Override
    void onHostPause() {
        main.removeCallbacks(check);
        if (web != null) web.onPause();
    }

    @Override
    void onHostResume() {
        stayAwake(true);
        if (web != null) web.onResume();
        main.removeCallbacks(check);
        main.postDelayed(check, CHECK_MS);
        show();
    }

    /** The link came up, dropped or moved to another address. */
    void linkChanged() {
        if (isOpen()) show();
    }

    /** The address the page on show was loaded from, or empty. */
    String showing() {
        return shown;
    }

    /**
     * A phone left on this screen on a charger is a desk display, so it stays
     * lit. On battery the screen times out as usual.
     */
    private void stayAwake(boolean showing) {
        BatteryManager battery = getContext().getSystemService(BatteryManager.class);
        setKeepScreenOn(showing && battery != null && battery.isCharging());
    }

    /** Puts on screen whatever is true of the link now: the Deck, or what is in the way. */
    private void show() {
        Deck d = Deck.get(getContext());
        if (d.state() != Deck.LINKED) {
            drop();
            boolean found = d.state() == Deck.FOUND;
            status.set(found ? "FOUND" : "NOT LINKED", found ? Ui.WARN : Ui.TEXT_3);
            say(Ui.emptyState(getContext(), Icons.MONITOR, "The whole Deck, on this phone",
                    found ? "Your Deck is in reach. Enter the 6-digit code it shows under Add a"
                            + " screen, and it opens here."
                            : "Everything the Car Thing shows, with all its controls. Link this"
                            + " phone to your Deck first.",
                    found ? "Enter code" : "Link your Deck", Ui.PRIMARY,
                    v -> host.editDeckConnection()));
            return;
        }
        String address = d.address();
        if (web != null && shown.equals(address)) return;
        drop();
        try {
            web = page(address);
        } catch (RuntimeException e) {
            // A phone whose system web view is missing, or in the middle of updating itself.
            web = null;
            status.set("UNAVAILABLE", Ui.DANGER);
            say(Ui.emptyState(getContext(), Icons.CIRCLE_ALERT,
                    "The Deck cannot be shown here",
                    "This phone's web view is missing or updating itself. Try again in a minute.",
                    "Try again", Ui.SECONDARY, v -> show()));
            return;
        }
        shown = address;
        status.set("CONNECTING", Ui.WARN);
        slot.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        Map<String, String> headers = new HashMap<>();
        String key = d.requestKey();
        if (!key.isEmpty()) headers.put("x-deck-token", key);
        web.loadUrl("http://" + address + "/", headers);
    }

    /**
     * Something to say in place of the Deck. The page runs edge to edge; words
     * keep the margin every other screen has.
     */
    private void say(View message) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        slot.addView(message, lp);
    }

    private void drop() {
        slot.removeAllViews();
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        shown = "";
    }

    private void failed() {
        drop();
        status.set("OFFLINE", Ui.DANGER);
        say(Ui.emptyState(getContext(), Icons.UNLINK, "Deck is out of reach",
                "The Deck stopped answering. Is the Mac awake and on this network?",
                "Try again", Ui.SECONDARY, v -> {
                    Deck.get(getContext()).refresh();
                    show();
                }));
        // Find out where the Deck is now; this screen follows the answer.
        Deck.get(getContext()).refresh();
    }

    /** The Deck's page is a program, so scripts are on. Nothing else is: no files, no location. */
    @SuppressLint("SetJavaScriptEnabled")
    private WebView page(String address) {
        final WebView w = new WebView(getContext());
        w.setBackgroundColor(DECK_BLACK);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setSupportZoom(false);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        CookieManager.getInstance().setAcceptCookie(true);
        final String home = "http://" + address + "/";
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Only the Deck itself is shown here; anything else is not followed.
                return !request.getUrl().toString().startsWith(home);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (view == web) status.set("LIVE", Ui.OK);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                    WebResourceError error) {
                if (view == web && request.isForMainFrame()) failed();
            }
        });
        return w;
    }
}
