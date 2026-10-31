package app.rutracker.tv.source;

public final class SearchResult {
    public final long topicId;
    public final String title;
    public final String topicUrl;
    public final String forum;
    public final String size;
    public final String seeds;

    public SearchResult(long topicId, String title, String topicUrl, String forum, String size, String seeds) {
        this.topicId = topicId;
        this.title = title == null ? "" : title;
        this.topicUrl = topicUrl == null ? "" : topicUrl;
        this.forum = forum == null ? "" : forum;
        this.size = size == null ? "" : size;
        this.seeds = seeds == null ? "" : seeds;
    }

    /** Returns the tracker seed count, or -1 when RuTracker did not expose it. */
    public int seedCount() {
        if (seeds.isEmpty()) return -1;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < seeds.length(); i++) {
            char value = seeds.charAt(i);
            if (value >= '0' && value <= '9') digits.append(value);
        }
        if (digits.length() == 0) return -1;
        try {
            long parsed = Long.parseLong(digits.toString());
            return parsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) parsed;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
