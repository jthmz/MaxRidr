package com.maxapacity.ride;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.telephony.SmsManager;
import android.widget.Toast;

import java.util.Locale;

/**
 * Foreground service that keeps GPS running for the length of a ride, so the
 * trip keeps recording while the rider is in the maps or controller app.
 *
 * With a crash contact set it also watches the accelerometer. A hard hit
 * followed by the bike not moving (see CrashDetector) starts a 30 second
 * alarm the rider can cancel; left alone, the rider's location is texted to
 * the contact.
 */
public final class RideService extends Service
        implements LocationListener, SensorEventListener {

    static final String ACTION_START = "com.maxapacity.ride.START";
    static final String ACTION_STOP = "com.maxapacity.ride.STOP";
    /** The rider answered the crash alarm: nothing is sent. */
    static final String ACTION_CRASH_OK = "com.maxapacity.ride.CRASH_OK";

    /** Setting: the number texted after a crash. Empty means the alert is off. */
    static final String CRASH_CONTACT = "crash_contact";
    static final long CRASH_COUNTDOWN_MS = 30000;
    private static final String CRASH_CHANNEL = "crash";
    private static final int CRASH_NOTIFICATION_ID = 3;

    private static final String CHANNEL = "ride";
    private static final int NOTIFICATION_ID = 1;

    /** A ride left stationary this long is ended and saved automatically. */
    static final long AUTO_END_MS = 10 * 60 * 1000;

    private LocationManager locationManager;

    private final CrashDetector crash = new CrashDetector();
    private final float[] gravity = new float[3];
    private boolean gravitySeeded;
    private boolean crashWatch;
    private boolean alarming;
    private SensorManager sensorManager;
    private PowerManager.WakeLock wakeLock;
    private Ringtone alarm;
    private final Runnable sendCrashText = this::sendCrashText;

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
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "maxapacity:crash");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CRASH_OK.equals(action)) {
            cancelCrashAlarm();
            if (!RideState.get().isRecording()) stopSelf();
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

        if (!RideState.get().isRecording()) RideState.get().start(InstalledGearing.atStart(this));
        startCrashWatch();
        handler.removeCallbacks(idleCheck);
        handler.postDelayed(idleCheck, 15000);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(idleCheck);
        // Ending the ride by hand answers the alarm too: the rider is fine.
        handler.removeCallbacks(sendCrashText);
        if (alarming) cancelCrashAlarm();
        if (crashWatch) sensorManager.unregisterListener(this);
        if (wakeLock.isHeld()) wakeLock.release();
        locationManager.removeUpdates(this);
        // Normally already saved by RideStore.finish; this covers the system
        // tearing the service down mid-ride.
        if (RideState.get().isRecording()) RideStore.finish(this);
        super.onDestroy();
    }

    @Override
    public void onLocationChanged(Location location) {
        RideState.get().onLocation(location);
        if (crashWatch) {
            crash.speed(location.hasSpeed() ? location.getSpeed() : 0f,
                    SystemClock.elapsedRealtime());
        }
    }

    // ---- Crash alert -------------------------------------------------------

    private String crashContact() {
        return Units.prefs(this).getString(CRASH_CONTACT, "").trim();
    }

    private void startCrashWatch() {
        if (crashWatch) return;
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel == null || crashContact().isEmpty()
                || checkSelfPermission(android.Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        crash.reset();
        gravitySeeded = false;
        crashWatch = true;
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        // Keeps the sensor delivering with the screen off, for this ride only.
        if (!wakeLock.isHeld()) wakeLock.acquire(12 * 60 * 60 * 1000L);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] a = event.values;
        if (!gravitySeeded) {
            System.arraycopy(a, 0, gravity, 0, 3);
            gravitySeeded = true;
            return;
        }
        // Low-pass tracks gravity; what is left is the knock itself.
        float sum = 0f;
        for (int i = 0; i < 3; i++) {
            gravity[i] = 0.8f * gravity[i] + 0.2f * a[i];
            float linear = a[i] - gravity[i];
            sum += linear * linear;
        }
        long now = SystemClock.elapsedRealtime();
        crash.accel((float) Math.sqrt(sum), now);
        if (!alarming && crash.confirmed(now)) startCrashAlarm();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private Notification.Builder crashNotification(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CRASH_CHANNEL,
                getString(R.string.crash_channel), NotificationManager.IMPORTANCE_HIGH));
        return new Notification.Builder(this, CRASH_CHANNEL)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
    }

    private void startCrashAlarm() {
        alarming = true;
        PendingIntent ok = PendingIntent.getService(this, 1,
                new Intent(this, RideService.class).setAction(ACTION_CRASH_OK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = crashNotification("Crash detected",
                "Texting your location to " + crashContact()
                        + " in 30 seconds. Tap if you are OK.")
                .setContentIntent(ok)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_bolt), "I'M OK", ok).build())
                .setOngoing(true)
                .build();
        getSystemService(NotificationManager.class).notify(CRASH_NOTIFICATION_ID, n);

        try {
            alarm = RingtoneManager.getRingtone(this,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            if (alarm != null) {
                alarm.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM).build());
                alarm.play();
            }
        } catch (RuntimeException e) {
            alarm = null; // No alarm sound on this phone; the vibration still runs.
        }
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[] {0, 600, 400}, 0));
        }
        handler.postDelayed(sendCrashText, CRASH_COUNTDOWN_MS);
    }

    private void stopCrashNoise() {
        try {
            if (alarm != null) alarm.stop();
        } catch (RuntimeException e) {
            // Already stopped.
        }
        alarm = null;
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) vibrator.cancel();
    }

    private void cancelCrashAlarm() {
        if (!alarming) return;
        alarming = false;
        handler.removeCallbacks(sendCrashText);
        stopCrashNoise();
        crash.reset();
        getSystemService(NotificationManager.class).cancel(CRASH_NOTIFICATION_ID);
        Toast.makeText(this, "Crash alert cancelled. Nothing was sent",
                Toast.LENGTH_LONG).show();
    }

    private void sendCrashText() {
        if (!alarming) return;
        alarming = false;
        stopCrashNoise();
        crash.reset();
        String to = crashContact();
        Location at = RideState.get().lastLocation();
        String text = "MAXAPACITY Ride: a crash was detected and the rider has not responded."
                + (at == null ? " No GPS position was available."
                : String.format(Locale.US, " Position: https://maps.google.com/?q=%.5f,%.5f",
                        at.getLatitude(), at.getLongitude()));
        boolean sent;
        try {
            SmsManager sms = getSystemService(SmsManager.class);
            sms.sendMultipartTextMessage(to, null, sms.divideMessage(text), null, null);
            sent = true;
        } catch (RuntimeException e) {
            // No SIM, no service or the permission was taken away.
            sent = false;
        }
        Notification n = crashNotification(
                sent ? "Crash alert sent" : "Crash alert could not be sent",
                sent ? "Your location was texted to " + to
                        : "The text to " + to + " failed. Call for help if you need it")
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, RideActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(CRASH_NOTIFICATION_ID, n);
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
