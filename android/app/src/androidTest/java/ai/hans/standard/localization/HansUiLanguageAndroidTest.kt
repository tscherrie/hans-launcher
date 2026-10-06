package ai.hans.standard.localization

import ai.hans.standard.R
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Public, test-owned configuration contexts; never changes the phone's system language. */
class HansUiLanguageAndroidTest {
    @Test fun firstSupportedSystemPreferenceMatchesRenderedProductCopy() {
        listOf(
            "en-US,de-DE" to "en",
            "en-GB,de-AT" to "en",
            "de-AT,en-US" to "de",
            "de-CH,en-GB" to "de",
            "fr-FR,de-DE" to "de",
            "bg-BG,en-US,de-DE" to "en",
        ).forEach { (tags, expected) ->
            val context = context(tags)
            assertEquals(tags, expected, context.hansUiLanguageTag())
            assertEquals(tags, if (expected == "de") "Menü" else "Menu",
                context.getString(R.string.presentation_menu))
        }
    }

    @Test fun unsupportedLanguagesUseEnglishEvenWhenDependenciesHaveTranslations() {
        listOf("fr-FR", "bg-BG", "es-ES", "ar-EG", "fr-FR,bg-BG").forEach { tags ->
            val context = context(tags)
            assertEquals(tags, "en", context.hansUiLanguageTag())
            assertEquals(tags, "Menu", context.getString(R.string.presentation_menu))
        }
    }

    @Test fun separateConfigurationContextsDoNotCacheOrChangeTheProcessLocale() {
        val original = Locale.getDefault()
        val english = context("en-US")
        val german = context("de-DE")
        assertEquals("en", english.hansUiLanguageTag())
        assertEquals("de", german.hansUiLanguageTag())
        assertEquals("en", english.hansUiLanguageTag())
        assertEquals(original, Locale.getDefault())
    }

    @Test fun formattingLocaleIsNotReplacedByProductLanguageCanary() {
        val context = context("fr-FR,de-DE")
        val before = context.resources.configuration.locales.toLanguageTags()
        assertEquals("de", context.hansUiLanguageTag())
        assertEquals(context.resources.configuration.locales[0], AndroidHansTextResolver(context).locale)
        assertEquals(before, context.resources.configuration.locales.toLanguageTags())
    }

    private fun context(tags: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(tags))
        })
    }
}
