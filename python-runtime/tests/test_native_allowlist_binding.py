from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VERIFY_RUNTIME_PATH = ROOT / "python-runtime/scripts/verify_runtime.py"
SPEC = importlib.util.spec_from_file_location("hans_verify_runtime_allowlist", VERIFY_RUNTIME_PATH)
assert SPEC is not None and SPEC.loader is not None
VERIFY_RUNTIME = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY_RUNTIME)


class NativeAllowlistBindingTest(unittest.TestCase):
    def parse(self, body: str) -> tuple[str, ...]:
        return VERIFY_RUNTIME._standard_extension_modules(body.encode("utf-8"))

    def reject(self, body: str, message: str) -> None:
        with self.assertRaisesRegex(VERIFY_RUNTIME.VerificationError, message):
            self.parse(body)

    def test_literal_sorted_unique_tuple_is_accepted(self) -> None:
        self.assertEqual(
            ("_ssl", "math"),
            self.parse('_STANDARD_EXTENSION_MODULES = ("_ssl", "math")\n'),
        )

    def test_dynamic_duplicate_unsorted_and_invalid_contracts_fail_closed(self) -> None:
        cases = (
            ('_STANDARD_EXTENSION_MODULES = tuple(["math"])\n', "literal tuple"),
            ('_STANDARD_EXTENSION_MODULES = ("math", "math")\n', "unique"),
            ('_STANDARD_EXTENSION_MODULES = ("math", "_ssl")\n', "sorted"),
            ('_STANDARD_EXTENSION_MODULES = ("pkg.mod",)\n', "invalid"),
            ('_STANDARD_EXTENSION_MODULES = (name,)\n', "literal strings"),
        )
        for body, message in cases:
            with self.subTest(body=body):
                self.reject(body, message)

    def test_shipped_importer_is_bound_as_a_literal_contract(self) -> None:
        importer = (
            ROOT / "python-runtime/runtime/hans_native_importer.py"
        ).read_bytes()
        modules = VERIFY_RUNTIME._standard_extension_modules(importer)
        self.assertIn("_posixsubprocess", modules)
        self.assertIn("_ssl", modules)
        self.assertNotIn("msgpack._cmsgpack", modules)


if __name__ == "__main__":
    unittest.main()
