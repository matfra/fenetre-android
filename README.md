# Fenetre Android

Native Android capture app for fenetre.cma

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

## Daily Timelapse Encoding

The default daily timelapse encoder is `H.264 fast`, which uses Android `MediaCodec` and writes `YYYY-MM-DD.mp4`.

`VP9 high quality` is available as an opt-in setting. It writes `YYYY-MM-DD.webm`, streams the source JPEGs through FFmpeg twice, and uses `libvpx-vp9` at the configured bitrate. The default VP9 bitrate is `7 Mbps`, matching the Python deployment defaults. The APK bundles an Android arm64 FFmpeg executable with `libvpx-vp9` support as a native library payload, so Android extracts it into the app executable native library directory. The FFmpeg executable path can still be overridden from the app settings.

The bundled executable was built from FFmpeg `7.1.1` and libvpx `1.15.2`. Rebuild it with:

```bash
ANDROID_HOME=/home/mathieu/Android/Sdk scripts/build-android-ffmpeg.sh
```
