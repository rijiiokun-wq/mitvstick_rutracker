package com.local.rutrackerplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends Activity {
    static final String EXTRA_URL = "stream_url";
    static final String EXTRA_TITLE = "stream_title";
    static final String EXTRA_FILE_SIZE_BYTES = "stream_file_size_bytes";
    static final String EXTRA_FILE_ID = "stream_file_id";
    static final String EXTRA_FILE_PATH = "stream_file_path";
    static final String EXTRA_TOPIC_ID = "topic_id";
    static final String EXTRA_TOPIC_URL = "topic_url";
    static final String EXTRA_FORUM = "topic_forum";
    static final String EXTRA_RESULT_SIZE = "result_size";
    static final String EXTRA_SEEDS = "result_seeds";
    static final String EXTRA_START_POSITION_MS = "start_position_ms";
    private static final long SPEED_POLL_INTERVAL_MS = 1_000;
    private static final long OVERLAY_TICK_MS = 500;
    private static final long PROGRESS_SAVE_INTERVAL_MS = 5_000;
    private static final long MIN_RESUME_POSITION_MS = 15_000;
    private static final long COMPLETION_MARGIN_MS = 60_000;
    private static final int STREAM_CONNECT_TIMEOUT_MS = 15_000;
    private static final int STREAM_READ_TIMEOUT_MS = 120_000;
    private static final String PROGRESS_PREFS = "playback_progress";

    private ExoPlayer player;
    private AdaptiveLoadControl adaptiveLoadControl;
    private MediaItem mediaItem;
    private TextView speedOverlay;
    private final Handler overlayHandler = new Handler(Looper.getMainLooper());
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final TorrServerClient statsClient = new TorrServerClient();
    private SharedPreferences progressPrefs;
    private String progressKey;
    private String torrentHash;
    private String historyTitle;
    private String topicUrl;
    private String forum;
    private String resultSize;
    private String seeds;
    private String selectedFilePath;
    private long topicId;
    private int selectedFileId;
    private long fileSizeBytes;
    private long explicitStartPositionMs;
    private boolean playbackStarted;
    private boolean playbackCompleted;
    private boolean monitoringActive;
    private boolean overlayVisible;
    private boolean statsRequestInFlight;
    private boolean hasPlayedContent;
    private boolean httpRangeRetryUsed;
    private boolean insufficientSpeedWarningShown;
    private int slowSpeedSamples;
    private int previousPlaybackState = Player.STATE_IDLE;
    private double smoothedDownloadSpeed = -1;
    private long activePlaybackMs;
    private long lastOverlayTickMs;
    private long nextSpeedPollMs;

    private final Runnable overlayTick = new Runnable() {
        @Override public void run() {
            if (!monitoringActive || player == null) return;
            long now = SystemClock.elapsedRealtime();
            if (player.isPlaying()) activePlaybackMs += Math.max(0, now - lastOverlayTickMs);
            lastOverlayTickMs = now;
            if (overlayVisible && activePlaybackMs >= BuildConfig.SPEED_OVERLAY_TIMEOUT_MS) {
                hideSpeedOverlay();
            }
            if (!statsRequestInFlight && now >= nextSpeedPollMs) requestDownloadSpeed(now);
            if (overlayVisible) updateSpeedOverlay();
            overlayHandler.postDelayed(this, OVERLAY_TICK_MS);
        }
    };

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (!playbackStarted || player == null) return;
            saveProgress();
            progressHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.isEmpty()) { finish(); return; }
        PlayerView view = new PlayerView(this);
        view.setUseController(true);
        view.setControllerAutoShow(true);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        FrameLayout root = new FrameLayout(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        speedOverlay = buildSpeedOverlay();
        FrameLayout.LayoutParams speedParams = new FrameLayout.LayoutParams(-2, -2);
        speedParams.gravity = Gravity.TOP | Gravity.START;
        speedParams.setMargins(dp(24), dp(24), dp(24), 0);
        root.addView(speedOverlay, speedParams);
        setContentView(root);

        Uri streamUri = Uri.parse(url);
        torrentHash = streamUri.getQueryParameter("link");
        fileSizeBytes = getIntent().getLongExtra(EXTRA_FILE_SIZE_BYTES, 0);
        String fileIndex = streamUri.getQueryParameter("index");
        selectedFileId = getIntent().getIntExtra(EXTRA_FILE_ID,
                fileIndex == null ? -1 : parseInt(fileIndex, -1));
        selectedFilePath = safe(getIntent().getStringExtra(EXTRA_FILE_PATH));
        topicId = getIntent().getLongExtra(EXTRA_TOPIC_ID, 0);
        topicUrl = safe(getIntent().getStringExtra(EXTRA_TOPIC_URL));
        forum = safe(getIntent().getStringExtra(EXTRA_FORUM));
        resultSize = safe(getIntent().getStringExtra(EXTRA_RESULT_SIZE));
        seeds = safe(getIntent().getStringExtra(EXTRA_SEEDS));
        explicitStartPositionMs = Math.max(0,
                getIntent().getLongExtra(EXTRA_START_POSITION_MS, 0));
        progressPrefs = getSharedPreferences(PROGRESS_PREFS, MODE_PRIVATE);
        String streamIdentity = torrentHash == null || torrentHash.isEmpty()
                ? Integer.toHexString(url.hashCode()) : torrentHash;
        progressKey = streamIdentity + "_" + (fileIndex == null ? "0" : fileIndex);
        adaptiveLoadControl = new AdaptiveLoadControl();
        DefaultHttpDataSource.Factory streamDataSource = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(STREAM_CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(STREAM_READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true);
        player = new ExoPlayer.Builder(this, buildRenderersFactory(this))
                .setLoadControl(adaptiveLoadControl)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(
                        streamDataSource, new Ac3RechunkingExtractorsFactory()))
                .build();
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                if (!httpRangeRetryUsed && isHttpRangeError(error) && player != null) {
                    httpRangeRetryUsed = true;
                    clearProgress();
                    previousPlaybackState = Player.STATE_IDLE;
                    player.stop();
                    player.setMediaItem(mediaItem, 0);
                    player.prepare();
                    player.play();
                    Toast.makeText(PlayerActivity.this,
                            "Поток изменился — перезапускаю с начала", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(PlayerActivity.this, "Ошибка воспроизведения: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
            }

            @Override public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_BUFFERING && hasPlayedContent
                        && previousPlaybackState != Player.STATE_BUFFERING) {
                    adaptiveLoadControl.onRebuffer();
                }
                if (playbackState == Player.STATE_READY) configureAdaptiveBuffer();
                if (playbackState == Player.STATE_ENDED && hasPlayedContent) {
                    long duration = player == null ? 0 : Math.max(0, player.getDuration());
                    saveHistory(duration, duration, true);
                    playbackCompleted = true;
                    clearProgress();
                }
                previousPlaybackState = playbackState;
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) hasPlayedContent = true;
            }
        });
        historyTitle = safe(getIntent().getStringExtra(EXTRA_TITLE));
        String mediaTitle = historyTitle.isEmpty() ? "RuTracker" : historyTitle;
        mediaItem = new MediaItem.Builder().setUri(url).setMediaId(mediaTitle).build();
        long savedPosition = Math.max(savedPosition(), explicitStartPositionMs);
        if (savedPosition >= MIN_RESUME_POSITION_MS) showResumeDialog(savedPosition);
        else beginPlayback(0);
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (Throwable ignored) { return fallback; }
    }

    private static DefaultRenderersFactory buildRenderersFactory(Context context) {
        MediaCodecSelector selector = (mimeType, secure, tunneling) -> {
            List<MediaCodecInfo> available = new ArrayList<>(
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling));
            if ("video/mp4v-es".equals(mimeType)) {
                // Prefer the Amlogic hardware path for old XVID/MPEG-4 Part 2.
                // Software decoding on this 2 GB stick produces visible frame
                // blending and cannot keep presentation timing stable.
                available.sort((left, right) ->
                        Boolean.compare(left.softwareOnly, right.softwareOnly));
            }
            return available;
        };
        return new DefaultRenderersFactory(context) {
            @Override protected AudioSink buildAudioSink(Context sinkContext,
                    boolean enableFloatOutput, boolean enablePlaybackParams) {
                // This firmware's c2.amlogic AC3 decoder crashes on older AVI
                // tracks. The context-aware sink uses the TV/HDMI capabilities,
                // allowing supported AC3 tracks to bypass that broken decoder.
                return new DefaultAudioSink.Builder(sinkContext)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enablePlaybackParams)
                        .build();
            }
        }.setMediaCodecSelector(selector).setEnableDecoderFallback(true);
    }

    private static boolean isHttpRangeError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof HttpDataSource.InvalidResponseCodeException
                    && ((HttpDataSource.InvalidResponseCodeException) current).responseCode == 416) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void beginPlayback(long startPositionMs) {
        if (playbackStarted || player == null) return;
        playbackStarted = true;
        player.setMediaItem(mediaItem, Math.max(0, startPositionMs));
        player.prepare();
        player.play();
        startSpeedOverlay();
        progressHandler.postDelayed(progressTick, PROGRESS_SAVE_INTERVAL_MS);
    }

    private void showResumeDialog(long savedPositionMs) {
        long savedDuration = progressPrefs.getLong(durationKey(), -1);
        String message = savedDuration > 0
                ? "Последняя позиция — " + formatPlaybackTime(savedPositionMs)
                        + " из " + formatPlaybackTime(savedDuration) + "."
                : "Вы остановились на " + formatPlaybackTime(savedPositionMs) + ".";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Продолжить просмотр?")
                .setMessage(message)
                .setPositiveButton("Продолжить", (ignored, which) -> beginPlayback(savedPositionMs))
                .setNegativeButton("Начать сначала", (ignored, which) -> {
                    clearProgress();
                    beginPlayback(0);
                })
                .setOnCancelListener(ignored -> finish())
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus());
        dialog.show();
    }

    private long savedPosition() {
        long position = progressPrefs.getLong(positionKey(), 0);
        long duration = progressPrefs.getLong(durationKey(), -1);
        if (position < MIN_RESUME_POSITION_MS
                || (duration > 0 && duration - position <= COMPLETION_MARGIN_MS)) {
            clearProgress();
            return 0;
        }
        return position;
    }

    private void saveProgress() {
        if (!playbackStarted || playbackCompleted || player == null || progressPrefs == null) return;
        long position = Math.max(0, player.getCurrentPosition());
        long duration = player.getDuration();
        if (duration > 0 && duration - position <= COMPLETION_MARGIN_MS) {
            clearProgress();
            if (hasPlayedContent) saveHistory(duration, duration, true);
            playbackCompleted = true;
            return;
        }
        if (position < MIN_RESUME_POSITION_MS) return;
        SharedPreferences.Editor editor = progressPrefs.edit().putLong(positionKey(), position);
        if (duration > 0) editor.putLong(durationKey(), duration);
        editor.apply();
        if (hasPlayedContent) saveHistory(position, duration, false);
    }

    private void saveHistory(long positionMs, long durationMs, boolean completed) {
        if (activePlaybackMs < MIN_RESUME_POSITION_MS) return;
        if (positionMs < MIN_RESUME_POSITION_MS && !completed) return;
        String key = PlaybackHistory.makeKey(topicId, selectedFilePath, torrentHash, selectedFileId);
        PlaybackHistory.upsert(this, new PlaybackHistory.Entry(
                key, progressKey, topicId, historyTitle, topicUrl, forum, resultSize, seeds,
                torrentHash, selectedFileId, selectedFilePath, fileSizeBytes,
                positionMs, durationMs, System.currentTimeMillis(), completed));
    }

    private void clearProgress() {
        if (progressPrefs == null || progressKey == null) return;
        progressPrefs.edit().remove(positionKey()).remove(durationKey()).apply();
    }

    private String positionKey() { return progressKey + ".position"; }
    private String durationKey() { return progressKey + ".duration"; }

    private static String formatPlaybackTime(long timeMs) {
        long seconds = Math.max(0, timeMs / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, remainingSeconds);
        }
        return String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds);
    }

    private TextView buildSpeedOverlay() {
        TextView label = new TextView(this);
        label.setText("Скачивание: —");
        label.setTextColor(Color.argb(240, 255, 255, 255));
        label.setTextSize(18);
        label.setPadding(dp(14), dp(9), dp(14), dp(9));
        label.setFocusable(false);
        label.setClickable(false);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        label.setVisibility(View.GONE);
        label.setElevation(dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(150, 0, 0, 0));
        background.setCornerRadius(dp(10));
        label.setBackground(background);
        return label;
    }

    private void startSpeedOverlay() {
        monitoringActive = true;
        overlayVisible = true;
        activePlaybackMs = 0;
        lastOverlayTickMs = SystemClock.elapsedRealtime();
        nextSpeedPollMs = 0;
        smoothedDownloadSpeed = -1;
        speedOverlay.setVisibility(View.VISIBLE);
        overlayHandler.post(overlayTick);
    }

    private void requestDownloadSpeed(long now) {
        statsRequestInFlight = true;
        nextSpeedPollMs = now + SPEED_POLL_INTERVAL_MS;
        statsClient.getDownloadSpeed(torrentHash, bytesPerSecond -> {
            statsRequestInFlight = false;
            if (!monitoringActive || speedOverlay == null) return;
            if (bytesPerSecond < 0) return;
            smoothedDownloadSpeed = smoothedDownloadSpeed < 0 ? bytesPerSecond
                    : smoothedDownloadSpeed * 0.75 + bytesPerSecond * 0.25;
            adaptiveLoadControl.onDownloadSpeed(smoothedDownloadSpeed);
            checkInsufficientSpeed(smoothedDownloadSpeed);
            if (overlayVisible) updateSpeedOverlay();
        });
    }

    private void configureAdaptiveBuffer() {
        if (player == null || adaptiveLoadControl == null) return;
        long duration = player.getDuration();
        if (duration > 0) adaptiveLoadControl.configureMedia(fileSizeBytes, duration);
    }

    private void updateSpeedOverlay() {
        if (speedOverlay == null || player == null) return;
        long bufferedSeconds = Math.max(0, player.getTotalBufferedDuration() / 1_000);
        String target = adaptiveLoadControl == null ? ""
                : " / " + adaptiveLoadControl.getTargetBufferSeconds() + " с";
        speedOverlay.setText(formatSpeed(smoothedDownloadSpeed)
                + "\nБуфер: " + bufferedSeconds + " с" + target);
    }

    private void checkInsufficientSpeed(double bytesPerSecond) {
        double required = adaptiveLoadControl.getRequiredBytesPerSecond();
        if (required <= 0 || bytesPerSecond >= required * 0.85) {
            slowSpeedSamples = 0;
            return;
        }
        slowSpeedSamples++;
        if (!insufficientSpeedWarningShown && slowSpeedSamples >= 10) {
            insufficientSpeedWarningShown = true;
            Toast.makeText(this, "Скорость раздачи ниже требуемой: "
                    + formatRate(bytesPerSecond) + " из " + formatRate(required)
                    + ". Буфер увеличен; для непрерывного просмотра лучше выбрать меньший файл.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void hideSpeedOverlay() {
        overlayVisible = false;
        if (speedOverlay != null) speedOverlay.setVisibility(View.GONE);
    }

    private void stopPlaybackMonitoring() {
        if (!monitoringActive) return;
        monitoringActive = false;
        overlayHandler.removeCallbacks(overlayTick);
        hideSpeedOverlay();
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 0) return "Скачивание: —";
        if (bytesPerSecond >= 1024 * 1024) {
            return String.format(Locale.getDefault(), "Скачивание: %.1f МБ/с", bytesPerSecond / (1024 * 1024));
        }
        if (bytesPerSecond >= 1024) {
            return String.format(Locale.getDefault(), "Скачивание: %.1f КБ/с", bytesPerSecond / 1024);
        }
        return String.format(Locale.getDefault(), "Скачивание: %.0f Б/с", bytesPerSecond);
    }

    private static String formatRate(double bytesPerSecond) {
        if (bytesPerSecond >= 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f МБ/с", bytesPerSecond / (1024 * 1024));
        }
        return String.format(Locale.getDefault(), "%.0f КБ/с", bytesPerSecond / 1024);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onStop() {
        boolean finishAfterStop = !isChangingConfigurations() && !isFinishing();
        saveProgress();
        progressHandler.removeCallbacksAndMessages(null);
        stopPlaybackMonitoring();
        if (player != null) { player.release(); player = null; }
        TorrServerClient.dropTorrentAsync(torrentHash);
        statsClient.destroy();
        super.onStop();
        if (finishAfterStop) finish();
    }

    @Override protected void onDestroy() {
        progressHandler.removeCallbacksAndMessages(null);
        overlayHandler.removeCallbacksAndMessages(null);
        statsClient.destroy();
        super.onDestroy();
    }
}
