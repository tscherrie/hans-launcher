package ai.hans.standard.phone.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayMotionPolicyTest {
    @Test
    fun automaticMp01KeepsHomeStaticAndExplicitLiveFeedbackAnimated() {
        val decision = decide(identity = mp01)
        assertTrue(decision.isEink)
        assertFalse(decision.animateHome)
        assertTrue(decision.animateLiveRecording)
    }

    @Test
    fun knownHardwareIdentityIsCaseInsensitive() {
        assertTrue(decide(identity = AndroidDeviceIdentity("along", "minimal_phone", "mp01", "mp01")).isEink)
    }

    @Test
    fun everyIdentityFieldIsRequiredAndNoOtherDeviceIsClaimedToBeEink() {
        listOf(
            mp01.copy(manufacturer = "other"),
            mp01.copy(brand = "other"),
            mp01.copy(model = "MP02"),
            mp01.copy(device = "other"),
            AndroidDeviceIdentity("", "", "", ""),
        ).forEach { identity ->
            val decision = decide(identity = identity)
            assertFalse(identity.toString(), decision.isEink)
            assertTrue(identity.toString(), decision.animateHome)
        }
    }

    @Test
    fun vendorPermissionAndSocketAvailabilityCannotTurnAnEinkPanelIntoANormalDisplay() {
        val controller = Mp01DisplayController(
            evidenceSource = Mp01DisplayTrustEvidenceSource { Mp01DisplayTrustEvidence(mp01, false) },
            transport = object : Mp01EinkSocketTransport {
                override fun canConnect(): Boolean = error("Untrusted vendor control must not be probed")
                override fun send(command: Byte) = error("UI motion must not control the panel")
            },
        )
        assertFalse(controller.probe().available)
        assertTrue(decide(identity = mp01).isEink)
        assertFalse(decide(identity = mp01).animateHome)
    }

    @Test
    fun automaticOtherDeviceUsesNormalMotionWithoutClaimingKnownPanelTechnology() {
        val decision = decide()
        assertFalse(decision.isEink)
        assertTrue(decision.animateHome)
        assertTrue(decision.animateLiveRecording)
    }

    @Test
    fun explicitEinkSupportsUnknownDevicesWithoutManufacturerHeuristics() {
        val decision = decide(mode = DisplayMotionMode.E_INK)
        assertTrue(decision.isEink)
        assertFalse(decision.animateHome)
        assertTrue(decision.animateLiveRecording)
    }

    @Test
    fun explicitNormalDisplayOverridesAutomaticHardwareClassification() {
        val decision = decide(mode = DisplayMotionMode.STANDARD, identity = mp01)
        assertFalse(decision.isEink)
        assertTrue(decision.animateHome)
    }

    @Test
    fun systemAnimationsOffWinsOverEveryDisplayModeIncludingLiveFeedback() {
        DisplayMotionMode.entries.forEach { mode ->
            val decision = decide(mode = mode, animations = false)
            assertFalse(decision.animateHome)
            assertFalse(decision.animateLiveRecording)
        }
    }

    @Test
    fun pausedOrBackgroundScreenHasNoHomeOrLiveAnimation() {
        DisplayMotionMode.entries.forEach { mode ->
            val decision = decide(mode = mode, resumed = false)
            assertFalse(decision.animateHome)
            assertFalse(decision.animateLiveRecording)
        }
    }

    @Test
    fun screenOffOrLostWindowFocusStopsMotionEvenIfActivityStillReportsResumed() {
        val decision = decide(windowFocused = false)
        assertFalse(decision.animateHome)
        assertFalse(decision.animateLiveRecording)
    }

    @Test
    fun persistedModesRoundTripAndAnAbsentOrUnknownValueKeepsAutomaticDefault() {
        DisplayMotionMode.entries.forEach { mode ->
            assertEquals(mode, DisplayMotionMode.fromStorage(mode.storageValue))
        }
        assertEquals(DisplayMotionMode.AUTOMATIC, DisplayMotionMode.fromStorage(null))
        assertEquals(DisplayMotionMode.AUTOMATIC, DisplayMotionMode.fromStorage("future-mode"))
    }

    private fun decide(
        mode: DisplayMotionMode = DisplayMotionMode.AUTOMATIC,
        identity: AndroidDeviceIdentity = normal,
        animations: Boolean = true,
        resumed: Boolean = true,
        windowFocused: Boolean = true,
    ) = DisplayMotionPolicy.decide(mode, identity, animations, resumed, windowFocused)

    private companion object {
        val mp01 = AndroidDeviceIdentity("ALONG", "Minimal_Phone", "MP01", "MP01")
        val normal = AndroidDeviceIdentity("Google", "google", "Pixel", "test-device")
    }
}
