package ai.hans.standard.voice

/** Converts only stable, content-free STT failure codes into user-facing lifecycle causes. */
fun openAiDictationFailure(code: String?): RecordingFailure = when (code) {
    "realtime_stt_delay_not_confirmed" -> RecordingFailure.TRANSCRIPTION_CONFIGURATION_UNCONFIRMED
    "realtime_stt_authentication_failed" -> RecordingFailure.OPENAI_AUTHENTICATION_FAILED
    "realtime_stt_permission_denied" -> RecordingFailure.OPENAI_PERMISSION_DENIED
    "realtime_stt_quota_exhausted" -> RecordingFailure.OPENAI_QUOTA_EXHAUSTED
    "realtime_stt_rate_limited" -> RecordingFailure.OPENAI_RATE_LIMITED
    "realtime_stt_network_unavailable",
    "realtime_stt_network_timeout",
    "realtime_stt_tls_failed" -> RecordingFailure.NETWORK_UNAVAILABLE
    "realtime_stt_provider_audio_unintelligible" -> RecordingFailure.EMPTY_TRANSCRIPT
    else -> RecordingFailure.TRANSCRIPTION_FAILED
}
