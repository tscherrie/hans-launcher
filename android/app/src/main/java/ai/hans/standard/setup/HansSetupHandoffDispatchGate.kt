package ai.hans.standard.setup

import ai.hans.standard.integration.BundledSetupBootstrapStatus
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase

internal object HansSetupHandoffDispatchGate {
    fun allowsNewTurn(
        runtimePhase: ClientRuntimePhase,
        sessionPhase: ClientSessionPhase,
        bootstrapStatus: BundledSetupBootstrapStatus,
        selectionAvailable: Boolean,
        setupSkillAvailable: Boolean,
    ): Boolean = runtimePhase == ClientRuntimePhase.READY &&
        sessionPhase == ClientSessionPhase.READY &&
        bootstrapStatus == BundledSetupBootstrapStatus.READY &&
        selectionAvailable && setupSkillAvailable
}
