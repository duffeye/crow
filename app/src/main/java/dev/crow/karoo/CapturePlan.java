package dev.crow.karoo;

/** A single user-requested capture, optionally preceded by a confirmed mode switch. Never replay after failure. */
final class CapturePlan {
    enum Action { NONE, SHUTTER, PHOTO_MODE, VIDEO_MODE }
    private int waitingForMode = -1;

    Action request(int requestedMode, boolean fresh, boolean busy, int mode, int state) {
        if (!fresh || busy || waitingForMode >= 0 || !(requestedMode == 1 || requestedMode == 5)) return Action.NONE;
        if (mode == requestedMode && CameraControls.canShutter(fresh, busy, mode, state)) return Action.SHUTTER;
        if (!CameraControls.canSwitchMode(fresh, busy, state)) return Action.NONE;
        waitingForMode = requestedMode;
        return requestedMode == 5 ? Action.PHOTO_MODE : Action.VIDEO_MODE;
    }

    boolean onStatus(int mode, int state) {
        if (waitingForMode < 0) return false;
        // An external capture/playback/sleep transition cancels the request instead of firing later.
        if (state != 1) { cancel(); return false; }
        if (mode != waitingForMode) return false;
        cancel();
        return true;
    }

    void cancel() { waitingForMode = -1; }
}
