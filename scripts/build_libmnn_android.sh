#!/bin/bash
# Build libMNN.so (OpenCL + LLM + diffusion, c++_shared) for the Android library, and the adb CLI demo.
#   ANDROID_NDK=/path/to/ndk scripts/build_libmnn_android.sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MNN="$ROOT/third_party/MNN"
: "${ANDROID_NDK:?set ANDROID_NDK}"

# The pinned MNN branch contains the complete Android Qwen runtime.  Apply the
# small official-Turbo extension before building; keeping it as a parent-repo
# patch makes a fresh clone reproducible without inventing a private MNN fork.
TURBO_PATCH="$ROOT/patches/mnn-qwen-image-official-turbo.patch"
if [ -f "$TURBO_PATCH" ]; then
    if git -C "$MNN" apply --check "$TURBO_PATCH" >/dev/null 2>&1; then
        git -C "$MNN" apply "$TURBO_PATCH"
    elif ! git -C "$MNN" apply --reverse --check "$TURBO_PATCH" >/dev/null 2>&1; then
        echo "MNN checkout is neither clean nor already patched for official Turbo" >&2
        exit 1
    fi
fi
source "$ROOT/scripts/apply_mnn_patch.sh"
apply_mnn_patch "$MNN" "$ROOT/patches/mnn-elephant-decision.patch"
COMMON=(-DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake -DCMAKE_BUILD_TYPE=Release
        -DANDROID_ABI=arm64-v8a -DANDROID_NATIVE_API_LEVEL=android-26 -DMNN_USE_SSE=OFF
        -DMNN_BUILD_FOR_ANDROID_COMMAND=true -DMNN_LOW_MEMORY=ON -DMNN_BUILD_DIFFUSION=ON -DMNN_BUILD_LLM=ON
        -DMNN_BUILD_OPENCV=ON -DMNN_IMGCODECS=ON -DMNN_OPENCL=ON -DMNN_SEP_BUILD=OFF
        -DMNN_SUPPORT_TRANSFORMER_FUSE=ON -DNATIVE_LIBRARY_OUTPUT=. -DNATIVE_INCLUDE_OUTPUT=.)

# 1) library for the AAR: shared STL, logs to logcat
LIB_BUILD="${MNN_ANDROID_LIB_BUILD:-$ROOT/build/android/mnn-lib}"
mkdir -p "$LIB_BUILD" && cd "$LIB_BUILD"
cmake "$MNN" "${COMMON[@]}" -DANDROID_STL=c++_shared -DMNN_USE_LOGCAT=ON
make -j8 MNN
cp libMNN.so "$ROOT/qwenimage21/src/main/jniLibs/arm64-v8a/"
cp -R "$MNN/include/MNN" "$ROOT/qwenimage21/src/main/cpp/include/"
cp "$MNN"/transformers/diffusion/engine/include/diffusion/{diffusion.hpp,qwen_image21_diffusion.hpp} \
   "$ROOT/qwenimage21/src/main/cpp/include/diffusion/"
mkdir -p "$ROOT/qwenimage21/src/main/cpp/include/llm"
cp "$MNN"/transformers/llm/engine/include/llm/llm.hpp "$ROOT/qwenimage21/src/main/cpp/include/llm/"

# 2) command-line demo for adb (static STL, stdout logs)
CLI_BUILD="${MNN_ANDROID_CLI_BUILD:-$ROOT/build/android/mnn-cli}"
mkdir -p "$CLI_BUILD" && cd "$CLI_BUILD"
cmake "$MNN" "${COMMON[@]}" -DANDROID_STL=c++_static -DMNN_USE_LOGCAT=OFF
make -j8 qwen_image21_demo
echo "CLI: $CLI_BUILD/{qwen_image21_demo,libMNN.so}"
