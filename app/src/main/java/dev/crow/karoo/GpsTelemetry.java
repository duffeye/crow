package dev.crow.karoo;

import android.location.Location;
import android.location.LocationManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** Converts fresh measured GNSS fixes to DJI 0017. Does not invent missing accuracy or satellite data. */
final class GpsTelemetry {
    private Location previous;
    String status = "Waiting for a GPS fix";
    void reset() { previous = null; status = "Waiting for a GPS fix"; }
    byte[] offer(Location fix, int satellites, long satellitesAtNs, long nowNs, long nowMs) {
        if (!LocationManager.GPS_PROVIDER.equals(fix.getProvider())) return reject("Waiting for GPS provider");
        if (fix.isFromMockProvider()) return reject("Mock locations are not forwarded");
        long age = nowNs - fix.getElapsedRealtimeNanos();
        if (age < 0 || age > 3_000_000_000L || Math.abs(nowMs - fix.getTime()) > 5000)
            return reject("Waiting for a fresh GPS fix");
        if (satellites < 4 || nowNs < satellitesAtNs || nowNs - satellitesAtNs > 5_000_000_000L)
            return reject("Waiting for satellite fix");
        if (!fix.hasAltitude() || !fix.hasSpeed() || !fix.hasAccuracy() || !fix.hasVerticalAccuracy() || !fix.hasSpeedAccuracy() || (fix.getSpeed() > 0 && !fix.hasBearing()))
            return reject("Waiting for altitude, speed and accuracy data");
        double[] values = {fix.getLatitude(), fix.getLongitude(), fix.getAltitude(), fix.getSpeed(), fix.getBearing(), fix.getAccuracy(), fix.getVerticalAccuracyMeters(), fix.getSpeedAccuracyMetersPerSecond()};
        for (double value : values) if (!Double.isFinite(value)) return reject("Invalid GPS measurement");
        if (Math.abs(fix.getLatitude()) > 90 || Math.abs(fix.getLongitude()) > 180 || Math.abs(fix.getAltitude()) > 2_000_000 || fix.getSpeed() < 0 || fix.getSpeed() > 150 || fix.getAccuracy() <= 0 || fix.getAccuracy() > 50 || fix.getVerticalAccuracyMeters() <= 0 || fix.getVerticalAccuracyMeters() > 100 || fix.getSpeedAccuracyMetersPerSecond() < 0 || fix.getSpeedAccuracyMetersPerSecond() > 10)
            return reject("Waiting for accurate GPS");
        if (previous == null) { previous = new Location(fix); status = "Waiting for next GPS fix"; return null; }
        long deltaNs = fix.getElapsedRealtimeNanos() - previous.getElapsedRealtimeNanos();
        // Some providers deliver faster than requested. Limit serialization and BLE traffic to 1 Hz.
        if (deltaNs < 1_000_000_000L) { status = "Waiting for a new GPS fix"; return null; }
        if (deltaNs > 3_000_000_000L) { previous = new Location(fix); status = "Waiting for next GPS fix"; return null; }
        double down = (previous.getAltitude() - fix.getAltitude()) / (deltaNs / 1e9);
        previous = new Location(fix);
        if (Math.abs(down) > 100) return reject("Invalid vertical movement");
        // DJI specifies local UTC+8 time; normalize date rollover instead of producing hours 24–31.
        ZonedDateTime time = Instant.ofEpochMilli(fix.getTime()).atZone(ZoneOffset.ofHours(8));
        ByteBuffer b = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(time.getYear() * 10000 + time.getMonthValue() * 100 + time.getDayOfMonth());
        b.putInt(time.getHour() * 10000 + time.getMinute() * 100 + time.getSecond());
        b.putInt((int) Math.round(fix.getLongitude() * 1e7));
        b.putInt((int) Math.round(fix.getLatitude() * 1e7));
        b.putInt((int) Math.round(fix.getAltitude() * 1000));
        double bearing = Math.toRadians(fix.getBearing());
        b.putFloat((float) (fix.getSpeed() * Math.cos(bearing) * 100));
        b.putFloat((float) (fix.getSpeed() * Math.sin(bearing) * 100));
        b.putFloat((float) (down * 100));
        b.putInt(Math.round(fix.getVerticalAccuracyMeters() * 1000));
        b.putInt(Math.round(fix.getAccuracy() * 1000));
        b.putInt(Math.round(fix.getSpeedAccuracyMetersPerSecond() * 100));
        b.putInt(satellites);
        status = "GPS fix ready";
        return b.array();
    }
    private byte[] reject(String message) { previous = null; status = message; return null; }
}
