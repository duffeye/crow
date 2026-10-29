package dev.crow.karoo;

import android.Manifest;
import android.content.Intent;
import android.app.Service;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
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
}
