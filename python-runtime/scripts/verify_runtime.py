#!/usr/bin/env python3
"""Verify staged Hans CPython artifacts without executing target code."""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
import os
import re
import stat
import struct
import zipfile
from collections import Counter
from pathlib import Path
from typing import Any


APK_PYTHON_ASSET_PREFIX = "assets/hans/python/"
APK_RUNTIME_MANIFEST = f"{APK_PYTHON_ASSET_PREFIX}runtime-manifest.json"
APK_STDLIB = f"{APK_PYTHON_ASSET_PREFIX}python314.zip"
APK_RESOLVER = f"{APK_PYTHON_ASSET_PREFIX}resolver.pyz"
APK_NATIVE_CATALOG = f"{APK_PYTHON_ASSET_PREFIX}native-packages/catalog.json"
APK_PYTHON_BRIDGE = "libhans_python_jni.so"
ZIP_LOCAL_HEADER = struct.Struct("<IHHHHHIIIHH")
ZIP_LOCAL_HEADER_SIGNATURE = 0x04034B50
SUPPORTED_APK_COMPRESSION = {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}


class VerificationError(RuntimeError):
    pass


def _standard_extension_modules(source: bytes) -> tuple[str, ...]:
    """Read the process-wide CPython extension allowlist without executing it."""

    try:
        text = source.decode("utf-8", errors="strict")
        tree = ast.parse(text, filename="hans_native_importer.py", mode="exec")
    except (UnicodeDecodeError, SyntaxError) as exc:
        raise VerificationError(f"invalid hans_native_importer.py: {exc}") from exc
    assignments = [
        node
        for node in tree.body
        if isinstance(node, ast.Assign)
        and len(node.targets) == 1
        and isinstance(node.targets[0], ast.Name)
        and node.targets[0].id == "_STANDARD_EXTENSION_MODULES"
    ]
    if len(assignments) != 1 or not isinstance(assignments[0].value, ast.Tuple):
        raise VerificationError("CPython extension allowlist must be one literal tuple")
    values: list[str] = []
    for element in assignments[0].value.elts:
        if not isinstance(element, ast.Constant) or not isinstance(element.value, str):
            raise VerificationError("CPython extension allowlist must contain literal strings")
        value = element.value
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", value):
            raise VerificationError(f"invalid CPython extension allowlist entry: {value!r}")
        values.append(value)
    if not values or len(values) != len(set(values)) or values != sorted(values):
        raise VerificationError("CPython extension allowlist must be nonempty, unique, and sorted")
    return tuple(values)


def _json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise VerificationError(f"invalid JSON file {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise VerificationError(f"JSON root must be an object: {path}")
    return value


def _digest(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    return size, digest.hexdigest()


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _is_sha256(value: Any) -> bool:
    return isinstance(value, str) and bool(re.fullmatch(r"[0-9a-f]{64}", value))


def _qualified_module_name(value: Any) -> bool:
    return isinstance(value, str) and bool(
        re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*", value)
    )


def _native_payload_digest(package: dict[str, Any]) -> str:
    """Reproduce the language-neutral payload identity used by Android/staging."""

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
            companion["assetPath"],
            companion["fileName"],
            str(companion["bytes"]),
            companion["sha256"],
            str(len(package["nativeLibraries"])),
        )
    )
    for library in package["nativeLibraries"]:
        values.extend(
            (
                library["moduleName"],
                library["packagedName"],
                str(library["bytes"]),
                library["sha256"],
            )
        )
    digest = hashlib.sha256()
    for value in values:
        raw = value.encode("utf-8")
        digest.update(str(len(raw)).encode("ascii"))
        digest.update(b":")
        digest.update(raw)
    return digest.hexdigest()


def _native_package_lock(path: Path, runtime: dict[str, Any]) -> list[dict[str, Any]]:
    _require_regular_file(path, "native package lock")
    lock = _json(path)
    if set(lock) != {"schemaVersion", "packages"} or lock.get("schemaVersion") != 1:
        raise VerificationError("unsupported native package lock")
    packages = lock.get("packages")
    if not isinstance(packages, list) or not 1 <= len(packages) <= 32:
        raise VerificationError("native package lock must contain a bounded package list")
    package_fields = {
        "catalogId", "packageName", "version", "source", "target",
        "requiresPython", "requiresDist", "importNames", "companionWheel",
        "nativeLibraries", "payloadSha256",
    }
    source_fields = {"name", "url", "bytes", "sha256"}
    target_fields = {
        "pythonSeries", "interpreterTag", "androidAbi", "minimumAndroidApi",
        "directorySegment",
    }
    seen_catalog_ids: set[str] = set()
    seen_assets: set[str] = set()
    seen_packaged_libraries: set[str] = set()
    seen_native_modules: set[str] = set()
    validated: list[dict[str, Any]] = []
    for package in packages:
        if not isinstance(package, dict) or set(package) != package_fields:
            raise VerificationError("native package descriptor has unexpected fields")
        catalog_id = package.get("catalogId")
        if not isinstance(catalog_id, str) or not re.fullmatch(
            r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", catalog_id
        ):
            raise VerificationError("invalid native package catalogId")
        if catalog_id in seen_catalog_ids:
            raise VerificationError(f"duplicate native package catalogId: {catalog_id}")
        seen_catalog_ids.add(catalog_id)
        for field in ("packageName", "version"):
            value = package.get(field)
            if not isinstance(value, str) or not value or len(value) > 128:
                raise VerificationError(f"invalid native package {field}: {catalog_id}")

        source = package.get("source")
        if not isinstance(source, dict) or set(source) != source_fields:
            raise VerificationError(f"invalid native package source: {catalog_id}")
        source_name = source.get("name")
        source_url = source.get("url")
        if (
            not isinstance(source_name, str)
            or Path(source_name).name != source_name
            or not isinstance(source_url, str)
            or not source_url.startswith("https://files.pythonhosted.org/")
            or not isinstance(source.get("bytes"), int)
            or isinstance(source.get("bytes"), bool)
            or source["bytes"] <= 0
            or not _is_sha256(source.get("sha256"))
        ):
            raise VerificationError(f"invalid pinned native source: {catalog_id}")

        target = package.get("target")
        android = runtime.get("android")
        if not isinstance(target, dict) or set(target) != target_fields or not isinstance(
            android, dict
        ):
            raise VerificationError(f"invalid native package target: {catalog_id}")
        python_series = target.get("pythonSeries")
        minimum_api = target.get("minimumAndroidApi")
        if (
            not isinstance(python_series, str)
            or not str(runtime.get("version", "")).startswith(python_series + ".")
            or target.get("androidAbi") != android.get("abi")
            or not isinstance(minimum_api, int)
            or isinstance(minimum_api, bool)
            or minimum_api != android.get("hostApplicationMinimumApi")
            or not isinstance(target.get("interpreterTag"), str)
            or not re.fullmatch(r"cp[0-9]+", target["interpreterTag"])
            or not isinstance(target.get("directorySegment"), str)
            or not re.fullmatch(r"[A-Za-z0-9_.-]+", target["directorySegment"])
        ):
            raise VerificationError(f"native package target/runtime mismatch: {catalog_id}")

        requires_python = package.get("requiresPython")
        requires_dist = package.get("requiresDist")
        import_names = package.get("importNames")
        if requires_python is not None and not isinstance(requires_python, str):
            raise VerificationError(f"invalid Requires-Python: {catalog_id}")
        if (
            not isinstance(requires_dist, list)
            or any(not isinstance(value, str) or not value for value in requires_dist)
            or len(requires_dist) != len(set(requires_dist))
        ):
            raise VerificationError(f"invalid native dependency list: {catalog_id}")
        if (
            not isinstance(import_names, list)
            or not import_names
            or any(not _qualified_module_name(value) for value in import_names)
            or len(import_names) != len(set(import_names))
        ):
            raise VerificationError(f"invalid native package import allowlist: {catalog_id}")

        companion = package.get("companionWheel")
        if not isinstance(companion, dict) or set(companion) != {
            "assetPath", "fileName", "bytes", "sha256",
        }:
            raise VerificationError(f"invalid companion wheel descriptor: {catalog_id}")
        asset_path = companion.get("assetPath")
        file_name = companion.get("fileName")
        if not isinstance(asset_path, str) or not isinstance(file_name, str):
            raise VerificationError(f"invalid companion wheel path: {catalog_id}")
        if (
            not asset_path.startswith("hans/python/native-packages/")
            or asset_path.startswith("/")
            or ".." in asset_path.split("/")
            or Path(asset_path).name != file_name
            or Path(file_name).name != file_name
            or not file_name.endswith(".whl")
            or asset_path in seen_assets
            or not isinstance(companion.get("bytes"), int)
            or isinstance(companion.get("bytes"), bool)
            or companion["bytes"] <= 0
            or not _is_sha256(companion.get("sha256"))
        ):
            raise VerificationError(f"invalid pinned companion wheel: {catalog_id}")
        seen_assets.add(asset_path)

        libraries = package.get("nativeLibraries")
        if not isinstance(libraries, list) or not libraries:
            raise VerificationError(f"native package has no native libraries: {catalog_id}")
        package_native_modules: set[str] = set()
        for library in libraries:
            if not isinstance(library, dict) or set(library) != {
                "moduleName", "packagedName", "bytes", "sha256",
            }:
                raise VerificationError(f"invalid native library descriptor: {catalog_id}")
            module_name = library.get("moduleName")
            packaged_name = library.get("packagedName")
            if (
                not _qualified_module_name(module_name)
                or module_name not in import_names
                or module_name in seen_native_modules
                or not isinstance(packaged_name, str)
                or not re.fullmatch(r"libhans_py_[A-Za-z0-9_]+\.so", packaged_name)
                or packaged_name in seen_packaged_libraries
                or not isinstance(library.get("bytes"), int)
                or isinstance(library.get("bytes"), bool)
                or library["bytes"] <= 0
                or not _is_sha256(library.get("sha256"))
            ):
                raise VerificationError(f"invalid pinned native library: {catalog_id}")
            package_native_modules.add(module_name)
            seen_native_modules.add(module_name)
            seen_packaged_libraries.add(packaged_name)
        if not package_native_modules:
            raise VerificationError(f"empty request-scoped native allowlist: {catalog_id}")
        if package.get("payloadSha256") != _native_payload_digest(package):
            raise VerificationError(f"native package payload digest mismatch: {catalog_id}")
        validated.append(package)
    return validated


def _native_manifest_entry(package: dict[str, Any]) -> dict[str, Any]:
    companion = package["companionWheel"]
    return {
        "catalogId": package["catalogId"],
        "packageName": package["packageName"],
        "version": package["version"],
        "payloadSha256": package["payloadSha256"],
        "sourceSdistSha256": package["source"]["sha256"],
        "supportedTargets": [package["target"]["directorySegment"]],
        "importNames": package["importNames"],
        "requiresPython": package["requiresPython"],
        "requiresDist": package["requiresDist"],
        "companionWheel": {
            "assetPath": companion["assetPath"],
            "fileName": companion["fileName"],
            "sha256": companion["sha256"],
            "sizeBytes": companion["bytes"],
        },
        "nativeLibraries": [
            {
                "moduleName": library["moduleName"],
                "packagedName": library["packagedName"],
                "sha256": library["sha256"],
                "sizeBytes": library["bytes"],
            }
            for library in package["nativeLibraries"]
        ],
    }


def _length_framed_digest(values: list[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        raw = value.encode("utf-8")
        digest.update(str(len(raw)).encode("ascii"))
        digest.update(b":")
        digest.update(raw)
    return digest.hexdigest()


def _native_package_policy(
    packages: list[dict[str, Any]],
) -> tuple[dict[str, Any], bytes]:
    entries = sorted(
        (library["moduleName"], library["packagedName"])
        for package in packages
        for library in package["nativeLibraries"]
    )
    if not entries or len(entries) > 32 or len(entries) != len(set(entries)):
        raise VerificationError(
            "native package C++ policy must contain unique bounded entries"
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
    descriptor = {
        "path": "prefix/include/hans_native_package_policy.h",
        "sizeBytes": len(header),
        "sha256": _sha256(header),
        "policySha256": policy_sha,
        "entryCount": len(entries),
    }
    return descriptor, header


def _verify_native_package_policy_header(
    stage_root: Path,
    packages: list[dict[str, Any]],
    public: dict[str, Any],
    stage: dict[str, Any] | None,
) -> None:
    expected_descriptor, expected_header = _native_package_policy(packages)
    descriptor = public.get("nativePackagePolicyHeader")
    if not isinstance(descriptor, dict) or set(descriptor) != {
        "path", "sizeBytes", "sha256", "policySha256", "entryCount",
    }:
        raise VerificationError("runtime manifest has an invalid native policy descriptor")
    if descriptor.get("path") != "prefix/include/hans_native_package_policy.h":
        raise VerificationError("runtime manifest native policy path changed")
    if descriptor != expected_descriptor:
        raise VerificationError(
            "runtime manifest native policy descriptor does not match the native lock"
        )
    if stage is not None and stage.get("nativePackagePolicyHeader") != descriptor:
        raise VerificationError("stage/runtime native policy descriptors differ")
    header_path = stage_root / "prefix/include/hans_native_package_policy.h"
    _require_regular_file(header_path, "native package policy header")
    header = header_path.read_bytes()
    if (
        len(header) != descriptor["sizeBytes"]
        or _sha256(header) != descriptor["sha256"]
        or header != expected_header
    ):
        raise VerificationError(
            "staged native package policy header does not match canonical lock rendering"
        )


def _verify_companion_wheel(data: bytes, label: str) -> None:
    import io

    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            if names != sorted(names) or len(names) != len(set(names)):
                raise VerificationError(f"companion wheel entries are not unique and sorted: {label}")
            if not names:
                raise VerificationError(f"empty companion wheel: {label}")
            for info in infos:
                if info.is_dir():
                    raise VerificationError(f"directory entry in companion wheel: {label}")
                if info.date_time != (1980, 1, 1, 0, 0, 0):
                    raise VerificationError(f"non-deterministic companion timestamp: {label}")
                if info.compress_type != zipfile.ZIP_STORED:
                    raise VerificationError(f"compressed companion wheel entry: {label}")
                if info.filename.endswith((".so", ".dex", ".jar", ".exe")):
                    raise VerificationError(f"executable payload in companion wheel: {label}")
    except zipfile.BadZipFile as exc:
        raise VerificationError(f"invalid companion wheel: {label}") from exc


def _verify_native_package_supply_chain(
    lock_path: Path,
    stage_root: Path,
    runtime: dict[str, Any],
    public: dict[str, Any],
    stage: dict[str, Any] | None = None,
) -> tuple[dict[str, bytes], dict[str, dict[str, Any]]]:
    packages = _native_package_lock(
        lock_path.parent / "native-packages.lock.json", runtime
    )
    _verify_native_package_policy_header(stage_root, packages, public, stage)
    expected_packages = [_native_manifest_entry(package) for package in packages]
    if public.get("nativePackages") != expected_packages:
        raise VerificationError("runtime manifest nativePackages do not match the native lock")
    if stage is not None:
        if stage.get("nativePackages") != expected_packages:
            raise VerificationError("stage manifest nativePackages do not match the native lock")
        if stage.get("nativeModules") != public.get("nativeModules"):
            raise VerificationError("stage/public native module manifests differ")
        public_bytes = (
            stage_root / "assets/hans/python/runtime-manifest.json"
        ).read_bytes()
        if stage.get("runtimeManifestSha256") != _sha256(public_bytes):
            raise VerificationError("stage manifest does not authenticate runtime-manifest.json")

    catalog_path = stage_root / "assets/hans/python/native-packages/catalog.json"
    catalog = _json(catalog_path)
    expected_catalog = {
        "schemaVersion": 1,
        "pythonVersion": runtime["version"],
        "packages": expected_packages,
    }
    if catalog != expected_catalog:
        raise VerificationError("staged native package catalog does not match the native lock")
    expected_catalog_bytes = (
        json.dumps(expected_catalog, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")
    if catalog_path.read_bytes() != expected_catalog_bytes:
        raise VerificationError("staged native package catalog is not canonical")

    expected_assets: dict[str, bytes] = {APK_NATIVE_CATALOG: expected_catalog_bytes}
    expected_libraries: dict[str, dict[str, Any]] = {}
    for package, manifest in zip(packages, expected_packages, strict=True):
        companion = package["companionWheel"]
        companion_path = stage_root / "assets" / Path(*companion["assetPath"].split("/"))
        _require_regular_file(companion_path, "native companion wheel")
        companion_data = companion_path.read_bytes()
        if len(companion_data) != companion["bytes"] or _sha256(companion_data) != companion["sha256"]:
            raise VerificationError(
                f"staged companion wheel differs from native lock: {package['catalogId']}"
            )
        _verify_companion_wheel(companion_data, package["catalogId"])
        expected_assets[f"assets/{companion['assetPath']}"] = companion_data
        for library, projected in zip(
            package["nativeLibraries"], manifest["nativeLibraries"], strict=True
        ):
            entry = f"lib/{runtime['android']['abi']}/{library['packagedName']}"
            if entry in expected_libraries:
                raise VerificationError(f"duplicate native package library: {entry}")
            expected_libraries[entry] = {
                "module": library["moduleName"],
                "packagedName": library["packagedName"],
                "bytes": projected["sizeBytes"],
                "sha256": projected["sha256"],
            }
    return expected_assets, expected_libraries


def _verify_native_module_scopes(
    public: dict[str, Any],
    request_scoped: dict[str, dict[str, Any]],
    process_wide_modules: tuple[str, ...],
) -> None:
    native_modules = public.get("nativeModules")
    if not isinstance(native_modules, list) or len(native_modules) < 40:
        raise VerificationError("runtime manifest native module set is incomplete")
    by_module: dict[str, dict[str, Any]] = {}
    ordered_modules: list[str] = []
    for descriptor in native_modules:
        if not isinstance(descriptor, dict):
            raise VerificationError("invalid native module descriptor")
        module = descriptor.get("module")
        if not _qualified_module_name(module) or module in by_module:
            raise VerificationError(f"invalid or duplicate native module: {module!r}")
        by_module[module] = descriptor
        ordered_modules.append(module)
    if ordered_modules != sorted(ordered_modules):
        raise VerificationError("runtime manifest native modules are not sorted")
    request_modules = {descriptor["module"] for descriptor in request_scoped.values()}
    if request_modules & set(process_wide_modules):
        raise VerificationError(
            "request-scoped third-party module leaked into the process-wide CPython allowlist"
        )
    for descriptor in request_scoped.values():
        actual = by_module.get(descriptor["module"])
        if actual is None or any(
            actual.get(field) != descriptor[field]
            for field in ("module", "packagedName", "bytes", "sha256")
        ):
            raise VerificationError(
                f"request-scoped native module mismatch: {descriptor['module']}"
            )
    standard_modules = tuple(
        module for module in ordered_modules if module not in request_modules
    )
    if standard_modules != process_wide_modules:
        raise VerificationError(
            "runtime manifest stdlib native modules do not match the process-wide "
            "CPython extension allowlist"
        )


def _verify_elf_bytes(
    data: bytes,
    label: str,
    expected_alignment: int,
    compatible_page_sizes: tuple[int, ...] = (4096, 16384),
) -> None:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise VerificationError(f"not an ELF library: {label}")
    if data[4] != 2 or data[5] != 1:
        raise VerificationError(f"ELF must be 64-bit little-endian: {label}")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != 183:
        raise VerificationError(f"ELF is not AArch64 (machine={machine}): {label}")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize = struct.unpack_from("<H", data, 54)[0]
    phnum = struct.unpack_from("<H", data, 56)[0]
    if phentsize < 56 or phnum <= 0 or phoff + phentsize * phnum > len(data):
        raise VerificationError(f"invalid ELF program header table: {label}")
    load_segments = 0
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type, _flags, p_offset, p_vaddr, _paddr, _filesz, _memsz, p_align = struct.unpack_from(
            "<IIQQQQQQ", data, offset
        )
        if p_type != 1:
            continue
        load_segments += 1
        if p_align < expected_alignment or p_align % expected_alignment:
            raise VerificationError(
                f"ELF PT_LOAD alignment {p_align} is below {expected_alignment}: {label}"
            )
        if p_offset % expected_alignment != p_vaddr % expected_alignment:
            raise VerificationError(f"ELF PT_LOAD offset/vaddr alignment mismatch: {label}")
        for page_size in compatible_page_sizes:
            if p_align % page_size or p_offset % page_size != p_vaddr % page_size:
                raise VerificationError(
                    f"ELF PT_LOAD is incompatible with {page_size}-byte pages: {label}"
                )
    if load_segments == 0:
        raise VerificationError(f"ELF contains no PT_LOAD segment: {label}")


def _verify_elf(path: Path, expected_alignment: int) -> None:
    _verify_elf_bytes(path.read_bytes(), str(path), expected_alignment)


def _require_regular_file(path: Path, label: str) -> None:
    try:
        metadata = path.lstat()
    except OSError as exc:
        raise VerificationError(f"cannot inspect {label} {path}: {exc}") from exc
    if not stat.S_ISREG(metadata.st_mode):
        raise VerificationError(f"{label} is not a regular file: {path}")


def _apk_entry_data_offset(apk_stream: Any, info: zipfile.ZipInfo) -> int:
    apk_stream.seek(info.header_offset)
    raw_header = apk_stream.read(ZIP_LOCAL_HEADER.size)
    if len(raw_header) != ZIP_LOCAL_HEADER.size:
        raise VerificationError(f"truncated APK local header for {info.filename}")
    (
        signature,
        _extract_version,
        local_flags,
        local_compression,
        _time,
        _date,
        _crc,
        _compressed_size,
        _uncompressed_size,
        filename_length,
        extra_length,
    ) = ZIP_LOCAL_HEADER.unpack(raw_header)
    if signature != ZIP_LOCAL_HEADER_SIGNATURE:
        raise VerificationError(f"invalid APK local header for {info.filename}")
    if local_flags != info.flag_bits or local_compression != info.compress_type:
        raise VerificationError(f"APK central/local header mismatch for {info.filename}")
    return info.header_offset + ZIP_LOCAL_HEADER.size + filename_length + extra_length


def _python_native_basename(name: str) -> bool:
    return (
        name == APK_PYTHON_BRIDGE
        or name.startswith("libpython")
        or bool(re.fullmatch(r"lib[A-Za-z0-9_]+_python\.so", name))
        or bool(re.fullmatch(r"libhans_py_[A-Za-z0-9_]+\.so", name))
    )


def _native_descriptors(
    runtime: dict[str, Any], public: dict[str, Any]
) -> dict[str, dict[str, Any]]:
    abi = runtime.get("android", {}).get("abi")
    if abi != "arm64-v8a":
        raise VerificationError(f"unsupported packaged Python ABI: {abi!r}")
    core_libraries = runtime.get("coreLibraries")
    native_modules = public.get("nativeModules")
    if not isinstance(core_libraries, list) or not isinstance(native_modules, list):
        raise VerificationError("Python native descriptor lists are missing")
    expected: dict[str, dict[str, Any]] = {}
    for descriptor in [*core_libraries, *native_modules]:
        if not isinstance(descriptor, dict):
            raise VerificationError("invalid Python native descriptor")
        name = descriptor.get("name") or descriptor.get("packagedName")
        size = descriptor.get("bytes")
        digest = descriptor.get("sha256")
        if not isinstance(name, str) or not _python_native_basename(name):
            raise VerificationError(f"invalid packaged Python native name: {name!r}")
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            raise VerificationError(f"invalid packaged Python native size: {name}")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise VerificationError(f"invalid packaged Python native digest: {name}")
        entry = f"lib/{abi}/{name}"
        if entry in expected:
            raise VerificationError(f"duplicate packaged Python native descriptor: {entry}")
        expected[entry] = descriptor
    return expected


def _verify_asset_mode(info: zipfile.ZipInfo) -> None:
    if info.create_system != 3:
        return
    mode = stat.S_IMODE(info.external_attr >> 16)
    if mode & 0o111:
        raise VerificationError(f"executable mode in packaged Python asset: {info.filename}")


def verify(lock_path: Path, stage_root: Path) -> None:
    lock = _json(lock_path)
    runtime = lock.get("runtime")
    if lock.get("schemaVersion") != 1 or not isinstance(runtime, dict):
        raise VerificationError("unsupported Python runtime lock")
    marker = stage_root / ".hans-python-runtime-generated"
    if not marker.is_file():
        raise VerificationError(f"staged runtime marker is missing: {marker}")
    stage = _json(stage_root / "stage-manifest.json")
    public = _json(stage_root / "assets/hans/python/runtime-manifest.json")
    required_public = {
        "schemaVersion": 1,
        "pythonVersion": runtime["version"],
        "abi": "arm64-v8a",
        "stdlibAsset": "hans/python/python314.zip",
    }
    for key, expected in required_public.items():
        if public.get(key) != expected:
            raise VerificationError(f"runtime manifest {key} mismatch")
    if public.get("compatiblePageSizesBytes") != [4096, 16384]:
        raise VerificationError("runtime manifest page-size compatibility mismatch")
    if public.get("environmentArchive") != runtime.get("environmentArchive"):
        raise VerificationError("runtime manifest environment archive contract mismatch")
    stdlib = stage_root / "assets/hans/python/python314.zip"
    stdlib_size, stdlib_sha = _digest(stdlib)
    stdlib_lock = runtime["stdlib"]
    if stdlib_lock.get("archiveCompression") != "stored":
        raise VerificationError("stdlib archive compression contract is not pinned")
    if (
        stdlib_lock.get("expectedBytes") != stdlib_size
        or stdlib_lock.get("expectedSha256") != stdlib_sha
    ):
        raise VerificationError("python.lock.json does not match python314.zip")
    if public.get("stdlibBytes") != stdlib_size or public.get("stdlibSha256") != stdlib_sha:
        raise VerificationError("runtime manifest does not match python314.zip")
    if stage.get("stdlibBytes") != stdlib_size or stage.get("stdlibSha256") != stdlib_sha:
        raise VerificationError("stage manifest does not match python314.zip")
    with zipfile.ZipFile(stdlib) as archive:
        names = archive.namelist()
        if names != sorted(names) or len(names) != len(set(names)):
            raise VerificationError("stdlib ZIP entries are not unique and sorted")
        for info in archive.infolist():
            if info.date_time != (1980, 1, 1, 0, 0, 0):
                raise VerificationError(f"non-deterministic ZIP timestamp: {info.filename}")
            if info.compress_type != zipfile.ZIP_STORED:
                raise VerificationError(
                    f"compressed stdlib entry cannot bootstrap without zlib: {info.filename}"
                )
            if info.filename.endswith((".so", ".dex", ".jar", ".exe")):
                raise VerificationError(f"executable payload in stdlib ZIP: {info.filename}")
        for required in (
            "encodings/__init__.py",
            "hans_fd_importer.py",
            "hans_native_importer.py",
            "hans_runtime_bootstrap.py",
            "hans_runtime_api.py",
        ):
            if required not in names:
                raise VerificationError(f"stdlib ZIP is missing {required}")
        importer_extensions = _standard_extension_modules(
            archive.read("hans_native_importer.py")
        )

    jni = stage_root / "jniLibs/arm64-v8a"
    expected_alignment = runtime["android"]["pageAlignmentBytes"]
    expected_names: set[str] = set()
    for descriptor in runtime["coreLibraries"]:
        name = descriptor["name"]
        expected_names.add(name)
        path = jni / name
        size, digest = _digest(path)
        if size != descriptor["bytes"] or digest != descriptor["sha256"]:
            raise VerificationError(f"pinned core library mismatch: {name}")
        _verify_elf(path, expected_alignment)
    native_assets, request_scoped_libraries = _verify_native_package_supply_chain(
        lock_path, stage_root, runtime, public, stage
    )
    native_modules = public.get("nativeModules")
    if not isinstance(native_modules, list) or len(native_modules) < 40:
        raise VerificationError("runtime manifest native module set is incomplete")
    required_native_modules = {"_asyncio", "_posixsubprocess", "_sqlite3", "_ssl"}
    actual_native_modules = {
        module.get("module")
        for module in native_modules
        if isinstance(module, dict) and isinstance(module.get("module"), str)
    }
    missing_native_modules = required_native_modules - actual_native_modules
    if missing_native_modules:
        raise VerificationError(
            "runtime manifest is missing required native modules: "
            f"{sorted(missing_native_modules)}"
        )
    _verify_native_module_scopes(public, request_scoped_libraries, importer_extensions)
    for module in native_modules:
        if not isinstance(module, dict):
            raise VerificationError("invalid native module descriptor")
        name = module.get("packagedName")
        if not isinstance(name, str) or not re.fullmatch(r"libhans_py_[A-Za-z0-9_]+\.so", name):
            raise VerificationError(f"invalid packaged native module name: {name!r}")
        expected_names.add(name)
        path = jni / name
        size, digest = _digest(path)
        if size != module.get("bytes") or digest != module.get("sha256"):
            raise VerificationError(f"native module mismatch: {name}")
        _verify_elf(path, expected_alignment)
    actual_names = {path.name for path in jni.iterdir() if path.is_file()}
    if actual_names != expected_names:
        raise VerificationError(
            f"unexpected staged native libraries: missing={sorted(expected_names-actual_names)}, "
            f"extra={sorted(actual_names-expected_names)}"
        )
    expected_python_assets = {
        "assets/hans/python/runtime-manifest.json",
        "assets/hans/python/python314.zip",
        *native_assets,
    }
    actual_python_assets = {
        path.relative_to(stage_root).as_posix()
        for path in (stage_root / "assets/hans/python").rglob("*")
        if path.is_file()
    }
    if actual_python_assets != expected_python_assets:
        raise VerificationError(
            "unexpected staged Python assets: "
            f"missing={sorted(expected_python_assets-actual_python_assets)}, "
            f"extra={sorted(actual_python_assets-expected_python_assets)}"
        )
    for path in (stage_root / "assets").rglob("*"):
        if path.is_file() and stat.S_IMODE(path.stat().st_mode) & 0o111:
            raise VerificationError(f"executable mode in Python assets: {path}")
    if not (stage_root / "prefix/include/python3.14/Python.h").is_file():
        raise VerificationError("CPython development headers are missing")
    if stage.get("headerFiles", 0) < 100:
        raise VerificationError("CPython development header set is incomplete")


def verify_apk(
    lock_path: Path,
    stage_root: Path,
    bridge_path: Path,
    resolver_path: Path,
    apk_path: Path,
) -> None:
    for path, label in (
        (lock_path, "Python runtime lock"),
        (bridge_path, "CPython JNI bridge"),
        (resolver_path, "Python resolver bundle"),
        (apk_path, "Standard APK"),
    ):
        _require_regular_file(path, label)
    verify(lock_path, stage_root)
    lock = _json(lock_path)
    runtime = lock["runtime"]
    public_path = stage_root / "assets/hans/python/runtime-manifest.json"
    public = _json(public_path)
    expected_alignment = runtime["android"]["pageAlignmentBytes"]
    native_assets, _request_scoped_libraries = _verify_native_package_supply_chain(
        lock_path, stage_root, runtime, public
    )
    bridge_data = bridge_path.read_bytes()
    resolver_data = resolver_path.read_bytes()
    _verify_elf_bytes(bridge_data, str(bridge_path), expected_alignment)

    with zipfile.ZipFile(apk_path) as archive:
        entries = archive.infolist()
        duplicate_names = sorted(
            name for name, count in Counter(info.filename for info in entries).items() if count > 1
        )
        if duplicate_names:
            raise VerificationError(
                "APK contains duplicate ZIP entries: " + ", ".join(duplicate_names)
            )
        by_name = {info.filename: info for info in entries}

        def required_bytes(name: str) -> bytes:
            info = by_name.get(name)
            if info is None or info.is_dir():
                raise VerificationError(f"APK is missing {name}")
            if info.flag_bits & 0x1:
                raise VerificationError(f"encrypted Python APK entry is forbidden: {name}")
            if info.compress_type not in SUPPORTED_APK_COMPRESSION:
                raise VerificationError(
                    f"unsupported Python APK compression {info.compress_type}: {name}"
                )
            return archive.read(info)

        expected_assets = {
            APK_RUNTIME_MANIFEST: public_path.read_bytes(),
            APK_STDLIB: (stage_root / "assets/hans/python/python314.zip").read_bytes(),
            APK_RESOLVER: resolver_data,
            **native_assets,
        }
        packaged_assets = {
            info.filename
            for info in entries
            if not info.is_dir() and info.filename.startswith(APK_PYTHON_ASSET_PREFIX)
        }
        if packaged_assets != set(expected_assets):
            raise VerificationError(
                "APK Python asset set mismatch: "
                f"missing={sorted(set(expected_assets) - packaged_assets)}, "
                f"extra={sorted(packaged_assets - set(expected_assets))}"
            )
        for name, expected in expected_assets.items():
            info = by_name[name]
            _verify_asset_mode(info)
            if required_bytes(name) != expected:
                raise VerificationError(f"APK Python asset differs from verified build output: {name}")

        packaged_manifest = expected_assets[APK_RUNTIME_MANIFEST]
        if packaged_manifest != public_path.read_bytes():
            raise VerificationError("APK Python runtime manifest differs from staging")
        packaged_stdlib = expected_assets[APK_STDLIB]
        if (
            len(packaged_stdlib) != public["stdlibBytes"]
            or hashlib.sha256(packaged_stdlib).hexdigest() != public["stdlibSha256"]
        ):
            raise VerificationError("APK Python stdlib differs from its signed manifest")

        native_descriptors = _native_descriptors(runtime, public)
        expected_native = set(native_descriptors)
        bridge_entry = f"lib/{runtime['android']['abi']}/{APK_PYTHON_BRIDGE}"
        expected_native.add(bridge_entry)
        packaged_native = {
            info.filename
            for info in entries
            if not info.is_dir() and _python_native_basename(Path(info.filename).name)
        }
        if packaged_native != expected_native:
            raise VerificationError(
                "APK Python native set or ABI mismatch: "
                f"missing={sorted(expected_native - packaged_native)}, "
                f"extra={sorted(packaged_native - expected_native)}"
            )

        with apk_path.open("rb") as apk_stream:
            for entry_name in sorted(expected_native):
                info = by_name[entry_name]
                packaged = required_bytes(entry_name)
                if entry_name == bridge_entry:
                    if packaged != bridge_data:
                        raise VerificationError(
                            "APK CPython JNI bridge differs from verified build output"
                        )
                else:
                    descriptor = native_descriptors[entry_name]
                    if (
                        len(packaged) != descriptor["bytes"]
                        or hashlib.sha256(packaged).hexdigest() != descriptor["sha256"]
                    ):
                        raise VerificationError(
                            f"APK native Python artifact mismatch: {entry_name}"
                        )
                _verify_elf_bytes(packaged, f"APK:{entry_name}", expected_alignment)
                if (
                    info.compress_type == zipfile.ZIP_STORED
                    and _apk_entry_data_offset(apk_stream, info) % expected_alignment
                ):
                    raise VerificationError(
                        "uncompressed Python native APK entry is not 16 KiB aligned: "
                        f"{entry_name}"
                    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--stage-root", type=Path, required=True)
    parser.add_argument("--bridge", type=Path)
    parser.add_argument("--resolver", type=Path)
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    try:
        apk_arguments = (args.bridge, args.resolver, args.apk)
        if any(value is not None for value in apk_arguments) and any(
            value is None for value in apk_arguments
        ):
            raise VerificationError("--bridge, --resolver, and --apk must be supplied together")
        if args.bridge is None:
            verify(args.lock, args.stage_root)
        else:
            verify_apk(args.lock, args.stage_root, args.bridge, args.resolver, args.apk)
    except (OSError, KeyError, TypeError, VerificationError, zipfile.BadZipFile) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
