package com.maxapacity.ride;

import android.app.Application;

/**
 * Process start-up. Exists so the crash note is armed before anything else
 * runs, whichever way the process was started (the home screen, the ride
 * recorder, or the flashlight gesture after a reboot).
 */
public final class RideApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
    }
}
