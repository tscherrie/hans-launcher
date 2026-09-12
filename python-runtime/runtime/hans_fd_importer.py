"""Import Python sources directly from JNI-verified read-only ZIP descriptors."""

import _frozen_importlib
import _hans_android
import sys


PLUGIN_SOURCE_PREFIX = "__hans_plugin_source__/"
PLUGIN_NAMESPACE_PREFIX = "_hans_plugin_d"
MAX_PLUGIN_MODULES = 512
MAX_PLUGIN_RELATIVE_PATH_BYTES = 1024
MAX_PLUGIN_SOURCE_BYTES = 8 * 1024 * 1024


class HansPluginValidationError(ValueError):
    """The requested plugin identity or entrypoint is not safely importable."""


class HansPluginSourceMissing(ImportError):
    """A declared plugin source member is absent from the verified archive."""


class HansPluginSourceCollision(ImportError):
    """The archive ambiguously declares one name as both module and package."""


class HansFdSourceLoader:
    def __init__(self, fullname: str, source: bytes, origin: str, is_package: bool) -> None:
        self.fullname = fullname
        self.source = source
        self.origin = origin
        self.package = is_package

    def create_module(self, spec):  # noqa: ANN001
        return None

    def exec_module(self, module) -> None:  # noqa: ANN001
        code = compile(self.source, self.origin, "exec", dont_inherit=True)
        exec(code, module.__dict__, module.__dict__)

    def get_code(self, fullname: str):
        if fullname != self.fullname:
            raise ImportError(fullname)
        return compile(self.source, self.origin, "exec", dont_inherit=True)

    def get_filename(self, fullname: str) -> str:
        if fullname != self.fullname:
            raise ImportError(fullname)
        return self.origin

    def get_source(self, fullname: str) -> str:
        if fullname != self.fullname:
            raise ImportError(fullname)
        return self.source.decode("utf-8")

    def is_package(self, fullname: str) -> bool:
        if fullname != self.fullname:
            raise ImportError(fullname)
        return self.package


class HansFdSourceFinder:
    def find_spec(self, fullname: str, path=None, target=None):  # noqa: ANN001
        del path, target
        if fullname == PLUGIN_SOURCE_PREFIX[:-1] or fullname.startswith(
            PLUGIN_SOURCE_PREFIX[:-1] + "."
        ):
            raise ModuleNotFoundError("reserved Hans plugin source namespace")
        found = _hans_android._archive_lookup(fullname)
        if found is None:
            return None
        source, origin, is_package = found
        loader = HansFdSourceLoader(fullname, source, origin, is_package)
        spec = _frozen_importlib.ModuleSpec(
            fullname,
            loader,
            origin=origin,
            is_package=is_package,
        )
        # ModuleSpec does not infer a file-like location from a synthetic
        # origin. Mark it explicitly so imported modules expose a useful,
        # non-filesystem __file__ and the dispatcher can evict environment
        # modules deterministically after each request.
        spec.has_location = True
        return spec


def install() -> HansFdSourceFinder:
    for finder in sys.meta_path:
        if isinstance(finder, HansFdSourceFinder):
            return finder
    finder = HansFdSourceFinder()
    # Built-in/frozen modules remain authoritative. Insert immediately after
    # them and ahead of filesystem finders, which have no worker paths anyway.
    position = min(2, len(sys.meta_path))
    sys.meta_path.insert(position, finder)
    return finder


def _valid_plugin_id(value: object) -> bool:
    if not isinstance(value, str) or not 1 <= len(value) <= 128 or not value.isascii():
        return False
    if not value[0].isalnum():
        return False
    return all(character.isalnum() or character in "._-" for character in value)


def _valid_digest(value: object) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


def _entrypoint_parts(relative_path: object) -> tuple[tuple[str, ...], str]:
    if not isinstance(relative_path, str) or not relative_path:
        raise HansPluginValidationError("plugin relativePath must be a non-empty string")
    try:
        encoded_length = len(relative_path.encode("utf-8"))
    except UnicodeEncodeError as exc:
        raise HansPluginValidationError("plugin relativePath is not valid UTF-8") from exc
    if encoded_length > MAX_PLUGIN_RELATIVE_PATH_BYTES:
        raise HansPluginValidationError("plugin relativePath is too long")
    if relative_path.startswith("/") or "\\" in relative_path or "\x00" in relative_path:
        raise HansPluginValidationError("plugin relativePath must be a POSIX relative path")
    path_parts = tuple(relative_path.split("/"))
    if any(part in {"", ".", ".."} for part in path_parts):
        raise HansPluginValidationError("plugin relativePath escaped its source root")
    filename = path_parts[-1]
    if not filename.endswith(".py"):
        raise HansPluginValidationError("plugin relativePath is not Python source")
    if filename == "__init__.py":
        module_parts = path_parts[:-1]
    else:
        module_parts = path_parts[:-1] + (filename[:-3],)
    if any(not part.isascii() or not part.isidentifier() for part in module_parts):
        raise HansPluginValidationError(
            "plugin source path components must be Python identifiers"
        )
    return module_parts, relative_path


class HansPluginSourceFinder:
    """Request-scoped finder for one verified plugin source subtree."""

    def __init__(self, namespace: str) -> None:
        self.namespace = namespace
        self._spec_count = 0

    def _read(self, member: str) -> bytes | None:
        source = _hans_android._archive_read("environment", member)
        if source is None:
            return None
        if not isinstance(source, bytes):
            raise ImportError("Hans plugin archive returned a non-byte source")
        if len(source) > MAX_PLUGIN_SOURCE_BYTES:
            raise ImportError("Hans plugin source exceeds its byte limit")
        return source

    def _source(self, relative_stem: str) -> tuple[bytes, str, bool, str] | None:
        package_member = PLUGIN_SOURCE_PREFIX + relative_stem + "/__init__.py"
        module_member = PLUGIN_SOURCE_PREFIX + relative_stem + ".py"
        package_source = self._read(package_member)
        module_source = self._read(module_member)
        if package_source is not None and module_source is not None:
            raise HansPluginSourceCollision(
                f"plugin source collision for {relative_stem}"
            )
        if package_source is not None:
            return package_source, package_member, True, package_member
        if module_source is not None:
            return module_source, module_member, False, module_member
        return None

    def _root_source(self) -> tuple[bytes, str, bool, str]:
        member = PLUGIN_SOURCE_PREFIX + "__init__.py"
        source = self._read(member)
        if source is None:
            return b"", f"hans-plugin://{self.namespace}/__init__.py", True, ""
        return source, member, True, member

    def lookup(self, fullname: str) -> tuple[bytes, str, bool, str] | None:
        if fullname == self.namespace:
            source, member_or_origin, is_package, member = self._root_source()
            origin = (
                member_or_origin
                if member_or_origin.startswith("hans-plugin://")
                else "hans-fd://environment/" + member_or_origin
            )
            return source, origin, is_package, member
        prefix = self.namespace + "."
        if not fullname.startswith(prefix):
            return None
        suffix = fullname[len(prefix) :]
        components = suffix.split(".")
        if not components or any(
            not component.isascii() or not component.isidentifier()
            for component in components
        ):
            return None
        found = self._source("/".join(components))
        if found is None:
            return None
        source, member_or_origin, is_package, member = found
        return source, "hans-fd://environment/" + member_or_origin, is_package, member

    def find_spec(self, fullname: str, path=None, target=None):  # noqa: ANN001
        del path, target
        found = self.lookup(fullname)
        if found is None:
            return None
        self._spec_count += 1
        if self._spec_count > MAX_PLUGIN_MODULES:
            raise ImportError("plugin imported too many source modules")
        source, origin, is_package, _member = found
        loader = HansFdSourceLoader(fullname, source, origin, is_package)
        spec = _frozen_importlib.ModuleSpec(
            fullname,
            loader,
            origin=origin,
            is_package=is_package,
        )
        spec.has_location = origin.startswith("hans-fd://")
        return spec


class HansPluginImportScope:
    """Owns one collision-free plugin importer and all modules it creates."""

    def __init__(self, plugin_id: str, environment_digest: str) -> None:
        if not _valid_plugin_id(plugin_id):
            raise HansPluginValidationError("pluginId contains unsupported characters")
        if not _valid_digest(environment_digest):
            raise HansPluginValidationError(
                "environmentDigest must be a lowercase SHA-256"
            )
        self.plugin_id = plugin_id
        self.environment_digest = environment_digest
        self.namespace = PLUGIN_NAMESPACE_PREFIX + environment_digest
        self.finder = HansPluginSourceFinder(self.namespace)
        self._entered = False
        self._meta_path_before: tuple[object, ...] = ()
        self._modules = None

    def __enter__(self):
        if self._entered:
            raise RuntimeError("Hans plugin import scope cannot be re-entered")
        namespace_prefix = self.namespace + "."
        if any(
            name == self.namespace or name.startswith(namespace_prefix)
            for name in sys.modules
        ):
            raise HansPluginSourceCollision("plugin namespace is already occupied")
        self._meta_path_before = tuple(sys.meta_path)
        self._modules = sys.modules
        position = min(2, len(sys.meta_path))
        sys.meta_path.insert(position, self.finder)
        self._entered = True
        return self

    def __exit__(self, exception_type, exception, traceback) -> None:  # noqa: ANN001
        del exception_type, exception, traceback
        namespace_prefix = self.namespace + "."
        modules = self._modules if isinstance(self._modules, dict) else sys.modules
        for name in tuple(modules):
            if name == self.namespace or name.startswith(namespace_prefix):
                modules.pop(name, None)
        if sys.modules is not modules:
            sys.modules = modules
        if isinstance(sys.meta_path, list):
            sys.meta_path[:] = self._meta_path_before
        else:
            sys.meta_path = list(self._meta_path_before)
        self._entered = False

    def _module_name(self, relative_path: object) -> tuple[str, str]:
        module_parts, normalized = _entrypoint_parts(relative_path)
        if not module_parts:
            return self.namespace, normalized
        return self.namespace + "." + ".".join(module_parts), normalized

    def validate_entry_path(self, relative_path: object) -> str:
        """Validate an entrypoint without reading or executing plugin source."""

        return self._module_name(relative_path)[1]

    def import_entry(self, relative_path: object):
        if not self._entered:
            raise RuntimeError("Hans plugin import scope is not active")
        module_name, normalized = self._module_name(relative_path)
        found = self.finder.lookup(module_name)
        if found is None:
            raise HansPluginSourceMissing(f"missing plugin source: {normalized}")
        expected_member = PLUGIN_SOURCE_PREFIX + normalized
        if found[3] != expected_member:
            raise HansPluginSourceCollision(
                f"plugin entrypoint collides with another source: {normalized}"
            )
        return __import__(module_name, fromlist=("*",))

    def resolve_callable(self, relative_path: object, callable_name: object):
        if not isinstance(callable_name, str) or not callable_name:
            raise HansPluginValidationError("function must be a non-empty string")
        target = self.import_entry(relative_path)
        for component in callable_name.split("."):
            if (
                not component
                or not component.isascii()
                or component.startswith("_")
                or not component.isidentifier()
            ):
                raise HansPluginValidationError(
                    "function contains a private or invalid component"
                )
            try:
                target = getattr(target, component)
            except AttributeError as exc:
                raise HansPluginValidationError(
                    f"plugin callable is missing: {callable_name}"
                ) from exc
        if not callable(target):
            raise HansPluginValidationError("requested plugin target is not callable")
        return target


def plugin_import_scope(
    plugin_id: str, environment_digest: str
) -> HansPluginImportScope:
    """Create a request-owned importer for exactly one verified environment."""

    return HansPluginImportScope(plugin_id, environment_digest)
