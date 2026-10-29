package dev.crow.karoo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** DJI R SDK framing. No Android dependency so the wire format can be tested on the JVM. */
public final class Protocol {
    private Protocol() {}
    public static int u16(byte[] b, int p) { return (b[p] & 255) | ((b[p + 1] & 255) << 8); }
    static void put(byte[] b, int p, long value, int count) {
        for (int i = 0; i < count; i++) b[p + i] = (byte) (value >>> (8 * i));
    }
    private static long crc(byte[] b, int count, long polynomial, long mask) {
        long crc = 0x3aa3;
        for (int i = 0; i < count; i++) {
            crc ^= b[i] & 255;
            for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) != 0 ? polynomial : 0);
        }
        return crc & mask;
    }
    public static byte[] encode(int type, int seq, int set, int id, byte[] payload) {
        int n = 18 + payload.length;
        if (n > 1023) throw new IllegalArgumentException("Frame too large");
        byte[] b = new byte[n];
        b[0] = (byte) 0xaa; put(b, 1, n, 2); b[3] = (byte) type;
        put(b, 8, seq, 2); put(b, 10, crc(b, 10, 0xa001, 0xffff), 2);
        b[12] = (byte) set; b[13] = (byte) id;
        System.arraycopy(payload, 0, b, 14, payload.length);
        put(b, n - 4, crc(b, n - 4, 0xedb88320L, 0xffffffffL), 4);
        return b;
    }
    public static byte[] pairing(byte[] identity, int code, boolean previouslyPaired) {
        if (identity.length != 6) throw new IllegalArgumentException("Identity needs six bytes");
        byte[] b = new byte[33];
        put(b, 0, 0x12345678, 4); b[4] = 6;
        System.arraycopy(identity, 0, b, 5, 6);
        b[26] = (byte) (previouslyPaired ? 0 : 1); put(b, 27, code, 2);
        return b;
    }
    /** Mode-switch payload from DJI's published 1D04 example (including its reserved bytes). */
    public static byte[] modeSwitch(int mode) {
        if (mode < 0 || mode > 255) throw new IllegalArgumentException("Invalid mode");
        return new byte[]{0, 0, 0x33, (byte) 0xff, (byte) mode, 1, 0x47, 0x39, 0x36};
    }
    public static final class Frame {
        public final int type, seq, set, id;
        public final byte[] payload;
        Frame(byte[] b) {
            type = b[3] & 255; seq = u16(b, 8); set = b[12] & 255; id = b[13] & 255;
            payload = Arrays.copyOfRange(b, 14, b.length - 4);
        }
        public boolean response() { return (type & 0x20) != 0; }
    }
    /** Accepts split/coalesced notifications, rejects corrupt frames, and bounds buffering. */
    public static final class Decoder {
        private byte[] pending = new byte[0];
        public List<Frame> accept(byte[] bytes) {
            if (bytes.length > 4096) { pending = new byte[0]; return new ArrayList<>(); }
            byte[] all = Arrays.copyOf(pending, pending.length + bytes.length);
            System.arraycopy(bytes, 0, all, pending.length, bytes.length);
            List<Frame> frames = new ArrayList<>(); int p = 0;
            while (p < all.length) {
                if ((all[p] & 255) != 0xaa) { p++; continue; }
                if (all.length - p < 12) break;
                int rawLength = u16(all, p + 1), n = rawLength & 1023;
                byte[] header = Arrays.copyOfRange(all, p, p + 12);
                if (rawLength != n || n < 18 || crc(header, 10, 0xa001, 0xffff) != u16(header, 10)) { p++; continue; }
                if (all.length - p < n) break;
                byte[] b = Arrays.copyOfRange(all, p, p + n);
                long tail = 0;
                for (int i = 0; i < 4; i++) tail |= (long) (b[n - 4 + i] & 255) << (8 * i);
                if (b[4] != 0 || crc(b, n - 4, 0xedb88320L, 0xffffffffL) != tail) { p++; continue; }
                frames.add(new Frame(b)); p += n;
            }
            pending = Arrays.copyOfRange(all, p, all.length);
            return frames;
        }
    }
}
