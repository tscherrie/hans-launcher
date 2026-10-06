package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.voice.feedback.OpenAiSpeechRemediation
import ai.hans.standard.voice.feedback.SpeechServiceFailureSnapshot
import ai.hans.standard.voice.feedback.openAiSpeechFailureMessage
import ai.hans.standard.voice.feedback.openAiSpeechRemediation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

data class SpeechFailureUiState(
    val message: String,
    val remediation: OpenAiSpeechRemediation? = null,
    /** Exact rendered event; an older dismiss tap must never clear a newer failure. */
    val revision: Long = 0,
)

internal fun projectSpeechFailure(
    snapshot: SpeechServiceFailureSnapshot,
    text: HansTextResolver,
): SpeechFailureUiState? = snapshot.code?.let { code ->
    SpeechFailureUiState(openAiSpeechFailureMessage(code, text), openAiSpeechRemediation(code), snapshot.revision)
}

/** No retries, browser launches or polling until the user explicitly taps an action. */
@Composable
internal fun SpeechFailureNotice(
    failure: SpeechFailureUiState,
    onOpenHelp: (OpenAiSpeechRemediation) -> Unit,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("speech_failure_notice")
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(failure.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        failure.remediation?.let { target ->
            TextButton(onClick = { onOpenHelp(target) }, modifier = Modifier.testTag("speech_failure_help")) {
                Text(stringResource(when (target) {
                    OpenAiSpeechRemediation.BILLING -> R.string.speech_failure_open_billing
                    OpenAiSpeechRemediation.LIMITS -> R.string.speech_failure_open_limits
                    OpenAiSpeechRemediation.PROJECT_SETTINGS -> R.string.speech_failure_open_project
                }))
            }
        }
        TextButton(onClick = { onDismiss(failure.revision) }, modifier = Modifier.testTag("speech_failure_dismiss")) {
            Text(stringResource(R.string.speech_failure_dismiss))
        }
    }
}
