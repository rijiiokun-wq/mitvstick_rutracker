package com.local.rutrackerplayer;

import java.util.Arrays;

/** AVI stream timing: header video cadence and indexed audio byte positions. */
final class AviAudioClock {
    // At most 4 MiB of retained index arrays across all audio tracks.
    private static final int MAX_ENTRIES = 262_144;
    private final java.util.Map<Integer, Stream> streams = new java.util.HashMap<>();
    private long moviStart = -1;
    private long indexBodyPosition = -1;
    private int indexBodySize;

    static final class Stream {
        long bytesPerSecond;
        long startUs;
        float frameRate = -1f;
        long[] positions = new long[0];
        long[] byteOffsets = new long[0];

        long timeUs(long payloadPosition) {
            int index = Arrays.binarySearch(positions, payloadPosition);
            if (index < 0 || bytesPerSecond <= 0) return Long.MIN_VALUE;
            return startUs + byteOffsets[index] * 1_000_000L / bytesPerSecond;
        }
    }

    Stream stream(int id) { return streams.computeIfAbsent(id, ignored -> new Stream()); }

    void observe(long position, byte[] data, int offset, int length) {
        if (length >= 12 && fourcc(data, offset, "LIST")
                && fourcc(data, offset + 8, "movi")) moviStart = position;
        if (length == 8 && fourcc(data, offset, "idx1")) {
            indexBodyPosition = position + 8;
            long size = uint(data, offset + 4);
            indexBodySize = size <= Integer.MAX_VALUE ? (int) size : 0;
        } else if (position == indexBodyPosition && length == indexBodySize) {
            readIndex(data, offset, length);
        }
        // Media3 reads the hdrl body in one operation, starting with avih.
        if (length >= 8 && fourcc(data, offset, "avih")) readHeaders(data, offset, length);
    }

    private void readHeaders(byte[] data, int offset, int length) {
        int end = offset + length;
        int id = 0;
        for (int p = offset; p + 8 <= end;) {
            long size = uint(data, p + 4);
            long next = p + 8L + size + (size & 1);
            if (next > end || size > Integer.MAX_VALUE) break;
            if (size >= 4 && fourcc(data, p, "LIST") && fourcc(data, p + 8, "strl")) {
                readStreamHeader(stream(id++), data, p + 12, (int) size - 4);
            }
            p = (int) next;
        }
    }

    private static void readStreamHeader(Stream stream, byte[] data, int offset, int length) {
        int end = offset + length;
        boolean audio = false;
        for (int p = offset; p + 8 <= end;) {
            long size = uint(data, p + 4);
            long next = p + 8L + size + (size & 1);
            if (next > end || size > Integer.MAX_VALUE) break;
            int body = p + 8;
            if (size >= 48 && fourcc(data, p, "strh")) {
                audio = fourcc(data, body, "auds");
                long scale = uint(data, body + 20);
                long rate = uint(data, body + 24);
                long start = uint(data, body + 28);
                if (fourcc(data, body, "vids") && scale > 0 && rate > 0) {
                    double framesPerSecond = rate / (double) scale;
                    if (framesPerSecond <= 1000) stream.frameRate = (float) framesPerSecond;
                }
                if (audio && rate > 0) {
                    stream.startUs = (long) (start * (double) scale * 1_000_000 / rate);
                }
            } else if (audio && size >= 12 && fourcc(data, p, "strf")) {
                stream.bytesPerSecond = uint(data, body + 8);
            }
            p = (int) next;
        }
    }

    private void readIndex(byte[] data, int offset, int length) {
        if (moviStart < 0 || length < 16) return;
        long firstOffset = uint(data, offset + 8);
        long base = firstOffset > moviStart ? 0 : moviStart + 8;
        int[] counts = new int[100];
        int total = 0;
        int end = offset + length;
        for (int p = offset; p + 16 <= end; p += 16) {
            int id = audioId(data, p);
            if (id >= 0) { counts[id]++; total++; }
        }
        if (total > MAX_ENTRIES) return;
        for (int id = 0; id < counts.length; id++) {
            if (counts[id] == 0) continue;
            Stream stream = stream(id);
            stream.positions = new long[counts[id]];
            stream.byteOffsets = new long[counts[id]];
        }
        long[] bytes = new long[100];
        int[] indices = new int[100];
        for (int p = offset; p + 16 <= end; p += 16) {
            int id = audioId(data, p);
            if (id < 0) continue;
            Stream stream = stream(id);
            int index = indices[id]++;
            long position = base + uint(data, p + 8) + 8;
            if (index > 0 && position <= stream.positions[index - 1]) {
                // An invalid/unsorted index must not produce guessed timestamps.
                stream.bytesPerSecond = 0;
            }
            stream.positions[index] = position;
            stream.byteOffsets[index] = bytes[id];
            bytes[id] += uint(data, p + 12);
        }
    }

    private static int audioId(byte[] data, int offset) {
        int tens = data[offset] - '0', ones = data[offset + 1] - '0';
        return tens >= 0 && tens <= 9 && ones >= 0 && ones <= 9
                && data[offset + 2] == 'w' && data[offset + 3] == 'b'
                ? tens * 10 + ones : -1;
    }

    private static boolean fourcc(byte[] data, int offset, String value) {
        for (int i = 0; i < 4; i++) if (data[offset + i] != value.charAt(i)) return false;
        return true;
    }

    private static long uint(byte[] data, int offset) {
        return (data[offset] & 255L) | (data[offset + 1] & 255L) << 8
                | (data[offset + 2] & 255L) << 16 | (data[offset + 3] & 255L) << 24;
    }
}
