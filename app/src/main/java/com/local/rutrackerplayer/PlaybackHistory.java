package com.local.rutrackerplayer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Persistent metadata for started and completed videos. */
final class PlaybackHistory {
    private static final String PREFS = "playback_history";
    private static final String KEY_ITEMS = "items_v1";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_ITEMS = 100;

    private PlaybackHistory() {}

    static synchronized List<Entry> list(Context context) {
        List<Entry> items = read(context);
        sortNewestFirst(items);
        return Collections.unmodifiableList(items);
    }

    static synchronized void upsert(Context context, Entry item) {
        if (context == null || item == null || item.key.isEmpty()) return;
        Entry normalized = item.updatedAtMs > 0 ? item : item.withUpdatedAt(System.currentTimeMillis());
        List<Entry> items = read(context);
        for (int i = items.size() - 1; i >= 0; i--) {
            if (normalized.key.equals(items.get(i).key)) items.remove(i);
        }
        items.add(normalized);
        sortNewestFirst(items);
        if (items.size() > MAX_ITEMS) {
            items = new ArrayList<>(items.subList(0, MAX_ITEMS));
        }
        write(context, items);
    }

    static synchronized void remove(Context context, String key) {
        if (context == null || key == null || key.isEmpty()) return;
        List<Entry> items = read(context);
        boolean changed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (key.equals(items.get(i).key)) {
                items.remove(i);
                changed = true;
            }
        }
        if (changed) write(context, items);
    }

    static String makeKey(long topicId, String filePath, String torrentHash, int fileId) {
        String path = safe(filePath);
        if (topicId > 0) return topicId + "|" + path;
        return safe(torrentHash) + "|" + fileId + "|" + path;
    }

    private static List<Entry> read(Context context) {
        ArrayList<Entry> items = new ArrayList<>();
        if (context == null) return items;
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = preferences.getString(KEY_ITEMS, "");
        if (raw == null || raw.trim().isEmpty()) return items;
        try {
            JSONArray array;
            String trimmed = raw.trim();
            if (trimmed.startsWith("[")) {
                // Accept an early array-only representation if one ever reached a device.
                array = new JSONArray(trimmed);
            } else {
                array = new JSONObject(trimmed).optJSONArray("items");
            }
            if (array == null) return items;
            for (int i = 0; i < array.length(); i++) {
                try {
                    Entry item = Entry.fromJson(array.getJSONObject(i));
                    if (!item.key.isEmpty()) items.add(item);
                } catch (Throwable ignored) {
                    // A damaged record must not hide the rest of the history.
                }
            }
        } catch (Throwable ignored) {
            // Treat a damaged preference as empty; the next upsert repairs it.
        }
        return items;
    }

    private static void write(Context context, List<Entry> items) {
        if (context == null) return;
        JSONArray array = new JSONArray();
        int count = Math.min(MAX_ITEMS, items.size());
        for (int i = 0; i < count; i++) {
            try {
                array.put(items.get(i).toJson());
            } catch (Throwable ignored) {
                // Skip only the record that could not be serialized.
            }
        }
        JSONObject root = new JSONObject();
        try {
            root.put("version", SCHEMA_VERSION);
            root.put("items", array);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_ITEMS, root.toString()).apply();
        } catch (Throwable ignored) {
            // SharedPreferences remains unchanged if serialization fails.
        }
    }

    private static void sortNewestFirst(List<Entry> items) {
        items.sort(Comparator.comparingLong((Entry value) -> value.updatedAtMs).reversed());
    }

    private static String safe(String value) { return value == null ? "" : value; }

    static final class Entry {
        final String key;
        final String progressKey;
        final long topicId;
        final String title;
        final String topicUrl;
        final String forum;
        final String resultSize;
        final String seeds;
        final String torrentHash;
        final int fileId;
        final String filePath;
        final long fileSizeBytes;
        final long positionMs;
        final long durationMs;
        final long updatedAtMs;
        final boolean completed;

        Entry(String key, String progressKey, long topicId, String title, String topicUrl,
                String forum, String resultSize, String seeds, String torrentHash, int fileId,
                String filePath, long fileSizeBytes, long positionMs, long durationMs,
                long updatedAtMs, boolean completed) {
            this.key = safe(key);
            this.progressKey = safe(progressKey);
            this.topicId = Math.max(0, topicId);
            this.title = safe(title);
            this.topicUrl = safe(topicUrl);
            this.forum = safe(forum);
            this.resultSize = safe(resultSize);
            this.seeds = safe(seeds);
            this.torrentHash = safe(torrentHash);
            this.fileId = fileId;
            this.filePath = safe(filePath);
            this.fileSizeBytes = Math.max(0, fileSizeBytes);
            this.positionMs = Math.max(0, positionMs);
            this.durationMs = Math.max(0, durationMs);
            this.updatedAtMs = Math.max(0, updatedAtMs);
            this.completed = completed;
        }

        private Entry withUpdatedAt(long value) {
            return new Entry(key, progressKey, topicId, title, topicUrl, forum, resultSize,
                    seeds, torrentHash, fileId, filePath, fileSizeBytes, positionMs, durationMs,
                    value, completed);
        }

        private JSONObject toJson() throws Exception {
            JSONObject value = new JSONObject();
            value.put("key", key);
            value.put("progressKey", progressKey);
            value.put("topicId", topicId);
            value.put("title", title);
            value.put("topicUrl", topicUrl);
            value.put("forum", forum);
            value.put("resultSize", resultSize);
            value.put("seeds", seeds);
            value.put("torrentHash", torrentHash);
            value.put("fileId", fileId);
            value.put("filePath", filePath);
            value.put("fileSizeBytes", fileSizeBytes);
            value.put("positionMs", positionMs);
            value.put("durationMs", durationMs);
            value.put("updatedAtMs", updatedAtMs);
            value.put("completed", completed);
            return value;
        }

        private static Entry fromJson(JSONObject value) {
            return new Entry(
                    value.optString("key"), value.optString("progressKey"),
                    value.optLong("topicId"), value.optString("title"),
                    value.optString("topicUrl"), value.optString("forum"),
                    value.optString("resultSize"), value.optString("seeds"),
                    value.optString("torrentHash"), value.optInt("fileId", -1),
                    value.optString("filePath"), value.optLong("fileSizeBytes"),
                    value.optLong("positionMs"), value.optLong("durationMs"),
                    value.optLong("updatedAtMs"), value.optBoolean("completed"));
        }
    }
}
