package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.localization.rememberHansTextResolver
import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Login gate used both after installation and whenever the runtime reports an expired session.
 * It renders only runtime-confirmed auth state and never advances optimistically.
 */
@Composable
fun AuthGateScreen(
    state: AuthGateUiState,
    callbacks: AuthGateUiCallbacks,
    modifier: Modifier = Modifier,
) {
    val uiText = rememberHansTextResolver()
    val canOfferNewConversation = state.canStartNewConversation &&
        state.stage == AuthGateStage.ERROR && state.sessionRecovery
    // A changed runtime eligibility invalidates an open confirmation instead of preserving consent.
    var showNewConversationConfirmation by remember(canOfferNewConversation) { mutableStateOf(false) }
    BackHandler(
        enabled = state.stage == AuthGateStage.DEVICE_CODE_AWAITING ||
            state.stage == AuthGateStage.COMPLETING ||
            state.stage == AuthGateStage.ERROR,
        onBack = callbacks.onCancel,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Hans",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.displayMedium,
        )
        Spacer(Modifier.height(34.dp))

        if (state.internetNotice.isNotBlank()) {
            Text(
                text = state.internetNotice,
                modifier = Modifier.fillMaxWidth().testTag("auth_internet_notice"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(16.dp))
        }

        when (state.stage) {
            AuthGateStage.CHECKING -> AuthProgressState(
                title = if (state.sessionRecovery) uiText.text(R.string.ui_preparing_conversation_03bea2) else uiText.text(R.string.ui_checking_sign_in_323dcf),
                body = if (state.sessionRecovery) {
                    uiText.text(R.string.ui_you_are_signed_in_hans_is_connecting_to_your_con_a8dbb4)
                } else {
                    uiText.text(R.string.ui_hans_is_checking_whether_your_chatgpt_session_is_68c8cc)
                },
                testTag = "auth_checking",
            )

            AuthGateStage.SIGNED_OUT -> SignedOutState(
                supportingMessage = state.supportingMessage,
                onStartLogin = callbacks.onStartChatGptLogin,
            )

            AuthGateStage.DEVICE_CODE_AWAITING -> DeviceCodeState(
                state = state,
                onOpenVerificationPage = callbacks.onOpenVerificationPage,
                onCopyUserCode = callbacks.onCopyUserCode,
                onCancel = callbacks.onCancel,
                onRetry = callbacks.onRetry,
            )

            AuthGateStage.COMPLETING -> {
                AuthProgressState(
                    title = uiText.text(R.string.ui_completing_sign_in_a948db),
                    body = uiText.text(R.string.ui_you_will_continue_automatically_once_chatgpt_con_499bd5),
                    testTag = "auth_completing",
                )
                Spacer(Modifier.height(22.dp))
                OutlinedButton(
                    onClick = callbacks.onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cancel_auth"),
                ) {
                    Text(stringResource(R.string.ui_cancel_f7ff11))
                }
            }

            AuthGateStage.ERROR -> ErrorState(
                title = state.errorTitle.ifBlank { uiText.text(R.string.presentation_auth_incomplete) },
                message = state.errorMessage,
                sessionRecovery = state.sessionRecovery,
                supportingMessage = state.supportingMessage,
                onRetry = callbacks.onRetry,
                onCancel = callbacks.onCancel,
                onOfferNewConversation = if (canOfferNewConversation) {
                    { showNewConversationConfirmation = true }
                } else null,
            )
        }
    }
    if (canOfferNewConversation && showNewConversationConfirmation) {
        AlertDialog(
            onDismissRequest = { showNewConversationConfirmation = false },
            modifier = Modifier.testTag("new_conversation_confirmation"),
            title = { Text(stringResource(R.string.conversation_recovery_title)) },
            text = { Text(stringResource(R.string.conversation_recovery_explanation)) },
            dismissButton = {
                TextButton(
                    onClick = { showNewConversationConfirmation = false },
                    modifier = Modifier.testTag("cancel_new_conversation"),
                ) { Text(stringResource(R.string.ui_cancel_f7ff11)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNewConversationConfirmation = false
                        if (canOfferNewConversation) callbacks.onStartNewConversation()
                    },
                    modifier = Modifier.testTag("confirm_new_conversation"),
                ) { Text(stringResource(R.string.conversation_recovery_confirm)) }
            },
        )
    }
}

@Composable
private fun AuthProgressState(
    title: String,
    body: String,
    testTag: String,
) {
    val uiText = rememberHansTextResolver()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StaticProgressMark()
        Spacer(Modifier.height(24.dp))
        Text(
            text = title,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun SignedOutState(
    supportingMessage: String,
    onStartLogin: () -> Unit,
) {
    val uiText = rememberHansTextResolver()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("auth_signed_out"),
    ) {
        Text(
            text = stringResource(R.string.ui_sign_in_with_chatgpt_6ea97b),
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.ui_hans_uses_your_existing_chatgpt_sign_in_for_code_049dfd),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        SupportingMessage(supportingMessage)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onStartLogin,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("start_chatgpt_login"),
        ) {
            Text(stringResource(R.string.ui_start_chatgpt_sign_in_72cab0))
        }
    }
}

@Composable
private fun DeviceCodeState(
    state: AuthGateUiState,
    onOpenVerificationPage: (String) -> Unit,
    onCopyUserCode: (String) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val uiText = rememberHansTextResolver()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("auth_device_code"),
    ) {
        Text(
            text = stringResource(R.string.ui_connect_chatgpt_0988a9),
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.ui_open_the_chatgpt_page_and_confirm_this_one_time__3358df),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(18.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("device_user_code"),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        ) {
            SelectionContainer {
                Text(
                    text = state.userCode.ifBlank { uiText.text(R.string.ui_loading_code_11582f) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 22.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    lineHeight = 40.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (state.verificationUri.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(
                    text = state.verificationUri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("device_verification_uri"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SupportingMessage(state.supportingMessage)
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onOpenVerificationPage(state.verificationUri) },
            enabled = state.hasCompleteDeviceCode,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("open_verification_page"),
        ) {
            Text(stringResource(R.string.ui_open_chatgpt_in_browser_abd94b))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onCopyUserCode(state.userCode) },
            enabled = state.userCode.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("copy_device_code"),
        ) {
            Text(stringResource(R.string.ui_copy_code_2ed99c))
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .testTag("cancel_auth"),
            ) {
                Text(stringResource(R.string.ui_cancel_f7ff11))
            }
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier
                    .weight(1f)
                    .testTag("retry_auth"),
            ) {
                Text(stringResource(R.string.ui_new_code_8082f2))
            }
        }
    }
}

@Composable
private fun ErrorState(
    title: String,
    message: String,
    sessionRecovery: Boolean,
    supportingMessage: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onOfferNewConversation: (() -> Unit)?,
) {
    val uiText = rememberHansTextResolver()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("auth_error"),
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message.ifBlank {
                if (sessionRecovery) uiText.text(R.string.ui_you_are_signed_in_the_connection_could_not_yet_b_1d7950)
                else uiText.text(R.string.ui_chatgpt_could_not_confirm_sign_in_da077d)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        SupportingMessage(supportingMessage)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("retry_auth"),
        ) {
            Text(stringResource(R.string.ui_try_again_948643))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cancel_auth"),
        ) {
            Text(stringResource(R.string.ui_cancel_f7ff11))
        }
        onOfferNewConversation?.let { offer ->
            TextButton(
                onClick = offer,
                modifier = Modifier.fillMaxWidth().testTag("offer_new_conversation"),
            ) { Text(stringResource(R.string.conversation_recovery_offer)) }
        }
    }
}

@Composable
private fun SupportingMessage(message: String) {
    val uiText = rememberHansTextResolver()
    if (message.isBlank()) return
    Spacer(Modifier.height(12.dp))
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("auth_supporting_message"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun StaticProgressMark() {
    val uiText = rememberHansTextResolver()
    val color = MaterialTheme.colorScheme.onBackground
    Canvas(
        modifier = Modifier
            .size(width = 74.dp, height = 24.dp)
            .semantics { contentDescription = uiText.text(R.string.ui_in_progress_e0b0f0) },
    ) {
        val radius = 7.dp.toPx()
        val centerY = size.height / 2f
        drawCircle(color, radius, Offset(radius, centerY))
        drawCircle(color, radius, Offset(size.width / 2f, centerY))
        drawCircle(color, radius, Offset(size.width - radius, centerY))
    }
}
