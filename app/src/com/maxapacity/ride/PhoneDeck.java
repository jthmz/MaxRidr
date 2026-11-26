package com.maxapacity.ride;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.nsd.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.SecureRandom;

/** A rider opts in on the home phone. Listen only on its current Wi-Fi address. */
public final class PhoneDeck extends Service {
    static volatile String status = "Starting receiver";
    static volatile boolean running;
    private static final SecureRandom random = new SecureRandom();
    private static String code = "";
    private static long expires;
    private static int attempts;
    private volatile boolean closing;
    private volatile ServerSocket listener;
    private final ExecutorService workers = new ThreadPoolExecutor(0, 6, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>());

    static synchronized String newCode() {
        code = String.format(Locale.US, "%06d", random.nextInt(1000000));
        expires = SystemClock.elapsedRealtime() + 300000;
        attempts = 5;
        return code;
    }

    static synchronized boolean acceptCode(String guess) {
        if (attempts <= 0 || SystemClock.elapsedRealtime() >= expires) return false;
        attempts--;
        if (!code.equals(guess)) return false;
        attempts = 0;
        return true;
    }

    static void enable(Context context, boolean enabled) {
        context.getSharedPreferences("home-deck", 0).edit().putBoolean("enabled", enabled).apply();
        Intent service = new Intent(context, PhoneDeck.class);
        if (enabled) context.startForegroundService(service);
        else context.stopService(service);
    }

    public static final class BootReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                    && context.getSharedPreferences("home-deck", 0).getBoolean("enabled", false)) {
                context.startForegroundService(new Intent(context, PhoneDeck.class));
            }
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("phone-deck", "Home Deck",
                NotificationManager.IMPORTANCE_LOW));
        startForeground(8791, new Notification.Builder(this, "phone-deck")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("This phone is your home Deck")
                .setContentText("Receiving paired riders on Wi-Fi").setOngoing(true).build());
        running = true;
        new Thread(this::receive, "home-deck").start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        closing = true;
        running = false;
        synchronized (PhoneDeck.class) { attempts = 0; }
        ServerSocket server = listener;
        if (server != null) try { server.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
        super.onDestroy();
    }

    private InetAddress wifiAddress() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null;
        LinkProperties link = cm.getLinkProperties(network);
        if (link != null) for (LinkAddress item : link.getLinkAddresses()) {
            if (item.getAddress() instanceof Inet4Address) return item.getAddress();
        }
        return null;
    }

    private void receive() {
        while (!closing) {
            InetAddress ip = wifiAddress();
            if (ip == null) status = "Waiting for home Wi-Fi";
            else try (ServerSocket server = new ServerSocket()) {
                listener = server;
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(ip, 8791));
                server.setSoTimeout(2000);
                NsdManager nsd = getSystemService(NsdManager.class);
                NsdServiceInfo info = new NsdServiceInfo();
                info.setServiceName("MAXAPACITY Phone Deck");
                info.setServiceType("_maxdeck._tcp");
                info.setPort(8791);
                NsdManager.RegistrationListener registration = new NsdManager.RegistrationListener() {
                    public void onServiceRegistered(NsdServiceInfo s) {}
                    public void onServiceUnregistered(NsdServiceInfo s) {}
                    public void onRegistrationFailed(NsdServiceInfo s, int error) {}
                    public void onUnregistrationFailed(NsdServiceInfo s, int error) {}
                };
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
                try {
                    status = "Listening at " + ip.getHostAddress() + ":8791";
                    while (!closing && ip.equals(wifiAddress())) {
                        try {
                            Socket socket = server.accept();
                            if (ip.equals(socket.getInetAddress())) socket.close();
                            else try {
                                workers.execute(() -> PhoneDeckRequest.handle(this, socket));
                            } catch (RejectedExecutionException busy) { socket.close(); }
                        } catch (SocketTimeoutException ignored) {}
                    }
                } finally { nsd.unregisterService(registration); }
            } catch (IOException | RuntimeException e) {
                status = "Wi-Fi receiver unavailable. Retrying…";
            }
            if (!closing) SystemClock.sleep(2000);
        }
    }
}
