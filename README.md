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
- Manual sign-in inside a protected WebView; credentials are never embedded in
  the source code or APK.
- Results sorted by seed count with clear colour labels:
  - green: 10 or more seeds;
  - yellow: 3–9 seeds;
  - red: 0–2 seeds, with an extra warning before playback.
- Local favourites.
- Authenticated `.torrent` download with magnet fallback.
- Streaming through TorrServer on `127.0.0.1:8090`.
- Built-in Media3 player.
- Resume prompt with **Continue** and **Start from beginning** choices.
- Download-speed and forward-buffer overlay for the first 10 minutes of active
  playback.
- Adaptive buffering for large files:
  - longer initial buffering for UHD releases;
  - automatic buffer growth when download speed is close to the media bitrate;
  - another increase after an actual playback interruption;
  - a hard 160 MiB memory cap to protect low-memory TV sticks.
- A warning when the torrent speed is persistently lower than the estimated
  bitrate of the selected file.

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
   adb install -r rutracker-tv-0.2.7.apk
   ```

5. Open **RuTracker Player**, select **Sign in**, complete any browser checks,
   and enter your credentials directly on the RuTracker page.
6. Return to the application, search for a title, and prefer a green result
   with at least 10 seeds.

Installing a newer APK with `adb install -r` preserves the local WebView
session, favourites, and saved playback positions when the signing key stays
the same.

## Adaptive buffering

The player estimates the selected file's average bitrate from its size and
duration. It combines that estimate with TorrServer's current download speed
and Media3's real forward-buffer duration.

The target grows in stages from 45 to 75 and then 120 seconds. Startup and
post-interruption thresholds grow as well. Memory use is always capped at
160 MiB, so very high-bitrate UHD files may reach the memory limit before the
time target.

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
- Favourites and playback positions are stored only on the TV device.
- The application contains no analytics or advertising SDK.
- TorrServer is accessed over localhost cleartext HTTP. Do not expose port 8090
  to the public internet. Prefer localhost-only access or a separate TorrServer
  password that is not reused for any website account.

## Build from source

Prerequisites:

- JDK 17;
- Android SDK 34;
- an internet connection for the first dependency download.

Build the debug APK:

```sh
./gradlew :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The public release APK is currently debug-signed for straightforward
sideloading. Do not commit signing keystores or passwords to the repository.

## Project structure

- `app/src/main/java/com/local/rutrackerplayer/` — Android TV UI, TorrServer
  integration, adaptive buffer, favourites, and player.
- `app/src/main/java/app/rutracker/tv/source/` — RuTracker WebView parsing and
  authenticated torrent download.
- `app/src/main/res/` — launcher icon, TV banner, and styles.
- `TORRSERVER_INTEGRATION.md` — integration and deployment notes.

## Known limitations

- For multi-file torrents, the largest recognised video file is selected
  automatically; episode selection is not implemented yet.
- RuTracker may occasionally require a manual browser verification or sign-in.
- Torrent availability and speed depend entirely on peers and network routing.
- Leaving playback immediately unloads that torrent, so an abandoned preload
  does not compete with the next selection for peers or VPN bandwidth.
- The release does not bundle TorrServe or TorrServer.

## Third-party software

Media playback uses [AndroidX Media3](https://github.com/androidx/media).
TorrServe/TorrServer is installed separately and is distributed by its authors
under the GPL-3.0 licence. No TorrServe server binary is bundled in this APK.
