package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.TestResourceTextResolver
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSettingsCleanupStringsTest {
    @Test fun voiceTaskHelpUsesCurrentBehaviourInEnglishAndGerman() {
        val en = TestResourceTextResolver(Locale.ENGLISH)
        val de = TestResourceTextResolver(Locale.GERMAN)
        assertTrue(en.text(R.string.voice_settings_task_help).contains("Without an assigned key"))
        assertTrue(en.text(R.string.voice_settings_task_help).contains("stop recording"))
        assertTrue(en.text(R.string.voice_settings_task_help).contains("while Codex is working"))
        assertTrue(de.text(R.string.voice_settings_task_help).contains("Ohne eingerichtete Taste"))
        assertTrue(de.text(R.string.voice_settings_task_help).contains("beendet die Aufnahme"))
        assertTrue(de.text(R.string.voice_settings_task_help).contains("während Codex arbeitet"))
        for (resolver in listOf(en, de)) {
            val help = resolver.text(R.string.voice_settings_task_help)
            assertFalse(help.contains("two minutes"))
            assertFalse(help.contains("zwei Minuten"))
            assertFalse(help.contains("lautlose"))
            assertFalse(help.contains("mute"))
            assertFalse(help.contains("stumm"))
            assertTrue(resolver.text(R.string.voice_settings_task_idle).contains(
                if (resolver == en) "two minutes" else "zwei Minuten"))
        }
    }

    @Test fun unknownSystemLanguagesFallbackToEnglishAndFormatTheConfirmedVoice() {
        val en = TestResourceTextResolver(Locale.ENGLISH)
        val fr = TestResourceTextResolver(Locale.FRENCH)
        val de = TestResourceTextResolver(Locale.GERMAN)
        assertEquals("Hans’ voice: Cove", en.text(R.string.voice_settings_selected_voice, "Cove"))
        assertEquals(en.text(R.string.voice_settings_task_help), fr.text(R.string.voice_settings_task_help))
        assertEquals("Hans’ Stimme: Cove", de.text(R.string.voice_settings_selected_voice, "Cove"))
    }
}
