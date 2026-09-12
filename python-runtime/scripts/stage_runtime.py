#!/usr/bin/env python3
"""Stage the pinned PSF Android CPython runtime deterministically for Hans."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any, BinaryIO


MARKER = ".hans-python-runtime-generated"
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
NATIVE_MODULE_PATTERN = re.compile(
    r"^(?P<module>[A-Za-z0-9_]+)(?:\.cpython-[^.]+|\.abi3)?\.so$"
)


class StageError(RuntimeError):
    pass


def _sha256_stream(stream: BinaryIO) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    while chunk := stream.read(1024 * 1024):
        digest.update(chunk)
        size += len(chunk)
    return size, digest.hexdigest()


def _sha256_path(path: Path) -> tuple[int, str]:
    with path.open("rb") as stream:
        return _sha256_stream(stream)


def _json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise StageError(f"invalid JSON file {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise StageError(f"JSON root must be an object: {path}")
    return value


def _runtime_lock(lock_path: Path) -> dict[str, Any]:
    lock = _json(lock_path)
    if lock.get("schemaVersion") != 1:
        raise StageError("unsupported python.lock.json schemaVersion")
    runtime = lock.get("runtime")
    if not isinstance(runtime, dict) or runtime.get("implementation") != "cpython":
        raise StageError("python.lock.json does not describe CPython")
    android = runtime.get("android")
    if not isinstance(android, dict):
        raise StageError("python.lock.json is missing runtime.android")
    if android.get("abi") != "arm64-v8a" or android.get("hostApplicationMinimumApi") != 31:
        raise StageError("Hans CPython lock must target arm64-v8a with host API 31")
    if android.get("pageAlignmentBytes") != 16_384:
        raise StageError("Hans CPython lock must require 16 KiB ELF alignment")
    if android.get("compatiblePageSizesBytes") != [4_096, 16_384]:
        raise StageError("Hans CPython lock must explicitly cover 4 KiB and 16 KiB pages")
    environment = runtime.get("environmentArchive")
    if not isinstance(environment, dict) or environment != {
        "format": "deterministic-pyz-v1",
        "sitePackagesPrefix": "",
        "pluginSourcePrefix": "__hans_plugin_source__/",
        "transport": "read-only-parcel-file-descriptor",
    }:
        raise StageError("Hans environment archive contract is not pinned")
    return runtime


def _download_or_verify(
    descriptor: dict[str, Any],
    cache: Path,
    offline: bool,
    allowed_url_prefixes: tuple[str, ...] = ("https://www.python.org/",),
) -> Path:
    name = descriptor.get("name") or Path(str(descriptor.get("url", ""))).name
    url = descriptor.get("url")
    expected_bytes = descriptor.get("bytes")
    expected_sha = descriptor.get("sha256")
    if (
        not isinstance(name, str)
        or not name
        or Path(name).name != name
        or not isinstance(url, str)
        or not url.startswith(allowed_url_prefixes)
        or not isinstance(expected_bytes, int)
        or expected_bytes <= 0
        or not isinstance(expected_sha, str)
        or not re.fullmatch(r"[0-9a-f]{64}", expected_sha)
    ):
        raise StageError(f"invalid pinned artifact descriptor: {descriptor!r}")
    cache.mkdir(parents=True, exist_ok=True)
    destination = cache / name
    if destination.is_file():
        actual_bytes, actual_sha = _sha256_path(destination)
        if (actual_bytes, actual_sha) == (expected_bytes, expected_sha):
            return destination
        if offline:
            raise StageError(f"offline cached artifact failed verification: {destination}")
    elif offline:
        raise StageError(f"offline artifact is not cached: {destination}")

    request = urllib.request.Request(url, headers={"User-Agent": "Hans-CPython-Stager/1"})
    with tempfile.NamedTemporaryFile(prefix=f".{name}.", dir=cache, delete=False) as tmp:
        temporary = Path(tmp.name)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:  # noqa: S310
                shutil.copyfileobj(response, tmp, length=1024 * 1024)
            tmp.flush()
            os.fsync(tmp.fileno())
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise
    actual_bytes, actual_sha = _sha256_path(temporary)
    if (actual_bytes, actual_sha) != (expected_bytes, expected_sha):
        temporary.unlink(missing_ok=True)
        raise StageError(
            f"downloaded {name} mismatch: expected {expected_bytes}/{expected_sha}, "
            f"got {actual_bytes}/{actual_sha}"
        )
    os.replace(temporary, destination)
    return destination


def _native_lock(path: Path, runtime: dict[str, Any]) -> dict[str, Any]:
    lock = _json(path)
    if lock.get("schemaVersion") != 1:
        raise StageError("unsupported native-packages.lock.json schemaVersion")
    packages = lock.get("packages")
    if not isinstance(packages, list) or not 1 <= len(packages) <= 32:
        raise StageError("native package lock must contain a bounded package list")
    seen: set[str] = set()
    for package in packages:
        if not isinstance(package, dict):
            raise StageError("native package descriptor must be an object")
        if set(package) != {
            "catalogId", "packageName", "version", "source", "target",
            "requiresPython", "requiresDist", "importNames", "companionWheel",
            "nativeLibraries", "payloadSha256",
        }:
            raise StageError("native package descriptor has unexpected fields")
        catalog_id = package.get("catalogId")
        if not isinstance(catalog_id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", catalog_id):
            raise StageError("invalid native package catalogId")
        if catalog_id in seen:
            raise StageError("duplicate native package catalogId")
        seen.add(catalog_id)
        if package.get("packageName") != "msgpack" or package.get("version") != "1.2.1":
            raise StageError("Hans v1 native catalog is pinned to msgpack 1.2.1")
        target = package.get("target")
        if target != {
            "pythonSeries": "3.14",
            "interpreterTag": "cp314",
            "androidAbi": "arm64-v8a",
            "minimumAndroidApi": 31,
            "directorySegment": "cp314-android_arm64_v8a",
        }:
            raise StageError("msgpack native target is not the Hans Android baseline")
        if not runtime["version"].startswith(target["pythonSeries"] + "."):
            raise StageError("native package lock targets another CPython series")
        source = package.get("source")
        if not isinstance(source, dict) or source != {
            "name": "msgpack-1.2.1.tar.gz",
            "url": "https://files.pythonhosted.org/packages/31/f9/c0a1c127f9049db9155afc316952ea571720dd01833ff5e4d7e8e6352dbb/msgpack-1.2.1.tar.gz",
            "bytes": 183960,
            "sha256": "04c721c2c7448767e9e3f2520a475663d8ee0f09c31890f6d2bd70fd636a9647",
        }:
            raise StageError("msgpack source sdist pin changed")
        if package.get("requiresPython") != ">=3.10" or package.get("requiresDist") != []:
            raise StageError("msgpack dependency metadata changed")
        if package.get("importNames") != ["msgpack", "msgpack._cmsgpack"]:
            raise StageError("msgpack import allowlist changed")
        companion = package.get("companionWheel")
        if not isinstance(companion, dict) or set(companion) != {
            "assetPath", "fileName", "bytes", "sha256"
        }:
            raise StageError("invalid msgpack companion wheel pin")
        if companion.get("assetPath") != "hans/python/native-packages/msgpack-1.2.1-py3-none-any.whl" or companion.get("fileName") != "msgpack-1.2.1-py3-none-any.whl":
            raise StageError("msgpack companion asset path changed")
        libraries = package.get("nativeLibraries")
        if not isinstance(libraries, list) or len(libraries) != 1:
            raise StageError("msgpack must have exactly one native library")
        library = libraries[0]
        if not isinstance(library, dict) or set(library) != {
            "moduleName", "packagedName", "bytes", "sha256"
        } or library.get("moduleName") != "msgpack._cmsgpack" or library.get("packagedName") != "libhans_py_msgpack___cmsgpack.so":
            raise StageError("msgpack native library identity changed")
        for descriptor, label in ((companion, "companion wheel"), (library, "native library")):
            if not isinstance(descriptor.get("bytes"), int) or descriptor["bytes"] <= 0:
                raise StageError(f"invalid pinned {label} size")
            if not isinstance(descriptor.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", descriptor["sha256"]):
                raise StageError(f"invalid pinned {label} digest")
        if not isinstance(package.get("payloadSha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", package["payloadSha256"]):
            raise StageError("invalid native package payload digest")
    return lock


def _validated_members(archive: tarfile.TarFile) -> dict[str, tarfile.TarInfo]:
    members: dict[str, tarfile.TarInfo] = {}
    for member in archive.getmembers():
        normalized = member.name.removeprefix("./")
        path = PurePosixPath(normalized)
        if not normalized or path.is_absolute() or ".." in path.parts:
            raise StageError(f"unsafe path in CPython archive: {member.name!r}")
        if normalized in members:
            raise StageError(f"duplicate path in CPython archive: {normalized}")
        members[normalized] = member
    return members


def _member_bytes(
    archive: tarfile.TarFile, members: dict[str, tarfile.TarInfo], name: str
) -> bytes:
    member = members.get(name)
    if member is None or not member.isfile():
        raise StageError(f"CPython archive is missing regular file: {name}")
    stream = archive.extractfile(member)
    if stream is None:
        raise StageError(f"cannot read CPython archive member: {name}")
    return stream.read()


def _write_bytes(path: Path, data: bytes, mode: int = 0o644) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    path.chmod(mode)


def _verify_bytes(data: bytes, descriptor: dict[str, Any], label: str) -> None:
    expected_size = descriptor.get("bytes")
    expected_sha = descriptor.get("sha256")
    actual_sha = hashlib.sha256(data).hexdigest()
    if len(data) != expected_size or actual_sha != expected_sha:
        raise StageError(
            f"{label} mismatch: expected {expected_size}/{expected_sha}, "
            f"got {len(data)}/{actual_sha}"
        )


def _zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
    # JNI indexes and reads this archive directly with pread(2). Restricting it
    # to STORED entries keeps that parser small, deterministic, and independent
    # of extension modules before the descriptor-backed importer is installed.
    info.compress_type = zipfile.ZIP_STORED
    info.create_system = 3
    info.external_attr = (stat.S_IFREG | 0o644) << 16
    return info


def _excluded_stdlib(relative: PurePosixPath, prefixes: list[str]) -> bool:
    if relative.suffix in {".so", ".pyc", ".pyo"}:
        return True
    parts = relative.parts
    return any(parts[: len(PurePosixPath(prefix).parts)] == PurePosixPath(prefix).parts for prefix in prefixes)


def _build_stdlib_zip(
    archive: tarfile.TarFile,
    members: dict[str, tarfile.TarInfo],
    runtime: dict[str, Any],
    runtime_source: Path,
    destination: Path,
) -> tuple[int, str, int]:
    stdlib = runtime["stdlib"]
    if stdlib.get("archiveCompression") != "stored":
        raise StageError("stdlib.archiveCompression must be pinned to stored")
    source_prefix = str(stdlib["sourceDirectory"]).strip("/") + "/"
    excluded = stdlib["excludeDirectoryPrefixes"]
    if not isinstance(excluded, list) or any(not isinstance(value, str) for value in excluded):
        raise StageError("stdlib.excludeDirectoryPrefixes must be a string array")
    files: dict[str, bytes] = {}
    for name, member in members.items():
        if not name.startswith(source_prefix) or not member.isfile():
            continue
        relative = PurePosixPath(name[len(source_prefix) :])
        if not relative.parts or _excluded_stdlib(relative, excluded):
            continue
        files[relative.as_posix()] = _member_bytes(archive, members, name)
    for source in sorted(runtime_source.glob("*.py")):
        if not source.is_file():
            continue
        if source.name in files:
            raise StageError(f"runtime source collides with upstream stdlib: {source.name}")
        files[source.name] = source.read_bytes()
    required = {
        "encodings/__init__.py",
        "importlib/__init__.py",
        "json/__init__.py",
        "hans_fd_importer.py",
        "hans_native_importer.py",
        "hans_runtime_bootstrap.py",
        "hans_runtime_api.py",
    }
    missing = sorted(required - files.keys())
    if missing:
        raise StageError(f"staged stdlib is missing required files: {missing}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_STORED) as output:
        for name in sorted(files):
            output.writestr(_zip_info(name), files[name], compress_type=zipfile.ZIP_STORED)
    size, digest = _sha256_path(destination)
    return size, digest, len(files)


def _wheel_record_hash(data: bytes) -> str:
    encoded = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).decode("ascii")
    return "sha256=" + encoded.rstrip("=")


def _build_msgpack_companion_wheel(
    source_archive: Path,
    destination: Path,
) -> tuple[int, str]:
    required_sources = (
        "msgpack/__init__.py",
        "msgpack/exceptions.py",
        "msgpack/ext.py",
        "msgpack/fallback.py",
    )
    files: dict[str, bytes] = {}
    with tarfile.open(source_archive, "r:gz") as archive:
        members = _validated_members(archive)
        for relative in required_sources:
            files[relative] = _member_bytes(
                archive, members, f"msgpack-1.2.1/{relative}"
            )
        license_data = _member_bytes(
            archive, members, "msgpack-1.2.1/COPYING"
        )
    dist_info = "msgpack-1.2.1.dist-info"
    files[f"{dist_info}/METADATA"] = (
        "Metadata-Version: 2.4\n"
        "Name: msgpack\n"
        "Version: 1.2.1\n"
        "Summary: MessagePack serializer\n"
        "License-Expression: Apache-2.0\n"
        "Requires-Python: >=3.10\n"
        "\n"
    ).encode("utf-8")
    files[f"{dist_info}/WHEEL"] = (
        "Wheel-Version: 1.0\n"
        "Generator: hans-native-package-stager-v1\n"
        "Root-Is-Purelib: true\n"
        "Tag: py3-none-any\n"
        "\n"
    ).encode("utf-8")
    files[f"{dist_info}/top_level.txt"] = b"msgpack\n"
    files[f"{dist_info}/licenses/COPYING"] = license_data
    record_path = f"{dist_info}/RECORD"
    records = [
        f"{name},{_wheel_record_hash(data)},{len(data)}"
        for name, data in sorted(files.items())
    ]
    records.append(f"{record_path},,")
    files[record_path] = ("\n".join(records) + "\n").encode("utf-8")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_STORED) as output:
        for name, data in sorted(files.items()):
            output.writestr(_zip_info(name), data, compress_type=zipfile.ZIP_STORED)
    return _sha256_path(destination)


def _android_ndk_root(version: str) -> Path:
    explicit = os.environ.get("ANDROID_NDK_ROOT")
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit))
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        sdk = os.environ.get(variable)
        if sdk:
            candidates.append(Path(sdk) / "ndk" / version)
    candidates.extend(
        (
            Path.home() / "Library/Android/sdk/ndk" / version,
            Path.home() / "Android/Sdk/ndk" / version,
        )
    )
    for candidate in candidates:
        if (candidate / "toolchains/llvm/prebuilt").is_dir():
            return candidate.resolve()
    raise StageError(f"Android NDK {version} is unavailable")


def _ndk_toolchain(ndk: Path) -> Path:
    prebuilts = sorted((ndk / "toolchains/llvm/prebuilt").iterdir())
    matches = [path for path in prebuilts if (path / "bin/clang").is_file()]
    if len(matches) != 1:
        raise StageError("Android NDK has an ambiguous host toolchain")
    return matches[0]


def _run_checked(command: list[str], label: str) -> str:
    result = subprocess.run(
        command,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=300,
    )
    if result.returncode != 0:
        raise StageError(f"{label} failed ({result.returncode}):\n{result.stdout[-8000:]}")
    return result.stdout


def _compile_msgpack_extension(
    source_archive: Path,
    prefix: Path,
    destination: Path,
    ndk_version: str,
) -> tuple[int, str]:
    ndk = _android_ndk_root(ndk_version)
    toolchain = _ndk_toolchain(ndk)
    compiler = toolchain / "bin/aarch64-linux-android31-clang"
    readelf = toolchain / "bin/llvm-readelf"
    if not compiler.is_file() or not readelf.is_file():
        raise StageError("Android NDK cross compiler tools are missing")
    with tempfile.TemporaryDirectory(prefix="hans-msgpack-native-") as temporary:
        source_root = Path(temporary) / "src"
        with tarfile.open(source_archive, "r:gz") as archive:
            members = _validated_members(archive)
            prefix_name = "msgpack-1.2.1/"
            for name, member in sorted(members.items()):
                if not name.startswith(prefix_name + "msgpack/") or not member.isfile():
                    continue
                relative = PurePosixPath(name[len(prefix_name) :])
                if relative.suffix not in {".c", ".h"}:
                    continue
                _write_bytes(source_root / Path(*relative.parts), _member_bytes(archive, members, name))
        c_source = source_root / "msgpack/_cmsgpack.c"
        if not c_source.is_file():
            raise StageError("msgpack sdist has no generated C extension source")
        destination.parent.mkdir(parents=True, exist_ok=True)
        command = [
            str(compiler),
            "--target=aarch64-linux-android31",
            "-shared",
            "-fPIC",
            "-O2",
            "-DNDEBUG",
            f"-ffile-prefix-map={source_root}=.",
            f"-fdebug-prefix-map={source_root}=.",
            "-I", str(prefix / "include/python3.14"),
            "-I", str(source_root),
            str(c_source),
            "-L", str(prefix / "lib"),
            "-lpython3.14",
            "-Wl,--no-undefined",
            "-Wl,--build-id=none",
            "-Wl,-z,max-page-size=16384",
            "-Wl,-z,common-page-size=16384",
            "-Wl,-soname,libhans_py_msgpack___cmsgpack.so",
            "-o", str(destination),
        ]
        _run_checked(command, "msgpack Android cross compilation")
    header = _run_checked([str(readelf), "-h", str(destination)], "msgpack ELF header audit")
    symbols = _run_checked([str(readelf), "-Ws", str(destination)], "msgpack ELF symbol audit")
    dynamic = _run_checked([str(readelf), "-d", str(destination)], "msgpack ELF dynamic audit")
    programs = _run_checked([str(readelf), "-lW", str(destination)], "msgpack ELF alignment audit")
    if "AArch64" not in header or "PyInit__cmsgpack" not in symbols:
        raise StageError("msgpack extension has the wrong architecture or init symbol")
    if "libhans_py_msgpack___cmsgpack.so" not in dynamic or "(SONAME)" not in dynamic:
        raise StageError("msgpack extension SONAME is not pinned")
    if "RPATH" in dynamic or "RUNPATH" in dynamic or "TEXTREL" in dynamic:
        raise StageError("msgpack extension contains a forbidden dynamic-linker directive")
    load_lines = [line for line in programs.splitlines() if line.lstrip().startswith("LOAD")]
    if not load_lines:
        raise StageError("msgpack extension has no ELF LOAD segments")
    for line in load_lines:
        try:
            alignment = int(line.split()[-1], 16)
        except (IndexError, ValueError) as exc:
            raise StageError("cannot audit msgpack ELF segment alignment") from exc
        if alignment < 16_384:
            raise StageError("msgpack extension is not 16 KiB page aligned")
    destination.chmod(0o644)
    return _sha256_path(destination)


def _native_payload_digest(package: dict[str, Any]) -> str:
    # A small language-neutral length-framed format is deliberately used here
    # instead of depending on either Python's or Android's JSON key ordering.
    # The Android catalog loader recomputes this exact identity before exposing
    # a package to the resolver.
    values: list[str] = [
        "hans.python-native-payload.v1",
        package["catalogId"],
        package["packageName"],
        package["version"],
        package["source"]["sha256"],
        "1",
        package["target"]["directorySegment"],
        package["requiresPython"] or "",
        str(len(package["requiresDist"])),
        *package["requiresDist"],
        str(len(package["importNames"])),
        *package["importNames"],
    ]
    companion = package["companionWheel"]
    values.extend(
        (
            companion["assetPath"], companion["fileName"],
            str(companion["bytes"]), companion["sha256"],
            str(len(package["nativeLibraries"])),
        )
    )
    for library in package["nativeLibraries"]:
        values.extend(
            (
                library["moduleName"], library["packagedName"],
                str(library["bytes"]), library["sha256"],
            )
        )
    digest = hashlib.sha256()
    for value in values:
        raw = value.encode("utf-8")
        digest.update(str(len(raw)).encode("ascii"))
        digest.update(b":")
        digest.update(raw)
    return digest.hexdigest()


def _length_framed_digest(values: list[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        raw = value.encode("utf-8")
        digest.update(str(len(raw)).encode("ascii"))
        digest.update(b":")
        digest.update(raw)
    return digest.hexdigest()


def _stage_native_package_policy_header(
    packages: list[dict[str, Any]],
    destination: Path,
) -> dict[str, Any]:
    entries = sorted(
        (library["moduleName"], library["packagedName"])
        for package in packages
        for library in package["nativeLibraries"]
    )
    if not entries or len(entries) > 32 or len(entries) != len(set(entries)):
        raise StageError("native package C++ policy must contain unique bounded entries")
    for module, packaged_name in entries:
        if not re.fullmatch(
            r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*", module
        ):
            raise StageError(f"invalid native package policy module: {module!r}")
        if not re.fullmatch(r"libhans_py_[A-Za-z0-9_]+\.so", packaged_name):
            raise StageError(
                f"invalid native package policy packaged name: {packaged_name!r}"
            )
    policy_sha = _length_framed_digest(
        [
            "hans.native-package-policy.v1",
            str(len(entries)),
            *(value for entry in entries for value in entry),
        ]
    )
    rows = "\n".join(
        f'    {{"{module}", "{packaged_name}"}},'
        for module, packaged_name in entries
    )
    header = (
        "// Generated from native-packages.lock.json; do not edit.\n"
        "#pragma once\n\n"
        "#include <cstddef>\n\n"
        "namespace hans::python::native_package_policy {\n\n"
        "struct Entry {\n"
        "  const char* module;\n"
        "  const char* packaged_name;\n"
        "};\n\n"
        "inline constexpr Entry kCatalogEntries[] = {\n"
        f"{rows}\n"
        "};\n"
        "inline constexpr std::size_t kCatalogEntryCount =\n"
        "    sizeof(kCatalogEntries) / sizeof(kCatalogEntries[0]);\n"
        f'inline constexpr char kPolicySha256[] = "{policy_sha}";\n\n'
        "}  // namespace hans::python::native_package_policy\n"
    ).encode("utf-8")
    _write_bytes(destination, header)
    return {
        "path": "prefix/include/hans_native_package_policy.h",
        "sizeBytes": len(header),
        "sha256": hashlib.sha256(header).hexdigest(),
        "policySha256": policy_sha,
        "entryCount": len(entries),
    }


def _stage_native_packages(
    lock: dict[str, Any],
    cache: Path,
    output: Path,
    runtime: dict[str, Any],
    offline: bool,
) -> list[dict[str, Any]]:
    manifest_entries: list[dict[str, Any]] = []
    for package in lock["packages"]:
        source = _download_or_verify(
            package["source"],
            cache,
            offline,
            allowed_url_prefixes=("https://files.pythonhosted.org/",),
        )
        companion = package["companionWheel"]
        companion_path = output / "assets" / Path(*PurePosixPath(companion["assetPath"]).parts)
        companion_size, companion_sha = _build_msgpack_companion_wheel(source, companion_path)
        library = package["nativeLibraries"][0]
        library_path = output / "jniLibs/arm64-v8a" / library["packagedName"]
        library_size, library_sha = _compile_msgpack_extension(
            source,
            output / "prefix",
            library_path,
            runtime["android"]["ndkVersion"],
        )
        actuals = (
            ("msgpack companion wheel", companion, companion_size, companion_sha),
            ("msgpack native library", library, library_size, library_sha),
        )
        mismatches = [
            f"{label}: got bytes={size} sha256={digest}"
            for label, pin, size, digest in actuals
            if pin["bytes"] != size or pin["sha256"] != digest
        ]
        if mismatches:
            raise StageError("native package outputs do not match their lock; " + "; ".join(mismatches))
        payload = _native_payload_digest(package)
        if payload != package["payloadSha256"]:
            raise StageError(
                "native package payload digest mismatch: "
                f"got payloadSha256={payload}"
            )
        manifest_entries.append(
            {
                "catalogId": package["catalogId"],
                "packageName": package["packageName"],
                "version": package["version"],
                "payloadSha256": payload,
                "sourceSdistSha256": package["source"]["sha256"],
                "supportedTargets": [package["target"]["directorySegment"]],
                "importNames": package["importNames"],
                "requiresPython": package["requiresPython"],
                "requiresDist": package["requiresDist"],
                "companionWheel": {
                    "assetPath": companion["assetPath"],
                    "fileName": companion["fileName"],
                    "sha256": companion_sha,
                    "sizeBytes": companion_size,
                },
                "nativeLibraries": [
                    {
                        "moduleName": library["moduleName"],
                        "packagedName": library["packagedName"],
                        "sha256": library_sha,
                        "sizeBytes": library_size,
                    }
                ],
            }
        )
    catalog = {
        "schemaVersion": 1,
        "pythonVersion": runtime["version"],
        "packages": manifest_entries,
    }
    _write_bytes(
        output / "assets/hans/python/native-packages/catalog.json",
        (json.dumps(catalog, indent=2, sort_keys=True) + "\n").encode("utf-8"),
    )
    return manifest_entries


def _native_module_name(filename: str) -> str:
    match = NATIVE_MODULE_PATTERN.fullmatch(filename)
    if match is None:
        raise StageError(f"unsupported CPython extension filename: {filename}")
    return match.group("module")


def _stage_archive(
    archive_path: Path,
    runtime: dict[str, Any],
    runtime_source: Path,
    output: Path,
) -> dict[str, Any]:
    jni = output / "jniLibs" / "arm64-v8a"
    prefix = output / "prefix"
    asset_root = output / "assets" / "hans" / "python"
    jni.mkdir(parents=True)
    prefix.mkdir(parents=True)
    asset_root.mkdir(parents=True)
    with tarfile.open(archive_path, "r:gz") as archive:
        members = _validated_members(archive)
        core_manifest: list[dict[str, Any]] = []
        for descriptor in runtime["coreLibraries"]:
            name = descriptor["name"]
            data = _member_bytes(archive, members, f"prefix/lib/{name}")
            _verify_bytes(data, descriptor, name)
            _write_bytes(jni / name, data)
            _write_bytes(prefix / "lib" / name, data)
            core_manifest.append(dict(descriptor))

        header_prefix = "prefix/include/python3.14/"
        header_count = 0
        for name, member in sorted(members.items()):
            if not name.startswith(header_prefix) or not member.isfile():
                continue
            relative = PurePosixPath(name[len("prefix/") :])
            _write_bytes(prefix / Path(*relative.parts), _member_bytes(archive, members, name))
            header_count += 1
        if header_count < 100:
            raise StageError(f"unexpectedly small CPython header set: {header_count}")

        native = runtime["nativeExtensions"]
        native_prefix = str(native["sourceDirectory"]).strip("/") + "/"
        packaged_prefix = native["packagedPrefix"]
        excluded_modules = set(native["excludedModules"])
        native_modules: list[dict[str, Any]] = []
        seen_packaged: set[str] = set()
        for name, member in sorted(members.items()):
            if not name.startswith(native_prefix) or not member.isfile() or not name.endswith(".so"):
                continue
            source_name = PurePosixPath(name).name
            module = _native_module_name(source_name)
            if module in excluded_modules:
                continue
            packaged = f"{packaged_prefix}{module}.so"
            if packaged in seen_packaged:
                raise StageError(f"native module package collision: {packaged}")
            seen_packaged.add(packaged)
            data = _member_bytes(archive, members, name)
            _write_bytes(jni / packaged, data)
            native_modules.append(
                {
                    "module": module,
                    "sourceName": source_name,
                    "packagedName": packaged,
                    "bytes": len(data),
                    "sha256": hashlib.sha256(data).hexdigest(),
                }
            )
        if len(native_modules) < 40:
            raise StageError(f"unexpectedly small curated native module set: {len(native_modules)}")

        stdlib = runtime["stdlib"]
        stdlib_asset = stdlib["asset"]
        expected_asset = "hans/python/python314.zip"
        if stdlib_asset != expected_asset:
            raise StageError(f"stdlib asset must remain {expected_asset}")
        stdlib_path = output / "assets" / Path(*PurePosixPath(stdlib_asset).parts)
        stdlib_bytes, stdlib_sha, stdlib_files = _build_stdlib_zip(
            archive, members, runtime, runtime_source, stdlib_path
        )
        if (
            stdlib_bytes != stdlib.get("expectedBytes")
            or stdlib_sha != stdlib.get("expectedSha256")
            or stdlib_files != stdlib.get("expectedFiles")
        ):
            raise StageError(
                "derived stdlib ZIP does not match python.lock.json; "
                f"got files={stdlib_files} bytes={stdlib_bytes} sha256={stdlib_sha}"
            )

    runtime_manifest = {
        "schemaVersion": 1,
        "pythonVersion": runtime["version"],
        "abi": runtime["android"]["abi"],
        "stdlibAsset": runtime["stdlib"]["asset"],
        "stdlibSha256": stdlib_sha,
        "stdlibBytes": stdlib_bytes,
        "upstreamArtifactSha256": runtime["artifact"]["sha256"],
        "pageAlignmentBytes": runtime["android"]["pageAlignmentBytes"],
        "compatiblePageSizesBytes": runtime["android"]["compatiblePageSizesBytes"],
        "environmentArchive": runtime["environmentArchive"],
        "nativeModules": native_modules,
    }
    manifest_bytes = (
        json.dumps(runtime_manifest, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    ).encode("utf-8")
    _write_bytes(asset_root / "runtime-manifest.json", manifest_bytes)
    stage_manifest = {
        "schemaVersion": 1,
        "runtimeManifestSha256": hashlib.sha256(manifest_bytes).hexdigest(),
        "pythonVersion": runtime["version"],
        "abi": runtime["android"]["abi"],
        "headerFiles": header_count,
        "stdlibFiles": stdlib_files,
        "stdlibBytes": stdlib_bytes,
        "stdlibSha256": stdlib_sha,
        "coreLibraries": core_manifest,
        "nativeModules": native_modules,
    }
    _write_bytes(
        output / "stage-manifest.json",
        (json.dumps(stage_manifest, indent=2, sort_keys=True) + "\n").encode("utf-8"),
    )
    _write_bytes(output / MARKER, b"Hans generated CPython runtime v1\n")
    return stage_manifest


def _replace_generated_tree(staged: Path, output: Path) -> None:
    output = output.resolve()
    if output == Path(output.anchor) or output == Path.home().resolve():
        raise StageError(f"refusing broad generated output target: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    backup = output.parent / f".{output.name}.previous-{os.getpid()}"
    if output.exists():
        if output.is_symlink():
            raise StageError(f"refusing to replace unmarked output tree: {output}")
        if (output / MARKER).is_file():
            if backup.exists():
                raise StageError(f"unexpected staging backup already exists: {backup}")
            output.rename(backup)
        elif output.is_dir() and not any(output.iterdir()):
            # Gradle creates declared output directories before Exec tasks run.
            # An empty directory has no user or previously generated content to
            # preserve, so removing only that exact directory is safe.  Any
            # non-empty unmarked tree still fails closed below.
            output.rmdir()
        else:
            raise StageError(f"refusing to replace unmarked output tree: {output}")
    try:
        staged.rename(output)
    except BaseException:
        if backup.exists() and not output.exists():
            backup.rename(output)
        raise
    if backup.exists():
        shutil.rmtree(backup)


def stage(lock_path: Path, runtime_source: Path, cache: Path, output: Path) -> None:
    runtime = _runtime_lock(lock_path)
    native_lock = _native_lock(lock_path.parent / "native-packages.lock.json", runtime)
    if not runtime_source.is_dir():
        raise StageError(f"runtime source directory does not exist: {runtime_source}")
    offline = os.environ.get("HANS_PYTHON_RUNTIME_OFFLINE") == "1"
    artifact = _download_or_verify(runtime["artifact"], cache, offline)
    signature = dict(runtime["sigstoreBundle"])
    signature["name"] = Path(signature["url"]).name
    _download_or_verify(signature, cache, offline)
    output_parent = output.resolve().parent
    output_parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f".{output.name}.stage-", dir=output_parent) as temp:
        staged = Path(temp) / output.name
        stage_manifest = _stage_archive(artifact, runtime, runtime_source, staged)
        native_packages = _stage_native_packages(
            native_lock,
            cache,
            staged,
            runtime,
            offline,
        )
        native_policy_header = _stage_native_package_policy_header(
            native_lock["packages"],
            staged / "prefix/include/hans_native_package_policy.h",
        )
        public_path = staged / "assets/hans/python/runtime-manifest.json"
        public = _json(public_path)
        public["nativePackages"] = native_packages
        public["nativePackagePolicyHeader"] = native_policy_header
        for package in native_packages:
            for library in package["nativeLibraries"]:
                public["nativeModules"].append(
                    {
                        "module": library["moduleName"],
                        "sourceName": (
                            f"{package['packageName']}-{package['version']}.tar.gz:"
                            + library["moduleName"].replace(".", "/")
                            + ".c"
                        ),
                        "packagedName": library["packagedName"],
                        "bytes": library["sizeBytes"],
                        "sha256": library["sha256"],
                    }
                )
        public["nativeModules"] = sorted(
            public["nativeModules"], key=lambda item: item["module"]
        )
        public_bytes = (
            json.dumps(public, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
        ).encode("utf-8")
        _write_bytes(public_path, public_bytes)
        stage_manifest["nativePackages"] = native_packages
        stage_manifest["nativePackagePolicyHeader"] = native_policy_header
        stage_manifest["nativeModules"] = public["nativeModules"]
        stage_manifest["runtimeManifestSha256"] = hashlib.sha256(public_bytes).hexdigest()
        _write_bytes(
            staged / "stage-manifest.json",
            (json.dumps(stage_manifest, indent=2, sort_keys=True) + "\n").encode("utf-8"),
        )
        _replace_generated_tree(staged, output)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--runtime-source", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        stage(args.lock, args.runtime_source, args.cache, args.output)
    except StageError as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
