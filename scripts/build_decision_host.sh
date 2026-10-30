#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${LLM_HOST_BUILD_DIR:-$ROOT/build/llm-host}"
bash "$ROOT/scripts/build_llm_host.sh"
cmake --build "$BUILD_DIR" --target omni_decision_smoke --parallel "${JOBS:-8}"
printf 'Omni decision host smoke: %s\n' "$BUILD_DIR/omni_decision_smoke"
