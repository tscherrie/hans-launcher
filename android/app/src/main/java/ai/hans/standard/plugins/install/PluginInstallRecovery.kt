package ai.hans.standard.plugins.install

/** Fresh, exact App Server proof relative to the immutable journal identity. */
internal enum class PluginInstallRemoteProof {
    EXACT,
    ABSENT,
    CHANGED,
    UNAVAILABLE,
}

/** Independently proven state of the local dependency recovery descriptor. */
internal enum class PluginInstallLocalRecoverability {
    PREPARED,
    COMMITTED,
    ABSENT,
    CHANGED,
    UNAVAILABLE,
}

internal enum class PluginInstallRecoveryAction {
    COMMIT,
    ROLLBACK,
    COMPENSATE,
    QUARANTINE,
    RETRY,
}

internal data class PluginInstallRecoveryDecision(
    val action: PluginInstallRecoveryAction,
    val reason: String,
)

/**
 * Side-effect-free recovery table. Callers must obtain both proofs independently and then execute
 * only the returned high-level action through the normal journaled transaction path.
 */
internal object PluginInstallRecovery {
    fun decide(
        phase: PluginInstallJournalPhase,
        remoteProof: PluginInstallRemoteProof,
        localRecoverability: PluginInstallLocalRecoverability,
    ): PluginInstallRecoveryDecision {
        val action = when (phase) {
            PluginInstallJournalPhase.LOCAL_PREPARING,
            PluginInstallJournalPhase.LOCAL_PREPARED,
            -> beforeRemoteIntent(remoteProof, localRecoverability)

            PluginInstallJournalPhase.REMOTE_INSTALL_INTENT,
            PluginInstallJournalPhase.REMOTE_ACCEPTED,
            PluginInstallJournalPhase.REMOTE_PROVEN_INSTALLED,
            PluginInstallJournalPhase.LOCAL_COMMIT_INTENT,
            PluginInstallJournalPhase.LOCAL_COMMITTED,
            PluginInstallJournalPhase.RECONCILE_REQUIRED,
            -> reconcileInstall(remoteProof, localRecoverability)

            PluginInstallJournalPhase.FINALIZED -> finalized(remoteProof, localRecoverability)

            PluginInstallJournalPhase.ROLLBACK_INTENT,
            PluginInstallJournalPhase.COMPENSATION_UNINSTALL_INTENT,
            PluginInstallJournalPhase.COMPENSATION_ACCEPTED,
            -> reconcileRemoval(remoteProof, localRecoverability)

            PluginInstallJournalPhase.COMPENSATION_PROVEN_ABSENT ->
                compensationProvenAbsent(remoteProof, localRecoverability)

            PluginInstallJournalPhase.QUARANTINED -> PluginInstallRecoveryAction.QUARANTINE
        }
        return PluginInstallRecoveryDecision(
            action = action,
            reason = "${phase.name.lowercase()}:${remoteProof.name.lowercase()}:" +
                "${localRecoverability.name.lowercase()}:${action.name.lowercase()}",
        )
    }

    private fun beforeRemoteIntent(
        remote: PluginInstallRemoteProof,
        local: PluginInstallLocalRecoverability,
    ): PluginInstallRecoveryAction = when {
        local == PluginInstallLocalRecoverability.CHANGED -> PluginInstallRecoveryAction.QUARANTINE
        local == PluginInstallLocalRecoverability.UNAVAILABLE -> PluginInstallRecoveryAction.RETRY
        remote == PluginInstallRemoteProof.EXACT || remote == PluginInstallRemoteProof.CHANGED ->
            PluginInstallRecoveryAction.QUARANTINE
        else -> PluginInstallRecoveryAction.ROLLBACK
    }

    private fun reconcileInstall(
        remote: PluginInstallRemoteProof,
        local: PluginInstallLocalRecoverability,
    ): PluginInstallRecoveryAction = when (remote) {
        PluginInstallRemoteProof.UNAVAILABLE -> PluginInstallRecoveryAction.RETRY
        PluginInstallRemoteProof.CHANGED -> PluginInstallRecoveryAction.QUARANTINE
        PluginInstallRemoteProof.ABSENT -> when (local) {
            PluginInstallLocalRecoverability.CHANGED -> PluginInstallRecoveryAction.QUARANTINE
            PluginInstallLocalRecoverability.UNAVAILABLE -> PluginInstallRecoveryAction.RETRY
            else -> PluginInstallRecoveryAction.ROLLBACK
        }
        PluginInstallRemoteProof.EXACT -> when (local) {
            PluginInstallLocalRecoverability.PREPARED,
            PluginInstallLocalRecoverability.COMMITTED,
            -> PluginInstallRecoveryAction.COMMIT
            PluginInstallLocalRecoverability.ABSENT -> PluginInstallRecoveryAction.COMPENSATE
            PluginInstallLocalRecoverability.CHANGED -> PluginInstallRecoveryAction.QUARANTINE
            PluginInstallLocalRecoverability.UNAVAILABLE -> PluginInstallRecoveryAction.RETRY
        }
    }

    private fun finalized(
        remote: PluginInstallRemoteProof,
        local: PluginInstallLocalRecoverability,
    ): PluginInstallRecoveryAction = when {
        remote == PluginInstallRemoteProof.UNAVAILABLE ||
            local == PluginInstallLocalRecoverability.UNAVAILABLE -> PluginInstallRecoveryAction.RETRY
        remote == PluginInstallRemoteProof.EXACT &&
            local in setOf(
                PluginInstallLocalRecoverability.PREPARED,
                PluginInstallLocalRecoverability.COMMITTED,
            ) -> PluginInstallRecoveryAction.COMMIT
        else -> PluginInstallRecoveryAction.QUARANTINE
    }

    private fun reconcileRemoval(
        remote: PluginInstallRemoteProof,
        local: PluginInstallLocalRecoverability,
    ): PluginInstallRecoveryAction = when (remote) {
        PluginInstallRemoteProof.UNAVAILABLE -> PluginInstallRecoveryAction.RETRY
        PluginInstallRemoteProof.CHANGED -> PluginInstallRecoveryAction.QUARANTINE
        PluginInstallRemoteProof.EXACT -> when (local) {
            PluginInstallLocalRecoverability.CHANGED -> PluginInstallRecoveryAction.QUARANTINE
            PluginInstallLocalRecoverability.UNAVAILABLE -> PluginInstallRecoveryAction.RETRY
            else -> PluginInstallRecoveryAction.COMPENSATE
        }
        PluginInstallRemoteProof.ABSENT -> when (local) {
            PluginInstallLocalRecoverability.CHANGED -> PluginInstallRecoveryAction.QUARANTINE
            PluginInstallLocalRecoverability.UNAVAILABLE -> PluginInstallRecoveryAction.RETRY
            else -> PluginInstallRecoveryAction.ROLLBACK
        }
    }

    private fun compensationProvenAbsent(
        remote: PluginInstallRemoteProof,
        local: PluginInstallLocalRecoverability,
    ): PluginInstallRecoveryAction = when {
        remote == PluginInstallRemoteProof.UNAVAILABLE ||
            local == PluginInstallLocalRecoverability.UNAVAILABLE -> PluginInstallRecoveryAction.RETRY
        remote != PluginInstallRemoteProof.ABSENT ||
            local == PluginInstallLocalRecoverability.CHANGED -> PluginInstallRecoveryAction.QUARANTINE
        else -> PluginInstallRecoveryAction.ROLLBACK
    }
}
