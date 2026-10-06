package ai.hans.standard.ui

import ai.hans.standard.localization.TestResourceTextResolver
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.voice.feedback.OpenAiSpeechRemediation
import ai.hans.standard.voice.feedback.SpeechServiceFailureSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechFailureProjectionTest {
    @Test
    fun creditFailureSurvivesUnrelatedNetworkAndComposerChanges() {
        val local = HansLocalUiState(text = "keep draft", speechFailure = SpeechServiceFailureSnapshot("realtime_stt_quota_exhausted", 4))
        val projected = HansClientUiProjector.project(null, local, HansSettings(), TestResourceTextResolver())
        assertEquals(OpenAiSpeechRemediation.BILLING, projected.chat.speechFailure?.remediation)
        assertEquals(4L, projected.chat.speechFailure?.revision)
        assertTrue(checkNotNull(projected.chat.speechFailure).message.contains("API credits"))
        val next = HansClientUiProjector.project(null, local.copy(connectionFailureMessage = "Network changed"), HansSettings(), TestResourceTextResolver())
        assertEquals(projected.chat.speechFailure, next.chat.speechFailure)
        assertEquals("keep draft", next.chat.composer.text)
        assertNull(projectSpeechFailure(SpeechServiceFailureSnapshot(revision = 5), TestResourceTextResolver()))
    }
}
