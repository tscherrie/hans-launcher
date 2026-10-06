#!/usr/bin/env python3
"""Offline ELF/package-contract regression checks; never use real credentials."""
import importlib.util
import json
import pathlib
import struct
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("transcription_elf", ROOT / "tests/verify-transcription-elf.py")
ELF = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ELF)


def elf_fixture(alignment=16384, segment_type=1, machine=183):
    result = bytearray(128)
    result[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HH", result, 16, 2, machine)
    struct.pack_into("<Q", result, 32, 64)
    struct.pack_into("<HH", result, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", result, 64, segment_type, 5, 0, 0x400000, 0, 128, 128, alignment)
    return result


class TranscriptionPackagingTest(unittest.TestCase):
    def verify(self, value):
        with tempfile.TemporaryDirectory(prefix="hans-transcription-elf-test-") as directory:
            path = pathlib.Path(directory) / "fixture.so"
            path.write_bytes(value)
            return ELF.verify(path)

    def test_valid_static_arm64_16k(self):
        self.assertEqual(self.verify(elf_fixture()), 1)

    def test_rejects_4k_alignment(self):
        with self.assertRaises(ValueError):
            self.verify(elf_fixture(alignment=4096))

    def test_rejects_interpreter(self):
        with self.assertRaises(ValueError):
            self.verify(elf_fixture(segment_type=3))

    def test_rejects_dynamic_section(self):
        with self.assertRaises(ValueError):
            self.verify(elf_fixture(segment_type=2))

    def test_rejects_wrong_architecture(self):
        with self.assertRaises(ValueError):
            self.verify(elf_fixture(machine=62))

    def test_rejects_truncated_program_table(self):
        with self.assertRaises(ValueError):
            self.verify(elf_fixture()[:100])

    def test_fixed_pinned_source(self):
        source = json.loads((ROOT / "transcription/source.lock.json").read_text())
        self.assertEqual(source["upstreamCommit"], "f0a1b8f0849d90960bc406b848f32e5a129b0457")
        self.assertEqual(source["sourceBytes"], 14498329)
        self.assertEqual(len(source["sourceSha256"]), 64)
        self.assertEqual(source["apkLibraryName"], "libcodex_transcribe.so")

    def test_product_endpoint_is_not_configurable(self):
        source = (ROOT / "transcription/src/main.rs").read_text()
        self.assertIn('const ENDPOINT: &str = "https://chatgpt.com/backend-api/transcribe";', source)
        self.assertIn(".without_redirects()", source)
        self.assertNotIn("api.openai.com/v1/audio/transcriptions", source)
        self.assertNotIn("tracing_subscriber", source)

    def test_production_auth_is_read_only(self):
        source = (ROOT / "transcription/src/main.rs").read_text().split("#[cfg(test)]\nmod tests", 1)[0]
        self.assertIn("manager.auth_cached()", source)
        self.assertNotIn("manager.auth().await", source)
        self.assertNotIn("refresh_token(", source)
        self.assertNotIn("login_with_api_key(", source)
        self.assertNotIn("save_auth(", source)


if __name__ == "__main__":
    unittest.main()
