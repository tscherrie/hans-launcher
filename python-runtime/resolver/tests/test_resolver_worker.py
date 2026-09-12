from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
BUILD_SCRIPT = ROOT / "scripts" / "build_resolver_bundle.py"


def candidate(
    name: str,
    version: str,
    dependencies: list[str] | None = None,
    *,
    requires_python: str | None = ">=3.14",
    yanked: bool = False,
    filename: str | None = None,
) -> dict:
    normalized = name.lower().replace("_", "-")
    filename_name = normalized.replace("-", "_")
    return {
        "name": normalized,
        "version": version,
        "filename": filename or f"{filename_name}-{version}-py3-none-any.whl",
        "url": f"https://files.pythonhosted.org/packages/{filename_name}-{version}.whl",
        "sha256": (normalized + version).encode().hex().ljust(64, "0")[:64],
        "sizeBytes": 1234,
        "requiresPython": requires_python,
        "yanked": yanked,
        "requiresDist": dependencies or [],
    }


class ResolverWorkerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory()
        cls.base = Path(cls.temporary.name) / "resolver.pyz"
        subprocess.run(
            [sys.executable, str(BUILD_SCRIPT), "--output", str(cls.base)],
            check=True,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    def resolve(self, requirements: list[str], catalog: dict[str, list[dict]]) -> dict:
        request = {
            "schemaVersion": 1,
            "pluginId": "test.plugin",
            "requirements": requirements,
            "target": {
                "pythonVersion": "3.14.7",
                "interpreterTag": "cp314",
                "androidAbi": "arm64-v8a",
                "minimumAndroidApi": 31,
            },
        }
        target = Path(self.temporary.name) / f"case-{len(list(Path(self.temporary.name).glob('case-*')))}.pyz"
        with zipfile.ZipFile(self.base) as source, zipfile.ZipFile(target, "w") as output:
            self.assertFalse(any("__pycache__" in name or name.endswith(".pyc") for name in source.namelist()))
            for item in source.infolist():
                output.writestr(item, source.read(item.filename))
            output.writestr("hans_resolver_payload/request.json", json.dumps(request))
            output.writestr("hans_resolver_payload/catalog.json", json.dumps(catalog))
        script = (
            "import json,sys;sys.path.insert(0,sys.argv[1]);"
            "import hans_resolver_worker as w;print(json.dumps(w.resolve(),sort_keys=True))"
        )
        completed = subprocess.run(
            [sys.executable, "-I", "-c", script, str(target)],
            check=True,
            text=True,
            capture_output=True,
        )
        return json.loads(completed.stdout)

    def test_backtracks_to_complete_transitive_closure(self) -> None:
        catalog = {
            "a": [candidate("a", "1.0", ["c<2"]), candidate("a", "2.0", ["c>=2"])],
            "b": [candidate("b", "1.0", ["c<2"])],
            "c": [candidate("c", "1.5"), candidate("c", "2.5")],
        }
        result = self.resolve(["a>=1", "b"], catalog)
        self.assertEqual(result["status"], "resolved")
        self.assertEqual(
            [(item["name"], item["version"]) for item in result["selected"]],
            [("a", "1.0"), ("b", "1.0"), ("c", "1.5")],
        )

    def test_extras_markers_and_android_environment(self) -> None:
        catalog = {
            "feature": [candidate(
                "feature",
                "1.0",
                [
                    "fastdep; extra == 'fast'",
                    "androiddep; sys_platform == 'android'",
                    "windep; sys_platform == 'win32'",
                    "olddep; python_version < '3.14'",
                ],
            )],
            "fastdep": [candidate("fastdep", "1.0")],
            "androiddep": [candidate("androiddep", "1.0")],
        }
        result = self.resolve(["feature[fast]"], catalog)
        self.assertEqual(
            [item["name"] for item in result["selected"]],
            ["androiddep", "fastdep", "feature"],
        )

    def test_late_extra_requirement_repins_and_adds_dependencies(self) -> None:
        catalog = {
            "a": [candidate("a", "1.0", ["shared"])],
            "b": [candidate("b", "1.0", ["shared[fast]"])],
            "shared": [candidate("shared", "1.0", ["fastdep; extra == 'fast'"])],
            "fastdep": [candidate("fastdep", "1.0")],
        }
        result = self.resolve(["a", "b"], catalog)
        self.assertEqual(
            [item["name"] for item in result["selected"]],
            ["a", "b", "fastdep", "shared"],
        )

    def test_prerelease_and_yanked_rules(self) -> None:
        catalog = {
            "release": [candidate("release", "1.0"), candidate("release", "2.0rc1")],
            "pinned": [candidate("pinned", "1.0", yanked=True)],
        }
        result = self.resolve(["release>=1", "pinned==1.0"], catalog)
        versions = {item["name"]: item["version"] for item in result["selected"]}
        self.assertEqual(versions, {"pinned": "1.0", "release": "1.0"})

        prerelease_only = self.resolve(
            ["future>=2"],
            {"future": [candidate("future", "2.1rc1"), candidate("future", "1.9")]},
        )
        self.assertEqual(prerelease_only["selected"][0]["version"], "2.1rc1")
        rejected_yank = self.resolve(
            ["withdrawn>=1"],
            {"withdrawn": [candidate("withdrawn", "1.0", yanked=True)]},
        )
        self.assertEqual(rejected_yank["errorCode"], "dependency_conflict")

    def test_requires_python_filters_candidate(self) -> None:
        result = self.resolve(
            ["demo"],
            {"demo": [
                candidate("demo", "1.0", requires_python=">=3.15"),
                candidate("demo", "0.9", requires_python=">=3.13"),
            ]},
        )
        self.assertEqual(result["selected"][0]["version"], "0.9")

    def test_requests_missing_projects_incrementally(self) -> None:
        first = self.resolve(["root>=1"], {})
        self.assertEqual(first, {
            "schemaVersion": 1,
            "status": "needs_projects",
            "projects": ["root"],
        })
        second = self.resolve(["root>=1"], {"root": [candidate("root", "1.0", ["child"]) ]})
        self.assertEqual(second["status"], "needs_projects")
        self.assertEqual(second["projects"], ["child"])

    def test_conflict_and_forbidden_direct_dependency_fail_closed(self) -> None:
        conflict = self.resolve(
            ["a", "c<2"],
            {"a": [candidate("a", "1.0", ["c>=2"])], "c": [candidate("c", "2.0")]},
        )
        self.assertEqual(conflict["errorCode"], "dependency_conflict")
        hostile = self.resolve(
            ["a"],
            {"a": [candidate("a", "1.0", ["evil @ https://attacker.invalid/evil.whl"])]},
        )
        self.assertEqual(hostile["errorCode"], "dependency_conflict")
        invalid_root = self.resolve(
            ["evil @ https://attacker.invalid/evil.whl"],
            {},
        )
        self.assertEqual(invalid_root["errorCode"], "invalid_requirement")

    def test_compatible_pure_wheel_tags_use_packaging_semantics(self) -> None:
        cp314 = candidate(
            "exact",
            "1.0",
            filename="exact-1.0-cp314-none-any.whl",
        )
        mixed = candidate(
            "mixed",
            "1.0",
            filename="mixed-1.0-py313.py314-none-any.whl",
        )
        result = self.resolve(["exact", "mixed"], {"exact": [cp314], "mixed": [mixed]})
        self.assertEqual([item["name"] for item in result["selected"]], ["exact", "mixed"])

    def test_output_is_reproducible(self) -> None:
        catalog = {"demo": [candidate("demo", "1.0"), candidate("demo", "2.0")]}
        self.assertEqual(self.resolve(["demo"], catalog), self.resolve(["demo"], catalog))


if __name__ == "__main__":
    unittest.main()
