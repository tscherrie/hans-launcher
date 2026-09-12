package ai.hans.standard.voice.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidResponseReadyHapticsTest {
    @Test
    fun knownAllowedPolicyPulsesOnceWithoutChangingAnySettings() {
        val platform = FakePlatform()
        val haptics = AndroidResponseReadyHaptics(platform)
        assertTrue(haptics.probe()!!.permitsPulse)
        assertEquals(0, platform.pulses)
        haptics.pulse()
        assertEquals(1, platform.pulses)
    }

    @Test
    fun missingPermissionHardwareDisabledSystemHapticsSilentOrDndNeverPulses() {
        listOf(
            allowed.copy(permissionGranted = false),
            allowed.copy(hasVibrator = false),
            allowed.copy(systemHapticsEnabled = false),
            allowed.copy(ringerAllowsHaptics = false),
            allowed.copy(interruptionsAllowed = false),
        ).forEach { policy ->
            val platform = FakePlatform(policy)
            val haptics = AndroidResponseReadyHaptics(platform)
            assertFalse(haptics.probe()!!.permitsPulse)
            haptics.pulse()
            assertEquals(0, platform.pulses)
        }
    }

    @Test
    fun unknownAndFailingPolicyAreFailClosed() {
        listOf(
            allowed.copy(systemHapticsEnabled = null),
            allowed.copy(ringerAllowsHaptics = null),
            allowed.copy(interruptionsAllowed = null),
        ).forEach { policy ->
            val platform = FakePlatform(policy)
            AndroidResponseReadyHaptics(platform).pulse()
            assertEquals(0, platform.pulses)
        }
        val inaccessible = object : ResponseReadyHapticPlatform {
            override fun readPolicy(): ResponseReadyHapticPolicy = throw SecurityException("unavailable")
            override fun pulse() = error("must not execute")
        }
        val haptics = AndroidResponseReadyHaptics(inaccessible)
        assertNull(haptics.probe())
        haptics.pulse()
    }

    @Test
    fun policyIsReadAgainForEveryPulseAndPlatformFailureNeverEscapes() {
        val platform = FakePlatform()
        val haptics = AndroidResponseReadyHaptics(platform)
        haptics.pulse()
        platform.policy = allowed.copy(interruptionsAllowed = false)
        haptics.pulse()
        assertEquals(1, platform.pulses)
        AndroidResponseReadyHaptics(object : ResponseReadyHapticPlatform {
            override fun readPolicy() = allowed
            override fun pulse() { throw SecurityException("vibrator unavailable") }
        }).pulse()
    }

    private class FakePlatform(var policy: ResponseReadyHapticPolicy = allowed) : ResponseReadyHapticPlatform {
        var pulses = 0
        override fun readPolicy() = policy
        override fun pulse() { pulses += 1 }
    }

    companion object {
        private val allowed = ResponseReadyHapticPolicy(true, true, true, true, true)
    }
}
