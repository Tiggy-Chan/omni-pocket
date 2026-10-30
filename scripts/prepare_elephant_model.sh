#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/build/elephant-1"
OUTPUT="$ROOT/models/llm/elephant-1"
DOWNLOAD_ONLY=0
if [[ "${1:-}" == "--download-only" ]]; then
    DOWNLOAD_ONLY=1
elif (( $# != 0 )); then
    echo "usage: $0 [--download-only]" >&2
    exit 2
fi
for tool in curl python3; do
    command -v "$tool" >/dev/null 2>&1 || { echo "required tool not found: $tool" >&2; exit 1; }
done
mkdir -p "$WORK"
python3 - "$ROOT" <<'PY'
import shutil, sys
available = shutil.disk_usage(sys.argv[1]).free
assert available >= 10_000_000_000, f'insufficient disk for conversion: {available:,} bytes free'
PY

verify_file() {
    python3 - "$1" "$2" "$3" <<'PY'
import hashlib, os, sys
path, size, expected = sys.argv[1:]
assert os.path.isfile(path) and os.path.getsize(path) == int(size), f'incomplete: {path}'
if expected:
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    assert digest.hexdigest() == expected, f'SHA-256 mismatch: {path}'
PY
}

download() {
    local url="$1" target="$2" size="$3" sha256="$4"
    if [[ -f "$target" ]] && verify_file "$target" "$size" "$sha256" 2>/dev/null; then
        printf 'Verified %s\n' "${target#$ROOT/}"
        return
    fi
    local part="$target.part"
    mkdir -p "$(dirname "$target")"
    if [[ -s "$part" ]]; then
        printf 'Resuming %s\n' "${target#$ROOT/}"
        if ! curl --fail --location --retry 3 --retry-all-errors --connect-timeout 30 --max-time 7200 \
            --header 'Accept-Encoding: identity' --continue-at - --output "$part" "$url"; then
            rm -f "$part"
        fi
    fi
    if [[ ! -f "$target" && ! -s "$part" ]]; then
        printf 'Downloading %s\n' "${target#$ROOT/}"
        curl --fail --location --retry 3 --retry-all-errors --connect-timeout 30 --max-time 7200 \
            --header 'Accept-Encoding: identity' --output "$part" "$url"
    fi
    if ! verify_file "$part" "$size" "$sha256"; then
        rm -f "$part"
        echo "download verification failed: $url" >&2
        exit 1
    fi
    mv "$part" "$target"
}

GRAPH='https://huggingface.co/taobao-mnn/Qwen3.5-9B-MNN/resolve/149e1e052267ffa377b87e3bb0ddb75f46217a9a'
SOURCE='https://huggingface.co/baselabs/elephant-1/resolve/b3f8a93462fb1051a9de031e94a7ff380e254072'
GGUF='https://huggingface.co/prithivMLmods/elephant-1-GGUF/resolve/77d938a13309d7b3da168d87abf5589f9329b122/elephant-1.Q4_K_M.gguf'
download "$GRAPH/config.json" "$WORK/config.json" 652 ''
download "$GRAPH/llm_config.json" "$WORK/llm_config.json" 8582 ''
download "$GRAPH/llm.mnn" "$WORK/llm.mnn" 3651096 fe9101e1b1754c304bef2091114c260cffbbac0ca6180110af0a04efd7f24c13
download "$GRAPH/llm.mnn.json" "$WORK/llm.mnn.json" 9172377 ''
download "$GRAPH/tokenizer.txt" "$WORK/tokenizer.txt" 6465727 7e75de1f279a10b65bd9dc1a5207205cb8993823861c4c42bbbd74e48e1c23a4
download "$GRAPH/visual.mnn" "$WORK/visual.mnn" 547632 f85e3ea5a2b51f267d8d8a857cc05da4ea2ae1f5fc1f98ec676d8130f31df748
download "$GRAPH/visual.mnn.weight" "$WORK/visual.mnn.weight" 258831244 3c9988e012c35b1d22e03412f316b2dc24e993ac8b149b3ad2b4095917f3c90c
download "$SOURCE/chat_template.jinja" "$WORK/chat_template.jinja" 7756 a4aee8afcf2e0711942cf848899be66016f8d14a889ff9ede07bca099c28f715
download "$SOURCE/readout.json" "$WORK/readout.json" 63 ''
download "$SOURCE/config.json" "$WORK/source_config.json" 3146 ''
download "$GGUF" "$WORK/elephant-1.Q4_K_M.gguf" 5629109376 764ef1834ffe7721f11ff7ed2ba42c3616140493b7505e2928eee057496c95a4

if (( DOWNLOAD_ONLY )); then
    printf 'Elephant sources are ready in %s\n' "$WORK"
    exit 0
fi

command -v uv >/dev/null 2>&1 || { echo 'uv is required for the isolated converter environment' >&2; exit 1; }
if [[ ! -x "$WORK/venv/bin/python" ]]; then
    UV_CACHE_DIR="$WORK/uv-cache" UV_PYTHON_INSTALL_DIR="$WORK/uv-python" \
        uv venv --python 3.12 "$WORK/venv"
fi
UV_CACHE_DIR="$WORK/uv-cache" uv pip install --python "$WORK/venv/bin/python" numpy
CONVERTER="${MNNCONVERT:-$ROOT/build/mnnconvert-host/MNNConvert}"
if [[ ! -x "$CONVERTER" ]]; then
    bash "$ROOT/scripts/build_mnnconvert_mac.sh"
fi
PYTHONPATH="$ROOT/third_party/MNN/transformers/llm/export" \
    "$WORK/venv/bin/python" "$ROOT/export/elephant_convert.py" \
    --source "$WORK" --output "$OUTPUT" --mnnconvert "$CONVERTER"
