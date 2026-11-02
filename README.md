# RuTracker Player for Android TV

An unofficial, remote-friendly Android TV application for searching RuTracker,
selecting a well-seeded release, and streaming it through a separately installed
[TorrServe/TorrServer](https://github.com/YouROK/TorrServe) instance.

The application was built for a Xiaomi TV Stick 4K, but the APK contains no
native code and should work on many Android TV devices running Android 9 or
newer. TorrServe is a separate dependency and its server binary must match the
CPU architecture of the TV device.

> Use this software only for content you are legally allowed to access. The
> project is not affiliated with RuTracker, Xiaomi, Google, or TorrServe.

## Features

- TV-remote navigation designed for the Android TV Leanback launcher.
- RuTracker search by movie title.
- Manual sign-in inside an in-app WebView; credentials are never embedded in
  the source code or APK.
- Results sorted by seed count with clear colour labels:
  - green: 10 or more seeds;
  - yellow: 3–9 seeds;
  - red: 0–2 seeds, with an extra warning before playback.
- Local favourites.
- Local playback history with resume, replay, and per-item removal. A video is
  added after at least 15 seconds of actual playback.
- Authenticated `.torrent` download with magnet fallback.
- Episode/video-file picker for multi-file torrents. Files stay in the order
  reported by the torrent and are shown with their full path and size.
- Streaming through TorrServer on `127.0.0.1:8090`.
- Built-in Media3 player.
- Source frame-rate matching on Android 11+ (including fractional movie rates),
  respecting Android's Match content frame rate setting. HDMI may briefly go
  black while the TV switches modes. Android and the TV make the final mode choice.
- Compatibility handling for older AVI releases:
  - Media3 1.11.1 includes the AVI audio-chunk fix needed by releases whose
    audio stream does not mark every chunk as a keyframe;
  - AC3 bytes split across arbitrary AVI chunks are joined and emitted as real
    AC3 syncframes, preventing periodic timestamp jumps during passthrough;
  - AC3 resume/seek timing uses actual indexed audio-byte positions instead of
    assuming the requested seek time is where the file reader landed;
  - MPEG-4 Part 2 B-frame presentation timestamps are reconstructed from the
    video bitstream, preventing backward timestamp jumps and dropped frames;
  - the AVI stream header supplies the exact source frame rate, so display
    matching also works when the original extractor omits that information;
  - MPEG-4 Part 2/XVID prefers the Amlogic hardware decoder on the tested
    Xiaomi stick, with decoder fallback enabled;
  - supported AC3 uses the TV/HDMI direct-output path, avoiding the affected
    firmware's crashing AC3 decoder.
- One automatic restart from the beginning if TorrServer returns HTTP 416 for
  an invalid byte range.
- Resume prompt with **Continue** and **Start from beginning** choices.
- Compact download-speed and forward-buffer overlay for the first 30 seconds
  of active playback. Pauses and buffering do not count toward this timeout.
- Adaptive buffering for large files:
  - longer initial buffering for UHD releases;
  - automatic buffer growth when download speed is close to the media bitrate;
  - another increase after an actual playback interruption;
  - a hard 128 MiB Media3 allocator cap to protect low-memory TV sticks.
- A warning when the torrent speed is persistently lower than the estimated
  bitrate of the selected file.
- A wide 16:9 Android TV launcher banner sized like standard Leanback tiles.

## Requirements

- Android TV 9 or newer (`minSdk 28`).
- A separately installed and running TorrServe/TorrServer.
- TorrServer reachable locally at `http://127.0.0.1:8090`.
- A RuTracker account.
- Network access to RuTracker and torrent peers. Depending on your location,
  this may require your own lawful VPN or proxy configuration.

Tested configuration:

- Xiaomi TV Stick 4K (`soul`), Android 14, ARMv7;
- TorrServe MatriX 143 Client;
- TorrServer MatriX 147.

## Install

1. Download the APK from the repository's
   [Releases](https://github.com/rijiiokun-wq/mitvstick_rutracker/releases)
   page.
2. Install TorrServe from its
   [official project](https://github.com/YouROK/TorrServe) and start/update the
   local TorrServer component.
3. In TorrServe, keep the server address at `http://127.0.0.1:8090` and enable
   automatic start on boot if the device supports it.
4. Sideload the downloaded APK. For example, with ADB:

   ```sh
   adb connect TV_IP_ADDRESS:ADB_PORT
   adb install -r rutracker-tv-0.3.2.apk
   ```

5. Open **RuTracker Player**, select **Sign in**, complete any browser checks,
   and enter your credentials directly on the RuTracker page.
6. Return to the application, search for a title, and prefer a green result
   with at least 10 seeds.

Installing a newer APK with `adb install -r` preserves the local WebView
session, favourites, playback history, and saved playback positions when the
signing key stays the same.

## Adaptive buffering

The player estimates the selected file's average bitrate from its size and
duration. It combines that estimate with TorrServer's current download speed
and Media3's real forward-buffer duration.

The target grows in stages from 45 to 75 and then 120 seconds. Startup
thresholds grow from 20 to 30 and then 45 seconds; post-interruption thresholds
grow from 30 to 60 and then 90 seconds. The Media3 allocator target grows from
96 to 112 and finally 128 MiB, so very high-bitrate UHD files may reach the
memory limit before the time target.

No finite buffer can guarantee uninterrupted playback when the average torrent
speed remains below the movie bitrate. For example, a 120+ GB movie lasting
about 2.5 hours may require roughly 13–14 MB/s throughout playback. If peers
only provide 4 MB/s, choose a smaller release or a better-seeded torrent.

### Suggested TorrServer settings for 2 GB TV sticks

The following values gave a substantially faster start on the tested Xiaomi
TV Stick 4K without putting its limited flash or memory under unnecessary
pressure:

- memory cache: **64 MiB**;
- preload: **20%** (about 13 MiB before playback);
- connection limit: **50**;
- download rate limit: **unlimited**;
- TCP, µTP, DHT, and PEX: **enabled**;
- responsive mode: **enabled**;
- disk cache: **disabled**.

The application unloads its active torrent as soon as playback closes. This is
important on a VPN connection because an abandoned preload can otherwise keep
using peers and bandwidth while the next selection starts.

## Privacy and security

- RuTracker credentials are not stored in source code, BuildConfig, or the APK.
- Authentication cookies remain in the application's local WebView storage.
- Favourites, playback positions, and playback history are stored only on the
  TV device.
- The application contains no analytics or advertising SDK.
- TorrServer is accessed over localhost cleartext HTTP. Do not expose port 8090
  to the public internet. Prefer localhost-only access or a separate TorrServer
  password that is not reused for any website account.
- Per-app VPN bypass can improve peer connectivity on some devices, but it
  exposes the home public IP to torrent peers. Treat it as a device-specific,
  opt-in troubleshooting step rather than a default configuration.

## Build from source

Prerequisites:

- JDK 17;
- Android SDK 36 (`targetSdk` remains 34);
- Android SDK Build Tools 36.0.0;
- Gradle 8.11.1 and Android Gradle Plugin 8.10.1 (the included wrapper selects
  the required Gradle version);
- an internet connection for the first dependency download.

Build the debug APK:

```sh
./gradlew :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Run the AVI timing and frame-cadence regression tests and Android lint:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

The public release APK is currently debug-signed for straightforward
sideloading. Updating with a build signed by a different key requires
uninstalling the old app first, which removes its local session and history.
Do not commit signing keystores or passwords to the repository.

## Project structure

- `app/src/main/java/com/local/rutrackerplayer/` — Android TV UI, TorrServer
  integration, adaptive buffer, favourites, and player.
- `app/src/main/java/app/rutracker/tv/source/` — RuTracker WebView parsing and
  authenticated torrent download.
- `app/src/main/res/` — launcher icon, TV banner, and styles.
- `TORRSERVER_INTEGRATION.md` — integration and deployment notes.

## Known limitations

- Episode selection uses the video filenames contained in the torrent; the
  application does not retrieve separate episode metadata.
- Playback history starts with videos played in version 0.3.0 or newer; older
  saved resume positions are not automatically imported into the history list.
- RuTracker may occasionally require a manual browser verification or sign-in.
- Torrent availability and speed depend entirely on peers and network routing.
- Leaving playback immediately unloads that torrent, so an abandoned preload
  does not compete with the next selection for peers or VPN bandwidth.
- The release does not bundle TorrServe or TorrServer.
- Packed multi-picture DivX packets and malformed MPEG-4 timing retain the
  original extractor behaviour; the compatibility fix targets ordinary
  MPEG-4 Part 2 AVI packets.

See [0.3.1 playback validation](docs/validation-0.3.1.md) for measured results
on the tested stick and the limits of those checks.

## Third-party software

Media playback uses [AndroidX Media3](https://github.com/androidx/media).
TorrServe/TorrServer is installed separately and is distributed by its authors
under the GPL-3.0 licence. No TorrServe server binary is bundled in this APK.
