package app.rutracker.tv.source;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class TorrentFetcher {
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int SNIFF_BYTES = 16 * 1024;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/120 Safari/537.36";
    private final Context appContext;
    private final RutrackerUrlPolicy policy;
    private final RutrackerCallback callback;
    private final String userAgent;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Long> inFlightTopics = ConcurrentHashMap.newKeySet();

    TorrentFetcher(Context context, RutrackerUrlPolicy policy, RutrackerCallback callback, String userAgent) {
        appContext = context.getApplicationContext();
        this.policy = policy;
        this.callback = callback;
        this.userAgent = userAgent == null || userAgent.trim().isEmpty() ? USER_AGENT : userAgent;
    }

    void fetch(String initialUrl) { fetch(initialUrl, null); }

    void fetch(String initialUrl, String refererUrl) {
        final long topicId = policy.topicId(initialUrl);
        if (!policy.isTorrentDownloadUrl(initialUrl) || topicId <= 0) {
            callback.onError("Недопустимая ссылка на torrent-файл", null);
            return;
        }
        if (!inFlightTopics.add(topicId)) return;
        CookieManager.getInstance().flush();
        executor.execute(() -> {
            try {
                File result = fetchBlocking(initialUrl, topicId, refererUrl);
                main.post(() -> callback.onTorrentFile(topicId, result));
            } catch (AuthenticationRequired e) {
                main.post(callback::onAuthenticationRequired);
            } catch (Throwable e) {
                main.post(() -> callback.onError("Не удалось получить torrent-файл", e));
            } finally {
                inFlightTopics.remove(topicId);
            }
        });
    }

    private File fetchBlocking(String initialUrl, long topicId, String refererUrl) throws Exception {
        String current = initialUrl;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!policy.isAllowedHttps(current)) throw new IOException("Redirect left allowed host");
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(45_000);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "application/x-bittorrent,*/*;q=0.8");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (policy.isAllowedHttps(refererUrl)) connection.setRequestProperty("Referer", refererUrl);
            String cookie = CookieManager.getInstance().getCookie(current);
            connection.setRequestProperty("Cookie", withDownloadCookie(cookie, topicId));
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Redirect without Location");
                current = new URI(current).resolve(location).toString();
                continue;
            }
            if (status == 401) {
                connection.disconnect();
                throw new AuthenticationRequired();
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IOException("HTTP " + status);
            }
            File dir = new File(appContext.getCacheDir(), "torrents");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create cache");
            File output = new File(dir, topicId + ".torrent");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream file = new FileOutputStream(output, false)) {
                validateTorrentResponse(input);
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_BYTES) throw new IOException("Torrent too large");
                    file.write(buffer, 0, read);
                }
            } catch (Throwable e) {
                //noinspection ResultOfMethodCallIgnored
                output.delete();
                throw e;
            } finally {
                connection.disconnect();
            }
            return output;
        }
        throw new IOException("Too many redirects");
    }

    private static String withDownloadCookie(String existing, long topicId) {
        StringBuilder value = new StringBuilder();
        if (existing != null && !existing.trim().isEmpty()) {
            for (String part : existing.split(";")) {
                String cookie = part.trim();
                if (cookie.isEmpty() || cookie.startsWith("bb_dl=")) continue;
                if (value.length() > 0) value.append("; ");
                value.append(cookie);
            }
        }
        if (value.length() > 0) value.append("; ");
        return value.append("bb_dl=").append(topicId).toString();
    }

    private static void validateTorrentResponse(InputStream input) throws IOException {
        if (!input.markSupported()) throw new IOException("Cannot inspect torrent response");
        input.mark(SNIFF_BYTES + 1);
        byte[] prefix = new byte[SNIFF_BYTES];
        int length = 0;
        while (length < prefix.length) {
            int read = input.read(prefix, length, prefix.length - length);
            if (read < 0) break;
            length += read;
        }
        input.reset();

        int first = 0;
        while (first < length && Character.isWhitespace((char) (prefix[first] & 0xff))) first++;
        if (first < length && prefix[first] == 'd') return;

        String text = new String(prefix, 0, length, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (text.contains("login_username") || text.contains("login_password")
                || text.contains("action=\"login.php") || text.contains("action='login.php")) {
            throw new AuthenticationRequired();
        }
        throw new IOException("Server did not return a torrent file");
    }

    void destroy() { executor.shutdownNow(); }
    private static final class AuthenticationRequired extends IOException {}
}
