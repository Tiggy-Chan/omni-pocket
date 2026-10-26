#!/bin/bash
# Build the MOSS-capable stable-diffusion.cpp CLI for Android (CPU + OpenCL).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SD_SRC="$ROOT/third_party/stable-diffusion.cpp"
BUILD_ROOT="${MOSS_BUILD_DIR:-$ROOT/build/moss-android}"
DEPS_ROOT="$BUILD_ROOT/deps"
SD_BUILD="$BUILD_ROOT/stable-diffusion"
OUTPUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libmoss_cli.so"

SD_REVISION="95ff76230889898e1bf40058ba272a3699b50dc6"
GGML_REVISION="a5915eb0e47adef21c3f25b889a008925ea838c2"
OPENCL_HEADERS_REVISION="4ea6df132107e3b4b9407f903204b5522fdffcd6"
OPENCL_ICD_REVISION="5907ac1114079de4383cecddf1c8640e3f52f92b"
NDK_REVISION="28.2.13676358"
ANDROID_PLATFORM="android-28"
PATCH="$ROOT/patches/moss-android.patch"

if [[ -z "${ANDROID_NDK:-}" ]]; then
    : "${ANDROID_HOME:?set ANDROID_HOME or ANDROID_NDK}"
    ANDROID_NDK="$ANDROID_HOME/ndk/$NDK_REVISION"
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
    echo "MOSS requires Android NDK $NDK_REVISION (found at $ANDROID_NDK)" >&2
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

if [[ -f "$PATCH" ]]; then
    if git -C "$SD_SRC" apply --check "$PATCH" >/dev/null 2>&1; then
        git -C "$SD_SRC" apply "$PATCH"
    elif ! git -C "$SD_SRC" apply --reverse --check "$PATCH" >/dev/null 2>&1; then
        echo "stable-diffusion.cpp is neither clean nor already patched by $PATCH" >&2
        exit 1
    fi
elif [[ -n "$(git -C "$SD_SRC" status --porcelain)" ]]; then
    echo "stable-diffusion.cpp has local changes and no MOSS patch was provided" >&2
    exit 1
fi

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
COMMON_ANDROID_ARGS=(
    -G Ninja
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI=arm64-v8a
    -DANDROID_PLATFORM="$ANDROID_PLATFORM"
    -DANDROID_STL=c++_static
)

cmake -S "$OPENCL_ICD_SRC" -B "$OPENCL_ICD_BUILD" \
    "${COMMON_ANDROID_ARGS[@]}" \
    -DBUILD_TESTING=OFF \
    -DOPENCL_ICD_LOADER_BUILD_TESTING=OFF \
    -DOPENCL_ICD_LOADER_HEADERS_DIR="$OPENCL_HEADERS"
cmake --build "$OPENCL_ICD_BUILD" --target OpenCL --parallel "${JOBS:-8}"

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
    -DSD_WEBM=OFF
    -DSD_WEBP=OFF
)

UPSTREAM_VERSION="$(git -C "$SD_SRC" describe --tags --abbrev=7 --dirty=+)"
{
    printf 'stable-diffusion.cpp revision: %s\n' "$SD_REVISION"
    printf 'stable-diffusion.cpp version: %s\n' "$UPSTREAM_VERSION"
    printf 'ggml revision: %s\n' "$GGML_REVISION"
    printf 'NDK revision: %s\n' "$NDK_REVISION"
    printf 'OpenCL-Headers revision: %s\n' "$OPENCL_HEADERS_REVISION"
    printf 'OpenCL-ICD-Loader revision: %s\n' "$OPENCL_ICD_REVISION"
    printf 'Android ABI: arm64-v8a\nAndroid platform: %s\nBuild type: Release\nSTL: c++_static\n' "$ANDROID_PLATFORM"
    printf 'stable-diffusion.cpp CMake arguments:\n'
    printf '  %q\n' "${SD_CMAKE_ARGS[@]}"
} | tee "$BUILD_ROOT/build-info.txt"

cmake -S "$SD_SRC" -B "$SD_BUILD" "${SD_CMAKE_ARGS[@]}"
cmake --build "$SD_BUILD" --target sd-cli --parallel "${JOBS:-8}"

[[ -f "$SD_BUILD/bin/sd-cli" ]] || {
    echo "sd-cli was not produced at $SD_BUILD/bin/sd-cli" >&2
    exit 1
}
mkdir -p "$(dirname "$OUTPUT")"
cp "$SD_BUILD/bin/sd-cli" "$OUTPUT"
chmod 0755 "$OUTPUT"
"$(dirname "$READELF")/llvm-strip" --strip-unneeded "$OUTPUT"

"$READELF" -h "$OUTPUT"
"$READELF" -d "$OUTPUT"
DYNAMIC_DEPS="$("$READELF" -d "$OUTPUT")"
grep -q 'Shared library: \[libOpenCL.so\]' <<<"$DYNAMIC_DEPS" || {
    echo "libmoss_cli.so does not declare its OpenCL runtime dependency" >&2
    exit 1
}
if grep -q 'Shared library: \[libc++_shared.so\]' <<<"$DYNAMIC_DEPS"; then
    echo "libmoss_cli.so unexpectedly requires shared libc++" >&2
    exit 1
fi

printf 'MOSS Android CLI: %s\n' "$OUTPUT"
