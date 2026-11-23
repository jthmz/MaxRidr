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
import android.os.IBinder;

/**
 * Foreground service that keeps GPS running for the length of a ride, so the
 * trip keeps recording while the rider is in the maps or controller app.
 */
public final class RideService extends Service implements LocationListener {

    static final String ACTION_START = "com.maxapacity.ride.START";
    static final String ACTION_STOP = "com.maxapacity.ride.STOP";

    private static final String CHANNEL = "ride";
    private static final int NOTIFICATION_ID = 1;

    private LocationManager locationManager;

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
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        locationManager.removeUpdates(this);
        RideState.get().stop();
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
