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

## ADB Keepalive

Wireless debugging ports can stop responding when they sit idle. Keep the
current phone ports in `adb-devices.yaml`, then run:

```bash
scripts/adb-keepalive.sh
```

To leave it running in the background:

```bash
nohup scripts/adb-keepalive.sh > adb-keepalive.log 2>&1 &
```

Set `ADB_KEEPALIVE_INTERVAL_SECONDS` to change the default 280-second interval.

To run it automatically under user systemd:

```bash
mkdir -p ~/.config/systemd/user
ln -sf "$PWD/systemd/fenetre-adb-keepalive.service" ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now fenetre-adb-keepalive.service
```

## Local Emulator For Web UI Iteration

For web UI work, use the local AVD instead of reinstalling on a physical phone:

```bash
/home/mathieu/Android/Sdk/cmdline-tools/latest/bin/avdmanager create avd \
  --name fenetre_webui \
  --package "system-images;android-35;google_apis;x86_64" \
  --device pixel_6_pro
```

If the emulator or Android 35 Google APIs image is missing, install them first:

```bash
/home/mathieu/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/home/mathieu/Android/Sdk \
  "emulator" \
  "system-images;android-35;google_apis;x86_64"
```

Start the emulator headless:

```bash
setsid sg kvm -c '/home/mathieu/Android/Sdk/emulator/emulator -avd fenetre_webui -no-window -no-audio -gpu swiftshader_indirect -camera-back emulated -camera-front none -port 5554' \
  </dev/null > /tmp/fenetre_webui_emulator.log 2>&1 &
```

Install the app, start the capture service, and forward the embedded web ports
to high localhost ports so they do not conflict with real phones:

```bash
ANDROID_HOME=/home/mathieu/Android/Sdk ./gradlew :app:assembleDebug
/home/mathieu/Android/Sdk/platform-tools/adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
/home/mathieu/Android/Sdk/platform-tools/adb -s emulator-5554 shell pm grant cam.fenetre.android android.permission.CAMERA || true
/home/mathieu/Android/Sdk/platform-tools/adb -s emulator-5554 shell am start-foreground-service -n cam.fenetre.android/.FenetreCaptureService -a cam.fenetre.android.START_CAPTURE
/home/mathieu/Android/Sdk/platform-tools/adb -s emulator-5554 forward tcp:18888 tcp:8888
/home/mathieu/Android/Sdk/platform-tools/adb -s emulator-5554 forward tcp:18889 tcp:8889
```

Emulator URLs:

- Public UI: `http://127.0.0.1:18888/`
- Admin UI: `http://127.0.0.1:18889/`

ADB forwards bind to localhost. To expose the emulator UI on LAN addresses too,
run a small TCP proxy from the host LAN interfaces to the localhost forwards:

```bash
BIND_ADDRS=$(ip -4 -o addr show scope global | awk '$2 !~ /^(docker|br-|tailscale)/ {split($4,a,"/"); print a[1]}' | paste -sd, -)
export BIND_ADDRS
setsid python3 - <<'PY' >/tmp/fenetre_webui_lan_proxy.log 2>&1 &
import asyncio, os, signal

bind_addrs = [addr for addr in os.environ["BIND_ADDRS"].split(",") if addr]
routes = [(18888, "127.0.0.1", 18888), (18889, "127.0.0.1", 18889)]
servers = []

async def pipe(reader, writer):
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    finally:
        writer.close()
        await writer.wait_closed()

async def handle(client_reader, client_writer, target_host, target_port):
    upstream_reader, upstream_writer = await asyncio.open_connection(target_host, target_port)
    await asyncio.gather(pipe(client_reader, upstream_writer), pipe(upstream_reader, client_writer))

async def main():
    for bind_addr in bind_addrs:
        for listen_port, target_host, target_port in routes:
            servers.append(await asyncio.start_server(
                lambda r, w, th=target_host, tp=target_port: handle(r, w, th, tp),
                bind_addr,
                listen_port,
            ))
    stop = asyncio.Future()
    loop = asyncio.get_running_loop()
    loop.add_signal_handler(signal.SIGTERM, stop.set_result, None)
    await stop

asyncio.run(main())
PY
echo $! > /tmp/fenetre_webui_lan_proxy.pid
```

Stop the emulator with:

```bash
/home/mathieu/Android/Sdk/platform-tools/adb -s emulator-5554 emu kill
```

## Exposure Control

The default exposure mode is `Adaptive low ISO`. In this mode, the app starts in
`Phone auto` during the day. Android controls exposure and keeps the phone's
normal image-processing pipeline, including lens correction and other
manufacturer tuning.

After each phone-auto frame, the app measures image luma and reads the EXIF ISO.
It switches to the configured night strategy only when both conditions are true:

- EXIF ISO is at or above `night_adaptive_iso_threshold`, defaulting to `400`.
- Image luma is no brighter than `manual_night_target_luma + 0.03`.

The usual night strategy is `Manual adaptive`. Requiring both ISO and luma keeps
the app in phone auto during twilight while the phone is still producing a much
brighter image than the configured night target.

In manual adaptive mode, the app tries to keep ISO at `100` and adjusts shutter
time to reach the configured night brightness target. If the requested shutter
time reaches the configured per-lens maximum, the app then allows ISO to rise.

At the end of the night, the app does not use luma to decide when to return to
phone auto, because manual adaptive is actively controlling luma. Instead, it
returns to phone auto when the requested shutter time becomes shorter than
`manual_to_auto_max_exposure_seconds`, defaulting to `0.033333` seconds
(`1/30s`). A very short requested exposure means there is enough ambient light
that the phone's normal auto mode should take over again.

Manual adaptive also supports a configurable radial vignette-correction
post-process before overlays and metadata are written. On the Pixel 6 Pro this
defaults to darkening the center instead of brightening the corners, preserving
ETTR in the vignetted sky and avoiding extra corner noise.

Night exposure boost is independent from the day/night mode switch. It defaults
to `0` and only applies inside the configured twilight-buffer night window when
explicitly enabled.

## SSIM Adaptive Interval

When SSIM adaptive interval is enabled, the app samples each post-processed
capture before overlays are drawn, converts the configured crop to a `50x50`
grayscale image, and keeps that sample in memory for comparison with the next
frame. This mirrors the Python service without decoding the previous JPEG again
or letting timestamp/sun-path overlays affect the similarity score.

The `ssim_area` setting accepts `x1,y1,x2,y2` as either ratios, when all values
are `<= 1.0`, or absolute pixels. If the latest SSIM is below the configured
target, the next interval is shortened by the decrease factor. If it is above
target, the interval grows by the configured number of seconds, bounded by the
configured min/max interval. Sunrise/sunset fast mode takes precedence.

When star detection is enabled, the app also analyzes small bright connected
components in the selected SSIM area before the crop is resampled to `50x50`.
If enough stars are detected in the night window, SSIM interval adaptation is
suppressed and the configurable star interval is used instead. The default star
interval is `20` seconds.

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
