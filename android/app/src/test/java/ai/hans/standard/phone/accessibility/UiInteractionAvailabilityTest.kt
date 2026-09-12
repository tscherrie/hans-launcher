package ai.hans.standard.phone.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiInteractionAvailabilityTest {
    @Test
    fun platformStateMatrixFailsClosedUnlessUnlockedAndInteractive() {
        assertEquals(
            UiInteractionAvailability.AVAILABLE,
            UiInteractionAvailability.fromPlatformState(
                deviceLocked = false,
                screenInteractive = true,
            ),
        )
        assertEquals(
            UiInteractionAvailability.DEVICE_LOCKED,
            UiInteractionAvailability.fromPlatformState(
                deviceLocked = true,
                screenInteractive = true,
            ),
        )
        assertEquals(
            UiInteractionAvailability.SCREEN_NOT_INTERACTIVE,
            UiInteractionAvailability.fromPlatformState(
                deviceLocked = false,
                screenInteractive = false,
            ),
        )
        assertEquals(
            UiInteractionAvailability.DEVICE_LOCKED_AND_SCREEN_NOT_INTERACTIVE,
            UiInteractionAvailability.fromPlatformState(
                deviceLocked = true,
                screenInteractive = false,
            ),
        )
        assertTrue(UiInteractionAvailability.AVAILABLE.isAvailable)
        assertFalse(UiInteractionAvailability.STATE_UNAVAILABLE.isAvailable)
        assertEquals(
            UiInteractionAvailability.STATE_UNAVAILABLE,
            UiInteractionAvailabilityProbe.FAIL_CLOSED.current(),
        )
    }
}
