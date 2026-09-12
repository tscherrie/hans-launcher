from __future__ import annotations

import hashlib
import importlib.util
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "stage_runtime", ROOT / "scripts/stage_runtime.py"
)
assert SPEC is not None and SPEC.loader is not None
STAGE_RUNTIME = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(STAGE_RUNTIME)


class NativePackagePolicyHeaderTest(unittest.TestCase):
    def test_header_is_exact_deterministic_and_manifest_bound(self) -> None:
        packages = [
            {
                "nativeLibraries": [
                    {
                        "moduleName": "msgpack._cmsgpack",
                        "packagedName": "libhans_py_msgpack___cmsgpack.so",
                    }
                ]
            }
        ]
        with tempfile.TemporaryDirectory() as temporary:
            first = Path(temporary) / "first.h"
            second = Path(temporary) / "second.h"
            first_descriptor = STAGE_RUNTIME._stage_native_package_policy_header(
                packages, first
            )
            second_descriptor = STAGE_RUNTIME._stage_native_package_policy_header(
                packages, second
            )

            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(first_descriptor, second_descriptor)
            self.assertEqual(1, first_descriptor["entryCount"])
            self.assertEqual(len(first.read_bytes()), first_descriptor["sizeBytes"])
            self.assertEqual(
                hashlib.sha256(first.read_bytes()).hexdigest(),
                first_descriptor["sha256"],
            )
            text = first.read_text(encoding="utf-8")
            self.assertIn(
                '{"msgpack._cmsgpack", "libhans_py_msgpack___cmsgpack.so"}',
                text,
            )
            self.assertIn(first_descriptor["policySha256"], text)

    def test_header_rejects_unbounded_or_unsafe_policy(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "policy.h"
            with self.assertRaises(STAGE_RUNTIME.StageError):
                STAGE_RUNTIME._stage_native_package_policy_header([], destination)
            with self.assertRaises(STAGE_RUNTIME.StageError):
                STAGE_RUNTIME._stage_native_package_policy_header(
                    [
                        {
                            "nativeLibraries": [
                                {
                                    "moduleName": "msgpack._cmsgpack;bad",
                                    "packagedName": "libhans_py_msgpack___cmsgpack.so",
                                }
                            ]
                        }
                    ],
                    destination,
                )


if __name__ == "__main__":
    unittest.main()
