#!/bin/bash
# Build the YuE2 music CLI (audio.cpp, CPU backend) for Android arm64.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_ROOT="${YUE2_BUILD_DIR:-$ROOT/build/yue2-android}"
SRC="$BUILD_ROOT/audio.cpp"
OUT_BUILD="$BUILD_ROOT/build"
OUTPUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libyue2_cli.so"

AUDIOCPP_URL="https://github.com/0xShug0/audio.cpp.git"
AUDIOCPP_REVISION="3a0dd44293fc706f5c375ec96d3e6fd9c54b6c45"
NDK_REVISION="28.2.13676358"
ANDROID_PLATFORM="android-28"
# Snapdragon 8 Gen 3 and other ARMv8.6 cores; i8mm speeds up the Q4_0/Q8_0 matmuls.
CPU_ARCH="armv8.6-a+dotprod+i8mm+bf16+fp16"

if [[ -z "${ANDROID_NDK:-}" ]]; then
    : "${ANDROID_HOME:?set ANDROID_HOME or ANDROID_NDK}"
    ANDROID_NDK="$ANDROID_HOME/ndk/$NDK_REVISION"
fi
for tool in cmake ninja git; do
    command -v "$tool" >/dev/null 2>&1 || { echo "required tool not found: $tool" >&2; exit 1; }
done
[[ -f "$ANDROID_NDK/build/cmake/android.toolchain.cmake" ]] || { echo "Android NDK not found at $ANDROID_NDK" >&2; exit 1; }
grep -q "Pkg.Revision = $NDK_REVISION" "$ANDROID_NDK/source.properties" || {
    echo "YuE2 requires Android NDK $NDK_REVISION (found at $ANDROID_NDK)" >&2
    exit 1
}
case "$(uname -s)-$(uname -m)" in
    Darwin-arm64|Darwin-x86_64) NDK_HOST_TAG="darwin-x86_64" ;;
    Linux-x86_64)               NDK_HOST_TAG="linux-x86_64" ;;
    *) echo "unsupported build host: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac
TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$NDK_HOST_TAG"
OPENMP_STATIC="$(ls "$TOOLCHAIN"/lib/clang/*/lib/linux/aarch64/libomp.a | head -1)"
[[ -f "$OPENMP_STATIC" ]] || { echo "static libomp.a not found in the NDK" >&2; exit 1; }

if [[ ! -d "$SRC/.git" ]]; then
    mkdir -p "$BUILD_ROOT"
    git clone --quiet "$AUDIOCPP_URL" "$SRC"
fi
if ! git -C "$SRC" cat-file -e "$AUDIOCPP_REVISION^{commit}" 2>/dev/null; then
    git -C "$SRC" fetch --quiet origin "$AUDIOCPP_REVISION"
fi
git -C "$SRC" checkout --quiet --detach "$AUDIOCPP_REVISION"
git -C "$SRC" submodule update --quiet --init --recursive
[[ -z "$(git -C "$SRC" status --porcelain)" ]] || { echo "audio.cpp has local changes: $SRC" >&2; exit 1; }

cmake -S "$SRC" -B "$OUT_BUILD" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DANDROID_STL=c++_static \
    -DAUDIOCPP_MODEL_SET=custom \
    -DAUDIOCPP_MODELS=yue2 \
    -DENGINE_ENABLE_CUDA=OFF \
    -DENGINE_ENABLE_HIP=OFF \
    -DENGINE_ENABLE_VULKAN=OFF \
    -DENGINE_ENABLE_METAL=OFF \
    -DENGINE_ENABLE_NATIVE_CPU=OFF \
    -DENGINE_ENABLE_OPENMP=ON \
    -DGGML_OPENMP=ON \
    -DGGML_CPU_ARM_ARCH="$CPU_ARCH" \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_BACKEND_DL=OFF \
    -DOpenMP_omp_LIBRARY="$OPENMP_STATIC" \
    -DCMAKE_EXE_LINKER_FLAGS="-static-openmp"
cmake --build "$OUT_BUILD" --target audiocpp_cli --parallel "${JOBS:-8}"

mkdir -p "$(dirname "$OUTPUT")"
cp "$OUT_BUILD/bin/audiocpp_cli" "$OUTPUT"
chmod 0755 "$OUTPUT"
"$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$OUTPUT"

DYNAMIC_DEPS="$("$TOOLCHAIN/bin/llvm-readelf" -d "$OUTPUT")"
for unwanted in libomp.so libc++_shared.so; do
    if grep -q "Shared library: \[$unwanted\]" <<<"$DYNAMIC_DEPS"; then
        echo "libyue2_cli.so unexpectedly requires $unwanted" >&2
        exit 1
    fi
done
printf 'audio.cpp revision: %s\nNDK revision: %s\nCPU arch: %s\n' "$AUDIOCPP_REVISION" "$NDK_REVISION" "$CPU_ARCH" \
    | tee "$BUILD_ROOT/build-info.txt"
ls -l "$OUTPUT"
