from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CPP = ROOT / "android/app/src/main/cpp/python"


class NativePrimitivesTest(unittest.TestCase):
    def test_json_and_sha256_primitives_compile_and_pass(self) -> None:
        compiler = os.environ.get("CXX") or shutil.which("c++") or shutil.which("clang++")
        if compiler is None:
            self.skipTest("no host C++17 compiler available")
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            executable = temporary_path / "native-primitives-test"
            stored_archive = temporary_path / "stored.pyz"
            deflated_archive = temporary_path / "deflated.pyz"
            with zipfile.ZipFile(
                stored_archive, "w", compression=zipfile.ZIP_STORED
            ) as archive:
                archive.writestr("sample.py", b"result = 42\n")
                archive.writestr("empty.py", b"")
            with zipfile.ZipFile(
                deflated_archive, "w", compression=zipfile.ZIP_DEFLATED
            ) as archive:
                archive.writestr("sample.py", b"result = 42\n")
            subprocess.run(
                [
                    compiler,
                    "-std=c++17",
                    "-Wall",
                    "-Wextra",
                    "-Werror",
                    f"-I{CPP}",
                    str(Path(__file__).with_name("native_primitives_test.cpp")),
                    str(CPP / "hans_json.cpp"),
                    str(CPP / "hans_sha256.cpp"),
                    str(CPP / "hans_stored_zip.cpp"),
                    "-o",
                    str(executable),
                ],
                check=True,
            )
            subprocess.run(
                [str(executable), str(stored_archive), str(deflated_archive)],
                check=True,
            )


if __name__ == "__main__":
    unittest.main()
