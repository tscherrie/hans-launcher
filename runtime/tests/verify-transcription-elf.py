#!/usr/bin/env python3
"""Validate the Hans-owned executable without depending on a host readelf."""
import pathlib
import struct
import sys


def verify(path):
    value = pathlib.Path(path).read_bytes()
    if len(value) < 64 or value[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError("Not an ELF64 little-endian executable")
    kind, machine = struct.unpack_from("<HH", value, 16)
    if kind not in (2, 3) or machine != 183:
        raise ValueError("Not an ARM64 executable")
    program_offset = struct.unpack_from("<Q", value, 32)[0]
    entry_size, count = struct.unpack_from("<HH", value, 54)
    if entry_size != 56 or not count or program_offset + entry_size * count > len(value):
        raise ValueError("Invalid ELF program table")
    loads = 0
    for index in range(count):
        kind, flags, offset, address, _, size, memory_size, alignment = struct.unpack_from(
            "<IIQQQQQQ", value, program_offset + entry_size * index
        )
        if kind in (2, 3):
            raise ValueError("Dynamic section or interpreter is forbidden")
        if kind == 1:
            loads += 1
            if alignment < 16384 or alignment & (alignment - 1):
                raise ValueError("ELF LOAD alignment is smaller than 16 KiB")
            if address % 16384 != offset % 16384 or size > memory_size or offset + size > len(value):
                raise ValueError("Invalid ELF LOAD segment")
    if not loads:
        raise ValueError("No ELF LOAD segments")
    return loads


if __name__ == "__main__":
    try:
        loads = verify(sys.argv[1])
    except (ValueError, OSError, IndexError, struct.error) as error:
        print(f"Transcription ELF rejected: {error}", file=sys.stderr)
        raise SystemExit(1)
    print(f"Transcription ELF verified: ARM64, static, {loads} LOAD segments, 16 KiB alignment")
