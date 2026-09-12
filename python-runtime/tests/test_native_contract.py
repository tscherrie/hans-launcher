from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class NativeContractTest(unittest.TestCase):
    def test_isolated_fd_and_jni_contract_remains_explicit(self) -> None:
        source = (
            ROOT / "android/app/src/main/cpp/python/hans_python_jni.cpp"
        ).read_text(encoding="utf-8")
        self.assertIn('"stdlibFd"', source)
        self.assertIn('"expectedStdlibBytes"', source)
        self.assertIn('"runtimeLease"', source)
        self.assertIn("F_DUPFD_CLOEXEC", source)
        self.assertNotIn('"/proc/self/fd/"', source)
        self.assertIn("g_stdlib_archive", source)
        self.assertIn("_archive_lookup", source)
        self.assertIn('PyImport_AppendInittab("_android_support"', source)
        self.assertIn("HansAndroidInitStreams", source)
        self.assertNotIn('"stdlibZip"', source)
        self.assertIn("onNativeCapabilityRequest", source)

    def test_process_creation_is_denied_by_native_audit_and_locked_primitives(self) -> None:
        source = (
            ROOT / "android/app/src/main/cpp/python/hans_python_jni.cpp"
        ).read_text(encoding="utf-8")
        self.assertIn("PySys_AddAuditHook(HansAuditHook", source)
        for event in (
            '"os.system"',
            '"os.fork"',
            '"os.exec"',
            '"os.posix_spawn"',
            '"subprocess.Popen"',
            '"ctypes.dlopen"',
        ):
            self.assertIn(event, source)
        self.assertIn("InstallProcessPrimitiveLockdown", source)
        self.assertIn('{"_posixsubprocess", "fork_exec"}', source)
        self.assertIn('{"subprocess", "_fork_exec"}', source)
        self.assertIn('"_process_denied"', source)

    def test_native_linker_contract_is_16k_and_api_is_gradle_pinned(self) -> None:
        cmake = (ROOT / "android/app/src/main/cpp/python/CMakeLists.txt").read_text()
        gradle = (ROOT / "android/app/build.gradle.kts").read_text()
        self.assertIn("-Wl,-z,max-page-size=16384", cmake)
        self.assertIn("-Wl,-z,common-page-size=16384", cmake)
        self.assertIn('"-DANDROID_PLATFORM=android-$pythonRuntimeHostApi"', gradle)
        self.assertIn('jniLibs.srcDir(layout.buildDirectory.dir("generated/pythonRuntime', gradle)

    def test_asyncio_native_dependency_is_not_excluded(self) -> None:
        lock = json.loads(
            (ROOT / "python-runtime/python.lock.json").read_text(encoding="utf-8")
        )
        excluded = lock["runtime"]["nativeExtensions"]["excludedModules"]
        self.assertNotIn("_posixsubprocess", excluded)
        verifier = (
            ROOT / "python-runtime/scripts/verify_runtime.py"
        ).read_text(encoding="utf-8")
        self.assertIn('"_posixsubprocess"', verifier)


if __name__ == "__main__":
    unittest.main()
