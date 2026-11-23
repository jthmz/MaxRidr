package com.maxapacity.ride;

import android.content.ComponentName;
import android.content.Intent;
import android.view.ViewGroup;

import org.json.JSONObject;

/** What the screens ask of the activity that hosts them. */
interface Host {

    /** The layer sheets and message bars are added to. */
    ViewGroup frame();

    void say(String message);

    /** A message with one action, for changes that can be undone. */
    void say(String message, String action, Runnable run);

    void launch(Intent intent);

    /** Pin an app to the dashboard's ride-app row. */
    void pin(ComponentName app);

    /** Start speech recognition; the result goes to Copilot. */
    void listen();

    void editGeminiKey();

    void editDeckConnection();

    void connectStrava();

    void setChopEnabled(boolean enabled);

    /** A setting changed that the dashboard shows (units, effects). */
    void settingsChanged();

    void showRide(JSONObject ride);

    /** A ride was deleted, restored or changed status. */
    void ridesChanged();

    void showBike();

    /** Runs {@code then} once Bluetooth permission is held; explains if refused. */
    void withBluetooth(Runnable then);
}
