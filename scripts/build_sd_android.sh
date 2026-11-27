#!/bin/bash
# Build the unified stable-diffusion.cpp JNI shared library (libsd_jni.so) for Android arm64 (CPU + OpenCL + WebM).
# Drives Wan2.2 / MiniMax-H3 video, MOSS-Image, and Hugging Face single-file/modular image models inside :engine.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SD_SRC="$ROOT/third_party/stable-diffusion.cpp"
BUILD_ROOT="${SD_BUILD_DIR:-${MOSS_BUILD_DIR:-$ROOT/build/sd-android}}"
DEPS_ROOT="$BUILD_ROOT/deps"
SD_BUILD="$BUILD_ROOT/stable-diffusion"
OUTPUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libsd_jni.so"
LEGACY_CLI="$ROOT/app/src/main/jniLibs/arm64-v8a/libsd_cli.so"
LEGACY_MOSS="$ROOT/app/src/main/jniLibs/arm64-v8a/libmoss_cli.so"

SD_REVISION="95ff76230889898e1bf40058ba272a3699b50dc6"
GGML_REVISION="a5915eb0e47adef21c3f25b889a008925ea838c2"
OPENCL_HEADERS_REVISION="4ea6df132107e3b4b9407f903204b5522fdffcd6"
OPENCL_ICD_REVISION="5907ac1114079de4383cecddf1c8640e3f52f92b"
KLEIDIAI_TAG="v1.31.0"
KLEIDIAI_MD5="930495f3aeb16b25dc18a2bde45924f4"
NDK_REVISION="28.2.13676358"
ANDROID_PLATFORM="android-28"
PATCHES=(
    "$ROOT/patches/sd-opencl-conv3d-bias.patch"
    "$ROOT/patches/sd-piflow-h3.patch"
)

if [[ -z "${ANDROID_NDK:-}" || ! -f "${ANDROID_NDK}/source.properties" ]] || ! grep -q "Pkg.Revision = $NDK_REVISION" "${ANDROID_NDK}/source.properties" 2>/dev/null; then
    if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk/$NDK_REVISION" ]]; then
        ANDROID_NDK="$ANDROID_HOME/ndk/$NDK_REVISION"
    elif [[ -z "${ANDROID_NDK:-}" ]]; then
        : "${ANDROID_HOME:?set ANDROID_HOME or ANDROID_NDK}"
        ANDROID_NDK="$ANDROID_HOME/ndk/$NDK_REVISION"
    fi
fi

for tool in cmake ninja git python3; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "required tool not found: $tool" >&2
        exit 1
    }
done

[[ -f "$ANDROID_NDK/build/cmake/android.toolchain.cmake" ]] || {
    echo "Android NDK not found at $ANDROID_NDK" >&2
    exit 1
}
grep -q "Pkg.Revision = $NDK_REVISION" "$ANDROID_NDK/source.properties" || {
    echo "stable-diffusion.cpp requires Android NDK $NDK_REVISION (found at $ANDROID_NDK)" >&2
    exit 1
}

case "$(uname -s)-$(uname -m)" in
    Darwin-arm64|Darwin-x86_64) NDK_HOST_TAG="darwin-x86_64" ;;
    Linux-x86_64)               NDK_HOST_TAG="linux-x86_64" ;;
    *)
        echo "unsupported build host: $(uname -s)-$(uname -m)" >&2
        exit 1
        ;;
esac
READELF="$ANDROID_NDK/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin/llvm-readelf"
[[ -x "$READELF" ]] || {
    echo "llvm-readelf not found at $READELF" >&2
    exit 1
}

[[ "$(git -C "$SD_SRC" rev-parse HEAD)" == "$SD_REVISION" ]] || {
    echo "stable-diffusion.cpp must be at $SD_REVISION" >&2
    exit 1
}
[[ "$(git -C "$SD_SRC/ggml" rev-parse HEAD)" == "$GGML_REVISION" ]] || {
    echo "patched ggml must be at $GGML_REVISION" >&2
    exit 1
}
[[ -z "$(git -C "$SD_SRC/ggml" status --porcelain)" ]] || {
    echo "ggml has local changes; refusing to modify or build them" >&2
    exit 1
}

for PATCH in "${PATCHES[@]}"; do
    if [[ -f "$PATCH" ]]; then
        if git -C "$SD_SRC" apply --check "$PATCH" >/dev/null 2>&1; then
            git -C "$SD_SRC" apply "$PATCH"
        elif ! git -C "$SD_SRC" apply --reverse --check "$PATCH" >/dev/null 2>&1; then
            echo "stable-diffusion.cpp is neither clean nor already patched by $PATCH" >&2
            exit 1
        fi
    fi
done

checkout_dependency() {
    local name="$1"
    local url="$2"
    local revision="$3"
    local destination="$DEPS_ROOT/$name-src"

    if [[ ! -d "$destination/.git" ]]; then
        mkdir -p "$DEPS_ROOT"
        git clone --quiet "$url" "$destination"
    fi
    if ! git -C "$destination" cat-file -e "$revision^{commit}" 2>/dev/null; then
        git -C "$destination" fetch --quiet origin "$revision"
    fi
    if [[ "$(git -C "$destination" rev-parse HEAD 2>/dev/null || true)" != "$revision" ]]; then
        git -C "$destination" checkout --quiet --detach "$revision"
    fi
    [[ -z "$(git -C "$destination" status --porcelain)" ]] || {
        echo "OpenCL dependency has local changes: $destination" >&2
        exit 1
    }
}

checkout_dependency "opencl-headers" \
    "https://github.com/KhronosGroup/OpenCL-Headers.git" \
    "$OPENCL_HEADERS_REVISION"
checkout_dependency "opencl-icd-loader" \
    "https://github.com/KhronosGroup/OpenCL-ICD-Loader.git" \
    "$OPENCL_ICD_REVISION"

OPENCL_HEADERS="$DEPS_ROOT/opencl-headers-src"
OPENCL_ICD_SRC="$DEPS_ROOT/opencl-icd-loader-src"
OPENCL_ICD_BUILD="$DEPS_ROOT/opencl-icd-loader-build"
KLEIDIAI_DIR="$DEPS_ROOT/kleidiai-${KLEIDIAI_TAG}-src"
KLEIDIAI_TAR="$DEPS_ROOT/kleidiai-${KLEIDIAI_TAG}-src.tar.gz"
KLEIDIAI_ENABLED=OFF
if [[ ! -f "$KLEIDIAI_DIR/CMakeLists.txt" ]]; then
    mkdir -p "$DEPS_ROOT"
    if [[ ! -f "$KLEIDIAI_TAR" ]]; then
        curl -fSL --retry 2 --retry-delay 2 --connect-timeout 10 \
            -o "$KLEIDIAI_TAR.part" \
            "https://github.com/ARM-software/kleidiai/releases/download/${KLEIDIAI_TAG}/kleidiai-${KLEIDIAI_TAG}-src.tar.gz" \
            && mv "$KLEIDIAI_TAR.part" "$KLEIDIAI_TAR" \
            || rm -f "$KLEIDIAI_TAR.part"
    fi
    if [[ -f "$KLEIDIAI_TAR" ]]; then
        ACTUAL_MD5="$(md5 -q "$KLEIDIAI_TAR" 2>/dev/null || md5sum "$KLEIDIAI_TAR" | awk '{print $1}')"
        if [[ "$ACTUAL_MD5" == "$KLEIDIAI_MD5" ]]; then
            mkdir -p "$KLEIDIAI_DIR"
            tar -xzf "$KLEIDIAI_TAR" -C "$KLEIDIAI_DIR" --strip-components=1
        else
            echo "Warning: SD KleidiAI archive MD5 mismatch ($ACTUAL_MD5); removing" >&2
            rm -f "$KLEIDIAI_TAR"
        fi
    fi
fi
if [[ -f "$KLEIDIAI_DIR/CMakeLists.txt" ]]; then
    KLEIDIAI_ENABLED=ON
fi
PREFIX_MAP_FLAGS="-ffile-prefix-map=$ROOT=. -ffile-prefix-map=$BUILD_ROOT=."
COMMON_ANDROID_ARGS=(
    -G Ninja
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI=arm64-v8a
    -DANDROID_PLATFORM="$ANDROID_PLATFORM"
    -DANDROID_STL=c++_static
    -DCMAKE_C_FLAGS="$PREFIX_MAP_FLAGS"
    -DCMAKE_CXX_FLAGS="$PREFIX_MAP_FLAGS"
)

cmake -S "$OPENCL_ICD_SRC" -B "$OPENCL_ICD_BUILD" \
    "${COMMON_ANDROID_ARGS[@]}" \
    -DBUILD_TESTING=OFF \
    -DOPENCL_ICD_LOADER_BUILD_TESTING=OFF \
    -DOPENCL_ICD_LOADER_HEADERS_DIR="$OPENCL_HEADERS"
cmake --build "$OPENCL_ICD_BUILD" --target OpenCL --parallel "${JOBS:-4}"

OPENCL_LIBRARY="$OPENCL_ICD_BUILD/libOpenCL.so"
[[ -f "$OPENCL_LIBRARY" ]] || {
    echo "OpenCL link library was not produced at $OPENCL_LIBRARY" >&2
    exit 1
}

SD_CMAKE_ARGS=(
    "${COMMON_ANDROID_ARGS[@]}"
    -DBUILD_SHARED_LIBS=OFF
    -DGGML_BACKEND_DL=OFF
    -DGGML_CPU=ON
    -DGGML_CPU_ARM_ARCH="${SD_CPU_ARCH:-armv8.2-a+dotprod+fp16}"
    -DGGML_CPU_KLEIDIAI="$KLEIDIAI_ENABLED"
    -DFETCHCONTENT_SOURCE_DIR_KLEIDIAI="$KLEIDIAI_DIR"
    -DGGML_NATIVE=OFF
    -DGGML_OPENMP=OFF
    -DGGML_OPENCL=ON
    -DGGML_OPENCL_EMBED_KERNELS=ON
    -DGGML_OPENCL_PROFILING=OFF
    -DGGML_OPENCL_TARGET_VERSION=300
    -DGGML_OPENCL_USE_ADRENO_KERNELS=ON
    -DOpenCL_INCLUDE_DIR="$OPENCL_HEADERS"
    -DOpenCL_LIBRARY="$OPENCL_LIBRARY"
    -DSD_BUILD_EXAMPLES=ON
    -DSD_BUILD_SHARED_GGML_LIB=OFF
    -DSD_BUILD_SHARED_LIBS=OFF
    -DSD_CUDA=OFF
    -DSD_HIPBLAS=OFF
    -DSD_METAL=OFF
    -DSD_MUSA=OFF
    -DSD_OPENCL=ON
    -DSD_SYCL=OFF
    -DSD_USE_SYSTEM_GGML=OFF
    -DSD_USE_UPSTREAM_GGML=OFF
    -DSD_VULKAN=OFF
    -DSD_WEBP=ON
    -DSD_WEBM=ON
)

UPSTREAM_VERSION="$(git -C "$SD_SRC" describe --tags --always --abbrev=7 --dirty=+)"
{
    printf 'stable-diffusion.cpp revision: %s\n' "$SD_REVISION"
    printf 'stable-diffusion.cpp version: %s\n' "$UPSTREAM_VERSION"
    printf 'ggml revision: %s\n' "$GGML_REVISION"
    printf 'NDK revision: %s\n' "$NDK_REVISION"
    printf 'OpenCL-Headers revision: %s\n' "$OPENCL_HEADERS_REVISION"
    printf 'OpenCL-ICD-Loader revision: %s\n' "$OPENCL_ICD_REVISION"
    printf 'Android ABI: arm64-v8a\nAndroid platform: %s\nBuild type: Release\nSTL: c++_static\nWebM: ON\n' "$ANDROID_PLATFORM"
} | tee "$BUILD_ROOT/build-info.txt"

cmake -S "$SD_SRC" -B "$SD_BUILD" "${SD_CMAKE_ARGS[@]}"
cmake --build "$SD_BUILD" --target stable-diffusion sd-cli --parallel "${JOBS:-4}"

# Link true JNI shared library (libsd_jni.so) with 16KB ELF page alignment and dlopen OpenCL loader.
# Reuses all compiled static libraries and compiles cli/main.cpp with -Dmain=omni_sd_cli_main.
TOOLCHAIN_BIN="$(dirname "$READELF")"
CLANG="$TOOLCHAIN_BIN/aarch64-linux-android28-clang"
CLANGXX="$TOOLCHAIN_BIN/aarch64-linux-android28-clang++"
JNI_OBJ_DIR="$BUILD_ROOT/jni-obj"
mkdir -p "$JNI_OBJ_DIR" "$(dirname "$OUTPUT")"

"$CLANG" -O3 -fPIC -DCL_TARGET_OPENCL_VERSION=300 $PREFIX_MAP_FLAGS \
    -I"$OPENCL_HEADERS" \
    -c "$ROOT/app/src/main/cpp/opencl_dlopen_loader.c" \
    -o "$JNI_OBJ_DIR/opencl_dlopen_loader.o"

SD_CLI_INCLUDES=(
    -I"$SD_SRC/include"
    -I"$SD_SRC/src"
    -I"$SD_SRC/examples"
    -I"$SD_SRC/examples/cli"
    -I"$SD_SRC/thirdparty"
    -I"$SD_SRC/thirdparty/libwebm"
    -I"$SD_SRC/thirdparty/libwebp/src"
    -I"$SD_SRC/ggml/include"
)

"$CLANGXX" -std=c++17 -O3 -fPIC -DSD_USE_WEBP -DSD_USE_WEBM $PREFIX_MAP_FLAGS \
    -Dmain=omni_sd_cli_main \
    "${SD_CLI_INCLUDES[@]}" \
    -c "$SD_SRC/examples/cli/main.cpp" \
    -o "$JNI_OBJ_DIR/sd_cli_main.o"

"$CLANGXX" -std=c++17 -O3 -fPIC $PREFIX_MAP_FLAGS \
    -c "$ROOT/scripts/native/sd_jni_bridge.cpp" \
    -o "$JNI_OBJ_DIR/sd_jni_bridge.o"

CLI_COMMON_OBJS=(
    "$SD_BUILD/examples/cli/CMakeFiles/sd-cli.dir/__/common/common.cpp.o"
    "$SD_BUILD/examples/cli/CMakeFiles/sd-cli.dir/__/common/log.cpp.o"
    "$SD_BUILD/examples/cli/CMakeFiles/sd-cli.dir/__/common/media_io.cpp.o"
    "$SD_BUILD/examples/cli/CMakeFiles/sd-cli.dir/image_metadata.cpp.o"
)

STATIC_LIBS=(
    "$SD_BUILD/libstable-diffusion.a"
    "$SD_BUILD/thirdparty/libzip/libzip.a"
    "$SD_BUILD/ggml/src/libggml.a"
    "$SD_BUILD/ggml/src/libggml-cpu.a"
    "$SD_BUILD/ggml/src/ggml-opencl/libggml-opencl.a"
    "$SD_BUILD/ggml/src/libggml-base.a"
    "$SD_BUILD/thirdparty/libwebm/libwebm.a"
    "$SD_BUILD/thirdparty/libwebp/libwebpmux.a"
    "$SD_BUILD/thirdparty/libwebp/libwebp.a"
    "$SD_BUILD/thirdparty/libwebp/libsharpyuv.a"
    "$SD_BUILD/thirdparty/libwebp/libcpufeatures-webp.a"
)
EXISTING_LIBS=()
for lib in "${STATIC_LIBS[@]}"; do
    [[ -f "$lib" ]] && EXISTING_LIBS+=("$lib")
done
while IFS= read -r klib; do
    EXISTING_LIBS+=("$klib")
done < <(find "$SD_BUILD" -name 'libkleidiai*.a' | sort)

"$CLANGXX" -shared -fPIC \
    -static-libstdc++ \
    -Wl,-z,max-page-size=16384 \
    -Wl,--version-script="$ROOT/scripts/native/jni_engine.map" \
    -Wl,--gc-sections \
    "$JNI_OBJ_DIR/sd_jni_bridge.o" \
    "$JNI_OBJ_DIR/sd_cli_main.o" \
    "$JNI_OBJ_DIR/opencl_dlopen_loader.o" \
    "${CLI_COMMON_OBJS[@]}" \
    -Wl,--start-group "${EXISTING_LIBS[@]}" -Wl,--end-group \
    -ldl -llog -lm -lz \
    -o "$OUTPUT"

"$TOOLCHAIN_BIN/llvm-strip" --strip-unneeded "$OUTPUT"
rm -f "$LEGACY_CLI" "$LEGACY_MOSS"

DYNAMIC_DEPS="$("$READELF" -d "$OUTPUT")"
if grep -q 'Shared library: \[libc++_shared.so\]' <<<"$DYNAMIC_DEPS"; then
    echo "libsd_jni.so unexpectedly requires shared libc++" >&2
    exit 1
fi
if grep -q 'Shared library: \[libOpenCL.so\]' <<<"$DYNAMIC_DEPS"; then
    echo "libsd_jni.so should use dlopen loader instead of DT_NEEDED libOpenCL.so" >&2
    exit 1
fi
PROG_HEADERS="$("$READELF" -l "$OUTPUT")"
grep -Eq 'LOAD.*[[:space:]]0x(4000|10000)[[:space:]]*$' <<<"$PROG_HEADERS" || {
    echo "libsd_jni.so is not 16KB page-aligned" >&2
    exit 1
}
DYN_SYMS="$("$READELF" --dyn-syms "$OUTPUT")"
grep -q 'Java_com_tiggy_qwenimage_engine_NativeEngineBridge_nativeRunSd' <<<"$DYN_SYMS" || {
    echo "libsd_jni.so missing JNI entry symbol" >&2
    exit 1
}

printf 'Unified sd JNI shared library ready: %s\n' "$OUTPUT"
ls -lh "$OUTPUT"
