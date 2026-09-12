package ai.hans.standard.plugins

import ai.hans.standard.plugins.install.PluginDependencyRecoveryComponent
import ai.hans.standard.plugins.install.PluginDependencyRecoveryComponentKind
import ai.hans.standard.plugins.install.PluginDependencyRecoveryDescriptor
import ai.hans.standard.plugins.install.PluginDependencyRecoveryKind
import ai.hans.standard.plugins.install.PluginInstallJournalPhase
import ai.hans.standard.plugins.install.PluginInstallLocalRecoverability
import java.io.File

/**
 * Recovery adapter for one non-Python component owner.
 *
 * A null descriptor is supplied only while recovering a crash before the outer journal obtained
 * any dependency receipt. Implementations must then return ABSENT, CHANGED or UNAVAILABLE; they
 * cannot expose an uncorrelated transaction.
 */
internal fun interface PluginRuntimeDependencyComponentRecoveryProvider {
    fun recover(
        pluginId: String,
        descriptor: PluginDependencyRecoveryComponent?,
        journalPhase: PluginInstallJournalPhase,
    ): PluginRuntimeDependencyRecoveryResult
}

/**
 * Prepares all root-free plugin runtime owners in a fixed order and publishes one durable receipt.
 */
internal class CompositePluginRuntimeDependencyPreparer(
    private val python: PluginRuntimeDependencyPreparer,
    private val surface: PluginRuntimeDependencyPreparer,
    private val remoteMcp: PluginRuntimeDependencyPreparer,
    private val recovery: CompositePluginRuntimeDependencyRecoveryProvider,
) : PluginRuntimeDependencyPreparer, PluginRuntimeDependencyRecoveryProvider {
    override fun prepare(
        requirements: PluginRuntimeRequirements,
        sourceRoot: File,
        cancellation: PluginRuntimePreparationCancellation,
    ): PluginRuntimeDependencyTransaction {
        val prepared = mutableListOf<PluginRuntimeDependencyTransaction>()
        try {
            cancellation.throwIfCancellationRequested()
            prepared += python.prepare(requirements, sourceRoot, cancellation)
            cancellation.throwIfCancellationRequested()
            prepared += surface.prepare(requirements, sourceRoot, cancellation)
            cancellation.throwIfCancellationRequested()
            prepared += remoteMcp.prepare(requirements, sourceRoot, cancellation)
            cancellation.throwIfCancellationRequested()
            return CompositePluginRuntimeDependencyTransaction.prepared(prepared)
        } catch (failure: Throwable) {
            rollbackAll(prepared.asReversed(), failure)
            throw failure
        }
    }

    override fun recover(
        pluginId: String,
        descriptor: PluginDependencyRecoveryDescriptor?,
        journalPhase: PluginInstallJournalPhase,
    ): PluginRuntimeDependencyRecoveryResult = recovery.recover(
        pluginId = pluginId,
        descriptor = descriptor,
        journalPhase = journalPhase,
    )

    private fun rollbackAll(
        transactions: List<PluginRuntimeDependencyTransaction>,
        primary: Throwable,
    ) {
        transactions.forEach { transaction ->
            runCatching(transaction::rollback).onFailure(primary::addSuppressed)
        }
    }
}

/**
 * A single local transaction spanning Python, the signed Android surface and Remote MCP.
 *
 * The list order is operational authority: Python, surface, then Remote MCP servers. Descriptor
 * components use their own canonical order and never derive authority from display order.
 */
internal class CompositePluginRuntimeDependencyTransaction private constructor(
    private val entries: List<Entry>,
    override val recoveryDescriptor: PluginDependencyRecoveryDescriptor?,
) : PluginRuntimeDependencyTransaction {
    override val resolvedEntrypointIds: Set<String> = entries
        .flatMapTo(linkedSetOf()) { it.transaction.resolvedEntrypointIds.sorted() }
    override val availableCapabilityIds: Set<String> = entries
        .flatMapTo(linkedSetOf()) { it.transaction.availableCapabilityIds.sorted() }

    private var rollbackComplete = false

    @Synchronized
    override fun commit() {
        check(!rollbackComplete) { "Composite plugin transaction was rolled back" }
        check(entries.none(Entry::rolledBack)) {
            "Composite plugin transaction rollback is incomplete"
        }
        entries.forEach { entry ->
            if (!entry.committed) {
                entry.transaction.commit()
                entry.committed = true
            }
        }
    }

    @Synchronized
    override fun rollback() {
        if (rollbackComplete) return
        check(entries.none(Entry::finalized)) {
            "A partially finalized composite transaction cannot be rolled back"
        }
        var failure: Throwable? = null
        entries.asReversed().forEach { entry ->
            if (!entry.rolledBack) {
                runCatching(entry.transaction::rollback)
                    .onSuccess { entry.rolledBack = true }
                    .onFailure { current ->
                        if (failure == null) failure = current else failure?.addSuppressed(current)
                    }
            }
        }
        if (failure != null) throw checkNotNull(failure)
        rollbackComplete = true
    }

    @Synchronized
    override fun finalizeCommit() {
        check(!rollbackComplete) { "Composite plugin transaction was rolled back" }
        check(entries.none(Entry::rolledBack)) {
            "Composite plugin transaction rollback is incomplete"
        }
        check(entries.all(Entry::committed)) {
            "Composite plugin transaction is not fully committed"
        }
        entries.forEach { entry ->
            if (!entry.finalized) {
                entry.transaction.finalizeCommit()
                entry.finalized = true
            }
        }
    }

    internal companion object {
        fun prepared(
            transactions: List<PluginRuntimeDependencyTransaction>,
        ): PluginRuntimeDependencyTransaction {
            val nonEmpty = transactions.filterNot { transaction ->
                transaction === EmptyPluginRuntimeDependencyTransaction
            }
            if (nonEmpty.isEmpty()) return EmptyPluginRuntimeDependencyTransaction
            val descriptor = descriptorFor(nonEmpty)
            return CompositePluginRuntimeDependencyTransaction(
                entries = nonEmpty.map { Entry(it, committed = false) },
                recoveryDescriptor = descriptor,
            )
        }

        fun recovered(
            descriptor: PluginDependencyRecoveryDescriptor,
            transactions: List<RecoveredTransaction>,
        ): CompositePluginRuntimeDependencyTransaction =
            CompositePluginRuntimeDependencyTransaction(
                entries = transactions.map {
                    Entry(transaction = it.transaction, committed = it.committed)
                },
                recoveryDescriptor = descriptor,
            )

        private fun descriptorFor(
            transactions: List<PluginRuntimeDependencyTransaction>,
        ): PluginDependencyRecoveryDescriptor {
            val components = transactions.flatMap { transaction ->
                checkNotNull(transaction.recoveryDescriptor) {
                    "A non-empty composite dependency has no recovery descriptor"
                }.asComponents()
            }
            return PluginDependencyRecoveryDescriptor.composite(components)
        }
    }

    internal data class RecoveredTransaction(
        val transaction: PluginRuntimeDependencyTransaction,
        val committed: Boolean,
    )

    private data class Entry(
        val transaction: PluginRuntimeDependencyTransaction,
        var committed: Boolean,
        var finalized: Boolean = false,
        var rolledBack: Boolean = false,
    )
}

/**
 * Reconstructs only transactions whose private-store receipt exactly equals its journal component.
 */
internal class CompositePluginRuntimeDependencyRecoveryProvider(
    private val python: PluginRuntimeDependencyRecoveryProvider,
    private val surface: PluginRuntimeDependencyComponentRecoveryProvider,
    private val remoteMcp: PluginRuntimeDependencyComponentRecoveryProvider,
) : PluginRuntimeDependencyRecoveryProvider {
    override fun recover(
        pluginId: String,
        descriptor: PluginDependencyRecoveryDescriptor?,
        journalPhase: PluginInstallJournalPhase,
    ): PluginRuntimeDependencyRecoveryResult {
        if (descriptor == null) return recoverUndescribed(pluginId, journalPhase)
        if (descriptor.kind == PluginDependencyRecoveryKind.PYTHON_ENVIRONMENT_V1) {
            return recoverLegacyPython(pluginId, descriptor, journalPhase)
        }

        val observations = descriptor.components.map { component ->
            val result = runCatching {
                when (component.kind) {
                    PluginDependencyRecoveryComponentKind.PYTHON_ENVIRONMENT_V1 -> python.recover(
                        pluginId,
                        component.toLegacyPythonDescriptor(),
                        journalPhase,
                    )
                    PluginDependencyRecoveryComponentKind.PLUGIN_SURFACE_V1 ->
                        surface.recover(pluginId, component, journalPhase)
                    PluginDependencyRecoveryComponentKind.REMOTE_MCP_V1 ->
                        remoteMcp.recover(pluginId, component, journalPhase)
                }
            }.getOrElse {
                PluginRuntimeDependencyRecoveryResult.withoutTransaction(
                    PluginInstallLocalRecoverability.UNAVAILABLE,
                )
            }
            Observation(component, requireExactCorrelation(component, result))
        }
        return aggregate(descriptor, observations)
    }

    private fun recoverLegacyPython(
        pluginId: String,
        descriptor: PluginDependencyRecoveryDescriptor,
        journalPhase: PluginInstallJournalPhase,
    ): PluginRuntimeDependencyRecoveryResult {
        val result = runCatching { python.recover(pluginId, descriptor, journalPhase) }
            .getOrElse {
                return PluginRuntimeDependencyRecoveryResult.withoutTransaction(
                    PluginInstallLocalRecoverability.UNAVAILABLE,
                )
            }
        val transaction = result.transaction
        if (transaction != null && transaction.recoveryDescriptor != descriptor) {
            return PluginRuntimeDependencyRecoveryResult.withoutTransaction(
                PluginInstallLocalRecoverability.CHANGED,
            )
        }
        return result
    }

    private fun recoverUndescribed(
        pluginId: String,
        journalPhase: PluginInstallJournalPhase,
    ): PluginRuntimeDependencyRecoveryResult {
        val results = listOf(
            runCatching { python.recover(pluginId, null, journalPhase) },
            runCatching { surface.recover(pluginId, null, journalPhase) },
            runCatching { remoteMcp.recover(pluginId, null, journalPhase) },
        ).map { attempt ->
            attempt.getOrElse {
                PluginRuntimeDependencyRecoveryResult.withoutTransaction(
                    PluginInstallLocalRecoverability.UNAVAILABLE,
                )
            }
        }
        val states = results.map(PluginRuntimeDependencyRecoveryResult::recoverability)
        val state = when {
            PluginInstallLocalRecoverability.CHANGED in states ->
                PluginInstallLocalRecoverability.CHANGED
            PluginInstallLocalRecoverability.UNAVAILABLE in states ->
                PluginInstallLocalRecoverability.UNAVAILABLE
            states.all { it == PluginInstallLocalRecoverability.ABSENT } ->
                PluginInstallLocalRecoverability.ABSENT
            else -> PluginInstallLocalRecoverability.CHANGED
        }
        // No outer receipt exists, so even a private-store cursor cannot be safely correlated.
        return PluginRuntimeDependencyRecoveryResult.withoutTransaction(state)
    }

    private fun requireExactCorrelation(
        expected: PluginDependencyRecoveryComponent,
        result: PluginRuntimeDependencyRecoveryResult,
    ): PluginRuntimeDependencyRecoveryResult {
        val transaction = result.transaction ?: return result
        val actual = runCatching {
            checkNotNull(transaction.recoveryDescriptor).asComponents()
        }.getOrNull()
        return if (actual == listOf(expected)) {
            result
        } else {
            PluginRuntimeDependencyRecoveryResult.withoutTransaction(
                PluginInstallLocalRecoverability.CHANGED,
            )
        }
    }

    private fun aggregate(
        descriptor: PluginDependencyRecoveryDescriptor,
        observations: List<Observation>,
    ): PluginRuntimeDependencyRecoveryResult {
        val states = observations.map { it.result.recoverability }
        if (PluginInstallLocalRecoverability.CHANGED in states) {
            return without(PluginInstallLocalRecoverability.CHANGED)
        }
        if (PluginInstallLocalRecoverability.UNAVAILABLE in states) {
            return without(PluginInstallLocalRecoverability.UNAVAILABLE)
        }
        if (states.all { it == PluginInstallLocalRecoverability.ABSENT }) {
            return without(PluginInstallLocalRecoverability.ABSENT)
        }
        // A partially vanished composite receipt cannot be committed or safely rolled back.
        if (PluginInstallLocalRecoverability.ABSENT in states) {
            return without(PluginInstallLocalRecoverability.CHANGED)
        }

        val recovered = observations.map { observation ->
            CompositePluginRuntimeDependencyTransaction.RecoveredTransaction(
                transaction = checkNotNull(observation.result.transaction),
                committed = observation.result.recoverability ==
                    PluginInstallLocalRecoverability.COMMITTED,
            )
        }
        val transaction = CompositePluginRuntimeDependencyTransaction.recovered(
            descriptor = descriptor,
            transactions = recovered,
        )
        return PluginRuntimeDependencyRecoveryResult.recoverable(
            recoverability = if (states.all {
                    it == PluginInstallLocalRecoverability.COMMITTED
                }) {
                PluginInstallLocalRecoverability.COMMITTED
            } else {
                PluginInstallLocalRecoverability.PREPARED
            },
            transaction = transaction,
        )
    }

    private fun without(
        state: PluginInstallLocalRecoverability,
    ) = PluginRuntimeDependencyRecoveryResult.withoutTransaction(state)

    private data class Observation(
        val component: PluginDependencyRecoveryComponent,
        val result: PluginRuntimeDependencyRecoveryResult,
    )
}

internal fun PluginDependencyRecoveryComponent.toSingleComponentDescriptor():
    PluginDependencyRecoveryDescriptor = PluginDependencyRecoveryDescriptor.composite(listOf(this))

internal fun PluginDependencyRecoveryComponent.toLegacyPythonDescriptor():
    PluginDependencyRecoveryDescriptor {
    require(kind == PluginDependencyRecoveryComponentKind.PYTHON_ENVIRONMENT_V1) {
        "Dependency component is not Python"
    }
    return PluginDependencyRecoveryDescriptor(
        kind = PluginDependencyRecoveryKind.PYTHON_ENVIRONMENT_V1,
        environmentTransactionId = transactionId,
        environmentDigest = stateDigest,
        entrypointTransactionId = secondaryTransactionId,
        entrypointMetadataDigest = secondaryStateDigest,
    )
}
