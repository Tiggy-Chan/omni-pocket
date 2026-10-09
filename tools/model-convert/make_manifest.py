#!/usr/bin/env python3
"""Create the manifest consumed by the Android model manager.

The manifest is generated after conversion so downloads are resumable and every
large file is checked before it becomes visible to the runtime.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--model-id", required=True)
    parser.add_argument("--source-repo", required=True)
    parser.add_argument("--revision", default="main")
    parser.add_argument("--features", default="text-to-image,image-edit,tiny-vae")
    args = parser.parse_args()

    files = []
    for path in sorted(args.root.rglob("*")):
        if not path.is_file() or path.name == "manifest.json" or path.name.endswith(".part"):
            continue
        files.append({
            "path": path.relative_to(args.root).as_posix(),
            "size": path.stat().st_size,
            "sha256": sha256(path),
        })
    manifest = {
        "schemaVersion": 1,
        "modelId": args.model_id,
        "sourceRepo": args.source_repo,
        "revision": args.revision,
        "features": [item for item in args.features.split(",") if item],
        "files": files,
    }
    (args.root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {args.root / 'manifest.json'} ({len(files)} files)")


if __name__ == "__main__":
    main()
