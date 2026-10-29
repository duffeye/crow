package dev.crow.karoo;

import org.junit.Test;
import static org.junit.Assert.*;

public class CapturePlanTest {
    @Test public void photoFromVideoWaitsForConfirmedIdlePhotoAndFiresOnlyOnce() {
        CapturePlan plan = new CapturePlan();
        assertEquals(CapturePlan.Action.PHOTO_MODE, plan.request(5, true, false, 1, 1));
        assertFalse(plan.onStatus(1, 1));
        assertTrue(plan.onStatus(5, 1));
        assertFalse(plan.onStatus(5, 1));
    }
    @Test public void recordingFromPhotoWaitsForVideoButExistingVideoCanStopImmediately() {
        CapturePlan plan = new CapturePlan();
        assertEquals(CapturePlan.Action.VIDEO_MODE, plan.request(1, true, false, 5, 1));
        assertFalse(plan.onStatus(5, 1));
        assertTrue(plan.onStatus(1, 1));
        assertEquals(CapturePlan.Action.SHUTTER, plan.request(1, true, false, 1, 3));
    }
    @Test public void photoNeverStopsAnExistingVideoAndStaleBusyCommandsAreIgnored() {
        CapturePlan plan = new CapturePlan();
        assertEquals(CapturePlan.Action.NONE, plan.request(5, true, false, 1, 3));
        assertEquals(CapturePlan.Action.NONE, plan.request(5, false, false, 1, 1));
        assertEquals(CapturePlan.Action.NONE, plan.request(5, true, true, 1, 1));
        assertFalse(plan.onStatus(5, 1));
        assertEquals(CapturePlan.Action.SHUTTER, plan.request(5, true, false, 5, 1));
    }
    @Test public void timeoutDisconnectOrRejectionCancelBeforeLateConfirmation() {
        CapturePlan plan = new CapturePlan();
        assertEquals(CapturePlan.Action.PHOTO_MODE, plan.request(5, true, false, 1, 1));
        plan.cancel();
        assertFalse(plan.onStatus(5, 1));
    }
    @Test public void externalCaptureCancelsPendingIntentRatherThanTakingPhotoLater() {
        CapturePlan plan = new CapturePlan();
        plan.request(5, true, false, 1, 1);
        assertFalse(plan.onStatus(5, 3));
        assertFalse(plan.onStatus(5, 1));
    }
}
