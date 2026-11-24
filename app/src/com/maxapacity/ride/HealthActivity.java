package com.maxapacity.ride;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * What the app does with Health Connect, in plain words. Android shows this
 * when the rider taps the privacy link on its Health Connect permission
 * screen, and will not offer the permissions to an app that has no such page.
 */
public final class HealthActivity extends Activity {

    static final String TEXT = "When you turn on Health Connect in this app's Settings, each"
            + " ride you save is written to Health Connect as a cycling workout: when it"
            + " started and ended, its route and its distance.\n\n"
            + "That is all the app writes, and it never reads anything from Health Connect.\n\n"
            + "Health Connect keeps this on your phone. Apps you have allowed to read"
            + " workouts there, such as Oura or Google Health, can then show your rides.\n\n"
            + "To stop, turn Health Connect off in this app's Settings. To remove rides"
            + " already shared, delete them in Health Connect.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        LinearLayout page = Ui.column(this);
        int pad = Ui.dp(this, 24);
        page.setPadding(pad, pad, pad, pad);
        android.widget.TextView title = Ui.title(this, "Rides and Health Connect");
        title.setSingleLine(false);
        page.addView(title, Ui.matchWrap());
        page.addView(Ui.body(this, TEXT, Ui.TEXT_2), Ui.top(this, Ui.matchWrap(), 16));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFitsSystemWindows(true);
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }
}
