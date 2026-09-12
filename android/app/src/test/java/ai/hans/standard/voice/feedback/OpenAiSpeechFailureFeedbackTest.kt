package ai.hans.standard.voice.feedback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiSpeechFailureFeedbackTest {
    @Test
    fun messagesDistinguishMissingRejectedQuotaRateAndNetwork() {
        assertTrue(openAiSpeechFailureMessage("credential_unavailable").contains("kein OpenAI API-Schlüssel"))
        assertTrue(openAiSpeechFailureMessage("authentication_failed").contains("abgelehnt"))
        assertTrue(openAiSpeechFailureMessage("quota_exhausted").contains("Guthaben"))
        assertTrue(openAiSpeechFailureMessage("rate_limited").contains("zu viele Anfragen"))
        assertTrue(openAiSpeechFailureMessage("network_timeout").contains("Internetverbindung"))
        assertTrue(openAiSpeechFailureMessage("tls_failed").contains("sichere Verbindung"))
    }

    @Test
    fun runtimePublishesOnlySanitizedCodes() {
        var observed = ""
        val observer = SpeechServiceFailureObserver { observed = it.code.orEmpty() }
        HansSpeechFailureRuntime.addObserver(observer)
        try {
            HansSpeechFailureRuntime.report("secret response with spaces")
            assertTrue(observed == "speech_service_failure")
            assertFalse(observed.contains("secret"))
        } finally {
            HansSpeechFailureRuntime.removeObserver(observer)
        }
    }
}
