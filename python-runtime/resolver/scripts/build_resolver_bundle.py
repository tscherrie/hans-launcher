#!/usr/bin/env python3
"""Build the deterministic, source-only Hans resolver PYZ."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import stat
import zipfile


FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def tree_digest(root: Path) -> tuple[str, int, int]:
    digest = hashlib.sha256()
    files = sorted(path for path in root.rglob("*") if path.is_file())
    total = 0
    for path in files:
        relative = path.relative_to(root).as_posix().encode("utf-8")
        payload = path.read_bytes()
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        digest.update(len(payload).to_bytes(8, "big"))
        digest.update(payload)
        total += len(payload)
    return digest.hexdigest(), len(files), total


def add_stored(archive: zipfile.ZipFile, name: str, payload: bytes) -> None:
    info = zipfile.ZipInfo(name, FIXED_TIME)
    info.compress_type = zipfile.ZIP_STORED
    info.create_system = 3
    info.external_attr = (stat.S_IFREG | 0o444) << 16
    archive.writestr(info, payload)


def build(root: Path, output: Path) -> None:
    lock = json.loads((root / "resolver.lock.json").read_text(encoding="utf-8"))
    actual_digest, actual_files, actual_bytes = tree_digest(root / "vendor")
    assert actual_digest == lock["vendorTreeSha256"], "resolver vendor digest changed"
    assert actual_files == lock["vendorFileCount"], "resolver vendor file count changed"
    assert actual_bytes == lock["vendorBytes"], "resolver vendor byte count changed"
    sources: dict[str, bytes] = {
        "hans_resolver_worker.py": (root / "hans_resolver_worker.py").read_bytes(),
        "hans_resolver_payload/__init__.py": b"",
    }
    for path in sorted(item for item in (root / "vendor").rglob("*") if item.is_file()):
        relative = path.relative_to(root / "vendor").as_posix()
        if "__pycache__" in path.parts or path.suffix == ".pyc":
            raise AssertionError(f"bytecode is forbidden in resolver bundle: {relative}")
        if path.suffix not in {".py", ".typed"}:
            raise AssertionError(f"non-source resolver payload is forbidden: {relative}")
        sources[relative] = path.read_bytes()
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = output.with_suffix(output.suffix + ".tmp")
    with zipfile.ZipFile(staging, "w", allowZip64=False) as archive:
        for name, payload in sorted(sources.items()):
            add_stored(archive, name, payload)
    staging.replace(output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    build(Path(__file__).resolve().parents[1], args.output.resolve())


if __name__ == "__main__":
    main()
