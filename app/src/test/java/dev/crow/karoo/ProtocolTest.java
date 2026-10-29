package dev.crow.karoo;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ProtocolTest {
    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
    @Test public void matchesDjiPublishedModeSwitchVector() {
        byte[] expected = hex("AA1B000100000000050057EE1D04000033FF0A01473936F4FAE1D0");
        assertArrayEquals(expected, Protocol.encode(1, 5, 0x1d, 4, hex("000033FF0A01473936")));
        Protocol.Frame frame = new Protocol.Decoder().accept(expected).get(0);
        assertEquals(5, frame.seq); assertEquals(0x1d, frame.set); assertFalse(frame.response());
    }
    @Test public void matchesDjiPublishedPairingVector() {
        byte[] expected = hex("AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000FE1F00000000796C667C");
        byte[] payload = Protocol.pairing(hex("383456789ABC"), 8190);
        payload[26] = 0; // Published vector uses reconnect verification mode.
        assertArrayEquals(expected, Protocol.encode(2, 1, 0, 0x19, payload));
    }
    @Test public void acceptsEveryPossibleNotificationSplit() {
        byte[] frame = Protocol.encode(2, 77, 0, 0x11, new byte[]{1, 1, 0, 0});
        for (int split = 1; split < frame.length; split++) {
            Protocol.Decoder decoder = new Protocol.Decoder();
            assertTrue(decoder.accept(Arrays.copyOfRange(frame, 0, split)).isEmpty());
            assertEquals(77, decoder.accept(Arrays.copyOfRange(frame, split, frame.length)).get(0).seq);
        }
    }
    @Test public void dropsCorruptionAndResynchronizes() {
        byte[] valid = Protocol.encode(0x20, 65535, 0, 0x11, new byte[]{0});
        for (int corrupt = 0; corrupt < valid.length; corrupt++) {
            byte[] bad = valid.clone(); bad[corrupt] ^= 1;
            // Pad past any falsely advertised incomplete frame before appending valid data.
            byte[] stream = new byte[bad.length + 1024 + valid.length];
            System.arraycopy(bad, 0, stream, 0, bad.length);
            System.arraycopy(valid, 0, stream, bad.length + 1024, valid.length);
            List<Protocol.Frame> frames = new Protocol.Decoder().accept(stream);
            assertEquals(1, frames.size()); assertTrue(frames.get(0).response());
        }
    }
    @Test public void acceptsCoalescedFramesAndBoundsOversizedInput() {
        byte[] a = Protocol.encode(0, 1, 1, 2, new byte[0]);
        byte[] both = Arrays.copyOf(a, a.length * 2);
        System.arraycopy(a, 0, both, a.length, a.length);
        Protocol.Decoder decoder = new Protocol.Decoder();
        assertEquals(2, decoder.accept(both).size());
        assertTrue(decoder.accept(new byte[5000]).isEmpty());
        assertEquals(1, decoder.accept(a).size());
    }
}
