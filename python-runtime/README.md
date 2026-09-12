# Hans embedded CPython runtime

Hans embeds upstream CPython directly. Android does not receive a global
`python3` executable: the interpreter is a signed APK library loaded by the
private `:python` service through JNI.

The runtime supply chain is deliberately split into two classes:

* `libpython`, its pinned OpenSSL/SQLite dependencies, and curated CPython
  extension modules are APK-native libraries. They remain in Android's
  package-managed `nativeLibraryDir` and are never extracted to writable app
  storage.
* Pure Python standard-library files are packed reproducibly as
  `hans/python/python314.zip`. The main-UID broker verifies the signed asset
  and passes it to the permissionless `isolatedProcess` worker as an explicit
  read-only `ParcelFileDescriptor`. JNI duplicates the descriptor, verifies
  regular-file type, seekability, exact size and SHA-256, then indexes its
  deterministic STORED members directly with `pread(2)`. A native-backed
  Python importer exposes only synthetic `hans-fd://stdlib/...` origins. No
  `/proc` path or app-private main-process path is shared, and nothing is
  extracted.
* Each immutable pure-Python environment is a deterministic `environment.pyz`
  with site-packages at its root and plugin source below
  `__hans_plugin_source__/`. It crosses the same Binder boundary as a bounded,
  read-only descriptor lease, is independently size/digest verified by JNI,
  read directly through the same native importer with synthetic
  `hans-fd://environment/...` origins, and is closed immediately after the
  result, cancellation, or failure.

`python.lock.json` pins the official PSF Android artifact, its Sigstore bundle,
the upstream tag/commit, Android ABI, NDK, core library bytes, and 16 KiB page
alignment contract. The lock is the only source of network locations.

## Local verification

```sh
python3 python-runtime/scripts/stage_runtime.py \
  --lock python-runtime/python.lock.json \
  --runtime-source python-runtime/runtime \
  --cache python-runtime/build/downloads \
  --output python-runtime/build/staged

python3 python-runtime/scripts/verify_runtime.py \
  --lock python-runtime/python.lock.json \
  --stage-root python-runtime/build/staged

python3 -m unittest discover -s python-runtime/tests -p 'test_*.py'
```

Set `HANS_PYTHON_RUNTIME_OFFLINE=1` to forbid downloads and require a verified
cached artifact. Gradle invokes the same scripts and stages only generated
output into the APK.

The native worker deliberately has no writable native-module path, no user
site, no environment-variable configuration, and no workspace filesystem
path. Workspace access remains unavailable until a separate read-only Binder
lease is present; it is never inferred from a handle string.
