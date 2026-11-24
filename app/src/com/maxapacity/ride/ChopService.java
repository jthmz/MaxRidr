package com.maxapacity.ride;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * "Chop twice for flashlight": watches the accelerometer for two sharp chopping
 * motions in quick succession and toggles the torch.
 *
 * Runs as a foreground service holding a partial wake lock so the gesture
 * also works with the screen off. Ignored while a ride is recording, because
 * trail hits on a bar-mounted phone look exactly like chops.
 */
public final class ChopService extends Service implements SensorEventListener {

    static final String PREF = "chop";

    /** Acceleration beyond gravity that counts as a chop, in m/s^2 (about 1.8 g). */
    private static final float CHOP_THRESHOLD = 18f;
    /** Spikes closer than this belong to the same chop (swing plus stop). */
    private static final long SAME_CHOP_MS = 250;
    /** The second chop must land within this window after the first. */
    private static final long CHOP_WINDOW_MS = 900;
    /** Quiet period after a toggle, so the follow-through does not undo it. */
    private static final long COOLDOWN_MS = 1500;

    private static final String CHANNEL = "chop";
    private static final int NOTIFICATION_ID = 2;

    /** Starts the service after a reboot when the gesture is switched on. */
    public static final class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
            if (isEnabled(context)) {
                context.startForegroundService(new Intent(context, ChopService.class));
            }
        }
    }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences("ride", Context.MODE_PRIVATE)
                .getBoolean(PREF, false);
    }

    private final float[] gravity = new float[3];
    private boolean gravitySeeded;
    private long lastSpikeMs;
    private long firstChopMs;
    private long cooldownUntilMs;

    private SensorManager sensorManager;
    private CameraManager cameraManager;
    private PowerManager.WakeLock wakeLock;
    private String torchCameraId;
    private boolean torchOn;

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchCameraId)) torchOn = enabled;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        torchCameraId = findTorchCamera();
        if (torchCameraId != null) cameraManager.registerTorchCallback(torchCallback, null);
        wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "maxapacity:chop");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel == null || torchCameraId == null || !isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, getString(R.string.chop_channel), NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle(getString(R.string.chop_active))
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, RideActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (RuntimeException e) {
            // Not allowed to start from the background right now; the launcher
            // starts it again the next time it is on screen.
            stopSelf();
            return START_NOT_STICKY;
        }

        sensorManager.unregisterListener(this);
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        if (!wakeLock.isHeld()) wakeLock.acquire();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sensorManager.unregisterListener(this);
        if (torchCameraId != null) cameraManager.unregisterTorchCallback(torchCallback);
        if (wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] a = event.values;
        if (!gravitySeeded) {
            System.arraycopy(a, 0, gravity, 0, 3);
            gravitySeeded = true;
            return;
        }
        // Low-pass tracks gravity; what is left is the hand's own acceleration.
        float sum = 0f;
        for (int i = 0; i < 3; i++) {
            gravity[i] = 0.8f * gravity[i] + 0.2f * a[i];
            float linear = a[i] - gravity[i];
            sum += linear * linear;
        }
        if (sum < CHOP_THRESHOLD * CHOP_THRESHOLD) return;

        long now = SystemClock.elapsedRealtime();
        boolean sameChop = now - lastSpikeMs < SAME_CHOP_MS;
        lastSpikeMs = now;
        if (sameChop || now < cooldownUntilMs || RideState.get().isRecording()) return;

        if (firstChopMs != 0 && now - firstChopMs <= CHOP_WINDOW_MS) {
            firstChopMs = 0;
            cooldownUntilMs = now + COOLDOWN_MS;
            toggleTorch();
        } else {
            firstChopMs = now;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void toggleTorch() {
        try {
            cameraManager.setTorchMode(torchCameraId, !torchOn);
        } catch (CameraAccessException | IllegalArgumentException e) {
            return; // Camera in use by another app; nothing to toggle.
        }
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    /** The rear camera with a flash unit, or the first camera that has one. */
    private String findTorchCamera() {
        try {
            String fallback = null;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                if (!Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                    continue;
                }
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
                if (fallback == null) fallback = id;
            }
            return fallback;
        } catch (CameraAccessException e) {
            return null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
