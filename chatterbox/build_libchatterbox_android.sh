#!/usr/bin/env bash
# Cross-compile the Chatterbox ggml engine for Android arm64-v8a.
#
#   native/build_chatterbox_android.sh [chatterbox.cpp checkout] [output dir]
#
# Produces libchatterbox.so (JNI bridge + tts-cpp + ggml, static-linked inside one
# shared object) plus the exported symbols list used by the app's jniLibs.
set -euo pipefail

CBX_DIR="${1:-${CHATTERBOX_CPP_DIR:-$HOME/chatterbox.cpp}}"
OUT_DIR="${2:-$HOME/.cache/chatterbox-android/out}"
NDK="${ANDROID_NDK:-/opt/android-sdk/ndk/28.2.13676358}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

[ -d "$CBX_DIR/src" ] || { echo "error: $CBX_DIR is not a chatterbox.cpp checkout" >&2; exit 1; }
[ -d "$NDK" ]         || { echo "error: NDK not found at $NDK" >&2; exit 1; }

BUILD="$OUT_DIR/build-arm64-v8a"
rm -rf "$BUILD"
mkdir -p "$BUILD"

cmake -S "$HERE" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCHATTERBOX_CPP_DIR="$CBX_DIR"

cmake --build "$BUILD" -j"$(nproc)" --target chatterbox

mkdir -p "$OUT_DIR/jniLibs/arm64-v8a"
cp "$BUILD/libchatterbox.so" "$OUT_DIR/jniLibs/arm64-v8a/"

SO="$OUT_DIR/jniLibs/arm64-v8a/libchatterbox.so"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

# Drop everything that is not needed at runtime (the JNI entry points stay: they live in .dynsym).
"$BIN/llvm-strip" --strip-unneeded "$SO"

echo "--- artifact ---"
ls -la "$SO"
"$BIN/llvm-readelf" -h "$SO" | grep -E "Class|Machine"
echo "--- JNI exports ---"
"$BIN/llvm-nm" -D --defined-only "$SO" | grep -i "chatterboxnative\|JNI_OnLoad" || echo "WARNING: no JNI symbols exported"
echo "--- undefined (must be only libc/libm/libdl/liblog) ---"
"$BIN/llvm-readelf" -d "$SO" | grep NEEDED || true
echo "--- host-path probe (must be 0) ---"
LEAK=0
for pat in "$CBX_DIR" "$BUILD" "$HOME"; do
    n=$(strings "$SO" | grep -c -- "$pat" || true)
    echo "  $pat: $n"
    [ "$n" = "0" ] || LEAK=1
done
[ "$LEAK" = "0" ] || { echo "error: build paths leaked into $SO" >&2; exit 1; }
