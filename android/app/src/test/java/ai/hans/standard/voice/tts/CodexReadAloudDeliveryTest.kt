package ai.hans.standard.voice.tts

import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexReadAloudDeliveryTest {
    @Test fun sourceCancellationRemovesOnlyMatchingQueuedOutputWithoutStoppingAnotherSource() = Fixture().use { f ->
        f.submit("source-b")
        f.submit("source-a")
        val active = f.ports.single()
        assertTrue(f.cancelThroughObserver(setOf(TtsMessageId("source-a"))))
        assertEquals(0, active.stops)
        assertEquals(0, f.last.queuedCount)
        f.finishCurrent()
        assertEquals(listOf("source-b"), f.spoken)
    }

    @Test fun matchingActiveSourceStopsPhysicallyThenKeepsOtherQueuedOutput() = Fixture().use { f ->
        f.submit("source-a")
        f.submit("source-b")
        val active = f.ports.single()
        assertTrue(f.cancelThroughObserver(setOf(TtsMessageId("source-a"))))
        assertEquals(1, active.stops)
        assertEquals(listOf("source-a", "source-b"), f.spoken)
        assertEquals(0, f.ports.last().stops)
    }

    @Test fun matchingConnectingSourceCannotFinishPreparationAfterSourceRemoval() = Fixture().use { f ->
        f.onConstruction = { if (f.ports.size == 1) f.ports.last().emitReady = false }
        f.submit("source-a")
        f.submit("source-b")
        val old = f.ports.single()
        assertEquals(CodexReadAloudDeliveryPhase.CONNECTING, f.last.phase)
        assertTrue(f.cancelThroughObserver(setOf(TtsMessageId("source-a"))))
        old.emit(CodexReadAloudDeliveryPhase.READY)
        f.drain()
        assertEquals(listOf("source-b"), f.spoken)
        assertEquals(1, old.stops)
    }

    @Test fun unconfirmedMatchingStopRetainsOwnerAndDoesNotStartDifferentQueuedSource() = Fixture().use { f ->
        f.submit("source-a")
        f.submit("source-b")
        val old = f.ports.single()
        old.released = false
        assertFalse(f.cancelThroughObserver(setOf(TtsMessageId("source-a"))))
        assertEquals(listOf("source-a"), f.spoken)
        assertFalse(f.last.releaseConfirmed)
        old.released = true
        assertTrue(f.cancelThroughObserver(setOf(TtsMessageId("source-a"))))
        assertEquals(listOf("source-a", "source-b"), f.spoken)
    }
    @Test fun completionReceiptRequiresNaturalOutputAndPositiveReleaseAndIsCorrelatedOnce() = Fixture().use { f ->
        f.submit("notification-one")
        assertEquals(listOf(TtsPlaybackEvent.MessageQueued(TtsMessageId("notification-one")),
            TtsPlaybackEvent.MessageStarted(TtsMessageId("notification-one"))), f.events)
        val old = f.ports.single()
        f.finishCurrent()
        old.emit(CodexReadAloudDeliveryPhase.STOPPED)
        old.emit(CodexReadAloudDeliveryPhase.SPEAKING)
        f.drain()
        assertEquals(listOf(TtsPlaybackEvent.MessageCompleted(TtsMessageId("notification-one"))),
            f.events.filterIsInstance<TtsPlaybackEvent.MessageCompleted>())
        assertTrue(f.events.filterIsInstance<TtsPlaybackEvent.MessageDropped>().isEmpty())
    }

    @Test fun ownerStopOrOrdinaryStoppedNeverCountsAsSpoken() {
        listOf(false, true).forEach { explicitStop -> Fixture().use { f ->
            f.submit("notification")
            val port = f.ports.single()
            if (explicitStop) {
                f.delivery.stop()
                f.drain()
                port.natural = true // Stale/spurious completion cannot override owner cancellation.
            }
            port.emit(CodexReadAloudDeliveryPhase.STOPPED)
            f.drain()
            assertTrue(f.events.filterIsInstance<TtsPlaybackEvent.MessageCompleted>().isEmpty())
            assertEquals(1, f.events.filterIsInstance<TtsPlaybackEvent.MessageDropped>().size)
        } }
    }

    @Test fun failedPlaybackEmitsOneFailureAndNeverCompletionFromLateEvents() = Fixture().use { f ->
        f.submit("notification")
        val port = f.ports.single()
        port.emit(CodexReadAloudDeliveryPhase.FAILED)
        f.drain()
        port.natural = true
        port.emit(CodexReadAloudDeliveryPhase.STOPPED)
        port.emit(CodexReadAloudDeliveryPhase.FAILED)
        f.drain()
        assertEquals(1, f.events.filterIsInstance<TtsPlaybackEvent.MessageFailed>().size)
        assertTrue(f.events.filterIsInstance<TtsPlaybackEvent.MessageCompleted>().isEmpty())
    }

    @Test fun guardRevokedBeforeConstructionDropsWithoutOpeningVoice() = Fixture().use { f ->
        var allowed = true
        f.onSnapshot = { if (it.phase == CodexReadAloudDeliveryPhase.CONNECTING) allowed = false }
        f.delivery.submitGuarded(revision("notification"), f.epoch) { allowed }
        f.drain()
        assertTrue(f.ports.isEmpty())
        assertEquals(listOf(TtsPlaybackEvent.MessageDropped(TtsMessageId("notification"),
            TtsMessageDropReason.ADMISSION_REJECTED)),
            f.events.filterIsInstance<TtsPlaybackEvent.MessageDropped>())
        assertTrue(f.events.filterIsInstance<TtsPlaybackEvent.MessageCompleted>().isEmpty())
    }

    @Test fun guardRevokedDuringFactoryStopsPreparedOwnerWithoutSpeaking() = Fixture().use { f ->
        var allowed = true
        f.onConstruction = { allowed = false }
        f.delivery.submitGuarded(revision("notification"), f.epoch) { allowed }
        f.drain()
        assertEquals(1, f.ports.size)
        assertEquals(0, f.ports.single().preparations)
        assertTrue(f.spoken.isEmpty())
        assertEquals(1, f.ports.single().closes)
        assertEquals(1, f.events.filterIsInstance<TtsPlaybackEvent.MessageDropped>().size)
    }

    @Test fun guardIsCheckedAgainAtSpeakBoundaryAfterReadyObserver() = Fixture().use { f ->
        var allowed = true
        f.onSnapshot = { if (it.phase == CodexReadAloudDeliveryPhase.READY && it.messageId != null) allowed = false }
        f.delivery.submitGuarded(revision("notification"), f.epoch) { allowed }
        f.drain()
        assertTrue(f.spoken.isEmpty())
        assertEquals(1, f.events.filterIsInstance<TtsPlaybackEvent.MessageDropped>().size)
        assertTrue(f.events.filterIsInstance<TtsPlaybackEvent.MessageStarted>().isEmpty())
    }

    @Test fun validGuardWithRejectedPreparationFailsOnceRatherThanCreatingAnUnboundedLoop() = Fixture().use { f ->
        f.onConstruction = { f.ports.last().acceptPreparation = false }
        f.delivery.submitGuarded(revision("notification"), f.epoch) { true }
        f.drain()
        assertEquals(1, f.ports.size)
        assertEquals(CodexReadAloudDeliveryPhase.FAILED, f.last.phase)
        assertEquals("codex_read_aloud_prepare_rejected", f.last.failureCode)
        assertTrue(f.spoken.isEmpty())
    }

    @Test fun rejectedNewerQueuedRevisionRemovesPreviouslyAdmittedPrivatePayload() = Fixture().use { f ->
        f.submit("first")
        f.submit("revoked", text = "Older private notification")
        assertEquals(1, f.last.queuedCount)
        f.delivery.submitGuarded(revision("revoked", revision = 2), f.epoch) { false }
        f.drain()
        assertEquals(0, f.last.queuedCount)
        f.finishCurrent()
        assertEquals(listOf("first"), f.spoken)
        assertEquals(1, f.ports.size)
        assertEquals(listOf(TtsPlaybackEvent.MessageDropped(TtsMessageId("revoked"),
            TtsMessageDropReason.ADMISSION_REJECTED)),
            f.events.filterIsInstance<TtsPlaybackEvent.MessageDropped>())
    }

    @Test fun failedCloseRetainsBarrierAndAcceptsLatePositiveReleaseEvenAfterOwnerClose() {
        val f = Fixture()
        try {
            f.submit("notification")
            val port = f.ports.single()
            port.released = false
            f.delivery.close()
            f.drain()
            assertEquals(CodexReadAloudDeliveryPhase.FAILED, f.last.phase)
            assertFalse(f.last.releaseConfirmed)
            port.released = true
            port.natural = true
            port.emit(CodexReadAloudDeliveryPhase.FAILED)
            f.drain()
            assertTrue(f.last.releaseConfirmed)
            assertEquals(CodexReadAloudDeliveryPhase.FAILED, f.last.phase)
            assertTrue(f.events.filterIsInstance<TtsPlaybackEvent.MessageCompleted>().isEmpty())
            assertEquals(1, f.ports.size)
        } finally { f.close() }
    }

    @Test fun onlyCompletedVisibleFinalsAreReadByDefault() = Fixture().use { f ->
        f.submit("answer", revision = 1, final = false)
        f.submit("progress", kind = TtsMessageKind.INTERMEDIATE)
        assertTrue(f.ports.isEmpty())
        f.submit("answer", revision = 2, text = "Complete visible answer")
        assertEquals(listOf("Complete visible answer"), f.spoken)
        f.submit("answer", revision = 3, text = "Changed completed answer")
        f.finishCurrent()
        assertEquals(1, f.ports.size)
        assertEquals(listOf("Complete visible answer"), f.spoken)
    }

    @Test fun allMessagesIncludesOnlyCompletedIntermediateMessagesAndRunsSequentially() = Fixture().use { f ->
        f.configure(enabled = true, allowIntermediate = true)
        f.submit("progress", final = false, kind = TtsMessageKind.INTERMEDIATE)
        assertTrue(f.ports.isEmpty())
        f.submit("progress", revision = 2, kind = TtsMessageKind.INTERMEDIATE)
        f.submit("answer")
        assertEquals(listOf("progress"), f.spoken)
        assertEquals(1, f.last.queuedCount)
        val first = f.ports.single()
        f.finishCurrent()
        assertEquals(1, first.barriers)
        assertEquals(1, first.closes)
        assertEquals(listOf("progress", "answer"), f.spoken)
        assertEquals(2, f.ports.size)
    }

    @Test fun maximumEightOutputsIncludesCurrentSpeechAndOverflowCannotReplay() = Fixture().use { f ->
        repeat(12) { f.submit("message-${it + 1}") }
        assertEquals(7, f.last.queuedCount)
        assertEquals(1, f.ports.size)
        repeat(8) { f.finishCurrent() }
        assertEquals((1..8).map { "message-$it" }, f.spoken)
        assertEquals(8, f.ports.size)
        f.submit("message-9", revision = 99)
        assertEquals(8, f.ports.size)
        assertEquals(0, f.last.queuedCount)
        assertTrue(f.ports.all { it.closes == 1 })
    }

    @Test fun queuedRevisionIsReplacedInPlaceButStaleOrSpokenRevisionsNeverRepeat() = Fixture().use { f ->
        f.submit("first")
        f.submit("queued", revision = 2, text = "Old complete text")
        f.submit("after")
        f.submit("queued", revision = 3, text = "Latest complete text")
        f.submit("queued", revision = 1, text = "Stale text")
        f.submit("first", revision = 50)
        f.finishCurrent()
        assertEquals(listOf("first", "Latest complete text"), f.spoken)
        f.finishCurrent()
        assertEquals(listOf("first", "Latest complete text", "after"), f.spoken)
    }

    @Test fun dictationEdgeStopsAndClearsOldOutputsButHoldsNewFinalsUntilRelease() = Fixture().use { f ->
        f.submit("old-speaking")
        f.submit("old-queued")
        val old = f.ports.single()
        f.setDictationHeld(true)
        f.drain()
        assertEquals(1, old.stops)
        assertEquals(1, old.closes)
        assertEquals(0, f.last.queuedCount)
        f.submit("new-during-dictation")
        f.setDictationHeld(true) // Repeated state must not clear the newer held output.
        f.submit("another-new-output")
        assertEquals(2, f.last.queuedCount)
        assertEquals(1, f.ports.size)
        f.prepare()
        f.drain()
        assertEquals(1, f.ports.size)
        f.setDictationHeld(false)
        f.drain()
        assertEquals(listOf("old-speaking", "new-during-dictation"), f.spoken)
        f.finishCurrent()
        assertEquals(listOf("old-speaking", "new-during-dictation", "another-new-output"), f.spoken)
        f.submit("old-queued", revision = 10)
        assertEquals(0, f.last.queuedCount)
    }

    @Test fun physicalReleaseSurvivesSpeechRevocationAndAccountEpochChange() = Fixture().use { f ->
        f.delivery.setDictationHeld(true, inputEpoch = 1)
        f.submit("old-held-output")
        assertTrue(f.ports.isEmpty())
        f.configure(enabled = false, allowIntermediate = false)
        f.beginTurn(2)
        f.configure(enabled = true, allowIntermediate = false)
        f.submit("new-account-final")
        assertTrue(f.ports.isEmpty())
        assertEquals(1, f.last.queuedCount)

        // No speech-epoch tag is needed: the physical microphone ended after the account changed.
        f.delivery.setDictationHeld(false, inputEpoch = 2)
        f.drain()
        assertEquals(listOf("new-account-final"), f.spoken)
        assertEquals(listOf(2L), f.factoryEpochs)
        assertEquals(0, f.last.queuedCount)
    }

    @Test fun captureOutputBarrierPreservesNewHeldFinalAndReadsItOnceAfterRelease() = Fixture().use { f ->
        f.submit("old-speaking-output")
        val old = f.ports.single()
        f.setDictationHeld(true)
        f.submit("new-held-final")
        assertEquals(1, f.last.queuedCount)
        assertTrue(f.stopThroughObserver(outputOnly = true))
        assertEquals(1, f.last.queuedCount)
        assertEquals(1, old.closes)
        assertEquals(listOf("old-speaking-output"), f.spoken)
        f.setDictationHeld(false)
        f.drain()
        assertEquals(listOf("old-speaking-output", "new-held-final"), f.spoken)
        f.finishCurrent()
        f.submit("new-held-final", revision = 2)
        assertEquals(listOf("old-speaking-output", "new-held-final"), f.spoken)
        assertEquals(2, f.ports.size)
    }

    @Test fun outputBarrierWithoutPhysicalHoldClearsPendingSpeechAndDoesNotPrepareAgain() = Fixture().use { f ->
        f.submit("speaking")
        f.submit("queued")
        assertTrue(f.stopThroughObserver(outputOnly = true))
        assertEquals(0, f.last.queuedCount)
        assertEquals(1, f.ports.single().closes)
        f.submit("queued", revision = 2)
        f.drain()
        assertEquals(listOf("speaking"), f.spoken)
        assertEquals(1, f.ports.size)
    }

    @Test fun explicitStopAndBlockingStopStillDiscardNewHeldFinals() {
        listOf(false, true).forEach { blocking -> Fixture().use { f ->
            f.setDictationHeld(true)
            f.submit("new-held-final")
            if (blocking) assertTrue(f.stopThroughObserver(outputOnly = false)) else f.delivery.stop()
            f.drain()
            assertEquals(0, f.last.queuedCount)
            f.setDictationHeld(false)
            f.submit("new-held-final", revision = 2)
            assertTrue(f.ports.isEmpty())
        } }
    }

    @Test fun stalePhysicalReleaseCannotUnlockNewerCaptureEvenAfterSpeechEpochChanged() = Fixture().use { f ->
        f.delivery.setDictationHeld(true, inputEpoch = 1)
        f.delivery.setDictationHeld(true, inputEpoch = 3)
        f.beginTurn(2)
        f.submit("new-capture-held-final")
        f.delivery.setDictationHeld(false, inputEpoch = 2)
        f.delivery.setDictationHeld(false, inputEpoch = 3) // Conflicting duplicate revision is stale too.
        f.drain()
        assertTrue(f.ports.isEmpty())
        assertEquals(1, f.last.queuedCount)
        f.delivery.setDictationHeld(false, inputEpoch = 4)
        f.drain()
        assertEquals(listOf("new-capture-held-final"), f.spoken)
    }

    @Test fun physicalHoldArrivingBeforeFirstSpeechEpochIsPreservedUntilItsRelease() {
        // A fresh delivery can receive physical state before any current account snapshot.
        val executor = QueuedExecutor()
        val ports = mutableListOf<Port>()
        val delivery = CodexReadAloudDelivery(
            sessionFactory = { _, listener -> Port(listener).also(ports::add) },
            serialExecutor = executor,
        )
        try {
            delivery.setDictationHeld(true, inputEpoch = 1)
            delivery.beginTurn(50)
            delivery.configure(enabled = true, allowIntermediate = false, expectedEpoch = 50)
            delivery.submit(revision("held-before-account-snapshot"), expectedEpoch = 50)
            executor.drain()
            assertTrue(ports.isEmpty())
            delivery.setDictationHeld(false, inputEpoch = 2)
            executor.drain()
            assertEquals(listOf("held-before-account-snapshot"), ports.flatMap { it.spoken })
        } finally {
            delivery.close()
            executor.drain()
            executor.shutdownNow()
        }
    }

    @Test fun muteClearsAndAbsorbsRevisionsSoUnmuteHasNoBacklog() = Fixture().use { f ->
        f.submit("speaking")
        f.submit("queued")
        f.configure(enabled = false, allowIntermediate = true)
        f.submit("muted")
        assertEquals(1, f.ports.single().closes)
        f.configure(enabled = true, allowIntermediate = true)
        f.drain()
        assertEquals(1, f.ports.size)
        f.submit("queued", revision = 2)
        f.submit("muted", revision = 2)
        f.submit("fresh")
        assertEquals(listOf("speaking", "fresh"), f.spoken)
    }

    @Test fun explicitStopClearsOldQueueWithoutPreventingNewOutput() = Fixture().use { f ->
        f.submit("speaking")
        f.submit("queued")
        f.delivery.stop()
        f.drain()
        f.submit("queued", revision = 2)
        assertEquals(1, f.ports.size)
        f.submit("fresh")
        assertEquals(listOf("speaking", "fresh"), f.spoken)
    }

    @Test fun newEpochStopsAndClearsButRepeatedEpochDoesNotReplay() = Fixture().use { f ->
        f.submit("first")
        f.submit("queued")
        val old = f.ports.single()
        f.beginTurn(2)
        f.drain()
        assertEquals(1, old.closes)
        assertEquals(0, f.last.queuedCount)
        f.submit("first") // The explicit new epoch permits reused IDs.
        f.beginTurn(2)
        f.submit("first")
        old.emit(CodexReadAloudDeliveryPhase.READY)
        old.emit(CodexReadAloudDeliveryPhase.FAILED)
        old.emit(CodexReadAloudDeliveryPhase.STOPPED)
        f.drain()
        assertEquals(listOf("first", "first"), f.spoken)
        assertEquals(CodexReadAloudDeliveryPhase.SPEAKING, f.last.phase)
        assertEquals(2, f.ports.size)
    }

    @Test fun failedSessionClearsQueueWithoutRetryUntilExplicitNewEpoch() = Fixture().use { f ->
        f.submit("first")
        f.submit("queued")
        val failed = f.ports.single()
        failed.emit(CodexReadAloudDeliveryPhase.FAILED)
        f.drain()
        assertEquals(CodexReadAloudDeliveryPhase.FAILED, f.last.phase)
        assertEquals("codex_read_aloud_session_failed", f.last.failureCode)
        assertEquals(1, failed.closes)
        f.submit("other-output-after-failure")
        f.prepare()
        f.drain()
        assertEquals(1, f.ports.size)
        assertEquals(0, f.last.queuedCount)
        f.beginTurn(2)
        f.submit("fresh")
        assertEquals(listOf("first", "fresh"), f.spoken)
    }

    @Test fun staleEpochAdmissionsAndPolicyUpdatesCannotAffectNewAccountSpeech() = Fixture().use { f ->
        f.submit("old-account-output")
        f.beginTurn(2)
        f.submit("current-account-output")
        f.submit("current-queued-output")
        val current = f.ports.last()
        f.delivery.submit(revision("stale-private-output"), expectedEpoch = 1)
        f.delivery.configure(enabled = false, allowIntermediate = true, expectedEpoch = 1)
        f.delivery.prepare(expectedEpoch = 1)
        f.delivery.beginTurn(1)
        f.drain()
        assertEquals(0, current.stops)
        assertEquals(1, f.last.queuedCount)
        assertEquals(CodexReadAloudDeliveryPhase.SPEAKING, f.last.phase)
        f.finishCurrent()
        assertEquals(listOf("old-account-output", "current-account-output", "current-queued-output"), f.spoken)
        assertEquals(listOf(1L, 2L, 2L), f.factoryEpochs)
    }

    @Test fun newEpochRequiresFreshPolicyAndOldEnableCannotAdmitHistory() = Fixture().use { f ->
        f.delivery.beginTurn(2)
        f.delivery.configure(enabled = true, allowIntermediate = true, expectedEpoch = 1)
        f.delivery.submit(revision("arrived-before-current-policy"), expectedEpoch = 2)
        f.delivery.prepare(expectedEpoch = 1)
        f.drain()
        assertTrue(f.ports.isEmpty())
        f.delivery.configure(enabled = true, allowIntermediate = false, expectedEpoch = 2)
        f.delivery.submit(revision("arrived-before-current-policy", revision = 2), expectedEpoch = 2)
        f.delivery.submit(revision("fresh-current-output"), expectedEpoch = 2)
        f.drain()
        assertEquals(listOf("fresh-current-output"), f.spoken)
        assertEquals(listOf(2L), f.factoryEpochs)
    }

    @Test fun stoppedWithoutPhysicalBarrierCannotStartAnotherSession() = Fixture().use { f ->
        f.submit("first")
        f.submit("queued")
        val first = f.ports.single()
        first.released = false
        first.emit(CodexReadAloudDeliveryPhase.STOPPED)
        f.drain()
        assertEquals(CodexReadAloudDeliveryPhase.FAILED, f.last.phase)
        assertEquals(1, f.ports.size)
        assertEquals(0, first.closes)
        assertEquals(0, f.last.queuedCount)
        f.beginTurn(2)
        f.submit("fresh")
        assertEquals(1, f.ports.size)
        first.released = true
        first.emit(CodexReadAloudDeliveryPhase.STOPPED)
        f.drain()
        assertEquals(1, first.closes)
        assertEquals(listOf("first"), f.spoken)
        assertEquals(CodexReadAloudDeliveryPhase.FAILED, f.last.phase)
        f.beginTurn(3)
        f.submit("fresh")
        assertEquals(listOf("first", "fresh"), f.spoken)
    }

    @Test fun heldNewOutputWaitsForOldPhysicalReleaseEvenAfterDictationReleased() = Fixture().use { f ->
        f.submit("first")
        val first = f.ports.single()
        first.released = false
        f.setDictationHeld(true)
        f.submit("new-final")
        f.setDictationHeld(false)
        f.drain()
        assertEquals(1, f.ports.size)
        assertEquals(1, f.last.queuedCount)
        first.released = true
        first.emit(CodexReadAloudDeliveryPhase.STOPPED)
        f.drain()
        assertEquals(listOf("first", "new-final"), f.spoken)
    }

    @Test fun preparationWithoutTextExpiresOnceWithoutOpeningALoop() = Fixture().use { f ->
        f.prepare()
        f.prepare()
        f.drain()
        assertEquals(1, f.ports.size)
        assertTrue(f.spoken.isEmpty())
        assertEquals(CodexReadAloudDeliveryPhase.READY, f.last.phase)
        f.finishCurrent()
        f.drain()
        assertEquals(1, f.ports.size)
        assertEquals(1, f.ports.single().closes)
        f.submit("fresh-later")
        assertEquals(2, f.ports.size)
        assertEquals(listOf("fresh-later"), f.spoken)
    }

    @Test fun boundedTombstonesNeverEvictOldIdsAndAccidentallyReplayHistory() = Fixture().use { f ->
        f.configure(enabled = false, allowIntermediate = false)
        repeat(CodexReadAloudDelivery.MAX_TRACKED_IDS + 10) { f.submit("muted-$it") }
        f.configure(enabled = true, allowIntermediate = false)
        f.submit("muted-0", revision = 99)
        f.submit("untracked-id")
        assertTrue(f.ports.isEmpty())
        f.beginTurn(2)
        f.submit("fresh")
        assertEquals(listOf("fresh"), f.spoken)
    }

    @Test fun overlongAndBlankTextNeverReachVoiceAndNoPayloadIsInSnapshots() = Fixture().use { f ->
        f.submit("blank", text = "  ")
        f.submit("long", text = "x".repeat(CodexReadAloudDelivery.MAX_TEXT_CHARACTERS + 1))
        assertTrue(f.ports.isEmpty())
        f.submit("valid", text = "Private synthetic output")
        assertTrue(f.snapshots.none { it.toString().contains("Private synthetic output") })
    }

    @Test fun switchingToFinalOnlyDropsIntermediateQueueButKeepsQueuedFinal() = Fixture().use { f ->
        f.configure(enabled = true, allowIntermediate = true)
        f.submit("progress-active", kind = TtsMessageKind.INTERMEDIATE)
        f.submit("progress-queued", kind = TtsMessageKind.INTERMEDIATE)
        f.submit("answer")
        f.configure(enabled = true, allowIntermediate = false)
        f.drain()
        assertEquals(listOf("progress-active", "answer"), f.spoken)
        assertEquals(1, f.ports.first().closes)
        assertEquals(0, f.last.queuedCount)
    }

    @Test fun observerCanReenterTheBlockingStopBarrierWithoutLockOrWorkerDeadlock() = Fixture().use { f ->
        var stopped: Boolean? = null
        f.onSnapshot = { snapshot ->
            if (snapshot.phase == CodexReadAloudDeliveryPhase.READY && snapshot.messageId != null && stopped == null) {
                stopped = false // Prevent recursive observer entry from requesting another stop.
                stopped = f.delivery.stopAndAwait(100)
            }
        }
        f.submit("answer")
        assertEquals(true, stopped)
        assertEquals(1, f.ports.single().closes)
        assertEquals(0, f.last.queuedCount)
        assertTrue(f.spoken.isEmpty())
    }

    @Test fun acceptedSpeechRequestDoesNotClaimPlaybackBeforeEngineObservedOutput() = Fixture().use { f ->
        f.automaticSpeaking = false
        f.submit("answer")
        assertEquals(listOf("answer"), f.spoken)
        assertEquals(CodexReadAloudDeliveryPhase.READY, f.last.phase)
        assertEquals(TtsMessageId("answer"), f.last.messageId)
        assertTrue(f.snapshots.none { it.phase == CodexReadAloudDeliveryPhase.SPEAKING })
        f.ports.single().emit(CodexReadAloudDeliveryPhase.SPEAKING)
        f.drain()
        assertEquals(CodexReadAloudDeliveryPhase.SPEAKING, f.last.phase)
    }

    @Test fun observerCanCancelPreparationBeforeAnySessionIsConstructed() = Fixture().use { f ->
        var stopped: Boolean? = null
        f.onSnapshot = { snapshot ->
            if (snapshot.phase == CodexReadAloudDeliveryPhase.CONNECTING && stopped == null) {
                stopped = false
                stopped = f.delivery.stopAndAwait(100)
            }
        }
        f.submit("answer")
        assertEquals(true, stopped)
        assertTrue(f.ports.isEmpty())
        assertEquals(0, f.last.queuedCount)
    }

    @Test fun closeIsIdempotentAndLateCallbacksCannotReopenVoice() {
        val f = Fixture()
        f.submit("first")
        val first = f.ports.single()
        f.delivery.close()
        f.delivery.close()
        f.drain()
        first.emit(CodexReadAloudDeliveryPhase.READY)
        f.delivery.submit(revision("late"), f.epoch)
        f.prepare()
        f.drain()
        assertEquals(1, first.closes)
        assertEquals(1, f.ports.size)
        assertEquals(CodexReadAloudDeliveryPhase.STOPPED, f.last.phase)
        f.close()
    }

    @Test fun disposalDoesNotClaimStoppedWhenPhysicalReleaseWasUnconfirmed() {
        val f = Fixture()
        f.submit("first")
        f.ports.single().released = false
        f.delivery.close()
        f.drain()
        assertEquals(1, f.ports.single().closes)
        assertEquals(CodexReadAloudDeliveryPhase.FAILED, f.last.phase)
        assertEquals("codex_read_aloud_release_unconfirmed", f.last.failureCode)
        f.close()
    }

    private class Fixture : Closeable {
        val executor = QueuedExecutor()
        val ports = mutableListOf<Port>()
        val snapshots = mutableListOf<CodexReadAloudDeliverySnapshot>()
        val events = mutableListOf<TtsPlaybackEvent>()
        var onSnapshot: (CodexReadAloudDeliverySnapshot) -> Unit = {}
        var onConstruction: () -> Unit = {}
        var automaticSpeaking = true
        var epoch = 1L
            private set
        private var enabled = true
        private var allowIntermediate = false
        private var inputEpoch = 0L
        val factoryEpochs = mutableListOf<Long>()
        val delivery = CodexReadAloudDelivery(
            sessionFactory = { epoch, listener ->
                factoryEpochs += epoch
                Port(listener).also { it.emitSpeaking = automaticSpeaking; ports += it; onConstruction() }
            },
            observer = { snapshots += it; onSnapshot(it) },
            serialExecutor = executor,
            stopBarrierMillis = 100,
            playbackEvent = { events += it },
        )
        val spoken get() = ports.flatMap { it.spoken }
        val last get() = snapshots.last()
        init {
            delivery.beginTurn(epoch)
            configure(enabled = true, allowIntermediate = false)
            drain()
        }
        fun configure(enabled: Boolean, allowIntermediate: Boolean) {
            this.enabled = enabled
            this.allowIntermediate = allowIntermediate
            delivery.configure(enabled, allowIntermediate, epoch)
        }
        fun beginTurn(next: Long) {
            if (next > epoch) epoch = next
            delivery.beginTurn(next)
            delivery.configure(enabled, allowIntermediate, epoch)
        }
        fun prepare() { delivery.prepare(epoch) }
        fun setDictationHeld(held: Boolean) { delivery.setDictationHeld(held, ++inputEpoch) }
        fun stopThroughObserver(outputOnly: Boolean): Boolean {
            var invoked = false
            var result = false
            val previous = onSnapshot
            onSnapshot = {
                if (!invoked) {
                    invoked = true
                    result = if (outputOnly) delivery.stopOutputAndAwait(100) else delivery.stopAndAwait(100)
                }
            }
            // Invoke on the delivery worker, not by blocking this manually drained test executor.
            delivery.configure(enabled, allowIntermediate, epoch)
            drain()
            onSnapshot = previous
            check(invoked)
            return result
        }
        fun cancelThroughObserver(ids: Set<TtsMessageId>): Boolean {
            var invoked = false
            var result = false
            val previous = onSnapshot
            onSnapshot = { if (!invoked) {
                invoked = true
                result = delivery.cancelMessagesAndAwait(ids, 100)
            } }
            delivery.configure(enabled, allowIntermediate, epoch)
            drain()
            onSnapshot = previous
            check(invoked)
            return result
        }
        fun submit(id: String, revision: Long = 1, text: String = id, final: Boolean = true,
                   kind: TtsMessageKind = TtsMessageKind.FINAL_OUTPUT) {
            delivery.submit(CodexReadAloudDeliveryTest.revision(id, revision, text, final, kind), epoch)
            drain()
        }
        fun finishCurrent() {
            ports.last().natural = true
            ports.last().emit(CodexReadAloudDeliveryPhase.STOPPED)
            drain()
        }
        fun drain() = executor.drain()
        override fun close() {
            delivery.close()
            drain()
            executor.shutdownNow()
        }
    }

    private class Port(private val listener: (CodexReadAloudDeliveryPhase) -> Unit) : CodexReadAloudSessionPort {
        val spoken = mutableListOf<String>()
        var released = true
        var stops = 0
        var barriers = 0
        var closes = 0
        var emitSpeaking = true
        var emitReady = true
        var natural = false
        var preparations = 0
        var acceptPreparation = true
        override fun prepareGuarded(admissionGuard: () -> Boolean): Boolean {
            if (!acceptPreparation || !admissionGuard()) return false
            prepare()
            return true
        }
        override val completedNaturally get() = natural
        override fun prepare() { preparations++; emit(CodexReadAloudDeliveryPhase.CONNECTING); if (emitReady) emit(CodexReadAloudDeliveryPhase.READY) }
        override fun speak(text: String): Boolean {
            spoken += text
            if (emitSpeaking) emit(CodexReadAloudDeliveryPhase.SPEAKING)
            return true
        }
        override fun stop() { stops++; natural = false }
        override fun stopAndAwait(timeoutMillis: Long): Boolean { barriers++; return released }
        override fun close() { closes++ }
        fun emit(phase: CodexReadAloudDeliveryPhase) = listener(phase)
    }

    private class QueuedExecutor : AbstractExecutorService() {
        private val work = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            work += command
        }
        fun drain() {
            var count = 0
            while (work.isNotEmpty()) {
                check(count++ < 10_000) { "Delivery entered a scheduling loop" }
                work.removeFirst().run()
            }
        }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return work.toMutableList().also { work.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && work.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    companion object {
        private fun revision(id: String, revision: Long = 1, text: String = id, final: Boolean = true,
                             kind: TtsMessageKind = TtsMessageKind.FINAL_OUTPUT) =
            TtsMessageRevision(TtsMessageId(id), revision, text, kind, final)
    }
}
