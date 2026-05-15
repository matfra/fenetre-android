#!/usr/bin/env bash
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/home/mathieu/Android/Sdk}"
NDK_VERSION="${NDK_VERSION:-28.2.13676358}"
API_LEVEL="${API_LEVEL:-26}"
BUILD_DIR="${BUILD_DIR:-/tmp/fenetre-ffmpeg-build}"
LIBVPX_VERSION="${LIBVPX_VERSION:-1.15.2}"
FFMPEG_VERSION="${FFMPEG_VERSION:-7.1.1}"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
PREFIX="$BUILD_DIR/prefix"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/src" "$PREFIX"

cd "$BUILD_DIR/src"
curl -fL -o libvpx.tar.gz "https://github.com/webmproject/libvpx/archive/refs/tags/v$LIBVPX_VERSION.tar.gz"
curl -fL -o ffmpeg.tar.xz "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
tar -xzf libvpx.tar.gz
tar -xJf ffmpeg.tar.xz

export PATH="$TOOLCHAIN/bin:$PATH"
export CC="aarch64-linux-android${API_LEVEL}-clang"
export CXX="aarch64-linux-android${API_LEVEL}-clang++"
export AR="llvm-ar"
export NM="llvm-nm"
export RANLIB="llvm-ranlib"
export STRIP="llvm-strip"

cd "$BUILD_DIR/src/libvpx-$LIBVPX_VERSION"
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
make -j"$(nproc)"
make install

export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"

cd "$BUILD_DIR/src/ffmpeg-$FFMPEG_VERSION"
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
make -j"$(nproc)" ffmpeg

mkdir -p "$REPO_DIR/app/src/main/jniLibs/arm64-v8a"
cp ffmpeg "$REPO_DIR/app/src/main/jniLibs/arm64-v8a/libffmpeg_exec.so"
echo "Wrote app/src/main/jniLibs/arm64-v8a/libffmpeg_exec.so"
