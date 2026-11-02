package com.local.rutrackerplayer;

/** A playable file reported by TorrServer for a prepared torrent. */
final class TorrentFile {
    final int id;
    final String path;
    final long lengthBytes;

    TorrentFile(int id, String path, long lengthBytes) {
        this.id = id;
        this.path = path == null ? "" : path;
        this.lengthBytes = Math.max(0, lengthBytes);
    }

    String fileName() {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
