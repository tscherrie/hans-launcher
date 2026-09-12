# Architecture

## System boundary

```text
Android OS
  Hans Standard APK
    Launcher UI and Android services (main process)
      Binder/AIDL RuntimeClient
    Hans Runtime (:runtime process)
      Codex App Server (private stdio)
      packaged tools/interpreters
      app-private CODEX_HOME and workspace
    Capability broker
      public Android APIs
      Notification listener
      optional Accessibility computer use
      optional Assistant/VoiceInteraction service
```

Inference is remote. Orchestration, files, tools, plugins, event persistence and
Android actions are local to the phone.

## Runtime packaging

Android 10 and newer forbid target-29+ apps from executing code from writable
app storage. The Codex executable and any native helper therefore ship as
signed APK native artifacts and execute from `nativeLibraryDir`; they are not
downloaded into `filesDir` and marked executable.

The primary runtime is the checksum-pinned official Codex ARM64 Linux-musl
artifact. A real `initialize` JSON-RPC handshake has already succeeded with it
on unrooted stock Android 14. It is packaged inside the signed APK and launched
from `nativeLibraryDir`; it is never copied to writable storage. The runtime
exposes no TCP port. Stdio stays private inside `:runtime`; Binder is the only
app-facing boundary.

Android still lacks some conventional Linux facilities, notably bubblewrap.
Hans must prove the effective sandbox/tool behavior rather than treating a
startup warning as protection. If the musl child cannot meet reliability or
tool gates from the APK, the fallback order is an Android/Bionic build, then a
Rust/JNI host. A Linux container/PRoot is a fallback prototype only.

## State and recovery

- App-private, no-backup credential store and Android Keystore envelope.
- Transactional SQLite/AtomicFile persistence for UI thread mapping, event cursor, capability state,
  automations and recovery checkpoints.
- Append-only bounded event journal with monotonic sequence numbers.
- UI reconnect starts with `initialize`, `account/read`, known thread resume and
  replay after the last acknowledged sequence.
- A destructive request is never automatically resent after ambiguous failure.
- Stable package ID/signing preserves private data over normal APK upgrades.

## Dispatch contract

`RuntimeOptions` is immutable per dispatch and contains effective model ID,
reasoning effort, service tier, sandbox and approval policy.

1. UI reads choices only from App Server `model/list` plus supported effort.
2. A new request captures one options snapshot.
3. `turn/start` receives that exact snapshot.
4. `turn/steer` is used only when no unsupported option changed.
5. Otherwise Hans starts a new configured turn/thread segment.
6. Diagnostics store requested and server-confirmed values without secrets.

The UI displays effective state, not merely saved preferences.

## Capability model

Every phone tool declares:

- stable tool ID and schema;
- required Android API level;
- required runtime permission, role or special access;
- availability probe and user-facing explanation;
- whether a confirmation is required;
- root-free Standard implementation or an explicit unsupported state;
- idempotency/postcondition strategy.

Prompts are generated from the live capability registry. Hans therefore knows
what the phone can currently do without pretending ungranted or privileged access.

## Notifications

Every posted notification is normalized, deduplicated and saved locally with
app, time, category, visible text, actions and removal state. This satisfies the
requirement that Codex can know about every notification without copying an
unbounded history into every prompt.

Codex receives:

- a query tool for bounded, relevant retrieval from the private local notification inbox;
- a restricted, isolated relevance-decision workflow for optional user alert/TTS;
- only the final validated announcement in interactive turn context, never a routine digest of all
  silent events;
- reply/snooze/dismiss tools only when Android exposes those actions and the ordinary capability and
  confirmation policy permits them.

Silent events remain local episodic history. They are not injected at every turn boundary and are
not promoted to Codex memory.

Sensitive content can be excluded per app and retention can be disabled or
cleared by the user.

## Computer use

Execution order:

1. documented intent/provider/notification action;
2. semantic Accessibility node action;
3. screenshot-based coordinate gesture;
4. observe and verify postcondition;
5. stop safely or ask the user if the state is ambiguous.

Screen capture and actions are frame-correlated. Secure windows are treated as
an expected unsupported state. The recording service owns microphone lifetime,
so launching or controlling another app cannot cancel a dictation.

## Voice

Dictation, TTS and Realtime are separate state machines sharing an explicit
audio-focus coordinator. Audio capture is not owned by the launcher Activity.
Foreground changes, runtime items and computer-use screenshots cannot destroy
the recorder.

TTS uses an ordered message/segment queue. Completion of the currently playing
segment immediately releases the next segment; arrival of a later message is
never needed to unblock it. Old queued messages may be dropped only before
playback and only when the configured three-message lag cap is exceeded.

## Idle rendering

The idle home surface has no repeating animation or polling clock. Time is
refreshed only on lifecycle/system time events. The thinking indicator is
static. The composer is logically ready for physical typing but has no idle
blinking cursor; the first key event focuses it and inserts that key. The only
repeating header animation is the explicitly requested recording dot while Live
Voice is active; it leaves the composition when Live Voice stops.

The launcher is chat-first. A ready runtime adds no status suffix to `Hans`; an
unavailable runtime shows only a sleeping marker in the adjacent status slot.
Tapping the title toggles Live Voice, which reuses that exact slot for its
recording dot. Settings, Android apps, plugins and setup live in a snap-open
right panel. Opening requires a deliberate left swipe that begins at the right
edge and is horizontally dominant, so ordinary timeline scrolling remains
available. Scrim, Back, an explicit close action and a right swipe close it.

Device-specific frame-rate or E-Ink policies live behind adapters and do not
affect generic phones. The root-free MP01 display adapter additionally requires
the exact stock device identity, a trust-checked Minimal system package and a
freshly reachable abstract vendor socket before each command. It exposes only
Balanced, Smooth, Speed and full refresh. Because that socket has no readback,
a successful write is reported as `sent_unverified` until the user/device test
observes the result; it never falls back to sysfs or Root.

## Distribution and trust

The direct Standard APK is root-free but powerful. Accessibility and notification
access are opt-in, independently revocable and visible in settings. A future Play
build excludes open-ended agentic Accessibility to comply with store policy.

Standard never embeds API keys. A clean, non-root install exposes a masked local
entry/paste surface in Settings and, after explicit consent, in conversational
setup. The draft is not saved to a Bundle, backup, analytics or logs; it is
wiped on save/cancel and only the Android Keystore-backed envelope persists.
Settings exposes only availability and lets the user replace or remove the
credential. Setup and chat never ask for or repeat it. A signed APK upgrade
preserves the existing envelope; uninstalling or explicitly removing it clears
the device-local access. Realtime uses short-lived credentials where supported.

The public Standard installer plugin contains a canonical sealed-release lock
for exactly one Standard APK. That lock binds release/source/descriptor
identity, byte length, SHA-256, application/version/API/ABI evidence and the
publisher certificate already verified by the reproducible release gate. This
makes the Marketplace plugin path ADB-only: exact APK bytes need no second
`aapt2`, `apksigner` or OpenSSL pass on a beginner's computer. Unpublished local
development APKs retain those explicit checks.

When ADB is absent, the plugin can offer one fixed, digest-pinned Google
Platform-Tools archive after the user sees and accepts its source, license and
destination. It extracts into a new private staging directory, rejects links
and path traversal, and executes only the resulting absolute ADB path. Standard
setup never invokes Fastboot.

The setup handoff is an explicit ordered broadcast to a receiver protected by
Android's `DUMP` permission. The receiver validates and durably stores the
correlation before returning its exact acknowledgement; the plugin then opens
the ordinary HOME activity separately. Thus a launch failure cannot lose an
accepted request, and an ordinary third-party app cannot enqueue setup turns.
All account, Android-role, permission and profile choices continue visibly on
the phone.

Hans has no root edition. Standard never discovers or delegates to a privileged
companion. The release descriptor accepts only the Standard APK and Standard
installer plugin, and the release verifier rejects legacy dual-product metadata.
