package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap

enum class DefinitionWriteResult {
    INSERTED,
    UPDATED,
    UNCHANGED,
    REJECTED_STALE_REVISION,
    REJECTED_REVISION_CONFLICT,
}

data class AutomationDiscoveryBatch(
    val automationId: AutomationId,
    val definitionRevision: Long,
    val evaluatedThrough: Instant,
    val items: List<AutomationInboxItem>,
) {
    init {
        require(definitionRevision >= 1)
        require(items.size <= 50_000)
        require(items.all { item ->
            item.key.automationId == automationId && item.definitionRevision == definitionRevision
        })
        require(items.map { it.key }.distinct().size == items.size)
    }
}

data class AutomationDiscoveryResult(
    val accepted: Boolean,
    val inserted: Int,
    val duplicates: Int,
    val errorCode: String? = null,
)

data class AutomationMaterializationResult(
    val createdRuns: List<AutomationRunKey>,
    val staleRunsSkipped: List<AutomationRunKey>,
    val duplicateRuns: Int,
)

data class AutomationLeaseClaim(
    val definition: AutomationDefinition,
    val run: AutomationRun,
    val lease: AutomationLease,
)

enum class AutomationHeartbeatResult {
    EXTENDED,
    LEASE_NOT_FOUND,
    TOKEN_MISMATCH,
    LEASE_EXPIRED,
    INVALID_TIME,
}

enum class AutomationDispatchFenceMarkResult {
    MARKED,
    ALREADY_MARKED,
    LEASE_NOT_FOUND,
    TOKEN_MISMATCH,
    LEASE_EXPIRED,
    INVALID_TIME,
    FENCE_CONFLICT,
}

enum class AutomationDispatchFenceClearResult {
    CLEARED,
    NOT_MARKED,
    LEASE_NOT_FOUND,
    TOKEN_MISMATCH,
    LEASE_EXPIRED,
    INVALID_TIME,
    FENCE_CONFLICT,
}

sealed interface AutomationCompletion {
    data object Succeeded : AutomationCompletion

    data class RetryableFailure(val errorCode: String) : AutomationCompletion {
        init {
            require(errorCode.matches(SAFE_ERROR_CODE))
        }
    }

    data class PermanentFailure(val errorCode: String) : AutomationCompletion {
        init {
            require(errorCode.matches(SAFE_ERROR_CODE))
        }
    }

    /**
     * Releases the lease without consuming an execution attempt. This is used when a runtime
     * precondition such as connectivity, login, permission or per-run confirmation is absent.
     */
    data class Deferred(
        val errorCode: String,
        val retryAt: Instant,
    ) : AutomationCompletion {
        init {
            require(errorCode.matches(SAFE_ERROR_CODE))
        }
    }

    private companion object {
        val SAFE_ERROR_CODE = Regex("[a-z][a-z0-9_]{2,95}")
    }
}

enum class AutomationCompletionResult {
    COMPLETED,
    RETRY_SCHEDULED,
    DEFERRED,
    FAILED_TERMINAL,
    LEASE_NOT_FOUND,
    TOKEN_MISMATCH,
    LEASE_EXPIRED,
    INVALID_TIME,
}

data class AutomationLeaseRecoveryResult(
    val recoveredAfterBoot: Int,
    val recoveredAfterExpiry: Int,
    val retriesScheduled: Int,
    val terminalFailures: Int,
    val staleRunsSkipped: Int,
    val recoveredAfterClockChange: Int = 0,
)

/** Closed set of live prerequisites whose Android transition may release a matching deferral. */
enum class AutomationResolvedPrecondition(internal val deferredErrorCode: String) {
    VALIDATED_NETWORK("network_offline"),
    DEVICE_UNLOCKED("device_unlock_required"),
}

sealed interface AutomationManualEnqueueResult {
    data class Enqueued(val key: AutomationRunKey) : AutomationManualEnqueueResult
    data class Duplicate(val key: AutomationRunKey) : AutomationManualEnqueueResult
    /** Durable run exists, but no platform lifecycle accepted responsibility for waking it. */
    data class DispatchRejected(
        val key: AutomationRunKey,
        val errorCode: String,
        val retryable: Boolean,
    ) : AutomationManualEnqueueResult {
        init {
            require(errorCode.matches(Regex("[a-z][a-z0-9_]{2,95}")))
        }
    }
    data object DefinitionNotFound : AutomationManualEnqueueResult
    data object RevisionConflict : AutomationManualEnqueueResult
    data object DefinitionDisabled : AutomationManualEnqueueResult
    data object RequestConflict : AutomationManualEnqueueResult
}

enum class AutomationConfirmationWriteResult {
    STORED,
    DUPLICATE,
    CONFIRMATION_ID_CONFLICT,
    RUN_NOT_FOUND,
    REVISION_CONFLICT,
    RUN_NOT_CONFIRMABLE,
}

sealed interface AutomationCancellationResult {
    data class Cancelled(
        val newRevision: Long,
        val inboxItemsCancelled: Int,
        val runsCancelled: Int,
        val leasesRevoked: Int,
    ) : AutomationCancellationResult

    data object NotFound : AutomationCancellationResult
    data object RevisionConflict : AutomationCancellationResult
    data object AlreadyDisabled : AutomationCancellationResult
}

interface AutomationStorage {
    /** Serializes a compound caller mutation; maintained Android stores also hold backup access. */
    fun <T> withAtomicAccess(block: () -> T): T = synchronized(this) { block() }

    fun snapshot(): AutomationStorageSnapshot

    /**
     * Crash-safe restore boundary used only after a validated, explicitly confirmed backup
     * import. The complete candidate is validated before it replaces any current state.
     */
    fun replaceSnapshotForRestore(snapshot: AutomationStorageSnapshot)

    /** Compare-and-swap variant preventing a backup preview from erasing concurrent runtime work. */
    fun compareAndReplaceSnapshotForRestore(
        expected: AutomationStorageSnapshot,
        replacement: AutomationStorageSnapshot,
    ): Boolean

    fun upsertDefinition(definition: AutomationDefinition): DefinitionWriteResult
    /**
     * Removes only the exact disabled revision. This compare-and-delete boundary prevents a
     * concurrent re-enable/update between cancellation and deletion from being removed.
     */
    fun removeDefinition(id: AutomationId, expectedRevision: Long): Boolean
    fun definitions(): List<AutomationDefinition>
    fun definition(id: AutomationId): AutomationDefinition?
    fun enqueueManualRun(
        id: AutomationId,
        expectedRevision: Long,
        requestId: AutomationManualRequestId,
        requestedAt: Instant,
    ): AutomationManualEnqueueResult

    fun recordConfirmation(
        receipt: AutomationRunConfirmationReceipt,
        now: Instant,
    ): AutomationConfirmationWriteResult

    fun confirmedRunKeys(now: Instant): Set<AutomationRunKey>

    /**
     * Disables the expected revision and revokes all not-yet-terminal work atomically. A running
     * executor observes the revoked lease on its next heartbeat and must stop producing effects.
     */
    fun cancelDefinition(
        id: AutomationId,
        expectedRevision: Long,
        now: Instant,
    ): AutomationCancellationResult

    /** Atomically commits inbox items and advances the matching scheduler cursor. */
    fun recordDiscovery(batch: AutomationDiscoveryBatch): AutomationDiscoveryResult
    fun schedulerCursor(id: AutomationId): AutomationSchedulerCursor?
    fun resetSchedulerCursors(ids: Set<AutomationId>, evaluatedThrough: Instant? = null)

    /** Atomically converts due durable inbox entries to unique runs. */
    fun materializeDueInbox(now: Instant, maximumItems: Int = 256): AutomationMaterializationResult

    /**
     * Atomically makes only matching precondition deferrals eligible now. Genuine execution and
     * lease-recovery backoffs are deliberately ineligible even if they use the same error code.
     */
    fun releaseDeferredRuns(
        resolved: Set<AutomationResolvedPrecondition>,
        now: Instant,
    ): Int

    /** Atomically claims one eligible run and creates its compare-and-set lease. */
    fun acquireNextLease(
        owner: AutomationWorkerId,
        token: AutomationLeaseToken,
        bootSessionId: AutomationBootSessionId,
        now: Instant,
        leaseDuration: Duration,
    ): AutomationLeaseClaim?

    fun heartbeat(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
        leaseDuration: Duration,
    ): AutomationHeartbeatResult

    /** Atomically extends the owned lease and persists the no-redispatch fence. */
    fun markDispatchFence(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
        leaseDuration: Duration,
    ): AutomationDispatchFenceMarkResult

    /** Clears a fence only after the same lease proved that Codex rejected before dispatch. */
    fun clearDispatchFenceAfterRejected(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
    ): AutomationDispatchFenceClearResult

    fun completeLease(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
        completion: AutomationCompletion,
    ): AutomationCompletionResult

    /** Reclaims expired same-boot leases and every lease owned by an older boot session. */
    fun recoverLeases(
        now: Instant,
        currentBootSessionId: AutomationBootSessionId,
        forceSameBootRecovery: Boolean = false,
    ): AutomationLeaseRecoveryResult

    fun recoveryState(): AutomationRecoveryState?
    fun storeRecoveryState(state: AutomationRecoveryState)
}

/**
 * Android-free, transactionally synchronized reference implementation.
 *
 * [snapshot] is the persistence boundary: tests can restore a new instance after process death
 * or reboot, while a future Room adapter can map the same atomic method contract to DB transactions.
 */
class InMemoryAutomationStorage(
    initialSnapshot: AutomationStorageSnapshot = AutomationStorageSnapshot(),
) : AutomationStorage {
    private val definitions = LinkedHashMap<AutomationId, AutomationDefinition>()
    private val inbox = LinkedHashMap<AutomationRunKey, AutomationInboxItem>()
    private val runs = LinkedHashMap<AutomationRunKey, AutomationRun>()
    private val receipts = LinkedHashMap<AutomationRunKey, AutomationRunReceipt>()
    private val confirmations = LinkedHashMap<AutomationRunKey, AutomationRunConfirmationReceipt>()
    private val manualInvocations = LinkedHashMap<AutomationManualRequestId, AutomationManualInvocation>()
    private val leases = LinkedHashMap<AutomationRunKey, AutomationLease>()
    private val cursors = LinkedHashMap<AutomationId, AutomationSchedulerCursor>()
    private var recoveryState: AutomationRecoveryState? = initialSnapshot.recoveryState

    init {
        requireUnique(initialSnapshot.definitions.map { it.id }, "definition")
        requireUnique(initialSnapshot.inbox.map { it.key }, "inbox")
        requireUnique(initialSnapshot.runs.map { it.key }, "run")
        requireUnique(initialSnapshot.receipts.map { it.key }, "receipt")
        requireUnique(initialSnapshot.confirmations.map { it.key }, "confirmation run")
        requireUnique(initialSnapshot.confirmations.map { it.id }, "confirmation id")
        requireUnique(initialSnapshot.manualInvocations.map { it.requestId }, "manual request")
        requireUnique(initialSnapshot.leases.map { it.key }, "lease")
        requireUnique(initialSnapshot.leases.map { it.token }, "lease token")
        requireUnique(initialSnapshot.cursors.map { it.automationId }, "cursor")
        definitions.putAll(initialSnapshot.definitions.associateBy { it.id })
        inbox.putAll(initialSnapshot.inbox.associateBy { it.key })
        runs.putAll(initialSnapshot.runs.associateBy { it.key })
        receipts.putAll(initialSnapshot.receipts.associateBy { it.key })
        confirmations.putAll(initialSnapshot.confirmations.associateBy { it.key })
        manualInvocations.putAll(initialSnapshot.manualInvocations.associateBy { it.requestId })
        leases.putAll(initialSnapshot.leases.associateBy { it.key })
        cursors.putAll(initialSnapshot.cursors.associateBy { it.automationId })
        require(inbox.keys.intersect(runs.keys).isEmpty()) {
            "An occurrence cannot be present in both inbox and runs"
        }
        require(receipts.keys.intersect(inbox.keys + runs.keys).isEmpty()) {
            "A compacted receipt cannot coexist with inbox or detailed run state"
        }
        require(confirmations.keys.all { it in runs }) {
            "Every confirmation requires detailed run state"
        }
        require(confirmations.all { (key, receipt) ->
            val run = runs[key]
            val definition = definitions[key.automationId]
            run?.definitionRevision == receipt.definitionRevision &&
                run.state in CONFIRMATION_ACTIVE_RUN_STATES &&
                definition?.enabled == true &&
                definition.revision == receipt.definitionRevision
        }) { "Every confirmation requires current active work" }
        require(manualInvocations.values.all { invocation ->
            invocation.key in inbox || invocation.key in runs || invocation.key in receipts
        }) { "Every manual invocation requires durable work or a receipt" }
        require(leases.all { (key, _) -> runs[key]?.state == AutomationRunState.LEASED }) {
            "Every restored lease requires a leased run"
        }
        require(leases.all { (key, lease) -> runs[key]?.attemptCount == lease.generation }) {
            "Lease generation must match the run attempt"
        }
        require(runs.values.all { run ->
            val fence = run.dispatchFence ?: return@all true
            when (run.state) {
                AutomationRunState.LEASED -> leases[run.key]?.let { lease ->
                    lease.token == fence.leaseToken && lease.generation == fence.leaseGeneration
                } == true
                AutomationRunState.FAILED_TERMINAL -> true
                else -> false
            }
        }) { "Dispatch fence must match its active lease or an ambiguous terminal run" }
    }

    @Synchronized
    override fun snapshot(): AutomationStorageSnapshot = AutomationStorageSnapshot(
        definitions = definitions.values.sortedBy { it.id.value }.toList(),
        inbox = inbox.values.sortedBy { it.key }.toList(),
        runs = runs.values.sortedBy { it.key }.toList(),
        receipts = receipts.values.sortedBy { it.key }.toList(),
        confirmations = confirmations.values.sortedBy { it.key }.toList(),
        manualInvocations = manualInvocations.values.sortedBy { it.requestedAt }.toList(),
        leases = leases.values.sortedBy { it.key }.toList(),
        cursors = cursors.values.sortedBy { it.automationId.value }.toList(),
        recoveryState = recoveryState,
    )

    @Synchronized
    override fun replaceSnapshotForRestore(snapshot: AutomationStorageSnapshot) {
        AutomationBackupRestoreSafety.requireSafeReplacement(snapshot(), snapshot)
        val verified = InMemoryAutomationStorage(snapshot).snapshot()
        definitions.clear()
        inbox.clear()
        runs.clear()
        receipts.clear()
        confirmations.clear()
        manualInvocations.clear()
        leases.clear()
        cursors.clear()
        definitions.putAll(verified.definitions.associateBy { it.id })
        inbox.putAll(verified.inbox.associateBy { it.key })
        runs.putAll(verified.runs.associateBy { it.key })
        receipts.putAll(verified.receipts.associateBy { it.key })
        confirmations.putAll(verified.confirmations.associateBy { it.key })
        manualInvocations.putAll(verified.manualInvocations.associateBy { it.requestId })
        leases.putAll(verified.leases.associateBy { it.key })
        cursors.putAll(verified.cursors.associateBy { it.automationId })
        recoveryState = verified.recoveryState
    }

    @Synchronized
    override fun compareAndReplaceSnapshotForRestore(
        expected: AutomationStorageSnapshot,
        replacement: AutomationStorageSnapshot,
    ): Boolean {
        if (snapshot() != expected) return false
        replaceSnapshotForRestore(replacement)
        return true
    }

    @Synchronized
    override fun upsertDefinition(definition: AutomationDefinition): DefinitionWriteResult {
        val previous = definitions[definition.id]
        if (previous == definition) return DefinitionWriteResult.UNCHANGED
        if (previous != null && definition.revision < previous.revision) {
            return DefinitionWriteResult.REJECTED_STALE_REVISION
        }
        if (previous != null && definition.revision == previous.revision) {
            return DefinitionWriteResult.REJECTED_REVISION_CONFLICT
        }
        if (previous != null) {
            retireOutstandingWork(
                definition.id,
                definition.updatedAt,
                "automation_definition_updated",
            )
            cursors.remove(definition.id)
        }
        definitions[definition.id] = definition
        return if (previous == null) DefinitionWriteResult.INSERTED else DefinitionWriteResult.UPDATED
    }

    @Synchronized
    override fun removeDefinition(id: AutomationId, expectedRevision: Long): Boolean {
        val definition = definitions[id] ?: return false
        if (
            definition.revision != expectedRevision ||
            definition.enabled ||
            inbox.keys.any { it.automationId == id } ||
            runs.values.any {
                it.key.automationId == id && it.state !in TERMINAL_RUN_STATES
            } ||
            leases.keys.any { it.automationId == id }
        ) {
            return false
        }
        cursors.remove(id)
        confirmations.keys.filter { it.automationId == id }.forEach(confirmations::remove)
        definitions.remove(id)
        return true
    }

    @Synchronized
    override fun definitions(): List<AutomationDefinition> =
        definitions.values.sortedBy { it.id.value }.toList()

    @Synchronized
    override fun definition(id: AutomationId): AutomationDefinition? = definitions[id]

    @Synchronized
    override fun enqueueManualRun(
        id: AutomationId,
        expectedRevision: Long,
        requestId: AutomationManualRequestId,
        requestedAt: Instant,
    ): AutomationManualEnqueueResult {
        manualInvocations[requestId]?.let { existing ->
            return if (
                existing.key.automationId == id &&
                existing.definitionRevision == expectedRevision
            ) {
                AutomationManualEnqueueResult.Duplicate(existing.key)
            } else {
                AutomationManualEnqueueResult.RequestConflict
            }
        }
        val definition = definitions[id] ?: return AutomationManualEnqueueResult.DefinitionNotFound
        if (definition.revision != expectedRevision) {
            return AutomationManualEnqueueResult.RevisionConflict
        }
        if (!definition.enabled) return AutomationManualEnqueueResult.DefinitionDisabled

        var scheduledAt = requestedAt
        repeat(1_000) {
            val key = AutomationRunKey(id, scheduledAt)
            if (key !in inbox && key !in runs && key !in receipts) {
                inbox[key] = AutomationInboxItem(
                    key = key,
                    definitionRevision = expectedRevision,
                    discoveredAt = requestedAt,
                    readyAt = scheduledAt,
                    source = AutomationDiscoverySource.MANUAL_RUN,
                )
                manualInvocations[requestId] = AutomationManualInvocation(
                    requestId,
                    key,
                    expectedRevision,
                    requestedAt,
                )
                return AutomationManualEnqueueResult.Enqueued(key)
            }
            scheduledAt = runCatching { scheduledAt.plusNanos(1) }
                .getOrElse { return AutomationManualEnqueueResult.RevisionConflict }
        }
        return AutomationManualEnqueueResult.RevisionConflict
    }

    @Synchronized
    override fun recordConfirmation(
        receipt: AutomationRunConfirmationReceipt,
        now: Instant,
    ): AutomationConfirmationWriteResult {
        confirmations.values
            .filter { it.expiresAt <= now }
            .map { it.key }
            .forEach(confirmations::remove)
        if (confirmations.values.any { it.id == receipt.id && it.key != receipt.key }) {
            return AutomationConfirmationWriteResult.CONFIRMATION_ID_CONFLICT
        }
        confirmations[receipt.key]?.let { existing ->
            return if (existing.definitionRevision == receipt.definitionRevision) {
                AutomationConfirmationWriteResult.DUPLICATE
            } else {
                AutomationConfirmationWriteResult.REVISION_CONFLICT
            }
        }
        val run = runs[receipt.key] ?: return AutomationConfirmationWriteResult.RUN_NOT_FOUND
        if (run.definitionRevision != receipt.definitionRevision) {
            return AutomationConfirmationWriteResult.REVISION_CONFLICT
        }
        val definition = definitions[receipt.key.automationId]
        if (definition?.revision != receipt.definitionRevision) {
            return AutomationConfirmationWriteResult.REVISION_CONFLICT
        }
        if (
            !definition.enabled ||
            run.state !in CONFIRMABLE_RUN_STATES ||
            receipt.confirmedAt > now ||
            receipt.expiresAt <= now
        ) {
            return AutomationConfirmationWriteResult.RUN_NOT_CONFIRMABLE
        }
        confirmations[receipt.key] = receipt
        if (
            run.state == AutomationRunState.RETRY_WAIT &&
            run.waitKind == AutomationRunWaitKind.DEFERRED_PRECONDITION &&
            run.lastFailureCode == "user_confirmation_required"
        ) {
            runs[receipt.key] = run.copy(
                state = AutomationRunState.PENDING,
                availableAt = now,
                updatedAt = now,
                lastFailureCode = null,
                waitKind = null,
            )
        }
        return AutomationConfirmationWriteResult.STORED
    }

    @Synchronized
    override fun confirmedRunKeys(now: Instant): Set<AutomationRunKey> {
        val invalid = confirmations.values.filter { receipt ->
            val run = runs[receipt.key]
            val definition = definitions[receipt.key.automationId]
            receipt.expiresAt <= now ||
                run?.definitionRevision != receipt.definitionRevision ||
                run.state !in CONFIRMATION_ACTIVE_RUN_STATES ||
                definition?.enabled != true ||
                definition.revision != receipt.definitionRevision
        }.map { it.key }
        invalid.forEach(confirmations::remove)
        return confirmations.keys.toSet()
    }

    @Synchronized
    override fun cancelDefinition(
        id: AutomationId,
        expectedRevision: Long,
        now: Instant,
    ): AutomationCancellationResult {
        val definition = definitions[id] ?: return AutomationCancellationResult.NotFound
        if (definition.revision != expectedRevision) {
            return AutomationCancellationResult.RevisionConflict
        }
        if (!definition.enabled) return AutomationCancellationResult.AlreadyDisabled
        require(expectedRevision < Long.MAX_VALUE) { "Automation revision exhausted" }

        definitions[id] = definition.copy(
            revision = expectedRevision + 1,
            enabled = false,
            updatedAt = now,
        )
        cursors.remove(id)

        val matchingInbox = inbox.values.filter { it.key.automationId == id }
        matchingInbox.forEach { item ->
            inbox.remove(item.key)
            if (item.key !in runs) {
                runs[item.key] = AutomationRun(
                    key = item.key,
                    definitionRevision = item.definitionRevision,
                    state = AutomationRunState.SKIPPED,
                    attemptCount = 0,
                    availableAt = item.readyAt,
                    createdAt = item.discoveredAt,
                    updatedAt = now,
                    lastFailureCode = "automation_cancelled",
                )
            }
        }

        var cancelledRuns = 0
        runs.values.toList().forEach { run ->
            if (run.key.automationId == id && run.state !in TERMINAL_RUN_STATES) {
                runs[run.key] = run.copy(
                    state = if (run.dispatchFence == null) {
                        AutomationRunState.SKIPPED
                    } else {
                        AutomationRunState.FAILED_TERMINAL
                    },
                    updatedAt = now,
                    lastFailureCode = if (run.dispatchFence == null) {
                        "automation_cancelled"
                    } else {
                        "automation_outcome_ambiguous"
                    },
                    waitKind = null,
                )
                cancelledRuns += 1
            }
        }
        val matchingLeases = leases.keys.filter { it.automationId == id }
        matchingLeases.forEach(leases::remove)
        confirmations.keys.filter { it.automationId == id }.forEach(confirmations::remove)
        return AutomationCancellationResult.Cancelled(
            newRevision = expectedRevision + 1,
            inboxItemsCancelled = matchingInbox.size,
            runsCancelled = cancelledRuns,
            leasesRevoked = matchingLeases.size,
        )
    }

    @Synchronized
    override fun recordDiscovery(batch: AutomationDiscoveryBatch): AutomationDiscoveryResult {
        val definition = definitions[batch.automationId]
        if (definition == null || definition.revision != batch.definitionRevision) {
            return AutomationDiscoveryResult(false, 0, 0, "definition_revision_mismatch")
        }
        if (!definition.enabled) {
            return AutomationDiscoveryResult(false, 0, 0, "definition_disabled")
        }
        val previousCursor = cursors[batch.automationId]
        if (
            previousCursor != null &&
            previousCursor.definitionRevision == batch.definitionRevision &&
            batch.evaluatedThrough < previousCursor.evaluatedThrough
        ) {
            return AutomationDiscoveryResult(false, 0, 0, "cursor_would_move_backwards")
        }
        var inserted = 0
        var duplicates = 0
        batch.items.sortedBy { it.key }.forEach { item ->
            if (item.key in inbox || item.key in runs || item.key in receipts) {
                duplicates += 1
            } else {
                inbox[item.key] = item
                inserted += 1
            }
        }
        cursors[batch.automationId] = AutomationSchedulerCursor(
            automationId = batch.automationId,
            definitionRevision = batch.definitionRevision,
            evaluatedThrough = batch.evaluatedThrough,
        )
        return AutomationDiscoveryResult(true, inserted, duplicates)
    }

    @Synchronized
    override fun schedulerCursor(id: AutomationId): AutomationSchedulerCursor? = cursors[id]

    @Synchronized
    override fun resetSchedulerCursors(ids: Set<AutomationId>, evaluatedThrough: Instant?) {
        ids.forEach { id ->
            if (evaluatedThrough == null) {
                cursors.remove(id)
            } else {
                definitions[id]?.let { definition ->
                    cursors[id] = AutomationSchedulerCursor(
                        id,
                        definition.revision,
                        evaluatedThrough,
                    )
                }
            }
        }
    }

    @Synchronized
    override fun materializeDueInbox(
        now: Instant,
        maximumItems: Int,
    ): AutomationMaterializationResult {
        require(maximumItems in 1..10_000)
        val due = inbox.values
            .filter { it.readyAt <= now }
            .sortedWith(compareBy({ it.readyAt }, { it.key }))
            .take(maximumItems)
        val created = mutableListOf<AutomationRunKey>()
        val skipped = mutableListOf<AutomationRunKey>()
        var duplicates = 0
        due.forEach { item ->
            inbox.remove(item.key)
            if (item.key in runs || item.key in receipts) {
                duplicates += 1
                return@forEach
            }
            val definition = definitions[item.key.automationId]
            val current = definition != null &&
                definition.enabled &&
                definition.revision == item.definitionRevision
            val run = AutomationRun(
                key = item.key,
                definitionRevision = item.definitionRevision,
                state = if (current) AutomationRunState.PENDING else AutomationRunState.SKIPPED,
                attemptCount = 0,
                availableAt = item.readyAt,
                createdAt = item.discoveredAt,
                updatedAt = now,
                lastFailureCode = if (current) null else "stale_or_disabled_definition",
            )
            runs[item.key] = run
            if (current) created += item.key else skipped += item.key
        }
        return AutomationMaterializationResult(created, skipped, duplicates)
    }

    @Synchronized
    override fun releaseDeferredRuns(
        resolved: Set<AutomationResolvedPrecondition>,
        now: Instant,
    ): Int {
        if (resolved.isEmpty()) return 0
        val releasedCodes = resolved.mapTo(hashSetOf()) { it.deferredErrorCode }
        var released = 0
        runs.values.toList().forEach { run ->
            val definition = definitions[run.key.automationId]
            if (
                run.state == AutomationRunState.RETRY_WAIT &&
                run.waitKind == AutomationRunWaitKind.DEFERRED_PRECONDITION &&
                run.lastFailureCode in releasedCodes &&
                run.availableAt > now &&
                run.dispatchFence == null &&
                definition?.enabled == true &&
                definition.revision == run.definitionRevision
            ) {
                runs[run.key] = run.copy(availableAt = now, updatedAt = now)
                released += 1
            }
        }
        return released
    }

    @Synchronized
    override fun acquireNextLease(
        owner: AutomationWorkerId,
        token: AutomationLeaseToken,
        bootSessionId: AutomationBootSessionId,
        now: Instant,
        leaseDuration: Duration,
    ): AutomationLeaseClaim? {
        requireValidLeaseDuration(leaseDuration)
        require(leases.values.none { it.token == token }) { "Lease token already exists" }
        val candidates = runs.values
            .filter {
                it.state in ELIGIBLE_RUN_STATES && it.availableAt <= now &&
                    it.key !in leases && it.dispatchFence == null
            }
            .sortedBy { it.key }
        for (run in candidates) {
            val definition = definitions[run.key.automationId]
            if (
                definition == null ||
                !definition.enabled ||
                definition.revision != run.definitionRevision
            ) {
                runs[run.key] = run.copy(
                    state = AutomationRunState.SKIPPED,
                    updatedAt = now,
                    lastFailureCode = "stale_or_disabled_definition",
                    waitKind = null,
                )
                continue
            }
            val leasedRun = run.copy(
                state = AutomationRunState.LEASED,
                attemptCount = run.attemptCount + 1,
                updatedAt = now,
                waitKind = null,
            )
            val lease = AutomationLease(
                key = run.key,
                token = token,
                owner = owner,
                bootSessionId = bootSessionId,
                generation = leasedRun.attemptCount,
                acquiredAt = now,
                heartbeatAt = now,
                expiresAt = now.plus(leaseDuration),
            )
            runs[run.key] = leasedRun
            leases[run.key] = lease
            return AutomationLeaseClaim(definition, leasedRun, lease)
        }
        return null
    }

    @Synchronized
    override fun heartbeat(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
        leaseDuration: Duration,
    ): AutomationHeartbeatResult {
        requireValidLeaseDuration(leaseDuration)
        val lease = leases[key] ?: return AutomationHeartbeatResult.LEASE_NOT_FOUND
        if (lease.token != token) return AutomationHeartbeatResult.TOKEN_MISMATCH
        if (now < lease.heartbeatAt) return AutomationHeartbeatResult.INVALID_TIME
        if (now >= lease.expiresAt) return AutomationHeartbeatResult.LEASE_EXPIRED
        val proposedExpiry = now.plus(leaseDuration)
        leases[key] = lease.copy(
            heartbeatAt = now,
            expiresAt = maxOf(lease.expiresAt, proposedExpiry),
        )
        return AutomationHeartbeatResult.EXTENDED
    }

    @Synchronized
    override fun markDispatchFence(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
        leaseDuration: Duration,
    ): AutomationDispatchFenceMarkResult {
        requireValidLeaseDuration(leaseDuration)
        val lease = leases[key] ?: return AutomationDispatchFenceMarkResult.LEASE_NOT_FOUND
        if (lease.token != token) return AutomationDispatchFenceMarkResult.TOKEN_MISMATCH
        if (now < lease.heartbeatAt) return AutomationDispatchFenceMarkResult.INVALID_TIME
        if (now >= lease.expiresAt) return AutomationDispatchFenceMarkResult.LEASE_EXPIRED
        val run = runs[key] ?: return AutomationDispatchFenceMarkResult.LEASE_NOT_FOUND
        val existing = run.dispatchFence
        if (existing != null) {
            return if (
                existing.leaseToken == token && existing.leaseGeneration == lease.generation
            ) {
                AutomationDispatchFenceMarkResult.ALREADY_MARKED
            } else {
                AutomationDispatchFenceMarkResult.FENCE_CONFLICT
            }
        }
        val proposedExpiry = now.plus(leaseDuration)
        leases[key] = lease.copy(
            heartbeatAt = now,
            expiresAt = maxOf(lease.expiresAt, proposedExpiry),
        )
        runs[key] = run.copy(
            updatedAt = now,
            dispatchFence = AutomationDispatchFence(token, lease.generation, now),
        )
        return AutomationDispatchFenceMarkResult.MARKED
    }

    @Synchronized
    override fun clearDispatchFenceAfterRejected(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
    ): AutomationDispatchFenceClearResult {
        val lease = leases[key] ?: return AutomationDispatchFenceClearResult.LEASE_NOT_FOUND
        if (lease.token != token) return AutomationDispatchFenceClearResult.TOKEN_MISMATCH
        if (now < lease.heartbeatAt) return AutomationDispatchFenceClearResult.INVALID_TIME
        if (now >= lease.expiresAt) return AutomationDispatchFenceClearResult.LEASE_EXPIRED
        val run = runs[key] ?: return AutomationDispatchFenceClearResult.LEASE_NOT_FOUND
        val fence = run.dispatchFence ?: return AutomationDispatchFenceClearResult.NOT_MARKED
        if (fence.leaseToken != token || fence.leaseGeneration != lease.generation) {
            return AutomationDispatchFenceClearResult.FENCE_CONFLICT
        }
        runs[key] = run.copy(updatedAt = now, dispatchFence = null)
        return AutomationDispatchFenceClearResult.CLEARED
    }

    @Synchronized
    override fun completeLease(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
        completion: AutomationCompletion,
    ): AutomationCompletionResult {
        val lease = leases[key] ?: return AutomationCompletionResult.LEASE_NOT_FOUND
        if (lease.token != token) return AutomationCompletionResult.TOKEN_MISMATCH
        if (now < lease.heartbeatAt) return AutomationCompletionResult.INVALID_TIME
        if (now >= lease.expiresAt) return AutomationCompletionResult.LEASE_EXPIRED
        val run = checkNotNull(runs[key])
        val definition = definitions[key.automationId]
            ?.takeIf { it.enabled && it.revision == run.definitionRevision }
        leases.remove(key)
        return when (completion) {
            AutomationCompletion.Succeeded -> {
                runs[key] = run.copy(
                    state = AutomationRunState.SUCCEEDED,
                    updatedAt = now,
                    lastFailureCode = null,
                    waitKind = null,
                    dispatchFence = null,
                )
                confirmations.remove(key)
                AutomationCompletionResult.COMPLETED
            }
            is AutomationCompletion.PermanentFailure -> {
                runs[key] = run.copy(
                    state = AutomationRunState.FAILED_TERMINAL,
                    updatedAt = now,
                    lastFailureCode = completion.errorCode,
                    waitKind = null,
                    // A permanent outcome prevents redispatch, while the retained fence remains
                    // the audit/manual-review evidence that this attempt crossed dispatch.
                    dispatchFence = run.dispatchFence,
                )
                confirmations.remove(key)
                AutomationCompletionResult.FAILED_TERMINAL
            }
            is AutomationCompletion.RetryableFailure -> if (run.dispatchFence != null) {
                runs[key] = run.copy(
                    state = AutomationRunState.FAILED_TERMINAL,
                    updatedAt = now,
                    lastFailureCode = "automation_outcome_ambiguous",
                    waitKind = null,
                )
                confirmations.remove(key)
                AutomationCompletionResult.FAILED_TERMINAL
            } else {
                scheduleRetryOrFail(
                    run = run,
                    definition = definition,
                    now = now,
                    errorCode = completion.errorCode,
                )
            }
            is AutomationCompletion.Deferred -> {
                if (run.dispatchFence != null) {
                    runs[key] = run.copy(
                        state = AutomationRunState.FAILED_TERMINAL,
                        updatedAt = now,
                        lastFailureCode = "automation_outcome_ambiguous",
                        waitKind = null,
                    )
                    confirmations.remove(key)
                    AutomationCompletionResult.FAILED_TERMINAL
                } else if (completion.retryAt <= now) {
                    // The lease remains owned so a malformed deferral cannot create a hot loop.
                    leases[key] = lease
                    AutomationCompletionResult.INVALID_TIME
                } else {
                    runs[key] = run.copy(
                        state = AutomationRunState.RETRY_WAIT,
                        attemptCount = (run.attemptCount - 1).coerceAtLeast(0),
                        availableAt = completion.retryAt,
                        updatedAt = now,
                        lastFailureCode = completion.errorCode,
                        waitKind = AutomationRunWaitKind.DEFERRED_PRECONDITION,
                    )
                    AutomationCompletionResult.DEFERRED
                }
            }
        }
    }

    @Synchronized
    override fun recoverLeases(
        now: Instant,
        currentBootSessionId: AutomationBootSessionId,
        forceSameBootRecovery: Boolean,
    ): AutomationLeaseRecoveryResult {
        var afterBoot = 0
        var afterExpiry = 0
        var afterClockChange = 0
        var retried = 0
        var terminal = 0
        var skipped = 0
        leases.values.toList().sortedBy { it.key }.forEach { lease ->
            val rebooted = lease.bootSessionId != currentBootSessionId
            val expired = now >= lease.expiresAt
            val clockChanged = forceSameBootRecovery && !rebooted
            if (!rebooted && !expired && !clockChanged) return@forEach
            leases.remove(lease.key)
            when {
                rebooted -> afterBoot += 1
                clockChanged -> afterClockChange += 1
                else -> afterExpiry += 1
            }
            val run = runs[lease.key] ?: return@forEach
            val definition = definitions[lease.key.automationId]
            if (run.dispatchFence != null) {
                runs[run.key] = run.copy(
                    state = AutomationRunState.FAILED_TERMINAL,
                    updatedAt = now,
                    lastFailureCode = "automation_outcome_ambiguous",
                    waitKind = null,
                )
                confirmations.remove(run.key)
                terminal += 1
            } else if (
                definition == null ||
                !definition.enabled ||
                definition.revision != run.definitionRevision
            ) {
                runs[run.key] = run.copy(
                    state = AutomationRunState.SKIPPED,
                    updatedAt = now,
                    lastFailureCode = "stale_or_disabled_definition",
                    waitKind = null,
                )
                skipped += 1
            } else {
                when (
                    scheduleRetryOrFail(
                        run,
                        definition,
                        now,
                        when {
                            rebooted -> "lease_recovered_after_reboot"
                            clockChanged -> "lease_recovered_after_clock_change"
                            else -> "lease_expired"
                        },
                    )
                ) {
                    AutomationCompletionResult.RETRY_SCHEDULED -> retried += 1
                    AutomationCompletionResult.FAILED_TERMINAL -> terminal += 1
                    else -> error("Unexpected lease recovery result")
                }
            }
        }
        return AutomationLeaseRecoveryResult(
            afterBoot,
            afterExpiry,
            retried,
            terminal,
            skipped,
            afterClockChange,
        )
    }

    @Synchronized
    override fun recoveryState(): AutomationRecoveryState? = recoveryState

    @Synchronized
    override fun storeRecoveryState(state: AutomationRecoveryState) {
        recoveryState = state
    }

    private fun scheduleRetryOrFail(
        run: AutomationRun,
        definition: AutomationDefinition?,
        now: Instant,
        errorCode: String,
    ): AutomationCompletionResult {
        val retryPolicy = definition?.retryPolicy
        if (retryPolicy != null && run.attemptCount < retryPolicy.maximumAttempts) {
            runs[run.key] = run.copy(
                state = AutomationRunState.RETRY_WAIT,
                availableAt = now.plus(retryPolicy.delayAfterFailure(run.attemptCount)),
                updatedAt = now,
                lastFailureCode = errorCode,
                waitKind = AutomationRunWaitKind.RETRY_BACKOFF,
            )
            return AutomationCompletionResult.RETRY_SCHEDULED
        }
        runs[run.key] = run.copy(
            state = AutomationRunState.FAILED_TERMINAL,
            updatedAt = now,
            lastFailureCode = errorCode,
            waitKind = null,
        )
        return AutomationCompletionResult.FAILED_TERMINAL
    }

    private fun retireOutstandingWork(
        id: AutomationId,
        now: Instant,
        errorCode: String,
    ) {
        inbox.values.filter { it.key.automationId == id }.forEach { item ->
            inbox.remove(item.key)
            if (item.key !in runs && item.key !in receipts) {
                runs[item.key] = AutomationRun(
                    key = item.key,
                    definitionRevision = item.definitionRevision,
                    state = AutomationRunState.SKIPPED,
                    attemptCount = 0,
                    availableAt = item.readyAt,
                    createdAt = item.discoveredAt,
                    updatedAt = now,
                    lastFailureCode = errorCode,
                )
            }
        }
        runs.values.toList().forEach { run ->
            if (run.key.automationId == id && run.state !in TERMINAL_RUN_STATES) {
                runs[run.key] = run.copy(
                    state = if (run.dispatchFence == null) {
                        AutomationRunState.SKIPPED
                    } else {
                        AutomationRunState.FAILED_TERMINAL
                    },
                    updatedAt = now,
                    lastFailureCode = if (run.dispatchFence == null) {
                        errorCode
                    } else {
                        "automation_outcome_ambiguous"
                    },
                    waitKind = null,
                )
            }
        }
        leases.keys.filter { it.automationId == id }.forEach(leases::remove)
        confirmations.keys.filter { it.automationId == id }.forEach(confirmations::remove)
    }

    private fun requireValidLeaseDuration(duration: Duration) {
        require(!duration.isNegative && !duration.isZero && duration <= MAX_LEASE_DURATION) {
            "Invalid lease duration"
        }
    }

    private fun <T> requireUnique(values: List<T>, label: String) {
        require(values.distinct().size == values.size) { "Duplicate $label in snapshot" }
    }

    private companion object {
        val ELIGIBLE_RUN_STATES = setOf(
            AutomationRunState.PENDING,
            AutomationRunState.RETRY_WAIT,
        )
        val TERMINAL_RUN_STATES = setOf(
            AutomationRunState.SUCCEEDED,
            AutomationRunState.FAILED_TERMINAL,
            AutomationRunState.SKIPPED,
        )
        val CONFIRMABLE_RUN_STATES = setOf(
            AutomationRunState.PENDING,
            AutomationRunState.RETRY_WAIT,
        )
        val CONFIRMATION_ACTIVE_RUN_STATES = CONFIRMABLE_RUN_STATES + AutomationRunState.LEASED
        val MAX_LEASE_DURATION: Duration = Duration.ofHours(24)
    }
}
