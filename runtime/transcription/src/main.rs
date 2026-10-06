//! Hans-owned compatibility helper. Credentials remain inside the pinned
//! upstream Codex auth manager; stdout is deliberately a tiny, stable protocol.
use codex_http_client::{
    ClientRouteClass, HttpClient, HttpClientBuilder, HttpClientFactory, OutboundProxyPolicy,
};
use codex_login::{
    AuthCredentialsStoreMode, AuthKeyringBackendKind, AuthManager, AuthRouteConfig, CodexAuth,
};
use serde_json::{Value, json};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;

const ENDPOINT: &str = "https://chatgpt.com/backend-api/transcribe";
const MAX_WAV_BYTES: usize = 6_000_000;
const MAX_PCM_BYTES: usize = 24_000 * 2 * 120;
const MIN_PCM_BYTES: usize = 24_000 * 2;
const MAX_RESPONSE_BYTES: usize = 65_536;
const MAX_TEXT_BYTES: usize = 16_384;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);
const BOUNDARY: &str = "hans-transcribe-0_1_0-bounded-wav";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Failure {
    InvalidAudio,
    AudioTooShort,
    AudioTooLong,
    AuthMissing,
    AuthRequired,
    AuthChanged,
    NetworkUnavailable,
    Timeout,
    Forbidden,
    RateLimited,
    Unavailable,
    InvalidResponse,
    EmptyTranscript,
    UnsupportedEnvironment,
}

impl Failure {
    fn code(self) -> &'static str {
        match self {
            Self::InvalidAudio => "codex_transcription_invalid_audio",
            Self::AudioTooShort => "codex_transcription_audio_too_short",
            Self::AudioTooLong => "codex_transcription_audio_too_long",
            Self::AuthMissing => "codex_transcription_auth_missing",
            Self::AuthRequired => "codex_transcription_auth_required",
            Self::AuthChanged => "codex_transcription_auth_changed",
            Self::NetworkUnavailable => "codex_transcription_network_unavailable",
            Self::Timeout => "codex_transcription_timeout",
            Self::Forbidden => "codex_transcription_forbidden",
            Self::RateLimited => "codex_transcription_rate_limited",
            Self::Unavailable => "codex_transcription_unavailable",
            Self::InvalidResponse => "codex_transcription_invalid_response",
            Self::EmptyTranscript => "codex_transcription_empty_transcript",
            Self::UnsupportedEnvironment => "codex_transcription_unsupported_environment",
        }
    }
}

fn emit(value: Value, success: bool) -> ! {
    // Do not format error Debug/Display values: upstream failures may contain
    // credentials, endpoints, identifiers, or a private backend response body.
    let (payload, success) = match encode_output(&value) {
        Ok(payload) => (payload, success),
        Err(_) => (
            br#"{"error":"codex_transcription_invalid_response"}"#.to_vec(),
            false,
        ),
    };
    let mut stdout = std::io::stdout().lock();
    let written = stdout
        .write_all(&payload)
        .and_then(|()| stdout.write_all(b"\n"));
    let _ = stdout.flush();
    std::process::exit(if success && written.is_ok() { 0 } else { 1 });
}

fn encode_output(value: &Value) -> Result<Vec<u8>, Failure> {
    let payload = serde_json::to_vec(value).map_err(|_| Failure::InvalidResponse)?;
    // The complete line, including newline, fits the parent's 64 KiB reader.
    if payload.len() >= MAX_RESPONSE_BYTES {
        return Err(Failure::InvalidResponse);
    }
    Ok(payload)
}

fn emit_failure(failure: Failure) -> ! {
    emit(json!({"error": failure.code()}), false)
}

fn main() {
    // There is no tracing/log subscriber, panic formatter, audio dump or
    // credential-bearing subprocess argument in this executable.
    std::panic::set_hook(Box::new(|_| {}));
    let command = std::env::args().skip(1).collect::<Vec<_>>();
    if command == ["--version"] {
        println!("hans-codex-transcribe {}", env!("CARGO_PKG_VERSION"));
        return;
    }
    if command != ["--transcribe"] && command != ["--check-auth"] {
        emit_failure(Failure::Unavailable);
    }
    if forbidden_environment_present() {
        emit_failure(Failure::UnsupportedEnvironment);
    }
    let codex_home = match std::env::var_os("CODEX_HOME") {
        Some(path) if Path::new(&path).is_absolute() => PathBuf::from(path),
        _ => emit_failure(Failure::AuthMissing),
    };
    let wav = if command == ["--transcribe"] {
        match read_wav(std::io::stdin().lock()) {
            Ok(wav) => Some(wav),
            Err(failure) => emit_failure(failure),
        }
    } else {
        None
    };
    let runtime = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(_) => emit_failure(Failure::Unavailable),
    };
    let result = runtime.block_on(async {
        tokio::time::timeout(REQUEST_TIMEOUT, async {
            let factory = HttpClientFactory::new(OutboundProxyPolicy::ReqwestDefault);
            let manager = AuthManager::new(
                codex_home,
                false,
                AuthCredentialsStoreMode::File,
                None,
                Some("https://chatgpt.com".into()),
                AuthKeyringBackendKind::Direct,
                AuthRouteConfig::from_http_client_factory(factory.clone()),
            )
            .await;
            require_chatgpt(manager.auth_cached())?;
            if let Some(wav) = wav {
                // This helper is a read-only auth consumer. The running App
                // Server is the sole token-refresh/persistence owner: upstream
                // refresh locks are process-local, so refreshing here would
                // race its refresh-token rotation. Never parse auth.json here
                // or return credentials across IPC. A stale token returns a
                // stable auth_required failure instead of rotating it.
                let auth = require_chatgpt(manager.auth_cached())?;
                let account = auth.get_account_id().ok_or(Failure::AuthRequired)?;
                let token = auth.get_token().map_err(|_| Failure::AuthRequired)?;
                let client = HttpClientBuilder::new()
                    .without_redirects()
                    .without_request_logging()
                    .connect_timeout(Duration::from_secs(10))
                    .build_respecting_outbound_proxy_policy(
                        &factory,
                        ENDPOINT,
                        ClientRouteClass::Api,
                    )
                    .map_err(|_| Failure::NetworkUnavailable)?;
                let text = upload(&client, ENDPOINT, &token, &account, &wav).await?;
                // A sign-out/account switch during the request must not publish a
                // result attributed to an obsolete account.
                manager.reload().await;
                let current = require_chatgpt(manager.auth_cached())?;
                if current.get_account_id().as_deref() != Some(account.as_str()) {
                    return Err(Failure::AuthChanged);
                }
                Ok(json!({"text": text}))
            } else {
                // Offline check: cached credentials only, no refresh or upload.
                Ok(json!({"ready": true}))
            }
        })
        .await
    });
    match result {
        Ok(Ok(value)) => emit(value, true),
        Ok(Err(failure)) => emit_failure(failure),
        Err(_) => emit_failure(Failure::Timeout),
    }
}

fn forbidden_environment_present() -> bool {
    // A product process may not redirect native refresh credentials or borrow
    // externally injected tokens. The app launcher also removes these values.
    [
        "CODEX_REFRESH_TOKEN_URL_OVERRIDE",
        "CODEX_REVOKE_TOKEN_URL_OVERRIDE",
        "CODEX_APP_SERVER_LOGIN_CLIENT_ID",
        "CODEX_ACCESS_TOKEN",
        "CODEX_API_KEY",
        "OPENAI_API_KEY",
        "CODEX_INTERNAL_ORIGINATOR_OVERRIDE",
    ]
    .iter()
    .any(|name| std::env::var_os(name).is_some())
}

fn require_chatgpt(auth: Option<CodexAuth>) -> Result<CodexAuth, Failure> {
    let auth = auth.ok_or(Failure::AuthMissing)?;
    if !auth.is_chatgpt_auth() {
        return Err(Failure::AuthRequired);
    }
    if auth.get_token().map_or(true, |value| value.is_empty())
        || auth.get_account_id().is_none_or(|value| value.is_empty())
    {
        return Err(Failure::AuthRequired);
    }
    Ok(auth)
}

fn read_wav(reader: impl Read) -> Result<Vec<u8>, Failure> {
    let mut wav = Vec::new();
    reader
        .take((MAX_WAV_BYTES + 1) as u64)
        .read_to_end(&mut wav)
        .map_err(|_| Failure::InvalidAudio)?;
    if wav.len() > MAX_WAV_BYTES {
        return Err(Failure::AudioTooLong);
    }
    validate_wav(&wav)?;
    Ok(wav)
}

fn u16le(value: &[u8]) -> u16 {
    u16::from_le_bytes([value[0], value[1]])
}
fn u32le(value: &[u8]) -> u32 {
    u32::from_le_bytes([value[0], value[1], value[2], value[3]])
}

fn validate_wav(wav: &[u8]) -> Result<(), Failure> {
    if wav.len() < 44
        || wav.get(..4) != Some(b"RIFF")
        || wav.get(8..12) != Some(b"WAVE")
        || u32le(&wav[4..8]) as usize + 8 != wav.len()
    {
        return Err(Failure::InvalidAudio);
    }
    let mut offset = 12usize;
    let mut format_seen = false;
    let mut data_bytes = None;
    while offset < wav.len() {
        if wav.len() - offset < 8 {
            return Err(Failure::InvalidAudio);
        }
        let size = u32le(&wav[offset + 4..offset + 8]) as usize;
        let start = offset + 8;
        let end = start.checked_add(size).ok_or(Failure::InvalidAudio)?;
        if end > wav.len() {
            return Err(Failure::InvalidAudio);
        }
        match &wav[offset..offset + 4] {
            b"fmt " => {
                if format_seen || size < 16 {
                    return Err(Failure::InvalidAudio);
                }
                let fmt = &wav[start..end];
                if u16le(&fmt[..2]) != 1
                    || u16le(&fmt[2..4]) != 1
                    || u32le(&fmt[4..8]) != 24_000
                    || u32le(&fmt[8..12]) != 48_000
                    || u16le(&fmt[12..14]) != 2
                    || u16le(&fmt[14..16]) != 16
                {
                    return Err(Failure::InvalidAudio);
                }
                format_seen = true;
            }
            b"data" => {
                if !format_seen || data_bytes.is_some() || size % 2 != 0 {
                    return Err(Failure::InvalidAudio);
                }
                data_bytes = Some(size);
            }
            _ => {}
        }
        offset = end.checked_add(size % 2).ok_or(Failure::InvalidAudio)?;
        if offset > wav.len() {
            return Err(Failure::InvalidAudio);
        }
    }
    let bytes = data_bytes.ok_or(Failure::InvalidAudio)?;
    if bytes < MIN_PCM_BYTES {
        return Err(Failure::AudioTooShort);
    }
    if bytes > MAX_PCM_BYTES {
        return Err(Failure::AudioTooLong);
    }
    Ok(())
}

fn multipart(wav: &[u8]) -> Vec<u8> {
    // Only the historical ChatGPT file part. Never add a paid-API model/prompt.
    let mut body = format!("--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"dictation.wav\"\r\nContent-Type: audio/wav\r\n\r\n").into_bytes();
    body.extend_from_slice(wav);
    body.extend_from_slice(format!("\r\n--{BOUNDARY}--\r\n").as_bytes());
    body
}

fn status_failure(status: u16) -> Option<Failure> {
    match status {
        200..=299 => None,
        401 => Some(Failure::AuthRequired),
        403 => Some(Failure::Forbidden),
        429 => Some(Failure::RateLimited),
        _ => Some(Failure::Unavailable),
    }
}

fn parse_text(body: &[u8]) -> Result<String, Failure> {
    let value: Value = serde_json::from_slice(body).map_err(|_| Failure::InvalidResponse)?;
    let text = value
        .get("text")
        .and_then(Value::as_str)
        .ok_or(Failure::InvalidResponse)?
        .trim();
    if text.is_empty() {
        return Err(Failure::EmptyTranscript);
    }
    if text.len() > MAX_TEXT_BYTES {
        return Err(Failure::InvalidResponse);
    }
    Ok(text.to_owned())
}

async fn upload(
    client: &HttpClient,
    endpoint: &str,
    token: &str,
    account: &str,
    wav: &[u8],
) -> Result<String, Failure> {
    // The URL is not configurable in production. Tests call this private
    // function only with localhost and dummy credentials.
    if !endpoint_allowed(endpoint) {
        return Err(Failure::Unavailable);
    }
    let delimiter = format!("\r\n--{BOUNDARY}");
    if wav
        .windows(delimiter.len())
        .any(|window| window == delimiter.as_bytes())
    {
        return Err(Failure::InvalidAudio);
    }
    let mut response = client
        .post(endpoint)
        .bearer_auth(token)
        .header("chatgpt-account-id", account)
        .header("originator", "hans_android_launcher")
        .header(
            "User-Agent",
            "hans-codex-transcribe/0.1.0 (codex-login/0.155.0)",
        )
        .header(
            "Content-Type",
            format!("multipart/form-data; boundary={BOUNDARY}"),
        )
        .body(multipart(wav))
        .timeout(REQUEST_TIMEOUT)
        .send()
        .await
        .map_err(|error| {
            if error.is_timeout() {
                Failure::Timeout
            } else {
                Failure::NetworkUnavailable
            }
        })?;
    if let Some(failure) = status_failure(response.status().as_u16()) {
        return Err(failure);
    }
    if response
        .content_length()
        .is_some_and(|bytes| bytes > MAX_RESPONSE_BYTES as u64)
    {
        return Err(Failure::InvalidResponse);
    }
    let mut body = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| Failure::NetworkUnavailable)?
    {
        if chunk.len() > MAX_RESPONSE_BYTES - body.len() {
            return Err(Failure::InvalidResponse);
        }
        body.extend_from_slice(&chunk);
    }
    parse_text(&body)
}

fn endpoint_allowed(endpoint: &str) -> bool {
    if endpoint == ENDPOINT {
        return true;
    }
    #[cfg(test)]
    if let Ok(url) = reqwest::Url::parse(endpoint) {
        return url.scheme() == "http"
            && url.host_str() == Some("127.0.0.1")
            && url.path() == "/backend-api/transcribe"
            && url.query().is_none()
            && url.fragment().is_none()
            && url.username().is_empty()
            && url.password().is_none();
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    use std::sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    };

    fn wav(seconds: usize) -> Vec<u8> {
        let bytes = seconds * 48_000;
        let mut value = Vec::from(&b"RIFF"[..]);
        value.extend_from_slice(&((bytes + 36) as u32).to_le_bytes());
        value.extend_from_slice(b"WAVEfmt ");
        value.extend_from_slice(&16u32.to_le_bytes());
        value.extend_from_slice(&1u16.to_le_bytes());
        value.extend_from_slice(&1u16.to_le_bytes());
        value.extend_from_slice(&24_000u32.to_le_bytes());
        value.extend_from_slice(&48_000u32.to_le_bytes());
        value.extend_from_slice(&2u16.to_le_bytes());
        value.extend_from_slice(&16u16.to_le_bytes());
        value.extend_from_slice(b"data");
        value.extend_from_slice(&(bytes as u32).to_le_bytes());
        value.resize(bytes + 44, 0);
        value
    }

    #[test]
    fn valid_wav_bounds() {
        assert_eq!(validate_wav(&wav(1)), Ok(()));
        assert_eq!(validate_wav(&wav(120)), Ok(()));
    }
    #[test]
    fn short_and_long_audio_rejected() {
        assert_eq!(validate_wav(&wav(0)), Err(Failure::AudioTooShort));
        assert_eq!(validate_wav(&wav(121)), Err(Failure::AudioTooLong));
    }
    #[test]
    fn malformed_wav_rejected() {
        let mut data = wav(1);
        data[22] = 2;
        assert_eq!(validate_wav(&data), Err(Failure::InvalidAudio));
        data = wav(1);
        data.pop();
        assert_eq!(validate_wav(&data), Err(Failure::InvalidAudio));
    }
    #[test]
    fn oversized_stdin_bounded() {
        assert_eq!(read_wav(std::io::repeat(0)), Err(Failure::AudioTooLong));
    }
    #[test]
    fn text_only_and_trimmed() {
        assert_eq!(parse_text(br#"{"text":" Hello "}"#), Ok("Hello".into()));
        assert_eq!(
            parse_text(br#"{"text":" "}"#),
            Err(Failure::EmptyTranscript)
        );
        assert_eq!(
            parse_text(br#"{"error":{"secret":"do not echo"}}"#),
            Err(Failure::InvalidResponse)
        );
    }
    #[test]
    fn oversized_text_rejected() {
        assert_eq!(
            parse_text(
                serde_json::to_string(&json!({"text": "x".repeat(MAX_TEXT_BYTES + 1)}))
                    .unwrap()
                    .as_bytes()
            ),
            Err(Failure::InvalidResponse)
        );
    }
    #[test]
    fn serialized_stdout_including_newline_is_bounded() {
        assert!(encode_output(&json!({"text": "normal output"})).is_ok());
        assert_eq!(
            encode_output(&json!({"text": "\u{0000}".repeat(MAX_TEXT_BYTES)})),
            Err(Failure::InvalidResponse)
        );
    }
    #[test]
    fn native_api_key_auth_never_accepted() {
        assert!(matches!(
            require_chatgpt(Some(CodexAuth::from_api_key("dummy-offline-test-only"))),
            Err(Failure::AuthRequired)
        ));
    }
    #[test]
    fn missing_auth_never_accepted() {
        assert!(matches!(require_chatgpt(None), Err(Failure::AuthMissing)));
    }
    #[test]
    fn foreign_or_modified_endpoints_rejected() {
        assert!(endpoint_allowed(ENDPOINT));
        for value in [
            "https://evil.invalid/backend-api/transcribe",
            "http://chatgpt.com/backend-api/transcribe",
            "https://chatgpt.com/backend-api/transcribe?copy=1",
            "https://chatgpt.com/backend-api/transcribe#fragment",
        ] {
            assert!(!endpoint_allowed(value));
        }
    }
    #[tokio::test]
    async fn native_file_api_key_is_loaded_but_never_used() {
        let directory = tempfile::tempdir().unwrap();
        codex_login::login_with_api_key(
            directory.path(),
            "dummy-offline-native-auth",
            AuthCredentialsStoreMode::File,
            AuthKeyringBackendKind::Direct,
        )
        .unwrap();
        let manager = AuthManager::new(
            directory.path().to_owned(),
            false,
            AuthCredentialsStoreMode::File,
            None,
            Some("https://chatgpt.com".into()),
            AuthKeyringBackendKind::Direct,
            AuthRouteConfig::from_http_client_factory(HttpClientFactory::new(
                OutboundProxyPolicy::ReqwestDefault,
            )),
        )
        .await;
        assert!(matches!(
            require_chatgpt(manager.auth_cached()),
            Err(Failure::AuthRequired)
        ));
    }
    #[tokio::test]
    async fn native_missing_file_is_not_a_login_attempt() {
        let directory = tempfile::tempdir().unwrap();
        let manager = AuthManager::new(
            directory.path().to_owned(),
            false,
            AuthCredentialsStoreMode::File,
            None,
            Some("https://chatgpt.com".into()),
            AuthKeyringBackendKind::Direct,
            AuthRouteConfig::from_http_client_factory(HttpClientFactory::new(
                OutboundProxyPolicy::ReqwestDefault,
            )),
        )
        .await;
        assert!(matches!(
            require_chatgpt(manager.auth_cached()),
            Err(Failure::AuthMissing)
        ));
    }
    #[test]
    fn errors_are_stable_not_backend_payloads() {
        assert_eq!(status_failure(401), Some(Failure::AuthRequired));
        assert_eq!(status_failure(403), Some(Failure::Forbidden));
        assert_eq!(status_failure(429), Some(Failure::RateLimited));
        assert_eq!(status_failure(302), Some(Failure::Unavailable));
    }
    #[test]
    fn file_only_multipart() {
        let body = multipart(&wav(1));
        let prefix = String::from_utf8_lossy(&body[..200]);
        assert!(prefix.contains("name=\"file\""));
        assert!(!prefix.contains("name=\"model\""));
        assert!(!prefix.contains("name=\"prompt\""));
    }

    async fn mock(
        status: &str,
        payload: &str,
        location: Option<&str>,
    ) -> (Result<String, Failure>, Arc<AtomicBool>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let consumed = Arc::new(AtomicBool::new(false));
        let consumed_child = consumed.clone();
        let response = format!(
            "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n{}\r\n{payload}",
            payload.len(),
            location
                .map(|value| format!("Location: {value}\r\n"))
                .unwrap_or_default()
        );
        let thread = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut request = Vec::new();
            let mut buffer = [0u8; 8192];
            loop {
                let count = stream.read(&mut buffer).unwrap();
                if count == 0 {
                    break;
                }
                request.extend_from_slice(&buffer[..count]);
                if let Some(offset) = request.windows(4).position(|window| window == b"\r\n\r\n") {
                    let headers = String::from_utf8_lossy(&request[..offset]);
                    let bytes = headers
                        .lines()
                        .find_map(|line| {
                            line.to_ascii_lowercase()
                                .strip_prefix("content-length:")
                                .and_then(|value| value.trim().parse::<usize>().ok())
                        })
                        .unwrap();
                    if request.len() >= offset + 4 + bytes {
                        break;
                    }
                }
            }
            consumed_child.store(true, Ordering::SeqCst);
            stream.write_all(response.as_bytes()).unwrap();
        });
        let client = HttpClientBuilder::new()
            .without_redirects()
            .without_request_logging()
            .build_direct()
            .unwrap();
        let result = upload(
            &client,
            &format!("http://{address}/backend-api/transcribe"),
            "dummy-offline-token",
            "dummy-offline-account",
            &wav(1),
        )
        .await;
        thread.join().unwrap();
        (result, consumed)
    }

    #[tokio::test]
    async fn mock_success() {
        let (result, consumed) = mock("200 OK", r#"{"text":"Open WhatsApp"}"#, None).await;
        assert_eq!(result, Ok("Open WhatsApp".into()));
        assert!(consumed.load(Ordering::SeqCst));
    }
    #[tokio::test]
    async fn mock_forbidden_body_is_not_returned() {
        let (result, _) = mock("403 Forbidden", "dummy secret in backend body", None).await;
        assert_eq!(result, Err(Failure::Forbidden));
    }
    #[tokio::test]
    async fn authenticated_redirect_never_followed() {
        let (result, _) = mock(
            "302 Found",
            "",
            Some("https://must-not-be-contacted.invalid/steal"),
        )
        .await;
        assert_eq!(result, Err(Failure::Unavailable));
    }
}
