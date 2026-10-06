# Hans Launcher

Hans is an agent-first Android launcher: chat, dictation, Live Voice, and
permission-gated tools for working with the phone. **Hans Standard is the only
product and runs without root.** It is an Android app, not an AOSP fork or a
replacement operating-system image.

The model runs remotely. The embedded Codex App Server, tool routing, files,
plugins, and a constrained CPython worker run in the app's Android sandbox.
An account and network access are required for online model features; building
the source does not require an account token, API key, or publisher signing key.

## Status

Developer preview for **Android 12–16 (API 31–36), ARM64**. The reference device
is the Minimal Phone MP01; device-specific conveniences are capability-probed
and do not imply equivalent behavior on every Android device.

Source availability and local build success are not a stable APK release.
Remote/cross-device workflows remain **experimental until their end-to-end
acceptance is recorded**. Do not assume that a remote host is paired, authorized,
reachable, or able to execute an operation merely because a control is shown.
There is no supported root edition and no current Play Store distribution.

## Voice and current capabilities

Sign in with your ChatGPT account. Live Voice and completed-recording dictation
use that account without a separate API key. Dictation starts on the first
action-key/microphone tap and submits the final transcript on the second tap.
The subscription transcription route is an undocumented compatibility feature;
availability may change. It has no paid API fallback or streaming preview.

Incoming desktop remote access is currently disabled and hidden. Existing
source for that experimental integration is not a promise of availability.
The embedded App Server and Code Mode host are pinned to Codex 0.160.1; model
and effort choices depend on the runtime-confirmed account catalog.

## Build it yourself

See **[Building Hans](docs/BUILDING.md)** for the pinned toolchain, complete
commands, test scope, and safe installation on a development phone.

With Java 17, Python 3.12+, CMake 3.22+, and the documented Android SDK/NDK
installed, first prepare the sealed dictation helper as described in the build
guide. Then the main build command is:

```sh
./gradlew --dependency-verification=strict \
  :android:app:testStandardDebugUnitTest \
  :android:app:assembleStandardDebug
```

The first build downloads checksum-pinned third-party dependencies. The debug
APK is generated locally at
`android/app/build/outputs/apk/standard/debug/app-standard-debug.apk`.
It is signed with your development key, not the Hans publisher identity.
**Do not uninstall an existing Hans installation to work around a signing
conflict:** uninstalling removes its app data and login.

## Capabilities and boundaries

- Chat and voice use the runtime-confirmed model and account capabilities.
- Android public APIs and intents are preferred; semantic Accessibility is
  used when appropriate and visual computer use is a fallback.
- Accessibility, notifications, microphone, shared-file access, and other
  sensitive capabilities require explicit, revocable Android permissions.
- Ordinary app storage isolation still applies: shared-file access does not
  grant access to other apps' private data or Android system files.
- Actions must verify their result. Opening an app or submitting a request is
  not by itself evidence that the intended action completed.
- Screens, transcripts, notifications, and tool results can contain sensitive
  information. Review what you authorize before using Hans with real accounts.

## Development and release

Useful entry points:

- [Architecture](docs/ARCHITECTURE.md)
- [Implementation plan](docs/PLAN.md)
- [Build and verification guide](docs/BUILDING.md)
- [Public APK promotion policy](docs/PUBLIC-RELEASE-GATE.md)

The public source includes the app-build and JVM-test inputs, not the internal
publisher signing workflow, private release evidence, installer packaging, or
website. The promotion policy describes a separate release boundary; its
maintainer-only gate commands are not a self-build prerequisite and are not
included as an executable release suite in this source export.

Contributions should preserve the root-free boundary, permission checks,
event-driven idle UI, and confirmed-runtime settings. Include a regression test
for each fixed bug and test Android behavior across API 31–36. Never submit
credentials, phone backups, private conversations, raw device logs, or signing
material in an issue or pull request.

## License

Hans-authored code and documentation are licensed under the
[Apache License 2.0](LICENSE). Third-party code and bundled components retain
their own licenses and notices; see the license assets under
`android/app/src/main/assets/hans/licenses/` and the resolver's adjacent license
files. This license does not claim ownership of third-party components or grant
trademark rights.
