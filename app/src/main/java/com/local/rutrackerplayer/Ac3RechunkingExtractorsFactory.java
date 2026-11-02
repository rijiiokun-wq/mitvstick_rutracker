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
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ForwardingExtractor;
import androidx.media3.extractor.ForwardingExtractorOutput;
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

        Ac3RechunkingExtractor(Extractor delegate) {
            super(delegate);
        }

        @Override public void init(ExtractorOutput output) {
            super.init(new ForwardingExtractorOutput(output) {
                @Override public TrackOutput track(int id, @C.TrackType int type) {
                    TrackOutput target = super.track(id, type);
                    if (type != C.TRACK_TYPE_AUDIO) return target;
                    Ac3TrackOutput existing = audioTracks.get(id);
                    if (existing != null) return existing;
                    Ac3TrackOutput created = new Ac3TrackOutput(target);
                    audioTracks.put(id, created);
                    return created;
                }
            });
        }

        @Override public void seek(long position, long timeUs) {
            for (Ac3TrackOutput output : audioTracks.values()) output.reset(timeUs);
            super.seek(position, timeUs);
        }
    }

    private static final class Ac3TrackOutput implements TrackOutput {
        private static final int INITIAL_CAPACITY = 32 * 1024;
        private static final int MAX_SYNCFRAME_SIZE = 1024 * 1024;

        private final TrackOutput delegate;
        private final byte[] syncHeader = new byte[6];
        private byte[] pending = new byte[INITIAL_CAPACITY];
        private int pendingSize;
        private boolean rechunk;
        private int sampleRate = 48_000;
        private long baseTimeUs = C.TIME_UNSET;
        private long emittedSamples;

        Ac3TrackOutput(TrackOutput delegate) {
            this.delegate = delegate;
        }

        @Override public void durationUs(long durationUs) {
            delegate.durationUs(durationUs);
        }

        @Override public void format(Format format) {
            rechunk = MimeTypes.AUDIO_AC3.equals(format.sampleMimeType)
                    || MimeTypes.AUDIO_E_AC3.equals(format.sampleMimeType)
                    || MimeTypes.AUDIO_E_AC3_JOC.equals(format.sampleMimeType);
            if (format.sampleRate > 0) sampleRate = format.sampleRate;
            delegate.format(format);
        }

        @Override public int sampleData(DataReader input, int length,
                boolean allowEndOfInput, @SampleDataPart int sampleDataPart) throws IOException {
            if (!rechunk || sampleDataPart != SAMPLE_DATA_PART_MAIN) {
                return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart);
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

        void reset(long timeUs) {
            pendingSize = 0;
            emittedSamples = 0;
            baseTimeUs = timeUs;
        }

        private void emitCompleteFrames() {
            if (!rechunk || baseTimeUs == C.TIME_UNSET) return;
            int cursor = 0;
            while (pendingSize - cursor >= syncHeader.length) {
                if ((pending[cursor] & 0xFF) != 0x0B
                        || (pending[cursor + 1] & 0xFF) != 0x77) {
                    cursor++;
                    continue;
                }
                System.arraycopy(pending, cursor, syncHeader, 0, syncHeader.length);
                int frameSize = Ac3Util.parseAc3SyncframeSize(syncHeader);
                if (frameSize <= 0 || frameSize > MAX_SYNCFRAME_SIZE) {
                    cursor++;
                    continue;
                }
                if (pendingSize - cursor < frameSize) break;

                ByteBuffer frame = ByteBuffer.wrap(pending, cursor, frameSize);
                int sampleCount = Ac3Util.parseAc3SyncframeAudioSampleCount(frame);
                ParsableByteArray frameData = new ParsableByteArray(pending, pendingSize);
                frameData.setPosition(cursor);
                delegate.sampleData(frameData, frameSize, SAMPLE_DATA_PART_MAIN);
                long timeUs = baseTimeUs + Util.scaleLargeTimestamp(
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
