package app.rutracker.tv.source;

import java.io.File;
import java.util.List;

public interface RutrackerCallback {
    default void onPageState(String url, boolean loading) {}
    default void onLoginPageShown() {}
    default void onSearchResults(List<SearchResult> results) {}
    default void onTopicMagnet(long topicId, String title, String magnetUri) {}
    default void onTorrentFile(long topicId, File torrentFile) {}
    default void onAuthenticationRequired() {}
    default void onError(String userMessage, Throwable cause) {}
}
