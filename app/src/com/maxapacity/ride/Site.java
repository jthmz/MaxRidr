package com.maxapacity.ride;

/**
 * The addresses on maxapacitybikes.com that the app opens in the phone's
 * browser. The site is where the app is described and offered; its
 * /apps.json lists this app, the Deck and the Studio and how they reach
 * each other.
 */
final class Site {

    private Site() {
    }

    static final String HOME = "https://maxapacitybikes.com";

    /** This app's own page: what it does, how to set it up, and questions. */
    static final String RIDE = HOME + "/apps/ride";
}
