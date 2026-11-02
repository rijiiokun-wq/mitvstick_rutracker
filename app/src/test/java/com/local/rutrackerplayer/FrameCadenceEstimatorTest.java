package com.local.rutrackerplayer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FrameCadenceEstimatorTest {
    @Test public void detectsExact23976Cadence() { fixedCadence(23.976); }

    @Test public void detectsNtscFilmCadence() { fixedCadence(24000.0 / 1001); }

    @Test public void detects24FpsCadence() { fixedCadence(24); }

    @Test public void detects25FpsCadence() { fixedCadence(25); }

    @Test public void detectsNtsc30Cadence() { fixedCadence(30000.0 / 1001); }

    @Test public void detects30FpsCadence() { fixedCadence(30); }

    @Test public void detects50FpsCadence() { fixedCadence(50); }

    @Test public void detectsNtsc60Cadence() { fixedCadence(60000.0 / 1001); }

    private static void fixedCadence(double fps) {
        FrameCadenceEstimator estimator = new FrameCadenceEstimator();
        float result = 0;
        for (int i = 0; i <= 48; i++) {
            result = estimator.addTimestamp(Math.round(i * 1_000_000.0 / fps));
            if (i < 48) assertEquals("Locked too early", 0, result, 0);
        }
        close(result, fps);
    }

    @Test public void rejectsVariableCadence() {
        FrameCadenceEstimator estimator = new FrameCadenceEstimator();
        long pts = 0;
        for (int i = 0; i < 500; i++) {
            assertEquals("Locked to VFR", 0, estimator.addTimestamp(pts), 0);
            pts += i % 2 == 0 ? 40_000 : 33_333;
        }
    }

    @Test public void recoversAfterSeekAndDrop() {
        FrameCadenceEstimator estimator = new FrameCadenceEstimator();
        for (int i = 0; i < 25; i++) estimator.addTimestamp(i * 40_000L);
        estimator.addTimestamp(0); // backward seek
        for (int i = 1; i < 25; i++) estimator.addTimestamp(i * 40_000L);
        estimator.addTimestamp(1_040_000); // one omitted frame
        float result = 0;
        for (int i = 1; i <= 49; i++) result = estimator.addTimestamp(1_040_000 + i * 40_000L);
        close(result, 25);
    }

    @Test public void latchesUntilReset() {
        FrameCadenceEstimator estimator = new FrameCadenceEstimator();
        for (int i = 0; i <= 48; i++) estimator.addTimestamp(i * 40_000L);
        close(estimator.addTimestamp(900_000_000), 25);
        estimator.reset();
        float result = 0;
        for (int i = 0; i <= 48; i++) result = estimator.addTimestamp(Math.round(i * 1_000_000.0 / 30));
        close(result, 30);
    }

    @Test public void recoversFrequentDroppedFrames() {
        FrameCadenceEstimator estimator = new FrameCadenceEstimator();
        int sourceFrame = 0;
        float result = 0;
        for (int i = 0; i <= 80; i++) {
            result = estimator.addTimestamp(Math.round(sourceFrame * 1_000_000.0 / 23.976));
            sourceFrame += i % 3 == 0 ? 2 : 1; // 18 rendered FPS from a 23.976 FPS source
        }
        close(result, 23.976);
    }

    @Test public void rejectsVariableCadenceWithDrops() {
        FrameCadenceEstimator estimator = new FrameCadenceEstimator();
        long pts = 0;
        for (int i = 0; i < 500; i++) {
            assertEquals("Locked to VFR gaps", 0, estimator.addTimestamp(pts), 0);
            pts += i % 2 == 0 ? 83_417 : 33_333;
        }
    }

    private static void close(float actual, double expected) {
        assertEquals(expected, actual, 0.001);
    }
}
