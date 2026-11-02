# Playback validation for 0.3.1

Tested on 2 November 2026 with a Xiaomi TV Stick 4K, Android 14, TorrServer
MatriX 147 and a 704x400 MPEG-4 Part 2 AVI with B-frames and stereo AC3 audio.
The source rate is 2997/125 = 23.976 fps. Hardware decoding remained enabled.

## Device comparison

Before the timing correction, the player typically submitted 16–19 frames per
second and dropped 30–40 frames every five seconds, despite 45–120 seconds of
buffered video. Android repeatedly reported video timestamps moving backwards
by 41 or 83 ms. Matching HDMI to 23.976 Hz alone did not remove these drops.

After the correction, playback resumed successfully at approximately 22:22.
Over the following 246.7 seconds after startup:

- mean submitted frame rate: 23.974 fps;
- newly dropped frames: 0;
- minimum forward buffer: 45.0 seconds;
- HDMI output: 3840x2160 at 23.976 Hz;
- backward timestamp reports, AC3 discontinuity exceptions, audio-sink errors
  and fatal exceptions: none observed.

Five frames were dropped during initial startup/mode switching. The counts
above describe the subsequent continuous playback window. The frame rate is
measured from Media3 decoder counters; it is not an optical measurement of the
TV panel.

## Automated checks

- 28 JUnit tests passed: AC3/index timing, MPEG-4 presentation timestamps,
  actual AVI sample-data entrypoints, byte preservation and frame cadence.
- A local 156-frame AVI sample yielded 156 consecutive presentation slots.
  All 58 tested reference-frame seek anchors reproduced the same timestamps.
  The media sample is not included in this repository.
- APK build succeeded; Android lint reported 0 errors and 19 warnings.
- Source and APK DEX were checked for the supplied account credentials;
  none were found.

## Limits

Hardware composition was active. A system screenshot did not capture the video
pixels, so this run does not independently establish visible image quality or
lip sync at the TV speakers. User confirmation is still needed for those.
Packed multi-VOP DivX packets and malformed timing deliberately retain the
original extractor behaviour. This test does not certify every AVI variant or
every Android TV device.

APK SHA-256:

```text
e7546ddbd8367977135e4d4fe10351594094ae8e8e75fdc661346f706cc1ff0a
```
