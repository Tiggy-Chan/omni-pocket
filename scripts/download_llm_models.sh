#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL_ID="${1:-qwen35-0.8b}"
CATALOG="$ROOT/app/src/main/assets/llm_models.json"
for tool in curl python3; do
    command -v "$tool" >/dev/null 2>&1 || { echo "required tool not found: $tool" >&2; exit 1; }
done
[[ "$MODEL_ID" == "qwen35-0.8b" || "$MODEL_ID" == "qwen35-2b" || "$MODEL_ID" == "qwen35-4b" ]] || {
    echo "usage: $0 [qwen35-0.8b|qwen35-2b|qwen35-4b]" >&2
    exit 2
}

FILE_LIST="$ROOT/build/llm-download-$MODEL_ID.tsv"
mkdir -p "$ROOT/build"
python3 - "$CATALOG" "$MODEL_ID" "$FILE_LIST" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as source:
    catalog = json.load(source)
models = [model for model in catalog['models'] if model['id'] == sys.argv[2]]
assert len(models) == 1, 'unknown model'
with open(sys.argv[3], 'w', encoding='utf-8') as output:
    for file in models[0]['files']:
        assert '/' not in file['path'] and file['path'] not in ('', '.', '..')
        output.write('\t'.join((file['path'], str(file['size']), file['sha256'], file['url'])) + '\n')
PY
ROOT_DIR="$ROOT/models/llm"
TARGET="$ROOT_DIR/$MODEL_ID"
STAGING="$ROOT_DIR/.$MODEL_ID.downloading"
BACKUP="$ROOT_DIR/.$MODEL_ID.backup"
mkdir -p "$STAGING"
python3 - "$STAGING" "$FILE_LIST" <<'PY'
import os, shutil, sys
remaining = 0
with open(sys.argv[2], encoding='utf-8') as source:
    for line in source:
        path, size, digest, _ = line.rstrip('\n').split('\t')
        target = os.path.join(sys.argv[1], path)
        part = target + '.part'
        if os.path.isfile(target) and os.path.getsize(target) == int(size):
            continue
        remaining += int(size) - (os.path.getsize(part) if os.path.isfile(part) else 0)
available = shutil.disk_usage(sys.argv[1]).free
assert available >= remaining + 512 * 1024 * 1024, f'insufficient disk: need {remaining:,} bytes plus 512 MiB reserve'
PY

CURRENT_PART=""
CURRENT_NEXT=""
cleanup() {
    if [[ -n "$CURRENT_NEXT" ]]; then rm -f "$CURRENT_NEXT" "$CURRENT_NEXT.headers"; fi
}
trap cleanup EXIT

while IFS=$'\t' read -r path size sha256 url; do
    target="$STAGING/$path"
    if [[ -f "$target" ]] && python3 - "$target" "$size" "$sha256" <<'PY'
import hashlib, os, sys
path, size, expected = sys.argv[1:]
if os.path.getsize(path) != int(size):
    raise SystemExit(1)
digest = hashlib.sha256()
with open(path, 'rb') as source:
    for chunk in iter(lambda: source.read(1024 * 1024), b''):
        digest.update(chunk)
raise SystemExit(digest.hexdigest() != expected)
PY
    then
        printf 'Verified %s\n' "$path"
        continue
    fi
    part="$target.part"
    next="$target.next"
    CURRENT_PART="$part"
    CURRENT_NEXT="$next"
    rm -f "$next" "$next.headers"
    if [[ -f "$part" && ! -f "$part.resume" ]]; then
        printf 'Removing untracked partial file: %s\n' "$path" >&2
        rm -f "$part"
    fi
    if [[ -f "$part" && "$(< "$part.resume")" != "$sha256" ]]; then
        rm -f "$part" "$part.resume"
    fi
    printf '%s' "$sha256" > "$part.resume"
    have=0
    if [[ -f "$part" ]]; then
        have="$(stat -f %z "$part")"
        if (( have > size )); then
            rm -f "$part"
            have=0
        fi
    fi
    if (( have == size )); then
        response=206
        : > "$next"
    elif (( have > 0 )); then
        printf 'Resuming %s at %d bytes\n' "$path" "$have"
        response="$(curl --fail --location --retry 3 --connect-timeout 30 --max-time 7200 \
            --header 'Accept-Encoding: identity' --range "$have-" \
            --dump-header "$next.headers" --output "$next" --write-out '%{http_code}' "$url")"
    else
        printf 'Downloading %s (%s bytes)\n' "$path" "$size"
        response="$(curl --fail --location --retry 3 --connect-timeout 30 --max-time 7200 \
            --header 'Accept-Encoding: identity' \
            --dump-header "$next.headers" --output "$next" --write-out '%{http_code}' "$url")"
    fi
    if [[ "$response" == "206" && "$have" -lt "$size" ]]; then
        python3 - "$next.headers" "$have" "$size" <<'PY'
import email, sys
with open(sys.argv[1], encoding='iso-8859-1') as source:
    blocks = source.read().split('\r\n\r\n')
headers = email.message_from_string(blocks[-2] if len(blocks) > 1 else blocks[0])
value = headers.get('Content-Range', '')
import re
match = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+|\*)', value)
assert match, f'invalid Content-Range: {value!r}'
start, end, total = int(match[1]), int(match[2]), match[3]
assert start == int(sys.argv[2]) and start <= end < int(sys.argv[3]), f'wrong range: {value}'
assert total == '*' or int(total) == int(sys.argv[3]), f'wrong total: {value}'
PY
        cat "$next" >> "$part"
    elif [[ "$response" == "200" ]]; then
        mv "$next" "$part"
    elif (( have != size )); then
        echo "unexpected HTTP $response for $path" >&2
        exit 1
    fi
    python3 - "$part" "$size" "$sha256" <<'PY'
import hashlib, os, sys
path, size, expected = sys.argv[1:]
assert os.path.getsize(path) == int(size), 'incomplete download; rerun to resume'
digest = hashlib.sha256()
with open(path, 'rb') as source:
    for chunk in iter(lambda: source.read(1024 * 1024), b''):
        digest.update(chunk)
assert digest.hexdigest() == expected, 'SHA-256 mismatch; delete the .part file before retrying'
PY
    mv "$part" "$target"
    rm -f "$part.resume" "$next" "$next.headers"
    CURRENT_PART=""
    CURRENT_NEXT=""
done < "$FILE_LIST"

python3 - "$CATALOG" "$MODEL_ID" "$STAGING/manifest.json" <<'PY'
import json, os, sys
with open(sys.argv[1], encoding='utf-8') as source:
    catalog = json.load(source)
model = next(model for model in catalog['models'] if model['id'] == sys.argv[2])
temporary = sys.argv[3] + '.tmp'
with open(temporary, 'w', encoding='utf-8') as output:
    json.dump({'schemaVersion': 1, 'models': [model]}, output, ensure_ascii=False, indent=2)
    output.write('\n')
os.replace(temporary, sys.argv[3])
PY
if [[ -e "$BACKUP" ]]; then rm -rf "$BACKUP"; fi
if [[ -e "$TARGET" ]]; then mv "$TARGET" "$BACKUP"; fi
if ! mv "$STAGING" "$TARGET"; then
    if [[ -e "$BACKUP" && ! -e "$TARGET" ]]; then mv "$BACKUP" "$TARGET"; fi
    exit 1
fi
if [[ -e "$BACKUP" ]]; then rm -rf "$BACKUP"; fi
printf 'Ready: %s\nRun: build/llm-host/omni_llm_smoke %s %s\n' \
    "$TARGET/config.json" "$TARGET/config.json" "$ROOT/build/llm-runtime-cache/$MODEL_ID-cpu"
