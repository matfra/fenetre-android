# Third-Party Notices

Fenetre Android application source code is licensed under the MIT License. Some
third-party components are licensed separately and are not relicensed under MIT.

## Bundled FFmpeg Executable

The APK bundles an Android arm64 FFmpeg executable at:

`app/src/main/jniLibs/arm64-v8a/libffmpeg_exec.so`

This executable is built from FFmpeg `7.1.1` and linked with libvpx `1.15.2` for
VP9 encoding. It is used by the optional "VP9 high quality" daily timelapse
mode.

FFmpeg is licensed under the GNU Lesser General Public License version 2.1 or
later unless GPL or nonfree components are enabled. The bundled build is
configured without `--enable-gpl` and without `--enable-nonfree`.

FFmpeg project:

https://ffmpeg.org/

FFmpeg license and legal information:

https://ffmpeg.org/legal.html

FFmpeg source archive used by the build script:

https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz

The bundled executable is reproducible with:

```bash
ANDROID_HOME=/home/mathieu/Android/Sdk scripts/build-android-ffmpeg.sh
```

The FFmpeg configure command used by `scripts/build-android-ffmpeg.sh` is:

```text
./configure \
  --prefix="$PREFIX" \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --enable-cross-compile \
  --cc="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang" \
  --cxx="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang++" \
  --ar="$TOOLCHAIN/bin/llvm-ar" \
  --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
  --strip="$TOOLCHAIN/bin/llvm-strip" \
  --sysroot="$TOOLCHAIN/sysroot" \
  --pkg-config=pkg-config \
  --pkg-config-flags=--static \
  --extra-cflags="-I$PREFIX/include -fPIC" \
  --extra-ldflags="-L$PREFIX/lib" \
  --extra-libs="-lm" \
  --disable-shared \
  --enable-static \
  --disable-doc \
  --disable-ffplay \
  --disable-ffprobe \
  --disable-debug \
  --disable-network \
  --enable-small \
  --enable-libvpx
```

## libvpx

libvpx `1.15.2` is linked into the bundled FFmpeg executable.

libvpx is licensed under the BSD 3-Clause License.

libvpx project:

https://github.com/webmproject/libvpx

libvpx source archive used by the build script:

https://github.com/webmproject/libvpx/archive/refs/tags/v1.15.2.tar.gz

libvpx license:

https://github.com/webmproject/libvpx/blob/main/LICENSE

The libvpx configure command used by `scripts/build-android-ffmpeg.sh` is:

```text
./configure \
  --target=arm64-android-gcc \
  --prefix="$PREFIX" \
  --disable-examples \
  --disable-tools \
  --disable-docs \
  --disable-unit-tests \
  --disable-shared \
  --enable-static \
  --enable-vp9-highbitdepth
```

## Android Gradle Wrapper

The Gradle wrapper scripts are licensed under the Apache License, Version 2.0.

https://www.apache.org/licenses/LICENSE-2.0
