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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class TorrServerClient {
    interface Callback {
        void onProgress(String message);
        void onReady(String streamUrl, String fileName, long fileSizeBytes);
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

    void check(HealthCallback callback) {
        executor.execute(() -> {
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
        executor.execute(() -> {
            double speed = -1;
            try {
                JSONObject request = new JSONObject();
                request.put("action", "get");
                request.put("hash", hash);
                speed = postJson("/torrents", request).optDouble("download_speed", -1);
            } catch (Throwable ignored) {}
            final double result = speed;
            main.post(() -> { if (!destroyed) callback.onResult(result); });
        });
    }

    void prepareMagnet(String magnet, String title, Callback callback) {
        final int operation = operationGeneration.incrementAndGet();
        executor.execute(() -> {
            try {
                progress(callback, "Передаю резервную magnet-ссылку в TorrServer…", operation);
                JSONObject request = new JSONObject();
                request.put("action", "add");
                request.put("link", magnet);
                request.put("title", title == null ? "" : title);
                request.put("category", "movie");
                request.put("save_to_db", false);
                JSONObject status = postJson("/torrents", request);
                waitForMetadata(status, callback, operation);
            } catch (Throwable e) {
                if (isActive(operation)) fail(callback, friendly(e), e, operation);
            }
        });
    }

    void prepareTorrent(File file, String title, Callback callback) {
        final int operation = operationGeneration.incrementAndGet();
        executor.execute(() -> {
            try {
                progress(callback, "Передаю авторизованный .torrent в TorrServer…", operation);
                JSONObject status = uploadTorrent(file, title);
                waitForMetadata(status, callback, operation);
            } catch (Throwable e) {
                if (isActive(operation)) fail(callback, friendly(e), e, operation);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        });
    }

    private void waitForMetadata(JSONObject first, Callback callback, int operation) throws Exception {
        String hash = first.optString("hash");
        if (hash.isEmpty()) throw new IOException("TorrServer не вернул hash");
        JSONObject status = first;
        long started = SystemClock.elapsedRealtime();
        long deadline = started + METADATA_TIMEOUT_MS;
        long nextProgress = started;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isActive(operation)) {
                removeTorrentQuietly(hash);
                throw new OperationCancelled();
            }
            JSONArray files = status.optJSONArray("file_stats");
            if (files != null && files.length() > 0) {
                JSONObject selected = chooseVideo(files);
                int id = selected.optInt("id", -1);
                String path = selected.optString("path");
                long fileSizeBytes = selected.optLong("length", 0);
                if (id < 0 || path.isEmpty()) throw new IOException("Некорректный список файлов");
                String name = path.substring(path.lastIndexOf('/') + 1);
                Uri stream = Uri.parse(BASE).buildUpon()
                        .appendPath("stream")
                        .appendPath(name)
                        .appendQueryParameter("link", hash)
                        .appendQueryParameter("index", String.valueOf(id))
                        .appendQueryParameter("play", "")
                        .appendQueryParameter("preload", "")
                        .build();
                String url = stream.toString();
                main.post(() -> {
                    if (isActive(operation)) callback.onReady(url, name, fileSizeBytes);
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
        removeTorrentQuietly(hash);
        throw new IOException("Не удалось получить метаданные за 3 минуты. Выберите зелёную раздачу с 10+ сидами");
    }

    private void removeTorrentQuietly(String hash) {
        try {
            JSONObject request = new JSONObject();
            request.put("action", "rem");
            request.put("hash", hash);
            postJson("/torrents", request);
        } catch (Throwable ignored) {}
    }

    private static JSONObject chooseVideo(JSONArray files) throws Exception {
        JSONObject largestVideo = null;
        JSONObject largestAny = null;
        long videoLength = -1;
        long anyLength = -1;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            long length = file.optLong("length", 0);
            String path = file.optString("path").toLowerCase(Locale.ROOT);
            if (length > anyLength) { largestAny = file; anyLength = length; }
            if (isVideo(path) && length > videoLength) { largestVideo = file; videoLength = length; }
        }
        if (largestVideo != null) return largestVideo;
        if (largestAny != null) return largestAny;
        throw new IOException("В раздаче нет файлов");
    }

    private static boolean isVideo(String path) {
        return path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".avi")
                || path.endsWith(".m4v") || path.endsWith(".mov") || path.endsWith(".webm")
                || path.endsWith(".ts") || path.endsWith(".m2ts") || path.endsWith(".mpg")
                || path.endsWith(".mpeg");
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

    private static JSONObject uploadTorrent(File file, String title) throws Exception {
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
                while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
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
        main.removeCallbacksAndMessages(null);
    }

    private static final class OperationCancelled extends IOException {}
}
