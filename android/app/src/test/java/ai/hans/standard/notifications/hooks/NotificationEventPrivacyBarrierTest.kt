package ai.hans.standard.notifications.hooks

import org.junit.Assert.*
import org.junit.Test

class NotificationEventPrivacyBarrierTest {
    @Test fun successfulDataClearCannotAcknowledgeUnconfirmedPhysicalOutputStop() {
        var error: String? = null
        assertFalse(NotificationEventPrivacyBarrier.apply({ true }, { false }, { error = it }))
        assertEquals("speech_revocation_unconfirmed", error)
    }

    @Test fun failedDataCommitStillAttemptsPhysicalStopWithoutClaimingPurgeSuccess() {
        var stopCalls = 0
        assertFalse(NotificationEventPrivacyBarrier.apply({ false }, { stopCalls++; true }, {}))
        assertEquals(1, stopCalls)
    }

    @Test fun boundedStopFailureIsVisibleAndNeverPretendsPhysicalRelease() {
        var error: String? = null
        assertFalse(NotificationEventPrivacyBarrier.apply({ true }, { error("fixture failure") }, { error = it }))
        assertEquals("speech_revocation_unconfirmed", error)
    }

    @Test fun ledgerMutationFinishesBeforeOutputWaitAndBothProofsAreRequired() {
        val order = mutableListOf<String>()
        assertTrue(NotificationEventPrivacyBarrier.apply(
            { order += "commit"; true }, { order += "physical-stop"; true }, { fail("unexpected $it") }))
        assertEquals(listOf("commit", "physical-stop"), order)
    }
}
