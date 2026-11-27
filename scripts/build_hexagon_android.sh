#!/bin/bash
# Build Qualcomm Hexagon HTP NPU backend (libggml-hexagon.a with embedded htp_iface_stub)
# and DSP skels (libggml-htp-v73/v75/v79/v81/v85.so) for Android arm64-v8a using the
# official Qualcomm Snapdragon Android toolchain Docker image.
#
# Outputs:
#   - build/hexagon-android/lib/libggml-hexagon.a
#   - app/src/main/assets/hexagon/libggml-htp-v{73,75,79,81,85}.so
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LLAMA_SRC="$ROOT/third_party/llama.cpp"
OUT_ROOT="${HEXAGON_BUILD_DIR:-$ROOT/build/hexagon-android}"
SKEL_ASSETS="$ROOT/app/src/main/assets/hexagon"
IMAGE="${SNAPDRAGON_TOOLCHAIN_IMAGE:-ghcr.io/snapdragon-toolchain/arm64-android:v0.8}"
JOBS="${JOBS:-4}"

EXPECTED="2280f0fd163b03d4331d49e08460f66502e35854"
[[ "$(git -C "$LLAMA_SRC" rev-parse HEAD)" == "$EXPECTED" ]] || {
    echo "llama.cpp must be at $EXPECTED" >&2
    exit 1
}

if ! command -v docker >/dev/null 2>&1; then
    echo "docker not found; skipping Hexagon NPU prebuild" >&2
    exit 1
fi
if ! docker info >/dev/null 2>&1; then
    echo "docker daemon not running; skipping Hexagon NPU prebuild" >&2
    exit 1
fi

mkdir -p "$OUT_ROOT/lib" "$SKEL_ASSETS"

DOCKER_EXTRA_ARGS=()
if [[ "$(uname -s)" == "Linux" ]]; then
    DOCKER_EXTRA_ARGS+=(-u "$(id -u):$(id -g)")
    if [[ -n "${ANDROID_NDK:-}" && -d "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64" ]]; then
        DOCKER_EXTRA_ARGS+=(-v "$ANDROID_NDK:/opt/host-ndk:ro")
    fi
fi

echo "Building ggml-hexagon static archive + HTP v73/v75/v79/v81/v85 skels via $IMAGE ..."
docker run --rm --platform linux/amd64 \
    "${DOCKER_EXTRA_ARGS[@]}" \
    -v "$ROOT:/workspace" \
    -w /workspace \
    "$IMAGE" \
    bash -ceu "
        set -euo pipefail
        HEX_SDK=\$(ls -d /opt/hexagon/* 2>/dev/null | sort -V | tail -n 1)
        if [[ -d /opt/host-ndk ]]; then
            NDK=/opt/host-ndk
        else
            NDK=\$(ls -d /opt/android-ndk* 2>/dev/null | sort -V | tail -n 1)
        fi
        [[ -d \"\$HEX_SDK\" ]] || { echo 'Hexagon SDK not found in image' >&2; exit 1; }
        [[ -d \"\$NDK\" ]] || { echo 'Android NDK not found in image' >&2; exit 1; }
        echo \"Container NDK=\$NDK HEX_SDK=\$HEX_SDK\"

        BUILD_DIR=/workspace/build/hexagon-android/cmake-build
        rm -rf \"\$BUILD_DIR\"
        cmake -S /workspace/third_party/llama.cpp -B \"\$BUILD_DIR\" -G Ninja \
            -DCMAKE_BUILD_TYPE=Release \
            -DCMAKE_TOOLCHAIN_FILE=\"\$NDK/build/cmake/android.toolchain.cmake\" \
            -DANDROID_ABI=arm64-v8a \
            -DANDROID_PLATFORM=android-28 \
            -DANDROID_STL=c++_shared \
            -DPREBUILT_LIB_DIR=android_aarch64 \
            -DBUILD_SHARED_LIBS=OFF \
            -DGGML_BACKEND_DL=OFF \
            -DGGML_CPU=ON \
            -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 \
            -DGGML_OPENMP=OFF \
            -DGGML_OPENCL=OFF \
            -DGGML_VULKAN=OFF \
            -DGGML_HEXAGON=ON \
            -DHEXAGON_SDK_ROOT=\"\$HEX_SDK\" \
            -DLLAMA_BUILD_COMMON=OFF \
            -DLLAMA_BUILD_TESTS=OFF \
            -DLLAMA_BUILD_TOOLS=OFF \
            -DLLAMA_BUILD_EXAMPLES=OFF \
            -DLLAMA_BUILD_SERVER=OFF \
            -DLLAMA_BUILD_APP=OFF \
            -DLLAMA_BUILD_UI=OFF \
            -DLLAMA_OPENSSL=OFF \
            -DLLAMA_SUBPROCESS=OFF \
            -DCMAKE_C_FLAGS='-O3 -fPIC -ffile-prefix-map=/workspace=.' \
            -DCMAKE_CXX_FLAGS='-O3 -fPIC -ffile-prefix-map=/workspace=.'

        cmake --build \"\$BUILD_DIR\" --target ggml-hexagon htp-v73 htp-v75 htp-v79 htp-v81 htp-v85 --parallel ${JOBS}

        HEX_OBJ_DIR=\"\$BUILD_DIR/ggml/src/ggml-hexagon\"
        cp -f \"\$HEX_OBJ_DIR/libggml-hexagon.a\" /workspace/build/hexagon-android/lib/libggml-hexagon.a

        for v in v73 v75 v79 v81 v85; do
            skel=\$(find \"\$HEX_OBJ_DIR\" -name \"libggml-htp-\${v}.so\" | head -n 1)
            [[ -n \"\$skel\" && -f \"\$skel\" ]] || { echo \"Missing HTP skel for \$v\" >&2; exit 1; }
            cp -f \"\$skel\" \"/workspace/app/src/main/assets/hexagon/libggml-htp-\${v}.so\"
        done
        rm -rf \"\$BUILD_DIR\"
    "

ls -lh "$OUT_ROOT/lib/libggml-hexagon.a" "$SKEL_ASSETS"/libggml-htp-v*.so
echo "Qualcomm Hexagon HTP NPU backend & DSP skels ready."
