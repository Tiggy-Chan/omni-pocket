#!/bin/bash
# Rebuild the uncensored MNN model directory from public sources. The phone app cannot convert GGUF;
# run this on a workstation and either import the output directory or publish it with manifest.json.
#
#   QWEN_SOURCE_REPO=abenzerps/Qwen-Image-2.1-Uncensored-GGUF \
#   QWEN_GGUF=qwen-image-2.1-UC-Q4_K_M.gguf \
#   OUT=models/qwen_image21_uc_q4km scripts/convert_models.sh
#
# Needs: python env with export/requirements.txt, and MNNConvert (scripts/build_mnnconvert_mac.sh).
# Downloads roughly 10 GB: the selected DiT GGUF, the official VAE and the Qwen3-VL MNN package.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY="${PYTHON:-python3}"
export MNNCONVERT="${MNNCONVERT:-$ROOT/third_party/MNN/build_host/MNNConvert}"
SRC="${MODEL_SRC:-$ROOT/models/src}"
OUT="${OUT:-$ROOT/models/qwen_image21_uc_q4km}"
QWEN_SOURCE_REPO="${QWEN_SOURCE_REPO:-abenzerps/Qwen-Image-2.1-Uncensored-GGUF}"
QWEN_REVISION="${QWEN_REVISION:-main}"
QWEN_GGUF="${QWEN_GGUF:-qwen-image-2.1-UC-Q4_K_M.gguf}"
mkdir -p "$SRC" "$OUT/text_encoder"

hf download "$QWEN_SOURCE_REPO" "$QWEN_GGUF" --revision "$QWEN_REVISION" --local-dir "$SRC/gguf"
hf download Qwen/Qwen-Image-2.1 vae/config.json vae/diffusion_pytorch_model.safetensors --local-dir "$SRC/qwen-image-2.1"
hf download taobao-mnn/Qwen3-VL-8B-Instruct-MNN --local-dir "$SRC/qwen3-vl-8b-mnn"

# DiT: Q4_K -> MNN int4 (lossless, block 32), small layers int8.
# --fuse 0: MNN's fused Attention op gives wrong results for the prefix / step masks.
"$PY" "$ROOT/export/export_mnn.py" --gguf "$SRC/gguf/$QWEN_GGUF" --out "$OUT" --aux_bits 8 --fuse 0
# VAE decoder (residual stream rescaled for fp16) + encoder for image editing, fp16 weights, dynamic size
"$PY" "$ROOT/export/export_vae.py" --vae "$SRC/qwen-image-2.1/vae" --out "$OUT" --fp16 --parts decoder,encoder
rm -rf "$OUT/onnx" "$OUT/vae_check.pt" "$OUT/order.txt"

# Text encoder: stock Qwen3-VL-8B MNN files + configs that return the pre-norm last hidden state
for f in llm.mnn llm.mnn.weight embeddings_int4.bin tokenizer.txt llm_config.json visual.mnn visual.mnn.weight; do
    cp "$SRC/qwen3-vl-8b-mnn/$f" "$OUT/text_encoder/$f"
done
"$PY" "$ROOT/export/make_te_config.py" "$OUT/text_encoder"
"$PY" "$ROOT/tools/model-convert/make_manifest.py" \
    --root "$OUT" --model-id qwen-image-2.1-uc-q4km \
    --source-repo "$QWEN_SOURCE_REPO" --revision "$QWEN_REVISION" \
    --features text-to-image,image-edit,tiny-vae
du -sh "$OUT"
