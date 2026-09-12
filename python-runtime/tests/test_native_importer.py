from __future__ import annotations

import importlib.util
import sys
import tempfile
import types
import unittest
from pathlib import Path


RUNTIME_SOURCE = Path(__file__).resolve().parents[1] / "runtime"


def load_importer():
    name = f"hans_native_importer_test_{id(object())}"
    specification = importlib.util.spec_from_file_location(
        name, RUNTIME_SOURCE / "hans_native_importer.py"
    )
    assert specification is not None and specification.loader is not None
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


class NativeImporterIsolationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.importer = load_importer()
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        for packaged_name in (
            "libhans_py_math.so",
            "libhans_py_msgpack___cmsgpack.so",
            "libhans_py_orjson.so",
        ):
            (self.directory / packaged_name).write_bytes(b"signed-apk-placeholder")
        self.meta_path = sys.meta_path[:]
        self.importer.install(str(self.directory))
        self.digest_a = "a" * 64
        self.digest_b = "b" * 64

    def tearDown(self) -> None:
        sys.meta_path[:] = self.meta_path
        for root in ("msgpack", "orjson"):
            for name in tuple(sys.modules):
                if name == root or name.startswith(root + "."):
                    sys.modules.pop(name, None)
        self.temporary.cleanup()

    @staticmethod
    def msgpack_descriptor() -> dict[str, str]:
        return {
            "module": "msgpack._cmsgpack",
            "packagedName": "libhans_py_msgpack___cmsgpack.so",
        }

    def standard_finder(self):
        return next(
            finder
            for finder in sys.meta_path
            if isinstance(finder, self.importer.HansNativeExtensionFinder)
            and "math" in finder._allowed
        )

    def test_allowed_environment_exposes_only_exact_native_module(self) -> None:
        standard = self.standard_finder()
        self.assertIsNotNone(standard.find_spec("math"))
        self.assertIsNone(standard.find_spec("msgpack._cmsgpack"))

        with self.importer.request_scope(
            self.digest_a, [self.msgpack_descriptor()]
        ) as scope:
            specification = scope.finder.find_spec("msgpack._cmsgpack")
            self.assertIsNotNone(specification)
            self.assertEqual(
                str(
                    (self.directory / "libhans_py_msgpack___cmsgpack.so").resolve()
                ),
                specification.origin,
            )
            self.assertIsNone(scope.finder.find_spec("orjson"))

    def test_unpinned_environment_cannot_reach_signed_third_party_elf(self) -> None:
        standard = self.standard_finder()
        with self.importer.request_scope(self.digest_a, []) as scope:
            self.assertIsNone(scope.finder.find_spec("msgpack._cmsgpack"))
            self.assertIsNone(standard.find_spec("msgpack._cmsgpack"))

    def test_changing_environments_never_reuses_package_modules(self) -> None:
        with self.importer.request_scope(
            self.digest_a, [self.msgpack_descriptor()]
        ):
            sys.modules["msgpack"] = types.ModuleType("msgpack")
            sys.modules["msgpack._cmsgpack"] = types.ModuleType(
                "msgpack._cmsgpack"
            )

        self.assertNotIn("msgpack", sys.modules)
        self.assertNotIn("msgpack._cmsgpack", sys.modules)
        with self.importer.request_scope(
            self.digest_b,
            [{"module": "orjson", "packagedName": "libhans_py_orjson.so"}],
        ) as scope:
            self.assertIsNone(scope.finder.find_spec("msgpack._cmsgpack"))
            self.assertIsNotNone(scope.finder.find_spec("orjson"))

    def test_exception_path_restores_importers_and_evicts_entire_package(self) -> None:
        before = sys.meta_path[:]
        with self.assertRaisesRegex(RuntimeError, "plugin failed"):
            with self.importer.request_scope(
                self.digest_a, [self.msgpack_descriptor()]
            ):
                sys.modules["msgpack"] = types.ModuleType("msgpack")
                sys.modules["msgpack.fallback"] = types.ModuleType(
                    "msgpack.fallback"
                )
                sys.modules["msgpack._cmsgpack"] = types.ModuleType(
                    "msgpack._cmsgpack"
                )
                raise RuntimeError("plugin failed")

        self.assertEqual(before, sys.meta_path)
        self.assertFalse(any(name.split(".", 1)[0] == "msgpack" for name in sys.modules))

    def test_stale_package_namespace_fails_closed(self) -> None:
        sys.modules["msgpack"] = types.ModuleType("msgpack")
        scope = self.importer.request_scope(
            self.digest_a, [self.msgpack_descriptor()]
        )
        with self.assertRaisesRegex(ImportError, "already occupied"):
            scope.__enter__()

    def test_descriptor_must_match_library_schema_and_remain_third_party(self) -> None:
        invalid = [
            {"module": "msgpack._cmsgpack", "packagedName": "other.so"},
            {"module": "math", "packagedName": "libhans_py_math.so"},
            {
                "module": "json._native",
                "packagedName": "libhans_py_json___native.so",
            },
            {"module": "msgpack._cmsgpack", "packagedName": "../escape.so"},
        ]
        for descriptor in invalid:
            with self.subTest(descriptor=descriptor):
                with self.assertRaises(self.importer.HansNativeValidationError):
                    self.importer.request_scope(self.digest_a, [descriptor])


if __name__ == "__main__":
    unittest.main()
