package ai.hans.standard.localization

import ai.hans.standard.R
import ai.hans.standard.network.InternetStatus
import ai.hans.standard.network.internetWorkNotice
import ai.hans.standard.setup.HansSetupOutputSanitizer
import ai.hans.standard.setup.AccessibilitySetupTestCopy
import ai.hans.standard.voice.feedback.openAiSpeechFailureMessage
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class IntegrationLocalizationTest {
    @Test fun failuresUseExplicitEffectiveLanguageAndUnsupportedLanguagesFallBackToEnglish() {
        val en = TestResourceTextResolver(Locale.ENGLISH)
        val de = TestResourceTextResolver(Locale.GERMAN)
        val fr = TestResourceTextResolver(Locale.FRENCH)
        assertTrue(openAiSpeechFailureMessage("authentication_failed", en).contains("rejected"))
        assertTrue(openAiSpeechFailureMessage("authentication_failed", de).contains("abgelehnt"))
        assertEquals(openAiSpeechFailureMessage("quota_exhausted", en),
            openAiSpeechFailureMessage("quota_exhausted", fr))
        assertTrue(InternetStatus.OFFLINE.notice(en).contains("data roaming"))
        assertTrue(InternetStatus.OFFLINE.notice(de).contains("Datenroaming"))
        assertTrue(internetWorkNotice(InternetStatus.OFFLINE, true, en).contains("will not be sent again"))
    }

    @Test fun sanitizerLocalizesOnlyOwnSafetyCopyAndPreservesMixedLanguageUserContent() {
        val mixed = "Hallo — please send café 42 to Олег.\nNo translation, bitte."
        listOf(Locale.ENGLISH, Locale.GERMAN).forEach { locale ->
            val text = TestResourceTextResolver(locale)
            assertEquals(mixed, HansSetupOutputSanitizer.sanitizeAssistantText(mixed, false, text))
            assertEquals(mixed, HansSetupOutputSanitizer.sanitizeAssistantText(mixed, true, text))
            val result = HansSetupOutputSanitizer.sanitizeAssistantText(
                "{\"currentStep\":\"intro\",\"operationNonce\":\"never_show_nonce_42\"}", true, text)
            assertFalse(result.contains("never_show_nonce"))
            assertFalse(result.contains("currentStep"))
            assertTrue(result.contains(if (locale == Locale.GERMAN) "Einrichtungsstand" else "setup status"))
        }
    }

    @Test fun serviceCopyAndReceiptCopyHaveBothLanguagesWithoutTranslatingIdentifiers() {
        val en = TestResourceTextResolver(Locale.ENGLISH)
        val de = TestResourceTextResolver(Locale.GERMAN)
        assertEquals("Hans dictation", en.text(R.string.integration_hans_dictation_cea468e))
        assertEquals("Hans-Diktat", de.text(R.string.integration_hans_dictation_cea468e))
        assertEquals("Hans is listening", en.text(R.string.integration_hans_is_listening_3d4eedb))
        assertNotEquals(AccessibilitySetupTestCopy.capture(en), AccessibilitySetupTestCopy.capture(de))
    }
}
