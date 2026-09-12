# Hans Standard public promotion gate

The **admin-sealed external copy** of `verify-public-release-gate.sh` is the
last local, non-publishing gate before a sealed Hans Standard release may be
uploaded. The checkout copy is only source material for preparing that external
authority bundle; running it from the candidate checkout cannot produce an
authoritative pass. The gate is intentionally
separate from the normal host/release gate: a staging descriptor, incomplete
third-party inventory or dirty source tree must remain useful for development
without ever becoming publicly installable.

## External authority and owner approval

Before a promotion attempt, the owner reviews and approves one exact outer
source revision and one exact nested-site revision. Four files are copied to a
new directory below an admin-controlled prefix such as
`/Library/Application Support/Hans Release Gate/hans-rc26-owner-approved`:

- `verify-public-release-gate.sh`
- `verify_public_release_gate.py`
- `public-release-toolchain.lock.json`
- `public-release-authority.json`

The strict authority document has this contract:

```json
{
  "approvalScope": "public-stable-standard-promotion",
  "approvedSiteSourceRevision": "<exact 40-64 hex site revision>",
  "approvedSourceRevision": "<exact 40-64 hex outer revision>",
  "gateSha256": "<sha256 of verify_public_release_gate.py>",
  "policyId": "hans-public-release-gate-v1",
  "schemaVersion": 1,
  "toolchainLockSha256": "<sha256 of public-release-toolchain.lock.json>",
  "wrapperSha256": "<sha256 of verify-public-release-gate.sh>"
}
```

The owner then makes the directory tree root/admin-owned and removes every
group/other write bit. Every parent directory must have the same protected
ownership boundary. The gate accepts exactly those four files, verifies their
hashes against the protected policy, and requires the command-line revisions to
equal the owner-approved pair. Root/admin compromise is outside this local
gate's threat model. A policy stored only in the candidate checkout,
caller-provided hashes, or output from the checkout wrapper is not owner
approval. Invoke the wrapper **directly from the protected authority directory**
and pass the same directory as `--authority-root`.

Sealing is an explicit owner action, not part of the candidate build. Freeze and
record both clean revisions, copy the three gate files into a fresh external
directory, calculate their SHA-256 values, write the authority JSON, and review
the revision pair and hashes. Then use admin authority to set the directory and
all four files to `root:wheel`, make only the wrapper executable, and remove
group/other writes (`0755` for the directory and wrapper, `0444` for the other
files is sufficient). Recalculate the hashes after copying and before sealing.
Any source or policy change requires a new approval directory and a new owner
review; never edit a previously approved bundle in place.

The gate accepts two explicit, independent, clean Git revisions: the outer
`clawdroid` repository and the nested `site/web` repository. Both roots must be
absolute, the site root must be exactly `<outer>/site/web`, and both revisions
are cloned independently into a private disposable workspace. It also requires
an external sealed bundle
containing exactly these five regular files:

- `metadata-public-key.pem`
- `manifest.json`
- `manifest.json.sig`
- `hans-standard.apk`
- `hans-android-setup.zip`

The bundle directory and all five files must have no user, group or other write
bits. This does not replace the signed hash contract; it prevents the ordinary
release workflow from accidentally modifying evidence while the gate is
running. The bundle is opened without following links and copied once, by file
descriptor, into private staging. Every later verifier and build consumes only
those captured bytes, so replacing the original path cannot change the result.
At the end the gate rechecks both byte length and SHA-256 of every staged copy,
then independently captures the external release bundle again and compares all
evidence. Mutation of either copy fails closed.

It requires a `standard-v2` stable descriptor, public Standard APK and installer
plugin, and the fixed update URL
`https://hans-android.yearemia.chatgpt.site/releases/current.json`. It runs the
canonical third-party checker with `--require-public-ready`, strictly parses its
JSON, and binds its `releaseId` and `apkSha256` to the signed release and captured
APK. Gate-owned code verifies the detached signature with the exact captured
OpenSSL runtime before any candidate-revision Python or JavaScript executes,
then requires both a modified manifest and a modified signature to fail. The
candidate `verify_release_manifest.py` remains an independent cross-check, not
the authenticity root. The gate compares the exact APK/plugin byte counts and
SHA-256 values against the already authenticated manifest.
Python and Git use a deliberately narrow macOS trust boundary. The only
accepted entry points are `/usr/bin/python3` and `/usr/bin/git`; Homebrew,
pyenv and other user-controlled paths are rejected before they can execute.
The gate verifies each entry point against Apple's
code-signing anchor, requires root ownership with no group/world writes, and
runs it under a minimal environment that cannot inherit `DYLD_*`, `PYTHON*` or
caller Git configuration. It additionally requires the Apple
Command Line Tools Python and Git resolutions, recursively rejects any
non-root-owned or group/world-writable node in the CLT Python runtime and Git
helper tree, and checks the resolved executables' Apple signatures. The two
entry-point SHA-256 values remain evidence pins, but are not misrepresented as
complete runtime-closure hashes.

OpenSSL is deliberately not taken from `/usr/bin/openssl`: on supported macOS
hosts that path is LibreSSL and cannot perform the Ed25519 `pkeyutl -rawin`
operation used by the Hans manifest contract. Instead, `--openssl-root` must
name a complete relocatable OpenSSL 3 runtime and `--openssl` must equal
`<openssl-root>/bin/openssl`. The complete root and executable are captured and
hash-pinned before use. The staged binary must load only Apple system dylibs,
runs with configuration and provider-module discovery confined to the private
gate workspace, and must pass an RFC 8032 Ed25519 known-answer verification.
The gate then corrupts the signature and requires the same binary to reject it
before any release-repository script may run. A `no-shared` build from an
official OpenSSL source release is the intended runtime.
Its approved source archive SHA-256, build configuration, exact version,
executable SHA-256 and captured-tree SHA-256 are independently fixed in the
externally anchored `public-release-toolchain.lock.json`. The authority policy
pins that complete lock file, so a command line cannot self-approve another
binary.

This boundary protects against an unprivileged account changing code or dylibs
between pinning and execution. Compromise of root/admin authority, Apple's code
signing trust or the operating system itself is explicitly outside this local
gate's threat model. A copied `/usr/bin` xcrun shim is not treated as a
relocatable executable: the original protected entry point is executed after
validation. On a host without the required Apple/CLT boundary the gate fails
closed; it does not fall back to Homebrew or pyenv.

Node and OpenSSL use fully captured trust paths. Node must come from a
complete relocatable runtime root (the official Node distribution is suitable),
with `--node` equal to `<node-root>/bin/node`. The whole Node root, Apple's Git
helper directory and npm's complete runtime tree are pinned and captured.
`--git-exec-path` must be exactly
`/Library/Developer/CommandLineTools/usr/libexec/git-core`, and `--npm-root` must name
`<node-root>/lib/node_modules/npm`, so Node and npm come from one distribution.
The externally protected toolchain policy fixes Node v26.0.0, npm 11.12.1, the
official Darwin ARM64 archive, the Node executable, the complete Node tree, npm
CLI and complete npm tree. It also fixes the exact site `package.json` and
`package-lock.json`; packages fetched by `npm ci` remain content-integrity-bound
by that lock. None of those hashes is accepted from the caller. OpenSSL is
tested first, and the release manifest signature plus both tamper negatives must
pass before Node or npm executes even a version command. A Homebrew launcher
copied without its adjacent libraries therefore cannot enter this gate.

Normal Git and npm installations contain relative symlinks. The gate resolves
them once and materializes their exact target bytes into private staging. npm
links must stay inside the npm tree. Git helper links may use relative targets
outside `git-core`; those target bytes and original link texts remain part of
the tree digest. Absolute, broken and non-file links fail closed. Compute the
four tree pins with the same capture contract before invoking the gate:

```bash
/usr/bin/python3 -I -S scripts/hash_public_release_tool_tree.py \
  --source /absolute/path/to/git-core --label git_exec_path \
  --allow-external-relative-symlinks --require-root-protected
/usr/bin/python3 -I -S scripts/hash_public_release_tool_tree.py \
  --source /absolute/path/to/npm-root --label npm_runtime
/usr/bin/python3 -I -S scripts/hash_public_release_tool_tree.py \
  --source /absolute/path/to/relocatable-node-root --label node_runtime
/usr/bin/python3 -I -S scripts/hash_public_release_tool_tree.py \
  --source /absolute/path/to/relocatable-openssl-root --label openssl_runtime
```

Every Git invocation receives command-line overrides for
`core.fsmonitor=false` and `core.hooksPath=/dev/null` as well as disabled
credentials, external protocols and pagers. This applies to inspection, clone,
checkout and post-build checks, so repository-local fsmonitor or hook programs
cannot execute inside the gate.

Finally, it verifies the externally pinned `package.json` and
`package-lock.json`, performs a
fresh `npm ci` with an isolated home/config/cache and a sanitized environment,
generates the ready status, and runs the existing `test:site` and `build`
commands. Mutable checkout `node_modules` is never linked. The generated status
must pass the release contract and the built
`dist/client/releases/current.json` must be byte-identical. Full post-command
Git porcelain (including index and untracked files) allows only the generated
status in the site clone and no change in the outer clone.
The tracked `site/web/public/releases/current.json` is never overwritten. The
sealed bundle is hashed before and after all checks. The gate never uploads,
deploys or publishes anything.

Example invocation:

```bash
/Library/Application\ Support/Hans\ Release\ Gate/hans-rc26-owner-approved/verify-public-release-gate.sh \
  --authority-root /Library/Application\ Support/Hans\ Release\ Gate/hans-rc26-owner-approved \
  --repository-root /absolute/path/to/clean/clawdroid \
  --source-revision 0123456789abcdef0123456789abcdef01234567 \
  --site-repository-root /absolute/path/to/clean/clawdroid/site/web \
  --site-source-revision 0123456789abcdef0123456789abcdef01234567 \
  --sealed-bundle /absolute/path/to/sealed-bundle \
  --git /usr/bin/git --git-sha256 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  --git-exec-path /Library/Developer/CommandLineTools/usr/libexec/git-core --git-exec-path-sha256 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  --python /usr/bin/python3 \
  --python-sha256 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  --openssl /absolute/path/to/openssl-root/bin/openssl \
  --openssl-root /absolute/path/to/openssl-root \
  --node /absolute/path/to/node-root/bin/node \
  --node-root /absolute/path/to/node-root \
  --npm /absolute/path/to/node-root/lib/node_modules/npm/bin/npm-cli.js \
  --npm-root /absolute/path/to/node-root/lib/node_modules/npm
```

A successful result includes `published: false`, exact captured artifact
hashes/sizes, both source revisions, all tool/runtime pins and the built status
hash. Publication remains a distinct owner action after the gate and after the
required physical-device acceptance.
