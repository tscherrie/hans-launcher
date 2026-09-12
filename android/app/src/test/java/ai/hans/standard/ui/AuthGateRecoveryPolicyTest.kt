package ai.hans.standard.ui

import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.codex.AccountUiSnapshot
import ai.hans.standard.codex.DeliveryUiSnapshot
import ai.hans.standard.codex.DispatchOptions
import ai.hans.standard.codex.SessionUiSnapshot
import ai.hans.standard.integration.ClientProblemCode
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.CodexClientProblem
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.integration.DispatchSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthGateRecoveryPolicyTest {
    @Test
    fun signedInThreadFailureRestartsSessionWithoutRequestingAnotherLoginCode() {
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.FAILED, ClientProblemCode.THREAD_RECOVERY)
        assertTrue(AuthGateRecoveryPolicy.isAuthenticatedRecovery(client))
        assertEquals(listOf("restart"), retryCalls(client))
    }

    @Test
    fun signedInNonAuthFailuresNeverRequestAnotherLoginCode() {
        ClientProblemCode.entries.filterNot { it == ClientProblemCode.AUTHENTICATION }.forEach { problem ->
            assertEquals(
                listOf("restart"),
                retryCalls(snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.FAILED, problem)),
            )
        }
    }

    @Test
    fun actualAuthenticationFailureRequestsLoginEvenIfAccountSnapshotWasSignedIn() {
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.FAILED, ClientProblemCode.AUTHENTICATION)
        assertFalse(AuthGateRecoveryPolicy.isAuthenticatedRecovery(client))
        assertEquals(listOf("login"), retryCalls(client))
    }

    @Test
    fun signedOutAndPendingLoginStillOfferFreshDeviceCode() {
        listOf(
            snapshot(AccountPhase.SIGNED_OUT, ClientSessionPhase.AUTH_REQUIRED),
            snapshot(AccountPhase.LOGIN_PENDING, ClientSessionPhase.LOGIN_PENDING),
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.AUTH_REQUIRED),
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.LOGIN_PENDING),
        ).forEach { client -> assertEquals(listOf("login"), retryCalls(client)) }
    }

    @Test
    fun missingOrUnknownRuntimeStateRechecksSessionWithoutInventingARequiredLogin() {
        assertEquals(listOf("restart"), retryCalls(null))
        assertEquals(
            listOf("restart"),
            retryCalls(snapshot(AccountPhase.UNKNOWN, ClientSessionPhase.FAILED, ClientProblemCode.RUNTIME_FAILED)),
        )
    }

    @Test
    fun loginRequestUnavailableFallsBackToOrdinarySessionRestart() {
        val calls = mutableListOf<String>()
        assertTrue(
            AuthGateRecoveryPolicy.retry(
                snapshot(AccountPhase.SIGNED_OUT, ClientSessionPhase.AUTH_REQUIRED),
                loginWithDeviceCode = { calls += "login"; false },
                restartSession = { calls += "restart"; true },
            ),
        )
        assertEquals(listOf("login", "restart"), calls)
    }

    @Test
    fun unavailableSessionRestartDoesNotFallBackToNewLogin() {
        assertFalse(
            AuthGateRecoveryPolicy.retry(
                snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.FAILED, ClientProblemCode.THREAD_RECOVERY),
                loginWithDeviceCode = { error("Must not request a new login for thread recovery") },
                restartSession = { false },
            ),
        )
    }

    private fun retryCalls(client: CodexClientSnapshot?): List<String> {
        val calls = mutableListOf<String>()
        assertTrue(
            AuthGateRecoveryPolicy.retry(
                client,
                loginWithDeviceCode = { calls += "login"; true },
                restartSession = { calls += "restart"; true },
            ),
        )
        return calls
    }

    private fun snapshot(
        accountPhase: AccountPhase,
        sessionPhase: ClientSessionPhase,
        problem: ClientProblemCode? = null,
    ): CodexClientSnapshot = CodexClientSnapshot(
        runtimePhase = ClientRuntimePhase.READY,
        sessionPhase = sessionPhase,
        generation = 1,
        session = SessionUiSnapshot(
            account = AccountUiSnapshot(accountPhase, null, null, null, null, null),
            currentThreadId = null,
            threads = emptyList(),
            delivery = DeliveryUiSnapshot(1, 1, null, false),
        ),
        models = emptyList(),
        deviceCodeLogin = null,
        outboundTimeline = emptyList(),
        timeline = emptyList(),
        pendingSelection = null,
        confirmedSelection = DispatchSelection(
            DispatchOptions.DEFAULT.model,
            DispatchOptions.DEFAULT.effort,
        ),
        problem = problem?.let { CodexClientProblem(it, retryable = true) },
    )
}
