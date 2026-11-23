package com.maxapacity.ride;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.UUID;

/**
 * One Bluetooth LE serial-style link: find a device by name, connect, turn on
 * notifications for one characteristic and write to the same one. Both the
 * ANT BMS and the Fardriver controller talk this way (service FFE0), so they
 * share this class and differ only in the characteristic and the bytes.
 *
 * Devices are found by scanning for their saved name each time rather than
 * by address, because some controller Bluetooth modules pick a new random
 * address at every power-up.
 *
 * Every callback is delivered on the main thread.
 */
final class BleLink {

    interface Listener {
        /** Notifications are on; it is now safe to write. */
        void onReady();

        void onData(byte[] value);

        /** The link dropped or could not be made. {@code why} is for the rider. */
        void onClosed(String why);
    }

    /** A device seen while scanning. */
    interface Finder {
        void onFound(String name, String address);
    }

    static final UUID SERVICE = uuid(0xFFE0);
    private static final UUID CCCD = uuid(0x2902);

    static UUID uuid(int shortId) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", shortId));
    }

    private static final long SCAN_TIMEOUT_MS = 12000;

    private final Context context;
    private final UUID characteristicId;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> writes = new ArrayDeque<>();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic characteristic;
    private ScanCallback scan;
    private boolean ready;
    private boolean writing;
    private boolean closed = true;
    /** Bumped on every open/close so late callbacks from an old attempt are ignored. */
    private int session;

    BleLink(Context context, UUID characteristicId, Listener listener) {
        this.context = context.getApplicationContext();
        this.characteristicId = characteristicId;
        this.listener = listener;
    }

    boolean isReady() {
        return ready;
    }

    boolean isOpen() {
        return !closed;
    }

    private static BluetoothAdapter adapter(Context c) {
        BluetoothManager m = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    /** Null when Bluetooth can be used, else the reason it cannot. */
    static String unavailableReason(Context c) {
        BluetoothAdapter a = adapter(c);
        if (a == null) return "This phone has no Bluetooth";
        try {
            if (!a.isEnabled()) return "Bluetooth is off";
        } catch (SecurityException e) {
            return "Bluetooth permission is needed";
        }
        return null;
    }

    // ---- Scanning ----------------------------------------------------------

    /**
     * Lists nearby devices until {@link #stopScan(Context, ScanCallback)}.
     *
     * @return the handle to stop with, or null if scanning could not start
     */
    static ScanCallback scan(Context c, final Finder finder) {
        BluetoothAdapter a = adapter(c);
        if (a == null) return null;
        final Handler main = new Handler(Looper.getMainLooper());
        ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult result) {
                final String name = result.getScanRecord() == null ? null
                        : result.getScanRecord().getDeviceName();
                final String address = result.getDevice().getAddress();
                if (name == null || name.isEmpty()) return;
                main.post(() -> finder.onFound(name, address));
            }
        };
        try {
            BluetoothLeScanner scanner = a.getBluetoothLeScanner();
            if (scanner == null) return null;
            scanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb);
            return cb;
        } catch (SecurityException | IllegalStateException e) {
            return null;
        }
    }

    static void stopScan(Context c, ScanCallback cb) {
        if (cb == null) return;
        BluetoothAdapter a = adapter(c);
        try {
            if (a != null && a.getBluetoothLeScanner() != null) {
                a.getBluetoothLeScanner().stopScan(cb);
            }
        } catch (SecurityException | IllegalStateException e) {
            // Bluetooth was switched off mid-scan; nothing left to stop.
        }
    }

    // ---- Connecting --------------------------------------------------------

    /** Finds the device advertising this name and connects to it. */
    void open(final String name) {
        close();
        closed = false;
        final int mine = ++session;
        String why = unavailableReason(context);
        if (why != null) {
            fail(mine, why);
            return;
        }
        scan = scan(context, (found, address) -> {
            if (mine != session || scan == null || !found.equals(name)) return;
            stopScan(context, scan);
            scan = null;
            connect(mine, address);
        });
        if (scan == null) {
            fail(mine, "Bluetooth scanning could not start");
            return;
        }
        main.postDelayed(() -> {
            if (mine == session && scan != null) fail(mine, name + " is not in range");
        }, SCAN_TIMEOUT_MS);
    }

    private void connect(final int mine, String address) {
        try {
            BluetoothDevice device = adapter(context).getRemoteDevice(address);
            gatt = device.connectGatt(context, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        try {
                            g.discoverServices();
                        } catch (SecurityException e) {
                            main.post(() -> fail(mine, "Bluetooth permission is needed"));
                        }
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        main.post(() -> fail(mine, "The link dropped"));
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {
                    main.post(() -> subscribe(mine, g));
                }

                @Override
                public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d,
                        int status) {
                    main.post(() -> {
                        if (mine != session) return;
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            fail(mine, "The device refused notifications");
                            return;
                        }
                        ready = true;
                        listener.onReady();
                    });
                }

                // Android 13 and later hand the bytes over directly.
                @Override
                public void onCharacteristicChanged(BluetoothGatt g,
                        BluetoothGattCharacteristic c, byte[] value) {
                    deliver(value);
                }

                // Android 12 leaves them on the characteristic.
                @Override
                @SuppressWarnings("deprecation")
                public void onCharacteristicChanged(BluetoothGatt g,
                        BluetoothGattCharacteristic c) {
                    deliver(c.getValue());
                }

                private void deliver(byte[] value) {
                    if (value == null) return;
                    final byte[] copy = value.clone();
                    main.post(() -> {
                        if (mine == session) listener.onData(copy);
                    });
                }

                @Override
                public void onCharacteristicWrite(BluetoothGatt g,
                        BluetoothGattCharacteristic c, int status) {
                    main.post(() -> {
                        if (mine != session) return;
                        writing = false;
                        pump();
                    });
                }
            }, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) fail(mine, "Could not connect");
        } catch (SecurityException | IllegalArgumentException e) {
            fail(mine, "Could not connect");
        }
    }

    private void subscribe(int mine, BluetoothGatt g) {
        if (mine != session) return;
        try {
            BluetoothGattService service = g.getService(SERVICE);
            characteristic = service == null ? null : service.getCharacteristic(characteristicId);
            if (characteristic == null) {
                fail(mine, "This device does not speak the expected protocol");
                return;
            }
            g.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor d = characteristic.getDescriptor(CCCD);
            if (d == null) {
                // No descriptor to write: notifications are already on.
                ready = true;
                listener.onReady();
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                writeDescriptorLegacy(g, d);
            }
        } catch (SecurityException e) {
            fail(mine, "Bluetooth permission is needed");
        }
    }

    @SuppressWarnings("deprecation")
    private static void writeDescriptorLegacy(BluetoothGatt g, BluetoothGattDescriptor d) {
        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        g.writeDescriptor(d);
    }

    // ---- Writing -----------------------------------------------------------

    /** Queues bytes to send, without response, one frame at a time. */
    void write(byte[] value) {
        if (!ready) return;
        writes.add(value);
        pump();
    }

    private void pump() {
        if (writing || !ready || writes.isEmpty()) return;
        byte[] value = writes.poll();
        final int mine = session;
        try {
            boolean sent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sent = gatt.writeCharacteristic(characteristic, value,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        == android.bluetooth.BluetoothStatusCodes.SUCCESS;
            } else {
                sent = writeLegacy(value);
            }
            if (sent) {
                writing = true;
                // If the write callback never comes, do not stall the queue.
                main.postDelayed(() -> {
                    if (mine == session && writing) {
                        writing = false;
                        pump();
                    }
                }, 300);
            } else {
                // The stack was busy: put it back and retry shortly.
                writes.addFirst(value);
                main.postDelayed(() -> {
                    if (mine == session) pump();
                }, 40);
            }
        } catch (SecurityException e) {
            fail(mine, "Bluetooth permission is needed");
        }
    }

    @SuppressWarnings("deprecation")
    private boolean writeLegacy(byte[] value) {
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        characteristic.setValue(value);
        return gatt.writeCharacteristic(characteristic);
    }

    // ---- Closing -----------------------------------------------------------

    private void fail(int mine, String why) {
        if (mine != session || closed) return;
        close();
        listener.onClosed(why);
    }

    /** Drops the link quietly; no callback follows. */
    void close() {
        session++;
        closed = true;
        ready = false;
        writing = false;
        writes.clear();
        if (scan != null) {
            stopScan(context, scan);
            scan = null;
        }
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException e) {
                // Permission was revoked; the system has closed it already.
            }
            gatt = null;
        }
        characteristic = null;
    }
}
