package dev.crow.karoo;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class CameraCapacityTest {
    @Test public void decodesDjiStatusOffsetsAsUnsignedLittleEndian() {
        byte[] payload = new byte[38];
        Arrays.fill(payload, (byte) 0x7f);
        Protocol.put(payload, 15, 123456, 4); // Remaining MB is distinct from photos and seconds.
        Protocol.put(payload, 19, 0x80000001L, 4);
        Protocol.put(payload, 23, 5070, 4);
        payload[37] = 85;
        CameraCapacity capacity = CameraCapacity.fromStatus(payload);
        assertEquals(85, capacity.battery);
        assertEquals(2147483649L, capacity.photos);
        assertEquals(5070, capacity.videoSeconds);
    }
    @Test public void unknownDoesNotBecomeZeroAndZeroMeansNoRemainingCapacity() {
        byte[] payload = new byte[38];
        CameraCapacity empty = CameraCapacity.fromStatus(payload);
        assertEquals(0, empty.battery); assertEquals(0, empty.videoSeconds); assertEquals(0, empty.photos);
        Arrays.fill(payload, (byte) 0xff);
        CameraCapacity unknown = CameraCapacity.fromStatus(payload);
        assertEquals(-1, unknown.battery); assertEquals(-1, unknown.videoSeconds); assertEquals(-1, unknown.photos);
        Protocol.put(payload, 23, 0xfffffffeL, 4);
        assertEquals(4294967294L, CameraCapacity.fromStatus(payload).videoSeconds);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsIncompleteStatus() {
        CameraCapacity.fromStatus(new byte[37]);
    }
    @Test public void formatsRemainingTimeAndCountsWithUnitsUnambiguous() {
        assertEquals("0:00", CameraCapacity.videoTime(0));
        assertEquals("0:59", CameraCapacity.videoTime(59));
        assertEquals("1:00", CameraCapacity.videoTime(60));
        assertEquals("59:59", CameraCapacity.videoTime(3599));
        assertEquals("1:00:00", CameraCapacity.videoTime(3600));
        assertEquals("1:24:30", CameraCapacity.videoTime(5070));
        assertEquals("0", CameraCapacity.photoCount(0));
        assertEquals("1,234", CameraCapacity.photoCount(1234));
        assertEquals("—", CameraCapacity.videoTime(-1));
        assertEquals("—", CameraCapacity.photoCount(-1));
    }
}
