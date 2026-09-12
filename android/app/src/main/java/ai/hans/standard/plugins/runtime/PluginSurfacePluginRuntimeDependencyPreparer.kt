package ai.hans.standard.plugins.runtime

import ai.hans.standard.plugins.EmptyPluginRuntimeDependencyTransaction
import ai.hans.standard.plugins.PluginRuntimeDependencyComponentRecoveryProvider
import ai.hans.standard.plugins.PluginRuntimeDependencyPreparer
import ai.hans.standard.plugins.PluginRuntimeDependencyTransaction
import ai.hans.standard.plugins.PluginRuntimeDependencyRecoveryResult
import ai.hans.standard.plugins.PluginRuntimePreparationCancellation
import ai.hans.standard.plugins.PluginRuntimeRequirements
import ai.hans.standard.plugins.install.PluginDependencyRecoveryComponent
import ai.hans.standard.plugins.install.PluginDependencyRecoveryComponentKind
import ai.hans.standard.plugins.install.PluginInstallJournalPhase
import ai.hans.standard.plugins.install.PluginInstallLocalRecoverability
import ai.hans.standard.plugins.runtime.PluginSurfacePrivatePhase.ACTIVE
import ai.hans.standard.plugins.runtime.PluginSurfacePrivatePhase.COMMITTED
import ai.hans.standard.plugins.runtime.PluginSurfacePrivatePhase.PREPARED
import java.io.File

/**
 * Atomically prepares and publishes only the root-free Hans surface of one plugin.
 *
 * Fresh App Server detail is never ambient: [PluginSurfaceEvidenceStager] must first create the
 * unique receipt consumed here. Commit merely journals a reversible local decision; the active
 * inventory and signed Android routes become visible together only at [finalizeCommit].
 */
internal class PluginSurfacePluginRuntimeDependencyPreparer(
    private val preflight: PluginSurfacePreflight,
    private val store: PluginSurfaceActivationStore,
) : PluginRuntimeDependencyPreparer, PluginRuntimeDependencyComponentRecoveryProvider {
    override fun prepare(
        requirements: PluginRuntimeRequirements,
        sourceRoot: File,
        cancellation: PluginRuntimePreparationCancellation,
    ): PluginRuntimeDependencyTransaction {
        cancellation.throwIfCancellationRequested()
        return when (preflight.declaration(requirements.pluginId, sourceRoot)) {
            PluginSurfaceManifestLoadResult.NotDeclared -> {
                require(store.recordsFor(requirements.pluginId).isEmpty()) {
                    "Undeclared plugin surface has private staged evidence"
                }
                require(store.active(requirements.pluginId) == null) {
                    "Undeclared plugin surface requires exact deactivation"
                }
                EmptyPluginRuntimeDependencyTransaction
            }
            is PluginSurfaceManifestLoadResult.Rejected ->
                throw IllegalArgumentException("Plugin surface manifest is invalid")
            is PluginSurfaceManifestLoadResult.Declared -> {
                val prepared = store.claimUnique(
                    pluginId = requirements.pluginId,
                    sourceRoot = sourceRoot,
                ) { staged ->
                    cancellation.throwIfCancellationRequested()
                    val ready = checkNotNull(preflight.revalidate(staged)) {
                        "Plugin surface evidence changed after staging"
                    }
                    require(ready.stateSha256 == staged.stateSha256) {
                        "Plugin surface entrypoint evidence changed after staging"
                    }
                    cancellation.throwIfCancellationRequested()
                }
                cancellation.throwIfCancellationRequested()
                transactionFor(prepared, committed = false)
            }
        }
    }

    override fun recover(
        pluginId: String,
        descriptor: PluginDependencyRecoveryComponent?,
        journalPhase: PluginInstallJournalPhase,
    ): PluginRuntimeDependencyRecoveryResult {
        if (!store.isAvailable()) return without(PluginInstallLocalRecoverability.UNAVAILABLE)
        val records = runCatching { store.recordsFor(pluginId) }.getOrElse {
            return without(PluginInstallLocalRecoverability.UNAVAILABLE)
        }
        val published = runCatching { store.active(pluginId) }.getOrElse {
            return without(PluginInstallLocalRecoverability.UNAVAILABLE)
        }
        if (descriptor == null) {
            return without(
                if (records.isEmpty() && published == null) {
                    PluginInstallLocalRecoverability.ABSENT
                } else {
                    PluginInstallLocalRecoverability.CHANGED
                },
            )
        }
        if (!descriptor.isSurfaceDescriptor()) {
            return without(PluginInstallLocalRecoverability.CHANGED)
        }
        if (records.size > 1) return without(PluginInstallLocalRecoverability.CHANGED)
        val receipt = records.singleOrNull()
        if (receipt != null) {
            if (!receipt.matches(pluginId, descriptor)) {
                return without(PluginInstallLocalRecoverability.CHANGED)
            }
            val ready = runCatching { preflight.revalidate(receipt) }.getOrElse {
                return without(PluginInstallLocalRecoverability.UNAVAILABLE)
            } ?: return without(PluginInstallLocalRecoverability.CHANGED)
            if (ready.stateSha256 != descriptor.stateDigest) {
                return without(PluginInstallLocalRecoverability.CHANGED)
            }
            if (!receipt.hasExpectedPublication(published)) {
                return without(PluginInstallLocalRecoverability.CHANGED)
            }
            return when (receipt.phase) {
                PREPARED -> {
                    if (published?.sameRecoveryIdentity(receipt) == true) {
                        without(PluginInstallLocalRecoverability.CHANGED)
                    }
                    else recoverable(receipt, committed = false)
                }
                COMMITTED -> recoverable(receipt, committed = true)
                PluginSurfacePrivatePhase.STAGED,
                ACTIVE,
                -> without(PluginInstallLocalRecoverability.CHANGED)
            }
        }
        if (published == null) return without(PluginInstallLocalRecoverability.ABSENT)
        if (!published.matches(pluginId, descriptor)) {
            return without(PluginInstallLocalRecoverability.CHANGED)
        }
        if (journalPhase !in FINALIZED_LOCAL_PHASES) {
            return without(PluginInstallLocalRecoverability.CHANGED)
        }
        val ready = runCatching { preflight.revalidate(published) }.getOrElse {
            return without(PluginInstallLocalRecoverability.UNAVAILABLE)
        } ?: return without(PluginInstallLocalRecoverability.CHANGED)
        if (ready.stateSha256 != descriptor.stateDigest) {
            return without(PluginInstallLocalRecoverability.CHANGED)
        }
        return PluginRuntimeDependencyRecoveryResult.recoverable(
            PluginInstallLocalRecoverability.COMMITTED,
            FinalizedPluginSurfaceTransaction(
                descriptor = descriptor,
                resolvedEntrypointIds = ready.inventory.resolvedEntrypointIds,
                availableCapabilityIds = ready.inventory.availableCapabilityIds,
            ),
        )
    }

    /** Exact post-uninstall cleanup. Neither plugin id nor digest alone is sufficient authority. */
    fun deactivate(pluginId: String, descriptor: PluginDependencyRecoveryComponent): Boolean {
        require(descriptor.isSurfaceDescriptor()) { "Not a plugin surface recovery receipt" }
        return store.deactivate(pluginId, descriptor.transactionId, descriptor.stateDigest)
    }

    private fun transactionFor(
        record: PluginSurfacePrivateRecord,
        committed: Boolean,
    ): PluginRuntimeDependencyTransaction {
        val ready = checkNotNull(preflight.revalidate(record)) {
            "Plugin surface entrypoint evidence changed"
        }
        return PluginSurfaceDependencyTransaction(
            store = store,
            initialRecord = record,
            initiallyCommitted = committed,
            resolvedEntrypointIds = ready.inventory.resolvedEntrypointIds,
            availableCapabilityIds = ready.inventory.availableCapabilityIds,
        )
    }

    private fun recoverable(
        record: PluginSurfacePrivateRecord,
        committed: Boolean,
    ): PluginRuntimeDependencyRecoveryResult = runCatching {
        PluginRuntimeDependencyRecoveryResult.recoverable(
            if (committed) PluginInstallLocalRecoverability.COMMITTED
            else PluginInstallLocalRecoverability.PREPARED,
            transactionFor(record, committed),
        )
    }.getOrElse { without(PluginInstallLocalRecoverability.CHANGED) }

    private fun without(state: PluginInstallLocalRecoverability) =
        PluginRuntimeDependencyRecoveryResult.withoutTransaction(state)

    private fun PluginSurfacePrivateRecord.matches(
        expectedPluginId: String,
        descriptor: PluginDependencyRecoveryComponent,
    ): Boolean = pluginId == expectedPluginId &&
        receiptId == descriptor.transactionId && stateSha256 == descriptor.stateDigest

    private fun PluginSurfacePrivateRecord.sameRecoveryIdentity(
        other: PluginSurfacePrivateRecord,
    ): Boolean = receiptId == other.receiptId && pluginId == other.pluginId &&
        stateSha256 == other.stateSha256

    private fun PluginSurfacePrivateRecord.hasExpectedPublication(
        published: PluginSurfacePrivateRecord?,
    ): Boolean = when {
        published == null -> previousReceiptId == null
        published.sameRecoveryIdentity(this) -> phase == COMMITTED
        else -> published.receiptId == previousReceiptId &&
            published.stateSha256 == previousStateSha256
    }

    private fun PluginDependencyRecoveryComponent.isSurfaceDescriptor(): Boolean =
        kind == PluginDependencyRecoveryComponentKind.PLUGIN_SURFACE_V1 &&
            componentId == PluginDependencyRecoveryComponent.PLUGIN_SURFACE_COMPONENT_ID &&
            secondaryTransactionId == null && secondaryStateDigest == null

    private companion object {
        val FINALIZED_LOCAL_PHASES = setOf(
            PluginInstallJournalPhase.LOCAL_COMMITTED,
            PluginInstallJournalPhase.FINALIZED,
        )
    }
}

private class PluginSurfaceDependencyTransaction(
    private val store: PluginSurfaceActivationStore,
    initialRecord: PluginSurfacePrivateRecord,
    initiallyCommitted: Boolean,
    override val resolvedEntrypointIds: Set<String>,
    override val availableCapabilityIds: Set<String>,
) : PluginRuntimeDependencyTransaction {
    private var record = initialRecord
    private var state = if (initiallyCommitted) State.COMMITTED else State.PREPARED

    override val recoveryDescriptor = record.surfaceDescriptor()

    @Synchronized
    override fun commit() {
        if (state == State.COMMITTED || state == State.FINALIZED) return
        check(state == State.PREPARED) { "Plugin surface transaction is not prepared" }
        record = store.markCommitted(record)
        state = State.COMMITTED
    }

    @Synchronized
    override fun rollback() {
        if (state == State.ROLLED_BACK) return
        check(state != State.FINALIZED) { "A finalized plugin surface cannot be rolled back" }
        store.rollback(record)
        state = State.ROLLED_BACK
    }

    @Synchronized
    override fun finalizeCommit() {
        if (state == State.FINALIZED) return
        check(state == State.COMMITTED) { "Plugin surface transaction is not committed" }
        record = store.finalizeActivation(record)
        state = State.FINALIZED
    }

    private enum class State { PREPARED, COMMITTED, ROLLED_BACK, FINALIZED }
}

private class FinalizedPluginSurfaceTransaction(
    descriptor: PluginDependencyRecoveryComponent,
    override val resolvedEntrypointIds: Set<String>,
    override val availableCapabilityIds: Set<String>,
) : PluginRuntimeDependencyTransaction {
    override val recoveryDescriptor = descriptor.toSingleDescriptor()
    override fun commit() = Unit
    override fun finalizeCommit() = Unit
    override fun rollback(): Unit = error("A finalized plugin surface cannot be rolled back")
}

private fun PluginSurfacePrivateRecord.surfaceDescriptor() = PluginDependencyRecoveryComponent(
    kind = PluginDependencyRecoveryComponentKind.PLUGIN_SURFACE_V1,
    componentId = PluginDependencyRecoveryComponent.PLUGIN_SURFACE_COMPONENT_ID,
    transactionId = receiptId,
    stateDigest = stateSha256,
).toSingleDescriptor()

private fun PluginDependencyRecoveryComponent.toSingleDescriptor() =
    ai.hans.standard.plugins.install.PluginDependencyRecoveryDescriptor.composite(listOf(this))
