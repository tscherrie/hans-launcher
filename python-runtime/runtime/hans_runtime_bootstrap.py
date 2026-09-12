"""Stable JSON execution dispatcher for Hans' embedded CPython worker."""

from __future__ import annotations

import contextlib
import hashlib
import importlib
import importlib.abc
import importlib.machinery
import io
import json
import os
import stat
import sys
import time
import traceback
import types
import zipfile
from collections.abc import Mapping
from typing import Any

import _hans_android
import hans_fd_importer
from hans_native_importer import (
    HansNativeValidationError,
    install as install_native_importer,
    request_scope as native_import_scope,
)


PROTOCOL_VERSION = 1
DEFAULT_OUTPUT_LIMIT = 1_048_576
MAX_EVENT_TEXT_BYTES = 8 * 1024
MAX_CAPABILITY_JSON_BYTES = 60 * 1024
MAX_WORKSPACE_ARCHIVE_BYTES = 192 * 1024 * 1024
MAX_WORKSPACE_CONTENT_BYTES = 128 * 1024 * 1024
MAX_WORKSPACE_FILES = 2048
MAX_WORKSPACE_PATH_BYTES = 1024
MAX_WORKSPACE_MANIFEST_BYTES = 8 * 1024 * 1024
MAX_WORKSPACE_SOURCE_BYTES = 8 * 1024 * 1024
WORKSPACE_MANIFEST_MEMBER = "__hans_workspace_manifest__.json"
WORKSPACE_FILE_PREFIX = "__hans_workspace_files__/"
WORKSPACE_ORIGIN_PREFIX = "hans-workspace://"
_context: "_ExecutionContext | None" = None


class InvalidRequest(ValueError):
    pass


class OutputLimitExceeded(RuntimeError):
    pass


class HansCapabilityError(RuntimeError):
    pass


class HansCapabilityDenied(HansCapabilityError):
    pass


class DependencyMissing(RuntimeError):
    pass


class _ExecutionContext:
    def __init__(
        self,
        request_id: str,
        stdout_limit: int,
        stderr_limit: int,
        maximum_events: int,
        allowed_capabilities: frozenset[str],
    ) -> None:
        self.request_id = request_id
        self.stdout_limit = stdout_limit
        self.stderr_limit = stderr_limit
        self.maximum_events = maximum_events
        self.allowed_capabilities = allowed_capabilities
        self.sequence = 0
        self.stdout_bytes = 0
        self.stderr_bytes = 0
        self.events = 0

    def _next_sequence(self) -> int:
        self.sequence += 1
        return self.sequence

    def stream(self, stream: str, text: str) -> None:
        if stream not in {"stdout", "stderr", "progress"}:
            raise ValueError(f"unsupported stream: {stream}")
        encoded = text.encode("utf-8")
        if stream == "stdout":
            self.stdout_bytes += len(encoded)
            if self.stdout_bytes > self.stdout_limit:
                raise OutputLimitExceeded(
                    f"stdout exceeded {self.stdout_limit} UTF-8 bytes"
                )
        elif stream == "stderr":
            self.stderr_bytes += len(encoded)
            if self.stderr_bytes > self.stderr_limit:
                raise OutputLimitExceeded(
                    f"stderr exceeded {self.stderr_limit} UTF-8 bytes"
                )
        for chunk in _utf8_chunks(text, MAX_EVENT_TEXT_BYTES):
            if self.events >= self.maximum_events:
                raise OutputLimitExceeded(f"event count exceeded {self.maximum_events}")
            event = {
                "protocolVersion": PROTOCOL_VERSION,
                "type": "stream",
                "requestId": self.request_id,
                "sequence": self._next_sequence(),
                "stream": stream,
                "text": chunk,
            }
            _hans_android.emit(_json(event))
            self.events += 1

    def capability(self, name: str, arguments: Any) -> Any:
        if not isinstance(name, str) or not name or len(name) > 200:
            raise ValueError("capability name must be a non-empty string")
        if name not in self.allowed_capabilities:
            raise HansCapabilityDenied(f"{name} is not allowlisted")
        if self.events >= self.maximum_events:
            raise OutputLimitExceeded(f"event count exceeded {self.maximum_events}")
        sequence = self._next_sequence()
        request = {
            "protocolVersion": PROTOCOL_VERSION,
            "type": "capability_request",
            "requestId": self.request_id,
            "sequence": sequence,
            "capability": {"name": name, "arguments": arguments},
        }
        encoded_request = _json(request)
        if len(encoded_request.encode("utf-8")) > MAX_CAPABILITY_JSON_BYTES:
            raise OutputLimitExceeded("capability request exceeds its transport limit")
        raw_response = _hans_android.call(encoded_request)
        self.events += 1
        try:
            response = json.loads(raw_response)
        except (TypeError, json.JSONDecodeError) as exc:
            raise HansCapabilityError("capability broker returned invalid JSON") from exc
        if not isinstance(response, dict):
            raise HansCapabilityError("capability broker response is not an object")
        for field, expected in (
            ("protocolVersion", PROTOCOL_VERSION),
            ("requestId", self.request_id),
            ("sequence", sequence),
        ):
            if response.get(field) != expected:
                raise HansCapabilityError(f"capability broker mismatched {field}")
        status = response.get("status")
        if status not in {"succeeded", "ok"}:
            code = response.get("errorCode") or "CAPABILITY_FAILED"
            message = response.get("errorMessage") or "capability request failed"
            raise HansCapabilityError(f"{code}: {message}")
        return response.get("value")


class _EventWriter(io.TextIOBase):
    def __init__(self, context: _ExecutionContext, stream: str) -> None:
        self._context = context
        self._stream = stream

    @property
    def encoding(self) -> str:
        return "utf-8"

    def writable(self) -> bool:
        return True

    def write(self, value: str) -> int:
        if not isinstance(value, str):
            raise TypeError("stream writes must be strings")
        if value:
            self._context.stream(self._stream, value)
        return len(value)

    def flush(self) -> None:
        return None


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def _utf8_chunks(value: str, maximum_bytes: int):
    """Yield Unicode chunks without ever splitting one UTF-8 code point."""
    start = 0
    used = 0
    for index, character in enumerate(value):
        width = len(character.encode("utf-8"))
        if used and used + width > maximum_bytes:
            yield value[start:index]
            start = index
            used = 0
        used += width
    if start < len(value):
        yield value[start:]


def _json_value(value: Any) -> Any:
    try:
        _json(value)
        return value
    except (TypeError, ValueError):
        return {"representation": repr(value), "type": type(value).__name__}


def install(native_library_dir: str) -> dict[str, Any]:
    install_native_importer(native_library_dir)
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "status": "ready",
        "pythonVersion": sys.version.split()[0],
        "platform": sys.platform,
        "androidApi": getattr(sys, "getandroidapilevel", lambda: None)(),
        "environmentTransport": "verified-read-only-fd",
    }


def progress(text: str) -> None:
    if _context is None:
        raise RuntimeError("no active Hans Python execution")
    _context.stream("progress", str(text))


def capability(name: str, arguments: Any = None) -> Any:
    if _context is None:
        raise RuntimeError("no active Hans Python execution")
    return _context.capability(name, {} if arguments is None else arguments)


def cancelled() -> bool:
    return bool(_hans_android.cancelled())


def _required_string(request: Mapping[str, Any], field: str) -> str:
    value = request.get(field)
    if not isinstance(value, str) or not value:
        raise InvalidRequest(f"{field} must be a non-empty string")
    return value


def _call_target(target: Any, arguments: Any) -> Any:
    if not callable(target):
        raise InvalidRequest("requested target is not callable")
    if isinstance(arguments, list):
        return target(*arguments)
    if isinstance(arguments, dict):
        return target(**arguments)
    return target(arguments)


def _public_callable(target: Any, callable_name: str) -> Any:
    for component in callable_name.split("."):
        if not component or component.startswith("_"):
            raise InvalidRequest("function contains a private or empty component")
        target = getattr(target, component)
    return target


def _plugin_environment(
    request: dict[str, Any], entrypoint: dict[str, Any]
) -> tuple[str, str, str]:
    plugin_id = _required_string(entrypoint, "pluginId")
    digest = _required_string(request, "environmentDigest")
    relative = _required_string(entrypoint, "relativePath")
    try:
        scope = hans_fd_importer.plugin_import_scope(plugin_id, digest)
        # Validate the path before any source module or package initializer runs.
        scope.validate_entry_path(relative)
    except hans_fd_importer.HansPluginValidationError as exc:
        raise InvalidRequest(str(exc)) from exc
    return plugin_id, digest, relative


def _execute_operation(request: dict[str, Any]) -> Any:
    entrypoint = request.get("entrypoint")
    if not isinstance(entrypoint, dict):
        raise InvalidRequest("entrypoint must be an object")
    kind = entrypoint.get("kind")
    arguments = request.get("arguments", {})
    if kind == "code":
        code = _required_string(entrypoint, "source")
        globals_dict: dict[str, Any] = {
            "__name__": "__hans_execution__",
            "__builtins__": __builtins__,
            "arguments": arguments,
            "hans_capability": capability,
            "hans_progress": progress,
            "hans_cancelled": cancelled,
        }
        exec(compile(code, "<hans>", "exec"), globals_dict, globals_dict)
        return globals_dict.get("result")
    if kind == "module":
        module_name = _required_string(entrypoint, "module")
        function = _required_string(entrypoint, "function")
        module = importlib.import_module(module_name)
        return _call_target(_public_callable(module, function), arguments)
    if kind == "plugin":
        function = _required_string(entrypoint, "function")
        plugin_id, digest, relative = _plugin_environment(request, entrypoint)
        try:
            with hans_fd_importer.plugin_import_scope(plugin_id, digest) as scope:
                target = scope.resolve_callable(relative, function)
                return _call_target(target, arguments)
        except hans_fd_importer.HansPluginValidationError as exc:
            raise InvalidRequest(str(exc)) from exc
        except hans_fd_importer.HansPluginSourceCollision as exc:
            raise InvalidRequest(str(exc)) from exc
        except hans_fd_importer.HansPluginSourceMissing as exc:
            raise DependencyMissing(
                f"environment_unavailable: missing source for {plugin_id}"
            ) from exc
    raise InvalidRequest(f"unsupported entrypoint kind: {kind}")


def _workspace_path(value: object) -> str:
    if not isinstance(value, str) or not value:
        raise InvalidRequest("workspace path must be a non-empty string")
    try:
        encoded = value.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise InvalidRequest("workspace path is not valid UTF-8") from exc
    if len(encoded) > MAX_WORKSPACE_PATH_BYTES:
        raise InvalidRequest("workspace path is too long")
    if value.startswith("/") or value.endswith("/") or "\\" in value or "\x00" in value:
        raise InvalidRequest("workspace path must be a POSIX relative file path")
    parts = value.split("/")
    if any(
        part in {"", ".", ".."}
        or len(part.encode("utf-8")) > 255
        or any(ord(character) < 32 or 127 <= ord(character) <= 159 for character in part)
        for part in parts
    ):
        raise InvalidRequest("workspace path escaped its snapshot")
    return value


def _lower_sha256(value: object) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


class _WorkspaceSourceLoader(importlib.abc.Loader):
    def __init__(
        self,
        scope: "_WorkspaceLease",
        fullname: str,
        relative_path: str,
        is_package: bool,
    ) -> None:
        self.scope = scope
        self.fullname = fullname
        self.relative_path = relative_path
        self.package = is_package
        self.origin = (
            f"{WORKSPACE_ORIGIN_PREFIX}{scope.workspace_handle}/{relative_path}"
        )

    def create_module(self, spec):  # noqa: ANN001
        return None

    def exec_module(self, module) -> None:  # noqa: ANN001
        source = self.scope.read_bytes(self.relative_path)
        if len(source) > MAX_WORKSPACE_SOURCE_BYTES:
            raise ImportError("workspace Python source exceeds its byte limit")
        code = compile(source, self.origin, "exec", dont_inherit=True)
        exec(code, module.__dict__, module.__dict__)

    def get_filename(self, fullname: str) -> str:
        if fullname != self.fullname:
            raise ImportError(fullname)
        return self.origin

    def is_package(self, fullname: str) -> bool:
        if fullname != self.fullname:
            raise ImportError(fullname)
        return self.package


class _WorkspaceSourceFinder(importlib.abc.MetaPathFinder):
    def __init__(self, scope: "_WorkspaceLease") -> None:
        self.scope = scope
        self.module_count = 0

    def find_spec(self, fullname: str, path=None, target=None):  # noqa: ANN001
        del path, target
        if not fullname or any(
            not component.isascii() or not component.isidentifier()
            for component in fullname.split(".")
        ):
            return None
        stem = fullname.replace(".", "/")
        package_path = f"{stem}/__init__.py"
        module_path = f"{stem}.py"
        has_package = self.scope.has_file(package_path)
        has_module = self.scope.has_file(module_path)
        if has_package and has_module:
            raise ImportError(f"workspace source collision for {fullname}")
        relative_path = package_path if has_package else module_path if has_module else None
        if relative_path is None:
            return None
        self.module_count += 1
        if self.module_count > MAX_WORKSPACE_FILES:
            raise ImportError("workspace imported too many modules")
        loader = _WorkspaceSourceLoader(
            self.scope,
            fullname,
            relative_path,
            has_package,
        )
        specification = importlib.machinery.ModuleSpec(
            fullname,
            loader,
            origin=loader.origin,
            is_package=has_package,
        )
        specification.has_location = True
        return specification


class _WorkspaceLease:
    def __init__(self, workspace_handle: str, lease: dict[str, Any]) -> None:
        expected_keys = {
            "workspaceFd",
            "workspaceHandle",
            "archiveDigest",
            "archiveBytes",
            "fileCount",
            "contentBytes",
        }
        if set(lease) != expected_keys:
            raise InvalidRequest("workspace lease fields do not match the runtime contract")
        if not _lower_sha256(workspace_handle) or lease.get("workspaceHandle") != workspace_handle:
            raise InvalidRequest("workspace lease does not match the requested handle")
        archive_digest = lease.get("archiveDigest")
        archive_bytes = lease.get("archiveBytes")
        file_count = lease.get("fileCount")
        content_bytes = lease.get("contentBytes")
        source_fd = lease.get("workspaceFd")
        if (
            not _lower_sha256(archive_digest)
            or not isinstance(source_fd, int)
            or source_fd < 0
            or not isinstance(archive_bytes, int)
            or not 22 <= archive_bytes <= MAX_WORKSPACE_ARCHIVE_BYTES
            or not isinstance(file_count, int)
            or not 0 <= file_count <= MAX_WORKSPACE_FILES
            or not isinstance(content_bytes, int)
            or not 0 <= content_bytes <= MAX_WORKSPACE_CONTENT_BYTES
        ):
            raise InvalidRequest("workspace lease manifest is invalid")
        try:
            duplicate = os.dup(source_fd)
        except OSError as exc:
            raise InvalidRequest("workspace descriptor is unavailable") from exc
        self.workspace_handle = workspace_handle
        self.archive_digest = archive_digest
        self.archive_bytes = archive_bytes
        self.expected_file_count = file_count
        self.expected_content_bytes = content_bytes
        self._file = os.fdopen(duplicate, "rb", closefd=True)
        self._archive: zipfile.ZipFile | None = None
        self._files: dict[str, tuple[int, str, str]] = {}
        self._finder: _WorkspaceSourceFinder | None = None
        self._module_before: types.ModuleType | None = None
        self._module_existed = False
        try:
            self._verify_and_open()
        except BaseException:
            self.close()
            raise

    def _verify_and_open(self) -> None:
        metadata = os.fstat(self._file.fileno())
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size != self.archive_bytes:
            raise InvalidRequest("workspace descriptor does not match its manifest")
        digest = hashlib.sha256()
        while True:
            chunk = self._file.read(64 * 1024)
            if not chunk:
                break
            digest.update(chunk)
        if digest.hexdigest() != self.archive_digest:
            raise InvalidRequest("workspace archive digest does not match its manifest")
        self._file.seek(0)
        try:
            archive = zipfile.ZipFile(self._file, "r")
        except (OSError, zipfile.BadZipFile) as exc:
            raise InvalidRequest("workspace archive is invalid") from exc
        self._archive = archive
        infos = archive.infolist()
        names = [info.filename for info in infos]
        if len(names) != len(set(names)) or WORKSPACE_MANIFEST_MEMBER not in names:
            raise InvalidRequest("workspace archive contains duplicate or missing metadata")
        manifest_info = archive.getinfo(WORKSPACE_MANIFEST_MEMBER)
        if manifest_info.file_size > MAX_WORKSPACE_MANIFEST_BYTES:
            raise InvalidRequest("workspace manifest exceeds its byte limit")
        try:
            manifest = json.loads(archive.read(manifest_info))
        except (KeyError, UnicodeDecodeError, json.JSONDecodeError, zipfile.BadZipFile) as exc:
            raise InvalidRequest("workspace manifest is invalid") from exc
        if (
            not isinstance(manifest, dict)
            or set(manifest) != {"protocolVersion", "workspaceHandle", "files"}
            or manifest.get("protocolVersion") != PROTOCOL_VERSION
            or manifest.get("workspaceHandle") != self.workspace_handle
            or not isinstance(manifest.get("files"), list)
        ):
            raise InvalidRequest("workspace manifest does not match its lease")
        files = manifest["files"]
        if len(files) != self.expected_file_count or len(files) > MAX_WORKSPACE_FILES:
            raise InvalidRequest("workspace manifest file count does not match its lease")
        digest = hashlib.sha256(b"hans-workspace-manifest-v1\n")
        total_bytes = 0
        previous_path: str | None = None
        expected_members = {WORKSPACE_MANIFEST_MEMBER}
        for entry in files:
            if not isinstance(entry, dict) or set(entry) != {
                "relativePath",
                "byteCount",
                "sha256",
            }:
                raise InvalidRequest("workspace manifest entry is invalid")
            relative_path = _workspace_path(entry.get("relativePath"))
            byte_count = entry.get("byteCount")
            sha256 = entry.get("sha256")
            if (
                not isinstance(byte_count, int)
                or not 0 <= byte_count <= MAX_WORKSPACE_CONTENT_BYTES
                or not _lower_sha256(sha256)
                or (previous_path is not None and relative_path <= previous_path)
            ):
                raise InvalidRequest("workspace manifest entry is invalid")
            previous_path = relative_path
            total_bytes += byte_count
            if total_bytes > MAX_WORKSPACE_CONTENT_BYTES:
                raise InvalidRequest("workspace content exceeds its byte limit")
            member = WORKSPACE_FILE_PREFIX + relative_path
            expected_members.add(member)
            try:
                info = archive.getinfo(member)
            except KeyError as exc:
                raise InvalidRequest("workspace archive is missing a declared file") from exc
            if info.is_dir() or info.file_size != byte_count:
                raise InvalidRequest("workspace archive member does not match its manifest")
            self._files[relative_path] = (byte_count, sha256, member)
            digest.update(relative_path.encode("utf-8"))
            digest.update(b"\0")
            digest.update(str(byte_count).encode("ascii"))
            digest.update(b"\0")
            digest.update(sha256.encode("ascii"))
            digest.update(b"\n")
        if (
            set(names) != expected_members
            or total_bytes != self.expected_content_bytes
            or digest.hexdigest() != self.workspace_handle
        ):
            raise InvalidRequest("workspace archive content address does not match its lease")

    def has_file(self, relative_path: str) -> bool:
        return relative_path in self._files

    def paths(self) -> tuple[str, ...]:
        return tuple(self._files)

    def read_bytes(self, relative_path: object) -> bytes:
        path = _workspace_path(relative_path)
        declared = self._files.get(path)
        if declared is None:
            raise FileNotFoundError(path)
        archive = self._archive
        if archive is None:
            raise ValueError("workspace lease is closed")
        byte_count, expected_digest, member = declared
        try:
            value = archive.read(member)
        except (KeyError, OSError, zipfile.BadZipFile) as exc:
            raise InvalidRequest("workspace archive member failed verification") from exc
        if len(value) != byte_count or hashlib.sha256(value).hexdigest() != expected_digest:
            raise InvalidRequest("workspace file digest does not match its manifest")
        return value

    def open(
        self,
        relative_path: object,
        mode: str = "r",
        encoding: str = "utf-8",
        errors: str = "strict",
    ):
        if mode not in {"r", "rt", "rb"}:
            raise PermissionError("Hans workspaces are read-only; use a typed workspace capability")
        value = self.read_bytes(relative_path)
        if mode == "rb":
            return io.BytesIO(value)
        return io.StringIO(value.decode(encoding, errors))

    def install(self) -> None:
        finder = _WorkspaceSourceFinder(self)
        self._finder = finder
        # Built-in and frozen modules stay authoritative. Project source precedes the
        # environment archive, matching a desktop project's position before site-packages.
        sys.meta_path.insert(min(2, len(sys.meta_path)), finder)
        self._module_existed = "hans_workspace" in sys.modules
        self._module_before = sys.modules.get("hans_workspace")
        module = types.ModuleType("hans_workspace")
        module.__dict__.update(
            {
                "__doc__": "Read-only access to the verified Hans workspace for this request.",
                "workspace_handle": self.workspace_handle,
                "paths": self.paths,
                "read_bytes": self.read_bytes,
                "read_text": lambda path, encoding="utf-8", errors="strict": self.read_bytes(path).decode(
                    encoding, errors
                ),
                "open": self.open,
            }
        )
        sys.modules["hans_workspace"] = module

    def close(self) -> None:
        if self._finder is not None:
            sys.meta_path[:] = [finder for finder in sys.meta_path if finder is not self._finder]
            self._finder = None
        for name, module in tuple(sys.modules.items()):
            origin = getattr(module, "__file__", None)
            if isinstance(origin, str) and origin.startswith(
                f"{WORKSPACE_ORIGIN_PREFIX}{self.workspace_handle}/"
            ):
                sys.modules.pop(name, None)
        if self._module_existed:
            if self._module_before is not None:
                sys.modules["hans_workspace"] = self._module_before
        else:
            sys.modules.pop("hans_workspace", None)
        if self._archive is not None:
            self._archive.close()
            self._archive = None
        if not self._file.closed:
            self._file.close()


@contextlib.contextmanager
def _request_environment(request: dict[str, Any]):
    old_cwd = os.getcwd()
    old_argv = sys.argv[:]
    old_meta_path = sys.meta_path[:]
    modules_before = set(sys.modules)
    workspace: _WorkspaceLease | None = None
    try:
        allowed_native_modules = request.get("allowedNativeModules", [])
        environment_digest = _required_string(request, "environmentDigest")
        workspace_handle = request.get("workspaceHandle")
        runtime_lease = request.get("runtimeLease")
        lease_native_modules = (
            runtime_lease.get("allowedNativeModules")
            if isinstance(runtime_lease, dict)
            else None
        )
        if allowed_native_modules and lease_native_modules is None:
            raise InvalidRequest(
                "allowedNativeModules requires a verified runtime lease"
            )
        if (
            lease_native_modules is not None
            and lease_native_modules != allowed_native_modules
        ):
            raise InvalidRequest(
                "allowedNativeModules does not match its verified runtime lease"
            )
        workspace_lease = runtime_lease.get("workspace") if isinstance(runtime_lease, dict) else None
        if workspace_handle is None:
            if workspace_lease is not None:
                raise InvalidRequest("workspace lease was supplied without a requested handle")
        else:
            if not isinstance(workspace_lease, dict):
                raise InvalidRequest("workspace_unresolved: verified workspace lease is required")
            workspace = _WorkspaceLease(workspace_handle, workspace_lease)
            workspace.install()
        sys.argv = ["<hans>"]
        try:
            with native_import_scope(environment_digest, allowed_native_modules):
                yield
        except HansNativeValidationError as exc:
            raise InvalidRequest(str(exc)) from exc
    finally:
        if workspace is not None:
            workspace.close()
        for name in set(sys.modules) - modules_before:
            module = sys.modules.get(name)
            module_file = getattr(module, "__file__", None)
            if isinstance(module_file, str) and (
                module_file.startswith("hans-fd://environment/")
                or module_file.startswith(WORKSPACE_ORIGIN_PREFIX)
            ):
                sys.modules.pop(name, None)
        importlib.invalidate_caches()
        sys.meta_path[:] = old_meta_path
        sys.argv = old_argv
        os.chdir(old_cwd)


def execute_json(request_json: str) -> str:
    global _context
    started_ns = time.monotonic_ns()
    request_id = "unknown"
    context: _ExecutionContext | None = None
    try:
        request = json.loads(request_json)
        if not isinstance(request, dict):
            raise InvalidRequest("request must be an object")
        if request.get("protocolVersion") != PROTOCOL_VERSION:
            raise InvalidRequest("unsupported protocolVersion")
        request_id = _required_string(request, "requestId")
        _required_string(request, "idempotencyKey")
        _required_string(request, "environmentDigest")
        allowed_capabilities = request.get("allowedCapabilities")
        if not isinstance(allowed_capabilities, list) or any(
            not isinstance(item, str) or not item for item in allowed_capabilities
        ):
            raise InvalidRequest("allowedCapabilities must be a string array")
        limits = request.get("limits", {})
        if not isinstance(limits, dict):
            raise InvalidRequest("limits must be an object")
        limit_names = (
            "maximumStdoutBytes",
            "maximumStderrBytes",
            "maximumResultBytes",
            "maximumEvents",
        )
        parsed_limits: dict[str, int] = {}
        for name in limit_names:
            value = limits.get(name, DEFAULT_OUTPUT_LIMIT if name != "maximumEvents" else 4096)
            maximum = 8_388_608 if name != "maximumEvents" else 65_536
            if not isinstance(value, int) or not 1 <= value <= maximum:
                raise InvalidRequest(f"limits.{name} is outside the supported range")
            parsed_limits[name] = value
        deadline = limits.get("deadlineElapsedRealtimeMillis")
        if not isinstance(deadline, int) or deadline <= 0:
            raise InvalidRequest("limits.deadlineElapsedRealtimeMillis must be positive")
        context = _ExecutionContext(
            request_id,
            parsed_limits["maximumStdoutBytes"],
            parsed_limits["maximumStderrBytes"],
            parsed_limits["maximumEvents"],
            frozenset(allowed_capabilities),
        )
        _context = context
        with (
            _request_environment(request),
            contextlib.redirect_stdout(_EventWriter(context, "stdout")),
            contextlib.redirect_stderr(_EventWriter(context, "stderr")),
        ):
            value = _execute_operation(request)
        status = "SUCCEEDED"
        error_code = None
        error_message = None
        safe_value = _json_value(value)
    except KeyboardInterrupt:
        status = "CANCELLED"
        error_code = "cancelled"
        error_message = "Python execution was cancelled"
        safe_value = None
    except OutputLimitExceeded as exc:
        status = "OUTPUT_LIMIT_EXCEEDED"
        error_code = "output_limit_exceeded"
        error_message = str(exc)
        safe_value = None
    except InvalidRequest as exc:
        status = "INVALID_REQUEST"
        error_code = "invalid_request"
        error_message = str(exc)
        safe_value = None
    except HansCapabilityDenied as exc:
        status = "CAPABILITY_DENIED"
        error_code = "capability_denied"
        error_message = str(exc)
        safe_value = None
    except HansCapabilityError as exc:
        status = "PYTHON_EXCEPTION"
        error_code = "capability_failed"
        error_message = str(exc)
        safe_value = None
    except DependencyMissing as exc:
        status = "DEPENDENCY_MISSING"
        error_code = "dependency_missing"
        error_message = str(exc)
        safe_value = None
    except BaseException as exc:  # Convert plugin failures into the stable envelope.
        status = "PYTHON_EXCEPTION"
        error_code = "python_exception"
        error_message = f"{type(exc).__name__}: {exc}"
        safe_value = {
            "exceptionType": type(exc).__name__,
            "traceback": "".join(traceback.format_exception(exc))[-65_536:],
        }
    finally:
        _context = None
    elapsed_ms = max(0, (time.monotonic_ns() - started_ns) // 1_000_000)
    metrics = {
        "durationMs": elapsed_ms,
        "stdoutBytes": 0 if context is None else context.stdout_bytes,
        "stderrBytes": 0 if context is None else context.stderr_bytes,
        "eventCount": 0 if context is None else context.events,
    }
    result = {
        "protocolVersion": PROTOCOL_VERSION,
        "requestId": request_id,
        "status": status,
        "value": safe_value,
        "errorCode": error_code,
        "errorMessage": error_message,
        "metrics": metrics,
    }
    encoded = _json(result)
    maximum_result = 1_048_576
    try:
        maximum_result = parsed_limits["maximumResultBytes"]
    except (NameError, KeyError):
        pass
    if len(encoded.encode("utf-8")) > maximum_result:
        result.update(
            status="OUTPUT_LIMIT_EXCEEDED",
            value=None,
            errorCode="result_limit_exceeded",
            errorMessage=f"result exceeded {maximum_result} UTF-8 bytes",
        )
        encoded = _json(result)
    return encoded
