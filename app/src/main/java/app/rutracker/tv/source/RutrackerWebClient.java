package app.rutracker.tv.source;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.SafeBrowsingResponse;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RutrackerWebClient {
    private static final int TOPIC_EXTRACT_MAX_ATTEMPTS = 5;
    private static final long TOPIC_EXTRACT_RETRY_MS = 600L;

    private final WebView webView;
    private final String forumBase;
    private final RutrackerUrlPolicy policy;
    private final RutrackerCallback callback;
    private final TorrentFetcher torrentFetcher;
    private long pendingTopicId = -1;
    private long topicRequestGeneration;

    @SuppressLint("SetJavaScriptEnabled")
    public RutrackerWebClient(Activity activity, WebView webView, String forumBase, RutrackerCallback callback) {
        this.webView = webView;
        this.forumBase = forumBase.endsWith("/") ? forumBase : forumBase + "/";
        this.policy = new RutrackerUrlPolicy(this.forumBase);
        this.callback = callback;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        settings.setTextZoom(120);
        this.torrentFetcher = new TorrentFetcher(activity, policy, callback, settings.getUserAgentString());
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setWebViewClient(new Client());
        webView.setDownloadListener((url, userAgent, disposition, mime, length) -> {
            if (policy.isTorrentDownloadUrl(url)) torrentFetcher.fetch(url, webView.getUrl());
            else callback.onError("Загрузка с внешнего адреса заблокирована", null);
        });
    }

    public void openLogin() {
        cancelPendingTopic();
        webView.loadUrl(forumBase + "login.php");
        webView.requestFocus();
    }

    public void search(String query) {
        String cleaned = query == null ? "" : query.trim();
        if (cleaned.isEmpty()) {
            callback.onError("Введите название фильма", null);
            return;
        }
        try {
            cancelPendingTopic();
            webView.loadUrl(forumBase + "tracker.php?nm=" + URLEncoder.encode(cleaned, "UTF-8"));
        } catch (Exception e) {
            callback.onError("Не удалось сформировать поисковый запрос", e);
        }
    }

    public void openTopic(long topicId) {
        if (topicId <= 0) {
            callback.onError("Некорректный идентификатор раздачи", null);
            return;
        }
        topicRequestGeneration++;
        pendingTopicId = topicId;
        webView.loadUrl(forumBase + "viewtopic.php?t=" + topicId);
    }

    public void destroy() {
        cancelPendingTopic();
        torrentFetcher.destroy();
        webView.stopLoading();
        webView.setDownloadListener(null);
        webView.setWebViewClient(null);
    }

    private final class Client extends WebViewClient {
        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            callback.onPageState(url, true);
        }

        @Override public void onPageFinished(WebView view, String url) {
            callback.onPageState(url, false);
            if (policy.isLoginUrl(url)) callback.onLoginPageShown();
            if (policy.isSearchUrl(url)) extractSearchResults();
            Uri parsed = Uri.parse(url);
            String path = parsed.getPath();
            if (policy.isAllowedHttps(url) && path != null && path.endsWith("/viewtopic.php")) {
                extractTopicSource(url);
            }
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl().toString());
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(url);
        }

        private boolean handleNavigation(String url) {
            if (url != null && url.startsWith("magnet:?")) {
                cancelPendingTopic();
                callback.onTopicMagnet(-1, "", url);
                return true;
            }
            if (policy.isTorrentDownloadUrl(url)) {
                String referer = webView.getUrl();
                cancelPendingTopic();
                torrentFetcher.fetch(url, referer);
                return true;
            }
            if (!policy.isAllowedHttps(url)) {
                callback.onError("Внешняя ссылка заблокирована", null);
                return true;
            }
            return false;
        }

        @Override public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType, SafeBrowsingResponse response) {
            response.backToSafety(true);
            callback.onError("WebView заблокировал небезопасную страницу", null);
        }
    }

    private void extractSearchResults() {
        String script = "(function(){const seen=new Set(),out=[];"
                // Real tracker rows use the tLink class. The broader viewtopic
                // selector also matches announcements and navigation links.
                + "const links=document.querySelectorAll('a.tLink');"
                + "for(const a of links){try{const u=new URL(a.href,location.href),id=u.searchParams.get('t');"
                + "if(!id||seen.has(id))continue;const r=a.closest('tr');if(!r)continue;"
                + "const txt=s=>{const e=r&&r.querySelector(s);return e?e.textContent.trim():''};"
                + "const size=txt('.tor-size,[data-ts_text]'),seeds=txt('.seedmed,.seed,b.seedmed');"
                + "if(!size&&seeds==='')continue;seen.add(id);"
                + "out.push({id:Number(id),title:a.textContent.trim(),url:u.href,forum:txt('.f-name,.forumlink,.gen'),"
                + "size:size,seeds:seeds});"
                + "if(out.length>=100)break;}catch(e){}}return JSON.stringify(out);})()";
        webView.evaluateJavascript(script, value -> {
            try {
                Object decoded = new JSONTokener(value).nextValue();
                if (!(decoded instanceof String)) return;
                JSONArray array = new JSONArray((String) decoded);
                List<SearchResult> results = new ArrayList<>();
                Set<Long> seen = new HashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    long id = item.optLong("id", -1);
                    if (id <= 0 || !seen.add(id)) continue;
                    results.add(new SearchResult(id, item.optString("title"), item.optString("url"),
                            item.optString("forum"), item.optString("size"), item.optString("seeds")));
                }
                callback.onSearchResults(results);
            } catch (Throwable e) {
                callback.onError("Не удалось разобрать результаты поиска", e);
            }
        });
    }

    private void extractTopicSource(String pageUrl) {
        final long topicId = policy.topicId(pageUrl);
        if (topicId <= 0 || topicId != pendingTopicId) return;
        extractTopicSource(pageUrl, topicId, topicRequestGeneration, 0);
    }

    private void extractTopicSource(String pageUrl, long topicId, long generation, int attempt) {
        if (!isPendingTopic(topicId, generation)) return;
        String script = "(function(){let download='',magnet='';"
                + "for(const a of document.querySelectorAll('a[href]')){"
                + "const raw=(a.getAttribute('href')||'').trim(),href=(a.href||raw).trim();"
                + "if(!magnet&&(raw.startsWith('magnet:?')||href.startsWith('magnet:?')))magnet=href||raw;"
                + "if(!download){try{const u=new URL(href||raw,location.href);"
                + "if(u.pathname.toLowerCase().endsWith('/dl.php')&&u.searchParams.get('t'))download=u.href;"
                + "}catch(e){}}if(download&&magnet)break;}"
                + "const signedIn=!!document.querySelector('#logged-in-username');"
                + "const user=document.querySelector('input[name=\"login_username\"]');"
                + "const pass=document.querySelector('input[name=\"login_password\"]');"
                + "return JSON.stringify({title:document.title||'',magnet:magnet,download:download,"
                + "login:!signedIn&&!!user&&!!pass,signedIn:signedIn});})()";
        webView.evaluateJavascript(script, value -> {
            try {
                if (!isPendingTopic(topicId, generation)) return;
                Object decoded = new JSONTokener(value).nextValue();
                if (!(decoded instanceof String) || ((String) decoded).isEmpty()) {
                    retryTopicExtraction(pageUrl, topicId, generation, attempt);
                    return;
                }
                JSONObject item = new JSONObject((String) decoded);
                String download = item.optString("download");
                if (policy.isTorrentDownloadUrl(download)) {
                    cancelPendingTopic();
                    torrentFetcher.fetch(download, pageUrl);
                    return;
                }
                String magnet = item.optString("magnet");
                if (magnet.startsWith("magnet:?")) {
                    cancelPendingTopic();
                    callback.onTopicMagnet(topicId, item.optString("title"), magnet);
                    return;
                }
                if (item.optBoolean("login")) {
                    cancelPendingTopic();
                    callback.onAuthenticationRequired();
                    return;
                }
                retryTopicExtraction(pageUrl, topicId, generation, attempt);
            } catch (Throwable e) {
                retryTopicExtraction(pageUrl, topicId, generation, attempt);
            }
        });
    }

    private void retryTopicExtraction(String pageUrl, long topicId, long generation, int attempt) {
        if (!isPendingTopic(topicId, generation)) return;
        if (attempt + 1 < TOPIC_EXTRACT_MAX_ATTEMPTS) {
            webView.postDelayed(() -> extractTopicSource(pageUrl, topicId, generation, attempt + 1),
                    TOPIC_EXTRACT_RETRY_MS);
            return;
        }
        // The authenticated endpoint remains usable when a theme or delayed
        // script has not exposed the download link in the rendered DOM.
        String directDownload = forumBase + "dl.php?t=" + topicId;
        cancelPendingTopic();
        torrentFetcher.fetch(directDownload, pageUrl);
    }

    private boolean isPendingTopic(long topicId, long generation) {
        return topicId > 0 && topicId == pendingTopicId && generation == topicRequestGeneration;
    }

    private void cancelPendingTopic() {
        pendingTopicId = -1;
        topicRequestGeneration++;
    }
}
