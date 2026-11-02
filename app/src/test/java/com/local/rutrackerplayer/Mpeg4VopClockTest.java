package com.local.rutrackerplayer;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import org.junit.Test;

public class Mpeg4VopClockTest {
    @Test public void bPicturesKeepCodedOrderButReceivePresentationTimes() {
        Mpeg4VopClock clock = configured();
        assertEquals(0, stamp(clock, vop(0, 0, 0), 0));
        assertEquals(125125, stamp(clock, vop(1, 0, 375), 41708));
        assertEquals(41708, stamp(clock, vop(2, 0, 125), 83417));
        assertEquals(83416, stamp(clock, vop(2, 0, 250), 125125));
        assertEquals(250250, stamp(clock, vop(1, 0, 750), 166833));
        assertEquals(166833, stamp(clock, vop(2, 0, 500), 208541));
        assertEquals(208541, stamp(clock, vop(2, 0, 625), 250250));
    }

    @Test public void bPicturesUsePreviousReferenceSecondAtRollover() {
        Mpeg4VopClock clock = configured();
        assertEquals(0, stamp(clock, vop(0, 0, 0), 0));
        assertEquals(1001001, stamp(clock, vop(1, 1, 3), 917584));
        assertEquals(917584, stamp(clock, vop(2, 0, 2750), 959292));
        assertEquals(959292, stamp(clock, vop(2, 0, 2875), 1001001));
        assertEquals(1126126, stamp(clock, concat(vol(), vop(1, 0, 378)), 1042709));
        assertEquals(1042709, stamp(clock, vop(2, 0, 128), 1084417));
        assertEquals(1084417, stamp(clock, vop(2, 0, 253), 1126126));
    }

    @Test public void seekRetainsVolAndRecoversWholeSecondsWithoutDecodeOrderBias() {
        Mpeg4VopClock clock = configured();
        stamp(clock, vop(0, 0, 0), 0);
        stamp(clock, vop(1, 1, 3), 917584);
        clock.reset();
        assertEquals(10043043, stamp(clock, vop(0, 0, 129), 9959959));
        assertEquals(10001334, stamp(clock, vop(2, 0, 4), 10001334));
        assertEquals(10168168, stamp(clock, vop(1, 0, 504), 10084751));
    }

    @Test public void govHeaderSuppliesAbsoluteSecond() {
        Mpeg4VopClock clock = configured();
        byte[] gop = new BitWriter().put(0, 5).put(0, 6).put(1, 1)
                .put(10, 6).put(1, 1).put(0, 1).startCode(0xb3);
        assertEquals(10043043,
                stamp(clock, concat(gop, vop(0, 0, 129)), 9959959));
        assertEquals(10168168, stamp(clock, vop(1, 0, 504), 10084751));
    }

    @Test public void unknownMalformedAndPackedSamplesFallBack() {
        Mpeg4VopClock missing = new Mpeg4VopClock();
        assertEquals(100, stamp(missing, vop(0, 0, 0), 100));
        Mpeg4VopClock clock = configured();
        byte[] packed = concat(vop(1, 0, 375), vop(2, 0, 125));
        assertEquals(41708, clock.timestamp(packed, packed.length, 2, 41708));
        assertEquals(83417, stamp(clock, vop(2, 0, 250), 83417));
        clock.reset();
        byte[] broken = {0, 0, 1, (byte) 0xb6, 0};
        assertEquals(777, stamp(clock, broken, 777));
        assertEquals(999, stamp(clock, vop(0, 0, 0), 999));
    }

    @Test public void headerOnlySampleAndNonzeroInitialPhase() {
        Mpeg4VopClock clock = new Mpeg4VopClock();
        byte[] header = vol();
        assertEquals(0, clock.timestamp(header, header.length, 0, 0));
        assertEquals(0, stamp(clock, vop(0, 0, 100), 0));
        assertEquals(125125, stamp(clock, vop(1, 0, 475), 41708));
        clock.reset();
        assertEquals(10009677, stamp(clock, vop(0, 0, 129), 9926260));
    }

    private static Mpeg4VopClock configured() {
        Mpeg4VopClock clock = new Mpeg4VopClock();
        clock.configure(vol());
        return clock;
    }

    static byte[] vol() {
        // Matches the real XviD sample's resolution=2997, fixed increment=125.
        return new BitWriter().put(0, 1).put(17, 8).put(0, 1).put(1, 4)
                .put(1, 1).put(1, 2).put(0, 1).put(0, 1).put(0, 2)
                .put(1, 1).put(2997, 16).put(1, 1).put(1, 1).put(125, 12)
                .startCode(0x20);
    }

    static byte[] vop(int type, int modulo, int increment) {
        BitWriter bits = new BitWriter().put(type, 2);
        for (int i = 0; i < modulo; i++) bits.put(1, 1);
        return bits.put(0, 1).put(1, 1).put(increment, 12).put(1, 1)
                .put(1, 1).startCode(0xb6);
    }

    private static long stamp(Mpeg4VopClock clock, byte[] packet, long chunkTimeUs) {
        return clock.timestamp(packet, packet.length, 1, chunkTimeUs);
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] result = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static final class BitWriter {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int value, count;
        BitWriter put(int input, int length) {
            for (int i = length - 1; i >= 0; i--) {
                value = (value << 1) | ((input >>> i) & 1);
                if (++count == 8) { out.write(value); value = 0; count = 0; }
            }
            return this;
        }
        byte[] startCode(int code) {
            if (count != 0) out.write(value << (8 - count));
            return concat(new byte[] {0, 0, 1, (byte) code}, out.toByteArray());
        }
    }
}
