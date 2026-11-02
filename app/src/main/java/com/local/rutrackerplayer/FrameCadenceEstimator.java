package com.local.rutrackerplayer;

import java.util.Arrays;

/** Estimates a fixed source cadence from PTS, never from wall-clock rendering speed. */
final class FrameCadenceEstimator {
    private static final int REQUIRED_INTERVALS = 48;
    private static final long MIN_INTERVAL_US = 4_000;
    private static final long MAX_INTERVAL_US = 250_000;
    private long previousPtsUs = Long.MIN_VALUE;
    private final long[] intervalsUs = new long[REQUIRED_INTERVALS];
    private int intervalCount;
    private int writeIndex;
    private float stableFrameRate;

    float addTimestamp(long ptsUs) {
        if (stableFrameRate > 0) return stableFrameRate;
        if (previousPtsUs == Long.MIN_VALUE) {
            previousPtsUs = ptsUs;
            return 0;
        }
        long intervalUs = ptsUs - previousPtsUs;
        previousPtsUs = ptsUs;
        if (intervalUs < MIN_INTERVAL_US || intervalUs > MAX_INTERVAL_US) {
            clearIntervals();
            return 0;
        }
        intervalsUs[writeIndex] = intervalUs;
        writeIndex = (writeIndex + 1) % REQUIRED_INTERVALS;
        intervalCount = Math.min(REQUIRED_INTERVALS, intervalCount + 1);
        if (intervalCount >= REQUIRED_INTERVALS) {
            // About-to-render callbacks omit dropped frames. Recover the fundamental
            // from normal intervals plus integer-multiple gaps instead of measuring
            // the already-degraded rendered FPS. A non-harmonic VFR window is rejected.
            long[] sorted = intervalsUs.clone();
            Arrays.sort(sorted);
            double fundamentalUs = sorted[REQUIRED_INTERVALS / 4];
            double toleranceUs = Math.max(50, fundamentalUs * 0.001);
            long matchedTimeUs = 0;
            int matchedFrames = 0;
            int matches = 0;
            int singleFrameIntervals = 0;
            for (long interval : sorted) {
                int multiple = (int) Math.round(interval / fundamentalUs);
                if (multiple >= 1 && multiple <= 8
                        && Math.abs(interval - multiple * fundamentalUs)
                        <= toleranceUs * multiple) {
                    matches++;
                    matchedFrames += multiple;
                    matchedTimeUs += interval;
                    if (multiple == 1) singleFrameIntervals++;
                }
            }
            if (matches >= REQUIRED_INTERVALS - 2
                    && singleFrameIntervals >= REQUIRED_INTERVALS / 3) {
                stableFrameRate = (float) (1_000_000.0 * matchedFrames / matchedTimeUs);
            }
        }
        return stableFrameRate;
    }

    void reset() {
        previousPtsUs = Long.MIN_VALUE;
        stableFrameRate = 0;
        clearIntervals();
    }

    private void clearIntervals() {
        intervalCount = 0;
        writeIndex = 0;
    }
}
