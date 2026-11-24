package com.maxapacity.ride;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Receives the Strava sign-in redirect (maxapacity://localhost?code=...),
 * finishes the sign-in and puts the rider back in the app, on the screen
 * they left to connect.
 */
public final class StravaActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context app = getApplicationContext();
        // Read before the sign-in settles and forgets it.
        boolean forARide = !Units.prefs(app).getString("strava_then", "").isEmpty();
        // The app first, so its screen is up to say how the sign-in went. A
        // toast says it only when there is no such screen.
        try {
            startActivity(new Intent(this, RideActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("open", forARide ? "rides" : "settings"));
        } catch (RuntimeException e) {
            // The result is still stored, and Settings shows it next time.
        }
        Strava.finish(app, getIntent().getData(), (ok, message) -> {
            if (!Strava.hasScreen()) Toast.makeText(app, message, Toast.LENGTH_LONG).show();
        });
        finish();
    }
}
