package com.local.rutrackerplayer;

import static org.junit.Assert.*;

import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.TrackOutput;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class AviVideoTrackOutputTest {
    @Test public void splitReadsPreserveEveryByteAndCorrectOnlyMetadata() throws Exception {
        Sink sink = new Sink();
        Ac3RechunkingExtractorsFactory.AviVideoTrackOutput output = output(sink, false);
        byte[][] packets = {Mpeg4VopClockTest.concat(Mpeg4VopClockTest.vol(),
                Mpeg4VopClockTest.vop(0, 0, 0)),
                Mpeg4VopClockTest.vop(1, 0, 375), Mpeg4VopClockTest.vop(2, 0, 125),
                Mpeg4VopClockTest.vop(2, 0, 250)};
        ByteArrayOutputStream original = new ByteArrayOutputStream();
        for (int i = 0; i < packets.length; i++) {
            byte[] packet = packets[i];
            original.write(packet);
            int[] position = {0};
            DataReader input = (data, offset, length) -> {
                int count = Math.min(2, Math.min(length, packet.length - position[0]));
                if (count == 0) return -1;
                System.arraycopy(packet, position[0], data, offset, count);
                position[0] += count;
                return count;
            };
            while (position[0] < packet.length) {
                // This three-argument overload is the actual AviExtractor path.
                if (i < 3) output.sampleData(input, packet.length - position[0], false);
                else output.sampleData(input, packet.length - position[0], false, 0);
            }
            output.sampleMetadata(i * 125_000_000L / 2997, 0, packet.length, 0, null);
        }
        assertArrayEquals(original.toByteArray(), sink.bytes.toByteArray());
        assertEquals(List.of(0L, 125125L, 41708L, 83416L), sink.times);
        output.reset();
        byte[] packet = Mpeg4VopClockTest.vop(0, 0, 129);
        output.sampleData(new ParsableByteArray(packet), packet.length);
        output.sampleMetadata(9959959, 1, packet.length, 0, null);
        assertEquals(10043043L, (long) sink.times.get(4));
    }

    @Test public void detectsPackedSecondVopEvenBeyondCapturedPrefix() {
        Sink sink = new Sink();
        Ac3RechunkingExtractorsFactory.AviVideoTrackOutput output = output(sink);
        byte[] packet = Arrays.copyOf(Mpeg4VopClockTest.vop(1, 0, 375), 80_000);
        byte[] second = Mpeg4VopClockTest.vop(2, 0, 125);
        System.arraycopy(second, 0, packet, 70_000, second.length);
        output.sampleData(new ParsableByteArray(packet), packet.length, 0);
        output.sampleMetadata(41708, 0, packet.length, 0, null);
        assertEquals(List.of(41708L), sink.times);
        assertArrayEquals(packet, sink.bytes.toByteArray());
    }

    @Test public void otherCodecsPassThroughWithoutTimingChanges() {
        Sink sink = new Sink();
        Ac3RechunkingExtractorsFactory.AviVideoTrackOutput output = output(sink);
        output.format(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build());
        byte[] packet = Mpeg4VopClockTest.vop(1, 0, 375);
        output.sampleData(new ParsableByteArray(packet), packet.length, 0);
        output.sampleMetadata(41708, 0, packet.length, 0, null);
        assertEquals(List.of(41708L), sink.times);
        assertArrayEquals(packet, sink.bytes.toByteArray());
    }

    private static Ac3RechunkingExtractorsFactory.AviVideoTrackOutput output(Sink sink) {
        return output(sink, true);
    }

    private static Ac3RechunkingExtractorsFactory.AviVideoTrackOutput output(Sink sink,
            boolean withInitializationData) {
        Ac3RechunkingExtractorsFactory.AviVideoTrackOutput output =
                new Ac3RechunkingExtractorsFactory.AviVideoTrackOutput(sink, new AviAudioClock.Stream());
        output.format(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_MP4V)
                .setInitializationData(withInitializationData
                        ? List.of(Mpeg4VopClockTest.vol()) : List.of()).build());
        return output;
    }

    private static final class Sink implements TrackOutput {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final List<Long> times = new ArrayList<>();
        @Override public void format(Format format) {}
        @Override public int sampleData(DataReader input, int length, boolean allowEndOfInput,
                int part) throws IOException {
            byte[] buffer = new byte[length];
            int count = input.read(buffer, 0, length);
            if (count > 0) bytes.write(buffer, 0, count);
            return count;
        }
        @Override public void sampleData(ParsableByteArray data, int length, int part) {
            bytes.write(data.getData(), data.getPosition(), length);
            data.skipBytes(length);
        }
        @Override public void sampleMetadata(long timeUs, int flags, int size, int offset,
                CryptoData cryptoData) { times.add(timeUs); }
    }
}
