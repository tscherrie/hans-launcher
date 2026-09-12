package ai.hans.standard.automations

import ai.hans.standard.backup.HansBackupMaintenance
import java.time.Duration
import java.time.Instant

/** Always acquire maintenance before the delegate's monitor, including compound tool mutations. */
class BackupMaintainedAutomationStorage(
    private val delegate: AutomationStorage,
    val maintenance: HansBackupMaintenance,
) : AutomationStorage {
    override fun <T> withAtomicAccess(block: () -> T): T =
        maintenance.withStateAccess { delegate.withAtomicAccess(block) }

    override fun snapshot(): AutomationStorageSnapshot = withAtomicAccess(delegate::snapshot)

    override fun replaceSnapshotForRestore(snapshot: AutomationStorageSnapshot) = withAtomicAccess {
        delegate.replaceSnapshotForRestore(snapshot)
    }

    override fun compareAndReplaceSnapshotForRestore(
        expected: AutomationStorageSnapshot,
        replacement: AutomationStorageSnapshot,
    ): Boolean = withAtomicAccess {
        delegate.compareAndReplaceSnapshotForRestore(expected, replacement)
    }

    override fun upsertDefinition(definition: AutomationDefinition): DefinitionWriteResult =
        withAtomicAccess { delegate.upsertDefinition(definition) }

    override fun removeDefinition(id: AutomationId, expectedRevision: Long): Boolean =
        withAtomicAccess { delegate.removeDefinition(id, expectedRevision) }

    override fun definitions(): List<AutomationDefinition> = withAtomicAccess(delegate::definitions)

    override fun definition(id: AutomationId): AutomationDefinition? =
        withAtomicAccess { delegate.definition(id) }

    override fun enqueueManualRun(
        id: AutomationId,
        expectedRevision: Long,
        requestId: AutomationManualRequestId,
        requestedAt: Instant,
    ): AutomationManualEnqueueResult = withAtomicAccess {
        delegate.enqueueManualRun(id, expectedRevision, requestId, requestedAt)
    }

    override fun recordConfirmation(
        receipt: AutomationRunConfirmationReceipt,
        now: Instant,
    ): AutomationConfirmationWriteResult = withAtomicAccess { delegate.recordConfirmation(receipt, now) }

    override fun confirmedRunKeys(now: Instant): Set<AutomationRunKey> =
        withAtomicAccess { delegate.confirmedRunKeys(now) }

    override fun cancelDefinition(
        id: AutomationId,
        expectedRevision: Long,
        now: Instant,
    ): AutomationCancellationResult = withAtomicAccess { delegate.cancelDefinition(id, expectedRevision, now) }

    override fun recordDiscovery(batch: AutomationDiscoveryBatch): AutomationDiscoveryResult =
        withAtomicAccess { delegate.recordDiscovery(batch) }

    override fun schedulerCursor(id: AutomationId): AutomationSchedulerCursor? =
        withAtomicAccess { delegate.schedulerCursor(id) }

    override fun resetSchedulerCursors(ids: Set<AutomationId>, evaluatedThrough: Instant?) =
        withAtomicAccess { delegate.resetSchedulerCursors(ids, evaluatedThrough) }

    override fun materializeDueInbox(now: Instant, maximumItems: Int): AutomationMaterializationResult =
        withAtomicAccess { delegate.materializeDueInbox(now, maximumItems) }

    override fun releaseDeferredRuns(
        resolved: Set<AutomationResolvedPrecondition>,
        now: Instant,
    ): Int = withAtomicAccess { delegate.releaseDeferredRuns(resolved, now) }

    override fun acquireNextLease(
        owner: AutomationWorkerId,
        token: AutomationLeaseToken,
        bootSessionId: AutomationBootSessionId,
        now: Instant,
        leaseDuration: Duration,
    ): AutomationLeaseClaim? = withAtomicAccess {
        delegate.acquireNextLease(owner, token, bootSessionId, now, leaseDuration)
    }

    override fun heartbeat(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
        leaseDuration: Duration,
    ): AutomationHeartbeatResult = withAtomicAccess { delegate.heartbeat(key, token, now, leaseDuration) }

    override fun markDispatchFence(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
        leaseDuration: Duration,
    ): AutomationDispatchFenceMarkResult = withAtomicAccess {
        delegate.markDispatchFence(key, token, now, leaseDuration)
    }

    override fun clearDispatchFenceAfterRejected(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
    ): AutomationDispatchFenceClearResult = withAtomicAccess {
        delegate.clearDispatchFenceAfterRejected(key, token, now)
    }

    override fun completeLease(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
        completion: AutomationCompletion,
    ): AutomationCompletionResult = withAtomicAccess { delegate.completeLease(key, token, now, completion) }

    override fun recoverLeases(
        now: Instant,
        currentBootSessionId: AutomationBootSessionId,
        forceSameBootRecovery: Boolean,
    ): AutomationLeaseRecoveryResult = withAtomicAccess {
        delegate.recoverLeases(now, currentBootSessionId, forceSameBootRecovery)
    }

    override fun recoveryState(): AutomationRecoveryState? = withAtomicAccess(delegate::recoveryState)

    override fun storeRecoveryState(state: AutomationRecoveryState) =
        withAtomicAccess { delegate.storeRecoveryState(state) }
}
