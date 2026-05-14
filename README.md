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
