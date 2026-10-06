# Building Hans from source

This guide covers a developer build of the root-free Hans Standard app. It does
not authorize a public APK release, install an operating system, unlock a
bootloader, grant Android permissions, or provide the maintainer's signing keys.

## Requirements

- A macOS or Linux development host with a POSIX shell. The native staging
  scripts currently expect the NDK's POSIX compiler tools; native Windows and
  Linux ARM64 build hosts are not documented as verified build configurations.
- JDK 17, with `JAVA_HOME` pointing to that installation.
- Python 3.12 or newer, CMake 3.22 or newer, and a CMake-supported build tool
  such as Make or Ninja.
- Git, `curl`, `jq`, `tar`, and `sha256sum` or `shasum`.
- Android SDK command-line tools, Platform Tools, Android platform 36,
  Build Tools 36.0.0, and **NDK 29.0.14206865**.
- Network access for the first dependency download and sufficient free space
  for the SDK, NDK, Gradle cache, native runtimes, and build outputs.
- For device testing: an ARM64 Android 12–16 development device with USB
  debugging explicitly enabled and authorized. Prefer a spare phone/emulator.

The Gradle wrapper pins Gradle 8.13 and its SHA-256. Android Gradle Plugin
8.13.2, Kotlin 2.2.21, dependency lockfiles, and artifact verification metadata
are part of the source. Do not bypass verification or regenerate locks merely
to make a mismatch disappear.

## Prepare the checkout and toolchain

Use the published `tscherrie/hans-launcher` source repository or an explicitly
reviewed source export. The historical `tscherrie/openclaw-os` AOSP repository
is a different project and is not the build input for this app.

```sh
git clone https://github.com/tscherrie/hans-launcher.git
cd hans-launcher
```

Set your actual SDK and JDK paths; the examples below use placeholders on
purpose. Do not copy the maintainer's machine-local paths or credentials.

```sh
export JAVA_HOME="/absolute/path/to/jdk-17"
export ANDROID_HOME="/absolute/path/to/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
export HANS_PYTHON="$(command -v python3)"
export HANS_CMAKE="$(command -v cmake)"

java -version
"$HANS_PYTHON" --version
"$HANS_CMAKE" --version
sdkmanager "platform-tools" "platforms;android-36" \
  "build-tools;36.0.0" "ndk;29.0.14206865"
sdkmanager --licenses
```

Read and accept Android SDK licenses yourself. No Hans account or speech API
credential belongs in Gradle properties, source files, environment examples,
or the build output.

## Prepare the sealed dictation helper

The subscription-authenticated dictation helper is separate from the current
App Server. Its source and input locks are in `runtime/transcription/`. It
remains pinned to helper 0.1.0 / Codex login 0.155.0; it is not silently replaced
when the App Server changes. Gradle requires its verified native artifact.

For this Developer Preview, the exact helper is also a release asset. Download
it only to the development host, verify it, then let the staging script check
its source binding and ELF layout again:

```sh
mkdir -p runtime/transcription/build/artifacts/0.1.0
curl --fail --location --proto '=https' --tlsv1.2 \
  https://github.com/tscherrie/hans-launcher/releases/download/preview-2026-10-06/libcodex_transcribe.so \
  --output runtime/transcription/build/artifacts/0.1.0/libcodex_transcribe.so
python3 - <<'PY'
from pathlib import Path
import hashlib
p = Path('runtime/transcription/build/artifacts/0.1.0/libcodex_transcribe.so')
assert p.stat().st_size == 17298240, 'Helper length mismatch'
assert hashlib.sha256(p.read_bytes()).hexdigest() == 'e586e8d0e5eadc8d68db1ecf53b15dda93fc5aca0c6a41ce9b1b87df86bc06d4', 'Helper digest mismatch'
PY
runtime/scripts/package-transcription-helper.sh --stage
```

Alternatively, `runtime/scripts/package-transcription-helper.sh --build`
compiles the checked-in Rust helper against verified upstream source. This
requires Rust 1.96.0, the `aarch64-unknown-linux-musl` target, a suitable static
ARM64 musl linker and the helper's native OpenSSL build prerequisites. An
independent toolchain may produce different bytes; the artifact lock deliberately
rejects such bytes rather than claiming reproducibility. No account credentials
or owner auth files are build inputs. Android executes only the APK-packaged
read-only native library, never a downloaded writable executable.

## Build and run host tests

Run from the repository root:

```sh
./gradlew --dependency-verification=strict \
  :android:app:verifyPinnedDependencyGraph \
  :android:app:testStandardDebugUnitTest \
  :android:app:lintStandardDebug \
  :android:app:lintStandardRelease \
  :android:app:assembleStandardDebug \
  :android:app:assembleStandardRelease \
  :android:app:assembleStandardDebugAndroidTest \
  :android:app:verifyStandardStructuralBoundaries \
  :android:app:verifyStandardDebugApkCodexRuntime
```

Generated APKs:

| Purpose | Path |
| --- | --- |
| Locally signed debug app | `android/app/build/outputs/apk/standard/debug/app-standard-debug.apk` |
| Unsigned release candidate | `android/app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk` |
| Android instrumentation | `android/app/build/outputs/apk/androidTest/standard/debug/app-standard-debug-androidTest.apk` |

The release APK is intentionally unsigned. The instrumentation APK is not the
launcher. Compiling it does not mean its Android tests ran. Some test classes
exercise storage recovery, local preferences, real permissions, or online
accounts; never run the entire instrumentation package on a personal phone.
Use reviewed test classes and disposable emulators for API 31–36 coverage.

The public source contains the complete app JVM-test source and its checked-in
fixtures. The internal publisher host/release suite, signing workflow, private
acceptance evidence, installer packaging, and website are deliberately not part
of this source export and are not required for the commands above. The
[public APK promotion policy](PUBLIC-RELEASE-GATE.md) describes that separate
release boundary; its maintainer-only commands are not a runnable self-build
suite here. Do not describe a successful app build as a pass of that larger gate.

## What the build downloads

The runtime packaging tasks download the following public artifacts and verify
their pinned lengths and SHA-256 values before use:

- Codex App Server and Code Mode host **0.160.1**, official ARM64 musl release
  assets pinned in [`runtime/runtime.lock.json`](../runtime/runtime.lock.json).
- CPython **3.14.7** Android artifact and its pinned Sigstore bundle from
  python.org, described in
  [`python-runtime/python.lock.json`](../python-runtime/python.lock.json).
- The msgpack **1.2.1** source archive from PyPI, cross-compiled with the pinned
  NDK and checked against
  [`native-packages.lock.json`](../python-runtime/native-packages.lock.json).
- Maven/Google dependencies, including WebRTC, checked against the Gradle
  lockfiles and `gradle/verification-metadata.xml`.

No private artifact server, account token, maintainer build directory, or
publisher keystore is required. The new Codex download URLs were checked for
anonymous availability on 2026-10-06; Python URLs were checked on 2026-09-13.
Upstream availability can change.
The application uses upstream compiled runtimes: a local Hans build is not a
from-source rebuild of Codex, CPython, or WebRTC.

For an offline build, first populate the verified Gradle/runtime caches with an
online build, then pass `--offline`. Missing cached bytes or mismatched hashes
must fail; do not substitute another runtime or turn verification off.

## Install on a development device

Connect a spare, authorized ARM64 phone and inspect the available devices:

```sh
adb devices -l
export HANS_DEVICE_SERIAL="your-explicit-development-device-serial"
adb -s "$HANS_DEVICE_SERIAL" shell pm path ai.hans.standard
```

If Hans is not already installed, install your debug build:

```sh
adb -s "$HANS_DEVICE_SERIAL" install \
  android/app/build/outputs/apk/standard/debug/app-standard-debug.apk
adb -s "$HANS_DEVICE_SERIAL" shell am start -n ai.hans.standard/.LauncherActivity
```

Complete first-use setup, model-account login, and permission choices on the
phone. Online features depend on the account's effective runtime capabilities.
Live Voice and completed-recording dictation use the existing ChatGPT sign-in,
without a separate paid API fallback. Dictation uses an undocumented ChatGPT
backend compatibility route and does not stream preliminary text. No model or
voice entitlement is guaranteed merely by building the app.

An existing app can only be updated with an APK signed by the same developer
key and compatible version. Keep your local debug key if you need update/data
continuity. A maintainer-signed Hans install cannot be replaced with your debug
APK: `INSTALL_FAILED_UPDATE_INCOMPATIBLE` is an expected safety boundary.
**Do not uninstall, clear data, force-stop, or change permissions as an automatic
workaround.** Use another development device or plan an explicit backup and
migration. A self-built debug install is not an official publisher update.

## Voice controls and desktop access

Tap the action key (or the microphone when no physical key is configured) to
start dictation, then tap again to finish and submit the recording. Text appears
after transcription completes. Tap the Hans header for the separate Live Voice
telephone mode. Microphone permission and effective ChatGPT-account support are
required; completing setup does not prove an actual audio round trip.

Incoming desktop remote access is currently **disabled and hidden** by product
decision. Historical pairing code remains in the source, but it is not an
enabled feature of this preview. Do not use old pairing instructions or bypass
the disabled state. This is distinct from outgoing advanced-work features.

## Reproducibility and release status

There are three different claims:

1. **Buildable source:** an independently created source checkout can build the
   documented app without private inputs.
2. **Pinned payloads:** packaged upstream runtime bytes match their locks.
3. **Reproducible release:** two clean independent builds produce byte-identical
   selected artifacts and are bound to a reviewed source revision.

Pins and a successful development build support the second claim, not
automatically the first or third. Do not claim clean-export or byte-for-byte
reproducibility until that exact source revision has the corresponding evidence.
Local signing keys and native build-path/toolchain differences can affect bytes.

Public APK distribution remains separate. The full current-runtime
transitive/native licensing inventory and independent byte-identical reproduction
are still open for Codex 0.160.1. The established notice checker does not clear
the new runtime by silently changing its older reviewed pin. This artifact is
published only as an explicitly owner-approved **Developer Preview**, not Stable.
Supplemental original-source notices describe added dependencies and remaining
provenance gaps; they do not claim complete native closure. A stable release
additionally requires current third-party review,
independent reproducible builds, a sealed signed manifest and immutable
artifacts, and the clean-device installation/update acceptance described in
[the public promotion gate](PUBLIC-RELEASE-GATE.md).

Historical remote/cross-device tests are not evidence that the currently
disabled incoming feature is available. A passing unit test is not a substitute
for live device, account, audio or third-party app acceptance.
