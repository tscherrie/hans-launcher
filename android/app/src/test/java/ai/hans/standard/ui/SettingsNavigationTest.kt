package ai.hans.standard.ui

import ai.hans.standard.localization.TestResourceTextResolver
import java.util.Locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsNavigationTest {
    private val localizationText by lazy { TestResourceTextResolver(Locale.GERMAN) }

    @Test
    fun sixTaskOrientedGroupsKeepTheirStableIdsAndHideLegacyEntries() {
        assertEquals(
            listOf(
                "runtime" to "Modell & Antworten",
                "speech" to "Sprache & Diktat",
                "input" to "Tasten & Anzeige",
                "personal" to "Hans & Gedächtnis",
                "permissions" to "Berechtigungen & Datenschutz",
                "maintenance" to "System & Erweitert",
            ),
            SettingsGroup.availableGroups.map { it.id to localizationText.text(it.titleResource) },
        )
        assertFalse(SettingsGroup.REMOTE_CONTROL.available)
        assertFalse(SettingsGroup.ADVANCED_WORK.available)
    }

    @Test
    fun openingEachGroupHasItsOwnTitleAndReturnsToTheUnchangedOverview() {
        val overview = SettingsNavigation()
        SettingsGroup.availableGroups.forEach { group ->
            val opened = overview.open(group)
            assertEquals(group, opened.group)
            assertEquals(group.titleResource, opened.titleResource)
            assertEquals(overview, opened.backToGroups())
            assertNull(overview.group)
            assertEquals("Menü", localizationText.text(overview.titleResource))
        }
    }

    @Test
    fun disabledDesktopDestinationCannotBeOpenedOrRestored() {
        val requested = SettingsNavigation().open(SettingsGroup.REMOTE_CONTROL)
        val restored = SettingsNavigation(SettingsGroup.REMOTE_CONTROL)
        listOf(requested, restored).forEach { navigation ->
            assertNull(navigation.group)
            assertEquals("Menü", localizationText.text(navigation.titleResource))
            assertEquals(SettingsNavigation(), navigation.backToGroups())
        }
        assertEquals(SettingsGroup.MAINTENANCE,
            SettingsNavigation().open(SettingsGroup.ADVANCED_WORK).group)
    }

    @Test
    fun restoredAdvancedWorkRedirectsToSystemWithoutRevivingTheOldGroup() {
        val restored = SettingsNavigation(SettingsGroup.ADVANCED_WORK)
        assertEquals(SettingsGroup.MAINTENANCE, restored.group)
        assertEquals("System & Erweitert", localizationText.text(restored.titleResource))
        assertEquals(SettingsNavigation(), restored.backToGroups())
    }

    @Test
    fun defaultEnglishAndUnsupportedLocaleKeepTheSameSixCategoryOrder() {
        listOf(Locale.ENGLISH, Locale.FRENCH).forEach { locale ->
            val text = TestResourceTextResolver(locale)
            assertEquals(listOf("Model & responses", "Voice & dictation", "Keys & display",
                "Hans & memory", "Permissions & privacy", "System & advanced"),
                SettingsGroup.availableGroups.map { text.text(it.titleResource) })
        }
    }

    @Test
    fun changingGroupsDoesNotCreateAHiddenStackOfEarlierGroups() {
        val navigation = SettingsNavigation()
            .open(SettingsGroup.RUNTIME)
            .open(SettingsGroup.PERMISSIONS)
        assertEquals(SettingsGroup.PERMISSIONS, navigation.group)
        assertEquals(SettingsNavigation(), navigation.backToGroups())
        assertEquals(SettingsNavigation(), navigation.backToGroups().backToGroups())
    }

    @Test
    fun aNewMenuSessionNeverInheritsTheLastSessionsGroup() {
        val earlierSession = SettingsNavigation().open(SettingsGroup.MAINTENANCE)
        val reopenedSession = SettingsNavigation()
        assertEquals(SettingsGroup.MAINTENANCE, earlierSession.group)
        assertNull(reopenedSession.group)
        assertEquals("Menü", localizationText.text(reopenedSession.titleResource))
    }
}
