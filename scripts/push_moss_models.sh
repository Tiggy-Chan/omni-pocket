#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/models/moss_image"
DST=/sdcard/Android/data/com.tiggy.qwenimage/files/models/moss_image
ADB=(adb)

if [ "$#" -gt 1 ]; then
    echo "usage: $0 [adb serial]" >&2
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

upload_file() {
    local local_file="$1"
    local name="$2"
    local remote="$DST/$name"
    local temporary="$remote.uploading"
    local local_hash
    local remote_hash
    local uploaded_hash

    if [ ! -f "$local_file" ]; then
        echo "missing local model file: $local_file" >&2
        exit 1
    fi

    local_hash=$(hash_file "$local_file")
    remote_hash=$("${ADB[@]}" shell "sha256sum '$remote' 2>/dev/null" | tr -d '\r' | cut -d ' ' -f 1 || true)
    if [ "$local_hash" = "$remote_hash" ]; then
        echo "skip $name"
        return
    fi

    echo "push $name"
    cat "$local_file" | "${ADB[@]}" shell "cat > '$temporary'"
    "${ADB[@]}" shell "chmod 644 '$temporary'"
    uploaded_hash=$("${ADB[@]}" shell "sha256sum '$temporary'" | tr -d '\r' | cut -d ' ' -f 1)
    if [ "$local_hash" != "$uploaded_hash" ]; then
        "${ADB[@]}" shell "rm -f '$temporary'"
        echo "hash mismatch after upload: $name" >&2
        exit 1
    fi
    "${ADB[@]}" shell "mv -f '$temporary' '$remote'"
}

"${ADB[@]}" shell mkdir -p "$DST"
"${ADB[@]}" shell chmod 755 "$DST"

for name in \
    MOSS-Image-SFT-Q4_0.gguf \
    Qwen3.5-9B-Q4_K_M.gguf \
    flux2-vae_small.safetensors \
    tokenizer.json
do
    upload_file "$SRC/$name" "$name"
done

upload_file "$ROOT/app/src/main/assets/moss_image.json" manifest.json

echo "done -> $DST"
