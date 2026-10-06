package ai.hans.standard.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiDictationFailureTest {
    @Test
    fun unconfirmedLatencyHasAnActionableContentFreeFailure() {
        assertEquals(
            RecordingFailure.TRANSCRIPTION_CONFIGURATION_UNCONFIRMED,
            openAiDictationFailure("realtime_stt_delay_not_confirmed"),
        )
    }

    @Test
    fun mapsEveryActionableOpenAiFailureWithoutExposingServerText() {
        assertEquals(
            RecordingFailure.OPENAI_AUTHENTICATION_FAILED,
            openAiDictationFailure("realtime_stt_authentication_failed"),
        )
        assertEquals(
            RecordingFailure.OPENAI_PERMISSION_DENIED,
            openAiDictationFailure("realtime_stt_permission_denied"),
        )
        assertEquals(
            RecordingFailure.OPENAI_QUOTA_EXHAUSTED,
            openAiDictationFailure("realtime_stt_quota_exhausted"),
        )
        assertEquals(
            RecordingFailure.OPENAI_RATE_LIMITED,
            openAiDictationFailure("realtime_stt_rate_limited"),
        )
        assertEquals(RecordingFailure.OPENAI_SPENDING_LIMIT_REACHED,
            openAiDictationFailure("realtime_stt_spending_limit_reached"))
        assertEquals(RecordingFailure.OPENAI_PROJECT_SPENDING_LIMIT_REACHED,
            openAiDictationFailure("realtime_stt_project_spending_limit_reached"))
        listOf(
            "realtime_stt_network_unavailable",
            "realtime_stt_network_timeout",
            "realtime_stt_tls_failed",
        ).forEach { code ->
            assertEquals(RecordingFailure.NETWORK_UNAVAILABLE, openAiDictationFailure(code))
        }
        assertEquals(
            RecordingFailure.EMPTY_TRANSCRIPT,
            openAiDictationFailure("realtime_stt_provider_audio_unintelligible"),
        )
        assertEquals(
            RecordingFailure.TRANSCRIPTION_FAILED,
            openAiDictationFailure("server-secret-or-unknown"),
        )
    }
}
