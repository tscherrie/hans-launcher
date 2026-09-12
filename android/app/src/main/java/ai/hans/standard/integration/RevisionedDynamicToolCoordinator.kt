package ai.hans.standard.integration

import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolNamespaceSpec

internal sealed interface DynamicToolSnapshotApplyResult {
    data class Applied(
        val previousRevision: String,
        val revision: String,
        val migration: DynamicToolMigrationResult,
    ) : DynamicToolSnapshotApplyResult

    data class Unchanged(val revision: String) : DynamicToolSnapshotApplyResult
    data class Queued(val revision: String) : DynamicToolSnapshotApplyResult
    data class StaleDenied(val revision: String) : DynamicToolSnapshotApplyResult
    data class ActivationFailed(val revision: String) : DynamicToolSnapshotApplyResult
}

/**
 * Serializes contract migration and client-generation replacement around an immutable router.
 * Only the newest snapshot is retained while work is active. The activity probe must include
 * App Server turns/calls, dictation and Live Voice.
 */
internal class RevisionedDynamicToolCoordinator(
    private val router: RevisionedDynamicToolRouter,
    private val migrateExact: (List<DynamicToolNamespaceSpec>) -> DynamicToolMigrationResult,
    private val activityProbe: () -> Boolean,
    private val installGeneration: (
        newLease: RevisionedDynamicToolLease,
        oldLease: RevisionedDynamicToolLease,
    ) -> Unit,
) {
    private val lock = Any()
    private var activeLease = router.acquire(onQuiescent = ::onLeaseQuiescent)
    private var pending: RevisionedDynamicToolSnapshot? = null
    private var applying = false
    /** Rollback uncertainty is terminal for this process; the active old lease remains usable. */
    private var mutationFailedClosed = false

    fun interactiveExecutor(): DynamicToolExecutor = synchronized(lock) { activeLease }

    fun backgroundExecutor(): DynamicToolExecutor = synchronized(lock) {
        activeLease.backgroundExecutor()
    }

    fun currentRevision(): String = synchronized(lock) { activeLease.snapshot.revision }

    fun apply(snapshot: RevisionedDynamicToolSnapshot): DynamicToolSnapshotApplyResult {
        val start = synchronized(lock) {
            if (mutationFailedClosed) {
                return DynamicToolSnapshotApplyResult.ActivationFailed(snapshot.revision)
            }
            val current = activeLease.snapshot.revision
            if (snapshot.revision == current && pending == null) {
                return DynamicToolSnapshotApplyResult.Unchanged(current)
            }
            if (snapshot.revision != current && router.hasSeenRevision(snapshot.revision)) {
                return DynamicToolSnapshotApplyResult.StaleDenied(snapshot.revision)
            }
            if (applying || hasActivityLocked()) {
                pending = snapshot
                return DynamicToolSnapshotApplyResult.Queued(snapshot.revision)
            }
            applying = true
            snapshot
        }
        return applyAndDrain(start)
    }

    /** Event-driven retry point called after any activity edge becomes quiescent. */
    fun onActivityChanged(): DynamicToolSnapshotApplyResult? {
        val start = synchronized(lock) {
            if (mutationFailedClosed) return null
            if (applying || hasActivityLocked()) return null
            val queued = pending ?: return null
            pending = null
            if (queued.revision == activeLease.snapshot.revision) {
                return DynamicToolSnapshotApplyResult.Unchanged(queued.revision)
            }
            if (router.hasSeenRevision(queued.revision)) {
                return DynamicToolSnapshotApplyResult.StaleDenied(queued.revision)
            }
            applying = true
            queued
        }
        return applyAndDrain(start)
    }

    internal fun pendingRevision(): String? = synchronized(lock) { pending?.revision }

    private fun applyAndDrain(
        first: RevisionedDynamicToolSnapshot,
    ): DynamicToolSnapshotApplyResult {
        var candidate = first
        var firstResult: DynamicToolSnapshotApplyResult? = null
        while (true) {
            val result = applyOne(candidate)
            if (firstResult == null) firstResult = result
            val next = synchronized(lock) {
                if (mutationFailedClosed) {
                    pending = null
                    applying = false
                    return@synchronized null
                }
                val queued = pending
                if (queued == null || hasActivityLocked()) {
                    applying = false
                    null
                } else {
                    pending = null
                    when {
                        queued.revision == activeLease.snapshot.revision -> null
                        router.hasSeenRevision(queued.revision) -> null
                        else -> queued
                    }.also { if (it == null) applying = false }
                }
            } ?: break
            candidate = next
        }
        return checkNotNull(firstResult)
    }

    private fun applyOne(
        candidate: RevisionedDynamicToolSnapshot,
    ): DynamicToolSnapshotApplyResult {
        val previous = synchronized(lock) { activeLease.snapshot }
        if (!router.canAcceptNewRevision()) {
            return DynamicToolSnapshotApplyResult.ActivationFailed(candidate.revision)
        }
        val migration = runCatching { migrateExact(candidate.interactiveSpecs) }
            .getOrElse {
                return DynamicToolSnapshotApplyResult.ActivationFailed(candidate.revision)
            }
        val publication = runCatching { router.publish(candidate) }
            .getOrElse {
                restoreOrFailClosed(previous.interactiveSpecs)
                return DynamicToolSnapshotApplyResult.ActivationFailed(candidate.revision)
            }
        if (publication is DynamicToolSnapshotPublication.Unchanged) {
            return DynamicToolSnapshotApplyResult.Unchanged(publication.revision)
        }
        publication as DynamicToolSnapshotPublication.Changed
        val newLease = router.acquire(
            revision = publication.revision,
            onQuiescent = ::onLeaseQuiescent,
        )
        val oldLease = synchronized(lock) { activeLease }
        val installed = runCatching { installGeneration(newLease, oldLease) }.isSuccess
        return if (installed) {
            synchronized(lock) {
                check(activeLease === oldLease) { "Dynamic tool generation changed during install" }
                activeLease = newLease
            }
            // Closing is generation-bound: the lease stays retained until any already-admitted
            // call finishes, while every late call fails with stale_dynamic_tool_revision.
            oldLease.close()
            DynamicToolSnapshotApplyResult.Applied(
                publication.previousRevision,
                publication.revision,
                migration,
            )
        } else {
            newLease.close()
            // The old client stays authoritative. Restore its persisted contract before making
            // this never-activated candidate eligible for an exact later retry.
            val migrationRestored = runCatching {
                migrateExact(oldLease.snapshot.interactiveSpecs)
            }.isSuccess
            val publicationAborted = runCatching {
                router.abortUnactivated(publication)
            }.isSuccess
            if (!migrationRestored || !publicationAborted) {
                synchronized(lock) {
                    mutationFailedClosed = true
                    pending = null
                }
            }
            DynamicToolSnapshotApplyResult.ActivationFailed(publication.revision)
        }
    }

    private fun restoreOrFailClosed(specs: List<DynamicToolNamespaceSpec>) {
        if (runCatching { migrateExact(specs) }.isFailure) {
            synchronized(lock) {
                mutationFailedClosed = true
                pending = null
            }
        }
    }

    private fun hasActivityLocked(): Boolean =
        activeLease.hasInFlightCalls() || activityProbe()

    private fun onLeaseQuiescent() {
        onActivityChanged()
    }
}
