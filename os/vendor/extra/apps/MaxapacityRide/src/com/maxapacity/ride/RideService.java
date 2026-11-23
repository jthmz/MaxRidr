package com.maxapacity.ride;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

/**
 * Foreground service that keeps GPS running for the length of a ride, so the
 * trip keeps recording while the rider is in the maps or controller app.
 */
public final class RideService extends Service implements LocationListener {

    static final String ACTION_START = "com.maxapacity.ride.START";
    static final String ACTION_STOP = "com.maxapacity.ride.STOP";

    private static final String CHANNEL = "ride";
    private static final int NOTIFICATION_ID = 1;

    /** A ride left stationary this long is ended and saved automatically. */
    static final long AUTO_END_MS = 10 * 60 * 1000;

    private LocationManager locationManager;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Timer rather than a per-fix check: indoors, fixes stop arriving at all. */
    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            RideState state = RideState.get();
            if (state.isRecording() && state.idleMs() >= AUTO_END_MS
                    && Units.prefs(RideService.this).getBoolean("auto_end", true)) {
                Toast.makeText(RideService.this, RideStore.finish(RideService.this),
                        Toast.LENGTH_LONG).show();
                stopSelf();
                return;
            }
            handler.postDelayed(this, 15000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, getString(R.string.ride_channel), NotificationManager.IMPORTANCE_LOW));

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, RideActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle(getString(R.string.ride_recording))
                .setContentIntent(open)
                .setOngoing(true)
                .build();

        try {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, this);
        } catch (SecurityException | IllegalArgumentException e) {
            // Location permission was revoked, or the device has no GPS provider.
            RideState.get().stop();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!RideState.get().isRecording()) RideState.get().start();
        handler.removeCallbacks(idleCheck);
        handler.postDelayed(idleCheck, 15000);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(idleCheck);
        locationManager.removeUpdates(this);
        // Normally already saved by RideStore.finish; this covers the system
        // tearing the service down mid-ride.
        if (RideState.get().isRecording()) RideStore.finish(this);
        super.onDestroy();
    }

    @Override
    public void onLocationChanged(Location location) {
        RideState.get().onLocation(location);
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
