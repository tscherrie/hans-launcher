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

- Codex App Server and Code Mode host **0.154.0**, official ARM64 musl release
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
publisher keystore is required. The Codex and Python download URLs were checked
for anonymous availability on 2026-09-13; upstream availability can change.
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
phone. Online features depend on the account's effective runtime capabilities;
voice features may additionally require a separately provisioned speech
credential or service entitlement. No model or voice is guaranteed merely by
building the app.

An existing app can only be updated with an APK signed by the same developer
key and compatible version. Keep your local debug key if you need update/data
continuity. A maintainer-signed Hans install cannot be replaced with your debug
APK: `INSTALL_FAILED_UPDATE_INCOMPATIBLE` is an expected safety boundary.
**Do not uninstall, clear data, force-stop, or change permissions as an automatic
workaround.** Use another development device or plan an explicit backup and
migration. A self-built debug install is not an official publisher update.

## Try incoming desktop remote access (experimental)

This is the incoming direction: **Desktop Codex / ChatGPT Work → your own
Android phone and its existing Hans conversation**. It is intended to extend a
compatible desktop session with the Android app-control tools that you have
allowed on the phone. It is not the outgoing **"Erweiterte Arbeit"** feature.

The following phone labels and protocol calls are verified against the source,
not a completed live desktop pairing. At this revision there is **no confirmed
paired desktop-to-phone end-to-end execution**. The account, desktop version,
service policy, and embedded runtime must actually support remote access; a
successful build or a displayed menu does not prove that availability.

1. Sign in to Hans, create or open its existing conversation, and open
   **"Fernzugriff durch ChatGPT Desktop"** in the phone's menu. Check the
   capability/status message; use **"Status aktualisieren"** when needed.
   If access is unavailable or policy-blocked, do not bypass that result.
2. Read the consent text, choose **"Fernzugriff freigeben"**, and confirm
   **"Jetzt freigeben"** on the phone. Wait for runtime-confirmed connection
   state; merely pressing the button is not success.
3. Choose **"Kopplungscode erstellen"**. Give the temporary code only to your
   own compatible desktop pairing flow. The code is not stored by Hans and
   disappears when it expires. Do not paste it into issues, logs, shared chats,
   or documentation.
4. Complete the pairing through the controls actually available in your
   Desktop Codex / ChatGPT Work version. No particular Mac button or menu path
   is prescribed here because that desktop flow has not been verified. If it
   is not offered, stop at this manual desktop step rather than treating the
   devices as paired.
5. On the phone, use **"Kopplung prüfen"** and
   **"Gekoppelte Geräte aktualisieren"** to check the runtime-confirmed result.
   Continue the conversation identified under **"Vorhandenes Hans-Gespräch"**
   from the desktop. New tasks created on the desktop do not receive the phone
   tools in this version. Verify one harmless, explicitly authorized Android
   action and its postcondition before claiming end-to-end control works.
6. End access with **"Fernzugriff ausschalten"**. To remove a desktop's
   enrollment, choose **"Kopplung widerrufen"** for that device and wait for
   the runtime-confirmed revocation result. An unconfirmed or timed-out RPC is
   not proof of successful remote revocation.

The implementation uses the pinned App Server 0.154.0 `remoteControl` protocol:
status reads/notifications, ephemeral enable/disable, manual-code pairing,
pairing-status checks, and client listing/revocation. It capability-probes the
running server instead of assuming that these calls are usable for every
account. Pairing material and local access consent are transient; consent is
not restored after Hans/the runtime restarts or exits. A retained client
enrollment is not permission to resume phone-tool access without fresh local
consent.

This permission is sensitive: the authorized desktop can reach private
Hans/Codex files within Hans's Android application UID as well as the granted
Android tools. It is not limited to public Downloads. Android permissions and
action confirmations still apply, and it does not grant other apps' private
data, root access, or the ability to unlock the device. The source build keeps
this feature opt-in; it does not silently enable access or pair a desktop.

Reachability depends on network, Android lifecycle/foreground-service limits,
and the effective account/runtime state. There is no guaranteed always-on or
24/7 connection. Unit tests and API-level lifecycle tests are useful evidence,
but do not replace the still-required real desktop pairing and Android-action
acceptance test.

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

Public APK distribution remains separate. The current third-party foundation
targets an older Codex 0.151.0 release; it does not clear the current 0.154.0
transitive/native notice obligations. Its public checker fails closed on runtime
drift. A stable release additionally requires current third-party review,
independent reproducible builds, a sealed signed manifest and immutable
artifacts, and the clean-device installation/update acceptance described in
[the public promotion gate](PUBLIC-RELEASE-GATE.md).

Remote/cross-device functionality is experimental until its pairing,
authorization, failure handling, and complete end-to-end execution are
demonstrated. A passing unit test or a visible UI control is not that proof.
