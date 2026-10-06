package ai.hans.standard.voice.feedback

import ai.hans.standard.localization.TestResourceTextResolver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiSpeechFailureFeedbackTest {
    @Test fun batchTranscriptionErrorsAreVisibleAndNeverSuggestApiBilling() {
        val text = TestResourceTextResolver(java.util.Locale.ENGLISH)
        for (suffix in listOf("auth_missing", "auth_required", "auth_changed", "forbidden", "rate_limited",
            "timeout", "network_unavailable", "unavailable", "unsupported_environment", "invalid_audio",
            "audio_too_short", "invalid_response", "empty_transcript")) {
            val code = "codex_transcription_$suffix"
            assertNull(openAiSpeechRemediation(code))
            assertFalse(openAiSpeechFailureMessage(code, text).isBlank())
        }
        assertEquals(text.text(ai.hans.standard.R.string.codex_batch_transcription_unavailable),
            openAiSpeechFailureMessage("codex_transcription_forbidden", text))
        assertEquals(text.text(ai.hans.standard.R.string.codex_live_sign_in_required),
            openAiSpeechFailureMessage("codex_transcription_auth_required", text))
    }
    @Test fun continuousDictationFailureWarnsBeforeRepeatingAndNeverSuggestsApiBilling() {
        for (locale in listOf(java.util.Locale.ENGLISH, java.util.Locale.GERMAN, java.util.Locale.FRENCH)) {
            val text = TestResourceTextResolver(locale)
            for (code in listOf("codex_task_voice_finish_timeout",
                    "action_voice_startup_buffer_full")) {
                assertNull(openAiSpeechRemediation(code))
                assertEquals(text.text(ai.hans.standard.R.string.dictation_voice_finish_unconfirmed),
                    openAiSpeechFailureMessage(code, text))
            }
        }
    }

    @Test fun missingWorkReceiptNeverClaimsMissingSpeechWasTheProblem() {
        for (locale in listOf(java.util.Locale.ENGLISH, java.util.Locale.GERMAN, java.util.Locale.FRENCH)) {
            val text = TestResourceTextResolver(locale)
            for (code in listOf("codex_task_voice_work_receipt_timeout", "codex_task_voice_work_receipt_invalid",
                    "codex_task_voice_receipt_capacity")) {
                assertNull(openAiSpeechRemediation(code))
                assertEquals(text.text(ai.hans.standard.R.string.dictation_work_completion_unconfirmed),
                    openAiSpeechFailureMessage(code, text))
            }
        }
    }

    @Test fun unconfirmedContinuousCaptureReleaseIsReportedAsRecoveryNotSuccess() {
        val text = TestResourceTextResolver(java.util.Locale.ENGLISH)
        for (code in listOf("codex_task_voice_audio_close_unconfirmed", "codex_task_voice_native_close_unconfirmed")) {
            assertEquals(text.text(ai.hans.standard.R.string.codex_live_recovery_required),
                openAiSpeechFailureMessage(code, text))
        }
    }

    @Test fun nativeReaderErrorsNeverSuggestAddingApiCreditsOrKeys() {
        for (locale in listOf(java.util.Locale.ENGLISH, java.util.Locale.GERMAN, java.util.Locale.FRENCH)) {
            val text = TestResourceTextResolver(locale)
            for (suffix in listOf("chatgpt_login_required", "authentication", "usage_limit", "not_available", "close_unconfirmed")) {
                val code = "codex_read_aloud_$suffix"
                assertNull(openAiSpeechRemediation(code))
                val message = openAiSpeechFailureMessage(code, text)
                assertFalse(message.isBlank())
                if (suffix != "close_unconfirmed") {
                    assertEquals(openAiSpeechFailureMessage("codex_live_$suffix", text), message)
                } else {
                    assertEquals(text.text(ai.hans.standard.R.string.codex_read_aloud_failed), message)
                }
            }
        }
    }
    @Test
    fun codexLiveFailuresNeverAskForApiKeysOrApiBilling() {
        val english = TestResourceTextResolver(java.util.Locale.ENGLISH)
        val german = TestResourceTextResolver(java.util.Locale.GERMAN)
        listOf("chatgpt_login_required", "authentication", "usage_limit", "not_available",
            "session_not_ready", "session_changed", "connection_failed", "timed_out", "recovery_required").forEach { suffix ->
            val code = "codex_live_$suffix"
            assertNull(openAiSpeechRemediation(code))
            assertFalse(openAiSpeechFailureMessage(code, english).isBlank())
            assertFalse(openAiSpeechFailureMessage(code, german).isBlank())
        }
        assertTrue(openAiSpeechFailureMessage("codex_live_authentication", english).contains("ChatGPT sign-in"))
        assertTrue(openAiSpeechFailureMessage("codex_live_usage_limit", german).contains("ChatGPT-Limit"))
        assertTrue(openAiSpeechFailureMessage("codex_live_not_available", english).contains("No API fallback"))
        assertTrue(openAiSpeechFailureMessage("codex_live_recovery_required", english).contains("finished closing"))
        assertTrue(openAiSpeechFailureMessage("codex_live_dictation_dispatch_failed", german).contains("nicht automatisch"))
    }

    @Test
    fun messagesDistinguishMissingRejectedQuotaRateAndNetwork() {
        assertTrue(openAiSpeechFailureMessage("credential_unavailable", text = TestResourceTextResolver(java.util.Locale.GERMAN)).contains("kein OpenAI API-Schlüssel"))
        assertTrue(openAiSpeechFailureMessage("authentication_failed", text = TestResourceTextResolver(java.util.Locale.GERMAN)).contains("abgelehnt"))
        assertTrue(openAiSpeechFailureMessage("quota_exhausted", text = TestResourceTextResolver(java.util.Locale.GERMAN)).contains("Guthaben"))
        assertTrue(openAiSpeechFailureMessage("rate_limited", text = TestResourceTextResolver(java.util.Locale.GERMAN)).contains("zu viele Anfragen"))
        assertTrue(openAiSpeechFailureMessage("network_timeout", text = TestResourceTextResolver(java.util.Locale.GERMAN)).contains("Internetverbindung"))
        assertTrue(openAiSpeechFailureMessage("tls_failed", text = TestResourceTextResolver(java.util.Locale.GERMAN)).contains("sichere Verbindung"))
    }

    @Test
    fun runtimePublishesOnlySanitizedCodes() {
        val store = SpeechServiceFailureStore()
        var observed = ""
        val observer = SpeechServiceFailureObserver { observed = it.code.orEmpty() }
        store.addObserver(observer)
        try {
            store.report("secret response with spaces")
            assertTrue(observed == "speech_service_failure")
            assertFalse(observed.contains("secret"))
        } finally {
            store.removeObserver(observer)
        }
    }

    @Test
    fun backgroundFailureIsReplayedAfterActivityRecreationAndRepeatAttemptsAreNotSuppressed() {
        val store = SpeechServiceFailureStore()
        store.report("live_quota_exhausted")
        val events = mutableListOf<SpeechServiceFailureSnapshot>()
        val observer = SpeechServiceFailureObserver(events::add)
        store.addObserver(observer)
        assertEquals("live_quota_exhausted", events.single().code)
        store.report("live_quota_exhausted")
        assertEquals(2, events.size)
        assertTrue(events[1].revision > events[0].revision)
        store.removeObserver(observer)
        store.report("realtime_stt_quota_exhausted")
        store.addObserver(observer)
        assertEquals("realtime_stt_quota_exhausted", events.last().code)
    }

    @Test
    fun dismissIsVersionedAndDoesNotSilenceTheNextAttempt() {
        val store = SpeechServiceFailureStore()
        val events = mutableListOf<SpeechServiceFailureSnapshot>()
        store.addObserver(SpeechServiceFailureObserver(events::add))
        store.report("quota_exhausted")
        val oldRevision = events.last().revision
        store.report("spending_limit_reached")
        store.dismiss(oldRevision)
        assertEquals("spending_limit_reached", events.last().code)
        store.dismiss(events.last().revision)
        assertNull(events.last().code)
        store.report("spending_limit_reached")
        assertEquals("spending_limit_reached", events.last().code)
    }

    @Test
    fun explicitlyDismissedFailureStaysClosedAfterActivityObserverRecreation() {
        val store = SpeechServiceFailureStore()
        val originalEvents = mutableListOf<SpeechServiceFailureSnapshot>()
        val original = SpeechServiceFailureObserver { originalEvents += it }
        store.addObserver(original)
        store.report("realtime_stt_quota_exhausted")
        store.dismiss(originalEvents.last().revision)
        store.removeObserver(original)
        val recreatedEvents = mutableListOf<SpeechServiceFailureSnapshot>()
        store.addObserver(SpeechServiceFailureObserver { recreatedEvents += it })
        assertNull(recreatedEvents.single().code)
        // Only a genuinely new provider failure, not a replayed recording snapshot, reopens it.
        store.report("realtime_stt_quota_exhausted")
        assertEquals("realtime_stt_quota_exhausted", recreatedEvents.last().code)
    }

    @Test
    fun onlyFixedTargetsAreAvailableAndQuotaIsDistinctFromRateAuthAndNetworkErrors() {
        listOf("", "live_", "realtime_", "realtime_stt_").forEach { prefix ->
            assertEquals(OpenAiSpeechRemediation.BILLING, openAiSpeechRemediation("${prefix}quota_exhausted"))
            assertEquals(OpenAiSpeechRemediation.LIMITS, openAiSpeechRemediation("${prefix}spending_limit_reached"))
            assertEquals(OpenAiSpeechRemediation.PROJECT_SETTINGS, openAiSpeechRemediation("${prefix}project_spending_limit_reached"))
        }
        listOf("rate_limited", "authentication_failed", "network_timeout", "https://evil.test/quota_exhausted").forEach {
            assertNull(openAiSpeechRemediation(it))
        }
        assertEquals("https://platform.openai.com/settings/organization/billing", OpenAiSpeechRemediation.BILLING.url)
        assertEquals("https://platform.openai.com/settings/organization/limits", OpenAiSpeechRemediation.LIMITS.url)
        assertEquals("https://platform.openai.com/settings/", OpenAiSpeechRemediation.PROJECT_SETTINGS.url)
    }

    @Test
    fun billingCopyUsesEnglishFallbackAndExplainsApiVersusChatGpt() {
        val english = TestResourceTextResolver(java.util.Locale.ENGLISH)
        val fallback = TestResourceTextResolver(java.util.Locale.FRENCH)
        val german = TestResourceTextResolver(java.util.Locale.GERMAN)
        val message = openAiSpeechFailureMessage("quota_exhausted", english)
        assertEquals(message, openAiSpeechFailureMessage("quota_exhausted", fallback))
        assertTrue(message.contains("ChatGPT subscription does not include API credit"))
        assertTrue(openAiSpeechFailureMessage("quota_exhausted", german).contains("ChatGPT-Abo enthält kein API-Guthaben"))
        assertTrue(openAiSpeechFailureMessage("spending_limit_reached", english).contains("spending or usage limit"))
        assertTrue(openAiSpeechFailureMessage("project_spending_limit_reached", english).contains("project spending limit"))
    }
}
