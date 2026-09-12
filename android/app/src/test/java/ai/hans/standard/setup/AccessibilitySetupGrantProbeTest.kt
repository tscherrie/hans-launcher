package ai.hans.standard.setup

import ai.hans.standard.phone.capabilities.AccessibilityGrantState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySetupGrantProbeTest {
    @Test
    fun durableGrantVerifiesAccessButDoesNotInventALiveTest() {
        val access = accessibilitySetupGrantProbe(AccessibilityGrantState.GRANTED)
        assertTrue(access.verified)
        assertFalse(access.transient)
        assertEquals("accessibility_access_granted", access.detailCode)

        val live = accessibilitySetupLiveProbe(
            AccessibilityGrantState.GRANTED,
            serviceConnected = false,
            sessionAvailable = false,
            receiptState = AccessibilitySetupReceiptState.NOT_ARMED,
        )
        assertFalse(live.verified)
        assertTrue(live.transient)
    }

    @Test
    fun unreadableGrantIsTransientButAnEmptyGrantListIsNot() {
        val unknown = accessibilitySetupGrantProbe(AccessibilityGrantState.UNKNOWN)
        assertFalse(unknown.verified)
        assertTrue(unknown.transient)
        assertEquals("accessibility_access_temporarily_unavailable", unknown.detailCode)

        val revoked = accessibilitySetupGrantProbe(AccessibilityGrantState.NOT_GRANTED)
        assertFalse(revoked.verified)
        assertFalse(revoked.transient)
        assertEquals("accessibility_access_missing", revoked.detailCode)
    }

    @Test
    fun anOldSuccessfulReceiptCannotProveACurrentlyDisconnectedLiveTest() {
        listOf(false to false, true to false, false to true).forEach { (connected, session) ->
            val live = accessibilitySetupLiveProbe(
                AccessibilityGrantState.GRANTED,
                serviceConnected = connected,
                sessionAvailable = session,
                receiptState = AccessibilitySetupReceiptState.VERIFIED,
            )
            assertFalse(live.verified)
            assertTrue(live.transient)
        }
    }

    @Test
    fun liveTestNeedsBothAnActualReceiptAndAUsableGrantedSession() {
        val verified = accessibilitySetupLiveProbe(
            AccessibilityGrantState.GRANTED,
            serviceConnected = true,
            sessionAvailable = true,
            receiptState = AccessibilitySetupReceiptState.VERIFIED,
        )
        assertTrue(verified.verified)
        assertFalse(verified.transient)

        val unproven = accessibilitySetupLiveProbe(
            AccessibilityGrantState.GRANTED,
            serviceConnected = true,
            sessionAvailable = true,
            receiptState = AccessibilitySetupReceiptState.NOT_ARMED,
        )
        assertFalse(unproven.verified)

        listOf(AccessibilityGrantState.NOT_GRANTED, AccessibilityGrantState.UNKNOWN).forEach {
            val rejected = accessibilitySetupLiveProbe(
                it,
                serviceConnected = true,
                sessionAvailable = true,
                receiptState = AccessibilitySetupReceiptState.VERIFIED,
            )
            assertFalse(rejected.verified)
            assertEquals(it == AccessibilityGrantState.UNKNOWN, rejected.transient)
        }
    }
}
