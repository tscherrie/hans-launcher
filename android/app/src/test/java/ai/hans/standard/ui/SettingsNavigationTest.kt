package ai.hans.standard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun incomingDesktopControlHasItsOwnGroupSeparateFromOutgoingAdvancedWork() {
        assertEquals(
            listOf(
                "permissions" to "Berechtigungen",
                "runtime" to "Modell & Antworten",
                "speech" to "Stimme & Vorlesen",
                "input" to "Tasten & Bedienung",
                "personal" to "Einrichtung & Gedächtnis",
                "remote_control" to "Fernzugriff durch ChatGPT Desktop",
                "advanced_work" to "Erweiterte Arbeit",
                "maintenance" to "Zugänge & Sicherung",
            ),
            SettingsGroup.entries.map { it.id to it.title },
        )
    }

    @Test
    fun openingEachGroupHasItsOwnTitleAndReturnsToTheUnchangedOverview() {
        val overview = SettingsNavigation()
        SettingsGroup.entries.forEach { group ->
            val opened = overview.open(group)
            assertEquals(group, opened.group)
            assertEquals(group.title, opened.title)
            assertEquals(overview, opened.backToGroups())
            assertNull(overview.group)
            assertEquals("Menü", overview.title)
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
        assertEquals("Menü", reopenedSession.title)
    }
}
