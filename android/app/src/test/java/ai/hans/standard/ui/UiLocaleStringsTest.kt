package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.TestResourceTextResolver
import ai.hans.standard.remotecontrol.RemoteControlSnapshot
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLocaleStringsTest {
    @Test fun englishDefaultGermanTranslationAndUnsupportedLanguageFallbackAreDistinct() {
        val english = TestResourceTextResolver(Locale.ENGLISH)
        val german = TestResourceTextResolver(Locale.GERMAN)
        val french = TestResourceTextResolver(Locale.FRENCH)
        assertEquals("Stop Codex task", english.text(R.string.ui_stop_codex_task_7c4b45))
        assertEquals("Codex-Aufgabe stoppen", german.text(R.string.ui_stop_codex_task_7c4b45))
        assertEquals(english.text(R.string.ui_stop_codex_task_7c4b45),
            french.text(R.string.ui_stop_codex_task_7c4b45))
    }

    @Test fun consentTranslationPreservesItsPrivateDataScopeAndPermissionLimits() {
        val english = remoteControlConsent(TestResourceTextResolver(Locale.ENGLISH))
        val german = remoteControlConsent(TestResourceTextResolver(Locale.GERMAN))
        assertTrue(english.contains("private Hans data"))
        assertTrue(english.contains("Android tools you have authorized"))
        assertTrue(english.contains("until Hans restarts or closes"))
        assertTrue(english.contains("until you turn remote access off"))
        assertTrue(english.contains("Android permissions and confirmations remain required"))
        assertTrue(english.contains("does not grant root privileges"))
        assertTrue(english.contains("unlock the phone"))
        assertTrue(german.contains("private Hans-Daten"))
        assertTrue(german.contains("von dir freigegebenen Android-Werkzeuge"))
        assertTrue(german.contains("bis zum Neustart oder Beenden von Hans"))
        assertTrue(german.contains("bis du den Fernzugriff ausschaltest"))
        assertTrue(german.contains("Android-Berechtigungen und Bestätigungen bleiben erforderlich"))
        assertTrue(german.contains("Root-Rechte"))
        assertEquals("Permission: not allowed",
            remoteControlPresentation(RemoteControlSnapshot(), TestResourceTextResolver()).consent)
        assertEquals("Freigabe: nicht erlaubt",
            remoteControlPresentation(RemoteControlSnapshot(), TestResourceTextResolver(Locale.GERMAN)).consent)
    }

    @Test fun numericPresentationUsesTheRequestedLocaleAndCountsUsePluralResources() {
        assertEquals("1.0 KB", formatWorkbenchBytes(1024, Locale.ENGLISH))
        assertEquals("1,0 KB", formatWorkbenchBytes(1024, Locale.GERMANY))
        val english = TestResourceTextResolver()
        val german = TestResourceTextResolver(Locale.GERMAN)
        assertEquals("1 pending operation", english.quantity(R.plurals.ui_pending_operations, 1, 1))
        assertEquals("2 pending operations", english.quantity(R.plurals.ui_pending_operations, 2, 2))
        assertEquals("1 ausstehender Vorgang", german.quantity(R.plurals.ui_pending_operations, 1, 1))
    }
}
