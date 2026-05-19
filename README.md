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

The default exposure mode is `Adaptive low ISO`, but the app does not force
manual sensor controls all day. It starts in `Phone auto`, then uses the last
frame's EXIF to compute an exposure composite:

```text
exposure composite = ISO * exposure_time_seconds
```

When phone auto rises above the configured ISO cap, default `100`, and the
composite exceeds the night threshold, default `2.0`, the app switches to the
configured night strategy. When the composite falls below the day threshold,
default `1.0`, it switches back to phone auto. This keeps daytime captures on
the phone's normal processing pipeline while still allowing long low-ISO night
captures.

The manual adaptive night strategy:

- keeps ISO at or below the configured ISO cap while exposure time can still
  increase;
- targets a configurable dark night-frame brightness, defaulting to `12%`,
  avoiding the daytime-like luma target that overexposes bright foregrounds and
  city-lit skies;
- can apply a configurable radial vignette-correction post-process before
  overlays and metadata are written; on the Pixel 6 Pro this defaults to
  darkening the center instead of brightening the corners, preserving ETTR in
  the vignetted sky and avoiding extra corner noise;
- clamps exposure time at the configured per-lens max, defaulting to `25s` for
  ultra-wide, `15s` for wide, and `5s` for tele;
- once the exposure max is reached, allows ISO to rise above the cap.

Night exposure boost is independent from the day/night mode switch. It defaults
to `0` and only applies inside the configured twilight-buffer night window when
explicitly enabled.

## Tested Devices

| Device | Tested lens | Day mode | Recommended night mode | Notes |
| --- | --- | --- | --- | --- |
| Google Pixel 6 Pro | Ultra-wide, wide | Phone auto | Manual adaptive | CameraX Night extension is available but produced disappointing quality in testing. Manual adaptive gives smoother low-ISO long exposures. Infinity focus is enabled. Camera2 logical max exposure reports about `8.31s`; EXIF can report `4.3s` even when a longer exposure was requested. |
| Samsung Galaxy S20 `SM-G981V` | Ultra-wide | Phone auto | Manual adaptive | CameraX Night extension is not available. Camera2 night scene is available but raised ISO aggressively in testing. The selected ultra-wide camera reports a standard Camera2 max exposure of about `0.15379s`, while Samsung vendor metadata advertises `30s`; manual adaptive uses the Samsung range but caps it at `8s`, because longer requests caused CameraX capture timeouts. |

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
