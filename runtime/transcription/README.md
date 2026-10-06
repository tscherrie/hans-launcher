# Hans subscription transcription compatibility helper

This is Hans-owned source, **not an official OpenAI release binary**. It reuses
the pinned Codex 0.155.0 `codex-login` native auth manager to call the historical
undocumented `https://chatgpt.com/backend-api/transcribe` endpoint. Availability,
model choice, quality and future compatibility are not guaranteed. It never
falls back to the public paid Audio API.

The helper is packaged as `libcodex_transcribe.so`, a static ARM64 Linux-musl
executable extracted to Android's read-only `nativeLibraryDir`. Do not copy it
to writable app storage or execute it as another UID.

## Process protocol

- `--version`: one plain line `hans-codex-transcribe 0.1.0`.
- `--check-auth`: cached native ChatGPT credentials only; JSON `{"ready":true}`
  or one stable `{"error":"codex_transcription_..."}`. No transcription or refresh.
- `--transcribe`: complete RIFF/WAV on stdin, then EOF. PCM signed 16-bit,
  24 kHz mono, 1–120 seconds, maximum 6,000,000 input bytes. No file path,
  base64, credentials or audio in argv. One stdout JSON line `{"text":"..."}`
  (maximum 16 KiB UTF-8 text) or stable error. Exit 0 success, 1 failure.
- Required absolute `CODEX_HOME`: same app-private home as Codex, file auth mode.
  Auth loading belongs solely to `codex-login`; the helper is read-only and
  never refreshes or writes auth. The existing App Server remains the sole
  refresh owner, avoiding concurrent refresh-token rotation. A stale access
  token returns `codex_transcription_auth_required` rather than modifying the
  login. No raw credentials leave the helper. Parent owns cancellation by
  terminating the process. Serialized stdout, including newline, is capped at
  65,536 bytes; excessive escaped text returns a stable invalid-response error.
- Thirty-second auth/upload deadline after stdin EOF; the parent must enforce
  its own recording/read/process deadline as well. No redirects are followed.
  No tracing subscriber, raw backend-error output or private diagnostic files.
- Parent must strip API-key/token and auth endpoint override environment values;
  the helper additionally rejects them. Existing `HTTPS_PROXY` and
  `SSL_CERT_FILE` may be supplied by Hans' standard network environment.

## Rebuild and verify

`runtime/scripts/package-transcription-helper.sh --build` fetches only the
SHA-pinned upstream source archive into an ignored `build/` directory and runs
`cargo +1.96.0 build --locked` with this checked-in Cargo.lock. The generated
source is not a dependency on a developer's previous runtime checkout. The
build requires a static ARM64 musl compiler and vendored OpenSSL build tools.
`RUSTFLAGS` includes 16 KiB ELF maximum page size. Package mode verifies the
Hans-owned binary's hash, byte count, architecture, no interpreter/dynamic
dependencies and PT_LOAD alignment against `helper.lock.json` before copying
it to the generated APK input directory.

Offline native regression tests use only in-memory WAV silence, dummy auth and
loopback HTTP servers. They never open the microphone, load real credentials
or call the backend.
