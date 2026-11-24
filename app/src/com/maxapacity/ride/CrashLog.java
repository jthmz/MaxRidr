package com.maxapacity.ride;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps a note of the last crash. A home screen that stops unexpectedly is
 * hard to report: there is no error to copy and most riders have no cable or
 * tools to hand. So when the app dies, where it died is written to a small
 * file, and the next launch offers to show and share it.
 *
 * The note holds the app version, the phone model, the Android version and
 * the stack trace. Nothing else is recorded and nothing is sent anywhere
 * unless the rider shares it.
 */
final class CrashLog {

    private CrashLog() {
    }

    private static final String FILE = "last-crash.txt";

    /** Call once per process, as early as possible. */
    static void install(Context context) {
        final Context c = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                write(c, error);
            } catch (Throwable ignored) {
                // Never let the note-taking hide the real failure.
            }
            // Hand on to the system so the app still stops the usual way.
            if (next != null) next.uncaughtException(thread, error);
        });
    }

    private static void write(Context c, Throwable error) throws IOException {
        try (PrintWriter w = new PrintWriter(new File(c.getFilesDir(), FILE), "UTF-8")) {
            w.println("MAXAPACITY Ride " + Updater.versionName(c)
                    + " (build " + Updater.versionCode(c) + ")");
            w.println(Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
            w.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            w.println();
            error.printStackTrace(w);
        }
    }

    /** The saved note, or null when the last run ended normally. */
    static String read(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.isFile()) return null;
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return text.trim().isEmpty() ? null : text;
        } catch (IOException e) {
            return null;
        }
    }

    static void clear(Context c) {
        new File(c.getFilesDir(), FILE).delete();
    }
}
