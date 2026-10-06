package ai.hans.standard.localization

import ai.hans.standard.R
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Run the same class on APIs 31, 32, 33, 34, 35 and 36. No system locale is mutated. */
class AndroidHansTextResolverTest {
    @Test
    fun englishDefaultAndUnsupportedLocalesUseCompleteEnglishResources() {
        listOf("en-US", "en-GB", "fr-FR", "bg-BG").forEach { tag ->
            assertEquals(tag, "Menu", text(tag).text(R.string.presentation_menu))
            assertEquals(tag, "Hans notification access", text(tag).text(R.string.notification_listener_label))
        }
    }

    @Test
    fun germanRegionVariantsUseGermanResourcesWithoutAnAppOverride() {
        listOf("de-DE", "de-AT", "de-CH").forEach { tag ->
            assertEquals(tag, "Menü", text(tag).text(R.string.presentation_menu))
            assertEquals(tag, "Hans Benachrichtigungszugriff", text(tag).text(R.string.notification_listener_label))
        }
    }

    @Test
    fun effectiveConfigurationContextIsNotReplacedWithApplicationContext() {
        val english = text("en-US")
        val german = text("de-DE")
        assertEquals("Menu", english.text(R.string.presentation_menu))
        assertEquals("Menü", german.text(R.string.presentation_menu))
        assertEquals("Menu", english.text(R.string.presentation_menu))
        assertEquals("en", english.locale.language)
        assertEquals("de", german.locale.language)
    }

    @Test
    fun placeholdersPreserveCallerProvidedNamesAndOneActionAuthorization() {
        val label = "Übungs-App العربية %s"
        val packageName = "com.example.untranslated"
        val english = text("en-US")
        assertEquals("Hans wants to tap a control in $label ($packageName).",
            english.text(R.string.accessibility_sensitive_confirmation_click, label, packageName))
        assertEquals("Allow once", english.text(R.string.accessibility_sensitive_confirmation_allow_once))
        assertEquals("This permission applies to this one action only.",
            english.text(R.string.accessibility_sensitive_confirmation_once))
    }

    @Test
    fun localeListsHonorOrderedSupportedPreferences() {
        assertEquals("Menu", text("en-US,de-DE").text(R.string.presentation_menu))
        assertEquals("Menü", text("de-AT,en-GB").text(R.string.presentation_menu))
        assertEquals("Menü", text("fr-FR,de-DE").text(R.string.presentation_menu))
    }

    @Test
    fun pluralSelectionAndFormattingUseAndroidResources() {
        assertEquals("The last update reported 1 issue.", text("en-US").quantity(
            R.plurals.presentation_marketplace_upgrade_issues, 1, 1))
        assertEquals("The last update reported 2 issues.", text("en-US").quantity(
            R.plurals.presentation_marketplace_upgrade_issues, 2, 2))
        assertTrue(text("de-DE").quantity(R.plurals.presentation_marketplace_upgrade_issues, 1, 1)
            .contains("1 Problem"))
    }

    private fun text(languageTags: String): HansTextResolver = AndroidHansTextResolver(context(languageTags))

    private fun context(languageTags: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(languageTags))
        }
        return base.createConfigurationContext(config)
    }
}
