package ai.hans.standard.ui

import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.integration.ClientProblemCode
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.CodexClientSnapshot

/** A failed conversation bootstrap is not evidence that the confirmed account needs a new login. */
object AuthGateRecoveryPolicy {
    fun requiresDeviceCode(client: CodexClientSnapshot?): Boolean = client != null && (
        client.problem?.code == ClientProblemCode.AUTHENTICATION ||
            client.sessionPhase == ClientSessionPhase.AUTH_REQUIRED ||
            client.sessionPhase == ClientSessionPhase.LOGIN_PENDING ||
            client.session.account.phase == AccountPhase.SIGNED_OUT ||
            client.session.account.phase == AccountPhase.LOGIN_PENDING
        )

    fun isAuthenticatedRecovery(client: CodexClientSnapshot): Boolean =
        client.session.account.phase == AccountPhase.SIGNED_IN && !requiresDeviceCode(client)

    /** Restart only the ordinary session runtime. Neither path changes app data or permissions. */
    fun retry(
        client: CodexClientSnapshot?,
        loginWithDeviceCode: () -> Boolean,
        restartSession: () -> Boolean,
    ): Boolean = if (requiresDeviceCode(client)) {
        loginWithDeviceCode() || restartSession()
    } else {
        restartSession()
    }
}
