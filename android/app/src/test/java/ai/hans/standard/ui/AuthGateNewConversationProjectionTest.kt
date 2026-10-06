package ai.hans.standard.ui

import ai.hans.standard.R
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
import ai.hans.standard.integration.CodexMigrationReadiness
import ai.hans.standard.integration.DispatchSelection
import ai.hans.standard.integration.DeviceCodeLoginUi
import ai.hans.standard.localization.TestResourceTextResolver
import ai.hans.standard.settings.HansSettings
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthGateNewConversationProjectionTest {
    @Test fun onlyAuthenticatedIdleThreadRecoveryOffersANewConversation() {
        val snapshot = recoverable()
        val state = project(snapshot)
        assertEquals(AuthGateStage.ERROR, state.stage)
        assertTrue(state.sessionRecovery)
        assertTrue(state.canStartNewConversation)
        assertEquals("old-thread-reference", snapshot.session.currentThreadId)
        assertFalse(project(null).canStartNewConversation)
        assertFalse(AuthGateUiState().canStartNewConversation)
    }

    @Test fun everyOtherRuntimeAccountSessionProblemAndActiveWorkCombinationIsExcluded() {
        val base = recoverable()
        AccountPhase.entries.filterNot { it == AccountPhase.SIGNED_IN }.forEach { phase ->
            assertFalse("account $phase", project(base.copy(session = base.session.copy(
                account = base.session.account.copy(phase = phase),
            ))).canStartNewConversation)
        }
        ClientRuntimePhase.entries.filterNot { it == ClientRuntimePhase.READY }.forEach { phase ->
            assertFalse("runtime $phase", project(base.copy(runtimePhase = phase)).canStartNewConversation)
        }
        ClientSessionPhase.entries.filterNot { it == ClientSessionPhase.FAILED }.forEach { phase ->
            assertFalse("session $phase", project(base.copy(sessionPhase = phase)).canStartNewConversation)
        }
        ClientProblemCode.entries.filterNot { it == ClientProblemCode.THREAD_RECOVERY }.forEach { problem ->
            assertFalse("problem $problem", project(base.copy(
                problem = CodexClientProblem(problem, retryable = true),
            )).canStartNewConversation)
        }
        assertFalse(project(base.copy(problem = null)).canStartNewConversation)
        assertFalse(project(base.copy(migrationReadiness = CodexMigrationReadiness(activeTurn = true)))
            .canStartNewConversation)
        assertFalse(project(base.copy(pendingDynamicToolCalls = 1)).canStartNewConversation)
        assertFalse(project(base.copy(remotePhoneToolsActive = true)).canStartNewConversation)
        assertFalse(project(base.copy(deviceCodeLogin = DeviceCodeLoginUi("CODE", "https://example.test/login")))
            .canStartNewConversation)
    }

    @Test fun recoveryExplanationPreservesOldReferencesAndDoesNotPromiseHistoryImportInEitherLanguage() {
        val english = TestResourceTextResolver(Locale.ENGLISH)
        val german = TestResourceTextResolver(Locale.GERMAN)
        val fallback = TestResourceTextResolver(Locale.FRENCH)
        val en = english.text(R.string.conversation_recovery_explanation)
        val de = german.text(R.string.conversation_recovery_explanation)
        assertTrue(en.contains("No previous conversation is deleted"))
        assertTrue(en.contains("saved reference is kept"))
        assertTrue(en.contains("not imported"))
        assertTrue(de.contains("Kein bisheriges Gespräch wird gelöscht"))
        assertTrue(de.contains("Verweis bleibt erhalten"))
        assertTrue(de.contains("nicht in das neue Gespräch übernommen"))
        assertEquals(en, fallback.text(R.string.conversation_recovery_explanation))
    }

    private fun project(snapshot: CodexClientSnapshot?): AuthGateUiState = HansClientUiProjector.project(
        snapshot, HansLocalUiState(), HansSettings(), TestResourceTextResolver(Locale.ENGLISH),
    ).authGate

    private fun recoverable() = CodexClientSnapshot(
        runtimePhase = ClientRuntimePhase.READY,
        sessionPhase = ClientSessionPhase.FAILED,
        generation = 1,
        session = SessionUiSnapshot(
            account = AccountUiSnapshot(AccountPhase.SIGNED_IN, null, null, null, null, null),
            currentThreadId = "old-thread-reference",
            threads = emptyList(),
            delivery = DeliveryUiSnapshot(1, 1, null, false),
        ),
        models = emptyList(), deviceCodeLogin = null, outboundTimeline = emptyList(),
        timeline = emptyList(), pendingSelection = null,
        confirmedSelection = DispatchSelection(DispatchOptions.DEFAULT.model, DispatchOptions.DEFAULT.effort),
        problem = CodexClientProblem(ClientProblemCode.THREAD_RECOVERY, retryable = true),
    )
}
