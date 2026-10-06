package ai.hans.standard.localization

import ai.hans.standard.R
import ai.hans.standard.ui.DictationUiStatus
import ai.hans.standard.ui.LiveVoiceUiStatus
import ai.hans.standard.ui.ReasoningEffortUiOption
import ai.hans.standard.ui.formatSpeechRate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HansPresentationLocalizationTest {
    @Test
    fun labelsAreResolvedPerPresentationWithoutCachingAGermanEnumValue() {
        val english = TestResourceTextResolver()
        val german = TestResourceTextResolver(Locale.GERMAN)
        val listening = LiveVoiceUiStatus.LISTENING
        assertEquals("Live is listening", english.text(listening.labelResource))
        assertEquals("Live hört zu", german.text(listening.labelResource))
        assertEquals("Live is listening", english.text(listening.labelResource))
        assertEquals("low", ReasoningEffortUiOption.LOW.id)
        assertEquals("Low", english.text(ReasoningEffortUiOption.LOW.labelResource))
        assertEquals("Klein", german.text(ReasoningEffortUiOption.LOW.labelResource))
        assertEquals("LISTENING", DictationUiStatus.LISTENING.name)
    }

    @Test
    fun speechRateFormattingUsesTheExplicitLocaleWithoutChangingGlobalState() {
        val before = Locale.getDefault()
        assertEquals("1.0×", formatSpeechRate(1f, Locale.US))
        assertEquals("1.25×", formatSpeechRate(1.25f, Locale.UK))
        assertEquals("1,0×", formatSpeechRate(1f, Locale.GERMANY))
        assertEquals("1,25×", formatSpeechRate(1.25f, Locale.GERMANY))
        assertEquals(before, Locale.getDefault())
    }

    @Test
    fun englishDefaultAndGermanResourcesPreserveConsentRestrictions() {
        val english = TestResourceTextResolver()
        val german = TestResourceTextResolver(Locale.GERMAN)
        assertEquals("Allow once", english.text(R.string.accessibility_sensitive_confirmation_allow_once))
        assertEquals("Einmal erlauben", german.text(R.string.accessibility_sensitive_confirmation_allow_once))
        assertTrue(english.text(R.string.accessibility_sensitive_confirmation_once).contains("one action only"))
        assertTrue(german.text(R.string.accessibility_sensitive_confirmation_once).contains("eine Aktion"))
    }
}
