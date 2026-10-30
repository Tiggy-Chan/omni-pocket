#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL_ID="${1:-qwen35-0.8b}"
case "$MODEL_ID" in
    qwen35-0.8b|qwen35-2b|qwen35-4b|elephant-1) ;;
    *) echo "usage: $0 [qwen35-0.8b|qwen35-2b|qwen35-4b|elephant-1]" >&2; exit 2 ;;
esac
command -v adb >/dev/null 2>&1 || { echo "adb not found" >&2; exit 1; }
LOCAL="$ROOT/models/llm/$MODEL_ID"
CATALOG="$ROOT/app/src/main/assets/llm_models.json"
mapfile_compatible_list="$(python3 - "$CATALOG" "$MODEL_ID" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as source:
    models = json.load(source)['models']
model = next((item for item in models if item['id'] == sys.argv[2]), None)
assert model is not None, 'unknown model'
for file in model['files']:
    assert '/' not in file['path']
    print(file['path'])
PY
)"
[[ -f "$LOCAL/manifest.json" ]] || { echo "missing pinned manifest; run scripts/download_llm_models.sh $MODEL_ID" >&2; exit 1; }
python3 - "$LOCAL" "$CATALOG" "$MODEL_ID" <<'PY'
import hashlib, json, os, sys
root, catalog_path, model_id = sys.argv[1:]
with open(catalog_path, encoding='utf-8') as source:
    expected = next(item for item in json.load(source)['models'] if item['id'] == model_id)
with open(os.path.join(root, 'manifest.json'), encoding='utf-8') as source:
    installed = json.load(source)
assert installed == {'schemaVersion': 1, 'models': [expected]}, 'manifest differs from bundled pinned catalog'
for file in expected['files']:
    path = os.path.join(root, file['path'])
    assert os.path.isfile(path) and os.path.getsize(path) == file['size'], f"incomplete: {file['path']}"
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    assert digest.hexdigest() == file['sha256'], f"SHA-256 mismatch: {file['path']}"
print('Local SHA-256 verification passed')
PY
[[ "$(adb get-state)" == "device" ]] || { echo "no ready Android device" >&2; exit 1; }
BASE="/sdcard/Android/data/com.tiggy.qwenimage/files/models/llm"
REMOTE="$BASE/$MODEL_ID"
STAGING="$BASE/.$MODEL_ID.uploading"
BACKUP="$BASE/.$MODEL_ID.backup"
adb shell mkdir -p "$STAGING"
adb shell rm -f "$STAGING"/* "$STAGING"/.[!.]* "$STAGING"/..?* 2>/dev/null || true
while IFS= read -r path; do
    [[ -n "$path" ]] || continue
    adb push "$LOCAL/$path" "$STAGING/$path"
done <<< "$mapfile_compatible_list"
adb push "$LOCAL/manifest.json" "$STAGING/manifest.json"
while IFS= read -r path; do
    [[ -n "$path" ]] || continue
    expected="$(stat -f %z "$LOCAL/$path")"
    actual="$(adb shell stat -c %s "$STAGING/$path" | tr -d '\r')"
    [[ "$actual" == "$expected" ]] || { echo "remote size mismatch: $path" >&2; exit 1; }
done <<< "$mapfile_compatible_list"
adb shell "if [ -e '$BACKUP' ]; then rm -rf '$BACKUP'; fi; if [ -e '$REMOTE' ]; then mv '$REMOTE' '$BACKUP' || exit 1; fi; if mv '$STAGING' '$REMOTE'; then rm -rf '$BACKUP'; else if [ -e '$BACKUP' ] && [ ! -e '$REMOTE' ]; then mv '$BACKUP' '$REMOTE'; fi; exit 1; fi"
if [[ "$MODEL_ID" == "elephant-1" ]]; then
    printf 'Pushed %s. Open 决策 → 模型 and verify on the phone before deciding.\n' "$MODEL_ID"
else
    printf 'Pushed %s. Open 对话 → 模型 and verify on the phone before generating.\n' "$MODEL_ID"
fi
printf 'No .verified.json was generated on the device.\n'
