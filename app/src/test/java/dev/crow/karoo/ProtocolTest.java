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
        assertArrayEquals(expected, Protocol.encode(1, 5, 0x1d, 4, Protocol.modeSwitch(CameraModel.ACTION_4, 10)));
        Protocol.Frame frame = new Protocol.Decoder().accept(expected).get(0);
        assertEquals(5, frame.seq); assertEquals(0x1d, frame.set); assertFalse(frame.response());
    }
    @Test public void photoAndVideoUseExplicitModeSwitchPayloads() {
        assertArrayEquals(hex("000033FF0501473936"), Protocol.modeSwitch(CameraModel.ACTION_4, 5));
        assertArrayEquals(hex("000033FF0101473936"), Protocol.modeSwitch(CameraModel.ACTION_4, 1));
    }
    @Test public void modelSpecificPhotoAndVideoCommandsKeepDjiExampleLayout() {
        assertArrayEquals(hex("000044FF0501473936"), Protocol.modeSwitch(CameraModel.ACTION_5_PRO, 5));
        assertArrayEquals(hex("000044FF0101473936"), Protocol.modeSwitch(CameraModel.ACTION_5_PRO, 1));
        assertArrayEquals(hex("000055FF0501473936"), Protocol.modeSwitch(CameraModel.ACTION_6, 5));
        assertArrayEquals(hex("000055FF0101473936"), Protocol.modeSwitch(CameraModel.ACTION_6, 1));
    }
    @Test public void ignoresAction5NonProtocolNotificationsAroundSplitFrames() {
        byte[] packet = Protocol.encode(0, 48, 0x1d, 2, new byte[38]);
        Protocol.Decoder decoder = new Protocol.Decoder();
        assertTrue(decoder.accept(hex("550200001122")).isEmpty());
        assertTrue(decoder.accept(Arrays.copyOfRange(packet, 0, 15)).isEmpty());
        byte[] tail = Arrays.copyOfRange(packet, 15, packet.length + 4);
        tail[tail.length - 4] = 0x55;
        List<Protocol.Frame> frames = decoder.accept(tail);
        assertEquals(1, frames.size());
        assertEquals(48, frames.get(0).seq);
        assertEquals(1, decoder.accept(packet).size());
    }
    @Test public void matchesDjiPublishedPairingVector() {
        byte[] expected = hex("AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000FE1F00000000796C667C");
        byte[] payload = Protocol.pairing(hex("383456789ABC"), 8190, true);
        assertArrayEquals(expected, Protocol.encode(2, 1, 0, 0x19, payload));
    }
    @Test public void firstPairingRequiresApprovalAndReconnectPreservesIdentity() {
        byte[] identity = hex("023456789ABC");
        byte[] first = Protocol.pairing(identity, 1234, false);
        byte[] reconnect = Protocol.pairing(identity, 1234, true);
        assertEquals(1, first[26]);
        assertEquals(0, reconnect[26]);
        assertArrayEquals(identity, Arrays.copyOfRange(reconnect, 5, 11));
        assertEquals(1234, Protocol.u16(reconnect, 27));
        reconnect[26] = 1;
        assertArrayEquals(first, reconnect);
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
