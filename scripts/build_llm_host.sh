#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXPECTED="24c6cb21081a657c66db4ffe1e7a2c9d3d20e823"
[[ "$(git -C "$ROOT/third_party/MNN" rev-parse HEAD)" == "$EXPECTED" ]] || {
    echo "MNN must be at $EXPECTED" >&2
    exit 1
}
for tool in cmake git; do
    command -v "$tool" >/dev/null 2>&1 || { echo "required tool not found: $tool" >&2; exit 1; }
done
source "$ROOT/scripts/apply_mnn_patch.sh"
apply_mnn_patch "$ROOT/third_party/MNN" "$ROOT/patches/mnn-elephant-decision.patch"
bash "$ROOT/scripts/prepare_llama.sh"
BUILD_DIR="${LLM_HOST_BUILD_DIR:-$ROOT/build/llm-host}"
cmake -S "$ROOT/tools/llm-host" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=Release
cmake --build "$BUILD_DIR" --target omni_llm_smoke --parallel "${JOBS:-8}"
printf 'Omni LLM host smoke: %s\n' "$BUILD_DIR/omni_llm_smoke"
