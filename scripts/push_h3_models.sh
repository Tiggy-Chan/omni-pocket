#!/bin/bash
set -euo pipefail

# Pushes locally downloaded MiniMax-H3 Pruned (FL2VA Q2_K) + pi-H3 shared companions
# directly into Omni Pocket's Model Hub storage and registers it in registry.json.
#
# Usage:
#   SRC_DIR=/path/to/minimax_h3_models bash scripts/push_h3_models.sh [adb-serial]

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${SRC_DIR:-$ROOT/models/minimax_h3}"
APP_ID="${OMNI_APP_ID:-com.tiggy.omnipocket}"
HUB_DIR="/sdcard/Android/data/$APP_ID/files/models/hub"
COMP_DIR="$HUB_DIR/_companions"
MODEL_ID="minimax-h3-unsloth-minimax-h3-gguf-minimax_h3_fl2va_pruned-q2_k"
DIT_FILE="minimax_h3_fl2va_pruned-Q2_K.gguf"
ADB=("${ADB_BIN:-adb}")

if [ "$#" -gt 1 ]; then
    echo "usage: SRC_DIR=<dir> $0 [adb serial]" >&2
    exit 2
fi
if [ "$#" -eq 1 ]; then
    ADB+=(-s "$1")
fi

hash_file() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d ' ' -f 1
    else
        sha256sum "$1" | cut -d ' ' -f 1
    fi
}

file_size() {
    stat -f%z "$1" 2>/dev/null || stat -c%s "$1"
}

upload_file() {
    local local_file="$1"
    local remote="$2"
    local expected_sha="${3:-}"
    local temporary="$remote.uploading"
    local local_size remote_size local_hash

    if [ ! -f "$local_file" ]; then
        echo "missing local file: $local_file" >&2
        exit 1
    fi
    local_size=$(file_size "$local_file")
    if [ -n "$expected_sha" ]; then
        local_hash=$(hash_file "$local_file")
        if [ "$local_hash" != "$expected_sha" ]; then
            echo "unexpected sha256 for $local_file: $local_hash (expected $expected_sha)" >&2
            exit 1
        fi
    fi

    remote_size=$("${ADB[@]}" shell "stat -c %s '$remote' 2>/dev/null || echo 0" | tr -d '\r')
    if [ "$remote_size" = "$local_size" ]; then
        echo "skip $(basename "$remote") ($local_size bytes)"
        return
    fi

    echo "push $(basename "$remote") ($local_size bytes)"
    "${ADB[@]}" push "$local_file" "$temporary"
    "${ADB[@]}" shell "chmod 644 '$temporary' && mv -f '$temporary' '$remote'"
}

"${ADB[@]}" shell "mkdir -p '$COMP_DIR' '$HUB_DIR/$MODEL_ID'"

upload_file "$SRC/pi_h3_2step_adapter.safetensors" \
    "$COMP_DIR/pi_h3_2step_adapter.safetensors" \
    "2602688b12e2763fdbc1ad7e0c626b83942fbda511050f266a49dbcdc1db5f02"

upload_file "$SRC/pi_h3_4step_adapter.safetensors" \
    "$COMP_DIR/pi_h3_4step_adapter.safetensors" \
    "2dcdbb193f08755e0efb5f3b07585a988e30eb1c38c25283d30635ccf3d6e07f"

upload_file "$SRC/minimax_h3_audio_vae_fp32.safetensors" \
    "$COMP_DIR/minimax_h3_audio_vae_fp32.safetensors" \
    "8e505d95dd1561d47abd43d4238fd40d9bb1ae9e147ed0a4cba778d76ae4db48"

upload_file "$SRC/minimax_h3_video_vae_int8_convrot.safetensors" \
    "$COMP_DIR/minimax_h3_video_vae_int8_convrot.safetensors" \
    "52a2c8c73583c86e4f41cdcce3a6ad0ea562987bc0bf3d60a0cef5f5c8e60c0e"

upload_file "$SRC/qwen3vl-32B-MiniMax-H3-Q2_K.gguf" \
    "$COMP_DIR/qwen3vl-32B-MiniMax-H3-Q2_K.gguf" \
    "5bbc11d0b3ef197c98df2ce8f05de8fbb8eb5917cd91c33d0b59f93759b34914"

upload_file "$SRC/$DIT_FILE" "$HUB_DIR/$MODEL_ID/$DIT_FILE"

DIT_SIZE=$(file_size "$SRC/$DIT_FILE")
TMP_REG=$(mktemp)
trap 'rm -f "$TMP_REG"' EXIT

"${ADB[@]}" shell "cat '$HUB_DIR/registry.json' 2>/dev/null" > "$TMP_REG" || true

python3 - "$TMP_REG" "$MODEL_ID" "$DIT_FILE" "$DIT_SIZE" <<'PY'
import json, sys, time
reg_path, model_id, dit_file, dit_size_str = sys.argv[1:5]
dit_size = int(dit_size_str)
try:
    with open(reg_path, "r", encoding="utf-8") as f:
        data = json.load(f)
except Exception:
    data = {"schemaVersion": 1, "models": []}
models = [m for m in data.get("models", []) if m.get("id") != model_id]
files = [
    {"path": dit_file, "size": dit_size},
    {"path": "qwen3vl-32B-MiniMax-H3-Q2_K.gguf", "size": 8487968160, "sha256": "5bbc11d0b3ef197c98df2ce8f05de8fbb8eb5917cd91c33d0b59f93759b34914", "sharedKey": "qwen3vl-32B-MiniMax-H3-Q2_K.gguf"},
    {"path": "vae/minimax_h3_video_vae_int8_convrot.safetensors", "size": 2811065184, "sha256": "52a2c8c73583c86e4f41cdcce3a6ad0ea562987bc0bf3d60a0cef5f5c8e60c0e", "sharedKey": "minimax_h3_video_vae_int8_convrot.safetensors"},
    {"path": "vae/minimax_h3_audio_vae_fp32.safetensors", "size": 605254808, "sha256": "8e505d95dd1561d47abd43d4238fd40d9bb1ae9e147ed0a4cba778d76ae4db48", "sharedKey": "minimax_h3_audio_vae_fp32.safetensors"},
    {"path": "pi_h3_2step_adapter/diffusion_pytorch_model.safetensors", "size": 503383808, "sha256": "2602688b12e2763fdbc1ad7e0c626b83942fbda511050f266a49dbcdc1db5f02", "sharedKey": "pi_h3_2step_adapter.safetensors"},
    {"path": "pi_h3_4step_adapter/diffusion_pytorch_model.safetensors", "size": 503383808, "sha256": "2dcdbb193f08755e0efb5f3b07585a988e30eb1c38c25283d30635ccf3d6e07f", "sharedKey": "pi_h3_4step_adapter.safetensors"},
]
models.append({
    "id": model_id,
    "name": "MiniMax-H3-GGUF · Q2_K",
    "repo": "unsloth/MiniMax-H3-GGUF",
    "revision": "main",
    "type": "video_wan",
    "entryFile": dit_file,
    "totalBytes": sum(x["size"] for x in files),
    "quantTag": "Q2_K",
    "installedAt": int(time.time() * 1000),
    "metadata": {
        "arch": "minimax_h3",
        "h3Mode": "fl2va",
        "llmCompanion": "qwen3vl-32B-MiniMax-H3-Q2_K.gguf",
        "vaeCompanion": "minimax_h3_video_vae_int8_convrot.safetensors",
        "audioVaeCompanion": "minimax_h3_audio_vae_fp32.safetensors",
        "piH3Adapter2Step": "pi_h3_2step_adapter.safetensors",
        "piH3Adapter4Step": "pi_h3_4step_adapter.safetensors",
        "defaultSteps": "2",
        "defaultCfg": "1.0",
        "defaultShift": "12.0",
        "defaultFps": "24",
        "defaultFrames": "22",
        "defaultWidth": "480",
        "defaultHeight": "288",
    },
    "files": files,
})
with open(reg_path, "w", encoding="utf-8") as f:
    json.dump({"schemaVersion": 1, "models": models}, f, indent=2)
PY

"${ADB[@]}" push "$TMP_REG" "$HUB_DIR/registry.json.tmp"
"${ADB[@]}" shell "chmod 644 '$HUB_DIR/registry.json.tmp' && mv -f '$HUB_DIR/registry.json.tmp' '$HUB_DIR/registry.json'"
echo "done -> $HUB_DIR/$MODEL_ID (registered in registry.json)"
