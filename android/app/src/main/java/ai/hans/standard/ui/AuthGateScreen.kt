package ai.hans.standard.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
                title = if (state.sessionRecovery) "Gespräch wird vorbereitet" else "Anmeldung wird geprüft",
                body = if (state.sessionRecovery) {
                    "Du bist angemeldet. Hans stellt die Verbindung zum Gespräch her."
                } else {
                    "Hans schaut nach, ob deine ChatGPT-Sitzung noch gültig ist."
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
                    title = "Anmeldung wird abgeschlossen",
                    body = "Sobald ChatGPT die Anmeldung bestätigt, geht es automatisch weiter.",
                    testTag = "auth_completing",
                )
                Spacer(Modifier.height(22.dp))
                OutlinedButton(
                    onClick = callbacks.onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cancel_auth"),
                ) {
                    Text("Abbrechen")
                }
            }

            AuthGateStage.ERROR -> ErrorState(
                title = state.errorTitle,
                message = state.errorMessage,
                sessionRecovery = state.sessionRecovery,
                supportingMessage = state.supportingMessage,
                onRetry = callbacks.onRetry,
                onCancel = callbacks.onCancel,
            )
        }
    }
}

@Composable
private fun AuthProgressState(
    title: String,
    body: String,
    testTag: String,
) {
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("auth_signed_out"),
    ) {
        Text(
            text = "Mit ChatGPT anmelden",
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Hans verwendet deine bestehende ChatGPT-Anmeldung für Codex. Dein Passwort gibst du ausschließlich bei ChatGPT ein.",
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
            Text("ChatGPT-Anmeldung starten")
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("auth_device_code"),
    ) {
        Text(
            text = "ChatGPT verbinden",
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Öffne die ChatGPT-Seite und bestätige dort diesen einmaligen Code:",
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
                    text = state.userCode.ifBlank { "Code wird geladen" },
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
            Text("ChatGPT im Browser öffnen")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onCopyUserCode(state.userCode) },
            enabled = state.userCode.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("copy_device_code"),
        ) {
            Text("Code kopieren")
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
                Text("Abbrechen")
            }
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier
                    .weight(1f)
                    .testTag("retry_auth"),
            ) {
                Text("Neuer Code")
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
) {
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
                if (sessionRecovery) "Du bist angemeldet. Die Verbindung konnte noch nicht hergestellt werden."
                else "ChatGPT konnte die Anmeldung nicht bestätigen."
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
            Text("Erneut versuchen")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cancel_auth"),
        ) {
            Text("Abbrechen")
        }
    }
}

@Composable
private fun SupportingMessage(message: String) {
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
    val color = MaterialTheme.colorScheme.onBackground
    Canvas(
        modifier = Modifier
            .size(width = 74.dp, height = 24.dp)
            .semantics { contentDescription = "Vorgang läuft" },
    ) {
        val radius = 7.dp.toPx()
        val centerY = size.height / 2f
        drawCircle(color, radius, Offset(radius, centerY))
        drawCircle(color, radius, Offset(size.width / 2f, centerY))
        drawCircle(color, radius, Offset(size.width - radius, centerY))
    }
}
