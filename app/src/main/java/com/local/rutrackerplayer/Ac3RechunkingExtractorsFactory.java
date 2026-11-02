package com.local.rutrackerplayer;

import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.Ac3Util;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ForwardingExtractor;
import androidx.media3.extractor.ForwardingExtractorOutput;
import androidx.media3.extractor.ForwardingExtractorInput;
import androidx.media3.extractor.ForwardingTrackOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.avi.AviExtractor;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-frames AC3 carried in AVI before it reaches Media3's passthrough sink.
 *
 * <p>Some older AVI muxers split the raw AC3 byte stream into arbitrary RIFF
 * chunks. Those chunk boundaries are not AC3 access-unit boundaries. Treating
 * each RIFF chunk as one encoded sample makes the direct HDMI sink count one
 * 1536-sample AC3 frame per chunk and produces a periodic timestamp jump. This
 * wrapper joins the byte stream again and emits one sample per real syncframe.
 */
@OptIn(markerClass = UnstableApi.class)
final class Ac3RechunkingExtractorsFactory implements ExtractorsFactory {
    private final DefaultExtractorsFactory delegate = new DefaultExtractorsFactory();

    @Override public Extractor[] createExtractors() {
        return wrap(delegate.createExtractors());
    }

    @Override public Extractor[] createExtractors(
            Uri uri, Map<String, List<String>> responseHeaders) {
        return wrap(delegate.createExtractors(uri, responseHeaders));
    }

    private static Extractor[] wrap(Extractor[] extractors) {
        Extractor[] wrapped = Arrays.copyOf(extractors, extractors.length);
        for (int i = 0; i < wrapped.length; i++) {
            if (wrapped[i].getUnderlyingImplementation() instanceof AviExtractor) {
                wrapped[i] = new Ac3RechunkingExtractor(wrapped[i]);
            }
        }
        return wrapped;
    }

    private static final class Ac3RechunkingExtractor extends ForwardingExtractor {
        private final Map<Integer, Ac3TrackOutput> audioTracks = new HashMap<>();
        private final Map<Integer, AviVideoTrackOutput> videoTracks = new HashMap<>();
        private final AviAudioClock audioClock = new AviAudioClock();

        Ac3RechunkingExtractor(Extractor delegate) {
            super(delegate);
        }

        @Override public void init(ExtractorOutput output) {
            super.init(new ForwardingExtractorOutput(output) {
                @Override public TrackOutput track(int id, @C.TrackType int type) {
                    TrackOutput target = super.track(id, type);
                    if (type == C.TRACK_TYPE_VIDEO) {
                        return videoTracks.computeIfAbsent(id,
                                ignored -> new AviVideoTrackOutput(target, audioClock.stream(id)));
                    }
                    if (type != C.TRACK_TYPE_AUDIO) return target;
                    Ac3TrackOutput existing = audioTracks.get(id);
                    if (existing != null) return existing;
                    Ac3TrackOutput created = new Ac3TrackOutput(target, audioClock.stream(id));
                    audioTracks.put(id, created);
                    return created;
                }
            });
        }

        @Override public void seek(long position, long timeUs) {
            for (Ac3TrackOutput output : audioTracks.values()) output.reset();
            for (AviVideoTrackOutput output : videoTracks.values()) output.reset();
            super.seek(position, timeUs);
        }

        @Override public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
            // Observe the header/index bytes that AviExtractor already reads; no
            // extra range requests or duplicate retained copy of idx1 are needed.
            return super.read(new ForwardingExtractorInput(input) {
                @Override public void readFully(byte[] target, int offset, int length) throws IOException {
                    long position = getPosition();
                    super.readFully(target, offset, length);
                    audioClock.observe(position, target, offset, length);
                }
                @Override public boolean readFully(byte[] target, int offset, int length,
                        boolean allowEndOfInput) throws IOException {
                    long position = getPosition();
                    boolean read = super.readFully(target, offset, length, allowEndOfInput);
                    if (read) audioClock.observe(position, target, offset, length);
                    return read;
                }
                @Override public void peekFully(byte[] target, int offset, int length) throws IOException {
                    long position = getPeekPosition();
                    super.peekFully(target, offset, length);
                    audioClock.observe(position, target, offset, length);
                }
            }, seekPosition);
        }
    }

    static final class AviVideoTrackOutput extends ForwardingTrackOutput {
        private static final int PREFIX_LIMIT = 64 * 1024;
        private final AviAudioClock.Stream clock;
        private final Mpeg4VopClock vopClock = new Mpeg4VopClock();
        private final byte[] prefix = new byte[PREFIX_LIMIT];
        private int prefixSize;
        private int startCode = -1;
        private int vopCount;
        private long packetSize;
        private boolean correctMpeg4Timing;

        AviVideoTrackOutput(TrackOutput target, AviAudioClock.Stream clock) {
            super(target);
            this.clock = clock;
        }

        @Override public void format(Format format) {
            // Media3's AVI extractor timestamps frames with rate/scale but omits
            // that rate from Format. Supply it before codec configuration, so
            // scheduling and display matching do not infer cadence from drops.
            if (format.frameRate <= 0 && clock.frameRate > 0) {
                format = format.buildUpon().setFrameRate(clock.frameRate).build();
            }
            correctMpeg4Timing = MimeTypes.VIDEO_MP4V.equals(format.sampleMimeType);
            if (correctMpeg4Timing) {
                for (byte[] initialization : format.initializationData) {
                    vopClock.configure(initialization);
                }
            }
            super.format(format);
        }

        @Override public int sampleData(DataReader input, int length,
                boolean allowEndOfInput, @SampleDataPart int sampleDataPart) throws IOException {
            if (!correctMpeg4Timing || sampleDataPart != SAMPLE_DATA_PART_MAIN) {
                return super.sampleData(input, length, allowEndOfInput, sampleDataPart);
            }
            // Capture only a bounded header prefix. The decoder receives the
            // original bytes immediately, in their original coded packet order.
            return super.sampleData((target, offset, count) -> {
                int read = input.read(target, offset, count);
                if (read > 0) observe(target, offset, read);
                return read;
            }, length, allowEndOfInput, sampleDataPart);
        }

        @Override public int sampleData(DataReader input, int length,
                boolean allowEndOfInput) throws IOException {
            // ForwardingTrackOutput forwards this overload straight to its
            // delegate. AVI uses it, so explicitly route through our observer.
            return sampleData(input, length, allowEndOfInput, SAMPLE_DATA_PART_MAIN);
        }

        @Override public void sampleData(ParsableByteArray data, int length,
                @SampleDataPart int sampleDataPart) {
            if (correctMpeg4Timing && sampleDataPart == SAMPLE_DATA_PART_MAIN) {
                observe(data.getData(), data.getPosition(), length);
            }
            super.sampleData(data, length, sampleDataPart);
        }

        @Override public void sampleData(ParsableByteArray data, int length) {
            sampleData(data, length, SAMPLE_DATA_PART_MAIN);
        }

        @Override public void sampleMetadata(long timeUs, @C.BufferFlags int flags,
                int size, int offset, CryptoData cryptoData) {
            if (correctMpeg4Timing && offset == 0 && size == packetSize && cryptoData == null) {
                timeUs = vopClock.timestamp(prefix, prefixSize, vopCount, timeUs);
            } else if (correctMpeg4Timing) {
                // AVI normally has offset=0 and no encryption. Unexpected
                // framing must not reuse a clock derived from another sample.
                vopClock.reset();
            }
            super.sampleMetadata(timeUs, flags, size, offset, cryptoData);
            clearPacket();
        }

        void reset() {
            clearPacket();
            vopClock.reset();
        }

        private void observe(byte[] data, int offset, int length) {
            int capture = Math.min(length, prefix.length - prefixSize);
            System.arraycopy(data, offset, prefix, prefixSize, capture);
            prefixSize += capture;
            packetSize += length;
            // Scan the entire packet, including bytes beyond the retained
            // prefix, to reject packed multi-VOP samples without allocating.
            for (int p = offset; p < offset + length; p++) {
                startCode = (startCode << 8) | (data[p] & 255);
                if (startCode == 0x000001b6) vopCount++;
            }
        }

        private void clearPacket() {
            prefixSize = 0;
            packetSize = 0;
            startCode = -1;
            vopCount = 0;
        }
    }

    static final class Ac3TrackOutput implements TrackOutput {
        private static final int INITIAL_CAPACITY = 32 * 1024;
        private static final int MAX_SYNCFRAME_SIZE = 1024 * 1024;

        private final TrackOutput delegate;
        private final AviAudioClock.Stream clock;
        private final byte[] syncHeader = new byte[6];
        private byte[] pending = new byte[INITIAL_CAPACITY];
        private int pendingSize;
        private boolean rechunk;
        private int sampleRate = 48_000;
        private long baseTimeUs = C.TIME_UNSET;
        private long emittedSamples;
        private long skippedBeforeFirstFrame;
        private boolean inputPositionCaptured;

        Ac3TrackOutput(TrackOutput delegate, AviAudioClock.Stream clock) {
            this.delegate = delegate;
            this.clock = clock;
        }

        @Override public void durationUs(long durationUs) {
            delegate.durationUs(durationUs);
        }

        @Override public void format(Format format) {
            // The byte-rate index clock is valid for constant-bitrate AC3.
            // E-AC3 dependent substreams require different access-unit grouping.
            rechunk = MimeTypes.AUDIO_AC3.equals(format.sampleMimeType);
            if (format.sampleRate > 0) sampleRate = format.sampleRate;
            delegate.format(format);
        }

        @Override public int sampleData(DataReader input, int length,
                boolean allowEndOfInput, @SampleDataPart int sampleDataPart) throws IOException {
            if (!rechunk || sampleDataPart != SAMPLE_DATA_PART_MAIN) {
                return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart);
            }
            if (!inputPositionCaptured && input instanceof ExtractorInput) {
                long exactTimeUs = clock.timeUs(((ExtractorInput) input).getPosition());
                if (exactTimeUs != Long.MIN_VALUE) baseTimeUs = exactTimeUs;
                inputPositionCaptured = true;
            }
            ensureCapacity(pendingSize + length);
            int read = input.read(pending, pendingSize, length);
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT;
                throw new EOFException();
            }
            pendingSize += read;
            emitCompleteFrames();
            return read;
        }

        @Override public void sampleData(ParsableByteArray data, int length,
                @SampleDataPart int sampleDataPart) {
            if (!rechunk || sampleDataPart != SAMPLE_DATA_PART_MAIN) {
                delegate.sampleData(data, length, sampleDataPart);
                return;
            }
            ensureCapacity(pendingSize + length);
            data.readBytes(pending, pendingSize, length);
            pendingSize += length;
            emitCompleteFrames();
        }

        @Override public void sampleMetadata(long timeUs, @C.BufferFlags int flags,
                int size, int offset, CryptoData cryptoData) {
            if (!rechunk) {
                delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData);
                return;
            }
            if (baseTimeUs == C.TIME_UNSET) baseTimeUs = timeUs;
            emitCompleteFrames();
        }

        void reset() {
            pendingSize = 0;
            emittedSamples = 0;
            skippedBeforeFirstFrame = 0;
            inputPositionCaptured = false;
            // The requested position is not the timestamp at the chosen AVI
            // keyframe. Anchor to actual indexed audio bytes or chunk metadata.
            baseTimeUs = C.TIME_UNSET;
        }

        private void emitCompleteFrames() {
            if (!rechunk || baseTimeUs == C.TIME_UNSET) return;
            int cursor = 0;
            while (pendingSize - cursor >= syncHeader.length) {
                if ((pending[cursor] & 0xFF) != 0x0B
                        || (pending[cursor + 1] & 0xFF) != 0x77) {
                    cursor++;
                    if (emittedSamples == 0) skippedBeforeFirstFrame++;
                    continue;
                }
                System.arraycopy(pending, cursor, syncHeader, 0, syncHeader.length);
                int frameSize = Ac3Util.parseAc3SyncframeSize(syncHeader);
                if (frameSize <= 0 || frameSize > MAX_SYNCFRAME_SIZE) {
                    cursor++;
                    if (emittedSamples == 0) skippedBeforeFirstFrame++;
                    continue;
                }
                if (pendingSize - cursor < frameSize) break;

                ByteBuffer frame = ByteBuffer.wrap(pending, cursor, frameSize);
                int sampleCount = Ac3Util.parseAc3SyncframeAudioSampleCount(frame);
                ParsableByteArray frameData = new ParsableByteArray(pending, pendingSize);
                frameData.setPosition(cursor);
                delegate.sampleData(frameData, frameSize, SAMPLE_DATA_PART_MAIN);
                long syncOffsetUs = clock.bytesPerSecond > 0
                        ? skippedBeforeFirstFrame * C.MICROS_PER_SECOND / clock.bytesPerSecond : 0;
                long timeUs = baseTimeUs + syncOffsetUs + Util.scaleLargeTimestamp(
                        emittedSamples, C.MICROS_PER_SECOND, sampleRate);
                delegate.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME,
                        frameSize, 0, null);
                emittedSamples += sampleCount;
                cursor += frameSize;
            }
            if (cursor > 0) {
                System.arraycopy(pending, cursor, pending, 0, pendingSize - cursor);
                pendingSize -= cursor;
            }
        }

        private void ensureCapacity(int required) {
            if (required <= pending.length) return;
            int next = Math.max(required, pending.length * 2);
            pending = Arrays.copyOf(pending, next);
        }
    }
}
