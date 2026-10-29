package dev.crow.karoo;

import org.junit.Test;
import static org.junit.Assert.*;

public class CameraControlsTest {
    @Test public void recordingCanBeStoppedButCannotChangeMode() {
        assertTrue(CameraControls.canShutter(true, false, 1, 3));
        assertEquals("Stop recording", CameraControls.shutterLabel(1, 3));
        assertFalse(CameraControls.canSwitchMode(true, false, 3));
    }
    @Test public void photoShutterOnlyRunsWhenIdle() {
        assertTrue(CameraControls.canShutter(true, false, 5, 1));
        assertEquals("Take photo", CameraControls.shutterLabel(5, 1));
        assertFalse(CameraControls.canShutter(true, false, 5, 3));
        assertFalse(CameraControls.canShutter(true, false, 5, 0));
    }
    @Test public void staleBusyAndUnsupportedStatesNeverTriggerShutter() {
        for (int mode : new int[]{1, 5}) for (int state : new int[]{1, 3}) {
            assertFalse(CameraControls.canShutter(false, false, mode, state));
            assertFalse(CameraControls.canShutter(true, true, mode, state));
        }
        for (int mode : new int[]{-1, 0, 2, 10}) assertFalse(CameraControls.canShutter(true, false, mode, 1));
        assertFalse(CameraControls.canSwitchMode(false, false, 1));
        assertFalse(CameraControls.canSwitchMode(true, true, 1));
        assertTrue(CameraControls.canSwitchMode(true, false, 1));
    }
}
