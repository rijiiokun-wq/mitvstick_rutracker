package com.local.rutrackerplayer;

/** Restores presentation timestamps carried inside ordinary MPEG-4 Part 2 AVI packets. */
final class Mpeg4VopClock {
    private int resolution;
    private int incrementBits;
    private long timeBase;
    private long previousTimeBase;
    private long offsetUs;
    private long originUs;
    private boolean anchored;
    private boolean unsupported;

    void configure(byte[] bytes) {
        try {
            for (int p = 0; p + 4 <= bytes.length; p++) {
                if (startCode(bytes, p) && (bytes[p + 3] & 0xf0) == 0x20) {
                    readVol(new Bits(bytes, p + 4, bytes.length));
                }
            }
        } catch (IllegalArgumentException ignored) {
            resolution = 0;
        }
    }

    /** Keep the VOL from initialization data across range seeks. */
    void reset() {
        timeBase = 0;
        previousTimeBase = 0;
        anchored = false;
        unsupported = false;
    }

    long timestamp(byte[] bytes, int length, int vopCount, long chunkTimeUs) {
        if (unsupported) return chunkTimeUs;
        // Packed DivX needs packet splitting as well as timing. Never give two
        // pictures a guessed common PTS or change the codec's packet contents.
        if (vopCount > 1) {
            unsupported = true;
            return chunkTimeUs;
        }
        try {
            for (int p = 0; p + 4 <= length; p++) {
                if (!startCode(bytes, p)) continue;
                int code = bytes[p + 3] & 255;
                Bits bits = new Bits(bytes, p + 4, length);
                if ((code & 0xf0) == 0x20) {
                    readVol(bits);
                } else if (code == 0xb3) {
                    int hours = bits.read(5);
                    int minutes = bits.read(6);
                    bits.marker();
                    int seconds = bits.read(6);
                    bits.read(2); // closed_gov / broken_link
                    if (hours > 23 || minutes > 59 || seconds > 59) fail();
                    timeBase = hours * 3600L + minutes * 60L + seconds;
                    // A GOV after a seek restores absolute bitstream seconds;
                    // replace the temporary whole-second seek offset as well.
                    anchored = false;
                } else if (code == 0xb6) {
                    if (resolution == 0) return chunkTimeUs;
                    int pictureType = bits.read(2);
                    int modulo = 0;
                    while (bits.read(1) != 0) {
                        if (++modulo > 3600) fail();
                    }
                    bits.marker();
                    int increment = bits.read(incrementBits);
                    bits.marker();
                    bits.read(1); // vop_coded: N-VOP still advances the bitstream clock.
                    if (increment >= resolution) fail();
                    long seconds;
                    if (pictureType == 2) {
                        seconds = previousTimeBase + modulo;
                    } else {
                        previousTimeBase = timeBase;
                        timeBase += modulo;
                        seconds = timeBase;
                    }
                    long timeUs = seconds * 1_000_000L
                            + increment * 1_000_000L / resolution;
                    if (!anchored) {
                        if (pictureType == 2) return chunkTimeUs;
                        // AVI's seek timestamp is in decode order. Anchoring to
                        // it exactly would preserve a one/two-frame sync error.
                        // VOP has the exact fractional-second phase; use AVI to
                        // recover only the whole seconds lost on a range seek.
                        if (chunkTimeUs == 0) originUs = -timeUs;
                        offsetUs = originUs + Math.round(
                                (chunkTimeUs - timeUs - originUs) / 1_000_000.0)
                                * 1_000_000L;
                        anchored = true;
                    }
                    long presentationUs = timeUs + offsetUs;
                    // Broken/nonstandard VOP timing must not move playback far
                    // from the container clock. Ordinary B reordering is small.
                    if (Math.abs(presentationUs - chunkTimeUs) > 500_000L) fail();
                    return presentationUs;
                }
            }
            // A VOP outside the bounded captured prefix cannot be parsed safely.
            if (vopCount != 0) unsupported = true;
        } catch (IllegalArgumentException ignored) {
            unsupported = true;
        }
        return chunkTimeUs;
    }

    private void readVol(Bits bits) {
        bits.read(1); // random_accessible_vol
        bits.read(8); // video_object_type_indication
        int version = 1;
        if (bits.read(1) != 0) {
            version = bits.read(4);
            bits.read(3);
        }
        int aspectRatio = bits.read(4);
        if (aspectRatio == 15) bits.read(16);
        if (bits.read(1) != 0) {
            bits.read(2); // chroma_format
            bits.read(1); // low_delay
            if (bits.read(1) != 0) {
                bits.read(15); bits.marker();
                bits.read(15); bits.marker();
                bits.read(15); bits.marker();
                bits.read(3); bits.read(11); bits.marker();
                bits.read(15); bits.marker();
            }
        }
        int shape = bits.read(2);
        if (shape == 3 && version != 1) bits.read(4);
        if (shape != 0) fail(); // Only rectangular video is supported here.
        bits.marker();
        int newResolution = bits.read(16);
        bits.marker();
        if (newResolution == 0) fail();
        int newIncrementBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(newResolution - 1));
        if (bits.read(1) != 0) bits.read(newIncrementBits);
        if (resolution != 0 && resolution != newResolution) reset();
        resolution = newResolution;
        incrementBits = newIncrementBits;
        // Repeated VOL before an I-frame does not reset accumulated seconds.
    }

    private static boolean startCode(byte[] data, int position) {
        return data[position] == 0 && data[position + 1] == 0
                && data[position + 2] == 1;
    }

    private static void fail() { throw new IllegalArgumentException("Invalid MPEG-4 timing"); }

    private static final class Bits {
        private final byte[] data;
        private final int end;
        private int position;
        Bits(byte[] data, int start, int end) {
            this.data = data;
            this.position = start * 8;
            this.end = end * 8;
        }
        int read(int count) {
            if (count < 0 || count > 16 || position > end - count) fail();
            int result = 0;
            for (int i = 0; i < count; i++, position++) {
                result = (result << 1) | ((data[position / 8] >>> (7 - position % 8)) & 1);
            }
            return result;
        }
        void marker() { if (read(1) != 1) fail(); }
    }
}
