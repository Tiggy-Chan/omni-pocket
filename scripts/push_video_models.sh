#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/models/wan22_turbo"
DST=/sdcard/Android/data/com.tiggy.qwenimage/files/models/wan22_turbo
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
    local name="$1"
    local expected="$2"
    local local_file="$SRC/$name"
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

"${ADB[@]}" shell mkdir -p "$DST"
"${ADB[@]}" shell chmod 755 "$DST"

# Hashes match the public Hugging Face files listed in README "Wan model preparation".
upload_file taew2_2.safetensors 5243c5c9d77ecf2d74800d672bac3678c0d72462899f1b3b10aa1bbc11eae461
upload_file umt5-xxl-encoder-Q3_K_S.gguf f64f8d6dc4d8a24276df69d0ccea789aae686f7417950a41e6568c30cb478a5c
upload_file Wan2_2-TI2V-5B-Turbo-Q4_0.gguf 8aa3a98c254bd02a9cc7e4f1db37720fe291cef8b0c32fb78e7a2391125d6b28

echo "done -> $DST"
