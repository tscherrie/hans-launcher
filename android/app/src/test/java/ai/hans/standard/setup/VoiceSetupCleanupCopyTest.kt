package ai.hans.standard.setup

import ai.hans.standard.R
import ai.hans.standard.localization.TestResourceTextResolver
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSetupCleanupCopyTest {
    @Test fun englishDefaultAndGermanExplainDistinctControlsAndOptionalPractice() {
        listOf(Locale.ENGLISH, Locale.GERMAN).forEach { locale ->
            val text = TestResourceTextResolver(locale)
            val hardware = text.text(R.string.voice_setup_short_task_hardware)
            val screen = text.text(R.string.voice_setup_short_task_screen)
            val phone = text.text(R.string.voice_setup_phone)
            val practice = text.text(R.string.voice_setup_optional_practice)
            val german = locale == Locale.GERMAN
            assertTrue(hardware.contains(if (german) "sofort" else "immediately"))
            assertTrue(hardware.contains(if (german) "beendet die Aufnahme" else "stop recording"))
            assertTrue(hardware.contains(if (german) "fertigen Text" else "finished text"))
            assertFalse(hardware.contains(if (german) "stumm" else "mute"))
            assertTrue(screen.contains(if (german) "keine physische Aktionstaste" else "no physical action key"))
            assertTrue(phone.contains(if (german) "längeres Gespräch" else "longer conversation"))
            assertTrue(phone.contains(if (german) "Verabschiedung" else "goodbye"))
            assertTrue(practice.contains(if (german) "freiwillig" else "optional"))
            assertTrue(practice.contains(if (german) "keine Aufnahme" else "has not tested"))
            listOf(hardware, screen, phone, practice).forEach {
                assertFalse(it.contains("SENT"))
                assertFalse(it.contains("API"))
                assertFalse(it.contains("hold to", ignoreCase = true))
            }
        }
    }
}
