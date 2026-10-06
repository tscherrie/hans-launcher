package ai.hans.standard.localization

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.hans.standard.R
import ai.hans.standard.network.InternetStatus
import ai.hans.standard.setup.AccessibilitySetupTestCopy
import ai.hans.standard.voice.feedback.openAiSpeechFailureMessage
import ai.hans.standard.voice.realtime.LiveVoiceLanguage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Resource-only platform checks: no microphone, account, permission or task dispatch. */
@RunWith(AndroidJUnit4::class)
class IntegrationLocalizationAndroidTest {
    @Test fun englishContextUsesEnglishErrorsAndNotificationCopy() {
        val text = AndroidHansTextResolver(context("en-US"))
        assertTrue(openAiSpeechFailureMessage("quota_exhausted", text).contains("API credits"))
        assertTrue(InternetStatus.OFFLINE.notice(text).contains("data roaming"))
        assertEquals("Hans dictation", text.text(R.string.integration_hans_dictation_cea468e))
    }

    @Test fun germanContextKeepsGermanErrorsAndNotificationCopy() {
        val text = AndroidHansTextResolver(context("de-DE"))
        assertTrue(openAiSpeechFailureMessage("quota_exhausted", text).contains("Guthaben"))
        assertTrue(InternetStatus.OFFLINE.notice(text).contains("Datenroaming"))
        assertEquals("Hans-Diktat", text.text(R.string.integration_hans_dictation_cea468e))
    }

    @Test fun unsupportedLanguageFallsBackToEnglishVoiceAndVisibleCopy() {
        val context = context("bg-BG")
        val text = AndroidHansTextResolver(context)
        assertEquals("en", context.hansUiLanguageTag())
        assertEquals(LiveVoiceLanguage.ENGLISH, LiveVoiceLanguage.forLanguageTag(context.hansUiLanguageTag()))
        assertEquals("Hans dictation", text.text(R.string.integration_hans_dictation_cea468e))
    }

    @Test fun voiceLanguageMatchesResolvedResourcesAcrossPreferenceLists() {
        val german = context("fr-FR,de-DE")
        val english = context("fr-FR,en-US,de-DE")
        assertEquals("de", german.hansUiLanguageTag())
        assertEquals("en", english.hansUiLanguageTag())
        assertTrue(LiveVoiceLanguage.forLanguageTag(german.hansUiLanguageTag()).greetingInstructions.contains("Ja, hallo?"))
        assertTrue(LiveVoiceLanguage.forLanguageTag(english.hansUiLanguageTag()).greetingInstructions.contains("Hi, hello?"))
    }

    @Test fun setupEvidenceCopyRemainsCapturedWhenAnotherLocaleIsResolved() {
        val en = AccessibilitySetupTestCopy.capture(AndroidHansTextResolver(context("en-US")))
        val de = AccessibilitySetupTestCopy.capture(AndroidHansTextResolver(context("de-DE")))
        assertEquals("Hans detected this exact test action", en.postconditionText)
        assertEquals("Hans hat genau diese Testaktion erkannt", de.postconditionText)
        assertNotEquals(en, de)
        assertEquals("Hans detected this exact test action", en.postconditionText)
    }

    private fun context(languageTags: String): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        return target.createConfigurationContext(Configuration(target.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(languageTags))
        })
    }
}
