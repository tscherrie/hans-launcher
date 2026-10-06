package ai.hans.standard.integration

import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.codex.AccountUiSnapshot
import ai.hans.standard.codex.DeliveryUiSnapshot
import ai.hans.standard.codex.DispatchOptions
import ai.hans.standard.codex.SessionUiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitConversationRecoveryPolicyTest {
    @Test fun authenticatedIdleFailurePreservesBeforeRestart() {
        val calls = mutableListOf<String>()
        assertTrue(ExplicitConversationRecoveryPolicy.startConfirmed(
            failed(), CodexRealtimeState.IDLE, "selected-thread",
            preserveSelected = { calls += "preserve:$it"; true },
            restart = { calls += "restart" },
        ))
        assertEquals(listOf("preserve:selected-thread", "restart"), calls)
    }

    @Test fun onlyThreadRecoveryInConfirmedSignedInFailedStateIsEligible() {
        val base = failed()
        assertTrue(ExplicitConversationRecoveryPolicy.canOffer(base))
        assertFalse(ExplicitConversationRecoveryPolicy.canOffer(null))
        ClientRuntimePhase.entries.filterNot { it == ClientRuntimePhase.READY }.forEach {
            assertBlocked(base.copy(runtimePhase = it))
        }
        ClientSessionPhase.entries.filterNot { it == ClientSessionPhase.FAILED }.forEach {
            assertBlocked(base.copy(sessionPhase = it))
        }
        AccountPhase.entries.filterNot { it == AccountPhase.SIGNED_IN }.forEach {
            assertBlocked(base.copy(session = base.session.copy(account = base.session.account.copy(phase = it))))
        }
        ClientProblemCode.entries.filterNot { it == ClientProblemCode.THREAD_RECOVERY }.forEach {
            assertBlocked(base.copy(problem = CodexClientProblem(it, true)))
        }
        assertBlocked(base.copy(problem = null))
        assertBlocked(base.copy(migrationReadiness = CodexMigrationReadiness(activeTurn = true)))
        assertBlocked(base.copy(pendingDynamicToolCalls = 1))
        assertBlocked(base.copy(remotePhoneToolsActive = true))
        assertBlocked(base.copy(deviceCodeLogin = DeviceCodeLoginUi("synthetic-code", "https://example.invalid")))
    }

    @Test fun activeVoiceAndMissingSelectedPointerNeverMutateAnything() {
        CodexRealtimeState.entries.filterNot { it in setOf(CodexRealtimeState.IDLE, CodexRealtimeState.CLOSED) }.forEach {
            assertFalse(ExplicitConversationRecoveryPolicy.startConfirmed(
                failed(), it, "selected-thread", { error("No preservation while voice is active") },
                { error("No restart while voice is active") },
            ))
        }
        listOf(null, "", " ").forEach {
            assertFalse(ExplicitConversationRecoveryPolicy.startConfirmed(
                failed(), CodexRealtimeState.IDLE, it, { error("No missing-pointer mutation") },
                { error("No missing-pointer restart") },
            ))
        }
    }

    @Test fun failedOrThrowingPreservationNeverRestarts() {
        listOf<(String) -> Boolean>({ false }, { throw IllegalStateException("synthetic persistence failure") }).forEach {
            assertFalse(ExplicitConversationRecoveryPolicy.startConfirmed(
                failed(), CodexRealtimeState.CLOSED, "selected-thread", it,
                { error("Cannot restart before durable reference preservation") },
            ))
        }
    }

    @Test fun unavailableRestartNeverTriggersDestructiveFallbackOrSecondAttempt() {
        val calls = mutableListOf<String>()
        assertFalse(ExplicitConversationRecoveryPolicy.startConfirmed(
            failed(), CodexRealtimeState.CLOSED, "selected-thread",
            { calls += "preserve"; true },
            { calls += "restart"; throw IllegalStateException("synthetic restart failure") },
        ))
        assertEquals(listOf("preserve", "restart"), calls)
    }

    private fun assertBlocked(client: CodexClientSnapshot) {
        assertFalse(ExplicitConversationRecoveryPolicy.canOffer(client))
        assertFalse(ExplicitConversationRecoveryPolicy.startConfirmed(
            client, CodexRealtimeState.IDLE, "selected-thread",
            { error("Ineligible state must not preserve or clear anything") },
            { error("Ineligible state must not restart") },
        ))
    }

    private fun failed() = CodexClientSnapshot(
        runtimePhase = ClientRuntimePhase.READY,
        sessionPhase = ClientSessionPhase.FAILED,
        generation = 1,
        session = SessionUiSnapshot(
            account = AccountUiSnapshot(AccountPhase.SIGNED_IN, null, null, null, null, null),
            currentThreadId = null, threads = emptyList(),
            delivery = DeliveryUiSnapshot(1, 1, null, false),
        ),
        models = emptyList(), deviceCodeLogin = null,
        outboundTimeline = emptyList(), timeline = emptyList(), pendingSelection = null,
        confirmedSelection = DispatchSelection(DispatchOptions.DEFAULT.model, DispatchOptions.DEFAULT.effort),
        problem = CodexClientProblem(ClientProblemCode.THREAD_RECOVERY, true),
    )
}
