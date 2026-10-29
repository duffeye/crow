package dev.crow.karoo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.*;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;
import java.util.HashSet;
import java.util.Locale;

public final class MainActivity extends Activity implements CameraLink.Listener {
    private CameraLink link;
    private CameraSession session;
    private TextView message, status;
    private TextView gpsStatus;
    private Switch gpsSwitch;
    private boolean updatingGpsSwitch;
    private Button shutter, photoMode, videoMode;
    private LinearLayout devices;
    private final HashSet<String> seen = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() { changed(); handler.postDelayed(this, 1000); }
    };
    private int dp(int n) { return (int) (getResources().getDisplayMetrics().density * n); }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        session = CameraSession.get(this); link = session.link;
        ScrollView scroll = new ScrollView(this); scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12)); scroll.addView(root); setContentView(scroll);
        TextView title = text(root, "CROW  /  ACTION 4", 22); title.setTextColor(Color.WHITE);
        status = text(root, "Camera disconnected", 26);
        message = text(root, "", 16);
        button(root, "Find camera", () -> { if (permissions()) { seen.clear(); devices.removeAllViews(); link.scan(); } });
        devices = new LinearLayout(this); devices.setOrientation(LinearLayout.VERTICAL); root.addView(devices);
        button(root, "Reconnect", () -> { if (permissions()) { devices.removeAllViews(); link.reconnectSaved(); } });
        shutter = button(root, "Shutter", () -> link.shutter());
        photoMode = button(root, "Photo mode", () -> link.switchMode(5));
        videoMode = button(root, "Video mode", () -> link.switchMode(1));
        button(root, "Disconnect", () -> { link.close(); link.report("Disconnected. Recording may continue on the camera."); });
        button(root, "Forget camera", () -> { devices.removeAllViews(); link.forgetCamera(); });
        gpsSwitch = new Switch(this); gpsSwitch.setText("Forward GPS to camera"); gpsSwitch.setMinHeight(dp(56)); root.addView(gpsSwitch);
        gpsSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingGpsSwitch) return;
            if (checked) enableGps(); else stopService(new Intent(this, GpsForwardingService.class));
        });
        gpsStatus = text(root, session.gpsStatus, 16);
        text(root, "GPS forwarding uses this device’s GPS and sends telemetry for DJI Mimo testing. It stays active with a notification until you turn it off or tap Stop in the notification.", 14);
        text(root, "Add Crow Camera to a Karoo ride screen for in-ride control. The enabled extension keeps the connection when you leave this app. Disconnecting never stops a camera recording.", 14);
        changed();
    }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setPadding(0, dp(8), 0, dp(8)); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setTextSize(19); b.setAllCaps(false);
        b.setMinHeight(dp(64)); b.setGravity(Gravity.CENTER);
        parent.addView(b, new LinearLayout.LayoutParams(-1, -2)); b.setOnClickListener(v -> action.run()); return b;
    }
    private String[] neededPermissions() {
        return Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT} : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    private void enableGps() {
        if (!permissions()) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            String[] required = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION};
            requestPermissions(required, 2); return;
        }
        try { startForegroundService(new Intent(this, GpsForwardingService.class)); }
        catch (RuntimeException e) { session.gpsStatus = "GPS could not start: " + e.getClass().getSimpleName(); changed(); }
    }
    private boolean permissions() {
        String[] needed = neededPermissions();
        for (String p : needed) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { requestPermissions(needed, 1); return false; }
        return true;
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 2) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) enableGps();
            else { session.gpsStatus = "Precise location permission is needed for GPS forwarding"; changed(); }
            return;
        }
        boolean allowed = results.length > 0;
        for (int r : results) allowed &= r == PackageManager.PERMISSION_GRANTED;
        if (allowed) { seen.clear(); devices.removeAllViews(); link.scan(); }
        else link.report("Bluetooth permissions are needed to find and connect to the camera.");
    }
    @Override public void found(BluetoothDevice device, String name) {
        if (seen.add(device.getAddress())) button(devices, name + "\n" + device.getAddress(), () -> { devices.removeAllViews(); link.connect(device); });
    }
    @Override public void changed() {
        if (message == null) return;
        if (gpsStatus != null) gpsStatus.setText(session.gpsStatus);
        if (gpsSwitch != null) {
            updatingGpsSwitch = true; gpsSwitch.setChecked(session.gpsRunning); updatingGpsSwitch = false;
        }
        message.setText(link.message);
        boolean fresh = link.fresh();
        String state = !link.ready ? "Camera disconnected" : !fresh ? "Waiting for live status" : link.state == 3 ? "CAPTURING" : "Camera ready";
        if (fresh) state += String.format(Locale.ROOT, "\n%s • Battery %s\n%02d:%02d", modeName(link.mode), link.battery < 0 ? "—" : link.battery + "%", link.seconds / 60, link.seconds % 60);
        status.setText(state); status.setTextColor(fresh && link.state == 3 ? Color.rgb(255, 100, 100) : Color.WHITE);
        shutter.setText(CameraControls.shutterLabel(link.mode, link.state));
        shutter.setEnabled(CameraControls.canShutter(fresh, link.busy, link.mode, link.state));
        photoMode.setEnabled(CameraControls.canSwitchMode(fresh, link.busy, link.state) && link.mode != 5);
        videoMode.setEnabled(CameraControls.canSwitchMode(fresh, link.busy, link.state) && link.mode != 1);
    }
    private String modeName(int mode) {
        switch (mode) { case 1: return "Video"; case 5: return "Photo"; case 0: return "Slow motion"; case 2: return "Timelapse"; case 10: return "Hyperlapse"; default: return "Mode " + mode; }
    }
    @Override protected void onResume() {
        super.onResume(); session.acquire(this); handler.post(refresh);
        for (String permission : neededPermissions()) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return;
        link.reconnectSaved();
    }
    @Override protected void onPause() {
        handler.removeCallbacks(refresh); session.release(this); super.onPause();
    }
}
