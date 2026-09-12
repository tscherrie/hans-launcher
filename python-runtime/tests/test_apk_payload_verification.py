from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import stat
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
VERIFY_RUNTIME_PATH = ROOT / "python-runtime/scripts/verify_runtime.py"
SPEC = importlib.util.spec_from_file_location("hans_verify_runtime", VERIFY_RUNTIME_PATH)
assert SPEC is not None and SPEC.loader is not None
VERIFY_RUNTIME = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY_RUNTIME)


def arm64_elf(load_alignment: int = 16_384, marker: bytes = b"") -> bytes:
    identity = b"\x7fELF" + bytes((2, 1, 1, 0, 0)) + bytes(7)
    header = identity + struct.pack(
        "<HHIQQQIHHHHHH",
        3,
        183,
        1,
        0,
        64,
        0,
        0,
        64,
        56,
        1,
        0,
        0,
        0,
    )
    program_header = struct.pack(
        "<IIQQQQQQ",
        1,
        5,
        0,
        0x400000,
        0x400000,
        120 + len(marker),
        120 + len(marker),
        load_alignment,
    )
    return header + program_header + marker


def companion_wheel(marker: bytes = b"pure-python-companion") -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        records = {
            "msgpack/__init__.py": marker,
            "msgpack-1.2.1.dist-info/METADATA": b"Name: msgpack\nVersion: 1.2.1\n",
            "msgpack-1.2.1.dist-info/RECORD": b"",
        }
        for name, data in sorted(records.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o444) << 16
            archive.writestr(info, data)
    return output.getvalue()


class PythonApkPayloadVerificationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.stage = self.root / "stage"
        self.assets = self.stage / "assets/hans/python"
        self.assets.mkdir(parents=True)
        self.lock_path = self.root / "python.lock.json"
        self.bridge_path = self.root / "libhans_python_jni.so"
        self.resolver_path = self.root / "resolver.pyz"
        self.apk_path = self.root / "app-standard-release-unsigned.apk"

        self.core = arm64_elf(marker=b"core")
        self.module = arm64_elf(marker=b"module")
        self.third_party_module = arm64_elf(marker=b"msgpack")
        self.bridge = arm64_elf(marker=b"bridge")
        self.stdlib = b"deterministic-stdlib"
        self.resolver = b"deterministic-source-only-resolver"
        self.companion = companion_wheel()
        self.bridge_path.write_bytes(self.bridge)
        self.resolver_path.write_bytes(self.resolver)
        (self.assets / "python314.zip").write_bytes(self.stdlib)
        self.runtime = {
            "version": "3.14.2",
            "android": {
                "abi": "arm64-v8a",
                "hostApplicationMinimumApi": 31,
                "pageAlignmentBytes": 16_384,
            },
            "coreLibraries": [self._descriptor("libpython3.14.so", self.core)],
        }
        self.native_package = {
            "catalogId": "msgpack-1.2.1-cp314-android31-arm64-v8a",
            "packageName": "msgpack",
            "version": "1.2.1",
            "source": {
                "name": "msgpack-1.2.1.tar.gz",
                "url": "https://files.pythonhosted.org/packages/msgpack-1.2.1.tar.gz",
                "bytes": 1234,
                "sha256": hashlib.sha256(b"source-sdist").hexdigest(),
            },
            "target": {
                "pythonSeries": "3.14",
                "interpreterTag": "cp314",
                "androidAbi": "arm64-v8a",
                "minimumAndroidApi": 31,
                "directorySegment": "cp314-android_arm64_v8a",
            },
            "requiresPython": ">=3.10",
            "requiresDist": [],
            "importNames": ["msgpack", "msgpack._cmsgpack"],
            "companionWheel": {
                "assetPath": "hans/python/native-packages/msgpack-1.2.1-py3-none-any.whl",
                "fileName": "msgpack-1.2.1-py3-none-any.whl",
                **self._size_digest(self.companion),
            },
            "nativeLibraries": [
                {
                    "moduleName": "msgpack._cmsgpack",
                    "packagedName": "libhans_py_msgpack___cmsgpack.so",
                    **self._size_digest(self.third_party_module),
                }
            ],
            "payloadSha256": "",
        }
        self.native_package["payloadSha256"] = VERIFY_RUNTIME._native_payload_digest(
            self.native_package
        )
        self.native_manifest_entry = VERIFY_RUNTIME._native_manifest_entry(self.native_package)
        (
            self.native_policy_descriptor,
            self.native_policy_header,
        ) = VERIFY_RUNTIME._native_package_policy([self.native_package])
        self.public = {
            "stdlibBytes": len(self.stdlib),
            "stdlibSha256": hashlib.sha256(self.stdlib).hexdigest(),
            "nativeModules": [
                {
                    "module": "math",
                    "packagedName": "libhans_py_math.so",
                    **self._size_digest(self.module),
                },
                {
                    "module": "msgpack._cmsgpack",
                    "sourceName": "msgpack-1.2.1.tar.gz:msgpack/_cmsgpack.c",
                    "packagedName": "libhans_py_msgpack___cmsgpack.so",
                    **self._size_digest(self.third_party_module),
                },
            ],
            "nativePackages": [self.native_manifest_entry],
            "nativePackagePolicyHeader": self.native_policy_descriptor,
        }
        self._write_metadata()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def _size_digest(payload: bytes) -> dict[str, object]:
        return {
            "bytes": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
        }

    def _descriptor(self, name: str, payload: bytes) -> dict[str, object]:
        return {"name": name, **self._size_digest(payload)}

    def _write_metadata(self) -> None:
        self.lock_path.write_text(
            json.dumps({"schemaVersion": 1, "runtime": self.runtime}, sort_keys=True),
            encoding="utf-8",
        )
        (self.root / "native-packages.lock.json").write_text(
            json.dumps(
                {"schemaVersion": 1, "packages": [self.native_package]},
                indent=2,
                sort_keys=True,
            )
            + "\n",
            encoding="utf-8",
        )
        (self.assets / "runtime-manifest.json").write_text(
            json.dumps(self.public, sort_keys=True),
            encoding="utf-8",
        )
        policy_path = self.stage / "prefix/include/hans_native_package_policy.h"
        policy_path.parent.mkdir(parents=True, exist_ok=True)
        policy_path.write_bytes(self.native_policy_header)
        native_assets = self.assets / "native-packages"
        native_assets.mkdir(parents=True, exist_ok=True)
        (native_assets / "msgpack-1.2.1-py3-none-any.whl").write_bytes(self.companion)
        catalog = {
            "schemaVersion": 1,
            "pythonVersion": self.runtime["version"],
            "packages": [self.native_manifest_entry],
        }
        (native_assets / "catalog.json").write_text(
            json.dumps(catalog, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

    @staticmethod
    def _zip_info(name: str, compression: int, mode: int = 0o444) -> zipfile.ZipInfo:
        info = zipfile.ZipInfo(name)
        info.compress_type = compression
        info.create_system = 3
        info.external_attr = (stat.S_IFREG | mode) << 16
        return info

    def _write_apk(
        self,
        *,
        omit: set[str] | None = None,
        extra: dict[str, bytes] | None = None,
        executable_asset: str | None = None,
        stored_native: str | None = None,
    ) -> None:
        omit = omit or set()
        records = {
            "assets/hans/python/runtime-manifest.json": (
                self.assets / "runtime-manifest.json"
            ).read_bytes(),
            "assets/hans/python/python314.zip": self.stdlib,
            "assets/hans/python/resolver.pyz": self.resolver,
            "assets/hans/python/native-packages/catalog.json": (
                self.assets / "native-packages/catalog.json"
            ).read_bytes(),
            "assets/hans/python/native-packages/msgpack-1.2.1-py3-none-any.whl": self.companion,
            "lib/arm64-v8a/libpython3.14.so": self.core,
            "lib/arm64-v8a/libhans_py_math.so": self.module,
            "lib/arm64-v8a/libhans_py_msgpack___cmsgpack.so": self.third_party_module,
            "lib/arm64-v8a/libhans_python_jni.so": self.bridge,
        }
        records.update(extra or {})
        ordered = list(records.items())
        if stored_native is not None:
            ordered.sort(key=lambda item: item[0] != stored_native)
        with zipfile.ZipFile(self.apk_path, "w") as archive:
            for name, payload in ordered:
                if name in omit:
                    continue
                compression = (
                    zipfile.ZIP_STORED if name == stored_native else zipfile.ZIP_DEFLATED
                )
                mode = 0o555 if name == executable_asset else 0o444
                archive.writestr(self._zip_info(name, compression, mode), payload)

    def _verify(self) -> None:
        with mock.patch.object(VERIFY_RUNTIME, "verify"):
            VERIFY_RUNTIME.verify_apk(
                self.lock_path,
                self.stage,
                self.bridge_path,
                self.resolver_path,
                self.apk_path,
            )

    def test_exact_release_payload_passes(self) -> None:
        self._write_apk()

        self._verify()

    def test_missing_resolver_asset_is_rejected(self) -> None:
        self._write_apk(omit={"assets/hans/python/resolver.pyz"})

        with self.assertRaisesRegex(VERIFY_RUNTIME.VerificationError, "asset set mismatch"):
            self._verify()

    def test_extra_python_asset_is_rejected(self) -> None:
        self._write_apk(extra={"assets/hans/python/injected.py": b"pass\n"})

        with self.assertRaisesRegex(VERIFY_RUNTIME.VerificationError, "extra=.*injected"):
            self._verify()

    def test_tampered_companion_wheel_is_rejected(self) -> None:
        entry = "assets/hans/python/native-packages/msgpack-1.2.1-py3-none-any.whl"
        self._write_apk(extra={entry: companion_wheel(b"tampered")})

        with self.assertRaisesRegex(VERIFY_RUNTIME.VerificationError, "differs from verified"):
            self._verify()

    def test_native_catalog_drift_is_rejected(self) -> None:
        catalog_path = self.assets / "native-packages/catalog.json"
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
        catalog["packages"][0]["version"] = "9.9.9"
        catalog_path.write_text(
            json.dumps(catalog, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        self._write_apk()

        with self.assertRaisesRegex(VERIFY_RUNTIME.VerificationError, "catalog does not match"):
            self._verify()

    def test_native_payload_digest_drift_is_rejected(self) -> None:
        self.native_package["payloadSha256"] = "0" * 64
        self._write_metadata()
        self._write_apk()

        with self.assertRaisesRegex(VERIFY_RUNTIME.VerificationError, "payload digest mismatch"):
            self._verify()

    def test_missing_native_policy_header_is_rejected(self) -> None:
        (self.stage / "prefix/include/hans_native_package_policy.h").unlink()
        self._write_apk()

        with self.assertRaisesRegex(
            VERIFY_RUNTIME.VerificationError, "cannot inspect native package policy header"
        ):
            self._verify()

    def test_tampered_native_policy_header_is_rejected(self) -> None:
        policy_path = self.stage / "prefix/include/hans_native_package_policy.h"
        policy_path.write_bytes(self.native_policy_header + b"// injected\n")
        self._write_apk()

        with self.assertRaisesRegex(
            VERIFY_RUNTIME.VerificationError, "does not match canonical lock rendering"
        ):
            self._verify()

    def test_native_policy_descriptor_mismatch_is_rejected(self) -> None:
        self.public["nativePackagePolicyHeader"] = {
            **self.native_policy_descriptor,
            "policySha256": "0" * 64,
        }
        self._write_metadata()
        self._write_apk()

        with self.assertRaisesRegex(
            VERIFY_RUNTIME.VerificationError, "descriptor does not match the native lock"
        ):
            self._verify()

    def test_stage_and_runtime_policy_descriptors_must_match(self) -> None:
        public_bytes = (self.assets / "runtime-manifest.json").read_bytes()
        stage = {
            "nativePackages": [self.native_manifest_entry],
            "nativeModules": self.public["nativeModules"],
            "nativePackagePolicyHeader": {
                **self.native_policy_descriptor,
                "entryCount": 2,
            },
            "runtimeManifestSha256": hashlib.sha256(public_bytes).hexdigest(),
        }

        with self.assertRaisesRegex(
            VERIFY_RUNTIME.VerificationError, "stage/runtime native policy descriptors differ"
        ):
            VERIFY_RUNTIME._verify_native_package_supply_chain(
                self.lock_path, self.stage, self.runtime, self.public, stage
            )

    def test_native_policy_pair_order_is_canonical(self) -> None:
        packages = [
            {
                "nativeLibraries": [
                    {
                        "moduleName": "zeta._native",
                        "packagedName": "libhans_py_zeta___native.so",
                    },
                    {
                        "moduleName": "alpha._native",
                        "packagedName": "libhans_py_alpha___native.so",
                    },
                ]
            }
        ]

        descriptor, header = VERIFY_RUNTIME._native_package_policy(packages)

        self.assertLess(header.index(b'"alpha._native"'), header.index(b'"zeta._native"'))
        self.assertEqual(hashlib.sha256(header).hexdigest(), descriptor["sha256"])

    def test_tampered_request_scoped_native_library_is_rejected(self) -> None:
        entry = "lib/arm64-v8a/libhans_py_msgpack___cmsgpack.so"
        self._write_apk(extra={entry: arm64_elf(marker=b"tampered-msgpack")})

        with self.assertRaisesRegex(VERIFY_RUNTIME.VerificationError, "native Python artifact mismatch"):
            self._verify()

    def test_executable_python_asset_mode_is_rejected(self) -> None:
        self._write_apk(executable_asset="assets/hans/python/resolver.pyz")

        with self.assertRaisesRegex(VERIFY_RUNTIME.VerificationError, "executable mode"):
            self._verify()

    def test_foreign_abi_python_library_is_rejected(self) -> None:
        expected = "lib/arm64-v8a/libhans_py_math.so"
        self._write_apk(
            omit={expected},
            extra={"lib/x86_64/libhans_py_math.so": self.module},
        )

        with self.assertRaisesRegex(VERIFY_RUNTIME.VerificationError, "ABI mismatch"):
            self._verify()

    def test_unexpected_python_native_library_is_rejected(self) -> None:
        self._write_apk(
            extra={"lib/arm64-v8a/libhans_py_injected.so": arm64_elf(marker=b"injected")}
        )

        with self.assertRaisesRegex(VERIFY_RUNTIME.VerificationError, "extra=.*injected"):
            self._verify()

    def test_uncompressed_misaligned_python_native_library_is_rejected(self) -> None:
        entry = "lib/arm64-v8a/libpython3.14.so"
        self._write_apk(stored_native=entry)

        with self.assertRaisesRegex(VERIFY_RUNTIME.VerificationError, "not 16 KiB aligned"):
            self._verify()

    def test_four_k_python_elf_is_rejected(self) -> None:
        self.core = arm64_elf(load_alignment=4096, marker=b"four-k")
        self.runtime["coreLibraries"] = [self._descriptor("libpython3.14.so", self.core)]
        self._write_metadata()
        self._write_apk()

        with self.assertRaisesRegex(VERIFY_RUNTIME.VerificationError, "PT_LOAD alignment"):
            self._verify()

    def test_request_scoped_module_cannot_enter_process_allowlist(self) -> None:
        standard_names = tuple(sorted(f"stdlib_{index}" for index in range(40)))
        native_modules = [
                {
                    "module": module,
                    "packagedName": f"libhans_py_{module}.so",
                    "bytes": 1,
                    "sha256": "1" * 64,
                }
                for module in standard_names
            ] + [
                {
                    "module": "msgpack._cmsgpack",
                    "packagedName": "libhans_py_msgpack___cmsgpack.so",
                    "bytes": 2,
                    "sha256": "2" * 64,
                }
            ]
        public = {"nativeModules": sorted(native_modules, key=lambda item: item["module"])}
        request_scoped = {
            "lib/arm64-v8a/libhans_py_msgpack___cmsgpack.so": {
                "module": "msgpack._cmsgpack",
                "packagedName": "libhans_py_msgpack___cmsgpack.so",
                "bytes": 2,
                "sha256": "2" * 64,
            }
        }

        VERIFY_RUNTIME._verify_native_module_scopes(
            public, request_scoped, standard_names
        )
        with self.assertRaisesRegex(
            VERIFY_RUNTIME.VerificationError, "leaked into the process-wide"
        ):
            VERIFY_RUNTIME._verify_native_module_scopes(
                public,
                request_scoped,
                (*standard_names, "msgpack._cmsgpack"),
            )


class PythonReleaseGateWiringTest(unittest.TestCase):
    def test_release_apk_verification_is_in_supply_chain_and_release_boundary(self) -> None:
        gradle = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        release_task = "verifyStandardReleaseApkPythonRuntime"
        self.assertIn(f"val {release_task}", gradle)
        supply_chain = gradle[gradle.index("val verifyPythonRuntimeSupplyChain") :]
        supply_chain = supply_chain[: supply_chain.index("val verifyStandardSourceBoundary")]
        self.assertIn(release_task, supply_chain)
        release_boundary = gradle[gradle.index("val verifyStandardReleaseStructuralBoundary") :]
        release_boundary = release_boundary[: release_boundary.index("val verifyStandardStructuralBoundaries")]
        self.assertIn(release_task, release_boundary)
        self.assertIn("standardReleaseApk", release_boundary)

    def test_native_package_lock_is_a_staging_and_verification_input(self) -> None:
        gradle = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn(
            'val pythonNativePackagesLockFile = rootProject.file("python-runtime/native-packages.lock.json")',
            gradle,
        )
        self.assertGreaterEqual(gradle.count("inputs.file(pythonNativePackagesLockFile)"), 4)


if __name__ == "__main__":
    unittest.main()
