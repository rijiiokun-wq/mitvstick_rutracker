package com.local.rutrackerplayer;

import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;

/** Requests the source cadence on this playback surface; never changes global display settings. */
@OptIn(markerClass = UnstableApi.class)
final class VideoFrameRateMatcher implements SurfaceHolder.Callback, VideoFrameMetadataListener {
    private static final String TAG = "VideoFrameRateMatcher";
    private final SurfaceHolder holder;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameCadenceEstimator estimator = new FrameCadenceEstimator();
    private volatile boolean released;
    private volatile float sourceFrameRate;
    private boolean playbackActive;
    private Surface appliedSurface;
    private float appliedFrameRate = -1;

    /** Create and call lifecycle methods on the player application's main thread. */
    VideoFrameRateMatcher(SurfaceView surfaceView) {
        holder = surfaceView.getHolder();
        holder.addCallback(this);
    }

    synchronized void onVideoInputFormat(Format format) {
        if (released) return;
        estimator.reset();
        if (validRate(format.frameRate)) acceptRate(format.frameRate);
    }

    /** False for an explicit pause/end; keep true during temporary buffering. */
    void setPlaybackActive(boolean active) {
        if (released || playbackActive == active) return;
        playbackActive = active;
        applyFrameRate();
    }

    float getSourceFrameRate() {
        return sourceFrameRate;
    }

    @Override public synchronized void onVideoFrameAboutToBeRendered(long presentationTimeUs,
            long releaseTimeNs, Format format, MediaFormat mediaFormat) {
        if (released) return;
        if (validRate(format.frameRate)) {
            acceptRate(format.frameRate);
        } else {
            float estimated = estimator.addTimestamp(presentationTimeUs);
            if (validRate(estimated)) acceptRate(estimated);
        }
    }

    private void acceptRate(float rate) {
        if (Math.abs(sourceFrameRate - rate) < 0.005f) return;
        sourceFrameRate = rate;
        main.post(() -> {
            if (!released) applyFrameRate();
        });
    }

    private static boolean validRate(float rate) {
        return !Float.isNaN(rate) && !Float.isInfinite(rate) && rate >= 4 && rate <= 250;
    }

    private void applyFrameRate() {
        if (Build.VERSION.SDK_INT < 30) return;
        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) return;
        float rate = playbackActive ? sourceFrameRate : 0;
        if (surface == appliedSurface && Math.abs(rate - appliedFrameRate) < 0.005f) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // Android still enforces the user's Match content frame rate preference.
                // ALWAYS lets opted-in TVs make a non-seamless HDMI switch for a movie.
                surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ALWAYS);
            } else {
                surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            }
            appliedSurface = surface;
            appliedFrameRate = rate;
            Log.i(TAG, "Requested source frame rate " + rate + " Hz");
        } catch (IllegalArgumentException | IllegalStateException e) {
            // A surface may become invalid during pause or an HDMI mode transition.
            Log.w(TAG, "Could not request source frame rate", e);
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        appliedSurface = null;
        appliedFrameRate = -1;
        if (!released) applyFrameRate();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (!released) applyFrameRate();
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        appliedSurface = null;
        appliedFrameRate = -1;
    }

    void release() {
        if (released) return;
        released = true;
        main.removeCallbacksAndMessages(null);
        playbackActive = false;
        applyFrameRate();
        holder.removeCallback(this);
    }
}
