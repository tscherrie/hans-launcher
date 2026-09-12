package ai.hans.standard.phone.lifecycle

import ai.hans.standard.phone.lifecycle.android.shouldStopActiveWorkService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HansActiveWorkStateTest {
    @Test
    fun firstAcquireStartsAndLastReleaseStops() {
        val sink = RecordingSink()
        val coordinator = HansActiveWorkCoordinator(sink)

        val started = coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, true)
        val stopped = coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, false)

        assertEquals(HansActiveWorkUpdateStatus.APPLIED, started.status)
        assertEquals(setOf(HansActiveWorkReason.CODEX_ACTIVE), started.activeReasons)
        assertEquals(HansActiveWorkUpdateStatus.APPLIED, stopped.status)
        assertTrue(stopped.activeReasons.isEmpty())
        assertEquals(
            listOf(
                SinkCall(
                    ActiveWorkCommand.START,
                    setOf(HansActiveWorkReason.CODEX_ACTIVE),
                    revision = 1L,
                ),
                SinkCall(ActiveWorkCommand.STOP, emptySet(), revision = 2L),
            ),
            sink.calls,
        )
    }

    @Test
    fun independentReasonsUpdateWithoutPrematureStop() {
        val sink = RecordingSink()
        val coordinator = HansActiveWorkCoordinator(sink)

        coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, true)
        val both = coordinator.setReason(HansActiveWorkReason.SPEECH_ACTIVE, true)
        val speechOnly = coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, false)

        assertEquals(
            setOf(
                HansActiveWorkReason.CODEX_ACTIVE,
                HansActiveWorkReason.SPEECH_ACTIVE,
            ),
            both.activeReasons,
        )
        assertEquals(
            setOf(HansActiveWorkReason.SPEECH_ACTIVE),
            speechOnly.activeReasons,
        )
        assertEquals(
            listOf(
                ActiveWorkCommand.START,
                ActiveWorkCommand.UPDATE,
                ActiveWorkCommand.UPDATE,
            ),
            sink.calls.map(SinkCall::command),
        )
    }

    @Test
    fun releasingAbsentReasonDoesNotStartOrStopService() {
        val sink = RecordingSink()
        val coordinator = HansActiveWorkCoordinator(sink)

        val update = coordinator.setReason(HansActiveWorkReason.SPEECH_ACTIVE, false)

        assertEquals(HansActiveWorkUpdateStatus.UNCHANGED, update.status)
        assertTrue(update.activeReasons.isEmpty())
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun duplicateAcquireAndReleaseAreIdempotent() {
        val sink = RecordingSink()
        val coordinator = HansActiveWorkCoordinator(sink)

        coordinator.setReason(HansActiveWorkReason.SPEECH_ACTIVE, true)
        val duplicateAcquire = coordinator.setReason(HansActiveWorkReason.SPEECH_ACTIVE, true)
        coordinator.setReason(HansActiveWorkReason.SPEECH_ACTIVE, false)
        val duplicateRelease = coordinator.setReason(HansActiveWorkReason.SPEECH_ACTIVE, false)

        assertEquals(HansActiveWorkUpdateStatus.UNCHANGED, duplicateAcquire.status)
        assertEquals(HansActiveWorkUpdateStatus.UNCHANGED, duplicateRelease.status)
        assertEquals(
            listOf(ActiveWorkCommand.START, ActiveWorkCommand.STOP),
            sink.calls.map(SinkCall::command),
        )
    }

    @Test
    fun rejectedDispatchRollsBackEffectiveStateAndCanBeRetried() {
        val sink = RecordingSink(accept = false)
        val coordinator = HansActiveWorkCoordinator(sink)

        val rejected = coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, true)
        sink.accept = true
        val retried = coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, true)

        assertEquals(HansActiveWorkUpdateStatus.REJECTED, rejected.status)
        assertTrue(rejected.activeReasons.isEmpty())
        assertEquals(HansActiveWorkUpdateStatus.APPLIED, retried.status)
        assertEquals(setOf(HansActiveWorkReason.CODEX_ACTIVE), retried.activeReasons)
        assertEquals(
            listOf(ActiveWorkCommand.START, ActiveWorkCommand.START),
            sink.calls.map(SinkCall::command),
        )
    }

    @Test
    fun rejectedUpdatePreservesPreviouslyAcceptedReasons() {
        val sink = RecordingSink()
        val coordinator = HansActiveWorkCoordinator(sink)
        coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, true)
        sink.accept = false

        val rejected = coordinator.setReason(HansActiveWorkReason.SPEECH_ACTIVE, true)

        assertEquals(HansActiveWorkUpdateStatus.REJECTED, rejected.status)
        assertEquals(setOf(HansActiveWorkReason.CODEX_ACTIVE), rejected.activeReasons)
        assertEquals(setOf(HansActiveWorkReason.CODEX_ACTIVE), coordinator.snapshot())
    }

    @Test
    fun serviceDeathOrPlatformTimeoutClearsReasonsWithoutDispatching() {
        val sink = RecordingSink()
        val coordinator = HansActiveWorkCoordinator(sink)
        coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, true)
        coordinator.setReason(HansActiveWorkReason.SPEECH_ACTIVE, true)
        val callCountBeforeStop = sink.calls.size

        coordinator.onServiceTimedOut()

        assertTrue(coordinator.snapshot().isEmpty())
        assertEquals(callCountBeforeStop, sink.calls.size)
        val releaseAfterStop = coordinator.setReason(
            HansActiveWorkReason.SPEECH_ACTIVE,
            false,
        )
        assertEquals(HansActiveWorkUpdateStatus.UNCHANGED, releaseAfterStop.status)
        assertEquals(callCountBeforeStop, sink.calls.size)
    }

    @Test
    fun staleDestroyFromPreviousServiceDoesNotClearAReacquiredReason() {
        val sink = RecordingSink()
        val coordinator = HansActiveWorkCoordinator(sink)
        coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, true)
        val oldServiceRevision = sink.calls.last().revision
        coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, false)
        coordinator.setReason(HansActiveWorkReason.SPEECH_ACTIVE, true)

        coordinator.onServiceStopped(oldServiceRevision)

        assertEquals(setOf(HansActiveWorkReason.SPEECH_ACTIVE), coordinator.snapshot())
    }

    @Test
    fun destroyOfLatestServiceRevisionClearsProcessLocalReasons() {
        val sink = RecordingSink()
        val coordinator = HansActiveWorkCoordinator(sink)
        coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, true)
        val liveRevision = sink.calls.last().revision

        coordinator.onServiceStopped(liveRevision)

        assertTrue(coordinator.snapshot().isEmpty())
    }

    @Test
    fun versionedSnapshotPublishesTheAcceptedDesiredStateForQueuedServiceStarts() {
        val coordinator = HansActiveWorkCoordinator(RecordingSink())

        coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, true)
        coordinator.setReason(HansActiveWorkReason.SPEECH_ACTIVE, true)
        val snapshot = coordinator.versionedSnapshot()

        assertEquals(2L, snapshot.revision)
        assertEquals(setOf(HansActiveWorkReason.CODEX_ACTIVE, HansActiveWorkReason.SPEECH_ACTIVE), snapshot.activeReasons)
    }

    @Test
    fun remoteAvailabilityIsIndependentOfCodexAndSpeechWork() {
        val sink = RecordingSink()
        val coordinator = HansActiveWorkCoordinator(sink)

        val remote = coordinator.setReason(HansActiveWorkReason.REMOTE_CONTROL, true)
        coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, true)
        coordinator.setReason(HansActiveWorkReason.SPEECH_ACTIVE, true)
        coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, false)
        val remoteOnly = coordinator.setReason(HansActiveWorkReason.SPEECH_ACTIVE, false)

        assertEquals(setOf(HansActiveWorkReason.REMOTE_CONTROL), remote.activeReasons)
        assertEquals(1L, remote.revision)
        assertEquals(setOf(HansActiveWorkReason.REMOTE_CONTROL), remoteOnly.activeReasons)
        assertEquals(5L, remoteOnly.revision)
        assertFalse(sink.calls.any { it.command == ActiveWorkCommand.STOP })
        val stopped = coordinator.setReason(HansActiveWorkReason.REMOTE_CONTROL, false)
        assertEquals(6L, stopped.revision)
        assertTrue(stopped.activeReasons.isEmpty())
        assertEquals(ActiveWorkCommand.STOP, sink.calls.last().command)
    }

    @Test
    fun remoteReleasePreservesAcceptedBackgroundWorkAndRejectedDispatchDoesNotAdvanceRevision() {
        val sink = RecordingSink()
        val coordinator = HansActiveWorkCoordinator(sink)
        coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, true)
        sink.accept = false
        val rejected = coordinator.setReason(HansActiveWorkReason.REMOTE_CONTROL, true)
        assertEquals(HansActiveWorkUpdateStatus.REJECTED, rejected.status)
        assertEquals(1L, rejected.revision)
        assertEquals(setOf(HansActiveWorkReason.CODEX_ACTIVE), rejected.activeReasons)

        sink.accept = true
        val remote = coordinator.setReason(HansActiveWorkReason.REMOTE_CONTROL, true)
        val duplicate = coordinator.setReason(HansActiveWorkReason.REMOTE_CONTROL, true)
        assertEquals(2L, remote.revision)
        assertEquals(remote.revision, duplicate.revision)
        val released = coordinator.setReason(HansActiveWorkReason.REMOTE_CONTROL, false)
        assertEquals(setOf(HansActiveWorkReason.CODEX_ACTIVE), released.activeReasons)
        assertEquals(ActiveWorkCommand.UPDATE, sink.calls.last().command)
    }

    @Test
    fun invalidQueuedIntentStillHasAProvisionalTypeThenReconcilesToStop() {
        val fallback = setOf(HansActiveWorkReason.CODEX_ACTIVE)
        val desired = HansActiveWorkSnapshot(emptySet(), revision = 0L)

        val provisional = ActiveWorkServiceReconciler.provisionalReasons(
            alreadyPublished = emptySet(),
            requested = null,
            fallback = fallback,
        )
        val target = ActiveWorkServiceReconciler.targetSnapshot(
            desired = desired,
            requested = null,
            requestedRevision = 0L,
        )

        assertEquals(fallback, provisional)
        assertTrue(target.activeReasons.isEmpty())
        assertEquals(0L, target.revision)
    }

    @Test
    fun staleQueuedIntentCannotRevertTheLatestDesiredReasons() {
        val latest = HansActiveWorkSnapshot(
            activeReasons = setOf(HansActiveWorkReason.SPEECH_ACTIVE),
            revision = 3L,
        )

        val target = ActiveWorkServiceReconciler.targetSnapshot(
            desired = latest,
            requested = setOf(HansActiveWorkReason.CODEX_ACTIVE),
            requestedRevision = 1L,
        )

        assertEquals(latest, target)
    }

    @Test
    fun directValidIntentCanBootstrapWhenNoProcessOwnerExistsYet() {
        val target = ActiveWorkServiceReconciler.targetSnapshot(
            desired = HansActiveWorkSnapshot(emptySet(), revision = 0L),
            requested = setOf(HansActiveWorkReason.CODEX_ACTIVE),
            requestedRevision = 1L,
        )

        assertEquals(setOf(HansActiveWorkReason.CODEX_ACTIVE), target.activeReasons)
        assertEquals(1L, target.revision)
    }

    @Test
    fun startThenStopBeforeDeliveryPromotesOnceThenReconcilesToEmpty() {
        val sink = RecordingSink()
        val coordinator = HansActiveWorkCoordinator(sink)
        coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, true)
        val queuedStart = sink.calls.single()
        coordinator.setReason(HansActiveWorkReason.CODEX_ACTIVE, false)

        val provisional = ActiveWorkServiceReconciler.provisionalReasons(
            alreadyPublished = emptySet(),
            requested = queuedStart.reasons,
            fallback = setOf(HansActiveWorkReason.CODEX_ACTIVE),
        )
        val target = ActiveWorkServiceReconciler.targetSnapshot(
            desired = coordinator.versionedSnapshot(),
            requested = queuedStart.reasons,
            requestedRevision = queuedStart.revision,
        )

        assertEquals(setOf(HansActiveWorkReason.CODEX_ACTIVE), provisional)
        assertTrue(target.activeReasons.isEmpty())
        assertEquals(2L, target.revision)
        assertFalse(shouldStopActiveWorkService(foregroundPublished = false))
    }

    @Test
    fun wireFormatIsVersionBoundedAndRejectsUnknownOrEmptyMasks() {
        val all = HansActiveWorkReason.entries.toSet()
        val mask = HansActiveWorkReason.toWireMask(all)

        assertEquals(all, HansActiveWorkReason.fromWireMask(mask))
        assertNull(HansActiveWorkReason.fromWireMask(0))
        assertNull(
            HansActiveWorkReason.fromWireMask(
                HansActiveWorkReason.knownWireMask or (1 shl 20),
            ),
        )
    }

    @Test
    fun reducerUsesOnlyFourExpectedTransitions() {
        val empty = emptySet<HansActiveWorkReason>()
        val codex = setOf(HansActiveWorkReason.CODEX_ACTIVE)
        val both = HansActiveWorkReason.entries.toSet()

        assertEquals(
            ActiveWorkCommand.NONE,
            ActiveWorkReasonReducer.reduce(empty, HansActiveWorkReason.CODEX_ACTIVE, false).command,
        )
        assertEquals(
            ActiveWorkCommand.START,
            ActiveWorkReasonReducer.reduce(empty, HansActiveWorkReason.CODEX_ACTIVE, true).command,
        )
        assertEquals(
            ActiveWorkCommand.UPDATE,
            ActiveWorkReasonReducer.reduce(codex, HansActiveWorkReason.SPEECH_ACTIVE, true).command,
        )
        assertEquals(
            ActiveWorkCommand.UPDATE,
            ActiveWorkReasonReducer.reduce(both, HansActiveWorkReason.CODEX_ACTIVE, false).command,
        )
        assertEquals(
            ActiveWorkCommand.STOP,
            ActiveWorkReasonReducer.reduce(codex, HansActiveWorkReason.CODEX_ACTIVE, false).command,
        )
    }

    private data class SinkCall(
        val command: ActiveWorkCommand,
        val reasons: Set<HansActiveWorkReason>,
        val revision: Long,
    )

    private class RecordingSink(
        var accept: Boolean = true,
    ) : ActiveWorkCommandSink {
        val calls = mutableListOf<SinkCall>()

        override fun apply(
            command: ActiveWorkCommand,
            reasons: Set<HansActiveWorkReason>,
            revision: Long,
        ): Boolean {
            calls += SinkCall(command, reasons.toSet(), revision)
            return accept
        }
    }
}
