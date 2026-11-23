package com.maxapacity.ride;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Over-the-air updates. CI publishes each build's APK next to a small
 * manifest (latest.json); the app reads the manifest, downloads a newer APK,
 * checks its SHA-256 against the manifest and hands it to the system
 * installer.
 *
 * The first update on a phone needs one tap on the system's install prompt.
 * After that this app is its own "installer of record", which lets Android
 * apply later updates without asking, so they install themselves the next
 * time the launcher leaves the screen. Nothing is installed during a ride.
 *
 * Android only accepts an update signed with the same key as the installed
 * app, so a tampered or foreign APK cannot replace it even if the download
 * host were compromised.
 */
final class Updater {

    private Updater() {
    }

    /** Public bucket on the project's Supabase storage; CI writes it. */
    static final String MANIFEST_URL = "https://dmrjgofvupbsgtjdzcct.supabase.co"
            + "/storage/v1/object/public/ride-app/latest.json";

    static final int IDLE = 0;
    static final int CHECKING = 1;
    static final int DOWNLOADING = 2;
    static final int READY = 3;
    static final int INSTALLING = 4;
    static final int FAILED = 5;

    private static final long AUTO_CHECK_EVERY_MS = 6 * 60 * 60 * 1000L;
    private static final String ACTION_STATUS = "com.maxapacity.ride.UPDATE_STATUS";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    private static int state = IDLE;
    private static int progressPct;
    private static String error;
    private static String readyVersion;
    private static long readyCode;

    static void addListener(Runnable r) {
        if (!LISTENERS.contains(r)) LISTENERS.add(r);
    }

    static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static void set(int newState) {
        state = newState;
        for (int i = LISTENERS.size() - 1; i >= 0; i--) LISTENERS.get(i).run();
    }

    static int state() {
        return state;
    }

    // ---- What is installed -------------------------------------------------

    static long versionCode(Context c) {
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static String versionName(Context c) {
        try {
            String name = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return name == null ? "?" : name;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    static boolean autoEnabled(Context c) {
        return Units.prefs(c).getBoolean("auto_update", true);
    }

    /** One line for Settings: what the updater is doing or last found. */
    static String statusLine(Context c) {
        switch (state) {
            case CHECKING:
                return "Checking for updates";
            case DOWNLOADING:
                return "Downloading " + readyVersion + "  " + progressPct + "%";
            case READY:
                return readyVersion + " is ready to install";
            case INSTALLING:
                return "Installing " + readyVersion;
            case FAILED:
                return error;
            default:
                break;
        }
        String lastError = Units.prefs(c).getString("update_error", "");
        if (!lastError.isEmpty()) return lastError;
        long checked = Units.prefs(c).getLong("update_checked", 0);
        if (checked == 0) return "Not checked yet";
        return "Up to date. Checked " + ago(System.currentTimeMillis() - checked);
    }

    /** True when the status line is reporting a problem. */
    static boolean hasProblem(Context c) {
        return state == FAILED || (state == IDLE
                && !Units.prefs(c).getString("update_error", "").isEmpty());
    }

    private static String ago(long ms) {
        long min = Math.max(0, ms / 60000);
        if (min < 1) return "just now";
        if (min < 60) return min + " min ago";
        long h = min / 60;
        if (h < 24) return h + " h ago";
        return (h / 24) + " d ago";
    }

    // ---- Checking ----------------------------------------------------------

    /** Background check on resume, at most every six hours. */
    static void autoCheck(Context context, Host host) {
        if (!autoEnabled(context)) return;
        if (state == READY) {
            offerInstall(context, host);
            return;
        }
        long last = Units.prefs(context).getLong("update_checked", 0);
        if (System.currentTimeMillis() - last < AUTO_CHECK_EVERY_MS) return;
        check(context, host, false);
    }

    /**
     * @param manual true when the rider asked: outcomes are always reported.
     *               A background check only speaks up when there is an update.
     */
    static void check(Context context, final Host host, final boolean manual) {
        if (state == CHECKING || state == DOWNLOADING || state == INSTALLING) return;
        if (state == READY) {
            offerInstall(context, host);
            return;
        }
        final Context c = context.getApplicationContext();
        final long installed = versionCode(c);
        error = null;
        set(CHECKING);
        IO.execute(() -> {
            try {
                JSONObject m = new JSONObject(new String(
                        fetch(MANIFEST_URL + "?t=" + System.currentTimeMillis(), 1 << 16),
                        StandardCharsets.UTF_8));
                final long code = m.getLong("versionCode");
                final String name = m.optString("versionName", String.valueOf(code));
                final String url = m.getString("url");
                final String sha256 = m.getString("sha256").toLowerCase(Locale.US);
                final long size = m.optLong("size", 0);
                Units.prefs(c).edit()
                        .putLong("update_checked", System.currentTimeMillis())
                        .remove("update_error").apply();
                if (code <= installed) {
                    MAIN.post(() -> {
                        set(IDLE);
                        if (manual) host.say("You have the latest version, " + versionName(c));
                    });
                    return;
                }
                MAIN.post(() -> {
                    readyVersion = name;
                    readyCode = code;
                    progressPct = 0;
                    set(DOWNLOADING);
                });
                File apk = apkFile(c);
                download(url, apk, size);
                if (!sha256.equals(sha256(apk))) {
                    apk.delete();
                    throw new IOException("The download did not match its checksum");
                }
                MAIN.post(() -> {
                    set(READY);
                    offerInstall(c, host);
                });
            } catch (IOException | JSONException e) {
                final String message = e instanceof IOException
                        ? offlineMessage((IOException) e)
                        : "The update listing could not be read";
                MAIN.post(() -> {
                    error = message;
                    set(FAILED);
                    // Leave FAILED so the next check can run, but keep the message.
                    state = IDLE;
                    Units.prefs(c).edit().putString("update_error", message).apply();
                    if (manual) host.say(message);
                });
            }
        });
    }

    private static String offlineMessage(IOException e) {
        String m = e.getMessage();
        if (m != null && m.startsWith("The ")) return m;
        if (m != null && m.startsWith("HTTP 4")) return "No update has been published yet";
        return "Could not reach the update server. Check the connection and try again";
    }

    private static File apkFile(Context c) {
        return new File(c.getCacheDir(), "update.apk");
    }

    // ---- Installing --------------------------------------------------------

    private static void offerInstall(Context c, Host host) {
        if (canInstallSilently(c)) {
            host.say("Update " + readyVersion + " downloaded. It installs when you leave"
                    + " this screen");
        } else {
            host.say("Update " + readyVersion + " is ready", "INSTALL", () -> install(c));
        }
    }

    /**
     * Android lets an app update itself without a prompt once it is its own
     * installer of record, which is the case after the first update it
     * installed.
     */
    static boolean canInstallSilently(Context c) {
        try {
            String installer = c.getPackageManager()
                    .getInstallSourceInfo(c.getPackageName()).getInstallingPackageName();
            return c.getPackageName().equals(installer);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Called as the launcher leaves the screen: apply a waiting update quietly. */
    static void installIfQuiet(Context c) {
        if (state != READY || !autoEnabled(c) || !canInstallSilently(c)) return;
        if (RideState.get().isRecording()) return;
        install(c);
    }

    static void install(Context context) {
        if (state != READY) return;
        final Context c = context.getApplicationContext();
        if (RideState.get().isRecording()) {
            error = "The update will install after this ride ends";
            set(READY);
            return;
        }
        File apk = apkFile(c);
        if (!apk.isFile()) {
            set(IDLE);
            return;
        }
        set(INSTALLING);
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE);
        }
        try {
            int id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (OutputStream out = session.openWrite("base.apk", 0, apk.length());
                     InputStream in = new FileInputStream(apk)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent status = new Intent(c, Receiver.class).setAction(ACTION_STATUS);
                // Mutable: the installer adds the result extras to this intent.
                PendingIntent pi = PendingIntent.getBroadcast(c, id, status,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pi.getIntentSender());
            }
        } catch (IOException | RuntimeException e) {
            fail(c, "The update could not be handed to the installer");
        }
    }

    private static void fail(Context c, String message) {
        error = message;
        Units.prefs(c).edit().putString("update_error", message).apply();
        set(FAILED);
        state = IDLE;
    }

    /** Result of an install session, delivered by the system. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            switch (status) {
                case PackageInstaller.STATUS_PENDING_USER_ACTION:
                    // First update on this phone: the system needs one confirmation.
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        try {
                            c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (RuntimeException e) {
                            fail(c, "Open Settings and tap Install update to finish");
                            state = READY;
                        }
                    }
                    break;
                case PackageInstaller.STATUS_SUCCESS:
                    // The app is normally replaced before this arrives.
                    apkFile(c).delete();
                    Units.prefs(c).edit().remove("update_error").apply();
                    set(IDLE);
                    break;
                case PackageInstaller.STATUS_FAILURE_ABORTED:
                    // The rider dismissed the prompt; keep the download for later.
                    set(READY);
                    break;
                case PackageInstaller.STATUS_FAILURE_CONFLICT:
                case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                    apkFile(c).delete();
                    fail(c, "This update is signed with a different key than the installed"
                            + " app. Reinstall once over USB to switch to the new builds");
                    break;
                case PackageInstaller.STATUS_FAILURE_STORAGE:
                    fail(c, "Not enough free storage to install the update");
                    break;
                default:
                    apkFile(c).delete();
                    fail(c, "The update did not install. It will be tried again");
                    break;
            }
        }
    }

    // ---- Network -----------------------------------------------------------

    private static byte[] fetch(String url, int maxBytes) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setUseCaches(false);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > maxBytes) throw new IOException("The listing is too large");
                }
                return buf.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void download(String url, File to, long expectedSize) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("The update link is not secure");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(30000);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long total = expectedSize > 0 ? expectedSize : conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(to)) {
                byte[] chunk = new byte[1 << 16];
                long done = 0;
                int shown = -1;
                int n;
                while ((n = in.read(chunk)) > 0) {
                    out.write(chunk, 0, n);
                    done += n;
                    if (done > 200L * 1024 * 1024) throw new IOException("The update is too large");
                    final int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
                    if (pct != shown) {
                        shown = pct;
                        MAIN.post(() -> {
                            progressPct = pct;
                            set(DOWNLOADING);
                        });
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[1 << 16];
            int n;
            while ((n = in.read(chunk)) > 0) md.update(chunk, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format(Locale.US, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("The checksum could not be computed");
        }
    }
}
