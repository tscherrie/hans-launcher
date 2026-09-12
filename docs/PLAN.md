# Hans delivery plan

Status: approved working plan, 23 August 2026.

## Outcome

Deliver a generic, root-free Hans launcher that a normal Android user can
install on a phone only a few years old. It must run Codex locally as the
orchestrator, preserve login across updates and provide chat, voice, plugins,
notifications, phone tools, app control and automations. Hans ships one
permanently root-free Standard product, its beginner-facing installer plugin
and a public ChatGPT Site. There is no root edition or privileged companion.

## Platform decision

Hans Standard uses `minSdk 31` (Android 12) and `targetSdk 36` (Android 16).

Android 12 is the deliberate floor:

- It covers devices released roughly five years before this plan while keeping
  modern foreground-service, security and UI behavior as the baseline.
- It avoids maintaining pre-Android-12 background execution and permission
  branches for devices unlikely to offer a good Hans experience.
- Android 13 still requires explicit notification permission and can gate
  sideloaded Accessibility under restricted settings.
- Android 14 adds foreground-service types and stricter while-in-use access for
  microphone, camera and location.
- Android 15 adds Private Space launcher duties and 16 KiB page-size readiness.
- Android 16 applies job quotas to work started from foreground services.

The app targets API 36 now so the implementation is built against the current
behavior contract and the 2026 Play target requirement. Direct APK distribution
is the primary release because open-ended LLM-controlled Accessibility is not
compatible with Google Play's current Accessibility policy. A narrower Play
variant is a later packaging task, not a limitation of Hans Standard Direct.

Primary references:

- <https://developer.android.com/about/versions/12/behavior-changes-12>
- <https://developer.android.com/about/versions/13/behavior-changes-13>
- <https://developer.android.com/about/versions/14/behavior-changes-14>
- <https://developer.android.com/about/versions/15/behavior-changes-all>
- <https://developer.android.com/about/versions/16/behavior-changes-all>
- <https://developer.android.com/google/play/requirements/target-sdk>
- <https://support.google.com/googleplay/android-developer/answer/10964491>

## Execution phases and gates

### 1. Clean baseline and executable gate

- Establish this repository, pinned toolchain, dependency verification and CI.
- Package a harmless ARM64 executable in the APK and prove it can run from the
  signed native-library area on stock Android 14.
- Pin the official ARM64 Linux-musl Codex artifact already proven to initialize
  on stock Android 14, start it in a separate app process and communicate over
  private stdio/Binder.
- Prove ChatGPT device-code login, account persistence, one streamed thread and
  a packaged tool invocation.
- Kill the runtime, restart the phone and install an update over the APK; verify
  thread recovery and retained login.

Gate: the direct `/data/local/tmp` probe has passed. No product feature work is
considered complete until the same checksum-pinned artifact runs from the signed
APK on the stock MP01. If that subprocess route is untenable, build for Bionic
or host the App Server core through Rust/JNI. PRoot is permitted only as a
documented prototype fallback, never silently promoted to the standard
architecture.

### 2. Runtime and conversation core

- Versioned Binder/AIDL protocol, event journal and bounded payload transport.
- App-private `CODEX_HOME`, workspace and credential storage.
- Account checks at launch, resume and periodically; login screen whenever the
  account is missing or refresh fails, not only during onboarding.
- Thread creation/resume, streamed items, tool status, approvals, stop and
  correlation recovery.
- Distinguish a running local runtime from a usable internet connection. An
  event-driven connectivity status must explain offline, restricted or
  unvalidated connectivity without pretending to know whether roaming is off.
  Preserve unsent text/voice drafts, report connection loss during active work
  and offer explicit retry. Reconnection must not replay an uncertain turn.
- App Server `model/list` as source of truth. Every `turn/start` carries the
  selected model and effort. If either changes during an active turn, the next
  input starts a new configured turn rather than falsely using `turn/steer`.
- Defaults: Luna with Max reasoning. MP01 Sym shortcut toggles Luna/Max and
  Sol/Ultra when the key is observable.

Gate: instrumentation captures the effective model and effort received by the
App Server for both presets, including a change during an active turn. Offline,
mobile-signal-without-data, captive-network, mid-turn-loss and recovery tests
must produce honest feedback without losing input or duplicating dispatch.

### 3. Event-driven launcher UI

- HOME role, app drawer fallback and portrait-first phone layout.
- System-following light/dark theme, high-legibility E-Ink palette and no
  global color inversion.
- Chat-first home screen. Plugins live behind one button in a dedicated view.
- Plugin list refreshes from App Server/filesystem events after install,
  uninstall or creation and appears within two seconds.
- Composer accepts Enter to send and Shift+Enter for newline, grows until
  available height and then scrolls internally.
- No on-screen send or voice button. Hardware/assistant gesture is the primary
  voice trigger, with generic accessibility/quick-settings fallbacks.
- Camera icon opens the system camera for a new capture; it never opens the
  gallery or photo picker. Captured images are attached directly. Videos are
  converted locally into metadata, audio transcript and representative frames
  before being sent to Codex.
- Auto-scroll to newest item, ephemeral voice-sent notice and Home closes
  settings. Home cursor and working indicator are static on E-Ink; on ordinary
  displays they may animate while visible and foreground, respecting Android's
  animations-off setting and battery saver. Active Live recording feedback is
  distinct from idle-home motion.
- The right-side menu keeps Apps and Plugins as direct destinations and
  presents settings as concise category entries. Setup is available only as
  `Setup starten` inside `Einrichtung & Gedächtnis`, because selecting it starts
  the setup conversation immediately. Opening a category composes only its own
  controls; Back returns to the overview before closing the menu.
- Assistant Markdown is rendered with native, theme-aware Android text. User
  input remains literal. Safe HTTP(S) links display and speak a readable label,
  remain tappable and never require a WebView or background URL fetch.
- A device-local Automatic / E-Ink / Normal display preference reports the
  effective behavior. Automatic recognizes the known MP01 identity; unknown
  devices default to Normal and other E-Ink devices can use the explicit
  override. This hardware preference is excluded from backup and transfer.
- Apps search receives focus on entry and retains native editing/cursor
  behavior; loading or query updates must not reclaim focus from another control.

Energy gate: on E-Ink, after content settles, the launcher produces no continuous
frame stream and no repeating Compose invalidation. First physical typing must
still work without an idle blinking cursor. Ordinary-display motion must stop
when the home content is covered, backgrounded or system animations are disabled.
Tests cover automatic detection, both overrides and lifecycle/settings changes.

Conversation-continuity gate: after actual process recreation and a
same-publisher update, the bounded recent visible User/Agent transcript is
rehydrated before READY without tool-output flooding, duplicate live events,
retry/dispatch state or replayed TTS. Resuming only the invisible model thread
does not satisfy this UI gate.

Owner checkpoint (29 August 2026, V17): the fully gated and publisher-signed
version 17 / `0.1.0-rc.16` was installed on the stock MP01 through the ordinary
same-publisher update path. One synthetic Hans item created under V16 appeared
exactly once in the Hans role after the update, and the empty-history placeholder
was absent. UID, data-directory and first-install projections, publisher, Home
role, Accessibility, notification-listener state and all eleven previously
captured user grants remained intact. This is bounded predecessor-agent recovery
evidence, not yet the complete continuity gate: the owner requested immediate
installation before the prepared reboot/audio run could prove a V17-created
User/Hans pair across a normal reboot and zero startup TTS. V16 user text without
a V17 visible-input receipt is intentionally not reconstructed. Installer,
generic-device and publication gates remain open.

### 4. Voice

- Independent microphone foreground service and recording state machine.
- One-hour recording limit with incremental background transcription; final
  transcript is dispatched only after the complete recording is finalized.
- A new text or voice input defaults to steer/interruption and may be recorded
  while a prior turn is still running.
- App control, foreground app changes, TTS and incoming runtime events must not
  stop a recording.
- Ordered streaming TTS with complete message boundaries, three-message queue
  cap and continued playback while another app is foreground.
- Realtime full-duplex voice presented as one continuous telephone call. The
  dedicated call surface contains Hans's avatar, microphone mute and hang-up,
  but no speaker or listening toggle. Live starts only after the built-in
  earpiece route is both available and observed as effective; route loss fails
  closed instead of leaking audio through the loudspeaker. Barge-in remains
  active while the microphone is unmuted. A local `end_live_call` tool lets Hans
  finish a clear farewell and closes after the final audio drains, with a
  bounded fail-safe if the transport loses its last completion event.
- Live receives the bounded recent conversation and task summary at call start.
  The selected speech voice is mapped explicitly to a supported Realtime voice
  on every new call; any non-identical fallback is represented honestly rather
  than implying that two different voice catalogs are equivalent.
- Defaults: Fable, 1.25x, read every visible agent message. Setting allows
  final-only versus all messages, voice and speed.

Gate: latency trace measures capture, STT finalization, first model event,
first TTS byte and first audible sample. Dictation/TTS regression tests cover
the selected normal output route and app switching. Live Voice separately
proves earpiece-only routing, route loss, repeated barge-in, mute/unmute and both
hang-up paths on API 31–36 and the physical MP01.

### 5. Root-free Android capability broker

- LauncherApps and public intents for deterministic app actions.
- User-granted contacts, calendar, media, camera, location and sensors.
- NotificationListener records every posted notification as a structured,
  deduplicated local event. Main Hans and automations can query the inbox and
  use the normal writable Codex long-term memory. The isolated relevance
  classifier is memory-aware through a bounded, read-only projection of confirmed
  profile facts and relevant memory hints; its own memory writes and general
  retrieval tools stay disabled so untrusted notification text cannot poison memory.
  Only validated important output is announced, without flooding every turn's
  token context.
- Quiet but useful notification claims have a separate durable Android-owned
  archive; announcement importance does not decide whether an eligible claim
  can be retained. Source validation, bounded retrieval, privacy recovery,
  cancellation and the distinction from native Codex memory follow
  [ADR 0006](adr/0006-durable-notification-claims.md). This unpublished addition
  is not considered accepted until its new host, Android and device gates pass.
- Notification reply actions are used when apps expose RemoteInput.
- Accessibility semantic tree first; screenshots and gestures only as fallback;
  all actions have observations, idempotency keys and verified postconditions.
- The owner-authorized full-access mode removes additional Hans/Codex
  per-action confirmation dialogs, including for requested sensitive actions.
  It does not grant Android permissions or treat notification/UI/web content
  as authorization. Setup reports its actual mode explicitly; the narrower
  confirmation mode remains distinct from the full-access policy.
- Optional assistant role, accessibility shortcut, quick-settings tile,
  persistent notification and media/headset trigger fallbacks.

Gate: clean-phone permission onboarding and deterministic WhatsApp/Gmail-style
scenarios on MP01, Pixel and Samsung. Secure windows must fail safely.

### 6. Generic and MP01 input/device adapters

- Onboarding records only hardware keys actually delivered to the app or
  Accessibility service, tests press/release/long press and explains fallbacks.
- Generic action key invokes dictation. Key mapping is editable.
- MP01 adapter maps the known side key and, when observable, Sym to the model
  preset toggle.
- Alt handling is fixed inside Hans' composer. A Hans IME is evaluated for
  global physical-keyboard symbols; no claim is made that a launcher can patch
  another app's keyboard map without root.
- MP01 display adapter detects vendor capabilities instead of assuming them.
  E-Ink profile and Android rendering budget remain separate settings.
- Balanced, Smooth, Speed and full refresh are offered only if the stock vendor
  interface is callable without privileged access. Otherwise Hans deep-links
  to the stock control. Privileged socket/sysfs access remains unsupported.
- Generic phones are never globally forced to 20/30 fps.

Gate: device capability report must accurately distinguish available,
permission-required and unsupported privileged functions.

### 7. Automations

- Transactional SQLite tables for definitions, runs, leases, inbox and recovery checkpoints.
- RFC 5545 RRULE parser, timezone-aware next-run calculation and DST tests.
- Unique `(automationId, scheduledAt)` idempotency key, atomic lease/heartbeat,
  retry/backoff and configurable missed-run policy.
- AlarmManager only for user-visible exact schedules when special access is
  granted; WorkManager/JobScheduler for normal reliable work.
- Reschedule on boot, timezone and clock change.
- Route startup, definition changes, connectivity recovery and Run now through
  the tracked JobScheduler lifecycle; no process-local fire-and-forget cycle.
- Use a configurable eight-minute monotonic cycle ceiling with checkpoint and
  reschedule. This is only a cleanup safety margin: Android 16 job quota is
  cumulative, bucket-dependent and never guarantees eight minutes of runtime.
- Record public JobScheduler stop reasons and, on API 36+, bounded pending-
  reason history. A stop before Codex dispatch may defer without consuming a
  retry; an unknown outcome after turn dispatch is held for explicit review and
  is never blindly dispatched again.
- Thread-bound heartbeat and independent new-thread execution through App
  Server, with replaceable execution adapters for phone/app actions.

Gate: duplicate alarms, process death, reboot, DST transition and offline
recovery produce no double execution.

### 8. Onboarding, identity and release engineering

- Short Android permission/role onboarding plus ChatGPT login gate.
- Optional turn-by-turn Kennenlerngespräch, resumable at any time, with a local
  review-and-confirm profile before durable use.
- Runtime developer instruction describes one coherent assistant: warm,
  friendly, intelligent, slightly cheeky/ironic and proactively helpful. It
  knows it operates a phone, speaks naturally for TTS and omits raw URLs unless
  explicitly requested. It never exposes internal multi-agent handoffs.
- Signed update path, backup/export, crash recovery, privacy controls and clear
  capability status.
- Full unit, integration, instrumentation, static, dependency, security,
  accessibility, energy and real-device regression suite.

Gate: a nontechnical tester installs on a clean supported phone, grants only
explained permissions, logs in, sends text/voice/media, controls an app,
installs a plugin, survives an update and can revoke every optional capability.

### 9. Beginner installation and on-device handoff

- `hans-android-setup` speaks to a beginner, discovers but never silently trusts
  local Android support tools, identifies exactly one attached phone, verifies
  API/ABI and the signed Standard artifact, and explains every required action
  in plain language.
- The computer-side flow makes no account, profile or permission decision for
  the user. It installs or updates Hans, verifies the exact installed package
  and then sends a versioned, authenticated setup handoff to the app.
- The app durably acknowledges the handoff before the plugin calls it
  successful. Opening Hans is a separate step, so a failed foreground launch
  cannot lose the setup request.
- The device resumes the same setup state machine: ChatGPT login when needed,
  Android roles and permissions with permanent choices where Android permits
  them, action-key selection, voice preferences and the optional turn-by-turn
  Kennenlerngespräch.
- Updates preserve app data and do not restart completed onboarding. Recovery
  and the explicit `Setup starten` action are idempotent and never interrupt an
  active Codex turn.

Gate: a first-time Android user can follow the plugin without entering shell
commands or copying opaque tokens, and the verified computer receipt correlates
to the setup request accepted by the phone. A clean install, update, interrupted
handoff and manual recovery all converge on one on-device setup state.

### 10. Standard release engineering

- The `standard-v2` profile contains exactly the signed Standard APK and the
  `hans-android-setup` plugin. Legacy dual-product descriptors and any retired
  privileged-product artifacts are rejected rather than merely hidden in the UI.
- Reproducibility is proved by two clean builds and bound cryptographically to
  the source revision, release descriptor and exact selected artifacts before
  the release manifest can be sealed.
- The plugin includes verified install, update, recovery and uninstall paths.
  Missing Android support tools lead to a visible, signed or officially sourced
  bootstrap step; the plugin never silently downloads or executes an
  unverified binary.
- ChatGPT web can explain and hand off; physical USB execution requires a local
  Codex-capable surface and the user's explicit Android debugging consent.

Gate: plugin-driven installation succeeds on a clean MP01 and at least one
clean generic supported physical phone, leaves a verifiable receipt and
preserves login/data across an update. Cancellation, tool drift and unsupported
devices fail safely before mutation.

### 11. ChatGPT Site (last)

- Requirements, permissions/privacy explanation, signed Standard download and
  checksum, beginner plugin installation link, source/docs, recovery, FAQ and
  known limitations and an explicit statement that Hans never uses root.
- Publish only sealed artifacts from the preceding gates. The Site must never
  advertise root-free access that Android cannot provide.

Gate: links, downloads and checksums resolve to the exact tested release; mobile
and desktop rendering and installer handoff are verified after publication.

## Proposed extension: prefix commands

Status: planning requested on 27 August 2026; not implemented or release-ready.
The detailed design and primary sources are in
[ADR 0005](adr/0005-prefix-commands.md).

Match the six demonstrated commands without changing their meanings:

| Prefix | Action | Preferred Android integration |
| --- | --- | --- |
| `@` | Send a message to a selected contact | User-selected messaging app/channel; distinguish sent from merely prepared. |
| `+` | Start a timer, e.g. `+5` for five minutes | Compatible Clock app, such as Google Clock. |
| `-` | Add a task | Google Tasks or an explicitly selected task provider. |
| `#` | Call a selected contact | System calling/dialer contract and the user's chosen calling mode. |
| `*` | Add a calendar event | Selected writable calendar or a verified calendar-app workflow. |
| `!` | Save a note | Google Keep or an explicitly selected notes app. |

Use a local parser and typed capability adapters rather than sending every
simple command through Codex. A static preview identifies the action and
destination; Enter authorizes the reviewed action. Preserve ordinary chat,
markdown, negative numbers and structured Codex mentions through collision
rules and a clear literal-text escape.

Missing apps, unavailable accounts and denied permissions are different states.
Keep the draft, explain the missing prerequisite, offer a compatible installed
app or an official installation handoff, and re-probe on actual lifecycle/package
events. After installation or a delayed return, require explicit resume of the
preserved action; never automatically send, call or start an old timer.
Do not silently replace Google storage with Hans-local storage.

Implementation order: parser/preview; durable pending state and authorization;
public timer/call/calendar/message adapters; Tasks/notes providers and verified
semantic fallbacks; first-use/install recovery; API 31-36 and physical-device
acceptance. Local commands remain usable without internet when their own
provider supports it. Network-dependent actions show the offline state and keep
their pending input. Success always requires operation-specific evidence, not
just an application opening.

## Release order

1. MP01 technical gate build.
2. Root-free Standard alpha and user test checkpoint.
3. Generic-device beta.
4. Beginner installer plugin plus seamless on-device setup acceptance.
5. Reproducible sealed `standard-v2` release.
6. Public Site and shared Standard release.
