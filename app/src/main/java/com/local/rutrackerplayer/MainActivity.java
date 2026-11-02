package com.local.rutrackerplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.rutracker.tv.source.RutrackerCallback;
import app.rutracker.tv.source.RutrackerWebClient;
import app.rutracker.tv.source.SearchResult;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity implements RutrackerCallback {
    private static final String BASE_URL = "https://rutracker.org/forum/";
    private static final int BG = Color.rgb(11, 16, 32);
    private static final int PANEL = Color.rgb(24, 35, 58);
    private static final int FOCUS = Color.rgb(30, 145, 165);
    private static final int TEXT = Color.rgb(240, 245, 252);

    private EditText query;
    private TextView status;
    private Button loginButton;
    private ScrollView resultsScroll;
    private LinearLayout results;
    private WebView webView;
    private RutrackerWebClient rutracker;
    private final TorrServerClient torrServer = new TorrServerClient();
    private final List<SearchResult> currentResults = new ArrayList<>();
    private final Map<Long, SearchResult> favorites = new LinkedHashMap<>();
    private PendingPlayback pendingPlayback;
    private String pendingPreparedHash = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        loadFavorites();
        rutracker = new RutrackerWebClient(this, webView, BASE_URL, this);
        showEmpty("Введите название фильма и нажмите «Поиск»");
        status.setText("Проверяю TorrServer…");
        torrServer.check((available, description) -> status.setText(available
                ? "TorrServer готов: " + description
                : "TorrServer не запущен — воспроизведение пока недоступно"));
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(18), dp(28), dp(18));
        root.setBackgroundColor(BG);

        TextView heading = new TextView(this);
        heading.setText("RuTracker Player  •  версия " + BuildConfig.VERSION_NAME);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(27);
        heading.setPadding(0, 0, 0, dp(12));
        root.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        query = new EditText(this);
        query.setHint("Название фильма");
        query.setSingleLine(true);
        query.setTextColor(Color.WHITE);
        query.setHintTextColor(Color.rgb(170, 180, 195));
        query.setTextSize(19);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setBackgroundColor(PANEL);
        query.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams queryParams = new LinearLayout.LayoutParams(0, dp(54), 1f);
        queryParams.setMarginEnd(dp(10));
        toolbar.addView(query, queryParams);

        Button search = button("Поиск");
        toolbar.addView(search, buttonParams());
        Button favoritesButton = button("Избранное");
        toolbar.addView(favoritesButton, buttonParams());
        Button historyButton = button("История");
        toolbar.addView(historyButton, buttonParams());
        loginButton = button("Войти");
        toolbar.addView(loginButton, buttonParams());
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextColor(Color.rgb(155, 220, 230));
        status.setTextSize(16);
        status.setPadding(0, dp(10), 0, dp(10));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout content = new FrameLayout(this);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        resultsScroll = new ScrollView(this);
        resultsScroll.setFillViewport(true);
        resultsScroll.addView(results, new ScrollView.LayoutParams(-1, -2));
        content.addView(resultsScroll, new FrameLayout.LayoutParams(-1, -1));
        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setVisibility(View.GONE);
        content.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("OK — открыть раздачу  •  ★ — добавить в избранное  •  пароль остаётся в защищённом хранилище WebView");
        hint.setTextColor(Color.rgb(145, 155, 170));
        hint.setTextSize(14);
        hint.setPadding(0, dp(8), 0, 0);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        search.setOnClickListener(v -> doSearch());
        query.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { doSearch(); return true; }
            return false;
        });
        favoritesButton.setOnClickListener(v -> {
            cancelPendingPlayback();
            currentResults.clear();
            currentResults.addAll(favorites.values());
            showResults(currentResults, "Избранное");
        });
        historyButton.setOnClickListener(v -> {
            cancelPendingPlayback();
            showHistory();
        });
        loginButton.setOnClickListener(v -> {
            cancelPendingPlayback();
            if (webView.getVisibility() == View.VISIBLE) showResults(currentResults, "Результаты");
            else { showWeb(); status.setText("Введите логин и пароль на странице RuTracker"); rutracker.openLogin(); }
        });
    }

    private void doSearch() {
        String text = query.getText().toString().trim();
        if (text.isEmpty()) { status.setText("Введите название фильма"); query.requestFocus(); return; }
        cancelPendingPlayback();
        // Keep WebView visible while loading. RuTracker may show a Cloudflare
        // browser check that needs to be rendered before the search page opens.
        // The results callback switches back to the native TV list.
        showWeb();
        status.setText("Ищу «" + text + "»…");
        showEmpty("Загрузка результатов…");
        rutracker.search(text);
    }

    private void showResults(List<SearchResult> items, String label) {
        hideWeb();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
        query.clearFocus();
        results.removeAllViews();
        TextView title = new TextView(this);
        title.setText(label + "  •  " + items.size());
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setPadding(dp(4), dp(4), dp(4), dp(10));
        results.addView(title);
        TextView legend = new TextView(this);
        legend.setText("●  10+ — ДОСТАТОЧНО     ▲  3–9 — МАЛО     ✕  0–2 — НЕ ЗАПУСКАТЬ");
        legend.setTextColor(Color.rgb(185, 195, 210));
        legend.setTextSize(16);
        legend.setPadding(dp(4), 0, dp(4), dp(10));
        results.addView(legend);
        if (items.isEmpty()) {
            showMessage("Здесь пока пусто");
            return;
        }
        Button first = null;
        for (SearchResult item : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Button open = button(format(item));
            open.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            open.setTextSize(16);
            open.setMinHeight(dp(78));
            open.setContentDescription(seedLabel(item) + ". " + item.title);
            open.setOnClickListener(v -> play(item));
            LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(0, -2, 1f);
            openParams.setMargins(0, 0, dp(8), dp(8));
            row.addView(open, openParams);
            Button favorite = button(favorites.containsKey(item.topicId) ? "★" : "☆");
            favorite.setContentDescription("Добавить в избранное");
            favorite.setOnClickListener(v -> {
                if (favorites.remove(item.topicId) == null) favorites.put(item.topicId, item);
                saveFavorites();
                favorite.setText(favorites.containsKey(item.topicId) ? "★" : "☆");
                status.setText(favorites.containsKey(item.topicId) ? "Добавлено в избранное" : "Удалено из избранного");
            });
            LinearLayout.LayoutParams faveParams = new LinearLayout.LayoutParams(dp(70), dp(78));
            faveParams.setMargins(0, 0, 0, dp(8));
            row.addView(favorite, faveParams);
            results.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (first == null) first = open;
        }
        if (first != null) first.requestFocus();
    }

    private void play(SearchResult item) {
        int seeds = item.seedCount();
        if (seeds >= 0 && seeds < 3) {
            new AlertDialog.Builder(this)
                    .setTitle("Недостаточно сидов: " + seeds)
                    .setMessage("Эта раздача, скорее всего, не запустится. Лучше выберите зелёную раздачу с 10+ сидами.")
                    .setNegativeButton("Выбрать другую", null)
                    .setPositiveButton("Всё равно запустить", (dialog, which) -> startPlay(item))
                    .show();
            return;
        }
        startPlay(item);
    }

    private void startPlay(SearchResult item) {
        startPlay(item, "", 0);
    }

    private void startPlay(SearchResult item, String preferredFilePath, long startPositionMs) {
        cancelPendingPlayback();
        pendingPlayback = new PendingPlayback(item, preferredFilePath, startPositionMs);
        hideWeb();
        status.setText("Получаю авторизованный .torrent-файл…");
        rutracker.openTopic(item.topicId);
    }

    private void cancelPendingPlayback() {
        pendingPlayback = null;
        if (!pendingPreparedHash.isEmpty()) {
            TorrServerClient.dropTorrentAsync(pendingPreparedHash);
            pendingPreparedHash = "";
        }
        torrServer.cancelPendingPreparation();
        if (rutracker != null) rutracker.cancelPending();
    }

    private CharSequence format(SearchResult item) {
        String badge = seedLabel(item);
        SpannableStringBuilder value = new SpannableStringBuilder(badge);
        value.setSpan(new ForegroundColorSpan(seedColor(item.seedCount())), 0, badge.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        value.setSpan(new StyleSpan(Typeface.BOLD), 0, badge.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        value.append("\n").append(item.title);
        if (!item.size.isEmpty()) value.append("\n").append(item.size);
        return value;
    }

    private static String seedLabel(SearchResult item) {
        int count = item.seedCount();
        if (count >= 10) return "●  СИДОВ: " + count + " — ДОСТАТОЧНО";
        if (count >= 3) return "▲  СИДОВ: " + count + " — МАЛО";
        if (count >= 0) return "✕  СИДОВ: " + count + " — НЕ ЗАПУСКАТЬ";
        return "?  СИДЫ: НЕТ ДАННЫХ";
    }

    private static int seedColor(int count) {
        if (count >= 10) return Color.rgb(85, 220, 135);
        if (count >= 3) return Color.rgb(255, 190, 70);
        if (count >= 0) return Color.rgb(255, 105, 105);
        return Color.rgb(185, 195, 210);
    }

    private void showEmpty(String text) {
        results.removeAllViews();
        showMessage(text);
    }

    private void showMessage(String text) {
        TextView message = new TextView(this);
        message.setText(text);
        message.setTextColor(TEXT);
        message.setTextSize(20);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(20), dp(70), dp(20), dp(20));
        results.addView(message, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showWeb() {
        resultsScroll.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        loginButton.setText("К списку");
        webView.requestFocus();
    }

    private void hideWeb() {
        webView.setVisibility(View.GONE);
        resultsScroll.setVisibility(View.VISIBLE);
        loginButton.setText("Войти");
    }

    private Button button(CharSequence text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(TEXT);
        button.setTextSize(17);
        button.setFocusable(true);
        button.setBackgroundColor(PANEL);
        button.setPadding(dp(15), 0, dp(15), 0);
        button.setOnFocusChangeListener((v, focused) -> {
            v.setBackgroundColor(focused ? FOCUS : PANEL);
            v.setScaleX(focused ? 1.025f : 1f);
            v.setScaleY(focused ? 1.025f : 1f);
        });
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(54));
        params.setMarginEnd(dp(8));
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onPageState(String url, boolean loading) {
        if (loading && webView.getVisibility() != View.VISIBLE) status.setText("Загружаю страницу RuTracker…");
    }

    @Override public void onLoginPageShown() {
        if (webView.getVisibility() == View.VISIBLE) status.setText("Авторизуйтесь и затем нажмите «К списку»");
    }

    @Override public void onSearchResults(List<SearchResult> found) {
        currentResults.clear();
        currentResults.addAll(found);
        currentResults.sort((left, right) -> Integer.compare(right.seedCount(), left.seedCount()));
        status.setText(found.isEmpty() ? "Ничего не найдено или требуется вход"
                : "Найдено: " + found.size() + " • сначала раздачи с большим числом сидов");
        showResults(currentResults, "Результаты поиска");
    }

    @Override public void onTopicMagnet(long topicId, String title, String magnetUri) {
        PendingPlayback request = pendingPlayback;
        if (request == null || request.item.topicId != topicId) return;
        torrServer.prepareMagnet(magnetUri, request.item.title, playerCallback(request));
    }

    @Override public void onTorrentFile(long topicId, File torrentFile) {
        PendingPlayback request = pendingPlayback;
        if (request == null || request.item.topicId != topicId) {
            // A slow response for a previously selected topic must never be
            // prepared or labelled as the user's newer selection.
            //noinspection ResultOfMethodCallIgnored
            torrentFile.delete();
            return;
        }
        torrServer.prepareTorrent(torrentFile, request.item.title, playerCallback(request));
    }

    private TorrServerClient.Callback playerCallback(PendingPlayback request) {
        return new TorrServerClient.Callback() {
            @Override public void onProgress(String message) {
                if (pendingPlayback == request) status.setText(message);
            }
            @Override public void onFilesReady(String hash, List<TorrentFile> files) {
                if (pendingPlayback != request) {
                    TorrServerClient.dropTorrentAsync(hash);
                    return;
                }
                TorrentFile preferred = findPreferred(files, request.preferredFilePath);
                if (preferred != null) {
                    launchPlayer(request, hash, preferred);
                } else if (files.size() == 1) {
                    launchPlayer(request, hash, files.get(0));
                } else {
                    showEpisodeChooser(request, hash, files);
                }
            }
            @Override public void onError(String message, Throwable cause) {
                if (pendingPlayback == request) {
                    pendingPlayback = null;
                    status.setText(message);
                }
            }
        };
    }

    private static TorrentFile findPreferred(List<TorrentFile> files, String preferredPath) {
        if (preferredPath == null || preferredPath.isEmpty()) return null;
        for (TorrentFile file : files) {
            if (preferredPath.equals(file.path)) return file;
        }
        return null;
    }

    private void showEpisodeChooser(PendingPlayback request, String hash,
            List<TorrentFile> files) {
        pendingPreparedHash = hash;
        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            TorrentFile file = files.get(i);
            labels[i] = file.path + "\n" + formatBytes(file.lengthBytes);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Выберите серию или видеофайл")
                .setItems(labels, (ignored, which) -> launchPlayer(request, hash, files.get(which)))
                .setNegativeButton("Отмена", null)
                .setOnCancelListener(ignored -> cancelPreparedTorrent(request, hash))
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                cancelPreparedTorrent(request, hash);
                dialog.dismiss();
            });
            if (dialog.getListView() != null) {
                dialog.getListView().setSelection(0);
                dialog.getListView().requestFocus();
            }
        });
        dialog.show();
    }

    private void cancelPreparedTorrent(PendingPlayback request, String hash) {
        if (pendingPlayback == request) pendingPlayback = null;
        if (hash.equals(pendingPreparedHash)) pendingPreparedHash = "";
        TorrServerClient.dropTorrentAsync(hash);
        status.setText("Выбор серии отменён");
    }

    private void launchPlayer(PendingPlayback request, String hash, TorrentFile file) {
        if (pendingPlayback != request) {
            TorrServerClient.dropTorrentAsync(hash);
            return;
        }
        pendingPlayback = null;
        if (hash.equals(pendingPreparedHash)) pendingPreparedHash = "";
        SearchResult item = request.item;
        status.setText("Запускаю: " + file.fileName());
        Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_URL, TorrServerClient.buildStreamUrl(hash, file));
        intent.putExtra(PlayerActivity.EXTRA_TITLE, item.title);
        intent.putExtra(PlayerActivity.EXTRA_FILE_SIZE_BYTES, file.lengthBytes);
        intent.putExtra(PlayerActivity.EXTRA_FILE_ID, file.id);
        intent.putExtra(PlayerActivity.EXTRA_FILE_PATH, file.path);
        intent.putExtra(PlayerActivity.EXTRA_TOPIC_ID, item.topicId);
        intent.putExtra(PlayerActivity.EXTRA_TOPIC_URL, item.topicUrl);
        intent.putExtra(PlayerActivity.EXTRA_FORUM, item.forum);
        intent.putExtra(PlayerActivity.EXTRA_RESULT_SIZE, item.size);
        intent.putExtra(PlayerActivity.EXTRA_SEEDS, item.seeds);
        intent.putExtra(PlayerActivity.EXTRA_START_POSITION_MS, request.startPositionMs);
        startActivity(intent);
    }

    private void showHistory() {
        hideWeb();
        List<PlaybackHistory.Entry> history = PlaybackHistory.list(this);
        results.removeAllViews();
        TextView title = new TextView(this);
        title.setText("История  •  " + history.size());
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setPadding(dp(4), dp(4), dp(4), dp(10));
        results.addView(title);
        if (history.isEmpty()) {
            showMessage("История пока пуста. Здесь появятся видео, просмотренные не менее 15 секунд.");
            return;
        }
        Button first = null;
        for (PlaybackHistory.Entry entry : history) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Button open = button(formatHistory(entry));
            open.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            open.setTextSize(15);
            open.setMinHeight(dp(88));
            open.setOnClickListener(v -> playHistory(entry));
            LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(0, -2, 1f);
            openParams.setMargins(0, 0, dp(8), dp(8));
            row.addView(open, openParams);
            Button remove = button("×");
            remove.setContentDescription("Удалить из истории");
            remove.setOnClickListener(v -> {
                PlaybackHistory.remove(this, entry.key);
                if (!entry.progressKey.isEmpty()) {
                    getSharedPreferences("playback_progress", MODE_PRIVATE).edit()
                            .remove(entry.progressKey + ".position")
                            .remove(entry.progressKey + ".duration")
                            .apply();
                }
                showHistory();
            });
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(70), dp(88));
            removeParams.setMargins(0, 0, 0, dp(8));
            row.addView(remove, removeParams);
            results.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (first == null) first = open;
        }
        if (first != null) first.requestFocus();
    }

    private void playHistory(PlaybackHistory.Entry entry) {
        if (entry.topicId <= 0) {
            status.setText("Эту запись нельзя открыть повторно: нет ссылки на тему RuTracker");
            return;
        }
        SearchResult item = new SearchResult(entry.topicId, entry.title, entry.topicUrl,
                entry.forum, entry.resultSize, entry.seeds);
        long position = entry.completed ? 0 : entry.positionMs;
        startPlay(item, entry.filePath, position);
    }

    private static String formatHistory(PlaybackHistory.Entry entry) {
        String file = entry.filePath;
        int slash = file.lastIndexOf('/');
        if (slash >= 0) file = file.substring(slash + 1);
        String progress = entry.completed ? "Просмотрено"
                : "Продолжить: " + formatTime(entry.positionMs)
                        + (entry.durationMs > 0 ? " / " + formatTime(entry.durationMs) : "");
        String date = entry.updatedAtMs > 0
                ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(entry.updatedAtMs)) : "";
        return progress + (date.isEmpty() ? "" : "  •  " + date)
                + "\n" + entry.title + (file.isEmpty() ? "" : "\n" + file);
    }

    private static String formatTime(long timeMs) {
        long totalSeconds = Math.max(0, timeMs / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(java.util.Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.getDefault(), "%.2f ГБ", bytes / (1024d * 1024 * 1024));
        }
        return String.format(java.util.Locale.getDefault(), "%.0f МБ", bytes / (1024d * 1024));
    }

    @Override public void onAuthenticationRequired() {
        status.setText("Для доступа к раздаче требуется вход");
        showWeb();
        rutracker.openLogin();
    }

    @Override public void onError(String message, Throwable cause) { status.setText(message); }

    private void loadFavorites() {
        favorites.clear();
        SharedPreferences prefs = getSharedPreferences("favorites", MODE_PRIVATE);
        String raw = prefs.getString("items", "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                SearchResult item = new SearchResult(o.optLong("id"), o.optString("title"), o.optString("url"),
                        o.optString("forum"), o.optString("size"), o.optString("seeds"));
                if (item.topicId > 0) favorites.put(item.topicId, item);
            }
        } catch (Throwable ignored) {}
    }

    private void saveFavorites() {
        JSONArray array = new JSONArray();
        for (SearchResult item : favorites.values()) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", item.topicId); o.put("title", item.title); o.put("url", item.topicUrl);
                o.put("forum", item.forum); o.put("size", item.size); o.put("seeds", item.seeds);
                array.put(o);
            } catch (Throwable ignored) {}
        }
        getSharedPreferences("favorites", MODE_PRIVATE).edit().putString("items", array.toString()).apply();
    }

    private static final class PendingPlayback {
        final SearchResult item;
        final String preferredFilePath;
        final long startPositionMs;

        PendingPlayback(SearchResult item, String preferredFilePath, long startPositionMs) {
            this.item = item;
            this.preferredFilePath = preferredFilePath == null ? "" : preferredFilePath;
            this.startPositionMs = Math.max(0, startPositionMs);
        }
    }

    @Override public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE) {
            cancelPendingPlayback();
            showResults(currentResults, "Результаты");
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        cancelPendingPlayback();
        if (rutracker != null) rutracker.destroy();
        torrServer.destroy();
        webView.destroy();
        super.onDestroy();
    }
}
