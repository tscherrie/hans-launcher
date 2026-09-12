package ai.hans.standard.phone.accessibility.resume

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.phone.accessibility.UiInteractionAvailability
import java.util.ArrayDeque
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTaskContinuationCoordinatorTest {
    @Test
    fun checkpointPersistsOnlyContentFreeIdentityAndDeduplicatesExactCall() {
        val storage = FakeStorage()
        val journal = journal(storage)
        val call = call(arguments = JSONObject().put("value", "private message").toString())

        val first = journal.checkpoint(call, UiInteractionAvailability.DEVICE_LOCKED)
        val duplicate = journal.checkpoint(call, UiInteractionAvailability.DEVICE_LOCKED)

        assertEquals(first, duplicate)
        assertTrue(first is UiTaskContinuationCheckpoint.Persisted)
        val record = storage.document.records.single()
        assertEquals(UiTaskContinuationStatus.WAITING_USER_PRESENT, record.status)
        assertEquals(UiTaskContinuationBlockedReason.DEVICE_LOCKED, record.blockedReason)
        assertEquals(call.threadId, record.identity.threadId)
        assertEquals(call.turnId, record.identity.turnId)
        assertEquals(call.callId, record.identity.callId)
        val encoded = UiTaskContinuationCodec.encode(storage.document)
        assertFalse(encoded.contains("private message"))
        assertFalse(encoded.contains("value"))
    }

    @Test
    fun sameOpaqueCallWithDifferentArgumentsFailsClosed() {
        val storage = FakeStorage()
        val journal = journal(storage)

        assertTrue(
            journal.checkpoint(call(arguments = "{}"), UiInteractionAvailability.DEVICE_LOCKED) is
                UiTaskContinuationCheckpoint.Persisted,
        )
        assertEquals(
            UiTaskContinuationCheckpoint.NotPersisted,
            journal.checkpoint(
                call(arguments = JSONObject().put("changed", true).toString()),
                UiInteractionAvailability.DEVICE_LOCKED,
            ),
        )
        assertEquals(1, storage.document.records.size)
    }

    @Test
    fun unverifiableUiStateAndMalformedArgumentsAreNeverCheckpointed() {
        val journal = journal(FakeStorage())

        assertEquals(
            UiTaskContinuationCheckpoint.NotPersisted,
            journal.checkpoint(call(), UiInteractionAvailability.STATE_UNAVAILABLE),
        )
        assertEquals(
            UiTaskContinuationCheckpoint.NotPersisted,
            journal.checkpoint(call(arguments = "[]"), UiInteractionAvailability.DEVICE_LOCKED),
        )
    }

    @Test
    fun unavailableDispatcherDoesNotClaimWaitingWork() {
        val storage = FakeStorage()
        val journal = journal(storage)
        journal.checkpoint(call(), UiInteractionAvailability.SCREEN_NOT_INTERACTIVE)
        val coordinator = UiTaskContinuationCoordinator(
            journal,
            UiTaskContinuationDispatcher.NONE,
        )

        assertEquals(
            UiTaskContinuationReconcileResult.DISPATCH_UNAVAILABLE,
            coordinator.onUserPresent(),
        )
        assertEquals(
            UiTaskContinuationStatus.WAITING_USER_PRESENT,
            storage.document.records.single().status,
        )
    }

    @Test
    fun acceptedTransportFinalizesOnceAndNeverBlindlyReplaysArguments() {
        val storage = FakeStorage()
        val journal = journal(storage)
        journal.checkpoint(
            call(arguments = JSONObject().put("text", "do not persist").toString()),
            UiInteractionAvailability.DEVICE_LOCKED,
        )
        val requests = mutableListOf<UiTaskContinuationDispatchRequest>()
        val coordinator = UiTaskContinuationCoordinator(
            journal,
            dispatcher { request ->
                requests += request
                UiTaskContinuationDispatchResult.AcceptedByTransport
            },
        )

        assertEquals(UiTaskContinuationReconcileResult.ACCEPTED, coordinator.onUserPresent())
        assertEquals(UiTaskContinuationReconcileResult.NOTHING_WAITING, coordinator.onUserPresent())
        assertEquals(1, requests.size)
        assertFalse(requests.single().continuationContext.contains("do not persist"))
        assertTrue(requests.single().continuationContext.contains("Freshly inspect"))
        assertEquals(
            UiTaskContinuationStatus.FINAL,
            storage.document.records.single().status,
        )
    }

    @Test
    fun definitePreTransportFailureReleasesButAmbiguityNeedsManualReview() {
        val storage = FakeStorage()
        val journal = journal(storage)
        journal.checkpoint(call(), UiInteractionAvailability.DEVICE_LOCKED)
        val results = ArrayDeque(
            listOf(
                UiTaskContinuationDispatchResult.RejectedBeforeTransport,
                UiTaskContinuationDispatchResult.OutcomeAmbiguous,
            ),
        )
        val coordinator = UiTaskContinuationCoordinator(
            journal,
            dispatcher { results.removeFirst() },
        )

        assertEquals(
            UiTaskContinuationReconcileResult.RELEASED_FOR_RETRY,
            coordinator.onUserPresent(),
        )
        assertEquals(
            UiTaskContinuationStatus.WAITING_USER_PRESENT,
            storage.document.records.single().status,
        )
        assertEquals(UiTaskContinuationReconcileResult.MANUAL_REVIEW, coordinator.onUserPresent())
        assertEquals(
            UiTaskContinuationStatus.MANUAL_REVIEW,
            storage.document.records.single().status,
        )
        assertEquals(UiTaskContinuationReconcileResult.NOTHING_WAITING, coordinator.onUserPresent())
    }

    @Test
    fun processDeathConvertsClaimToManualReviewAndOldTokenCannotMutateIt() {
        val storage = FakeStorage()
        val journal = journal(storage)
        journal.checkpoint(call(), UiInteractionAvailability.DEVICE_LOCKED)
        val claim = checkNotNull(journal.claimOldestWaiting())

        assertEquals(1, journal.recoverClaimedAfterProcessDeath())
        assertEquals(
            UiTaskContinuationStatus.MANUAL_REVIEW,
            storage.document.records.single().status,
        )
        assertFalse(journal.releaseAfterDefinitePreTransportFailure(claim))
        assertFalse(journal.completeClaim(claim, UiTaskContinuationStatus.FINAL))
    }

    @Test
    fun staleWaitingCheckpointExpiresInsteadOfCreatingImmortalBacklog() {
        val stale = record("call-stale").copy(
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val storage = FakeStorage(UiTaskContinuationDocument(listOf(stale)))
        val journal = UiTaskContinuationJournal(
            storage = storage,
            nowMillis = { 24L * 60L * 60L * 1_000L + 2L },
            claimTokens = { "claim-token" },
        )

        assertEquals(1, journal.expireStaleWaiting())
        assertEquals(
            UiTaskContinuationStatus.MANUAL_REVIEW,
            storage.document.records.single().status,
        )
        assertFalse(journal.hasWaiting())
    }

    @Test
    fun codecRoundTripsAndRejectsUnknownOrUnboundedState() {
        val storage = FakeStorage()
        val journal = journal(storage)
        journal.checkpoint(call(), UiInteractionAvailability.DEVICE_LOCKED)
        val encoded = UiTaskContinuationCodec.encode(storage.document)

        assertEquals(storage.document, UiTaskContinuationCodec.decode(encoded))
        assertThrows(IllegalArgumentException::class.java) {
            UiTaskContinuationCodec.decode(JSONObject(encoded).put("unexpected", true).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            UiTaskContinuationDocument(
                List(UiTaskContinuationBounds.MAX_RECORDS + 1) { index ->
                    record("call-$index")
                },
            )
        }
    }

    @Test
    fun continuationIdentityIsStableButArgumentFingerprintChanges() {
        val first = checkNotNull(UiTaskContinuationIdentity.from(call(arguments = "{}")))
        val second = checkNotNull(
            UiTaskContinuationIdentity.from(
                call(arguments = JSONObject().put("x", true).toString()),
            ),
        )

        assertEquals(first.continuationId, second.continuationId)
        assertNotEquals(first.argumentFingerprint, second.argumentFingerprint)
        assertEquals(64, first.continuationId.length)
    }

    private fun dispatcher(
        dispatch: (UiTaskContinuationDispatchRequest) -> UiTaskContinuationDispatchResult,
    ): UiTaskContinuationDispatcher = object : UiTaskContinuationDispatcher {
        override fun isReady(): Boolean = true

        override fun dispatch(
            request: UiTaskContinuationDispatchRequest,
        ): UiTaskContinuationDispatchResult = dispatch(request)
    }

    private fun journal(storage: FakeStorage) = UiTaskContinuationJournal(
        storage = storage,
        nowMillis = { 42L },
        claimTokens = { "claim-token" },
    )

    private fun call(arguments: String = "{}") = DynamicToolCallParams(
        threadId = "thread-1",
        turnId = "turn-1",
        callId = "call-1",
        namespace = UiTaskContinuationBounds.ACCESSIBILITY_NAMESPACE,
        tool = "inspect_ui",
        argumentsJson = arguments,
    )

    private fun record(callId: String): UiTaskContinuationRecord {
        val identity = checkNotNull(
            UiTaskContinuationIdentity.from(
                call().copy(callId = callId),
            ),
        )
        return UiTaskContinuationRecord(
            identity = identity,
            blockedReason = UiTaskContinuationBlockedReason.DEVICE_LOCKED,
            status = UiTaskContinuationStatus.WAITING_USER_PRESENT,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
    }

    private class FakeStorage(
        initial: UiTaskContinuationDocument = UiTaskContinuationDocument(),
    ) : UiTaskContinuationStorage {
        var document = initial
            private set

        override fun read(): UiTaskContinuationDocument = document

        override fun <T> mutate(
            transform: (UiTaskContinuationDocument) -> UiTaskContinuationMutation<T>,
        ): T {
            val mutation = transform(document)
            document = mutation.document
            return mutation.result
        }
    }
}
