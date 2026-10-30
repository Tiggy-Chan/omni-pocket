#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL_ID="qwen35-0.8b"
MODEL_CONFIG="$ROOT/models/llm/$MODEL_ID/config.json"
[[ -f "$MODEL_CONFIG" ]] || {
    echo "model not found; run scripts/download_llm_models.sh $MODEL_ID" >&2
    exit 1
}
command -v python3 >/dev/null 2>&1 || { echo "python3 not found" >&2; exit 1; }
bash "$ROOT/scripts/build_llm_host.sh"
TEST_CONFIG="$ROOT/build/llm-host/$MODEL_ID.smoke.json"
python3 - "$MODEL_CONFIG" "$TEST_CONFIG" <<'PY'
import json, os, sys
with open(sys.argv[1], encoding='utf-8') as source:
    config = json.load(source)
config['base_dir'] = os.path.dirname(os.path.abspath(sys.argv[1])) + os.sep
with open(sys.argv[2], 'w', encoding='utf-8') as output:
    json.dump(config, output, ensure_ascii=False, indent=2)
    output.write('\n')
PY
exec "$ROOT/build/llm-host/omni_llm_smoke" \
    "$TEST_CONFIG" "$ROOT/build/llm-runtime-cache/$MODEL_ID-cpu"
