package ai.hans.standard.phone.capabilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalLaunchTargetSelectionTest {
    @Test
    fun canonicalPackageFrontDoorWinsOverFirstOfMultipleLauncherActivities() {
        val alphabeticalFirst = component("org.example.multi", "org.example.multi.AlphaActivity")
        val canonicalFrontDoor = component("org.example.multi", "org.example.multi.ZFrontDoorActivity")

        val selected = preferredPersonalLaunchComponent(
            requestedPackage = "org.example.multi",
            canonical = canonicalFrontDoor,
            launcherActivities = listOf(alphabeticalFirst, canonicalFrontDoor),
        )

        assertEquals(canonicalFrontDoor, selected)
    }

    @Test
    fun canonicalComponentFromAnotherPackageCannotReplaceExactRequestedTarget() {
        val requested = component("org.example.requested", "org.example.requested.MainActivity")
        val wrongPackage = component("org.example.other", "org.example.other.MainActivity")

        val selected = preferredPersonalLaunchComponent(
            requestedPackage = "org.example.requested",
            canonical = wrongPackage,
            launcherActivities = listOf(wrongPackage, requested),
        )

        assertEquals(requested, selected)
    }

    @Test
    fun selectionFailsClosedWhenNoExactPackageActivityExists() {
        val selected = preferredPersonalLaunchComponent(
            requestedPackage = "org.example.requested",
            canonical = null,
            launcherActivities = listOf(
                component("org.example.other", "org.example.other.MainActivity"),
            ),
        )

        assertNull(selected)
    }

    private fun component(packageName: String, className: String) =
        LaunchComponentIdentity(packageName, className)
}
