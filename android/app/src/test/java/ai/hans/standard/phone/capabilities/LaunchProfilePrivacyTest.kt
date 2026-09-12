package ai.hans.standard.phone.capabilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchProfilePrivacyTest {
    @Test
    fun lockedPrivateProfileRetainsOnlyLiveStateAndDropsItsEntireInventory() {
        val catalog = LaunchableAppCatalog(
            apps = listOf(
                LaunchableApp("org.personal", "org.personal/.Main", "Personal"),
                LaunchableApp(
                    "org.private",
                    "org.private/.Main",
                    "Secret label",
                    profileId = "profile_0123456789abcdef0123456789abcdef",
                    profileType = LaunchProfileType.PRIVATE,
                ),
            ),
            profiles = listOf(
                personalProfile(),
                LaunchProfileState(
                    "profile_0123456789abcdef0123456789abcdef",
                    LaunchProfileType.PRIVATE,
                    locked = true,
                ),
            ),
        ).privacyFiltered()

        assertEquals(listOf("org.personal"), catalog.apps.map { it.packageName })
        assertFalse(catalog.apps.any { it.label.contains("Secret") })
        assertTrue(catalog.profiles.single { it.type == LaunchProfileType.PRIVATE }.locked)
    }

    @Test
    fun unlockedPrivateProfileIsVisibleAndLegacyAppsRemainPersonalByDefault() {
        val privateId = "profile_0123456789abcdef0123456789abcdef"
        val legacyApp = LaunchableApp("org.legacy", "org.legacy/.Main", "Legacy")
        val privateApp = LaunchableApp(
            "org.private",
            "org.private/.Main",
            "Private",
            profileId = privateId,
            profileType = LaunchProfileType.PRIVATE,
        )
        val catalog = LaunchableAppCatalog(
            apps = listOf(privateApp, legacyApp),
            profiles = listOf(
                personalProfile(),
                LaunchProfileState(privateId, LaunchProfileType.PRIVATE, locked = false),
            ),
        ).privacyFiltered()

        assertEquals(PERSONAL_LAUNCH_PROFILE_ID, legacyApp.profileId)
        assertEquals(LaunchProfileType.PERSONAL, legacyApp.profileType)
        assertEquals(setOf("org.legacy", "org.private"), catalog.apps.mapTo(mutableSetOf()) { it.packageName })
    }

    @Test
    fun unknownProfileInventoryFailsClosed() {
        val catalog = LaunchableAppCatalog(
            apps = listOf(
                LaunchableApp(
                    "org.unknown",
                    "org.unknown/.Main",
                    "Unknown",
                    profileId = "profile_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    profileType = LaunchProfileType.OTHER,
                ),
            ),
            profiles = listOf(personalProfile()),
        ).privacyFiltered()

        assertTrue(catalog.apps.isEmpty())
    }

    @Test
    fun opaqueRegistryIsStableOnlyWhileTheProfileRemainsLive() {
        val randomValues = mutableListOf(
            ByteArray(16) { 0x11 },
            ByteArray(16) { 0x22 },
        )
        val registry = OpaqueProfileHandleRegistry<String> { randomValues.removeAt(0) }

        val first = registry.handleFor("raw-user-10")
        assertEquals(first, registry.handleFor("raw-user-10"))
        assertTrue(isLaunchProfileId(first))
        assertFalse(first.contains("10"))

        registry.retain(emptySet())
        val replacement = registry.handleFor("raw-user-10")
        assertNotEquals(first, replacement)
    }

    private fun personalProfile() = LaunchProfileState(
        PERSONAL_LAUNCH_PROFILE_ID,
        LaunchProfileType.PERSONAL,
        locked = false,
    )
}
