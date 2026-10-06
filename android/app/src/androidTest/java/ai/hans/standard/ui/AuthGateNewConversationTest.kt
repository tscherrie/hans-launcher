package ai.hans.standard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pure UI: no login, runtime restart, conversation creation, microphone or storage access. */
class AuthGateNewConversationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newConversationRequiresExplicitConfirmationAndDoesNotOptimisticallyLeaveRecovery() {
        var starts = 0
        show(callbacks = callbacks(onNew = { starts++ }))
        compose.onNodeWithTag("new_conversation_confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("offer_new_conversation").performScrollTo().performClick()
        compose.onNodeWithTag("new_conversation_confirmation").assertExists()
        compose.onNodeWithText("Kein bisheriges Gespräch wird gelöscht", substring = true).assertExists()
        compose.onNodeWithText("nicht in das neue Gespräch übernommen", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("confirm_new_conversation").performClick()
        compose.onNodeWithTag("new_conversation_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("auth_error").assertExists()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun cancellingTheConfirmationNeitherStartsNorCancelsTheExistingRecovery() {
        var starts = 0
        var cancels = 0
        var retries = 0
        show(callbacks = callbacks(onNew = { starts++ }, onCancel = { cancels++ }, onRetry = { retries++ }))
        compose.onNodeWithTag("offer_new_conversation").performScrollTo().performClick()
        compose.onNodeWithTag("cancel_new_conversation").performClick()
        compose.onNodeWithTag("new_conversation_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("auth_error").assertExists()
        compose.runOnIdle {
            assertEquals(0, starts)
            assertEquals(0, cancels)
            assertEquals(0, retries)
        }
    }

    @Test fun retryAndExistingCancelNeverCreateANewConversation() {
        var starts = 0
        var cancels = 0
        var retries = 0
        show(callbacks = callbacks(onNew = { starts++ }, onCancel = { cancels++ }, onRetry = { retries++ }))
        compose.onNodeWithTag("retry_auth").performScrollTo().performClick()
        compose.onNodeWithTag("cancel_auth").performScrollTo().performClick()
        compose.onNodeWithTag("new_conversation_confirmation").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, starts)
            assertEquals(1, cancels)
            assertEquals(1, retries)
        }
    }

    @Test fun eligibilityLossDismissesConfirmationAndARecoveryReturnNeedsFreshConsent() {
        val state = mutableStateOf(recoverable())
        var starts = 0
        show({ state.value }, callbacks(onNew = { starts++ }))
        compose.onNodeWithTag("offer_new_conversation").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(canStartNewConversation = false) }
        compose.onNodeWithTag("new_conversation_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("offer_new_conversation").assertDoesNotExist()
        compose.runOnIdle { state.value = recoverable() }
        compose.onNodeWithTag("offer_new_conversation").assertExists()
        compose.onNodeWithTag("new_conversation_confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, starts) }
    }

    @Test fun loginStagesAndUnconfirmedRecoveryNeverOfferThisAction() {
        val state = mutableStateOf(AuthGateUiState())
        show({ state.value })
        compose.onNodeWithTag("offer_new_conversation").assertDoesNotExist()
        AuthGateStage.entries.forEach { stage ->
            compose.runOnIdle {
                state.value = recoverable().copy(stage = stage, sessionRecovery = false)
            }
            compose.onNodeWithTag("offer_new_conversation").assertDoesNotExist()
        }
        AuthGateStage.entries.filterNot { it == AuthGateStage.ERROR }.forEach { stage ->
            compose.runOnIdle { state.value = recoverable().copy(stage = stage) }
            compose.onNodeWithTag("offer_new_conversation").assertDoesNotExist()
        }
    }

    private fun show(
        state: () -> AuthGateUiState = { recoverable() },
        callbacks: AuthGateUiCallbacks = callbacks(),
    ) {
        compose.setGermanContent { MaterialTheme { AuthGateScreen(state(), callbacks) } }
    }

    private fun recoverable() = AuthGateUiState(
        stage = AuthGateStage.ERROR, sessionRecovery = true, canStartNewConversation = true,
        errorTitle = "Gespräch konnte nicht geöffnet werden", errorMessage = "Du bist angemeldet.",
    )

    private fun callbacks(
        onNew: () -> Unit = {}, onCancel: () -> Unit = {}, onRetry: () -> Unit = {},
    ) = AuthGateUiCallbacks(
        onStartChatGptLogin = {}, onOpenVerificationPage = {}, onCopyUserCode = {},
        onCancel = onCancel, onRetry = onRetry, onStartNewConversation = onNew,
    )
}
