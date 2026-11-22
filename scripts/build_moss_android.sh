#!/bin/bash
# Compatibility wrapper: MOSS, Wan2.2 video, and generic HF SD models now share libsd_cli.so.
set -euo pipefail
exec bash "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/build_sd_android.sh" "$@"
