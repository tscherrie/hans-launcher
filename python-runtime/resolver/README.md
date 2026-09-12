# Hans offline Python resolver

Hans resolves plugin dependencies without `pip`, a shell, build backends, or
worker-side networking. The Android main process obtains bounded PEP 691 Simple
JSON and PEP 658 metadata from the fixed PyPI origin. It then creates a
read-only, content-addressed PYZ containing that metadata, this resolver, and
the pinned `packaging` 26.3 and `resolvelib` 1.2.1 sources.

The isolated CPython 3.14 worker performs PEP 440/508 marker, extras,
Requires-Python, prerelease, yanked-release, and backtracking semantics. It can
only return either missing normalized project names, a complete selection, or a
bounded incompatibility code. Selected wheels are downloaded and hash/size
checked in the main process, then checked again by `PythonEnvironmentStore`.

`resolver.lock.json` is the supply-chain record for the vendored resolver
libraries. The vendor tree is an unchanged extraction of those exact pure
Python wheels. Both upstream license texts are retained beside the lock.
