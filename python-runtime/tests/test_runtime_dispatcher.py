from __future__ import annotations

import hashlib
import contextlib
import importlib.util
import json
import os
import sys
import tempfile
import types
import unittest
import zipfile
from pathlib import Path


RUNTIME_SOURCE = Path(__file__).resolve().parents[1] / "runtime"


class AndroidBridge(types.ModuleType):
    def __init__(self) -> None:
        super().__init__("_hans_android")
        self.events: list[str] = []
        self.cancel_requested = False
        # Keep the fixture archive decoded before the descriptor-backed finder
        # is installed. Opening ZipFile recursively from _archive_lookup would
        # itself import filename codecs and exercise this mock finder again.
        # Native code never has that cycle: it indexes STORED members with
        # pread(2), so the host double mirrors the indexed member table.
        self.environment_members: dict[str, bytes] = {}

    def emit(self, event: str) -> None:
        self.events.append(event)

    def call(self, request: str) -> str:
        value = json.loads(request)
        return json.dumps(
            {
                "protocolVersion": 1,
                "requestId": value["requestId"],
                "sequence": value["sequence"],
                "status": "succeeded",
                "value": {"echo": value["capability"]["arguments"]},
            }
        )

    def cancelled(self) -> bool:
        return self.cancel_requested

    def _archive_read(self, kind: str, member: str) -> bytes | None:
        if kind != "environment":
            return None
        return self.environment_members.get(member)

    def _archive_lookup(self, fullname: str):
        stem = fullname.replace(".", "/")
        for member, package in ((f"{stem}/__init__.py", True), (f"{stem}.py", False)):
            source = self._archive_read("environment", member)
            if source is not None:
                return source, f"hans-fd://environment/{member}", package
        return None


def load_dispatcher(bridge: AndroidBridge):
    sys.modules["_hans_android"] = bridge
    fd_importer_spec = importlib.util.spec_from_file_location(
        "hans_fd_importer", RUNTIME_SOURCE / "hans_fd_importer.py"
    )
    assert fd_importer_spec is not None and fd_importer_spec.loader is not None
    fd_importer = importlib.util.module_from_spec(fd_importer_spec)
    sys.modules["hans_fd_importer"] = fd_importer
    fd_importer_spec.loader.exec_module(fd_importer)
    fd_importer.install()
    importer = types.ModuleType("hans_native_importer")
    importer.HansNativeValidationError = ValueError
    importer.install = lambda directory: directory
    importer.scopes = []

    @contextlib.contextmanager
    def request_scope(environment_digest, descriptors):
        importer.scopes.append((environment_digest, descriptors))
        try:
            yield
        finally:
            importer.scope_exited = True

    importer.request_scope = request_scope
    importer.scope_exited = False
    sys.modules["hans_native_importer"] = importer
    specification = importlib.util.spec_from_file_location(
        "hans_runtime_bootstrap_test", RUNTIME_SOURCE / "hans_runtime_bootstrap.py"
    )
    assert specification is not None and specification.loader is not None
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


class RuntimeDispatcherTest(unittest.TestCase):
    def setUp(self) -> None:
        self.bridge = AndroidBridge()
        self.runtime = load_dispatcher(self.bridge)
        self.temporary = tempfile.TemporaryDirectory()
        self.environment = Path(self.temporary.name) / "environment.pyz"
        with zipfile.ZipFile(self.environment, "w") as archive:
            archive.writestr("sample_module.py", "def answer(value):\n    return value + 1\n")
            archive.writestr(
                "__hans_plugin_source__/entry.py",
                """from . import sibling
from .cycle_a import cycle_value
from .nested.worker import nested_value
from .package import PACKAGE_VALUE
import sys

def run(value):
    return {
        "plugin": value,
        "sibling": sibling.VALUE,
        "nested": nested_value(),
        "cycle": cycle_value(),
        "packageInit": PACKAGE_VALUE,
        "origin": __file__,
        "package": __package__,
        "unsafePathCount": sum(
            1 for path in sys.path
            if "__hans_plugin_source__" in path or path.startswith("hans-fd://")
        ),
    }

def proof_only():
    raise AssertionError("proof invoked the callable")
""",
            )
            archive.writestr(
                "__hans_plugin_source__/__init__.py",
                "ROOT_INITIALIZED = 'root-init'\n",
            )
            archive.writestr(
                "__hans_plugin_source__/sibling.py",
                "VALUE = 'sibling'\n",
            )
            archive.writestr(
                "__hans_plugin_source__/nested/__init__.py",
                "from .worker import nested_value\n",
            )
            archive.writestr(
                "__hans_plugin_source__/nested/worker.py",
                "from .. import sibling\n\ndef nested_value():\n    return sibling.VALUE + '-nested'\n",
            )
            archive.writestr(
                "__hans_plugin_source__/cycle_a.py",
                "TOKEN = 'a'\nfrom . import cycle_b\n\ndef cycle_value():\n    return TOKEN + cycle_b.TOKEN\n",
            )
            archive.writestr(
                "__hans_plugin_source__/cycle_b.py",
                "from . import cycle_a\nTOKEN = cycle_a.TOKEN + 'b'\n",
            )
            archive.writestr(
                "__hans_plugin_source__/package/__init__.py",
                "PACKAGE_VALUE = 'package-init'\n\ndef package_entry():\n    return PACKAGE_VALUE\n",
            )
            archive.writestr(
                "__hans_plugin_source__/broken.py",
                "raise RuntimeError('broken import')\n",
            )
            archive.writestr(
                "__hans_plugin_source__/mutate_importers.py",
                "import sys\n\ndef run():\n    sys.meta_path = []\n    return 'mutated'\n",
            )
            archive.writestr(
                "__hans_plugin_source__/collision.py",
                "def run():\n    return 'module'\n",
            )
            archive.writestr(
                "__hans_plugin_source__/collision/__init__.py",
                "def run():\n    return 'package'\n",
            )
        self.digest = hashlib.sha256(self.environment.read_bytes()).hexdigest()
        with zipfile.ZipFile(self.environment) as archive:
            self.bridge.environment_members = {
                name: archive.read(name) for name in archive.namelist()
            }

    def tearDown(self) -> None:
        sys.meta_path[:] = [
            finder
            for finder in sys.meta_path
            if finder.__class__.__name__ != "HansFdSourceFinder"
        ]
        sys.modules.pop("sample_module", None)
        for name in tuple(sys.modules):
            if name.startswith("_hans_plugin_d"):
                sys.modules.pop(name, None)
        self.bridge.environment_members.clear()
        self.temporary.cleanup()

    def request(self, entrypoint: dict, **overrides) -> str:
        value = {
            "protocolVersion": 1,
            "requestId": "request-1",
            "idempotencyKey": "idempotency-1",
            "environmentDigest": self.digest,
            "allowedNativeModules": [],
            "entrypoint": entrypoint,
            "arguments": {},
            "workspaceHandle": None,
            "allowedCapabilities": [],
            "limits": {
                "deadlineElapsedRealtimeMillis": 1,
                "maximumStdoutBytes": 1_048_576,
                "maximumStderrBytes": 1_048_576,
                "maximumResultBytes": 1_048_576,
                "maximumEvents": 4096,
            },
        }
        value.update(overrides)
        return json.dumps(value)

    def execute(self, entrypoint: dict, **overrides) -> dict:
        return json.loads(self.runtime.execute_json(self.request(entrypoint, **overrides)))

    def workspace_lease(self, files: dict[str, bytes]):
        entries = []
        content_digest = hashlib.sha256(b"hans-workspace-manifest-v1\n")
        for relative_path in sorted(files):
            value = files[relative_path]
            digest = hashlib.sha256(value).hexdigest()
            entries.append(
                {
                    "relativePath": relative_path,
                    "byteCount": len(value),
                    "sha256": digest,
                }
            )
            content_digest.update(relative_path.encode())
            content_digest.update(b"\0")
            content_digest.update(str(len(value)).encode("ascii"))
            content_digest.update(b"\0")
            content_digest.update(digest.encode("ascii"))
            content_digest.update(b"\n")
        handle = content_digest.hexdigest()
        archive_path = Path(self.temporary.name) / f"workspace-{handle}.zip"
        manifest = {
            "protocolVersion": 1,
            "workspaceHandle": handle,
            "files": entries,
        }
        with zipfile.ZipFile(archive_path, "w") as archive:
            archive.writestr("__hans_workspace_manifest__.json", json.dumps(manifest))
            for relative_path in sorted(files):
                archive.writestr(
                    "__hans_workspace_files__/" + relative_path,
                    files[relative_path],
                )
        descriptor = os.open(archive_path, os.O_RDONLY)
        lease = {
            "workspaceFd": descriptor,
            "workspaceHandle": handle,
            "archiveDigest": hashlib.sha256(archive_path.read_bytes()).hexdigest(),
            "archiveBytes": archive_path.stat().st_size,
            "fileCount": len(files),
            "contentBytes": sum(len(value) for value in files.values()),
        }
        return handle, descriptor, lease

    def test_code_streams_unicode_in_bounded_events_and_returns_value(self) -> None:
        text = "ä" * 10_000
        result = self.execute(
            {"kind": "code", "source": f"print({text!r}); result = 42"}
        )
        self.assertEqual("SUCCEEDED", result["status"])
        self.assertEqual(42, result["value"])
        events = [json.loads(value) for value in self.bridge.events]
        self.assertGreater(len(events), 1)
        self.assertEqual(text + "\n", "".join(event["text"] for event in events))
        self.assertTrue(all(len(event["text"].encode()) <= 8192 for event in events))

    def test_native_allowlist_is_request_scoped_and_lease_bound(self) -> None:
        descriptor = {
            "module": "msgpack._cmsgpack",
            "packagedName": "libhans_py_msgpack___cmsgpack.so",
        }
        result = self.execute(
            {"kind": "code", "source": "result = 42"},
            allowedNativeModules=[descriptor],
            runtimeLease={"allowedNativeModules": [descriptor]},
        )

        self.assertEqual("SUCCEEDED", result["status"])
        importer = sys.modules["hans_native_importer"]
        self.assertEqual((self.digest, [descriptor]), importer.scopes[-1])
        self.assertTrue(importer.scope_exited)

        missing_lease = self.execute(
            {"kind": "code", "source": "result = 42"},
            allowedNativeModules=[descriptor],
        )
        self.assertEqual("INVALID_REQUEST", missing_lease["status"])
        self.assertIn("requires a verified runtime lease", missing_lease["errorMessage"])

        mismatch = self.execute(
            {"kind": "code", "source": "result = 42"},
            allowedNativeModules=[descriptor],
            runtimeLease={"allowedNativeModules": []},
        )
        self.assertEqual("INVALID_REQUEST", mismatch["status"])
        self.assertIn("verified runtime lease", mismatch["errorMessage"])

    def test_capability_round_trip_obeys_allowlist(self) -> None:
        source = "result = hans_capability('phone.location', {'fresh': True})"
        result = self.execute(
            {"kind": "code", "source": source},
            allowedCapabilities=["phone.location"],
        )
        self.assertEqual("SUCCEEDED", result["status"])
        self.assertEqual({"echo": {"fresh": True}}, result["value"])

        denied = self.execute({"kind": "code", "source": source})
        self.assertEqual("CAPABILITY_DENIED", denied["status"])
        self.assertEqual("capability_denied", denied["errorCode"])

    def test_module_and_plugin_load_directly_from_verified_pyz(self) -> None:
        module = self.execute(
            {"kind": "module", "module": "sample_module", "function": "answer"},
            arguments=4,
        )
        self.assertEqual("SUCCEEDED", module["status"])
        self.assertEqual(5, module["value"])
        self.assertNotIn("sample_module", sys.modules)

        plugin = self.execute(
            {
                "kind": "plugin",
                "pluginId": "example",
                "relativePath": "entry.py",
                "function": "run",
            },
            arguments="ok",
        )
        self.assertEqual("SUCCEEDED", plugin["status"])
        self.assertEqual("ok", plugin["value"]["plugin"])
        self.assertEqual("sibling", plugin["value"]["sibling"])
        self.assertEqual("sibling-nested", plugin["value"]["nested"])
        self.assertEqual("aab", plugin["value"]["cycle"])
        self.assertEqual("package-init", plugin["value"]["packageInit"])
        self.assertTrue(
            plugin["value"]["origin"].startswith(
                "hans-fd://environment/__hans_plugin_source__/"
            )
        )
        self.assertTrue(plugin["value"]["package"].startswith("_hans_plugin_d"))
        self.assertEqual(0, plugin["value"]["unsafePathCount"])
        self.assertFalse(
            any(name.startswith("_hans_plugin_d") for name in sys.modules),
            "request-scoped plugin modules leaked after execution",
        )

        package = self.execute(
            {
                "kind": "plugin",
                "pluginId": "example",
                "relativePath": "package/__init__.py",
                "function": "package_entry",
            }
        )
        self.assertEqual("SUCCEEDED", package["status"])
        self.assertEqual("package-init", package["value"])

    def test_plugin_import_scope_is_evicted_after_failure_and_reloads_fresh_source(self) -> None:
        meta_path_before = tuple(sys.meta_path)
        failed = self.execute(
            {
                "kind": "plugin",
                "pluginId": "example",
                "relativePath": "broken.py",
                "function": "run",
            }
        )
        self.assertEqual("PYTHON_EXCEPTION", failed["status"])
        self.assertEqual(meta_path_before, tuple(sys.meta_path))
        self.assertFalse(any(name.startswith("_hans_plugin_d") for name in sys.modules))

        mutated = self.execute(
            {
                "kind": "plugin",
                "pluginId": "example",
                "relativePath": "mutate_importers.py",
                "function": "run",
            }
        )
        self.assertEqual("SUCCEEDED", mutated["status"])
        self.assertEqual("mutated", mutated["value"])
        self.assertEqual(meta_path_before, tuple(sys.meta_path))

        first = self.execute(
            {
                "kind": "plugin",
                "pluginId": "example",
                "relativePath": "sibling.py",
                "function": "VALUE",
            }
        )
        self.assertEqual("INVALID_REQUEST", first["status"])
        self.bridge.environment_members["__hans_plugin_source__/fresh.py"] = (
            b"def run():\n    return 'first'\n"
        )
        loaded_first = self.execute(
            {
                "kind": "plugin",
                "pluginId": "example",
                "relativePath": "fresh.py",
                "function": "run",
            }
        )
        self.assertEqual("first", loaded_first["value"])
        self.bridge.environment_members["__hans_plugin_source__/fresh.py"] = (
            b"def run():\n    return 'second'\n"
        )
        loaded_second = self.execute(
            {
                "kind": "plugin",
                "pluginId": "example",
                "relativePath": "fresh.py",
                "function": "run",
            }
        )
        self.assertEqual("second", loaded_second["value"])
        self.assertFalse(any(name.startswith("_hans_plugin_d") for name in sys.modules))

    def test_plugin_collisions_reserved_namespace_and_namespace_preoccupation_fail_closed(self) -> None:
        collision = self.execute(
            {
                "kind": "plugin",
                "pluginId": "example",
                "relativePath": "collision.py",
                "function": "run",
            }
        )
        self.assertEqual("INVALID_REQUEST", collision["status"])
        self.assertIn("collision", collision["errorMessage"])

        reserved = self.execute(
            {
                "kind": "code",
                "source": "import __hans_plugin_source__.entry\nresult = 1",
            }
        )
        self.assertEqual("PYTHON_EXCEPTION", reserved["status"])
        self.assertIn("reserved Hans plugin source namespace", reserved["errorMessage"])

        namespace = "_hans_plugin_d" + self.digest
        foreign = types.ModuleType(namespace)
        sys.modules[namespace] = foreign
        try:
            occupied = self.execute(
                {
                    "kind": "plugin",
                    "pluginId": "example",
                    "relativePath": "entry.py",
                    "function": "run",
                }
            )
            self.assertEqual("INVALID_REQUEST", occupied["status"])
            self.assertIs(foreign, sys.modules.get(namespace))
        finally:
            sys.modules.pop(namespace, None)

    def test_plugin_source_and_module_counts_are_bounded(self) -> None:
        self.bridge.environment_members["__hans_plugin_source__/oversized.py"] = (
            b"#" * (8 * 1024 * 1024 + 1)
        )
        oversized = self.execute(
            {
                "kind": "plugin",
                "pluginId": "example",
                "relativePath": "oversized.py",
                "function": "run",
            }
        )
        self.assertEqual("PYTHON_EXCEPTION", oversized["status"])
        self.assertIn("byte limit", oversized["errorMessage"])

        for index in range(512):
            self.bridge.environment_members[
                f"__hans_plugin_source__/bounded_{index}.py"
            ] = b"VALUE = 1\n"
        self.bridge.environment_members["__hans_plugin_source__/many.py"] = (
            b"def run():\n"
            b"    for index in range(512):\n"
            b"        __import__(__package__ + '.bounded_' + str(index), fromlist=('*',))\n"
            b"    return True\n"
        )
        too_many = self.execute(
            {
                "kind": "plugin",
                "pluginId": "example",
                "relativePath": "many.py",
                "function": "run",
            }
        )
        self.assertEqual("PYTHON_EXCEPTION", too_many["status"])
        self.assertIn("too many source modules", too_many["errorMessage"])
        self.assertFalse(any(name.startswith("_hans_plugin_d") for name in sys.modules))

    def test_workspace_reads_and_imports_verified_descriptor_without_write_access(self) -> None:
        handle, descriptor, lease = self.workspace_lease(
            {
                "data/message.txt": b"hello workspace",
                "project_module.py": b"VALUE = 41\n",
                "project_package/__init__.py": b"from .worker import answer\n",
                "project_package/worker.py": b"def answer():\n    return 42\n",
            }
        )
        try:
            result = self.execute(
                {
                    "kind": "code",
                    "source": """
import hans_workspace
import project_module
from project_package import answer
result = {
    'text': hans_workspace.read_text('data/message.txt'),
    'module': project_module.VALUE + 1,
    'package': answer(),
    'origin': project_module.__file__,
    'paths': list(hans_workspace.paths()),
}
""",
                },
                workspaceHandle=handle,
                runtimeLease={"workspace": lease},
            )
        finally:
            os.close(descriptor)
        self.assertEqual("SUCCEEDED", result["status"])
        self.assertEqual("hello workspace", result["value"]["text"])
        self.assertEqual(42, result["value"]["module"])
        self.assertEqual(42, result["value"]["package"])
        self.assertTrue(result["value"]["origin"].startswith("hans-workspace://"))
        self.assertEqual(
            [
                "data/message.txt",
                "project_module.py",
                "project_package/__init__.py",
                "project_package/worker.py",
            ],
            result["value"]["paths"],
        )
        self.assertNotIn("project_module", sys.modules)
        self.assertNotIn("project_package", sys.modules)
        self.assertNotIn("hans_workspace", sys.modules)

        handle, descriptor, lease = self.workspace_lease({"value.txt": b"immutable"})
        try:
            write = self.execute(
                {
                    "kind": "code",
                    "source": "import hans_workspace\nhans_workspace.open('value.txt', 'w')",
                },
                workspaceHandle=handle,
                runtimeLease={"workspace": lease},
            )
        finally:
            os.close(descriptor)
        self.assertEqual("PYTHON_EXCEPTION", write["status"])
        self.assertIn("read-only", write["errorMessage"])

    def test_workspace_unknown_lease_traversal_and_corruption_fail_closed(self) -> None:
        workspace = self.execute(
            {"kind": "code", "source": "result = 1"},
            workspaceHandle="a" * 64,
        )
        self.assertEqual("INVALID_REQUEST", workspace["status"])
        self.assertIn("workspace_unresolved", workspace["errorMessage"])

        handle, descriptor, lease = self.workspace_lease({"value.txt": b"immutable"})
        try:
            traversal = self.execute(
                {
                    "kind": "code",
                    "source": "import hans_workspace\nresult = hans_workspace.read_bytes('../value.txt')",
                },
                workspaceHandle=handle,
                runtimeLease={"workspace": lease},
            )
            corrupt_lease = dict(lease)
            corrupt_lease["archiveDigest"] = "0" * 64
            corrupt = self.execute(
                {"kind": "code", "source": "result = 1"},
                workspaceHandle=handle,
                runtimeLease={"workspace": corrupt_lease},
            )
        finally:
            os.close(descriptor)
        self.assertEqual("INVALID_REQUEST", traversal["status"])
        self.assertIn("escaped", traversal["errorMessage"])
        self.assertEqual("INVALID_REQUEST", corrupt["status"])
        self.assertIn("digest", corrupt["errorMessage"])

        escaped = self.execute(
            {
                "kind": "plugin",
                "pluginId": "example",
                "relativePath": "../entry.py",
                "function": "run",
            }
        )
        self.assertEqual("INVALID_REQUEST", escaped["status"])

        for relative in (
            "/entry.py",
            "nested//worker.py",
            "bad-name.py",
            "mód.py",
            "entry.py/extra",
        ):
            invalid = self.execute(
                {
                    "kind": "plugin",
                    "pluginId": "example",
                    "relativePath": relative,
                    "function": "run",
                }
            )
            self.assertEqual("INVALID_REQUEST", invalid["status"], relative)


if __name__ == "__main__":
    unittest.main()
