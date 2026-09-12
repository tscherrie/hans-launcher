package ai.hans.standard.plugins.install

/** Opaque startup-recovery work item. App Server orchestration only needs these safe selectors. */
internal data class PluginInstallRecoveryClaim(
    val operationId: String,
    val pluginId: String,
)

/** Result of reconciling one claimed journal entry against fresh remote and local evidence. */
internal sealed interface PluginInstallRecoveryExecution {
    /** The exact installation or rollback is complete and its journal entry was removed. */
    data object Finalized : PluginInstallRecoveryExecution

    /** App Server must uninstall this exact plugin before another fresh absence proof. */
    data class CompensationRequired(
        val operationId: String,
        val pluginId: String,
    ) : PluginInstallRecoveryExecution

    /** Evidence is temporarily unavailable. Keep the durable journal and retry later. */
    data class Deferred(val reason: String) : PluginInstallRecoveryExecution

    /** Evidence changed or a local action failed. The entry remains fail-closed for inspection. */
    data class Quarantined(val reason: String) : PluginInstallRecoveryExecution
}
