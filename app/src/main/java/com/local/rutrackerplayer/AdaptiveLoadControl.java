package com.local.rutrackerplayer;

import android.os.SystemClock;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

/** Memory-bounded load control that grows its forward buffer when needed. */
@OptIn(markerClass = UnstableApi.class)
final class AdaptiveLoadControl extends DefaultLoadControl {
    private static final int MIB = 1024 * 1024;
    private static final int ALLOCATION_SIZE = 64 * 1024;
    private static final int MAX_BUFFER_MS = 180_000;
    private static final int[] TARGET_BUFFER_MS = {45_000, 75_000, 120_000};
    private static final int[] START_BUFFER_MS = {20_000, 30_000, 45_000};
    private static final int[] REBUFFER_MS = {30_000, 60_000, 90_000};
    private static final int[] TARGET_BUFFER_BYTES = {96 * MIB, 112 * MIB, 128 * MIB};
    private static final long SPEED_INCREASE_COOLDOWN_MS = 30_000;

    private final DefaultAllocator allocator;
    private volatile int stage;
    private volatile long mediaDurationUs = -1;
    private volatile double requiredBytesPerSecond = -1;
    private int slowSamples;
    private long lastIncreaseMs;

    AdaptiveLoadControl() {
        this(new DefaultAllocator(true, ALLOCATION_SIZE));
    }

    private AdaptiveLoadControl(DefaultAllocator allocator) {
        super(
                allocator,
                MAX_BUFFER_MS,
                MAX_BUFFER_MS,
                MAX_BUFFER_MS,
                MAX_BUFFER_MS,
                START_BUFFER_MS[0],
                START_BUFFER_MS[0],
                REBUFFER_MS[0],
                REBUFFER_MS[0],
                TARGET_BUFFER_BYTES[TARGET_BUFFER_BYTES.length - 1],
                false,
                false,
                0,
                false);
        this.allocator = allocator;
    }

    synchronized void configureMedia(long fileSizeBytes, long durationMs) {
        if (durationMs <= 0) return;
        mediaDurationUs = durationMs * 1_000L;
        if (fileSizeBytes <= 0) return;
        requiredBytesPerSecond = fileSizeBytes * 1_000.0 / durationMs;
        if (fileSizeBytes >= 20L * 1024 * MIB || requiredBytesPerSecond >= 4.0 * MIB) {
            increaseTo(1);
        }
    }

    synchronized void onDownloadSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 0 || requiredBytesPerSecond <= 0) return;
        if (bytesPerSecond < requiredBytesPerSecond * 1.20) slowSamples++;
        else slowSamples = 0;
        long now = SystemClock.elapsedRealtime();
        if (slowSamples >= 3 && (lastIncreaseMs == 0
                || now - lastIncreaseMs >= SPEED_INCREASE_COOLDOWN_MS)) {
            increaseTo(stage + 1);
            slowSamples = 0;
        }
    }

    synchronized void onRebuffer() {
        increaseTo(stage + 1);
    }

    int getTargetBufferSeconds() {
        return TARGET_BUFFER_MS[stage] / 1_000;
    }

    double getRequiredBytesPerSecond() {
        return requiredBytesPerSecond;
    }

    @Override public boolean shouldContinueLoading(LoadControl.Parameters parameters) {
        boolean baseDecision = super.shouldContinueLoading(parameters);
        int currentStage = stage;
        if (parameters.bufferedDurationUs >= TARGET_BUFFER_MS[currentStage] * 1_000L) return false;
        if (allocator.getTotalBytesAllocated() >= TARGET_BUFFER_BYTES[currentStage]) return false;
        return baseDecision;
    }

    @Override public boolean shouldStartPlayback(LoadControl.Parameters parameters) {
        int currentStage = stage;
        long requiredUs = (parameters.rebuffering
                ? REBUFFER_MS[currentStage] : START_BUFFER_MS[currentStage]) * 1_000L;
        long playoutBufferedUs = parameters.playbackSpeed > 0
                ? (long) (parameters.bufferedDurationUs / parameters.playbackSpeed)
                : parameters.bufferedDurationUs;
        boolean timeReady = playoutBufferedUs >= requiredUs;
        boolean memoryLimitReady = allocator.getTotalBytesAllocated()
                >= TARGET_BUFFER_BYTES[currentStage] - ALLOCATION_SIZE;
        boolean endOfMediaBuffered = mediaDurationUs > 0
                && parameters.playbackPositionUs + parameters.bufferedDurationUs
                >= mediaDurationUs - 500_000L;
        return timeReady || memoryLimitReady || endOfMediaBuffered;
    }

    private void increaseTo(int requestedStage) {
        int next = Math.min(TARGET_BUFFER_MS.length - 1, Math.max(stage, requestedStage));
        if (next != stage) {
            stage = next;
            lastIncreaseMs = SystemClock.elapsedRealtime();
        }
    }
}
