#!/usr/bin/env python3
"""Generate the tiny CPython-core codec bootstrap from the pinned stdlib ZIP."""

from __future__ import annotations

import argparse
import hashlib
import marshal
import sys
import zipfile
from pathlib import Path


MODULES = (
    ("encodings", "encodings/__init__.py", True),
    ("encodings.aliases", "encodings/aliases.py", False),
    ("encodings.utf_8", "encodings/utf_8.py", False),
)


def _array(name: str, payload: bytes) -> str:
    rows = []
    for offset in range(0, len(payload), 16):
        rows.append(
            "    "
            + ", ".join(f"0x{value:02x}" for value in payload[offset : offset + 16])
            + ","
        )
    return f"static const unsigned char {name}[] = {{\n" + "\n".join(rows) + "\n};\n"


def generate(stdlib: Path, output: Path) -> None:
    if sys.version_info[:2] != (3, 14) or marshal.version != 5:
        raise SystemExit("frozen bootstrap generation requires CPython 3.14 marshal v5")
    arrays: list[str] = []
    entries: list[str] = []
    source_receipts: list[str] = []
    with zipfile.ZipFile(stdlib) as archive:
        for index, (module, member, is_package) in enumerate(MODULES):
            source = archive.read(member)
            code = compile(source, f"<frozen {module}>", "exec", dont_inherit=True, optimize=2)
            payload = marshal.dumps(code)
            symbol = f"kFrozen{index}"
            arrays.append(_array(symbol, payload))
            entries.append(
                f'    {{"{module}", {symbol}, static_cast<int>(sizeof({symbol})), '
                f'{1 if is_package else 0}}},'
            )
            source_receipts.append(
                f"// {module}: source-sha256={hashlib.sha256(source).hexdigest()} "
                f"marshal-sha256={hashlib.sha256(payload).hexdigest()}"
            )
    document = """// Generated deterministically by generate_frozen_bootstrap.py.
// Only the codec modules required before descriptor-backed imports are frozen.
#include <Python.h>

#include "hans_frozen_bootstrap.h"

namespace hans::python {
"""
    document += "\n".join(source_receipts) + "\n\n"
    document += "\n".join(arrays)
    document += "\nconst struct _frozen kFrozenBootstrap[] = {\n"
    document += "\n".join(entries)
    document += "\n    {nullptr, nullptr, 0, 0},\n};\n\n}  // namespace hans::python\n"
    output.write_text(document, encoding="utf-8", newline="\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stdlib", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    generate(args.stdlib, args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
