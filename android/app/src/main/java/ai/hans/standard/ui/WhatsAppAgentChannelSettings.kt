package ai.hans.standard.ui

import ai.hans.standard.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

/** Only persisted, locally confirmed channel state is projected here. */
data class WhatsAppAgentChannelUiState(
    val available: Boolean = true,
    val enabled: Boolean = false,
    val pendingCount: Int = 0,
    val uncertainCount: Int = 0,
    val lookupRequiredCount: Int = 0,
)

@Composable
internal fun WhatsAppAgentChannelSettings(
    state: WhatsAppAgentChannelUiState,
    onConfigure: () -> Unit,
    onDisable: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("whatsapp_agent_channel")) {
        Text(stringResource(R.string.agent_channel_disclosure))
        Text(
            stringResource(
                when {
                    !state.available -> R.string.agent_channel_unavailable
                    state.enabled -> R.string.agent_channel_enabled
                    else -> R.string.agent_channel_disabled
                },
            ),
            modifier = Modifier.testTag("whatsapp_agent_channel_status"),
        )
        if (state.pendingCount > 0) {
            Text(stringResource(R.string.agent_channel_pending, state.pendingCount))
        }
        if (state.uncertainCount > 0) {
            Text(stringResource(R.string.agent_channel_uncertain, state.uncertainCount))
        }
        if (state.lookupRequiredCount > 0) {
            Text(stringResource(R.string.agent_channel_lookup_required),
                modifier = Modifier.testTag("whatsapp_agent_channel_incomplete"))
        }
        OutlinedButton(
            onClick = onConfigure,
            enabled = state.available,
            modifier = Modifier.fillMaxWidth().testTag("configure_whatsapp_agent_channel"),
        ) {
            Text(stringResource(R.string.agent_channel_configure))
        }
        if (state.enabled) {
            TextButton(onClick = onDisable, modifier = Modifier.testTag("disable_whatsapp_agent_channel")) {
                Text(stringResource(R.string.agent_channel_disable))
            }
        }
    }
}
