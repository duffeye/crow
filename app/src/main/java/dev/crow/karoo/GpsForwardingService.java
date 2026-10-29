package dev.crow.karoo;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.*;
import android.os.*;

/** User-started foreground GNSS service. Uses Karoo's measured Android GPS, not phone/network locations. */
@android.annotation.SuppressLint("MissingPermission") // Permission is checked before registering and Android revocation stops the service.
public final class GpsForwardingService extends Service implements CameraLink.Listener, LocationListener {
    private static final String CHANNEL = "camera-gps";
    private static final int NOTIFICATION = 17;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final GpsTelemetry telemetry = new GpsTelemetry();
    private CameraSession session;
    private LocationManager locations;
    private int satellites;
    private long satellitesAt, fixAt;
    private boolean registered;
    private String lastNotification;
    private final GnssStatus.Callback satelliteCallback = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            satellites = 0;
            for (int i = 0; i < status.getSatelliteCount(); i++) if (status.usedInFix(i)) satellites++;
            satellitesAt = SystemClock.elapsedRealtimeNanos();
        }
        @Override public void onStopped() { satellites = 0; satellitesAt = 0; }
    };
    @Override public void onCreate() {
        super.onCreate();
        session = CameraSession.get(this);
        locations = getSystemService(LocationManager.class);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "Camera GPS forwarding", NotificationManager.IMPORTANCE_LOW));
    }
    private boolean allowed() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && session.permissionsGranted();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (!allowed()) { session.gpsStatus = "GPS stopped: precise location and Bluetooth permission needed"; stopSelf(); return START_NOT_STICKY; }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification("Waiting for GPS"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTIFICATION, notification("Waiting for GPS"));
            if (!registered) {
                session.gpsRunning = true; session.acquire(this); session.reconnectIfAllowed();
                if (locations == null || !locations.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                    session.gpsStatus = "GPS provider unavailable on this device"; stopSelf(); return START_NOT_STICKY;
                }
                if (!locations.registerGnssStatusCallback(satelliteCallback, main)) {
                    session.gpsStatus = "Cannot receive GPS satellite status"; stopSelf(); return START_NOT_STICKY;
                }
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100, 0, this, Looper.getMainLooper());
                registered = true; main.post(tick);
            }
        } catch (RuntimeException e) { session.gpsStatus = "GPS stopped: " + e.getClass().getSimpleName(); stopSelf(); }
        return START_NOT_STICKY;
    }
    @Override public void onLocationChanged(Location fix) {
        if (!session.gpsRunning) return;
        byte[] payload = telemetry.offer(fix, satellites, satellitesAt, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis());
        fixAt = SystemClock.elapsedRealtime();
        if (payload != null) session.link.sendGps(payload); // Drop rather than queue behind camera commands.
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!allowed()) { session.gpsStatus = "GPS stopped: permission removed"; stopSelf(); return; }
            String text;
            if (!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) text = "Enable GPS on this device";
            else if (!session.link.fresh()) text = "GPS waiting for camera connection";
            else if (session.link.powerMode != 0) text = "GPS paused: camera asleep";
            else if (fixAt == 0 || SystemClock.elapsedRealtime() - fixAt > 3000) text = "Waiting for a fresh GPS fix";
            else if (session.link.lastGpsWriteAt > 0 && SystemClock.elapsedRealtime() - session.link.lastGpsWriteAt < 3000) text = "GPS packets sent to camera";
            else text = telemetry.status;
            session.gpsStatus = text;
            if (!text.equals(lastNotification)) { getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text)); lastNotification = text; }
            main.postDelayed(this, 1000);
        }
    };
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 17, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 18, new Intent(this, GpsForwardingService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_camera_field).setContentTitle("Crow GPS forwarding")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }
    @Override public void onDestroy() {
        main.removeCallbacks(tick);
        if (locations != null) {
            try { locations.removeUpdates(this); locations.unregisterGnssStatusCallback(satelliteCallback); }
            catch (SecurityException ignored) { /* Permission may have been revoked while forwarding. */ }
        }
        session.gpsRunning = false;
        if (!session.gpsStatus.startsWith("GPS stopped:") && !session.gpsStatus.contains("unavailable") && !session.gpsStatus.startsWith("Cannot")) session.gpsStatus = "GPS forwarding off";
        session.release(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onProviderDisabled(String provider) { satellites = 0; satellitesAt = 0; }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void changed() { }
    @Override public void found(BluetoothDevice device, String name) { }
}
