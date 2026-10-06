package ai.hans.standard.voice.realtime

import org.junit.Assert.*
import org.junit.Test

class LiveVoiceLanguageTest {
    @Test fun languageMappingHasGermanVariantsAndEnglishFallbackWithoutGlobalLocale() {
        listOf("de", "de-DE", "de-AT", "de-CH").forEach {
            assertEquals(LiveVoiceLanguage.GERMAN, LiveVoiceLanguage.forLanguageTag(it))
        }
        listOf(null, "", "en", "en-GB", "fr-FR", "bg-BG", "invalid tag").forEach {
            assertEquals(LiveVoiceLanguage.ENGLISH, LiveVoiceLanguage.forLanguageTag(it))
        }
        assertEquals(LiveVoiceLanguage.ENGLISH, LiveVoiceSessionContext("Instructions").language)
    }

    @Test fun welcomeLanguageDoesNotForceDictationOrOverrideTheUsersLanguage() {
        assertTrue(LiveVoiceLanguage.GERMAN.greetingInstructions.contains("Ja, hallo?"))
        assertTrue(LiveVoiceLanguage.ENGLISH.greetingInstructions.contains("Hi, hello?"))
        LiveVoiceLanguage.entries.forEach {
            assertTrue(it.greetingInstructions.contains("exactly once"))
            assertTrue(it.conversationInstructions.contains("user's actual language"))
            assertTrue(it.conversationInstructions.contains("Do not translate dictated user text"))
            assertFalse(it.greetingInstructions.contains("Speak German."))
        }
    }

    @Test fun boundedContextRefreshCapturesLanguageWithoutReintroducingWelcomeText() {
        var language = LiveVoiceLanguage.ENGLISH
        val provider = BoundedLiveVoiceInstructionsProvider(
            baseInstructionsProvider = { "You are Hans. Welcome only a genuinely new call." },
            snapshotProvider = { null }, capabilitySummaryProvider = { "No additional access." },
            languageProvider = { language },
        )
        val english = provider.buildSessionContext()
        language = LiveVoiceLanguage.GERMAN
        val german = provider.buildSessionContext()
        assertEquals(LiveVoiceLanguage.ENGLISH, english.language)
        assertEquals(LiveVoiceLanguage.GERMAN, german.language)
        assertTrue(english.instructions.contains("English"))
        assertTrue(german.instructions.contains("German"))
        assertFalse(german.refreshInstructions.contains("Ja, hallo?"))
        assertFalse(english.refreshInstructions.contains("Hi, hello?"))
    }
}
