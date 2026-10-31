#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/models/yue2/Yue2-3B-GGUF"
DST=/sdcard/Android/data/com.tiggy.qwenimage/files/models/yue2
HF_BASE="https://huggingface.co/audio-cpp/Yue2-3B-GGUF/resolve/main"
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

# Downloads a missing file from audio-cpp/Yue2-3B-GGUF, resuming partial downloads.
fetch_file() {
    local name="$1"
    local local_file="$SRC/$name"
    [ -f "$local_file" ] && return
    mkdir -p "$(dirname "$local_file")"
    echo "download $name"
    curl -fL --retry 5 -C - -o "$local_file.part" "$HF_BASE/$name"
    mv "$local_file.part" "$local_file"
}

upload_file() {
    local name="$1"
    local expected="$2"
    local local_file="$SRC/$name"
    local remote="$DST/$name"
    local temporary="$remote.uploading"
    local local_hash
    local remote_hash
    local uploaded_hash

    fetch_file "$name"
    local_hash=$(hash_file "$local_file")
    if [ "$local_hash" != "$expected" ]; then
        echo "unexpected sha256 for $local_file: $local_hash" >&2
        exit 1
    fi
    remote_hash=$("${ADB[@]}" shell "sha256sum '$remote' 2>/dev/null" | tr -d '\r' | cut -d ' ' -f 1 || true)
    if [ "$local_hash" = "$remote_hash" ]; then
        echo "skip $name"
        return
    fi

    echo "push $name"
    "${ADB[@]}" shell "mkdir -p '$(dirname "$remote")'"
    "${ADB[@]}" push "$local_file" "$temporary"
    "${ADB[@]}" shell "chmod 644 '$temporary'"
    uploaded_hash=$("${ADB[@]}" shell "sha256sum '$temporary'" | tr -d '\r' | cut -d ' ' -f 1)
    if [ "$local_hash" != "$uploaded_hash" ]; then
        "${ADB[@]}" shell "rm -f '$temporary'"
        echo "hash mismatch after upload: $name" >&2
        exit 1
    fi
    "${ADB[@]}" shell "mv -f '$temporary' '$remote'"
}

"${ADB[@]}" shell mkdir -p "$DST/sidecars"
"${ADB[@]}" shell chmod 755 "$DST"

# YuE2 weights are CC BY-NC 4.0 (non-commercial). Hashes match audio-cpp/Yue2-3B-GGUF.
upload_file sidecars/yue2-generation-config.json 203830cebde6e3644eb291925362990d198e66c3b6006cd11f1aaa21904bcc61
upload_file sidecars/yue2-model-config.json ad3477bbef890bf98ae196c1e4b44779494a6231c4ab66f32708eabadf265329
upload_file sidecars/yue2-qwen.tiktoken b2b1b8dfb5cc5f024bafc373121c6aba3f66f9a5a0269e243470a1de16a33186
upload_file sidecars/yue2-vae-config.json f0191bb9694009956de44e0c361a6f1334760be4c8f848e599bde242a54a0970
upload_file yue2-vae-f16.gguf d4f4a05d8f291ae820cd1e43609da3fa91b56465810091a2b08c3350b751719d
upload_file yue2-3b-q4_0.gguf b80215b59a7fe41a4c76b6163c0ae1031672f892b5951d66fefaa2053a815029

echo "done -> $DST"
