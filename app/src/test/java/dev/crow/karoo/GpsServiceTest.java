package dev.crow.karoo;

import android.Manifest;
import android.content.Intent;
import android.app.Service;
import android.location.LocationManager;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class GpsServiceTest {
    @Test public void noPreciseLocationPermissionDoesNotStartForwarding() {
        var app = RuntimeEnvironment.getApplication();
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        var controller = Robolectric.buildService(GpsForwardingService.class).create();
        var service = controller.get();
        var session = CameraSession.get(app);
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(new Intent(app, GpsForwardingService.class), 0, 1));
        assertFalse(session.gpsRunning);
        assertFalse(session.inUse());
        assertTrue(shadowOf(service).isStoppedBySelf());
        controller.destroy();
    }
    @Test public void gpsDemandPausesForUnavailableCameraAndResumesAtOneHz() {
        var app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT);
        var locations = app.getSystemService(LocationManager.class);
        var gps = shadowOf(locations);
        gps.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        var session = CameraSession.get(app);
        var controller = Robolectric.buildService(GpsForwardingService.class).create();
        var service = controller.get();
        try {
            service.onStartCommand(new Intent(app, GpsForwardingService.class), 0, 1);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(session.gpsRunning);
            assertTrue(gps.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty());
            assertTrue(session.gpsStatus.contains("waiting for camera"));

            session.link.ready = true; session.link.powerMode = 0;
            session.link.statusAt = SystemClock.elapsedRealtime();
            service.changed();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, gps.getLocationRequests(LocationManager.GPS_PROVIDER).size());
            assertEquals(1000, gps.getLocationRequests(LocationManager.GPS_PROVIDER).get(0).getIntervalMillis());
            service.changed(); // Repeated status packets must not add location subscriptions.
            assertEquals(1, gps.getLocationRequests(LocationManager.GPS_PROVIDER).size());

            session.link.powerMode = 3; service.changed();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(gps.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty());
            assertTrue(session.gpsStatus.contains("asleep"));

            session.link.powerMode = 0; service.changed();
            assertEquals(1, gps.getLocationRequests(LocationManager.GPS_PROVIDER).size());
            // Freshness watchdog releases GPS even without a disconnect callback.
            shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS);
            assertTrue(gps.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty());
            assertTrue(session.gpsRunning); // Enabled, paused; no unsolicited restart/pairing.

            session.link.statusAt = SystemClock.elapsedRealtime(); service.changed();
            assertEquals(1, gps.getLocationRequests(LocationManager.GPS_PROVIDER).size());
            session.link.close(); service.changed();
            assertTrue(gps.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty());
        } finally { controller.destroy(); }
        assertFalse(session.gpsRunning);
        assertTrue(gps.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty());
        assertFalse(session.inUse());
    }
}
