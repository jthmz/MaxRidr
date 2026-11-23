package com.maxapacity.ride;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/** Receives the Strava sign-in redirect (maxapacity://localhost?code=...). */
public final class StravaActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri data = getIntent().getData();
        String code = data == null ? null : data.getQueryParameter("code");
        if (code == null || code.isEmpty()) {
            Toast.makeText(this, "Strava sign-in was cancelled", Toast.LENGTH_LONG).show();
        } else {
            final android.content.Context app = getApplicationContext();
            Strava.exchangeCode(app, code, (ok, message) ->
                    Toast.makeText(app, message, Toast.LENGTH_LONG).show());
        }
        finish();
    }
}
