# Hans native Codex Live voice

The root-free edition uses native WebRTC for Realtime voice. Media is never
proxied through the local Codex runtime: Android captures and plays the duplex
audio stream. Codex App Server owns authenticated signaling, the sideband,
transcripts and delegation to the existing main task. Android never extracts
ChatGPT credentials. No separate API key or automatic API fallback is used for
Live calls. API dictation and standalone read-aloud remain separate.

## Pinned WebRTC artifact

- Maven coordinate: `io.github.webrtc-sdk:android-prefixed-stripped:144.7559.12`
- Published: 2026-08-12
- Wrapper source: <https://github.com/webrtc-sdk/android/releases/tag/v144.7559.12>
- WebRTC source commit: `webrtc-sdk/webrtc@0bbf3dd72c44824f20b06f22b3e0fca133556e12`
- Maven Central AAR SHA-256:
  `d945209f4f38615ee07a8d8d94b14b08cf2a0d75cd71b46d5e1574119e338ec0`
- Maven Central POM SHA-256:
  `5ed255b703963662be48c13d65897943d767ca95142bd75af3ab74acd2dbd834`

This is the prefixed build, so its Java namespace is
`livekit.org.webrtc` and cannot silently collide with another library's
`org.webrtc` classes. The stripped variant omits software video codecs and is
appropriate because Hans uses audio and the data channel only. It still adds a
large native AAR (roughly 30 MB before APK/AAB compression and ABI splitting).

The project is a maintained packaging of upstream Google WebRTC, not an Android
framework component. The wrapper is MIT licensed; upstream WebRTC is BSD-style
licensed and ships additional third-party notices. Before changing the pin:

1. compare the wrapper release with the pinned upstream WebRTC commit;
2. verify the downloaded Maven AAR checksum independently;
3. inspect the packaged license/notice files;
4. run duplex echo, interruption, Bluetooth/headset, reconnect, and background
   app-control tests on Android 12 through 16;
5. update the coordinate and checksums together in one reviewed change.

The app's `preBuild` task resolves this coordinate without transitive
dependencies and verifies the exact AAR SHA-256 above, so a repository-side
byte replacement fails before compilation. Repository-wide Gradle dependency
verification can still be added at a later release gate; the checksum above is
the human-auditable bootstrap value for that metadata.

## Pinned Codex 0.155.0 protocol

- `thread/realtime/start`: WebRTC offer, explicit `v3`, `gpt-live-1-codex`, audio.
- Native ChatGPT authentication and remote SDP stay behind `CodexRealtimeGateway`.
- Native `started` and WebRTC data-channel readiness must both precede capture.
  The empty start reply is not connected-media proof.
- Native StartOrSteer owns all delegation. Android only displays native
  transcripts; it never dispatches those transcripts or peer delegation twice.
- A local hangup closes Voice without canceling accepted Codex work. Spoken work
  cancellation is delegated/steered; the existing composer stop button remains
  the explicit `turn/interrupt` control. Ordinary barge-in affects speech.
- Late events, account/runtime changes and ambiguous stop results cannot start
  another call or repeat a task. Connection failure does not auto-retry.

The current native protocol has no trusted per-turn Voice-versus-desktop origin.
Live admission therefore requires a proven disabled desktop relay and no remote
work. Remote enabling is blocked during Voice and remaining voice-origin work;
consent and pairing are not changed automatically. Errors explain the conflict.

## Host integration hooks

`CodexLiveVoiceSession` is Activity-independent and is owned by
`HansLiveVoiceForegroundService`. It reuses the Android WebRTC audio route,
speaker gain, physical mute and foreground microphone permission checks.
`CodexLiveSessionProvider` supplies signaling without an Android key lookup.
`LiveApiVoiceSession` and older API adapters remain for isolated tests/legacy
code; the production factory selects Codex only. The two Live catalogues and
the TTS catalogue have separate stored preferences.

Host unit tests and synthetic API31–36 tests do not prove account entitlement,
audible duplex quality or actual phone actions. Those need a user-started call.
