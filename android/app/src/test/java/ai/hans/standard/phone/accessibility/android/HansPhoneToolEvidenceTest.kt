package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.devicecontrol.tools.VisualFallbackProofStore
import ai.hans.standard.phone.accessibility.AccessibilityCommand
import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityUserApproval
import ai.hans.standard.phone.accessibility.AccessibilityWindowId
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import org.junit.Assert.*
import org.junit.Test

class HansPhoneToolEvidenceTest {
    @Test fun epochReachesMultipleOldVisualProofStoresAndInvalidatesCurrentSession() {
        val owner = Any()
        var cleared = 0
        val session = session { cleared++; true }
        HansAccessibilitySessions.connect(owner)
        assertTrue(HansAccessibilitySessions.publish(owner, session))
        try {
            val stores = List(2) { VisualFallbackProofStore({ 1L }, { "fallback:retained-token" }) }
            val tokens = stores.map { it.issue(CALL, CORRELATION) }
            val before = HansPhoneToolEvidence.epoch()
            HansPhoneToolEvidence.invalidateRetainedEvidence()
            assertTrue(HansPhoneToolEvidence.epoch() > before)
            assertEquals(1, cleared)
            stores.zip(tokens).forEach { (store, token) ->
                assertNull(store.claim(token, CALL, CORRELATION, CORRELATION))
                val fresh = store.issue(CALL, CORRELATION)
                assertNotNull(store.claim(fresh, CALL, CORRELATION, CORRELATION))
                store.clear()
                assertNull(store.claim(fresh, CALL, CORRELATION, CORRELATION))
            }
        } finally { HansAccessibilitySessions.disconnect(owner) }
    }

    @Test fun failedSessionInvalidationIsNotSuccessAndEpochNeverRollsBack() {
        val owner = Any()
        HansAccessibilitySessions.connect(owner)
        assertTrue(HansAccessibilitySessions.publish(owner, session { false }))
        try {
            val before = HansPhoneToolEvidence.epoch()
            assertTrue(runCatching { HansPhoneToolEvidence.invalidateRetainedEvidence() }.isFailure)
            assertTrue(HansPhoneToolEvidence.epoch() > before)
        } finally { HansAccessibilitySessions.disconnect(owner) }
    }

    private fun session(invalidate: () -> Boolean) = object : HansAccessibilitySession {
        override val sessionId = CORRELATION.sessionId
        override fun currentSnapshot(): SemanticUiSnapshot? = null
        override fun invalidateRetainedEvidence(): Boolean = invalidate()
        override fun submit(command: AccessibilityCommand, approval: AccessibilityUserApproval?,
            callback: AccessibilityCommandCallback): Boolean = false
    }

    private companion object {
        val CORRELATION = UiSnapshotCorrelation(AccessibilitySessionId("cross-turn-session"),
            AccessibilityWindowId(1), AccessibilitySnapshotId(1))
        val CALL = DynamicToolCallParams("thread", "turn", "call", "android_ui", "find_ui", "{}")
    }
}
