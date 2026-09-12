package ai.hans.standard.automations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationUnlockTransitionGateTest {
    @Test
    fun swipeOnlyKeyguardStillBlocksUiAutomation() {
        assertFalse(
            AndroidKeyguardUnlockPolicy.isUiUnlocked(
                deviceLocked = false,
                keyguardLocked = true,
            ),
        )
        assertFalse(
            AndroidKeyguardUnlockPolicy.isUiUnlocked(
                deviceLocked = true,
                keyguardLocked = true,
            ),
        )
        assertTrue(
            AndroidKeyguardUnlockPolicy.isUiUnlocked(
                deviceLocked = false,
                keyguardLocked = false,
            ),
        )
    }

    @Test
    fun coldUnlockedServiceConnectionAndOnlyLockedToUnlockedEdgesDispatch() {
        val gate = AutomationUnlockTransitionGate()

        assertTrue(gate.onServiceConnected(unlocked = true))
        assertFalse(gate.onPotentialStateChange(unlocked = true))
        assertFalse(gate.onPotentialStateChange(unlocked = false))
        assertTrue(gate.onPotentialStateChange(unlocked = true))
        assertFalse(gate.onPotentialStateChange(unlocked = true))
    }

    @Test
    fun coldLockedServiceWaitsForTheFirstUnlock() {
        val gate = AutomationUnlockTransitionGate()

        assertFalse(gate.onServiceConnected(unlocked = false))
        assertFalse(gate.onPotentialStateChange(unlocked = false))
        assertTrue(gate.onPotentialStateChange(unlocked = true))
    }

    @Test
    fun rejectedDispatchIsImmediatelyRetryableWhileAcceptedDispatchIsDebounced() {
        var now = 100L
        var calls = 0
        val debouncer = AutomationUnlockDispatchDebouncer(
            elapsedRealtime = { now },
            debounceMillis = 1_500L,
        )

        assertFalse(debouncer.attempt { calls += 1; false })
        assertTrue(debouncer.attempt { calls += 1; true })
        assertTrue(debouncer.attempt { calls += 1; error("must be coalesced") })
        assertEquals(2, calls)

        now += 1_500L
        assertTrue(debouncer.attempt { calls += 1; true })
        assertEquals(3, calls)
    }
}
