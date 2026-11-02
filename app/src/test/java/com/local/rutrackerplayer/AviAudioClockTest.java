package com.local.rutrackerplayer;

import static org.junit.Assert.*;

import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.TrackOutput;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class AviAudioClockTest {
    @Test public void missingVideoFrameRateComesFromRationalAviHeader() {
        AviAudioClock clock = videoClock(125, 2997);
        assertEquals(23.976f, clock.stream(0).frameRate, 0.00001f);
        RecordingOutput target = new RecordingOutput();
        Ac3RechunkingExtractorsFactory.AviVideoTrackOutput output =
                new Ac3RechunkingExtractorsFactory.AviVideoTrackOutput(target, clock.stream(0));
        output.format(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_MP4V).build());
        assertEquals(23.976f, target.format.frameRate, 0.00001f);
        output.format(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_MP4V)
                .setFrameRate(25).build());
        assertEquals(25f, target.format.frameRate, 0f);
    }

    @Test public void invalidVideoHeaderDoesNotInventAFrameRate() {
        assertEquals(-1f, videoClock(0, 2997).stream(0).frameRate, 0f);
        assertEquals(-1f, videoClock(125, 0).stream(0).frameRate, 0f);
        assertEquals(-1f, videoClock(1, Integer.MAX_VALUE).stream(0).frameRate, 0f);
    }

    @Test public void unevenChunksUseCumulativeBytesAndBothIndexOffsetConventions() {
        for (boolean absolute : new boolean[] {false, true}) {
            AviAudioClock.Stream clock = indexedClock(absolute).stream(1);
            assertEquals(0, clock.timeUs(10252));
            // Large first chunk is500ms, although average AVI chunk duration is~42ms.
            assertEquals(500000, clock.timeUs(25472));
            assertEquals(541708, clock.timeUs(26636));
            assertEquals(Long.MIN_VALUE, clock.timeUs(26637));
        }
    }

    @Test public void seekUsesActualAudioBytePositionAndSkipsPartialFrame() throws Exception {
        AviAudioClock.Stream clock = indexedClock(false).stream(1);
        RecordingOutput target = new RecordingOutput();
        Ac3RechunkingExtractorsFactory.Ac3TrackOutput output =
                new Ac3RechunkingExtractorsFactory.Ac3TrackOutput(target, clock);
        output.format(new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3)
                .setSampleRate(48000).setChannelCount(2).build());
        output.reset();
        // Index says500ms. The chunk starts480 bytes into a768-byte frame,
        // so288 discarded bytes move the first complete frame to512ms.
        byte[] data = new byte[288 + 768 * 2];
        putFrame(data, 288);
        putFrame(data, 288 + 768);
        ExtractorInput input = inputAt(data, 25472);
        output.sampleData(input, data.length, false, TrackOutput.SAMPLE_DATA_PART_MAIN);
        // Deliberately wrong average-chunk/seek timestamp must not set the clock.
        output.sampleMetadata(900000, C.BUFFER_FLAG_KEY_FRAME, data.length, 0, null);
        assertEquals(List.of(512000L, 544000L), target.times);
        assertEquals(List.of(768, 768), target.sizes);
    }

    @Test public void arbitraryChunksRetainCompleteFramesAndNoLongTermRoundingDrift() throws Exception {
        AviAudioClock.Stream clock = new AviAudioClock.Stream();
        clock.bytesPerSecond = 24000;
        RecordingOutput target = new RecordingOutput();
        Ac3RechunkingExtractorsFactory.Ac3TrackOutput output =
                new Ac3RechunkingExtractorsFactory.Ac3TrackOutput(target, clock);
        output.format(new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3)
                .setSampleRate(48000).setChannelCount(2).build());
        byte[] stream = new byte[768 * 10000];
        for (int p = 0; p < stream.length; p += 768) putFrame(stream, p);
        int p = 0, chunk = 0;
        while (p < stream.length) {
            int count = Math.min(chunk == 0 ? 12000 : 1001, stream.length - p);
            byte[] part = java.util.Arrays.copyOfRange(stream, p, p + count);
            output.sampleData(inputAt(part, p), count, false, TrackOutput.SAMPLE_DATA_PART_MAIN);
            output.sampleMetadata(chunk * 41708L, C.BUFFER_FLAG_KEY_FRAME, count, 0, null);
            p += count;
            chunk++;
        }
        assertEquals(10000, target.times.size());
        assertEquals(0L, (long) target.times.get(0));
        assertEquals(9999 * 32000L, (long) target.times.get(9999));
        assertEquals(10000 * 768, target.byteCount);
    }

    @Test public void noIndexFallsBackToActualChunkTimestampAfterReset() throws Exception {
        RecordingOutput target = new RecordingOutput();
        Ac3RechunkingExtractorsFactory.Ac3TrackOutput output =
                new Ac3RechunkingExtractorsFactory.Ac3TrackOutput(target, new AviAudioClock.Stream());
        output.format(new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3)
                .setSampleRate(48000).build());
        byte[] frame = new byte[768]; putFrame(frame, 0);
        output.reset();
        output.sampleData(inputAt(frame, 100000), 768, false, TrackOutput.SAMPLE_DATA_PART_MAIN);
        assertTrue(target.times.isEmpty());
        output.sampleMetadata(48000000, C.BUFFER_FLAG_KEY_FRAME, 768, 0, null);
        assertEquals(List.of(48000000L), target.times);
    }

    private static AviAudioClock indexedClock(boolean absolute) {
        AviAudioClock clock = new AviAudioClock();
        clock.stream(1).bytesPerSecond = 24000;
        byte[] movi = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .put("LIST".getBytes(StandardCharsets.US_ASCII)).putInt(100000)
                .put("movi".getBytes(StandardCharsets.US_ASCII)).array();
        clock.observe(10232, movi, 0, movi.length);
        ByteBuffer body = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        long base = absolute ? 0 : 10240;
        long[] payloadPositions = {10252, 25472, 26636};
        int[] sizes = {12000, 1001, 1001};
        for (int i = 0; i < sizes.length; i++) {
            body.put("01wb".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                    .putInt((int) (payloadPositions[i] - 8 - base)).putInt(sizes[i]);
        }
        byte[] header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put("idx1".getBytes(StandardCharsets.US_ASCII)).putInt(48).array();
        clock.observe(200000, header, 0, 8);
        clock.observe(200008, body.array(), 0, 48);
        return clock;
    }

    private static AviAudioClock videoClock(int scale, int rate) {
        ByteBuffer data = ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN);
        data.put("avih".getBytes(StandardCharsets.US_ASCII)).putInt(0);
        data.put("LIST".getBytes(StandardCharsets.US_ASCII)).putInt(60);
        data.put("strl".getBytes(StandardCharsets.US_ASCII));
        data.put("strh".getBytes(StandardCharsets.US_ASCII)).putInt(48);
        int body = data.position();
        data.put("vids".getBytes(StandardCharsets.US_ASCII));
        data.putInt(body + 20, scale);
        data.putInt(body + 24, rate);
        AviAudioClock clock = new AviAudioClock();
        clock.observe(24, data.array(), 0, data.capacity());
        return clock;
    }

    private static void putFrame(byte[] data, int offset) {
        data[offset] = 0x0b; data[offset + 1] = 0x77;
        data[offset + 4] = 0x14; data[offset + 5] = 0x40;
    }

    private static ExtractorInput inputAt(byte[] bytes, long position) {
        int[] cursor = {0};
        return (ExtractorInput) Proxy.newProxyInstance(ExtractorInput.class.getClassLoader(),
                new Class<?>[] {ExtractorInput.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getPosition")) return position + cursor[0];
                    if (method.getName().equals("read")) {
                        int count = Math.min((int) args[2], bytes.length - cursor[0]);
                        if (count == 0) return -1;
                        System.arraycopy(bytes, cursor[0], args[0], (int) args[1], count);
                        cursor[0] += count;
                        return count;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class RecordingOutput implements TrackOutput {
        final List<Long> times = new ArrayList<>();
        final List<Integer> sizes = new ArrayList<>();
        int byteCount;
        Format format;
        @Override public void format(Format format) { this.format = format; }
        @Override public int sampleData(DataReader input, int length, boolean allowEndOfInput,
                int part) throws IOException { throw new AssertionError("Unexpected direct read"); }
        @Override public void sampleData(ParsableByteArray data, int length, int part) {
            byteCount += length; data.skipBytes(length);
        }
        @Override public void sampleMetadata(long timeUs, int flags, int size, int offset,
                CryptoData cryptoData) { times.add(timeUs); sizes.add(size); }
    }
}
