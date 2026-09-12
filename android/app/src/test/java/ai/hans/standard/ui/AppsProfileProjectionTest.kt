package ai.hans.standard.ui

import ai.hans.standard.phone.capabilities.LaunchProfileType
import ai.hans.standard.phone.capabilities.PERSONAL_LAUNCH_PROFILE_ID
import ai.hans.standard.phone.capabilities.PrivateSpaceAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsProfileProjectionTest {
    @Test
    fun privateAppsHaveASeparateSectionWhileUnlocked() {
        val sections = appUiSections(state(privateLocked = false))

        assertEquals(listOf("Persönlich", "Privater Bereich"), sections.map { it.title })
        assertEquals(listOf("org.personal"), sections[0].apps.map { it.packageName })
        assertEquals(listOf("org.private"), sections[1].apps.map { it.packageName })
    }

    @Test
    fun lockedPrivateInventoryCannotBeFoundEvenIfStaleUiDataStillExists() {
        val sections = appUiSections(
            state(privateLocked = true).copy(query = "Secret private label"),
        )
        val privateSection = sections.single { it.type == LaunchProfileType.PRIVATE }

        assertTrue(privateSection.locked)
        assertTrue(privateSection.apps.isEmpty())
        assertFalse(sections.flatMap { it.apps }.any { it.packageName == "org.private" })
    }

    @Test
    fun androidTwelveStyleStateNeedsNoProfileSpecificFields() {
        val sections = appUiSections(
            AppsUiState(
                apps = listOf(AppUiModel("org.legacy", "org.legacy/.Main", "Legacy")),
            ),
        )

        assertEquals(1, sections.size)
        assertEquals(PERSONAL_LAUNCH_PROFILE_ID, sections.single().profileId)
        assertEquals("org.legacy", sections.single().apps.single().packageName)
    }

    @Test
    fun hiddenPrivateContainerRemovesProfileAndInventoryFromSearchProjection() {
        val sections = appUiSections(
            state(privateLocked = false).copy(
                query = "Secret private label",
                privateSpace = state(privateLocked = false).privateSpace.copy(
                    containerVisible = false,
                ),
            ),
        )

        assertFalse(sections.any { it.type == LaunchProfileType.PRIVATE })
        assertFalse(sections.flatMap { it.apps }.any { it.packageName == "org.private" })
    }

    @Test
    fun visibleUnlockedPrivateContainerRemainsDiscoverableWhenItHasNoApps() {
        val source = state(privateLocked = false)
        val sections = appUiSections(
            source.copy(apps = source.apps.filterNot { it.profileType == LaunchProfileType.PRIVATE }),
        )

        val privateSection = sections.single { it.type == LaunchProfileType.PRIVATE }
        assertFalse(privateSection.locked)
        assertTrue(privateSection.apps.isEmpty())
    }

    @Test
    fun staleOpaqueHandleCannotBindInventoryToAReplacementPrivateProfile() {
        val source = state(privateLocked = false)
        val sections = appUiSections(
            source.copy(
                privateSpace = source.privateSpace.copy(
                    profileId = "profile_ffffffffffffffffffffffffffffffff",
                ),
            ),
        )

        assertFalse(sections.any { it.type == LaunchProfileType.PRIVATE })
        assertFalse(sections.flatMap { it.apps }.any { it.packageName == "org.private" })
    }

    @Test
    fun androidHiddenEntrypointRemovesLockedContainerButNotUnlockedContainer() {
        val locked = state(privateLocked = true).let { source ->
            source.copy(
                privateSpace = source.privateSpace.copy(entrypointHiddenWhenLocked = true),
            )
        }
        assertFalse(appUiSections(locked).any { it.type == LaunchProfileType.PRIVATE })

        val unlocked = locked.copy(
            profiles = locked.profiles.map { profile ->
                if (profile.type == LaunchProfileType.PRIVATE) profile.copy(locked = false)
                else profile
            },
            privateSpace = locked.privateSpace.copy(locked = false),
        )
        assertTrue(appUiSections(unlocked).any { it.type == LaunchProfileType.PRIVATE })
    }

    @Test
    fun profileLossPurgesEveryNonPersonalAppAndProfileBeforePlatformReread() {
        val privateId = "profile_0123456789abcdef0123456789abcdef"
        val workId = "profile_11111111111111111111111111111111"
        val cloneId = "profile_22222222222222222222222222222222"
        val retained = personalAppInventoryOnly(
            apps = listOf(
                AppUiModel("org.personal", "org.personal/.Main", "Personal"),
                AppUiModel(
                    "org.private",
                    "org.private/.Main",
                    "Private",
                    privateId,
                    LaunchProfileType.PRIVATE,
                ),
                AppUiModel(
                    "org.work",
                    "org.work/.Main",
                    "Work",
                    workId,
                    LaunchProfileType.WORK,
                ),
                AppUiModel(
                    "org.clone",
                    "org.clone/.Main",
                    "Clone",
                    cloneId,
                    LaunchProfileType.CLONE,
                ),
                AppUiModel(
                    "org.malformed",
                    "org.malformed/.Main",
                    "Malformed",
                    workId,
                    LaunchProfileType.PERSONAL,
                ),
            ),
            profiles = listOf(
                AppProfileUiModel(
                    PERSONAL_LAUNCH_PROFILE_ID,
                    LaunchProfileType.PERSONAL,
                    false,
                ),
                AppProfileUiModel(privateId, LaunchProfileType.PRIVATE, false),
                AppProfileUiModel(workId, LaunchProfileType.WORK, false),
                AppProfileUiModel(cloneId, LaunchProfileType.CLONE, false),
                AppProfileUiModel(workId, LaunchProfileType.PERSONAL, false),
            ),
        )

        assertEquals(listOf("org.personal"), retained.apps.map { it.packageName })
        assertEquals(
            listOf(PERSONAL_LAUNCH_PROFILE_ID),
            retained.profiles.map { it.profileId },
        )
    }

    private fun state(privateLocked: Boolean): AppsUiState {
        val privateId = "profile_0123456789abcdef0123456789abcdef"
        return AppsUiState(
            apps = listOf(
                AppUiModel("org.personal", "org.personal/.Main", "Personal"),
                AppUiModel(
                    "org.private",
                    "org.private/.Main",
                    "Secret private label",
                    profileId = privateId,
                    profileType = LaunchProfileType.PRIVATE,
                ),
            ),
            profiles = listOf(
                AppProfileUiModel(
                    PERSONAL_LAUNCH_PROFILE_ID,
                    LaunchProfileType.PERSONAL,
                    locked = false,
                ),
                AppProfileUiModel(privateId, LaunchProfileType.PRIVATE, privateLocked),
            ),
            privateSpace = PrivateSpaceUiState(
                availability = PrivateSpaceAvailability.AVAILABLE,
                profileId = privateId,
                locked = privateLocked,
                containerVisible = true,
            ),
        )
    }
}
