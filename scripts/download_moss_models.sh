#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:-$ROOT/models/moss_image}"
HF_ROOT="${MOSS_HF_ROOT:-https://huggingface.co}"
mkdir -p "$DEST"

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

verified() {
    [ -f "$1" ] && [ "$(file_size "$1")" = "$2" ] && [ "$(hash_file "$1")" = "$3" ]
}

fetch() {
    local name="$1" size="$2" url="$3" sha="$4"
    local output="$DEST/$name" partial="$DEST/$name.part"
    local attempt=1 code
    if [ -f "$output" ]; then
        if verified "$output" "$size" "$sha"; then
            echo "ready $name"
            return
        fi
        echo "existing file failed size or SHA-256 validation: $output" >&2
        return 1
    fi
    if [ -f "$partial" ] && [ "$(file_size "$partial")" -gt "$size" ]; then
        echo "partial file exceeds expected size: $partial" >&2
        return 1
    fi
    while [ "$attempt" -le 20 ]; do
        if [ -f "$partial" ] && [ "$(file_size "$partial")" = "$size" ]; then
            if verified "$partial" "$size" "$sha"; then
                mv "$partial" "$output"
                echo "ready $name"
                return
            fi
            echo "complete partial file failed SHA-256 validation: $partial" >&2
            return 1
        fi
        echo "download $name · attempt $attempt"
        if curl -fL --http1.1 --silent --show-error --connect-timeout 30 \
            --speed-limit 1024 --speed-time 60 -C - "$url" -o "$partial"; then
            if verified "$partial" "$size" "$sha"; then
                mv "$partial" "$output"
                echo "ready $name"
                return
            fi
            echo "download failed size or SHA-256 validation: $name" >&2
            return 1
        else
            code=$?
        fi
        if [ "$code" -eq 33 ]; then
            echo "server does not support resume for $name" >&2
            return "$code"
        fi
        sleep $((attempt * 2 < 30 ? attempt * 2 : 30))
        attempt=$((attempt + 1))
    done
    echo "download remains incomplete: $name; rerun to resume" >&2
    return 1
}

TARGET="${2:-all}"
case "$TARGET" in
    all|sft|text|aux) ;;
    *) echo "usage: $0 [destination] [all|sft|text|aux]" >&2; exit 2 ;;
esac

if [ "$TARGET" = all ] || [ "$TARGET" = sft ]; then
    fetch MOSS-Image-SFT-Q4_0.gguf 3327400800 \
        "$HF_ROOT/leejet/MOSS-Image-SFT-GGUF/resolve/main/MOSS-Image-SFT-Q4_0.gguf" \
        b59d2461fa431d670471f74ab54fd90a0bfb36799fd3ffbec1cf103568a883b8
fi
if [ "$TARGET" = all ] || [ "$TARGET" = text ]; then
    fetch Qwen3.5-9B-Q4_K_M.gguf 5680522464 \
        "$HF_ROOT/unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_K_M.gguf" \
        03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8
fi
if [ "$TARGET" = all ] || [ "$TARGET" = aux ]; then
    fetch flux2-vae_small.safetensors 249521340 \
        "$HF_ROOT/Comfy-Org/MOSS-Image/resolve/main/vae/flux2-vae_small.safetensors" \
        d8d52ba036475f5fb07c8b435e176d3d97ebfa82f0d1a1c317f9cc1e25bd013b
    fetch tokenizer.json 19989325 \
        "$HF_ROOT/OpenMOSS-Team/MOSS-Image-Base/resolve/main/tokenizer/tokenizer.json" \
        06b9509352d2af50381ab2247e083b80d32d5c0aba91c272ca9ff729b6a0e523
fi
