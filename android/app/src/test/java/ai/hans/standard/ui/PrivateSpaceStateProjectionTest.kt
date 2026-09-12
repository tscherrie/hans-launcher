package ai.hans.standard.ui

import ai.hans.standard.phone.capabilities.LaunchProfileState
import ai.hans.standard.phone.capabilities.LaunchProfileType
import ai.hans.standard.phone.capabilities.PrivateSpaceAvailability
import ai.hans.standard.phone.capabilities.PrivateSpaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSpaceStateProjectionTest {
    @Test
    fun accessibleProfileProjectsOnlyItsOpaqueHandleAndLiveLockState() {
        val opaqueId = "profile_0123456789abcdef0123456789abcdef"
        val state = privateSpaceUiState(
            snapshot = PrivateSpaceSnapshot(
                availability = PrivateSpaceAvailability.AVAILABLE,
                profile = LaunchProfileState(
                    profileId = opaqueId,
                    type = LaunchProfileType.PRIVATE,
                    locked = true,
                ),
                settingsAvailable = true,
            ),
            containerVisible = false,
        )

        assertEquals(opaqueId, state.profileId)
        assertTrue(state.locked)
        assertFalse(state.containerVisible)
        assertTrue(state.settingsAvailable)
        assertTrue(state.profilePresent)
    }

    @Test
    fun absentHomeRoleProjectsNoProfileIdentityAndNoControls() {
        val state = privateSpaceUiState(
            snapshot = PrivateSpaceSnapshot(PrivateSpaceAvailability.HOME_ROLE_REQUIRED),
            containerVisible = true,
        )

        assertNull(state.profileId)
        assertFalse(state.profilePresent)
        assertFalse(state.canChangeLock)
        assertTrue(state.isRelevantToSettings)
    }

    @Test
    fun androidHiddenEntrypointOnlyOverridesHansVisibilityWhileLocked() {
        val snapshot = PrivateSpaceSnapshot(
            availability = PrivateSpaceAvailability.AVAILABLE,
            profile = LaunchProfileState(
                profileId = "profile_0123456789abcdef0123456789abcdef",
                type = LaunchProfileType.PRIVATE,
                locked = true,
            ),
            entrypointHiddenWhenLocked = true,
        )
        val locked = privateSpaceUiState(snapshot, containerVisible = true)
        val unlocked = locked.copy(locked = false)

        assertTrue(locked.containerVisible)
        assertTrue(locked.entrypointHiddenWhenLocked)
        assertFalse(locked.effectiveContainerVisible)
        assertTrue(unlocked.effectiveContainerVisible)
    }
}
