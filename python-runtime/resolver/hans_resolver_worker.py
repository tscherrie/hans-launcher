"""Offline dependency solver for Hans Standard.

The Android main process builds a bounded, read-only PYZ containing this module,
the pinned ``packaging``/``resolvelib`` sources, and two JSON payload files.  No
network or filesystem capability is exposed to this worker.
"""

from __future__ import annotations

from dataclasses import dataclass
from importlib.resources import files
import json
import re
from typing import Any, Iterable, Mapping, Sequence

from packaging.markers import default_environment
from packaging.requirements import InvalidRequirement, Requirement
from packaging.specifiers import InvalidSpecifier, SpecifierSet
from packaging.utils import canonicalize_name, parse_wheel_filename
from packaging.version import InvalidVersion, Version
from resolvelib import AbstractProvider, BaseReporter, Resolver
from resolvelib.resolvers import ResolutionImpossible, ResolutionTooDeep


SCHEMA_VERSION = 1
MAX_REQUIREMENTS = 128
MAX_REQUIREMENT_BYTES = 512
MAX_PROJECTS = 128
MAX_CANDIDATES = 1024
MAX_DEPENDENCIES_PER_CANDIDATE = 256
MAX_ROUNDS = 512
SAFE_NAME = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,126}[a-z0-9])?")


class ResolverInputError(ValueError):
    pass


class MissingProjects(RuntimeError):
    def __init__(self, names: Iterable[str]) -> None:
        self.names = tuple(sorted(set(names)))
        super().__init__(", ".join(self.names))


@dataclass(frozen=True)
class Candidate:
    name: str
    version: Version
    version_text: str
    filename: str
    url: str
    sha256: str
    size_bytes: int
    requires_python: str | None
    yanked: bool
    requires_dist: tuple[str, ...]
    extras: frozenset[str]

    @property
    def identity(self) -> tuple[str, str, str, tuple[str, ...]]:
        return self.name, self.version_text, self.filename, tuple(sorted(self.extras))


def _payload(name: str) -> Any:
    package = files("hans_resolver_payload")
    raw = package.joinpath(name).read_text(encoding="utf-8")
    if len(raw.encode("utf-8")) > 16 * 1024 * 1024:
        raise ResolverInputError(f"{name} exceeds the resolver payload limit")
    return json.loads(raw)


def _only_keys(value: Mapping[str, Any], keys: set[str], label: str) -> None:
    extra = set(value) - keys
    if extra:
        raise ResolverInputError(f"unexpected {label} fields")


def _string(value: Any, label: str, maximum: int) -> str:
    if not isinstance(value, str) or not value or len(value.encode("utf-8")) > maximum:
        raise ResolverInputError(f"invalid {label}")
    return value


def _environment(target: Mapping[str, Any]) -> dict[str, str]:
    _only_keys(
        target,
        {"pythonVersion", "interpreterTag", "androidAbi", "minimumAndroidApi"},
        "target",
    )
    version = _string(target.get("pythonVersion"), "Python version", 32)
    interpreter = _string(target.get("interpreterTag"), "interpreter tag", 16)
    abi = _string(target.get("androidAbi"), "Android ABI", 32)
    api = target.get("minimumAndroidApi")
    if not version.startswith("3.14.") or interpreter != "cp314":
        raise ResolverInputError("the resolver only supports CPython 3.14")
    if abi not in {"arm64-v8a", "x86_64"} or not isinstance(api, int) or api < 31:
        raise ResolverInputError("unsupported Android target")
    environment = default_environment()
    environment.update(
        {
            "implementation_name": "cpython",
            "implementation_version": version,
            "os_name": "posix",
            "platform_machine": "aarch64" if abi == "arm64-v8a" else "x86_64",
            "platform_python_implementation": "CPython",
            "platform_release": str(api),
            "platform_system": "Android",
            "platform_version": str(api),
            "python_full_version": version,
            "python_version": ".".join(version.split(".")[:2]),
            "sys_platform": "android",
        }
    )
    return environment


def _root_requirements(raw: Any, environment: Mapping[str, str]) -> list[Requirement]:
    if not isinstance(raw, list) or len(raw) > MAX_REQUIREMENTS:
        raise ResolverInputError("invalid root requirement list")
    requirements: list[Requirement] = []
    for item in raw:
        text = _string(item, "root requirement", MAX_REQUIREMENT_BYTES)
        try:
            requirement = Requirement(text)
        except InvalidRequirement as exc:
            raise ResolverInputError("invalid PEP 508 root requirement") from exc
        if requirement.url is not None:
            raise ResolverInputError("direct URL requirements are not permitted")
        if requirement.marker is not None and not requirement.marker.evaluate(
            {**environment, "extra": ""}, context="requirement"
        ):
            continue
        requirements.append(requirement)
    return requirements


def _valid_tags(filename: str, expected_name: str, expected_version: str) -> bool:
    try:
        name, version, _build, tags = parse_wheel_filename(filename)
    except (InvalidVersion, ValueError):
        return False
    if canonicalize_name(name) != expected_name or str(version) != expected_version:
        return False
    compatible_interpreters = {"py3", "py314", "cp314"}
    return (
        bool(tags)
        and all(tag.abi == "none" and tag.platform == "any" for tag in tags)
        and any(tag.interpreter in compatible_interpreters for tag in tags)
    )


def _catalog(raw: Any) -> dict[str, tuple[Mapping[str, Any], ...]]:
    if not isinstance(raw, dict) or len(raw) > MAX_PROJECTS:
        raise ResolverInputError("invalid project catalog")
    total = 0
    result: dict[str, tuple[Mapping[str, Any], ...]] = {}
    for raw_name, candidates in raw.items():
        name = canonicalize_name(_string(raw_name, "project name", 128))
        if not SAFE_NAME.fullmatch(name) or name != raw_name:
            raise ResolverInputError("project catalog name is not canonical")
        if not isinstance(candidates, list):
            raise ResolverInputError("project candidates must be a list")
        total += len(candidates)
        if total > MAX_CANDIDATES:
            raise ResolverInputError("candidate catalog exceeds its limit")
        if not all(isinstance(candidate, dict) for candidate in candidates):
            raise ResolverInputError("candidate must be an object")
        result[name] = tuple(candidates)
    return result


def _marker_applies(
    requirement: Requirement,
    environment: Mapping[str, str],
    active_extras: frozenset[str],
) -> bool:
    if requirement.marker is None:
        return True
    contexts = active_extras or frozenset({""})
    return any(
        requirement.marker.evaluate({**environment, "extra": extra}, context="requirement")
        for extra in contexts
    )


def _exact_pin(requirement: Requirement, version: Version) -> bool:
    for specifier in requirement.specifier:
        if specifier.operator not in {"==", "==="} or specifier.version.endswith(".*"):
            continue
        try:
            if Version(specifier.version) == version:
                return True
        except InvalidVersion:
            if specifier.operator == "===" and specifier.version == str(version):
                return True
    return False


class Provider(AbstractProvider[Requirement, Candidate, str]):
    def __init__(
        self,
        catalog: Mapping[str, Sequence[Mapping[str, Any]]],
        environment: Mapping[str, str],
    ) -> None:
        self.catalog = catalog
        self.environment = environment

    def identify(self, requirement_or_candidate: Requirement | Candidate) -> str:
        if isinstance(requirement_or_candidate, Candidate):
            return requirement_or_candidate.name
        return canonicalize_name(requirement_or_candidate.name)

    def get_preference(
        self,
        identifier: str,
        resolutions: Mapping[str, Candidate],
        candidates: Mapping[str, Iterable[Candidate]],
        information: Mapping[str, Iterable[Any]],
        backtrack_causes: Sequence[Any],
    ) -> tuple[int, int, str]:
        requirement_count = sum(1 for _ in information[identifier])
        candidate_count = sum(1 for _ in candidates[identifier])
        return (candidate_count, -requirement_count, identifier)

    def find_matches(
        self,
        identifier: str,
        requirements: Mapping[str, Iterable[Requirement]],
        incompatibilities: Mapping[str, Iterable[Candidate]],
    ) -> Iterable[Candidate]:
        requirement_list = list(requirements[identifier])
        rows = self.catalog.get(identifier)
        if rows is None:
            raise MissingProjects([identifier])
        extras = frozenset(
            canonicalize_name(extra)
            for requirement in requirement_list
            for extra in requirement.extras
        )
        incompatible = {candidate.identity for candidate in incompatibilities[identifier]}
        candidates = [
            candidate
            for row in rows
            if (candidate := self._candidate(identifier, row, extras)) is not None
            and candidate.identity not in incompatible
            and all(
                requirement.specifier.contains(candidate.version, prereleases=True)
                for requirement in requirement_list
            )
            and (not candidate.yanked or any(_exact_pin(req, candidate.version) for req in requirement_list))
        ]
        stable = [candidate for candidate in candidates if not candidate.version.is_prerelease]
        explicitly_allows_prerelease = any(
            requirement.specifier.prereleases is True for requirement in requirement_list
        )
        if stable and not explicitly_allows_prerelease:
            candidates = stable
        unique: dict[Version, Candidate] = {}
        for candidate in sorted(candidates, key=lambda item: item.filename):
            current = unique.get(candidate.version)
            if current is None or self._wheel_rank(candidate.filename) > self._wheel_rank(current.filename):
                unique[candidate.version] = candidate
        return sorted(unique.values(), key=lambda item: (item.version, item.filename), reverse=True)

    def is_satisfied_by(self, requirement: Requirement, candidate: Candidate) -> bool:
        required_extras = {canonicalize_name(extra) for extra in requirement.extras}
        return (
            canonicalize_name(requirement.name) == candidate.name
            and requirement.url is None
            and requirement.specifier.contains(candidate.version, prereleases=True)
            and required_extras.issubset(candidate.extras)
        )

    def get_dependencies(self, candidate: Candidate) -> Iterable[Requirement]:
        dependencies: list[Requirement] = []
        missing: set[str] = set()
        for raw in candidate.requires_dist:
            try:
                requirement = Requirement(raw)
            except InvalidRequirement as exc:
                raise ResolverInputError("candidate has invalid Requires-Dist metadata") from exc
            if requirement.url is not None:
                raise ResolverInputError("candidate uses a forbidden direct URL dependency")
            if not _marker_applies(requirement, self.environment, candidate.extras):
                continue
            name = canonicalize_name(requirement.name)
            if name not in self.catalog:
                missing.add(name)
            dependencies.append(requirement)
        if missing:
            raise MissingProjects(missing)
        return dependencies

    def _candidate(
        self,
        name: str,
        row: Mapping[str, Any],
        extras: frozenset[str],
    ) -> Candidate | None:
        try:
            _only_keys(
                row,
                {
                    "name", "version", "filename", "url", "sha256", "sizeBytes",
                    "requiresPython", "yanked", "requiresDist",
                },
                "candidate",
            )
            row_name = canonicalize_name(_string(row.get("name"), "candidate name", 128))
            version_text = _string(row.get("version"), "candidate version", 128)
            filename = _string(row.get("filename"), "wheel filename", 256)
            url = _string(row.get("url"), "wheel URL", 2048)
            sha256 = _string(row.get("sha256"), "wheel digest", 64)
            size_bytes = row.get("sizeBytes")
            requires_python = row.get("requiresPython")
            yanked = row.get("yanked")
            requires_dist = row.get("requiresDist")
            if row_name != name or not re.fullmatch(r"[a-f0-9]{64}", sha256):
                return None
            if not isinstance(size_bytes, int) or size_bytes < 1 or size_bytes > 64 * 1024 * 1024:
                return None
            if requires_python is not None and (
                not isinstance(requires_python, str) or len(requires_python.encode("utf-8")) > 512
            ):
                return None
            if not isinstance(yanked, bool) or not isinstance(requires_dist, list):
                return None
            if len(requires_dist) > MAX_DEPENDENCIES_PER_CANDIDATE or not all(
                isinstance(item, str) and 0 < len(item.encode("utf-8")) <= 2048
                for item in requires_dist
            ):
                return None
            version = Version(version_text)
            if not _valid_tags(filename, name, version_text):
                return None
            if requires_python is not None:
                specifier = SpecifierSet(requires_python)
                if not specifier.contains(Version(self.environment["python_full_version"]), prereleases=True):
                    return None
            for dependency in requires_dist:
                if Requirement(dependency).url is not None:
                    return None
            return Candidate(
                name=name,
                version=version,
                version_text=version_text,
                filename=filename,
                url=url,
                sha256=sha256,
                size_bytes=size_bytes,
                requires_python=requires_python,
                yanked=yanked,
                requires_dist=tuple(requires_dist),
                extras=extras,
            )
        except (InvalidRequirement, InvalidSpecifier, InvalidVersion, ResolverInputError, ValueError):
            return None

    @staticmethod
    def _wheel_rank(filename: str) -> tuple[int, str]:
        python_tag = filename.removesuffix(".whl").rsplit("-", 3)[-3]
        tags = set(python_tag.split("."))
        rank = 2 if "cp314" in tags else 1 if "py314" in tags else 0
        return (rank, filename)


def resolve() -> dict[str, Any]:
    try:
        request = _payload("request.json")
        catalog_input = _payload("catalog.json")
        if not isinstance(request, dict):
            raise ResolverInputError("request payload must be an object")
        _only_keys(request, {"schemaVersion", "pluginId", "requirements", "target"}, "request")
        if request.get("schemaVersion") != SCHEMA_VERSION:
            raise ResolverInputError("unsupported resolver schema")
        plugin_id = _string(request.get("pluginId"), "plugin id", 128)
        target = request.get("target")
        if not isinstance(target, dict):
            raise ResolverInputError("target must be an object")
        environment = _environment(target)
        catalog = _catalog(catalog_input)
    except (ResolverInputError, json.JSONDecodeError, OSError, TypeError, ValueError):
        return {
            "schemaVersion": SCHEMA_VERSION,
            "status": "failed",
            "errorCode": "worker_protocol_error",
        }
    try:
        roots = _root_requirements(request.get("requirements"), environment)
    except (ResolverInputError, InvalidRequirement, ValueError):
        return {
            "schemaVersion": SCHEMA_VERSION,
            "status": "failed",
            "errorCode": "invalid_requirement",
        }
    try:
        result = Resolver(Provider(catalog, environment), BaseReporter()).resolve(
            roots,
            max_rounds=MAX_ROUNDS,
        )
    except MissingProjects as missing:
        return {
            "schemaVersion": SCHEMA_VERSION,
            "status": "needs_projects",
            "projects": list(missing.names),
        }
    except ResolutionTooDeep:
        return {
            "schemaVersion": SCHEMA_VERSION,
            "status": "failed",
            "errorCode": "resolution_too_complex",
        }
    except ResolutionImpossible:
        return {
            "schemaVersion": SCHEMA_VERSION,
            "status": "failed",
            "errorCode": "dependency_conflict",
        }
    selected = [
        {
            "name": candidate.name,
            "version": candidate.version_text,
            "filename": candidate.filename,
        }
        for candidate in result.mapping.values()
    ]
    selected.sort(key=lambda item: (item["name"], item["version"], item["filename"]))
    return {
        "schemaVersion": SCHEMA_VERSION,
        "status": "resolved",
        "pluginId": plugin_id,
        "selected": selected,
    }
