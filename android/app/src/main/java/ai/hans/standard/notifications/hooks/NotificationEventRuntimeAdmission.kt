package ai.hans.standard.notifications.hooks

import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase

/** Control readiness only: an ordinary active Codex turn is a supported join, not a blocker. */
internal object NotificationEventRuntimeAdmission {
    fun allows(runtime: ClientRuntimePhase?, session: ClientSessionPhase?, account: AccountPhase?): Boolean =
        runtime == ClientRuntimePhase.READY && account == AccountPhase.SIGNED_IN &&
            session in setOf(ClientSessionPhase.READY, ClientSessionPhase.BUSY)
}
