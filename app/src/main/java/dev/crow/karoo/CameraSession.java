package dev.crow.karoo;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** One application-context BLE link shared by the foreground app and Karoo's bound extension. Main thread only. */
final class CameraSession implements CameraLink.Listener {
    private static CameraSession instance;
    static CameraSession get(Context context) {
        if (instance == null) instance = new CameraSession(context.getApplicationContext());
        return instance;
    }
    final CameraLink link;
    private final Context context;
    private final Set<CameraLink.Listener> owners = Collections.newSetFromMap(new IdentityHashMap<>());
    private CameraSession(Context context) { this.context = context; link = new CameraLink(context, this); }
    void acquire(CameraLink.Listener owner) { owners.add(owner); }
    void release(CameraLink.Listener owner) {
        owners.remove(owner);
        if (owners.isEmpty()) { link.close(); link.report("Disconnected. Recording may continue on the camera."); }
    }
    boolean inUse() { return !owners.isEmpty(); }
    boolean permissionsGranted() {
        String[] required = Build.VERSION.SDK_INT >= 31
            ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String p : required) if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    void reconnectIfAllowed() {
        if (!inUse()) return;
        if (permissionsGranted()) link.reconnectSaved();
        else link.report("Open Crow Camera and grant Bluetooth permissions.");
    }
    @Override public void changed() {
        for (CameraLink.Listener owner : new ArrayList<>(owners)) owner.changed();
    }
    @Override public void found(BluetoothDevice device, String name) {
        for (CameraLink.Listener owner : new ArrayList<>(owners)) owner.found(device, name);
    }
}
