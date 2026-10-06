package ai.hans.standard.devicecontrol.tools

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.diagnostics.ToolFailureCode
import ai.hans.standard.diagnostics.ToolFailureDetail
import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityWindowId
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import ai.hans.standard.phone.accessibility.android.HansPhoneToolEvidence
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualFallbackProofStoreTest {
    @Test
    fun expiryIsKnownOnlyWhileProofExistsAndExactBoundaryIsUnchanged() {
        var now = 0L
        val store = store { now }
        val token = store.issue(call(), CORRELATION)
        now = 30_000
        val atBoundary = store.claim(token, call("at-boundary"), CORRELATION, CORRELATION)
        assertNotNull(atBoundary)
        atBoundary!!.release()
        now += 1
        assertRejected(ToolFailureDetail.PROOF_EXPIRED, store.claimWithDiagnostic(token, call(), CORRELATION, CORRELATION))
        assertRejected(ToolFailureDetail.PROOF_MISSING_OR_CONSUMED, store.claimWithDiagnostic(token, call(), CORRELATION, CORRELATION))
    }

    @Test
    fun wrongContextHasPreciseReasonWithoutConsumingValidProof() {
        val store = store()
        val token = store.issue(call(), CORRELATION)
        val newer = CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2))
        assertRejected(ToolFailureDetail.PROOF_THREAD_MISMATCH, store.claimWithDiagnostic(token, call().copy(threadId = "other-thread"), CORRELATION, CORRELATION))
        assertRejected(ToolFailureDetail.PROOF_TURN_MISMATCH, store.claimWithDiagnostic(token, call().copy(turnId = "other-turn"), CORRELATION, CORRELATION))
        assertRejected(ToolFailureDetail.PROOF_CORRELATION_MISMATCH, store.claimWithDiagnostic(token, call(), newer, CORRELATION))
        assertRejected(ToolFailureDetail.PROOF_CURRENT_SNAPSHOT_MISMATCH, store.claimWithDiagnostic(token, call(), CORRELATION, newer))
        assertNotNull(store.claim(token, call(), CORRELATION, CORRELATION))
    }

    @Test
    fun activeClaimRejectionReleaseAndConsumeRemainOneShot() {
        val store = store()
        val token = store.issue(call(), CORRELATION)
        val first = store.claim(token, call("first"), CORRELATION, CORRELATION)!!
        assertRejected(ToolFailureDetail.PROOF_ALREADY_CLAIMED, store.claimWithDiagnostic(token, call("second"), CORRELATION, CORRELATION))
        first.release()
        val second = store.claim(token, call("second"), CORRELATION, CORRELATION)!!
        first.consume() // An old lease cannot consume a replacement claim.
        assertRejected(ToolFailureDetail.PROOF_ALREADY_CLAIMED, store.claimWithDiagnostic(token, call("third"), CORRELATION, CORRELATION))
        second.consume()
        second.release()
        assertRejected(ToolFailureDetail.PROOF_MISSING_OR_CONSUMED, store.claimWithDiagnostic(token, call(), CORRELATION, CORRELATION))
        assertNull(store.claim(token, call(), CORRELATION, CORRELATION))
    }

    @Test
    fun simultaneousClaimsHaveExactlyOneWinnerAndNoProofReuse() {
        val store = store()
        val token = store.issue(call(), CORRELATION)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val attempts = (1..8).map { index -> executor.submit<VisualFallbackProofStore.ClaimAttempt> {
                check(start.await(5, TimeUnit.SECONDS))
                store.claimWithDiagnostic(token, call("call-$index"), CORRELATION, CORRELATION)
            } }
            start.countDown()
            val results = attempts.map { it.get(5, TimeUnit.SECONDS) }
            val winner = results.filterIsInstance<VisualFallbackProofStore.ClaimAttempt.Granted>().single()
            assertEquals(7, results.filterIsInstance<VisualFallbackProofStore.ClaimAttempt.Rejected>().size)
            results.filterIsInstance<VisualFallbackProofStore.ClaimAttempt.Rejected>().forEach {
                assertRejected(ToolFailureDetail.PROOF_ALREADY_CLAIMED, it)
            }
            winner.claim.consume()
            assertNull(store.claim(token, call(), CORRELATION, CORRELATION))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun evictionClearAndPreviouslyPurgedExpiryAreNotMisdiagnosedAsConsumption() {
        var now = 0L
        var tokenNumber = 0
        val store = VisualFallbackProofStore({ now }, { "fallback:bounded-${++tokenNumber}" }, maxEntries = 1)
        val evicted = store.issue(call(), CORRELATION)
        val current = store.issue(call(), CORRELATION)
        assertRejected(ToolFailureDetail.PROOF_MISSING_OR_CONSUMED, store.claimWithDiagnostic(evicted, call(), CORRELATION, CORRELATION))
        now = 30_001
        store.issue(call(), CORRELATION) // Existing expiry purge discards the evidence, no tombstone.
        assertRejected(ToolFailureDetail.PROOF_MISSING_OR_CONSUMED, store.claimWithDiagnostic(current, call(), CORRELATION, CORRELATION))
        val cleared = store.issue(call(), CORRELATION)
        store.clear()
        assertRejected(ToolFailureDetail.PROOF_MISSING_OR_CONSUMED, store.claimWithDiagnostic(cleared, call(), CORRELATION, CORRELATION))
    }

    @Test
    fun evidenceEpochChangeIsKnownOnlyForProofPresentDuringInvalidation() {
        val store = store()
        val token = store.issue(call(), CORRELATION)
        HansPhoneToolEvidence.invalidateRetainedEvidence()
        assertRejected(ToolFailureDetail.PROOF_EVIDENCE_EPOCH_CHANGED, store.claimWithDiagnostic(token, call(), CORRELATION, CORRELATION))
        assertRejected(ToolFailureDetail.PROOF_MISSING_OR_CONSUMED, store.claimWithDiagnostic(token, call(), CORRELATION, CORRELATION))
    }

    private fun assertRejected(detail: ToolFailureDetail, attempt: VisualFallbackProofStore.ClaimAttempt) {
        assertTrue(attempt is VisualFallbackProofStore.ClaimAttempt.Rejected)
        val rejected = attempt as VisualFallbackProofStore.ClaimAttempt.Rejected
        assertEquals(detail, rejected.detail)
        assertEquals(ToolFailureCode.SEMANTIC_FALLBACK_PROOF_REQUIRED, rejected.diagnostic.code)
        assertEquals(detail, rejected.diagnostic.detail)
        assertTrue(rejected.diagnostic.toString().none { it == ':' })
    }

    private fun store(clock: () -> Long = { 0L }) = VisualFallbackProofStore(clock, { "fallback:proof-diagnostic" })

    private fun call(id: String = "call") = DynamicToolCallParams(
        "thread", "turn", id, "android_ui", "visual_gesture_fallback", "{}",
    )

    companion object {
        private val CORRELATION = UiSnapshotCorrelation(
            AccessibilitySessionId("session-test"), AccessibilityWindowId(1), AccessibilitySnapshotId(1),
        )
    }
}
