#!/bin/bash
# Build the official Qwen/Qwen-Image-2.1-Turbo DiT for the Android MNN runtime.
#
# The official release is BF16 Safetensors.  For a phone-sized conversion we
# use the Q4_K_M GGUF published from that exact official checkpoint by the same
# upstream owner, then convert only the 7B transformer to MNN int4 (auxiliary
# projections int8, norms/fixed tensors fp16).  The output can be copied into
# models/qwen_image21_uc_q4km beside dit.mnn; the app selects it through the
# official-turbo feature flag.
#
#   scripts/convert_official_turbo.sh
#
# Requires: `hf`, Python packages in export/requirements.txt, and
# third_party/MNN/build_host/MNNConvert.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY="${PYTHON:-python3}"
export MNNCONVERT="${MNNCONVERT:-$ROOT/third_party/MNN/build_host/MNNConvert}"
SRC="${SRC:-$ROOT/models/src/qwen-image-2.1-turbo}"
OUT="${OUT:-$ROOT/models/qwen_image21_uc_q4km}"
REVISION="${QWEN_TURBO_REVISION:-main}"
SOURCE_REPO="${QWEN_TURBO_SOURCE_REPO:-abenzerps/Qwen-Image-2.1-Turbo-Quantized}"
SOURCE_GGUF="${QWEN_TURBO_GGUF:-qwen-image-2.1-turbo-Q4_K_M.gguf}"
GGUF_PATH="${QWEN_TURBO_GGUF_PATH:-$SRC/gguf/$SOURCE_GGUF}"

mkdir -p "$SRC/gguf" "$OUT"

# Q4_K_M is about 4.05 GB and is derived from Qwen/Qwen-Image-2.1-Turbo.
# If another workstation/agent already downloaded it, use that file directly.
if [ ! -f "$GGUF_PATH" ]; then
  hf download "$SOURCE_REPO" "$SOURCE_GGUF" \
    --revision "$REVISION" --local-dir "$SRC/gguf"
fi
test -s "$GGUF_PATH"

"$PY" "$ROOT/export/export_mnn.py" \
  --gguf "$GGUF_PATH" \
  --out "$OUT" \
  --only dit \
  --bits 4 \
  --aux_bits 8 \
  --block 64 \
  --fuse 0 \
  --dit_name dit_official_turbo.mnn

rm -rf "$OUT/onnx"

"$PY" "$ROOT/tools/model-convert/make_manifest.py" \
  --root "$OUT" \
  --model-id qwen-image-2.1-uc-q4km \
  --source-repo abenzerps/Qwen-Image-2.1-Uncensored-GGUF \
  --revision main \
  --features text-to-image,image-edit,tiny-vae,official-turbo \
  --official-turbo-source-repo "$SOURCE_REPO" \
  --official-turbo-revision "$REVISION" \
  --official-turbo-schedule 1.0,0.978453,0.95418,0.926626,0.89508,0.845148,0.704534,0.414568 \
  --official-turbo-validated

du -sh "$OUT"
