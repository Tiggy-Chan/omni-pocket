#!/bin/bash
# Build the unified audio.cpp music CLI (libyue2_cli.so, CPU OpenMP + Vulkan GPU) for Android arm64.
# Includes YuE2, ACE-Step 1.5, HeartMuLa, and Stable Audio 3, and stages Khronos Vulkan/SPIRV headers
# into build/vulkan-deps/ so app/src/main/cpp (llama.cpp) can also enable ggml-vulkan automatically.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_ROOT="${YUE2_BUILD_DIR:-$ROOT/build/yue2-android}"
DEPS_ROOT="$BUILD_ROOT/deps"
VULKAN_STAGE="$ROOT/build/vulkan-deps"
SRC="$BUILD_ROOT/audio.cpp"
OUT_BUILD="$BUILD_ROOT/build"
OUTPUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libyue2_cli.so"

AUDIOCPP_URL="https://github.com/0xShug0/audio.cpp.git"
AUDIOCPP_REVISION="3a0dd44293fc706f5c375ec96d3e6fd9c54b6c45"
VULKAN_HEADERS_REVISION="691fa560c8a27d1255fa97e350a04cdca676e772"
SPIRV_HEADERS_REVISION="fb1379425b16e58dcbdda7be0f8604a7f5cdce78"
NDK_REVISION="28.2.13676358"
ANDROID_PLATFORM="android-28"
# Portable ARMv8.2+dotprod default so release APKs run across broad arm64 devices without SIGILL.
CPU_ARCH="${YUE2_CPU_ARCH:-armv8.2-a+dotprod}"
MUSIC_MODELS="${YUE2_MODELS:-yue2;ace_step;heartmula;stable_audio}"

if [[ -z "${ANDROID_NDK:-}" || ! -f "${ANDROID_NDK}/source.properties" ]] || ! grep -q "Pkg.Revision = $NDK_REVISION" "${ANDROID_NDK}/source.properties" 2>/dev/null; then
    if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk/$NDK_REVISION" ]]; then
        ANDROID_NDK="$ANDROID_HOME/ndk/$NDK_REVISION"
    elif [[ -z "${ANDROID_NDK:-}" ]]; then
        : "${ANDROID_HOME:?set ANDROID_HOME or ANDROID_NDK}"
        ANDROID_NDK="$ANDROID_HOME/ndk/$NDK_REVISION"
    fi
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
GLSLC="$ANDROID_NDK/shader-tools/$NDK_HOST_TAG/glslc"
OPENMP_STATIC="$(ls "$TOOLCHAIN"/lib/clang/*/lib/linux/aarch64/libomp.a | head -1)"
[[ -f "$OPENMP_STATIC" ]] || { echo "static libomp.a not found in the NDK" >&2; exit 1; }
[[ -x "$GLSLC" ]] || { echo "glslc not found in the NDK at $GLSLC" >&2; exit 1; }

checkout_dep() {
    local name="$1"
    local url="$2"
    local rev="$3"
    local dest="$DEPS_ROOT/$name"
    if [[ ! -d "$dest/.git" ]]; then
        mkdir -p "$DEPS_ROOT"
        git clone --quiet "$url" "$dest"
    fi
    if ! git -C "$dest" cat-file -e "$rev^{commit}" 2>/dev/null; then
        git -C "$dest" fetch --quiet origin "$rev"
    fi
    git -C "$dest" checkout --quiet --detach "$rev"
}

checkout_dep "vulkan-headers" "https://github.com/KhronosGroup/Vulkan-Headers.git" "$VULKAN_HEADERS_REVISION"
checkout_dep "spirv-headers" "https://github.com/KhronosGroup/SPIRV-Headers.git" "$SPIRV_HEADERS_REVISION"

# Stage Vulkan-Headers + SPIRV-Headers CMake package into build/vulkan-deps/ for both audio.cpp and llama.cpp.
mkdir -p "$VULKAN_STAGE"
cmake -S "$DEPS_ROOT/spirv-headers" -B "$DEPS_ROOT/spirv-headers-build" -G Ninja \
    -DCMAKE_INSTALL_PREFIX="$VULKAN_STAGE" \
    -DSPIRV_HEADERS_ENABLE_TESTS=OFF \
    -DSPIRV_HEADERS_ENABLE_INSTALL=ON >/dev/null
cmake --install "$DEPS_ROOT/spirv-headers-build" >/dev/null
cmake -S "$DEPS_ROOT/vulkan-headers" -B "$DEPS_ROOT/vulkan-headers-build" -G Ninja \
    -DCMAKE_INSTALL_PREFIX="$VULKAN_STAGE" \
    -DVULKAN_HEADERS_ENABLE_TESTS=OFF \
    -DVULKAN_HEADERS_ENABLE_INSTALL=ON >/dev/null
cmake --install "$DEPS_ROOT/vulkan-headers-build" >/dev/null

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
    -DCMAKE_C_FLAGS="-ffile-prefix-map=$BUILD_ROOT=. -I$VULKAN_STAGE/include" \
    -DCMAKE_CXX_FLAGS="-ffile-prefix-map=$BUILD_ROOT=. -I$VULKAN_STAGE/include" \
    -DAUDIOCPP_MODEL_SET=custom \
    -DAUDIOCPP_MODELS="$MUSIC_MODELS" \
    -DAUDIOCPP_DEPLOYMENT_BUILD=ON \
    -DENGINE_ENABLE_CUDA=OFF \
    -DENGINE_ENABLE_HIP=OFF \
    -DENGINE_ENABLE_VULKAN=ON \
    -DVulkan_INCLUDE_DIR="$VULKAN_STAGE/include" \
    -DVulkan_GLSLC_EXECUTABLE="$GLSLC" \
    -DENGINE_ENABLE_METAL=OFF \
    -DENGINE_ENABLE_NATIVE_CPU=OFF \
    -DENGINE_ENABLE_OPENMP=ON \
    -DGGML_OPENMP=ON \
    -DGGML_CPU_ARM_ARCH="$CPU_ARCH" \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_BACKEND_DL=OFF \
    -DOpenMP_omp_LIBRARY="$OPENMP_STATIC" \
    -DCMAKE_EXE_LINKER_FLAGS="-static-openmp -llog"
cmake --build "$OUT_BUILD" --target audiocpp_cli --parallel "${JOBS:-4}"

mkdir -p "$(dirname "$OUTPUT")"
cp "$OUT_BUILD/bin/audiocpp_cli" "$OUTPUT"
chmod 0755 "$OUTPUT"
"$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$OUTPUT"

DYNAMIC_DEPS="$("$TOOLCHAIN/bin/llvm-readelf" -d "$OUTPUT")"
grep -q 'Shared library: \[libvulkan.so\]' <<<"$DYNAMIC_DEPS" || {
    echo "libyue2_cli.so does not declare its Vulkan runtime dependency" >&2
    exit 1
}
for unwanted in libomp.so libc++_shared.so; do
    if grep -q "Shared library: \[$unwanted\]" <<<"$DYNAMIC_DEPS"; then
        echo "libyue2_cli.so unexpectedly requires $unwanted" >&2
        exit 1
    fi
done
printf 'audio.cpp revision: %s\nmodels: %s\nbackends: cpu,vulkan\nNDK revision: %s\nCPU arch: %s\n' \
    "$AUDIOCPP_REVISION" "$MUSIC_MODELS" "$NDK_REVISION" "$CPU_ARCH" | tee "$BUILD_ROOT/build-info.txt"
ls -lh "$OUTPUT"
