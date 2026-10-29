package dev.crow.karoo;

/** The same live-status rules apply to the app, ride field, and controller buttons. */
final class CameraControls {
    private CameraControls() {}
    static boolean canShutter(boolean fresh, boolean busy, int mode, int state) {
        return fresh && !busy && (mode == 5 && state == 1 || mode == 1 && (state == 1 || state == 3));
    }
    static boolean canSwitchMode(boolean fresh, boolean busy, int state) { return fresh && !busy && state == 1; }
    static String shutterLabel(int mode, int state) {
        return mode == 5 ? "Take photo" : state == 3 ? "Stop recording" : "Start recording";
    }
}
