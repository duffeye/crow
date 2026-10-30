package dev.crow.karoo;

import android.location.Location;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class GpsTelemetryTest {
    private static final long UTC = Instant.parse("2026-12-31T16:00:00Z").toEpochMilli();
    private Location fix(long ns, long ms, double altitude) {
        Location l = new Location("gps");
        l.setLatitude(53.5); l.setLongitude(-6.25); l.setAltitude(altitude);
        l.setSpeed(10); l.setBearing(90); l.setAccuracy(3);
        l.setVerticalAccuracyMeters(5); l.setSpeedAccuracyMetersPerSecond(.25f);
        l.setElapsedRealtimeNanos(ns); l.setTime(ms); return l;
    }
    @Test public void encodesDjiUnitsDirectionsAndUtcEightDateRollover() {
        GpsTelemetry gps = new GpsTelemetry();
        assertNull(gps.offer(fix(10_000_000_000L, UTC - 1000, 100), 9, 11_000_000_000L, 11_000_000_000L, UTC));
        byte[] payload = gps.offer(fix(11_000_000_000L, UTC, 99), 9, 11_000_000_000L, 11_000_000_000L, UTC);
        assertNotNull(payload); assertEquals(48, payload.length);
        ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(20270101, b.getInt(0)); assertEquals(0, b.getInt(4));
        assertEquals(-62500000, b.getInt(8)); assertEquals(535000000, b.getInt(12));
        assertEquals(99000, b.getInt(16));
        assertEquals(0, b.getFloat(20), .001); assertEquals(1000, b.getFloat(24), .001);
        assertEquals(100, b.getFloat(28), .001);
        assertEquals(5000, b.getInt(32)); assertEquals(3000, b.getInt(36));
        assertEquals(25, b.getInt(40)); assertEquals(9, b.getInt(44));
        byte[] wire = Protocol.encode(0, 7, 0, 0x17, payload);
        assertEquals(66, wire.length);
        Protocol.Frame frame = new Protocol.Decoder().accept(wire).get(0);
        assertEquals(0x17, frame.id); assertEquals(0, frame.set); assertEquals(0, frame.type);
        assertArrayEquals(payload, frame.payload);
    }
    @Test public void rejectsOldFixesSatelliteDataAndMissingAccuracyRatherThanFabricatingThem() {
        GpsTelemetry gps = new GpsTelemetry();
        assertNull(gps.offer(fix(1, UTC - 6000, 100), 9, 11_000_000_000L, 11_000_000_000L, UTC));
        assertNull(gps.offer(fix(11_000_000_000L, UTC, 100), 0, 11_000_000_000L, 11_000_000_000L, UTC));
        assertNull(gps.offer(fix(11_000_000_000L, UTC, 100), 9, 1, 11_000_000_000L, UTC));
        Location missing = fix(11_000_000_000L, UTC, 100); missing.removeVerticalAccuracy();
        assertNull(gps.offer(missing, 9, 11_000_000_000L, 11_000_000_000L, UTC));
        Location inaccurate = fix(11_000_000_000L, UTC, 100); inaccurate.setAccuracy(100);
        assertNull(gps.offer(inaccurate, 9, 11_000_000_000L, 11_000_000_000L, UTC));
    }
    @Test public void duplicateAndTooFastUpdatesDoNotResendOldFixes() {
        GpsTelemetry gps = new GpsTelemetry();
        Location first = fix(10_000_000_000L, UTC, 100);
        assertNull(gps.offer(first, 9, 10_000_000_000L, 10_000_000_000L, UTC));
        assertNull(gps.offer(first, 9, 10_000_000_000L, 10_000_000_000L, UTC));
        assertNull(gps.offer(fix(10_050_000_000L, UTC + 50, 100), 9, 10_050_000_000L, 10_050_000_000L, UTC + 50));
        assertNull(gps.offer(fix(10_900_000_000L, UTC + 900, 100), 9, 10_900_000_000L, 10_900_000_000L, UTC + 900));
        assertNotNull(gps.offer(fix(11_000_000_000L, UTC + 1000, 100), 9, 11_000_000_000L, 11_000_000_000L, UTC + 1000));
        assertNull(gps.offer(fix(11_000_000_000L, UTC + 1000, 100), 9, 11_000_000_000L, 11_000_000_000L, UTC + 1000));
    }
    @Test public void resumingAfterPauseRequiresNewBaselineRatherThanReusingOldAltitude() {
        GpsTelemetry gps = new GpsTelemetry();
        gps.offer(fix(10_000_000_000L, UTC, 100), 9, 10_000_000_000L, 10_000_000_000L, UTC);
        gps.reset();
        assertNull(gps.offer(fix(11_000_000_000L, UTC + 1000, 99), 9, 11_000_000_000L, 11_000_000_000L, UTC + 1000));
        byte[] payload = gps.offer(fix(12_000_000_000L, UTC + 2000, 99), 9, 12_000_000_000L, 12_000_000_000L, UTC + 2000);
        assertNotNull(payload);
        assertEquals(0, ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getFloat(28), .001);
    }
    @Test public void aGapRequiresTwoNewFixesAndStationaryZeroCoordinatesAreValid() {
        GpsTelemetry gps = new GpsTelemetry();
        gps.offer(fix(10_000_000_000L, UTC, 100), 9, 10_000_000_000L, 10_000_000_000L, UTC);
        Location next = fix(15_000_000_000L, UTC + 5000, 100);
        next.setLatitude(0); next.setLongitude(0); next.setSpeed(0); next.removeBearing();
        assertNull(gps.offer(next, 9, 15_000_000_000L, 15_000_000_000L, UTC + 5000));
        Location last = new Location(next); last.setElapsedRealtimeNanos(16_000_000_000L); last.setTime(UTC + 6000);
        assertNotNull(gps.offer(last, 9, 16_000_000_000L, 16_000_000_000L, UTC + 6000));
    }
}
