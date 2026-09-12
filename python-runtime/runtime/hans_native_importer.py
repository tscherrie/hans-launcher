"""Load only APK-signed CPython extensions from Android nativeLibraryDir.

CPython's own extension modules and third-party package extensions deliberately use
different finders. The process-wide finder has a closed stdlib allowlist. A
third-party finder exists only for one verified environment request and accepts
only the exact module/library pairs transported by the Android runtime broker.
"""

from __future__ import annotations

import importlib.machinery
import importlib.util
import os
import sys
from collections.abc import Iterable, Mapping


_PREFIX = "libhans_py_"
_MAX_THIRD_PARTY_MODULES = 32

# Exact CPython 3.14 extension surface staged by Hans. Keeping it explicit
# prevents a newly bundled third-party ELF from becoming process-wide importable
# merely because it shares the libhans_py_ packaging prefix.
_STANDARD_EXTENSION_MODULES = (
        "_asyncio", "_bisect", "_blake2", "_bz2", "_codecs_cn",
        "_codecs_hk", "_codecs_iso2022", "_codecs_jp", "_codecs_kr",
        "_codecs_tw", "_csv", "_decimal", "_elementtree", "_hashlib",
        "_heapq", "_hmac", "_interpchannels", "_interpqueues",
        "_interpreters", "_json", "_lsprof", "_lzma", "_md5",
        "_multibytecodec", "_pickle", "_posixsubprocess", "_queue",
        "_random", "_sha1", "_sha2", "_sha3", "_socket", "_sqlite3",
        "_ssl", "_statistics", "_struct", "_zoneinfo", "_zstd", "array",
        "binascii", "cmath", "fcntl", "math", "mmap", "pyexpat",
        "resource", "select", "syslog", "termios", "unicodedata", "zlib",
)


class HansNativeValidationError(ValueError):
    """A native module request did not match the closed transport contract."""


def _valid_digest(value: object) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


def _encoded_module(fullname: object) -> str:
    if not isinstance(fullname, str) or not 1 <= len(fullname) <= 128:
        raise HansNativeValidationError("native module name is invalid")
    components = fullname.split(".")
    if any(
        not component
        or not component.isascii()
        or not component.isidentifier()
        for component in components
    ):
        raise HansNativeValidationError("native module name is invalid")
    return fullname.replace(".", "__")


def _expected_packaged_name(fullname: str) -> str:
    return f"{_PREFIX}{_encoded_module(fullname)}.so"


def _native_directory(native_library_dir: object) -> str:
    if not isinstance(native_library_dir, str):
        raise HansNativeValidationError("nativeLibraryDir must be a string")
    directory = os.path.realpath(native_library_dir)
    if not os.path.isabs(directory) or not os.path.isdir(directory):
        raise HansNativeValidationError(
            "nativeLibraryDir must be an existing absolute directory"
        )
    return directory


def _closed_allowlist(descriptors: object) -> dict[str, str]:
    if not isinstance(descriptors, list):
        raise HansNativeValidationError("allowedNativeModules must be an array")
    if len(descriptors) > _MAX_THIRD_PARTY_MODULES:
        raise HansNativeValidationError("too many allowed native modules")
    allowed: dict[str, str] = {}
    packaged_names: set[str] = set()
    for descriptor in descriptors:
        if not isinstance(descriptor, Mapping) or set(descriptor) != {
            "module",
            "packagedName",
        }:
            raise HansNativeValidationError("native module descriptor is invalid")
        module = descriptor.get("module")
        packaged_name = descriptor.get("packagedName")
        encoded = _encoded_module(module)
        expected = f"{_PREFIX}{encoded}.so"
        if (
            not isinstance(packaged_name, str)
            or packaged_name != expected
            or "/" in packaged_name
            or "\\" in packaged_name
        ):
            raise HansNativeValidationError(
                "native module packagedName does not match its module"
            )
        module_root = module.split(".", 1)[0]
        if (
            module in _STANDARD_EXTENSION_MODULES
            or module_root in sys.stdlib_module_names
        ):
            raise HansNativeValidationError(
                "standard CPython extensions cannot be request-scoped"
            )
        if module in allowed or packaged_name in packaged_names:
            raise HansNativeValidationError("duplicate native module descriptor")
        allowed[module] = packaged_name
        packaged_names.add(packaged_name)
    return allowed


class HansNativeExtensionFinder:
    """Resolve one exact set of APK-signed extension modules."""

    def __init__(
        self,
        native_library_dir: str,
        allowed_modules: Mapping[str, str] | Iterable[str],
    ) -> None:
        self._directory = _native_directory(native_library_dir)
        if isinstance(allowed_modules, Mapping):
            self._allowed = dict(allowed_modules)
        else:
            self._allowed = {
                module: _expected_packaged_name(module) for module in allowed_modules
            }

    def find_spec(self, fullname: str, path=None, target=None):  # noqa: ANN001
        del path, target
        packaged_name = self._allowed.get(fullname)
        if packaged_name is None:
            return None
        library = os.path.join(self._directory, packaged_name)
        if os.path.dirname(os.path.realpath(library)) != self._directory:
            raise ImportError("native extension escaped nativeLibraryDir")
        if not os.path.isfile(library):
            raise ImportError(f"allowlisted native extension is missing: {fullname}")
        loader = importlib.machinery.ExtensionFileLoader(fullname, library)
        return importlib.util.spec_from_file_location(fullname, library, loader=loader)


class HansThirdPartyNativeScope:
    """Own one environment's native finder and every related package module."""

    def __init__(self, environment_digest: str, descriptors: object) -> None:
        if not _valid_digest(environment_digest):
            raise HansNativeValidationError(
                "environmentDigest must be a lowercase SHA-256"
            )
        standard = _installed_standard_finder()
        self.environment_digest = environment_digest
        self._allowed = _closed_allowlist(descriptors)
        self._roots = frozenset(module.split(".", 1)[0] for module in self._allowed)
        self.finder = HansNativeExtensionFinder(standard._directory, self._allowed)
        self._entered = False
        self._meta_path_before: tuple[object, ...] = ()
        self._modules: dict[str, object] | None = None

    def __enter__(self):
        if self._entered:
            raise RuntimeError("native import scope cannot be re-entered")
        occupied = sorted(
            name for name in sys.modules if name.split(".", 1)[0] in self._roots
        )
        if occupied:
            raise ImportError(
                "third-party native package namespace is already occupied: "
                + occupied[0]
            )
        self._meta_path_before = tuple(sys.meta_path)
        self._modules = sys.modules
        position = min(2, len(sys.meta_path))
        sys.meta_path.insert(position, self.finder)
        self._entered = True
        return self

    def __exit__(self, exception_type, exception, traceback) -> None:  # noqa: ANN001
        del exception_type, exception, traceback
        modules = self._modules if isinstance(self._modules, dict) else sys.modules
        for name in tuple(modules):
            if name.split(".", 1)[0] in self._roots:
                modules.pop(name, None)
        if sys.modules is not modules:
            sys.modules = modules
        if isinstance(sys.meta_path, list):
            sys.meta_path[:] = self._meta_path_before
        else:
            sys.meta_path = list(self._meta_path_before)
        self._entered = False


def _installed_standard_finder() -> HansNativeExtensionFinder:
    for finder in sys.meta_path:
        if isinstance(finder, HansNativeExtensionFinder) and set(
            finder._allowed
        ) == set(_STANDARD_EXTENSION_MODULES):
            return finder
    raise RuntimeError("Hans standard native extension finder is not installed")


def request_scope(
    environment_digest: str,
    allowed_native_modules: object,
) -> HansThirdPartyNativeScope:
    """Create a fail-closed third-party finder for one verified request."""

    return HansThirdPartyNativeScope(environment_digest, allowed_native_modules)


def install(native_library_dir: str) -> HansNativeExtensionFinder:
    """Install the closed CPython-stdlib finder for the signed library directory."""

    directory = _native_directory(native_library_dir)
    for finder in sys.meta_path:
        if isinstance(finder, HansNativeExtensionFinder) and set(
            finder._allowed
        ) == set(_STANDARD_EXTENSION_MODULES):
            if finder._directory != directory:
                raise RuntimeError(
                    "native extension finder already uses another directory"
                )
            return finder
    finder = HansNativeExtensionFinder(directory, _STANDARD_EXTENSION_MODULES)
    # Preserve built-in and frozen module authority.
    sys.meta_path.insert(min(2, len(sys.meta_path)), finder)
    return finder
