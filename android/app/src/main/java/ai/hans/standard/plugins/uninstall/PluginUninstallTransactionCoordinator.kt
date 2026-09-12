package ai.hans.standard.plugins.uninstall

import ai.hans.standard.plugins.BoundedPluginSourceIdentityHasher
import ai.hans.standard.plugins.PluginListWireResult
import ai.hans.standard.plugins.PluginSourceHashCancellation
import ai.hans.standard.plugins.PluginSourceIdentityHasher
import ai.hans.standard.plugins.PluginWireRecord
import android.os.SystemClock
import java.io.File
import java.util.LinkedHashMap
import java.util.UUID

internal sealed interface PluginUninstallPreparation {
    data class Prepared(val operationId: String, val pluginId: String) : PluginUninstallPreparation
    data class Rejected(val reason: String) : PluginUninstallPreparation
}

internal sealed interface PluginUninstallExecution {
    /** Remote absence and exact local deactivation are both durable. */
    data object Removed : PluginUninstallExecution
    /** Exact target remains installed; no local activation changed and a later retry is safe. */
    data object Retry : PluginUninstallExecution
    /** Evidence changed or is incomplete. The durable claim stays quarantined. */
    data class Quarantined(val reason: String) : PluginUninstallExecution
    /** A store failed after deactivation intent. Durable recovery must continue before mutations. */
    data class Deferred(val reason: String) : PluginUninstallExecution
}

internal data class PluginUninstallRecoveryClaim(
    val operationId: String,
    val pluginId: String,
)

/**
 * Fail-closed ordinary plugin uninstall.
 *
 * `plugin/uninstall` success is only an acceptance receipt. The local runtime remains published
 * until a second force-refetched, load-error-free `plugin/list` proves the exact target absent.
 */
internal class PluginUninstallTransactionCoordinator(
    private val runtime: PluginRuntimeUninstallManager,
    private val journal: PluginUninstallJournal,
    private val sourceHasher: PluginSourceIdentityHasher = BoundedPluginSourceIdentityHasher(),
    private val remoteProver: PluginUninstallRemoteProver =
        ExactPluginUninstallRemoteProver(sourceHasher),
    private val onRuntimePublicationChanged: () -> Unit = {},
    private val ownerProcessEpoch: String = randomId("process"),
    private val leaseIdFactory: () -> String = { randomId("lease") },
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lock = Any()
    private val pending = LinkedHashMap<String, Pending>()
    private val recovery = LinkedHashMap<String, RecoveryClaim>()

    /** Blocking source/store proof. Invoke on the plugin preparation executor. */
    fun prepare(
        operationId: String,
        record: PluginWireRecord,
    ): PluginUninstallPreparation {
        PluginUninstallBounds.requireOpaqueId(operationId, "operation")
        if (!record.card.installed || record.card.pluginId != record.locator.pluginId) {
            return PluginUninstallPreparation.Rejected("uninstall_target_not_installed")
        }
        synchronized(lock) {
            require(operationId !in pending && operationId !in recovery) {
                "Duplicate plugin uninstall operation"
            }
            require(pending.size < PluginUninstallBounds.MAX_ENTRIES) {
                "Too many plugin uninstall operations"
            }
        }
        val target = captureTarget(record)
            ?: return PluginUninstallPreparation.Rejected("uninstall_target_identity_unavailable")
        val descriptor = when (val snapshot = runCatching {
            runtime.snapshot(target.pluginId)
        }.getOrElse { PluginRuntimeUninstallSnapshot.Unavailable }) {
            is PluginRuntimeUninstallSnapshot.Exact -> snapshot.descriptor
            PluginRuntimeUninstallSnapshot.Changed ->
                return PluginUninstallPreparation.Rejected("uninstall_runtime_identity_changed")
            PluginRuntimeUninstallSnapshot.Unavailable ->
                return PluginUninstallPreparation.Rejected("uninstall_runtime_identity_unavailable")
        }
        if (descriptor.components.isNotEmpty() && target.canonicalSourceRoot == null) {
            return PluginUninstallPreparation.Rejected("uninstall_runtime_source_identity_missing")
        }
        val wall = wallClockMillis()
        val elapsed = elapsedRealtimeMillis()
        val entry = PluginUninstallJournalEntry(
            operationId = operationId,
            leaseId = leaseIdFactory(),
            revision = 1L,
            ownerProcessEpoch = ownerProcessEpoch,
            phase = PluginUninstallJournalPhase.TARGET_CAPTURED,
            target = target,
            runtime = descriptor,
            timestamps = PluginUninstallJournalTimestamps(wall, wall, elapsed, elapsed),
        )
        val created = runCatching { journal.create(entry) }.getOrNull()
        if (created != PluginUninstallJournalMutationResult.APPLIED) {
            return PluginUninstallPreparation.Rejected("uninstall_journal_unavailable")
        }
        synchronized(lock) {
            if (pending.putIfAbsent(operationId, Pending(entry, State.PREPARED)) != null) {
                runCatching { journal.remove(entry.cursor) }
                error("Plugin uninstall operation changed during preparation")
            }
        }
        return PluginUninstallPreparation.Prepared(operationId, target.pluginId)
    }

    /** Persisted before plugin/uninstall enters the transport. */
    fun markRemoteUninstallIntent(operationId: String) = synchronized(lock) {
        val current = requirePending(operationId, State.PREPARED)
        current.entry = advance(current.entry, PluginUninstallJournalPhase.REMOTE_UNINSTALL_INTENT)
        current.state = State.REMOTE_INTENT
    }

    /** Direct success reply is acceptance, never proof. */
    fun markRemoteAccepted(operationId: String) = synchronized(lock) {
        val current = requirePending(operationId, State.REMOTE_INTENT)
        current.entry = advance(current.entry, PluginUninstallJournalPhase.REMOTE_ACCEPTED)
        current.state = State.REMOTE_ACCEPTED
    }

    /** Blocking source hash and local store mutation. Invoke away from the controller monitor. */
    fun proveAndDeactivate(
        operationId: String,
        listed: PluginListWireResult,
    ): PluginUninstallExecution {
        val claimed = synchronized(lock) {
            requirePending(operationId, State.REMOTE_ACCEPTED).also {
                it.state = State.EVALUATING
            }
        }
        return when (remoteProver.prove(claimed.entry.target, listed)) {
            PluginUninstallRemoteProof.ABSENT -> deactivateClaimed(
                operationId = operationId,
                claimed = claimed,
                allowAlreadyAbsent = false,
            )
            PluginUninstallRemoteProof.PRESENT_EXACT -> {
                removeLiveJournal(operationId, claimed)
                PluginUninstallExecution.Retry
            }
            PluginUninstallRemoteProof.CONFLICT -> quarantineLive(
                operationId,
                claimed,
                "uninstall_remote_identity_changed",
            )
            PluginUninstallRemoteProof.UNKNOWN -> quarantineLive(
                operationId,
                claimed,
                "uninstall_remote_absence_unprovable",
            )
        }
    }

    /**
     * Failure before remote intent is discardable. At/after intent it is ambiguous and therefore
     * remains a durable startup-recovery claim; local routes stay live.
     */
    fun fail(operationId: String): Boolean {
        val value = synchronized(lock) { pending.remove(operationId) } ?: return false
        return when (value.state) {
            State.PREPARED -> {
                require(journal.remove(value.entry.cursor) == PluginUninstallJournalMutationResult.APPLIED)
                true
            }
            State.REMOTE_INTENT,
            State.REMOTE_ACCEPTED,
            State.EVALUATING,
            -> {
                value.entry = advance(value.entry, PluginUninstallJournalPhase.RECONCILE_REQUIRED)
                true
            }
        }
    }

    fun abortAll() {
        val values = synchronized(lock) {
            pending.values.toList().also {
                pending.clear()
                // Recovery leases are process-generation scoped. Their journals stay durable,
                // but an aborted App Server generation must not leave an in-memory claim that
                // prevents the next generation from taking the same entry over with a new CAS
                // lease.
                recovery.clear()
            }
        }
        var failure: Throwable? = null
        values.forEach { value ->
            runCatching {
                when (value.state) {
                    State.PREPARED -> require(
                        journal.remove(value.entry.cursor) ==
                            PluginUninstallJournalMutationResult.APPLIED,
                    )
                    else -> value.entry = advance(
                        value.entry,
                        PluginUninstallJournalPhase.RECONCILE_REQUIRED,
                    )
                }
            }.onFailure { current ->
                if (failure == null) failure = current else failure?.addSuppressed(current)
            }
        }
        failure?.let { throw it }
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    /** Exact-CAS takeover of all unfinished entries from an earlier process epoch. */
    fun claimRecoveryBatch(): List<PluginUninstallRecoveryClaim> {
        synchronized(lock) { check(pending.isEmpty()) { "Uninstall recovery raced a live operation" } }
        journal.readAll().sortedBy(PluginUninstallJournalEntry::operationId).forEach { entry ->
            if (synchronized(lock) { entry.operationId in recovery }) return@forEach
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
                    PluginUninstallJournalMutationResult.APPLIED,
            ) { "Plugin uninstall recovery lease changed" }
            synchronized(lock) {
                check(recovery.put(entry.operationId, RecoveryClaim(replacement)) == null)
            }
        }
        return synchronized(lock) {
            recovery.values.map {
                PluginUninstallRecoveryClaim(it.entry.operationId, it.entry.target.pluginId)
            }
        }
    }

    /** Reconciles one claim using the same fresh complete plugin/list shared by the recovery batch. */
    fun recoverClaim(
        operationId: String,
        listed: PluginListWireResult,
    ): PluginUninstallExecution {
        val claim = synchronized(lock) {
            requireNotNull(recovery[operationId]) { "Unknown plugin uninstall recovery operation" }
        }
        if (claim.entry.phase == PluginUninstallJournalPhase.TARGET_CAPTURED) {
            removeRecoveryJournal(claim)
            return PluginUninstallExecution.Retry
        }
        if (claim.entry.phase == PluginUninstallJournalPhase.LOCAL_DEACTIVATED ||
            claim.entry.phase == PluginUninstallJournalPhase.FINALIZED
        ) {
            return finalizeRecoveredPublication(claim)
        }
        return when (remoteProver.prove(claim.entry.target, listed)) {
            PluginUninstallRemoteProof.ABSENT -> deactivateRecoveryClaim(claim)
            PluginUninstallRemoteProof.PRESENT_EXACT -> {
                if (claim.entry.phase in PRE_DEACTIVATION_PHASES) {
                    removeRecoveryJournal(claim)
                    PluginUninstallExecution.Retry
                } else {
                    quarantineRecovery(claim, "uninstall_remote_reappeared_after_absence")
                }
            }
            PluginUninstallRemoteProof.CONFLICT ->
                quarantineRecovery(claim, "uninstall_recovery_identity_changed")
            PluginUninstallRemoteProof.UNKNOWN ->
                quarantineRecovery(claim, "uninstall_recovery_absence_unprovable")
        }
    }

    fun recoveryPendingCount(): Int = synchronized(lock) { recovery.size }

    private fun deactivateClaimed(
        operationId: String,
        claimed: Pending,
        allowAlreadyAbsent: Boolean,
    ): PluginUninstallExecution {
        return try {
            claimed.entry = advance(claimed.entry, PluginUninstallJournalPhase.REMOTE_PROVEN_ABSENT)
            claimed.entry = advance(claimed.entry, PluginUninstallJournalPhase.LOCAL_DEACTIVATION_INTENT)
            when (runtime.deactivateExact(claimed.entry.runtime, allowAlreadyAbsent)) {
                PluginRuntimeUninstallDeactivationResult.EXACT -> {
                    claimed.entry = advance(
                        claimed.entry,
                        PluginUninstallJournalPhase.LOCAL_DEACTIVATED,
                    )
                    onRuntimePublicationChanged()
                    claimed.entry = advance(claimed.entry, PluginUninstallJournalPhase.FINALIZED)
                    removeLiveJournal(operationId, claimed)
                    PluginUninstallExecution.Removed
                }
                PluginRuntimeUninstallDeactivationResult.CHANGED ->
                    quarantineLive(operationId, claimed, "uninstall_local_identity_changed")
                PluginRuntimeUninstallDeactivationResult.UNAVAILABLE ->
                    deferLive(operationId, claimed, "uninstall_local_state_unavailable")
                PluginRuntimeUninstallDeactivationResult.PARTIAL ->
                    deferLive(operationId, claimed, "uninstall_local_cleanup_partial")
            }
        } catch (_: Throwable) {
            deferLive(operationId, claimed, "uninstall_local_cleanup_failed")
        }
    }

    private fun deactivateRecoveryClaim(claim: RecoveryClaim): PluginUninstallExecution {
        return try {
            if (claim.entry.phase !in POST_ABSENCE_PHASES) {
                claim.entry = advance(
                    claim.entry,
                    PluginUninstallJournalPhase.REMOTE_PROVEN_ABSENT,
                )
            }
            val recoveringPartial = claim.entry.phase ==
                PluginUninstallJournalPhase.LOCAL_DEACTIVATION_INTENT
            if (!recoveringPartial) {
                claim.entry = advance(
                    claim.entry,
                    PluginUninstallJournalPhase.LOCAL_DEACTIVATION_INTENT,
                )
            }
            when (runtime.deactivateExact(claim.entry.runtime, recoveringPartial)) {
                PluginRuntimeUninstallDeactivationResult.EXACT -> {
                    claim.entry = advance(
                        claim.entry,
                        PluginUninstallJournalPhase.LOCAL_DEACTIVATED,
                    )
                    onRuntimePublicationChanged()
                    claim.entry = advance(claim.entry, PluginUninstallJournalPhase.FINALIZED)
                    removeRecoveryJournal(claim)
                    PluginUninstallExecution.Removed
                }
                PluginRuntimeUninstallDeactivationResult.CHANGED ->
                    quarantineRecovery(claim, "uninstall_recovery_local_identity_changed")
                PluginRuntimeUninstallDeactivationResult.UNAVAILABLE ->
                    PluginUninstallExecution.Deferred("uninstall_recovery_local_unavailable")
                PluginRuntimeUninstallDeactivationResult.PARTIAL ->
                    PluginUninstallExecution.Deferred("uninstall_recovery_local_partial")
            }
        } catch (_: Throwable) {
            PluginUninstallExecution.Deferred("uninstall_recovery_local_failed")
        }
    }

    /** Publication is part of the durable uninstall commit, not best-effort UI cleanup. */
    private fun finalizeRecoveredPublication(claim: RecoveryClaim): PluginUninstallExecution =
        try {
            // Repeating this after a crash is intentional and idempotent: the revisioned router
            // returns Unchanged when the exact post-uninstall contract was already published.
            onRuntimePublicationChanged()
            if (claim.entry.phase == PluginUninstallJournalPhase.LOCAL_DEACTIVATED) {
                claim.entry = advance(claim.entry, PluginUninstallJournalPhase.FINALIZED)
            }
            removeRecoveryJournal(claim)
            PluginUninstallExecution.Removed
        } catch (_: Throwable) {
            PluginUninstallExecution.Deferred("uninstall_runtime_publication_failed")
        }

    private fun removeLiveJournal(operationId: String, value: Pending) {
        check(journal.remove(value.entry.cursor) == PluginUninstallJournalMutationResult.APPLIED)
        synchronized(lock) { check(pending.remove(operationId, value)) }
    }

    private fun quarantineLive(
        operationId: String,
        value: Pending,
        reason: String,
    ): PluginUninstallExecution.Quarantined {
        if (value.entry.phase != PluginUninstallJournalPhase.QUARANTINED) {
            value.entry = advance(value.entry, PluginUninstallJournalPhase.QUARANTINED)
        }
        synchronized(lock) { pending.remove(operationId, value) }
        return PluginUninstallExecution.Quarantined(reason)
    }

    private fun deferLive(
        operationId: String,
        value: Pending,
        reason: String,
    ): PluginUninstallExecution.Deferred {
        synchronized(lock) { pending.remove(operationId, value) }
        return PluginUninstallExecution.Deferred(reason)
    }

    private fun quarantineRecovery(
        claim: RecoveryClaim,
        reason: String,
    ): PluginUninstallExecution.Quarantined {
        if (claim.entry.phase != PluginUninstallJournalPhase.QUARANTINED) {
            claim.entry = advance(claim.entry, PluginUninstallJournalPhase.QUARANTINED)
        }
        return PluginUninstallExecution.Quarantined(reason)
    }

    private fun removeRecoveryJournal(claim: RecoveryClaim) {
        check(journal.remove(claim.entry.cursor) == PluginUninstallJournalMutationResult.APPLIED)
        synchronized(lock) { check(recovery.remove(claim.entry.operationId, claim)) }
    }

    private fun requirePending(operationId: String, state: State): Pending {
        val current = requireNotNull(pending[operationId]) { "Unknown plugin uninstall operation" }
        require(current.state == state) { "Plugin uninstall operation phase changed" }
        return current
    }

    private fun advance(
        current: PluginUninstallJournalEntry,
        phase: PluginUninstallJournalPhase,
    ): PluginUninstallJournalEntry {
        val replacement = current.copy(
            revision = current.revision + 1L,
            ownerProcessEpoch = ownerProcessEpoch,
            phase = phase,
            timestamps = current.timestamps.copy(
                updatedAtWallEpochMillis = wallClockMillis(),
                updatedAtElapsedRealtimeMillis = elapsedRealtimeMillis(),
            ),
        )
        check(
            journal.compareAndSet(current.cursor, replacement) ==
                PluginUninstallJournalMutationResult.APPLIED,
        ) { "Plugin uninstall journal cursor changed" }
        return replacement
    }

    private fun captureTarget(record: PluginWireRecord): PluginUninstallTargetIdentity? {
        val source = record.localSourcePath?.let { raw ->
            val root = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
            val digest = runCatching {
                sourceHasher.digest(root, PluginSourceHashCancellation.NONE)
            }.getOrNull() ?: return null
            root.path to digest
        }
        return runCatching {
            PluginUninstallTargetIdentity(
                pluginId = record.card.pluginId,
                pluginHandleSha256 = record.card.handle.value,
                pluginName = record.locator.pluginName,
                marketplaceName = record.locator.marketplaceName,
                marketplacePath = record.locator.marketplacePath,
                installedVersion = record.localVersion,
                canonicalSourceRoot = source?.first,
                sourceSha256 = source?.second,
            )
        }.getOrNull()
    }

    private data class Pending(
        var entry: PluginUninstallJournalEntry,
        var state: State,
    )

    private data class RecoveryClaim(var entry: PluginUninstallJournalEntry)

    private enum class State { PREPARED, REMOTE_INTENT, REMOTE_ACCEPTED, EVALUATING }

    private companion object {
        val PRE_DEACTIVATION_PHASES = setOf(
            PluginUninstallJournalPhase.REMOTE_UNINSTALL_INTENT,
            PluginUninstallJournalPhase.REMOTE_ACCEPTED,
            PluginUninstallJournalPhase.RECONCILE_REQUIRED,
            PluginUninstallJournalPhase.QUARANTINED,
        )
        val POST_ABSENCE_PHASES = setOf(
            PluginUninstallJournalPhase.REMOTE_PROVEN_ABSENT,
            PluginUninstallJournalPhase.LOCAL_DEACTIVATION_INTENT,
        )

        fun randomId(prefix: String): String =
            "$prefix-${UUID.randomUUID().toString().replace("-", "")}"
    }
}
