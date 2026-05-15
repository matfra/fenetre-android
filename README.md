# Fenetre Android

Native Android capture app for fenetre.cma

## License

Fenetre Android application source code is licensed under the MIT License. The
APK bundles an FFmpeg executable for optional VP9 daily timelapse encoding; that
binary and its libvpx dependency are licensed separately. See
`THIRD_PARTY_NOTICES.md`.

## Build

```bash
ANDROID_HOME=/home/mathieu/Android/Sdk ./gradlew :app:assembleDebug
```

## Install

```bash
/home/mathieu/Android/Sdk/platform-tools/adb -s 192.168.8.242:<port> install -r app/build/outputs/apk/debug/app-debug.apk
```

## Start Capture Service

```bash
/home/mathieu/Android/Sdk/platform-tools/adb -s 192.168.8.242:<port> shell am start-foreground-service -n cam.fenetre.android/.FenetreCaptureService -a cam.fenetre.android.START_CAPTURE
```

The app serves the public camera UI on port `8888` and the admin UI/API on port `8889`.

## Exposure Control

The default exposure mode is `Adaptive low ISO`. The first frame after startup
uses Android auto exposure as a baseline. After that, the app applies manual
sensor controls using the last frame's EXIF and measured brightness:

- keep ISO at or below the configured ISO cap, default `100`, while exposure
  time can still increase;
- clamp exposure time at the configured per-lens max, defaulting to `25s` for
  ultra-wide, `15s` for wide, and `5s` for tele;
- once the exposure max is reached, allow ISO to rise above the cap.

`Phone auto` is available as a fallback and leaves exposure decisions to
Android/CameraX.

## Storage Management

The Android app has an opt-in storage management system inspired by the Python service.

Defaults are conservative: storage management is disabled and dry-run is enabled. When enabled, the service checks storage after captures at the configured interval, archives completed day directories, and enforces a maximum app data directory size.

Archiving only touches completed days older than the configured age, after both `daylight.png` and a daily timelapse larger than 1 MB exist. It keeps a representative subset of JPEG files, defaulting to `48`, and writes an `archived` marker. Size cleanup deletes the oldest day directories under `photos/<camera>/YYYY-MM-DD` when the app's `fenetre` data directory exceeds the configured max size, defaulting to `10 GB`.

The app settings screen includes controls for enabling storage management, dry-run mode, check interval, max size, archive age, and JPEGs to keep. The admin API exposes the current storage status in `/status.json` and Prometheus metrics in `/metrics`.

## Daily Timelapse Encoding

The default daily timelapse encoder is `H.264 fast`, which uses Android `MediaCodec` and writes `YYYY-MM-DD.mp4`.

`VP9 high quality` is available as an opt-in setting. It writes `YYYY-MM-DD.webm`, streams the source JPEGs through FFmpeg twice, and uses `libvpx-vp9` at the configured bitrate. The default VP9 bitrate is `7 Mbps`, matching the Python deployment defaults. The APK bundles an Android arm64 FFmpeg executable with `libvpx-vp9` support as a native library payload, so Android extracts it into the app executable native library directory. The FFmpeg executable path can still be overridden from the app settings.

The bundled executable was built from FFmpeg `7.1.1` and libvpx `1.15.2`. Rebuild it with:

```bash
ANDROID_HOME=/home/mathieu/Android/Sdk scripts/build-android-ffmpeg.sh
```

When a daily timelapse is successfully created for a completed day, the app removes that day's frequent timelapse artifacts (`segment-*.ts`, `YYYY-MM-DD.m3u8`, the HLS manifest cache, and `timelapse.json`). Current-day HLS files are kept so today's timelapse remains available while captures are still being added.
