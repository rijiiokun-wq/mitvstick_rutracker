package com.local.rutrackerplayer;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

final class TorrServerClient {
    interface Callback {
        void onProgress(String message);
        void onFilesReady(String hash, List<TorrentFile> files);
        void onError(String message, Throwable cause);
    }

    interface HealthCallback { void onResult(boolean available, String description); }
    interface SpeedCallback { void onResult(double bytesPerSecond); }

    private static final String BASE = "http://127.0.0.1:8090";
    private static final long METADATA_TIMEOUT_MS = 180_000;
    private static final long POLL_INTERVAL_MS = 2_000;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger operationGeneration = new AtomicInteger();
    private volatile boolean destroyed;
    private String lastSpeedHash = "";
    private long lastUsefulBytes = -1;
    private long lastSpeedSampleMs;

    void check(HealthCallback callback) {
        submit(() -> {
            try {
                String version = get("/echo", 2500);
                main.post(() -> { if (!destroyed) callback.onResult(true, version.trim()); });
            } catch (Throwable e) {
                main.post(() -> { if (!destroyed) callback.onResult(false, "не запущен"); });
            }
        });
    }

    void getDownloadSpeed(String hash, SpeedCallback callback) {
        if (hash == null || !hash.matches("(?i)[0-9a-f]{40}")) {
            main.post(() -> { if (!destroyed) callback.onResult(-1); });
            return;
        }
        submit(() -> {
            double speed = -1;
            try {
                JSONObject request = new JSONObject();
                request.put("action", "get");
                request.put("hash", hash);
                JSONObject status = postJson("/torrents", request);
                speed = status.optDouble("download_speed", -1);
                if (speed < 0) {
                    long usefulBytes = status.optLong("bytes_read_useful_data", -1);
                    long now = SystemClock.elapsedRealtime();
                    if (hash.equals(lastSpeedHash) && lastUsefulBytes >= 0
                            && usefulBytes >= lastUsefulBytes && now > lastSpeedSampleMs) {
                        speed = (usefulBytes - lastUsefulBytes) * 1000.0
                                / (now - lastSpeedSampleMs);
                    }
                    lastSpeedHash = hash;
                    lastUsefulBytes = usefulBytes;
                    lastSpeedSampleMs = now;
                }
            } catch (Throwable ignored) {}
            final double result = speed;
            main.post(() -> { if (!destroyed) callback.onResult(result); });
        });
    }

    void prepareMagnet(String magnet, String title, Callback callback) {
        final int operation = operationGeneration.incrementAndGet();
        submit(() -> {
            try {
                if (!isActive(operation)) throw new OperationCancelled();
                progress(callback, "Передаю резервную magnet-ссылку в TorrServer…", operation);
                JSONObject request = new JSONObject();
                request.put("action", "add");
                request.put("link", magnet);
                request.put("title", title == null ? "" : title);
                request.put("category", "movie");
                request.put("save_to_db", false);
                if (!isActive(operation)) throw new OperationCancelled();
                JSONObject status = postJson("/torrents", request);
                waitForMetadata(status, callback, operation);
            } catch (Throwable e) {
                if (isActive(operation)) fail(callback, friendly(e), e, operation);
            }
        });
    }

    void prepareTorrent(File file, String title, Callback callback) {
        final int operation = operationGeneration.incrementAndGet();
        if (!submit(() -> {
            try {
                if (!isActive(operation)) throw new OperationCancelled();
                progress(callback, "Передаю авторизованный .torrent в TorrServer…", operation);
                JSONObject status = uploadTorrent(file, title, operation);
                waitForMetadata(status, callback, operation);
            } catch (Throwable e) {
                if (isActive(operation)) fail(callback, friendly(e), e, operation);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        })) {
            // A completed authenticated download may race Activity teardown.
            // Never retain its temporary file when the client cannot accept work.
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** Cancels metadata work before a newer RuTracker topic is downloaded. */
    void cancelPendingPreparation() {
        operationGeneration.incrementAndGet();
    }

    private boolean submit(Runnable work) {
        if (destroyed) return false;
        try {
            executor.execute(work);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private void waitForMetadata(JSONObject first, Callback callback, int operation) throws Exception {
        String hash = first.optString("hash");
        if (hash.isEmpty()) throw new IOException("TorrServer не вернул hash");
        boolean handedOff = false;
        try {
            JSONObject status = first;
            long started = SystemClock.elapsedRealtime();
            long deadline = started + METADATA_TIMEOUT_MS;
            long nextProgress = started;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!isActive(operation)) throw new OperationCancelled();
                JSONArray files = status.optJSONArray("file_stats");
                if (files != null && files.length() > 0) {
                    List<TorrentFile> videoFiles = collectVideoFiles(files);
                    if (videoFiles.isEmpty()) {
                        throw new IOException("В раздаче нет поддерживаемых видеофайлов");
                    }
                    if (!isActive(operation)) throw new OperationCancelled();
                    handedOff = true;
                    main.post(() -> {
                        if (isActive(operation)) callback.onFilesReady(hash, videoFiles);
                        else dropTorrentAsync(hash);
                    });
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                if (now >= nextProgress) {
                    long elapsedSeconds = (now - started) / 1000;
                    progress(callback, "Получаю список файлов: " + elapsedSeconds
                            + " / 180 с. Ищу сидов…", operation);
                    nextProgress = now + 5_000;
                }
                Thread.sleep(POLL_INTERVAL_MS);
                JSONObject request = new JSONObject();
                request.put("action", "get");
                request.put("hash", hash);
                status = postJson("/torrents", request);
            }
            throw new IOException("Не удалось получить метаданные за 3 минуты. Выберите зелёную раздачу с 10+ сидами");
        } finally {
            // If cancellation, destruction, timeout, or an HTTP error happens
            // before the file list reaches the UI, do not leave a hidden
            // metadata session competing with the next stream for bandwidth.
            if (!handedOff) removeTorrentQuietly(hash);
        }
    }

    private void removeTorrentQuietly(String hash) {
        try {
            JSONObject request = new JSONObject();
            request.put("action", "rem");
            request.put("hash", hash);
            postJson("/torrents", request);
        } catch (Throwable ignored) {}
    }

    private static List<TorrentFile> collectVideoFiles(JSONArray files) throws Exception {
        List<TorrentFile> result = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            int id = file.optInt("id", -1);
            String path = file.optString("path");
            if (id >= 0 && !path.isEmpty() && isVideo(path)) {
                result.add(new TorrentFile(id, path, file.optLong("length", 0)));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean isVideo(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mkv") || lower.endsWith(".mp4") || lower.endsWith(".avi")
                || lower.endsWith(".m4v") || lower.endsWith(".mov") || lower.endsWith(".webm")
                || lower.endsWith(".ts") || lower.endsWith(".m2ts") || lower.endsWith(".mpg")
                || lower.endsWith(".mpeg");
    }

    static String buildStreamUrl(String hash, TorrentFile file) {
        if (hash == null || !hash.matches("(?i)[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Некорректный hash торрента");
        }
        if (file == null || file.id < 0 || file.fileName().isEmpty()) {
            throw new IllegalArgumentException("Некорректный видеофайл");
        }
        return Uri.parse(BASE).buildUpon()
                .appendPath("stream")
                .appendPath(file.fileName())
                .appendQueryParameter("link", hash)
                .appendQueryParameter("index", String.valueOf(file.id))
                .appendQueryParameter("play", "")
                .build()
                .toString();
    }

    private static JSONObject postJson(String path, JSONObject request) throws Exception {
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open(path, "POST", 10_000, 20_000);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setFixedLengthStreamingMode(body.length);
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) { output.write(body); }
        return new JSONObject(readResponse(connection));
    }

    /**
     * Unloads a stream that this app no longer reads. TorrServer otherwise keeps
     * the old reader alive for its disconnect timeout, so a cancelled preload can
     * compete with the next movie for peers and VPN bandwidth.
     */
    static void dropTorrentAsync(String hash) {
        if (hash == null || !hash.matches("(?i)[0-9a-f]{40}")) return;
        Thread cleanup = new Thread(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("action", "drop");
                request.put("hash", hash);
                postJson("/torrents", request);
            } catch (Throwable ignored) {
                // The server also expires disconnected readers by itself. This
                // best-effort request only shortens that window.
            }
        }, "torrserver-drop");
        cleanup.start();
    }

    private JSONObject uploadTorrent(File file, String title, int operation) throws Exception {
        if (!isActive(operation)) throw new OperationCancelled();
        String boundary = "----RutrackerTv" + System.currentTimeMillis();
        HttpURLConnection connection = open("/torrent/upload", "POST", 10_000, 30_000);
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(16 * 1024);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream out = connection.getOutputStream()) {
            field(out, boundary, "title", title == null ? "" : title);
            field(out, boundary, "category", "movie");
            String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"source.torrent\"\r\n"
                    + "Content-Type: application/x-bittorrent\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (!isActive(operation)) throw new OperationCancelled();
                    out.write(buffer, 0, read);
                }
            }
            if (!isActive(operation)) throw new OperationCancelled();
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (!isActive(operation)) throw new OperationCancelled();
        return new JSONObject(readResponse(connection));
    }

    private static void field(OutputStream out, String boundary, String name, String value) throws IOException {
        String data = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        out.write(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String get(String path, int timeout) throws Exception {
        return readResponse(open(path, "GET", timeout, timeout));
    }

    private static HttpURLConnection open(String path, String method, int connect, int read) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connect);
        connection.setReadTimeout(read);
        connection.setUseCaches(false);
        return connection;
    }

    private static String readResponse(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = input == null ? "" : read(input, 2 * 1024 * 1024);
        connection.disconnect();
        if (code < 200 || code >= 300) throw new IOException("TorrServer HTTP " + code + (text.isEmpty() ? "" : ": " + text));
        return text;
    }

    private static String read(InputStream input, int max) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > max) throw new IOException("Слишком большой ответ сервера");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private boolean isActive(int operation) {
        return !destroyed && operationGeneration.get() == operation;
    }

    private void progress(Callback callback, String message, int operation) {
        main.post(() -> { if (isActive(operation)) callback.onProgress(message); });
    }

    private void fail(Callback callback, String message, Throwable cause, int operation) {
        main.post(() -> { if (isActive(operation)) callback.onError(message, cause); });
    }

    private static String friendly(Throwable error) {
        String message = error.getMessage();
        if (message != null && (message.contains("Connection refused") || message.contains("failed to connect"))) {
            return "TorrServer не запущен на приставке";
        }
        return message == null || message.isEmpty() ? "Ошибка TorrServer" : message;
    }

    void destroy() {
        destroyed = true;
        operationGeneration.incrementAndGet();
        executor.shutdownNow();
    }

    private static final class OperationCancelled extends IOException {}
}
