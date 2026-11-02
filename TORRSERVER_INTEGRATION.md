# TorrServer / TorrServe integration notes

Checked against the official upstream repositories and release metadata on
2026-11-02, then validated on the Xiaomi TV Stick 4K with TorrServe Client 143
and TorrServer MatriX 147.

## Compatible official artifacts

The stick reports `armeabi-v7a`, so the matching server artifact is
`TorrServer-android-arm7`.

| Component | Verified compatible release | Size | SHA-256 |
|---|---:|---:|---|
| TorrServer Android ARMv7 | `MatriX.147.2` | 63,974,812 bytes | `be10bc7d073487fafc8d076774c4dabf517d13740f8c638aac920093fd7898cc` |
| TorrServe Android client | `MatriX.143.Client` | 11,955,914 bytes | `4b577862df6de9b22c387fd2737818a8373b9efa354c91badcc6b78f73f7c7a9` |

Links:

- Server release: <https://github.com/YouROK/TorrServer/releases/tag/MatriX.147.2>
- Server binary: <https://github.com/YouROK/TorrServer/releases/download/MatriX.147.2/TorrServer-android-arm7>
- Current upstream server release (not yet device-tested here): <https://github.com/YouROK/TorrServer/releases/tag/MatriX.148>
- Android client release: <https://github.com/YouROK/TorrServe/releases/tag/MatriX.143.Client>
- Android client APK: <https://github.com/YouROK/TorrServe/releases/download/MatriX.143.Client/TorrServe_MatriX.143.Client.apk>

The downloaded server was independently identified as a 32-bit ARM EABI5 PIE
for Android (`/system/bin/linker`). The APK has `minSdk 16`, `targetSdk 28`,
and no bundled native libraries; it downloads the correct server binary after
installation. The released `143` tag includes the upstream Android 14 service
fix from [issue #130](https://github.com/YouROK/TorrServe/issues/130): explicit
data-sync/media-playback foreground-service types, wake-lock/battery permissions,
and `stopWithTask=false`.

`MatriX.148` is the current upstream server release, but this project has only
device-tested MatriX 147 and verified the 147.2 ARM7 artifact. During the
2026-10-31 device check, TorrServe's network updater installed/reported
MatriX 147. Prefer the client's network updater for its supported path, or use
“Update server from file” only when deliberately pinning the known-compatible
147.2 ARM7 binary. Do not treat the untested 148 release as a required upgrade.

## Recommended installation arrangement

Keep TorrServe/TorrServer as a separately installed GPL application and let the
custom RuTracker UI communicate with it over localhost HTTP.

1. Sideload `TorrServe_MatriX.143.Client.apk`.
2. Launch TorrServe and choose `Updates -> Update server from network`, or use
   `Update server from file` for the verified `MatriX.147.2` ARM7 binary.
3. Keep the server address as `http://localhost:8090`.
4. Enable “Start server on boot”. On firmware that kills the service, exclude
   TorrServe from battery optimisation and optionally enable TorrServe's
   Accessibility service (upstream describes it as preventing OS shutdown).
5. Verify with `GET http://127.0.0.1:8090/echo`; the response is the server
   version string.

The raw server binary can be launched manually with, for example,
`--ip 127.0.0.1 --port 8090 --path <private-dir>`, but an ADB-launched process
is not a reliable Android service and will not survive reboot. The official
TorrServe service is the better MVP host.

TorrServer defaults to an in-memory 64 MiB cache, 25 connections, disk cache
off. Those conservative defaults are suitable for an initial test on this
2 GiB stick. Do not enable disk caching on the limited internal flash for the
first version.

## Minimal API flow

Base URL: `http://127.0.0.1:8090`.

Health check:

```http
GET /echo
```

Add a magnet or an unauthenticated public `.torrent` URL:

```http
POST /torrents
Content-Type: application/json

{
  "action": "add",
  "link": "magnet:?xt=urn:btih:...",
  "title": "Optional title",
  "category": "movie",
  "save_to_db": false
}
```

RuTracker's `.torrent` download is authenticated, so the custom app should
download it with its own RuTracker session and upload the bytes to TorrServer;
passing the protected download URL to `action:add` loses the cookies.

```http
POST /torrent/upload
Content-Type: multipart/form-data

file=<torrent bytes>
title=<optional title>
category=movie
save=<include only when persistence in TorrServer is desired>
```

The response is a torrent status object. Its important fields are `hash` and
`file_stats`, whose entries contain `id`, `path`, and `length`. If metadata is
not ready, poll:

```http
POST /torrents
Content-Type: application/json

{"action":"get","hash":"<infohash>"}
```

Collect the recognised video entries from `file_stats`. A single-film release
can start immediately; for a multi-file release, show the files in torrent
order and let the user select the episode or video before giving
Media3/ExoPlayer one of these URLs:

```text
http://127.0.0.1:8090/play/<infohash>/<file-id>
http://127.0.0.1:8090/stream/<urlencoded-filename>?link=<infohash>&index=<file-id>&play
```

The second form is what the official Android client generates. TorrServer
supports HTTP range requests through these endpoints. Do not add `&preload` to
the player URL: on MatriX 147 that query runs the configured preload
synchronously before the stream handler responds, which can exceed a media
client's response timeout on a slow swarm. Let the player read the stream
directly and manage its own forward buffer instead. A modern custom Android
app must explicitly permit cleartext HTTP to localhost in its network security
configuration.

Other useful requests:

```json
{"action":"list"}
{"action":"drop","hash":"<infohash>"}
{"action":"rem","hash":"<infohash>"}
```

- `drop` unloads an active torrent while leaving a saved DB entry.
- `rem` removes it from the active client and TorrServer DB.
- For this MVP, keep `save_to_db:false`; app favourites should store RuTracker
  topic identifiers and metadata in the custom app, not duplicate them in
  TorrServer.

If TorrServe's local HTTP authentication is enabled, send
`Authorization: Basic <base64(user:password)>` to the control API. Use a new,
app-specific credential. Do not reuse any website account password.

## Network exposure

The raw TorrServer default bind address is all interfaces. The official
TorrServe launcher starts the binary without an `--ip` restriction, so port
8090 can be reachable from the LAN. For a localhost-only custom app, either:

- enable TorrServe's local HTTP authentication and store a distinct credential
  in the custom app; or
- run a separately managed server with `--ip 127.0.0.1` when a reliable Android
  service wrapper is available.

Never expose port 8090 to the public internet. The standard endpoint is plain
HTTP and would expose stream URLs and Basic-auth credentials.

## Licensing

Both upstream projects are GPL-3.0:

- <https://github.com/YouROK/TorrServer/blob/master/LICENSE>
- <https://github.com/YouROK/TorrServe/blob/master/LICENSE>

A custom app that only talks to a separately installed TorrServer over its HTTP
API can be kept as a distinct program. Bundling, modifying, or redistributing
TorrServer/TorrServe requires GPL compliance: preserve notices and licence,
make the corresponding source available, and provide the same freedoms to
recipients. Avoid copying TorrServe client code into a closed-source app; use
the documented HTTP API instead. This is an engineering summary, not legal
advice.
