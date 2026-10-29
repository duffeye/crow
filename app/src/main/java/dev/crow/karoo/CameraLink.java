package dev.crow.karoo;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.security.SecureRandom;
import java.util.*;

/** Foreground-only BLE session. All state and GATT operations are serialized on the main thread. */
@android.annotation.SuppressLint("MissingPermission") // Activity gates BLE entry points with runtime permissions.
@SuppressWarnings("deprecation")
final class CameraLink {
    interface Listener { void changed(); void found(BluetoothDevice device, String name); }
    private static UUID uuid(int value) { return UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", value)); }
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final SharedPreferences preferences;
    private final ArrayDeque<byte[]> writes = new ArrayDeque<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx;
    private Protocol.Decoder decoder = new Protocol.Decoder();
    private int sequence, pendingSeq = -1;
    private int pendingSet, pendingId, targetMode = -1;
    private boolean writing, scanning;
    private boolean reconnecting;
    private Runnable writeTimeout, commandTimeout, connectTimeout;
    String message = "Turn on the Action 4, then find your camera.";
    boolean ready, busy;
    int mode = -1, state = -1, battery = -1, seconds;
    long statusAt;

    CameraLink(Context context, Listener listener) {
        this.context = context; this.listener = listener;
        preferences = context.getSharedPreferences("camera", Context.MODE_PRIVATE);
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }
    boolean fresh() { return ready && android.os.SystemClock.elapsedRealtime() - statusAt < 5000; }
    void report(String text) { message = text; listener.changed(); }
    private void fail(String text) { close(); report(text); }
    void reconnectSaved() {
        if (gatt != null || scanning) return;
        String address = preferences.getString("paired_address", null);
        if (address == null) { report("Find your camera to pair. It will reconnect automatically next time."); return; }
        if (adapter == null || !adapter.isEnabled()) { report("Enable Bluetooth, then tap Reconnect."); return; }
        try { connect(adapter.getRemoteDevice(address)); }
        catch (IllegalArgumentException e) { report("Saved camera address is invalid. Tap Forget camera, then Find camera."); }
    }
    void forgetCamera() {
        close(); preferences.edit().remove("paired_address").apply();
        report("Camera forgotten. Tap Find camera to pair again.");
    }
    void scan() {
        close();
        if (adapter == null || !adapter.isEnabled()) { report("Enable Bluetooth in Karoo settings first."); return; }
        scanning = true;
        try {
            adapter.getBluetoothLeScanner().startScan(scanCallback);
            report("Searching for DJI cameras…");
            handler.postDelayed(stopScan, 12000);
        } catch (RuntimeException e) { fail("Bluetooth scan failed: " + e.getMessage()); }
    }
    private final Runnable stopScan = () -> {
        if (!scanning) return;
        stopScanning(); report("Search complete. Select your camera, or search again.");
    };
    private void stopScanning() {
        handler.removeCallbacks(stopScan);
        if (scanning && adapter != null && adapter.getBluetoothLeScanner() != null)
            adapter.getBluetoothLeScanner().stopScan(scanCallback);
        scanning = false;
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanFailed(int code) { handler.post(() -> fail("Scan failed (" + code + "). Try again.")); }
        @Override public void onScanResult(int type, ScanResult result) {
            ScanRecord record = result.getScanRecord();
            if (record == null) return;
            // Android strips the little-endian manufacturer ID (AA 08) from its data value.
            byte[] data = record.getManufacturerSpecificData(0x08aa);
            if (data == null || data.length < 3 || (data[2] & 255) != 0xfa) return;
            String name = record.getDeviceName();
            handler.post(() -> { if (scanning) listener.found(result.getDevice(), name == null ? "DJI camera" : name); });
        }
    };
    void connect(BluetoothDevice device) {
        close(); decoder = new Protocol.Decoder();
        reconnecting = device.getAddress().equals(preferences.getString("paired_address", null));
        report(reconnecting ? "Reconnecting to your camera…" : "Connecting… Keep the camera screen visible.");
        try {
            gatt = device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
            connectTimeout = () -> fail("Connection timed out. Wake the camera and tap Reconnect, or use Find camera.");
            handler.postDelayed(connectTimeout, 45000);
        } catch (RuntimeException e) { fail("Connection failed: " + e.getMessage()); }
    }
    private void cancel(Runnable task) { if (task != null) handler.removeCallbacks(task); }
    void close() {
        stopScanning(); cancel(connectTimeout); cancel(writeTimeout); cancel(commandTimeout);
        ready = false; busy = false; writing = false; pendingSeq = -1; targetMode = -1; statusAt = 0;
        mode = state = battery = -1; writes.clear(); tx = null;
        BluetoothGatt old = gatt; gatt = null;
        if (old != null) { old.disconnect(); old.close(); }
    }
    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            handler.post(() -> {
                if (g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) { fail("Camera disconnected. Recording may continue on the camera."); return; }
                if (newState == BluetoothProfile.STATE_CONNECTED && !g.discoverServices()) fail("Cannot discover camera services.");
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            handler.post(() -> {
                if (g != gatt) return;
                BluetoothGattService service = g.getService(uuid(0xfff0));
                if (status != 0 || service == null) { fail("Camera does not expose DJI remote service FFF0."); return; }
                tx = service.getCharacteristic(uuid(0xfff5));
                BluetoothGattCharacteristic rx = service.getCharacteristic(uuid(0xfff4));
                if (tx == null || rx == null) { fail("Camera remote characteristics are missing."); return; }
                int properties = tx.getProperties();
                if ((properties & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) { fail("Camera characteristic is not writable."); return; }
                tx.setWriteType((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                BluetoothGattDescriptor cccd = rx.getDescriptor(uuid(0x2902));
                if (cccd == null || !g.setCharacteristicNotification(rx, true)) { fail("Cannot enable camera notifications."); return; }
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!g.writeDescriptor(cccd)) fail("Cannot subscribe to camera notifications.");
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            handler.post(() -> {
                if (g != gatt) return;
                if (status != 0) { fail("Notification setup failed (" + status + ")."); return; }
                SharedPreferences prefs = preferences;
                String saved = prefs.getString("identity", null);
                byte[] identity = new byte[6];
                if (saved == null) {
                    new SecureRandom().nextBytes(identity); identity[0] = (byte) ((identity[0] & 0xfc) | 2);
                    StringBuilder hex = new StringBuilder();
                    for (byte b : identity) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
                    prefs.edit().putString("identity", hex.toString()).apply();
                } else for (int i = 0; i < 6; i++) identity[i] = (byte) Integer.parseInt(saved.substring(i * 2, i * 2 + 2), 16);
                int code = new SecureRandom().nextInt(10000);
                report(String.format(Locale.ROOT, reconnecting ? "Reconnecting… If the camera asks, confirm code %04d." : "Confirm code %04d on the camera.", code));
                send(2, ++sequence, 0, 0x19, Protocol.pairing(identity, code, reconnecting));
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            handler.post(() -> {
                if (g != gatt) return;
                cancel(writeTimeout);
                if (status != 0) { fail("Bluetooth write failed (" + status + "). Check camera state before retrying."); return; }
                writes.poll(); writing = false; pump();
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] value = c.getValue();
            if (value == null) return;
            byte[] copy = value.clone();
            handler.post(() -> {
                if (g != gatt) return;
                for (Protocol.Frame frame : decoder.accept(copy)) {
                    if (g != gatt) break;
                    receive(frame);
                }
            });
        }
    };
    private void send(int type, int seq, int set, int id, byte[] payload) {
        byte[] frame = Protocol.encode(type, seq, set, id, payload);
        // Default ATT MTU of 23 permits 20 data bytes; serialize every fragment.
        for (int p = 0; p < frame.length; p += 20) writes.add(Arrays.copyOfRange(frame, p, Math.min(p + 20, frame.length)));
        pump();
    }
    private void pump() {
        if (writing || writes.isEmpty() || gatt == null || tx == null) return;
        writing = true; tx.setValue(writes.peek());
        if (!gatt.writeCharacteristic(tx)) { fail("Camera write could not start."); return; }
        writeTimeout = () -> fail("Bluetooth write timed out. Check camera state before retrying.");
        handler.postDelayed(writeTimeout, 5000);
    }
    private void receive(Protocol.Frame f) {
        byte[] p = f.payload;
        if (f.set == 0 && f.id == 0x19 && !ready) {
            if (f.response()) {
                if (p.length < 5 || p[4] != 0) fail("Camera rejected the pairing request.");
            } else if (p.length >= 33 && p[26] == 2) {
                if (Protocol.u16(p, 27) != 0) { fail("Pairing declined on camera."); return; }
                // Restrict this first release to Action 4. DJI documents device ID 0xFF33.
                if (Protocol.u16(p, 0) != 0xff33) { fail("This prototype supports the Osmo Action 4 only."); return; }
                byte[] ack = new byte[9]; Protocol.put(ack, 0, 0x12345678, 4);
                send(0x20, f.seq, 0, 0x19, ack);
                ready = true; cancel(connectTimeout);
                send(0, ++sequence, 0x1d, 5, new byte[]{3, 20, 0, 0, 0, 0});
                report("Paired. Waiting for camera status…");
            }
            return;
        }
        if (!ready) return;
        if (f.set == 0x1d && f.id == 2 && !f.response() && p.length >= 38) {
            // Remember only after approval and a valid live status, never on selection or a failed handshake.
            String address = gatt.getDevice().getAddress();
            if (!address.equals(preferences.getString("paired_address", null)))
                preferences.edit().putString("paired_address", address).apply();
            mode = p[0] & 255; state = p[1] & 255; seconds = Protocol.u16(p, 5);
            battery = (p[37] & 255) <= 100 ? p[37] & 255 : -1;
            statusAt = android.os.SystemClock.elapsedRealtime();
            if (targetMode >= 0 && mode == targetMode) {
                targetMode = -1; pendingSeq = -1; busy = false; cancel(commandTimeout);
                report(mode == 5 ? "Photo mode ready. Tap Take photo." : "Video mode ready.");
            }
            listener.changed();
        }
        if (f.response() && f.seq == pendingSeq && f.set == pendingSet && f.id == pendingId) {
            if (targetMode >= 0 && p.length > 0 && p[0] == 0) {
                report("Mode change accepted. Waiting for camera status…");
                return;
            }
            targetMode = -1;
            cancel(commandTimeout); pendingSeq = -1; busy = false;
            // Allow a status push to arrive before enabling another toggle; do not assume an ACK means recording.
            if (p.length > 0 && p[0] == 0) {
                busy = true;
                commandTimeout = () -> { busy = false; listener.changed(); };
                handler.postDelayed(commandTimeout, 1000);
                report("Camera accepted command. Check live status.");
            } else report("Camera rejected command.");
        }
    }
    void shutter() {
        if (!fresh() || busy || !(mode == 5 && state == 1 || mode == 1 && (state == 1 || state == 3))) return;
        command(0, 0x11, new byte[]{1, 1, 0, 0});
    }
    void switchMode(int requestedMode) {
        if (!fresh() || busy || state != 1 || mode == requestedMode || !(requestedMode == 1 || requestedMode == 5)) return;
        targetMode = requestedMode;
        command(0x1d, 4, Protocol.modeSwitch(requestedMode));
    }
    private void command(int set, int id, byte[] payload) {
        busy = true; pendingSeq = ++sequence;
        pendingSet = set; pendingId = id;
        send(2, pendingSeq, set, id, payload);
        if (!ready) return;
        report("Command sent; waiting for camera…");
        commandTimeout = () -> {
            boolean switchingMode = targetMode >= 0;
            busy = false; pendingSeq = -1; targetMode = -1;
            report(switchingMode ? "Mode change not confirmed. Check camera mode before retrying." : "No command acknowledgement. Check camera state before retrying.");
        };
        handler.postDelayed(commandTimeout, 5000);
    }
}
