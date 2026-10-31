package app.rutracker.tv.source;

import android.net.Uri;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class RutrackerUrlPolicy {
    private final String host;

    public RutrackerUrlPolicy(String baseUrl) {
        Uri parsed = Uri.parse(baseUrl);
        String parsedHost = parsed.getHost();
        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsedHost == null) {
            throw new IllegalArgumentException("RuTracker base URL must be HTTPS");
        }
        host = parsedHost.toLowerCase(Locale.ROOT);
    }

    public boolean isAllowedHttps(String rawUrl) {
        if (rawUrl == null) return false;
        try {
            URI uri = new URI(rawUrl);
            String candidate = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || candidate == null) return false;
            candidate = candidate.toLowerCase(Locale.ROOT);
            return candidate.equals(host) || stripWww(candidate).equals(stripWww(host));
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    public boolean isLoginUrl(String rawUrl) {
        return isPath(rawUrl, "/login.php");
    }

    public boolean isSearchUrl(String rawUrl) {
        return isPath(rawUrl, "/tracker.php");
    }

    public boolean isTorrentDownloadUrl(String rawUrl) {
        return isPath(rawUrl, "/dl.php") && topicId(rawUrl) > 0;
    }

    public long topicId(String rawUrl) {
        if (rawUrl == null) return -1;
        String raw = Uri.parse(rawUrl).getQueryParameter("t");
        if (raw == null) return -1;
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private boolean isPath(String rawUrl, String suffix) {
        if (!isAllowedHttps(rawUrl)) return false;
        String path = Uri.parse(rawUrl).getPath();
        return path != null && path.endsWith(suffix);
    }

    private static String stripWww(String value) {
        return value.startsWith("www.") ? value.substring(4) : value;
    }
}
