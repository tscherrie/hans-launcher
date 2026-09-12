# Hans native Realtime voice

The root-free edition uses native WebRTC for Realtime voice. Media is never
proxied through the local Codex runtime: Android captures and plays the duplex
audio stream, while the `oai-events` data channel carries bounded JSON events.

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

## OpenAI protocol

The adapter follows the current official WebRTC flow:

- mint a short-lived client secret with `POST /v1/realtime/client_secrets`;
- post the local SDP offer to `POST /v1/realtime/calls` with that secret;
- configure the session through the `oai-events` data channel;
- let WebRTC carry audio and let server VAD manage interruption/truncation.

Normal VAD interruption must not send a manual `conversation.item.truncate`.
For WebRTC, the server knows the playout buffer and automatically truncates
unheard assistant audio. `output_audio_buffer.clear` is reserved for an
explicit user stop action.

## Host integration hooks

`LiveVoiceSession` is Activity-independent. To guarantee that audio continues
while app control puts another application in front, the Android host still
needs to own the session from its microphone foreground service and display the
required persistent notification. That host wiring deliberately lives outside
this package.

The host also supplies:

- bounded instructions produced by `LiveVoiceContextBuilder`;
- an ephemeral credential provider (a backend in production, or the existing
  Keystore key through `OpenAiRealtimeClientSecretProvider` for a local test);
- a `LiveVoiceTaskExecutor` adapter for the chosen work runtime;
- permission and lifecycle UX.
