#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bash "$ROOT/scripts/build_decision_host.sh"
if [[ "${1:-}" == "--protocol-only" ]]; then
    exec "$ROOT/build/llm-host/omni_decision_smoke" --protocol-only
elif (( $# != 0 )); then
    echo "usage: $0 [--protocol-only]" >&2
    exit 2
fi
MODEL_DIR="${ELEPHANT_MODEL_DIR:-$ROOT/models/llm/elephant-1}"
MODEL_CONFIG="$MODEL_DIR/config.json"
[[ -f "$MODEL_CONFIG" ]] || {
    echo "Elephant model not found; run scripts/prepare_elephant_model.sh" >&2
    exit 1
}
command -v python3 >/dev/null 2>&1 || { echo "python3 not found" >&2; exit 1; }
mkdir -p "$ROOT/build/decision-runtime"
TEST_CONFIG="$ROOT/build/decision-runtime/elephant-1.$$.json"
CACHE="$ROOT/build/decision-runtime/elephant-1.$$"
cleanup() {
    rm -f "$TEST_CONFIG"
    rm -rf "$CACHE"
}
trap cleanup EXIT
python3 - "$MODEL_CONFIG" "$TEST_CONFIG" <<'PY'
import json, os, sys
with open(sys.argv[1], encoding='utf-8') as source:
    config = json.load(source)
config['base_dir'] = os.path.dirname(os.path.abspath(sys.argv[1])) + os.sep
with open(sys.argv[2], 'w', encoding='utf-8') as output:
    json.dump(config, output, ensure_ascii=False, indent=2)
    output.write('\n')
PY
RESULT_LOG="$ROOT/build/decision-runtime/last-run.log"
"$ROOT/build/llm-host/omni_decision_smoke" "$TEST_CONFIG" "$CACHE" 2>&1 | tee "$RESULT_LOG"
python3 "$ROOT/export/elephant_reference.py" verify \
    --reference "$ROOT/tools/decision-host/reference.json" --log "$RESULT_LOG"
