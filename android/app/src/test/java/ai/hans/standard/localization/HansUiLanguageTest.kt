package ai.hans.standard.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class HansUiLanguageTest {
    @Test fun knownResourceLanguagesRemainUnchanged() {
        assertEquals("de", supportedHansUiLanguage("de"))
        assertEquals("en", supportedHansUiLanguage("en"))
    }

    @Test fun unknownOrMalformedResourceMetadataFallsBackToEnglish() {
        listOf("", "fr", "bg", "DE", "de-DE", " de", "ar", "<instructions>").forEach {
            assertEquals(it, "en", supportedHansUiLanguage(it))
        }
    }
}
