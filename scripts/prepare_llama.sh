#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/third_party/llama.cpp"
PATCH="$ROOT/patches/llama-elephant-decision.patch"
EXPECTED="6d8a8886fa44a4a404c58ace93052ea680193981"
[[ "$(git -C "$SOURCE" rev-parse HEAD)" == "$EXPECTED" ]] || {
    echo "llama.cpp must be at $EXPECTED" >&2
    exit 1
}
if git -C "$SOURCE" apply --check "$PATCH" >/dev/null 2>&1; then
    git -C "$SOURCE" apply "$PATCH"
elif git -C "$SOURCE" apply --reverse --check "$PATCH" >/dev/null 2>&1; then
    echo "llama.cpp decision patch already applied"
else
    echo "llama.cpp checkout is incompatible with decision patch" >&2
    exit 1
fi

# Stage Arm KleidiAI v1.32.0 sources once so Gradle/CMake can enable GGML_CPU_KLEIDIAI offline.
KLEIDIAI_TAG="v1.32.0"
KLEIDIAI_MD5="05f1953428ffe8e8c7f585dc6d508887"
KLEIDIAI_DIR="$ROOT/build/deps/kleidiai-src"
KLEIDIAI_TAR="$ROOT/build/deps/kleidiai-${KLEIDIAI_TAG}-src.tar.gz"
if [[ ! -f "$KLEIDIAI_DIR/CMakeLists.txt" ]]; then
    mkdir -p "$ROOT/build/deps"
    if [[ ! -f "$KLEIDIAI_TAR" ]]; then
        curl -fSL --retry 2 --retry-delay 2 --connect-timeout 10 \
            -o "$KLEIDIAI_TAR.part" \
            "https://github.com/ARM-software/kleidiai/releases/download/${KLEIDIAI_TAG}/kleidiai-${KLEIDIAI_TAG}-src.tar.gz" \
            && mv "$KLEIDIAI_TAR.part" "$KLEIDIAI_TAR" \
            || rm -f "$KLEIDIAI_TAR.part"
    fi
    if [[ -f "$KLEIDIAI_TAR" ]]; then
        ACTUAL_MD5="$(md5 -q "$KLEIDIAI_TAR" 2>/dev/null || md5sum "$KLEIDIAI_TAR" | awk '{print $1}')"
        if [[ "$ACTUAL_MD5" == "$KLEIDIAI_MD5" ]]; then
            mkdir -p "$KLEIDIAI_DIR"
            tar -xzf "$KLEIDIAI_TAR" -C "$KLEIDIAI_DIR" --strip-components=1
            echo "Arm KleidiAI ${KLEIDIAI_TAG} staged at $KLEIDIAI_DIR"
        else
            echo "Warning: KleidiAI archive MD5 mismatch ($ACTUAL_MD5); removing archive" >&2
            rm -f "$KLEIDIAI_TAR"
        fi
    fi
fi
