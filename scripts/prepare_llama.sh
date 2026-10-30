#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/third_party/llama.cpp"
PATCH="$ROOT/patches/llama-elephant-decision.patch"
EXPECTED="2280f0fd163b03d4331d49e08460f66502e35854"
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
