package ai.hans.standard.plugins

import ai.hans.standard.mcp.RemoteMcpConnectionRequest
import ai.hans.standard.mcp.RemoteMcpConnectionRequiredException
import ai.hans.standard.mcp.RemoteMcpPolicyReviewRequiredException
import ai.hans.standard.mcp.RemoteMcpPolicyReviewRequest
import ai.hans.standard.plugins.install.PluginDependencyRecoveryDescriptor
import ai.hans.standard.plugins.install.PluginInstallAttempt
import ai.hans.standard.plugins.install.PluginInstallIdentity
import ai.hans.standard.plugins.install.PluginInstallJournal
import ai.hans.standard.plugins.install.PluginInstallJournalEntry
import ai.hans.standard.plugins.install.PluginInstallJournalMutationResult
import ai.hans.standard.plugins.install.PluginInstallJournalPhase
import ai.hans.standard.plugins.install.PluginInstallJournalTimestamps
import ai.hans.standard.plugins.install.ExactPluginInstallRemoteProver
import ai.hans.standard.plugins.install.PluginInstallRecovery
import ai.hans.standard.plugins.install.PluginInstallRecoveryAction
import ai.hans.standard.plugins.install.PluginInstallRecoveryClaim
import ai.hans.standard.plugins.install.PluginInstallRecoveryExecution
import ai.hans.standard.plugins.install.PluginInstallRemoteProver
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Prepared dependency state whose authority remains inside its concrete runtime owner. */
internal interface PluginRuntimeDependencyTransaction {
    val resolvedEntrypointIds: Set<String>
    val availableCapabilityIds: Set<String>
        get() = emptySet()
    val recoveryDescriptor: PluginDependencyRecoveryDescriptor?
        get() = null

    fun commit()
    fun rollback()
    fun finalizeCommit()
}

internal fun interface PluginRuntimeDependencyPreparer {
    /** Blocking work. Callers must invoke it away from the App Server/controller monitor. */
    fun prepare(
        requirements: PluginRuntimeRequirements,
        sourceRoot: File,
        cancellation: PluginRuntimePreparationCancellation,
    ): PluginRuntimeDependencyTransaction
}

/** Removes only a previously committed callable registry after an independently proven uninstall. */
internal fun interface PluginRuntimeDependencyRemoval {
    fun removeCommitted(pluginId: String, expectedEnvironmentDigest: String): Boolean

    companion object {
        val DENY_ALL = PluginRuntimeDependencyRemoval { _, _ -> false }
    }
}

/** Push-based cancellation so session loss immediately interrupts resolver and download I/O. */
internal interface PluginRuntimePreparationCancellation {
    fun isCancellationRequested(): Boolean
    fun onCancel(action: () -> Unit): Closeable

    fun throwIfCancellationRequested() {
        if (isCancellationRequested()) throw PluginRuntimePreparationCancelledException()
    }
}

internal class PluginRuntimePreparationCancelledException :
    java.util.concurrent.CancellationException("Plugin runtime preparation was cancelled")

internal class MutablePluginRuntimePreparationCancellation : PluginRuntimePreparationCancellation {
    private val cancelled = AtomicBoolean(false)
    private val listenerLock = Any()
    private val listeners = LinkedHashSet<CancellationListener>()

    override fun isCancellationRequested(): Boolean = cancelled.get()

    override fun onCancel(action: () -> Unit): Closeable {
        val listener = CancellationListener(action)
        val invokeNow = synchronized(listenerLock) {
            if (cancelled.get()) {
                true
            } else {
                listeners += listener
                false
            }
        }
        if (invokeNow) listener.invokeOnce()
        return Closeable { synchronized(listenerLock) { listeners.remove(listener) } }
    }

    fun cancel(): Boolean {
        if (!cancelled.compareAndSet(false, true)) return false
        val snapshot = synchronized(listenerLock) {
            listeners.toList().also { listeners.clear() }
        }
        snapshot.forEach { listener -> runCatching(listener::invokeOnce) }
        return true
    }

    private class CancellationListener(private val action: () -> Unit) {
        private val invoked = AtomicBoolean(false)

        fun invokeOnce() {
            if (invoked.compareAndSet(false, true)) action()
        }
    }
}

internal sealed interface PluginInstallRuntimePreparation {
    data object StandardCodexPlugin : PluginInstallRuntimePreparation

    data class Prepared(
        val operationId: String,
        val handle: PluginHandle,
        val compatibility: PluginCompatibilitySnapshot,
    ) : PluginInstallRuntimePreparation

    /** No remote install occurred; the user must explicitly connect the exact current server. */
    data class RemoteMcpConnectionRequired(
        val request: RemoteMcpConnectionRequest,
    ) : PluginInstallRuntimePreparation

    /** No effect occurred and no journal remains; explicit per-tool approval is required. */
    data class RemoteMcpPolicyReviewRequired(
        val request: RemoteMcpPolicyReviewRequest,
        val sourceSha256: String,
    ) : PluginInstallRuntimePreparation

    data class Rejected(val reason: String) : PluginInstallRuntimePreparation
}

/**
 * Two-phase bridge between local runtime preparation and experimental App Server installation.
 * A runtime is committed only after a fresh plugin/list proves the exact card installed.
 */
internal class PluginInstallTransactionCoordinator(
    private val compatibility: PluginRuntimeCompatibilityService,
    private val dependencies: PluginRuntimeDependencyPreparer,
    private val availableCapabilities: () -> Set<String>,
    private val runtimeRemoval: PluginRuntimeDependencyRemoval =
        PluginRuntimeDependencyRemoval.DENY_ALL,
    private val sourceIdentityHasher: PluginSourceIdentityHasher =
        BoundedPluginSourceIdentityHasher(),
    private val dependencyRecovery: PluginRuntimeDependencyRecoveryProvider? =
        dependencies as? PluginRuntimeDependencyRecoveryProvider,
    private val remoteRecoveryProver: PluginInstallRemoteProver =
        ExactPluginInstallRemoteProver(sourceIdentityHasher),
    private val installJournal: PluginInstallJournal? = null,
    /** Event-driven publication edge; it must never perform plugin/network discovery itself. */
    private val onRuntimePublicationChanged: () -> Unit = {},
    private val ownerProcessEpoch: String = randomOpaqueId("process"),
    private val leaseIdFactory: () -> String = { randomOpaqueId("lease") },
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lock = Any()
    private val pending = LinkedHashMap<String, Pending>()
    private val preparing = LinkedHashMap<String, MutablePluginRuntimePreparationCancellation>()
    private val recoveryClaims = LinkedHashMap<String, RecoveryClaim>()

    fun prepare(
        operationId: String,
        record: PluginWireRecord,
    ): PluginInstallRuntimePreparation {
        requireOperationId(operationId)
        val cancellation = MutablePluginRuntimePreparationCancellation()
        synchronized(lock) {
            require(operationId !in pending && operationId !in preparing) {
                "Duplicate plugin runtime operation"
            }
            require(pending.size + preparing.size < MAX_PENDING) {
                "Too many plugin runtime operations"
            }
            preparing[operationId] = cancellation
        }
        try {
            val initial = runCatching {
                compatibility.evaluateForInstall(
                    record = record,
                    dependenciesResolved = false,
                    resolvedEntrypointIds = emptySet(),
                    availableCapabilityIds = availableCapabilities(),
                )
            }.getOrElse {
                return PluginInstallRuntimePreparation.Rejected(
                    "runtime_compatibility_preflight_failed",
                )
            }
            return when (initial) {
                PluginRuntimeCompatibilityEvaluation.StandardCodexPlugin ->
                    PluginInstallRuntimePreparation.StandardCodexPlugin
                is PluginRuntimeCompatibilityEvaluation.Rejected ->
                    PluginInstallRuntimePreparation.Rejected(initial.reason)
                is PluginRuntimeCompatibilityEvaluation.Evaluated -> prepareDeclaredRuntime(
                    operationId = operationId,
                    record = record,
                    initial = initial,
                    cancellation = cancellation,
                )
            }
        } finally {
            synchronized(lock) {
                preparing.remove(operationId, cancellation)
            }
        }
    }

    private fun prepareDeclaredRuntime(
        operationId: String,
        record: PluginWireRecord,
        initial: PluginRuntimeCompatibilityEvaluation.Evaluated,
        cancellation: MutablePluginRuntimePreparationCancellation,
    ): PluginInstallRuntimePreparation {
        val preparedIdentity = try {
            PreparedPluginIdentity.capture(
                record = record,
                sourceRoot = initial.manifest.sourceRoot,
                hasher = sourceIdentityHasher,
                cancellation = PluginSourceHashCancellation {
                    cancellation.isCancellationRequested()
                },
            )
        } catch (_: PluginRuntimePreparationCancelledException) {
            return PluginInstallRuntimePreparation.Rejected("runtime_prepare_cancelled")
        } catch (_: Throwable) {
            return PluginInstallRuntimePreparation.Rejected("runtime_source_identity_unavailable")
        } ?: return PluginInstallRuntimePreparation.Rejected("runtime_source_identity_unavailable")
        var journalEntry = try {
            createPreparingJournalEntry(operationId, record, preparedIdentity)
        } catch (_: Throwable) {
            return PluginInstallRuntimePreparation.Rejected("runtime_install_journal_unavailable")
        }
        val transaction = try {
            dependencies.prepare(
                initial.manifest.requirements,
                initial.manifest.sourceRoot,
                cancellation,
            )
        } catch (_: PluginRuntimePreparationCancelledException) {
            discardUncommittedJournal(journalEntry)
            return PluginInstallRuntimePreparation.Rejected("runtime_prepare_cancelled")
        } catch (required: RemoteMcpConnectionRequiredException) {
            // Composite preparation has already rolled every earlier local component back. If a
            // rollback failed it is attached as suppressed evidence and the durable journal must
            // remain for recovery instead of presenting a misleading Connect action.
            if (required.suppressed.isNotEmpty()) {
                return PluginInstallRuntimePreparation.Rejected(
                    "runtime_dependency_rollback_failed",
                )
            }
            if (!discardUncommittedJournal(journalEntry)) {
                return PluginInstallRuntimePreparation.Rejected(
                    "runtime_install_journal_unavailable",
                )
            }
            return PluginInstallRuntimePreparation.RemoteMcpConnectionRequired(required.request)
        } catch (required: RemoteMcpPolicyReviewRequiredException) {
            // Composite preparation has rolled back every earlier component. Never expose review
            // after an incomplete rollback, and never retain the pre-effect install journal.
            if (required.suppressed.isNotEmpty()) {
                return PluginInstallRuntimePreparation.Rejected(
                    "runtime_dependency_rollback_failed",
                )
            }
            if (!discardUncommittedJournal(journalEntry)) {
                return PluginInstallRuntimePreparation.Rejected(
                    "runtime_install_journal_unavailable",
                )
            }
            return PluginInstallRuntimePreparation.RemoteMcpPolicyReviewRequired(
                request = required.request,
                sourceSha256 = preparedIdentity.sourceSha256,
            )
        } catch (_: Throwable) {
            discardUncommittedJournal(journalEntry)
            return PluginInstallRuntimePreparation.Rejected("runtime_dependency_prepare_failed")
        }
        val evaluated = runCatching {
            compatibility.evaluateForInstall(
                record = record,
                dependenciesResolved = true,
                resolvedEntrypointIds = transaction.resolvedEntrypointIds,
                availableCapabilityIds = availableCapabilities() + transaction.availableCapabilityIds,
            ) as? PluginRuntimeCompatibilityEvaluation.Evaluated
        }.getOrNull()
        val stableIdentity = try {
            evaluated?.let {
                PreparedPluginIdentity.capture(
                    record = record,
                    sourceRoot = it.manifest.sourceRoot,
                    hasher = sourceIdentityHasher,
                    cancellation = PluginSourceHashCancellation {
                        cancellation.isCancellationRequested()
                    },
                )
            }
        } catch (_: PluginRuntimePreparationCancelledException) {
            rollbackPreparedTransaction(transaction, journalEntry)
            return PluginInstallRuntimePreparation.Rejected("runtime_prepare_cancelled")
        } catch (_: Throwable) {
            null
        }
        if (
            evaluated == null ||
            !evaluated.maySendAppServerInstall() ||
            stableIdentity != preparedIdentity
        ) {
            rollbackPreparedTransaction(transaction, journalEntry)
            return PluginInstallRuntimePreparation.Rejected("runtime_compatibility_rejected")
        }
        journalEntry = try {
            journalEntry?.let { current ->
                advanceJournal(
                    current = current,
                    phase = PluginInstallJournalPhase.LOCAL_PREPARED,
                    dependencyRecovery = transaction.recoveryDescriptor,
                )
            }
        } catch (_: Throwable) {
            rollbackPreparedTransaction(transaction, journalEntry)
            return PluginInstallRuntimePreparation.Rejected("runtime_install_journal_unavailable")
        }
        val accepted = synchronized(lock) {
            val stillPreparing = preparing.remove(operationId, cancellation)
            if (!stillPreparing || cancellation.isCancellationRequested()) {
                false
            } else {
                pending[operationId] = Pending(
                    identity = preparedIdentity,
                    transaction = transaction,
                    state = State.PREPARED,
                    journalEntry = journalEntry,
                )
                true
            }
        }
        return if (!accepted) {
            rollbackPreparedTransaction(transaction, journalEntry)
            PluginInstallRuntimePreparation.Rejected("runtime_prepare_cancelled")
        } else {
            PluginInstallRuntimePreparation.Prepared(
                operationId,
                record.card.handle,
                evaluated.compatibility,
            )
        }
    }

    /** Persisted immediately before the App Server install request is transmitted. */
    fun markRemoteInstallIntent(operationId: String) {
        synchronized(lock) {
            val value = requireNotNull(pending[operationId]) {
                "Unknown plugin runtime operation"
            }
            require(value.state == State.PREPARED) { "Plugin runtime operation is not prepared" }
            value.journalEntry = value.journalEntry?.let { current ->
                advanceJournal(current, PluginInstallJournalPhase.REMOTE_INSTALL_INTENT)
            }
            value.state = State.REMOTE_INSTALL_INTENT
        }
    }

    /** Direct install reply is an acceptance receipt, never installation proof. */
    fun markAppServerAccepted(operationId: String) {
        synchronized(lock) {
            val value = requireNotNull(pending[operationId]) {
                "Unknown plugin runtime operation"
            }
            require(value.state == State.REMOTE_INSTALL_INTENT) {
                "Plugin runtime operation lacks remote install intent"
            }
            value.journalEntry = value.journalEntry?.let { current ->
                advanceJournal(current, PluginInstallJournalPhase.REMOTE_ACCEPTED)
            }
            value.state = State.APP_SERVER_ACCEPTED
        }
    }

    /** Returns true only after exact list proof, runtime commit and receipt finalization succeed. */
    fun proveAndCommit(
        operationId: String,
        refreshedRecords: List<PluginWireRecord>,
    ): Boolean {
        val value = synchronized(lock) {
            val current = requireNotNull(pending[operationId]) {
                "Unknown plugin runtime operation"
            }
            require(current.state == State.APP_SERVER_ACCEPTED) {
                "Plugin runtime operation lacks App Server acceptance"
            }
            current.state = State.COMMITTING
            current
        }
        val proven = runCatching {
            refreshedRecords.singleOrNull { record ->
                value.identity.matchesInstalledProof(record, sourceIdentityHasher)
            }
        }.getOrNull()
        if (proven == null) {
            if (refreshedRecords.any(value.identity::conflictsWithInstalledProof)) {
                quarantineAndRollbackClaimed(operationId, value)
            } else {
                rollbackClaimed(operationId, value)
            }
            return false
        }
        return try {
            value.advanceJournal(PluginInstallJournalPhase.REMOTE_PROVEN_INSTALLED)
            value.advanceJournal(PluginInstallJournalPhase.LOCAL_COMMIT_INTENT)
            value.transaction.commit()
            value.advanceJournal(PluginInstallJournalPhase.LOCAL_COMMITTED)
            value.advanceJournal(PluginInstallJournalPhase.FINALIZED)
            value.transaction.finalizeCommit()
            removeFinalizedJournal(value.journalEntry)
            synchronized(lock) {
                require(pending[operationId] === value && value.state == State.COMMITTING) {
                    "Plugin runtime operation changed"
                }
                pending.remove(operationId, value)
            }
            runCatching(onRuntimePublicationChanged)
            true
        } catch (failure: Throwable) {
            runCatching { rollbackClaimed(operationId, value) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    fun fail(operationId: String): Boolean {
        val (rollback, reconcile, cancellation) = synchronized(lock) {
            val activeCancellation = preparing.remove(operationId)
            val current = pending[operationId]
            var claimedRollback: Pending? = null
            var claimedReconcile: Pending? = null
            if (activeCancellation == null && current != null) {
                when (current.state) {
                    State.PREPARED -> {
                        current.state = State.ROLLING_BACK
                        claimedRollback = current
                    }
                    State.REMOTE_INSTALL_INTENT,
                    State.APP_SERVER_ACCEPTED,
                    -> {
                        pending.remove(operationId, current)
                        current.state = State.RECONCILE_REQUIRED
                        claimedReconcile = current
                    }
                    else -> Unit
                }
            }
            Triple(claimedRollback, claimedReconcile, activeCancellation)
        }
        if (cancellation != null) {
            cancellation.cancel()
            return true
        }
        if (rollback != null) {
            rollbackClaimed(operationId, rollback, alreadyClaimed = true)
            return true
        }
        if (reconcile != null) {
            reconcile.advanceJournal(PluginInstallJournalPhase.RECONCILE_REQUIRED)
            return true
        }
        return false
    }

    /** Session loss never leaves an inactive environment transaction orphaned. */
    fun abortAll() {
        val (cancellations, transactions, reconciliations) = synchronized(lock) {
            val activeCancellations = preparing.values.toList()
            preparing.clear()
            val activeTransactions = mutableListOf<Pair<String, Pending>>()
            val ambiguousTransactions = mutableListOf<Pending>()
            pending.entries.toList().forEach { (operationId, value) ->
                when (value.state) {
                    State.PREPARED -> {
                        value.state = State.ROLLING_BACK
                        activeTransactions += operationId to value
                    }
                    State.REMOTE_INSTALL_INTENT,
                    State.APP_SERVER_ACCEPTED,
                    -> {
                        value.state = State.RECONCILE_REQUIRED
                        pending.remove(operationId, value)
                        ambiguousTransactions += value
                    }
                    else -> Unit
                }
            }
            Triple(activeCancellations, activeTransactions, ambiguousTransactions)
        }
        cancellations.forEach(MutablePluginRuntimePreparationCancellation::cancel)
        var failure: Throwable? = null
        reconciliations.forEach { value ->
            runCatching { value.advanceJournal(PluginInstallJournalPhase.RECONCILE_REQUIRED) }
                .onFailure { reconciliationFailure ->
                    if (failure == null) {
                        failure = reconciliationFailure
                    } else {
                        failure?.addSuppressed(reconciliationFailure)
                    }
                }
        }
        transactions.forEach { (operationId, value) ->
            runCatching {
                rollbackClaimed(operationId, value, alreadyClaimed = true)
            }.onFailure { rollbackFailure ->
                if (failure == null) failure = rollbackFailure else failure?.addSuppressed(rollbackFailure)
            }
        }
        failure?.let { throw it }
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size + preparing.size }

    /**
     * Acquires a process-epoch lease over every durable install left by an earlier process.
     * Nothing remote or local is mutated beyond the CAS lease rotation itself.
     */
    fun claimRecoveryBatch(): List<PluginInstallRecoveryClaim> {
        val journal = installJournal ?: return emptyList()
        synchronized(lock) {
            check(pending.isEmpty() && preparing.isEmpty()) {
                "Plugin install recovery cannot race a live installation"
            }
        }
        journal.readAll().sortedBy(PluginInstallJournalEntry::operationId).forEach { entry ->
            if (synchronized(lock) { entry.operationId in recoveryClaims }) return@forEach
            val replacement = entry.copy(
                leaseId = leaseIdFactory(),
                revision = entry.revision + 1L,
                ownerProcessEpoch = ownerProcessEpoch,
                timestamps = entry.timestamps.copy(
                    updatedAtWallEpochMillis = wallClockMillis(),
                    updatedAtElapsedRealtimeMillis = elapsedRealtimeMillis(),
                ),
            )
            check(
                journal.compareAndSet(entry.cursor, replacement) ==
                    PluginInstallJournalMutationResult.APPLIED,
            ) { "Plugin install recovery lease changed" }
            synchronized(lock) {
                check(recoveryClaims.put(entry.operationId, RecoveryClaim(replacement)) == null) {
                    "Plugin install recovery was claimed concurrently"
                }
            }
        }
        synchronized(lock) {
            return recoveryClaims.values.map { it.publicClaim() }
        }
    }

    /**
     * Reconciles one leased entry against a fresh, complete plugin/list result. Blocking local
     * store work must be performed by the caller's recovery executor, never the controller lock.
     */
    fun recoverClaim(
        operationId: String,
        listed: PluginListWireResult,
    ): PluginInstallRecoveryExecution {
        val claim = synchronized(lock) {
            requireNotNull(recoveryClaims[operationId]) { "Unknown plugin recovery operation" }
        }
        val remote = remoteRecoveryProver.prove(claim.entry.identity, listed)
        val local = dependencyRecovery?.recover(
            pluginId = claim.entry.identity.pluginId,
            descriptor = claim.entry.dependencyRecovery,
            journalPhase = claim.entry.phase,
        ) ?: PluginRuntimeDependencyRecoveryResult.withoutTransaction(
            if (
                claim.entry.dependencyRecovery == null &&
                claim.entry.phase == PluginInstallJournalPhase.LOCAL_PREPARING
            ) {
                ai.hans.standard.plugins.install.PluginInstallLocalRecoverability.ABSENT
            } else {
                ai.hans.standard.plugins.install.PluginInstallLocalRecoverability.UNAVAILABLE
            },
        )
        val decision = PluginInstallRecovery.decide(
            phase = claim.entry.phase,
            remoteProof = remote,
            localRecoverability = local.recoverability,
        )
        return when (decision.action) {
            PluginInstallRecoveryAction.COMMIT -> runCatching {
                commitRecovered(claim, checkNotNull(local.transaction))
                PluginInstallRecoveryExecution.Finalized
            }.getOrElse { PluginInstallRecoveryExecution.Deferred(decision.reason) }

            PluginInstallRecoveryAction.ROLLBACK -> runCatching {
                rollbackRecovered(claim, local.transaction)
                PluginInstallRecoveryExecution.Finalized
            }.getOrElse { PluginInstallRecoveryExecution.Deferred(decision.reason) }

            PluginInstallRecoveryAction.COMPENSATE -> runCatching {
                if (
                    claim.entry.phase != PluginInstallJournalPhase.COMPENSATION_UNINSTALL_INTENT &&
                    claim.entry.phase != PluginInstallJournalPhase.COMPENSATION_ACCEPTED
                ) {
                    claim.advance(PluginInstallJournalPhase.COMPENSATION_UNINSTALL_INTENT)
                }
                PluginInstallRecoveryExecution.CompensationRequired(
                    operationId = claim.entry.operationId,
                    pluginId = claim.entry.identity.pluginId,
                )
            }.getOrElse { PluginInstallRecoveryExecution.Deferred(decision.reason) }

            PluginInstallRecoveryAction.QUARANTINE -> runCatching {
                if (claim.entry.phase != PluginInstallJournalPhase.QUARANTINED) {
                    claim.advance(PluginInstallJournalPhase.QUARANTINED)
                }
                PluginInstallRecoveryExecution.Quarantined(decision.reason)
            }.getOrElse { PluginInstallRecoveryExecution.Deferred(decision.reason) }

            PluginInstallRecoveryAction.RETRY ->
                PluginInstallRecoveryExecution.Deferred(decision.reason)
        }
    }

    /** Direct uninstall reply is acceptance only. A second fresh list must still prove absence. */
    fun markRecoveryCompensationAccepted(operationId: String) {
        val claim = synchronized(lock) {
            requireNotNull(recoveryClaims[operationId]) { "Unknown plugin recovery operation" }
        }
        when (claim.entry.phase) {
            PluginInstallJournalPhase.COMPENSATION_UNINSTALL_INTENT ->
                claim.advance(PluginInstallJournalPhase.COMPENSATION_ACCEPTED)
            PluginInstallJournalPhase.COMPENSATION_ACCEPTED -> Unit
            else -> error("Plugin recovery lacks compensation intent")
        }
    }

    fun recoveryPendingCount(): Int = synchronized(lock) { recoveryClaims.size }

    /**
     * Called only after a fresh App Server list proves the plugin absent. The exact environment
     * identity prevents a late uninstall receipt from removing a newer plugin activation.
     */
    fun removeCommittedRuntime(
        pluginId: String,
        expectedEnvironmentDigest: String,
    ): Boolean = runtimeRemoval.removeCommitted(pluginId, expectedEnvironmentDigest).also { removed ->
        if (removed) runCatching(onRuntimePublicationChanged)
    }

    private fun commitRecovered(
        claim: RecoveryClaim,
        transaction: PluginRuntimeDependencyTransaction,
    ) {
        when (claim.entry.phase) {
            PluginInstallJournalPhase.FINALIZED -> Unit
            PluginInstallJournalPhase.LOCAL_COMMITTED ->
                claim.advance(PluginInstallJournalPhase.FINALIZED)
            PluginInstallJournalPhase.LOCAL_COMMIT_INTENT -> {
                transaction.commit()
                claim.advance(PluginInstallJournalPhase.LOCAL_COMMITTED)
                claim.advance(PluginInstallJournalPhase.FINALIZED)
            }
            else -> {
                claim.advance(PluginInstallJournalPhase.REMOTE_PROVEN_INSTALLED)
                claim.advance(PluginInstallJournalPhase.LOCAL_COMMIT_INTENT)
                transaction.commit()
                claim.advance(PluginInstallJournalPhase.LOCAL_COMMITTED)
                claim.advance(PluginInstallJournalPhase.FINALIZED)
            }
        }
        transaction.finalizeCommit()
        removeRecoveredJournal(claim)
        runCatching(onRuntimePublicationChanged)
    }

    private fun rollbackRecovered(
        claim: RecoveryClaim,
        transaction: PluginRuntimeDependencyTransaction?,
    ) {
        if (claim.entry.phase != PluginInstallJournalPhase.ROLLBACK_INTENT) {
            claim.advance(PluginInstallJournalPhase.ROLLBACK_INTENT)
        }
        transaction?.rollback()
        removeRecoveredJournal(claim)
        runCatching(onRuntimePublicationChanged)
    }

    private fun RecoveryClaim.advance(phase: PluginInstallJournalPhase) {
        entry = advanceJournal(entry, phase)
    }

    private fun removeRecoveredJournal(claim: RecoveryClaim) {
        val journal = checkNotNull(installJournal) { "Plugin install journal is unavailable" }
        check(
            journal.remove(claim.entry.cursor) == PluginInstallJournalMutationResult.APPLIED,
        ) { "Plugin install recovery journal cursor changed" }
        synchronized(lock) {
            check(recoveryClaims.remove(claim.entry.operationId, claim)) {
                "Plugin install recovery claim changed"
            }
        }
    }

    private fun createPreparingJournalEntry(
        operationId: String,
        record: PluginWireRecord,
        identity: PreparedPluginIdentity,
    ): PluginInstallJournalEntry? {
        val journal = installJournal ?: return null
        val wall = wallClockMillis()
        val elapsed = elapsedRealtimeMillis()
        val entry = PluginInstallJournalEntry(
            operationId = operationId,
            leaseId = leaseIdFactory(),
            revision = 1L,
            ownerProcessEpoch = ownerProcessEpoch,
            phase = PluginInstallJournalPhase.LOCAL_PREPARING,
            identity = identity.toJournalIdentity(record),
            installAttempt = PluginInstallAttempt(operationId, 1),
            dependencyRecovery = null,
            timestamps = PluginInstallJournalTimestamps(
                createdAtWallEpochMillis = wall,
                updatedAtWallEpochMillis = wall,
                createdAtElapsedRealtimeMillis = elapsed,
                updatedAtElapsedRealtimeMillis = elapsed,
            ),
        )
        require(journal.create(entry) == PluginInstallJournalMutationResult.APPLIED) {
            "Plugin install journal rejected a new operation"
        }
        return entry
    }

    private fun advanceJournal(
        current: PluginInstallJournalEntry,
        phase: PluginInstallJournalPhase,
        dependencyRecovery: PluginDependencyRecoveryDescriptor? = current.dependencyRecovery,
    ): PluginInstallJournalEntry {
        val journal = checkNotNull(installJournal) { "Plugin install journal is unavailable" }
        val replacement = current.copy(
            revision = current.revision + 1L,
            ownerProcessEpoch = ownerProcessEpoch,
            phase = phase,
            dependencyRecovery = dependencyRecovery,
            timestamps = current.timestamps.copy(
                updatedAtWallEpochMillis = wallClockMillis(),
                updatedAtElapsedRealtimeMillis = elapsedRealtimeMillis(),
            ),
        )
        require(
            journal.compareAndSet(current.cursor, replacement) ==
                PluginInstallJournalMutationResult.APPLIED,
        ) { "Plugin install journal cursor changed" }
        return replacement
    }

    private fun Pending.advanceJournal(phase: PluginInstallJournalPhase) {
        journalEntry = journalEntry?.let { current -> advanceJournal(current, phase) }
    }

    private fun removeFinalizedJournal(entry: PluginInstallJournalEntry?) {
        val journal = installJournal ?: return
        entry ?: return
        // Finalized runtime authority is already exact and safe. A failed cleanup remains durable
        // for startup reconciliation rather than turning a successful install into a false failure.
        runCatching { journal.remove(entry.cursor) }
    }

    private fun removeRolledBackJournal(entry: PluginInstallJournalEntry?) {
        val journal = installJournal ?: return
        entry ?: return
        require(journal.remove(entry.cursor) == PluginInstallJournalMutationResult.APPLIED) {
            "Plugin install rollback journal cursor changed"
        }
    }

    private fun discardUncommittedJournal(entry: PluginInstallJournalEntry?): Boolean {
        val journal = installJournal ?: return true
        entry ?: return true
        return runCatching {
            journal.remove(entry.cursor) == PluginInstallJournalMutationResult.APPLIED
        }.getOrDefault(false)
    }

    private fun rollbackPreparedTransaction(
        transaction: PluginRuntimeDependencyTransaction,
        entry: PluginInstallJournalEntry?,
    ) {
        var current = entry
        var failure: Throwable? = null
        val beforeRollbackIntent = current
        if (beforeRollbackIntent != null) {
            runCatching {
                current = advanceJournal(
                    beforeRollbackIntent,
                    PluginInstallJournalPhase.ROLLBACK_INTENT,
                )
            }.onFailure { failure = it }
        }
        runCatching(transaction::rollback).onFailure { rollbackFailure ->
            if (failure == null) failure = rollbackFailure else failure?.addSuppressed(rollbackFailure)
        }
        if (failure == null) {
            runCatching { removeRolledBackJournal(current) }.onFailure { failure = it }
        }
        val rollbackFailure = failure
        val failedEntry = current
        if (rollbackFailure != null && failedEntry != null) {
            runCatching { advanceJournal(failedEntry, PluginInstallJournalPhase.QUARANTINED) }
                .onFailure(rollbackFailure::addSuppressed)
        }
    }

    private fun rollbackClaimed(
        operationId: String,
        value: Pending,
        alreadyClaimed: Boolean = false,
    ) {
        if (!alreadyClaimed) {
            synchronized(lock) {
                require(pending[operationId] === value && value.state == State.COMMITTING) {
                    "Plugin runtime operation changed"
                }
                value.state = State.ROLLING_BACK
            }
        }
        try {
            value.advanceJournal(PluginInstallJournalPhase.ROLLBACK_INTENT)
            value.transaction.rollback()
            removeRolledBackJournal(value.journalEntry)
        } catch (failure: Throwable) {
            runCatching { value.advanceJournal(PluginInstallJournalPhase.QUARANTINED) }
                .onFailure(failure::addSuppressed)
            throw failure
        } finally {
            synchronized(lock) { pending.remove(operationId, value) }
        }
    }

    private fun quarantineAndRollbackClaimed(operationId: String, value: Pending) {
        synchronized(lock) {
            require(pending[operationId] === value && value.state == State.COMMITTING) {
                "Plugin runtime operation changed"
            }
            value.state = State.ROLLING_BACK
        }
        var failure: Throwable? = null
        runCatching(value.transaction::rollback).onFailure { failure = it }
        runCatching { value.advanceJournal(PluginInstallJournalPhase.QUARANTINED) }
            .onFailure { journalFailure ->
                if (failure == null) failure = journalFailure else failure?.addSuppressed(journalFailure)
            }
        synchronized(lock) { pending.remove(operationId, value) }
        failure?.let { throw it }
    }

    private data class Pending(
        val identity: PreparedPluginIdentity,
        val transaction: PluginRuntimeDependencyTransaction,
        var state: State,
        var journalEntry: PluginInstallJournalEntry?,
    )

    private data class RecoveryClaim(var entry: PluginInstallJournalEntry) {
        fun publicClaim() = PluginInstallRecoveryClaim(
            operationId = entry.operationId,
            pluginId = entry.identity.pluginId,
        )
    }

    private data class PreparedPluginIdentity(
        val locator: PluginLocator,
        val handle: PluginHandle,
        val pluginId: String,
        val expectedInstalledVersion: String,
        val canonicalSourceRoot: String,
        val sourceSha256: String,
    ) {
        fun matchesInstalledProof(
            record: PluginWireRecord,
            hasher: PluginSourceIdentityHasher,
        ): Boolean {
            if (
                !record.card.installed ||
                record.locator != locator ||
                record.card.handle != handle ||
                record.card.pluginId != pluginId ||
                record.localVersion != expectedInstalledVersion
            ) {
                return false
            }
            val proofRoot = record.localSourcePath?.let { raw ->
                runCatching { File(raw).canonicalFile }.getOrNull()
            } ?: return false
            if (proofRoot.path != canonicalSourceRoot) return false
            return runCatching { hasher.digest(proofRoot, PluginSourceHashCancellation.NONE) }
                .getOrNull() == sourceSha256
        }

        fun conflictsWithInstalledProof(record: PluginWireRecord): Boolean =
            record.card.installed && record.card.pluginId == pluginId

        fun toJournalIdentity(record: PluginWireRecord) = PluginInstallIdentity(
            pluginId = pluginId,
            pluginHandleSha256 = handle.value,
            pluginName = record.locator.pluginName,
            marketplaceName = record.locator.marketplaceName,
            marketplacePath = record.locator.marketplacePath,
            expectedInstalledVersion = expectedInstalledVersion,
            canonicalSourceRoot = canonicalSourceRoot,
            sourceSha256 = sourceSha256,
        )

        companion object {
            fun capture(
                record: PluginWireRecord,
                sourceRoot: File,
                hasher: PluginSourceIdentityHasher,
                cancellation: PluginSourceHashCancellation,
            ): PreparedPluginIdentity? {
                val expectedVersion = record.availableVersion?.takeIf(String::isNotBlank)
                    ?: return null
                val canonicalRoot = runCatching { sourceRoot.canonicalFile }.getOrNull()
                    ?: return null
                val recordRoot = record.localSourcePath?.let { raw ->
                    runCatching { File(raw).canonicalFile }.getOrNull()
                } ?: return null
                if (recordRoot != canonicalRoot) return null
                val digest = hasher.digest(canonicalRoot, cancellation)
                return PreparedPluginIdentity(
                    locator = record.locator,
                    handle = record.card.handle,
                    pluginId = record.card.pluginId,
                    expectedInstalledVersion = expectedVersion,
                    canonicalSourceRoot = canonicalRoot.path,
                    sourceSha256 = digest,
                )
            }
        }
    }

    private enum class State {
        PREPARED,
        REMOTE_INSTALL_INTENT,
        APP_SERVER_ACCEPTED,
        COMMITTING,
        ROLLING_BACK,
        RECONCILE_REQUIRED,
    }

    companion object {
        private const val MAX_PENDING = 16
        private val OPERATION_ID = Regex("[A-Za-z0-9._:-]{1,128}")

        private fun requireOperationId(value: String) {
            require(OPERATION_ID.matches(value)) { "Invalid plugin runtime operation id" }
        }

        private fun randomOpaqueId(prefix: String): String =
            "$prefix-${UUID.randomUUID().toString().replace("-", "")}"
    }
}
