#!/bin/bash
# Push a model directory to the demo app (and the CLI demo if built).
#   scripts/push_models.sh [model_dir] [adb serial]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-$ROOT/models/qwen_image21}"
A="adb ${2:+-s $2}"
DST=/sdcard/Android/data/com.scsonic.qwenimage21.demo/files/qwen_image21

hash_file() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d ' ' -f 1
    else
        sha256sum "$1" | cut -d ' ' -f 1
    fi
}

$A shell mkdir -p $DST/text_encoder
# adb shell creates these owned by shell:ext_data_rw. Ensure the app process can traverse them.
$A shell chmod 755 $DST $DST/text_encoder
cd "$SRC"
while IFS= read -r -d '' path; do
    f="${path#./}"
    case "$f" in
        README.md|LICENSE|.gitattributes|.cache/*|*.part) continue ;;
    esac
    case "$f" in
        *"'"*) echo "unsupported filename (single quote): $f" >&2; exit 1 ;;
    esac

    remote="$DST/$f"
    temporary="$remote.uploading"
    local_hash=$(hash_file "$f")
    remote_hash=$($A shell "sha256sum '$remote' 2>/dev/null" | tr -d '\r' | cut -d ' ' -f 1 || true)
    if [ "$local_hash" = "$remote_hash" ]; then
        echo "skip $f"
        continue
    fi

    echo "push $f"
    $A shell "mkdir -p \"\$(dirname '$remote')\""
    # Piping through cat avoids remote fchown failures on some Android/data mounts.
    cat "$f" | $A shell "cat > '$temporary'"
    $A shell "chmod 644 '$temporary'"
    uploaded_hash=$($A shell "sha256sum '$temporary'" | tr -d '\r' | cut -d ' ' -f 1)
    if [ "$local_hash" != "$uploaded_hash" ]; then
        $A shell "rm -f '$temporary'"
        echo "hash mismatch after upload: $f" >&2
        exit 1
    fi
    $A shell "mv -f '$temporary' '$remote'"
done < <(find -L . -type f ! -name "*.part" ! -path "./.cache/*" -print0)

CLI="$ROOT/third_party/MNN/build_android_cli"
if [ -x "$CLI/qwen_image21_demo" ]; then
    $A shell mkdir -p /data/local/tmp/qwen
    $A push "$CLI/libMNN.so" "$CLI/qwen_image21_demo" /data/local/tmp/qwen/ > /dev/null
fi
echo "done -> $DST"
