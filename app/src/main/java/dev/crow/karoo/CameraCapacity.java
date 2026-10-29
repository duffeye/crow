package dev.crow.karoo;

import java.util.Locale;

/** Camera-reported estimates from DJI status push 1D02; never infer storage from battery or elapsed time. */
final class CameraCapacity {
    final int battery;
    final long videoSeconds, photos;
    private CameraCapacity(int battery, long videoSeconds, long photos) {
        this.battery = battery; this.videoSeconds = videoSeconds; this.photos = photos;
    }
    static CameraCapacity fromStatus(byte[] payload) {
        if (payload.length < 38) throw new IllegalArgumentException("Incomplete camera status");
        int battery = payload[37] & 255;
        return new CameraCapacity(battery <= 100 ? battery : -1, remaining(payload, 23), remaining(payload, 19));
    }
    private static long remaining(byte[] payload, int offset) {
        long value = 0;
        for (int i = 0; i < 4; i++) value |= (long) (payload[offset + i] & 255) << (8 * i);
        return value == 0xffffffffL ? -1 : value;
    }
    static String videoTime(long seconds) {
        if (seconds < 0) return "—";
        // Whole minutes, rounded down so the displayed estimate never overstates camera capacity.
        return String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, (seconds / 60) % 60);
    }
    static String photoCount(long photos) { return photos < 0 ? "—" : String.format(Locale.ROOT, "%,d", photos); }
}
