package ai.hans.standard.plugins

import ai.hans.standard.plugins.install.PluginDependencyRecoveryDescriptor
import ai.hans.standard.plugins.install.PluginInstallJournalPhase
import ai.hans.standard.plugins.install.PluginInstallLocalRecoverability

/**
 * Proves and, when possible, reconstructs one durable local dependency transaction.
 *
 * The caller must treat [recoverability] as a point-in-time proof. Every operation on a returned
 * [transaction] is still revalidated by the owning stores against the exact durable receipts.
 */
internal fun interface PluginRuntimeDependencyRecoveryProvider {
    fun recover(
        pluginId: String,
        descriptor: PluginDependencyRecoveryDescriptor?,
        journalPhase: PluginInstallJournalPhase,
    ): PluginRuntimeDependencyRecoveryResult
}

internal data class PluginRuntimeDependencyRecoveryResult(
    val recoverability: PluginInstallLocalRecoverability,
    val transaction: PluginRuntimeDependencyTransaction?,
) {
    init {
        require(
            (recoverability == PluginInstallLocalRecoverability.PREPARED ||
                recoverability == PluginInstallLocalRecoverability.COMMITTED) ==
                (transaction != null),
        ) {
            "Only an exactly recoverable dependency receipt may expose a transaction"
        }
    }

    companion object {
        fun recoverable(
            recoverability: PluginInstallLocalRecoverability,
            transaction: PluginRuntimeDependencyTransaction,
        ) = PluginRuntimeDependencyRecoveryResult(recoverability, transaction)

        fun withoutTransaction(recoverability: PluginInstallLocalRecoverability) =
            PluginRuntimeDependencyRecoveryResult(recoverability, null)
    }
}
