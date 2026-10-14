package ai.hans.standard.devicecontrol.tools

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.diagnostics.ToolFailureCode
import ai.hans.standard.diagnostics.ToolFailureDetail
import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityWindowId
import ai.hans.standard.phone.accessibility.BoundedSemanticUiSnapshotFactory
import ai.hans.standard.phone.accessibility.RawSemanticUiNode
import ai.hans.standard.phone.accessibility.RawSemanticUiSnapshot
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.SnapshotTruncationReason
import ai.hans.standard.phone.accessibility.UiBounds
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

    @Test
    fun revalidationAllowsOnlyTrustedSameContextNewerCompleteFrameAndRemainsOneShot() {
        val store = store()
        val token = store.issue(call(), CORRELATION)
        val newer = snapshot(CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2)))
        var reads = 0
        val valid = store.claimWithSnapshotRevalidation(token, call("first"), CORRELATION) {
            assertEquals(CORRELATION, it)
            reads += 1
            newer
        } as VisualFallbackProofStore.ClaimAttempt.Granted
        assertEquals(newer, valid.claim.validatedSnapshot)
        assertRejected(ToolFailureDetail.PROOF_ALREADY_CLAIMED,
            store.claimWithSnapshotRevalidation(token, call("second"), CORRELATION) { reads += 1; newer })
        assertEquals(1, reads)
        valid.claim.release()
        val again = store.claimWithSnapshotRevalidation(token, call("second"), CORRELATION) { newer }
            as VisualFallbackProofStore.ClaimAttempt.Granted
        again.claim.consume()
        assertRejected(ToolFailureDetail.PROOF_MISSING_OR_CONSUMED,
            store.claimWithSnapshotRevalidation(token, call(), CORRELATION) { newer })
    }

    @Test
    fun revalidationRejectsUnprovenFramesWithoutConsumingOriginalEvidence() {
        val store = store()
        val token = store.issue(call(), CORRELATION)
        listOf(
            null,
            snapshot(CORRELATION.copy(sessionId = AccessibilitySessionId("session-other"))),
            snapshot(CORRELATION.copy(windowId = AccessibilityWindowId(2))),
            snapshot(CORRELATION, incomplete = true),
        ).forEach { frame ->
            assertRejected(ToolFailureDetail.PROOF_CURRENT_SNAPSHOT_MISMATCH,
                store.claimWithSnapshotRevalidation(token, call(), CORRELATION) { frame })
        }
        assertRejected(ToolFailureDetail.PROOF_CURRENT_SNAPSHOT_MISMATCH,
            store.claimWithSnapshotRevalidation(token, call(), CORRELATION) { throw IllegalStateException("private") })
        assertNotNull(store.claim(token, call(), CORRELATION, CORRELATION))
    }

    @Test
    fun slowRevalidationCannotExtendExpiryOrCrossAnEvidenceEpoch() {
        var now = 0L
        val store = store { now }
        val expiring = store.issue(call(), CORRELATION)
        assertRejected(ToolFailureDetail.PROOF_EXPIRED,
            store.claimWithSnapshotRevalidation(expiring, call(), CORRELATION) {
                now = 30_001
                snapshot(CORRELATION)
            })
        val invalidated = store.issue(call(), CORRELATION)
        assertRejected(ToolFailureDetail.PROOF_EVIDENCE_EPOCH_CHANGED,
            store.claimWithSnapshotRevalidation(invalidated, call(), CORRELATION) {
                HansPhoneToolEvidence.invalidateRetainedEvidence()
                snapshot(CORRELATION)
            })
        assertNull(store.claim(invalidated, call(), CORRELATION, CORRELATION))
    }

    @Test
    fun identityAndTurnRejectionsNeverReadOrRebindAFrame() {
        val store = store()
        val token = store.issue(call(), CORRELATION)
        var reads = 0
        listOf(
            Triple(call().copy(threadId = "other"), CORRELATION, ToolFailureDetail.PROOF_THREAD_MISMATCH),
            Triple(call().copy(turnId = "other"), CORRELATION, ToolFailureDetail.PROOF_TURN_MISMATCH),
            Triple(call(), CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2)), ToolFailureDetail.PROOF_CORRELATION_MISMATCH),
        ).forEach { (request, correlation, detail) ->
            assertRejected(detail, store.claimWithSnapshotRevalidation(token, request, correlation) {
                reads += 1
                snapshot(CORRELATION)
            })
        }
        assertEquals(0, reads)
        assertNotNull(store.claim(token, call(), CORRELATION, CORRELATION))
    }

    @Test
    fun screenshotReplacementKeepsOriginalExpiryAndOldLeaseCannotBeUsedAgain() {
        var now = 0L
        var tokenNumber = 0
        val store = VisualFallbackProofStore({ now }, { "fallback:replacement-${++tokenNumber}" })
        val original = store.issue(call(), CORRELATION)
        val newer = CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2))
        val claim = store.claim(original, call("image"), CORRELATION, CORRELATION)!!
        now = 29_999
        val replacement = claim.replaceFor(call("image"), newer) as VisualFallbackProofStore.ReplacementAttempt.Granted
        assertNull(store.claim(original, call(), CORRELATION, CORRELATION))
        assertTrue(claim.replaceFor(call("image"), newer) is VisualFallbackProofStore.ReplacementAttempt.Rejected)
        now = 30_000
        val atBoundary = store.claim(replacement.token, call("gesture"), newer, newer)!!
        atBoundary.release()
        now += 1
        assertRejected(ToolFailureDetail.PROOF_EXPIRED,
            store.claimWithDiagnostic(replacement.token, call("gesture"), newer, newer))
    }

    @Test
    fun screenshotReplacementAfterExpiryOrEpochChangeCannotMintNewAuthority() {
        listOf(false, true).forEach { invalidateEpoch ->
            var now = 0L
            var tokenNumber = 0
            val store = VisualFallbackProofStore({ now }, { "fallback:late-replacement-${++tokenNumber}" })
            val original = store.issue(call(), CORRELATION)
            val claim = store.claim(original, call("image"), CORRELATION, CORRELATION)!!
            if (invalidateEpoch) HansPhoneToolEvidence.invalidateRetainedEvidence() else now = 30_001
            val result = claim.replaceFor(call("image"), CORRELATION)
                as VisualFallbackProofStore.ReplacementAttempt.Rejected
            assertEquals(if (invalidateEpoch) ToolFailureDetail.PROOF_EVIDENCE_EPOCH_CHANGED else ToolFailureDetail.PROOF_EXPIRED,
                result.detail)
            assertEquals(1, tokenNumber)
            assertNull(store.claim(original, call(), CORRELATION, CORRELATION))
        }
    }

    private fun snapshot(
        correlation: UiSnapshotCorrelation,
        incomplete: Boolean = false,
    ): SemanticUiSnapshot = BoundedSemanticUiSnapshotFactory().build(RawSemanticUiSnapshot(
        correlation = correlation,
        displayBounds = UiBounds(0, 0, 100, 100),
        capturedAtElapsedMillis = 1,
        roots = listOf(RawSemanticUiNode(bounds = UiBounds(0, 0, 100, 100))),
        sourceTruncationReasons = if (incomplete) setOf(SnapshotTruncationReason.PLATFORM_NODE_UNAVAILABLE) else emptySet(),
    ))

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
