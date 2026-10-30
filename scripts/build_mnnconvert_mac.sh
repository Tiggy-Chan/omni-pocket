#!/bin/bash
# Host MNNConvert (+ qwen_image21_demo for CPU testing on the host).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MNN="$ROOT/third_party/MNN"
source "$ROOT/scripts/apply_mnn_patch.sh"
apply_mnn_patch "$MNN" "$ROOT/patches/mnn-qwen-image-official-turbo.patch"
apply_mnn_patch "$MNN" "$ROOT/patches/mnn-elephant-decision.patch"
BUILD_DIR="${MNNCONVERT_BUILD_DIR:-$ROOT/build/mnnconvert-host}"
cmake -S "$MNN" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=Release -DMNN_BUILD_CONVERTER=ON -DMNN_BUILD_LLM=ON -DMNN_BUILD_DIFFUSION=ON \
    -DMNN_LOW_MEMORY=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON -DMNN_BUILD_OPENCV=ON -DMNN_IMGCODECS=ON -DMNN_SEP_BUILD=OFF
cmake --build "$BUILD_DIR" --target MNNConvert qwen_image21_demo --parallel "${JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || nproc)}"
