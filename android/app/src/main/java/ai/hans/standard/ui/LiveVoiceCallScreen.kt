package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.localization.rememberHansTextResolver
import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.hans.standard.voice.audio.SpeechAudioRoute
import ai.hans.standard.voice.audio.SpeechAudioRouteState

/** Owned above launcher navigation so leaving Chat cannot discard a call's presentation. */
@Composable
internal fun rememberLiveCallMinimized(liveActive: Boolean): MutableState<Boolean> {
    val uiText = rememberHansTextResolver()
    val minimized = rememberSaveable(liveActive) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, liveActive) {
        val observer = LifecycleEventObserver { _, event ->
            // Foreign activities own their own window. Do not change the service or bring the
            // launcher back; returning retains the compact bar until an explicit expand.
            if (liveActive && event == Lifecycle.Event.ON_PAUSE) minimized.value = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return minimized
}

/**
 * A deliberately static phone-call surface. Live capture remains full duplex, but the E-Ink
 * display is invalidated only by an actual runtime state change or a user action.
 */
@Composable
internal fun LiveVoiceCallScreen(
    status: LiveVoiceUiStatus,
    inputMuted: Boolean,
    onInputMutedChanged: (Boolean) -> Unit,
    onHangUp: () -> Unit,
    audioRoute: SpeechAudioRouteState = SpeechAudioRouteState(),
    onAudioRouteRequested: (SpeechAudioRoute) -> Unit = {},
    onMinimize: () -> Unit = {},
    speechFailure: SpeechFailureUiState? = null,
    onOpenSpeechFailureHelp: (ai.hans.standard.voice.feedback.OpenAiSpeechRemediation) -> Unit = {},
    onDismissSpeechFailure: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiText = rememberHansTextResolver()
    // Back changes only presentation. The visible compact bar keeps microphone/stop controls
    // available; only the red call button requests a hang-up.
    BackHandler(enabled = true, onBack = onMinimize)

    Surface(
        modifier = modifier.fillMaxSize().testTag("live_call_screen"),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                // Keep call controls outside the scrollable, height-bounded error region.
                // Error details replace the large avatar on small screens and at large font scales.
                modifier = if (speechFailure != null) {
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .testTag("live_call_failure_scroll")
                } else Modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Hans", style = MaterialTheme.typography.displayMedium)
                Text(
                    text = callStatusLabel(status, inputMuted, uiText),
                    modifier = Modifier.padding(top = 6.dp).testTag("live_call_status"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                speechFailure?.let { failure ->
                    SpeechFailureNotice(failure, onOpenSpeechFailureHelp, onDismissSpeechFailure)
                }
            }
            if (speechFailure == null) {
                Spacer(Modifier.weight(1f))
                Image(
                    painter = painterResource(R.drawable.hans_call_avatar),
                    contentDescription = "Hans",
                    modifier = Modifier
                        .size(252.dp)
                        .clip(CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .testTag("live_call_avatar"),
                )
                Spacer(Modifier.weight(1f))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiveCallControl(
                    tag = "live_call_mute",
                    modifier = if (speechFailure != null) Modifier.weight(1f) else Modifier,
                    label = if (inputMuted) uiText.text(R.string.ui_microphone_on_0db06a) else uiText.text(R.string.ui_muted_d9b25d),
                    contentDescription = if (inputMuted) {
                        uiText.text(R.string.ui_microphone_is_muted_unmute_microphone_31d458)
                    } else {
                        uiText.text(R.string.ui_mute_microphone_b01aff)
                    },
                    enabled = status.canChangeMute,
                    selected = inputMuted,
                    icon = painterResource(
                        if (inputMuted) R.drawable.ic_live_microphone_off
                        else R.drawable.ic_live_microphone,
                    ),
                    onClick = { onInputMutedChanged(!inputMuted) },
                )
                LiveCallControl(
                    tag = "live_call_speaker",
                    modifier = if (speechFailure != null) Modifier.weight(1f) else Modifier,
                    label = uiText.text(R.string.ui_speaker_8cb912),
                    contentDescription = liveSpeakerDescription(audioRoute.effective, uiText),
                    enabled = status.canChangeMute && audioRoute.active &&
                        liveSpeakerTarget(audioRoute.effective) in audioRoute.available,
                    selected = audioRoute.effective == SpeechAudioRoute.SPEAKER,
                    icon = painterResource(R.drawable.ic_live_speaker),
                    onClick = { onAudioRouteRequested(liveSpeakerTarget(audioRoute.effective)) },
                )
                LiveCallControl(
                    tag = "live_call_hang_up",
                    modifier = if (speechFailure != null) Modifier.weight(1f) else Modifier,
                    label = uiText.text(R.string.ui_hang_up_f481f5),
                    contentDescription = uiText.text(R.string.ui_end_call_with_hans_f20872),
                    enabled = true,
                    selected = false,
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    icon = painterResource(R.drawable.ic_live_hang_up),
                    onClick = onHangUp,
                )
            }
        }
    }
}

/** Launcher content, never a Dialog or a cross-application overlay. */
@Composable
internal fun LiveVoiceCallBar(
    status: LiveVoiceUiStatus,
    inputMuted: Boolean,
    onInputMutedChanged: (Boolean) -> Unit,
    onHangUp: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiText = rememberHansTextResolver()
    Surface(
        modifier = modifier.fillMaxWidth().testTag("live_call_bar"),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Hans", style = MaterialTheme.typography.titleMedium)
                Text(
                    callStatusLabel(status, inputMuted, uiText),
                    modifier = Modifier.testTag("live_call_bar_status"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onExpand, modifier = Modifier.testTag("live_call_expand")) {
                Text(stringResource(R.string.ui_open_bc385a))
            }
            LiveCallCompactControl(
                tag = "live_call_bar_mute",
                contentDescription = if (inputMuted) {
                    uiText.text(R.string.ui_microphone_is_muted_unmute_microphone_31d458)
                } else {
                    uiText.text(R.string.ui_mute_microphone_b01aff)
                },
                enabled = status.canChangeMute,
                selected = inputMuted,
                icon = painterResource(
                    if (inputMuted) R.drawable.ic_live_microphone_off
                    else R.drawable.ic_live_microphone,
                ),
                onClick = { onInputMutedChanged(!inputMuted) },
            )
            Spacer(Modifier.size(8.dp))
            LiveCallCompactControl(
                tag = "live_call_bar_hang_up",
                contentDescription = uiText.text(R.string.ui_end_call_with_hans_f20872),
                enabled = true,
                selected = false,
                icon = painterResource(R.drawable.ic_live_hang_up),
                onClick = onHangUp,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        }
    }
}

@Composable
private fun LiveCallCompactControl(
    tag: String,
    contentDescription: String,
    enabled: Boolean,
    selected: Boolean,
    icon: Painter,
    onClick: () -> Unit,
    containerColor: Color = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    },
    contentColor: Color = if (selected) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    },
) {
    val uiText = rememberHansTextResolver()
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(if (enabled) containerColor else containerColor.copy(alpha = 0.4f), CircleShape)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
                this.selected = selected
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (enabled) contentColor else contentColor.copy(alpha = 0.5f)),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun LiveCallControl(
    tag: String,
    label: String,
    contentDescription: String,
    enabled: Boolean,
    selected: Boolean,
    icon: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    },
    contentColor: Color = if (selected) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    },
) {
    val uiText = rememberHansTextResolver()
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .background(
                    color = if (enabled) containerColor else containerColor.copy(alpha = 0.4f),
                    shape = CircleShape,
                )
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                    this.selected = selected
                    if (!enabled) disabled()
                }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = icon,
                contentDescription = null,
                colorFilter = ColorFilter.tint(
                    if (enabled) contentColor else contentColor.copy(alpha = 0.5f),
                ),
                modifier = Modifier.size(34.dp),
            )
        }
        Text(
            text = label,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

private fun liveSpeakerTarget(effective: SpeechAudioRoute): SpeechAudioRoute =
    if (effective == SpeechAudioRoute.SPEAKER) SpeechAudioRoute.EARPIECE else SpeechAudioRoute.SPEAKER

private fun liveSpeakerDescription(effective: SpeechAudioRoute, uiText: HansTextResolver): String = when (effective) {
    SpeechAudioRoute.SPEAKER -> uiText.text(R.string.ui_audio_output_speaker_switch_to_earpiece_b95789)
    SpeechAudioRoute.EARPIECE -> uiText.text(R.string.ui_audio_output_earpiece_turn_on_speaker_20499f)
    SpeechAudioRoute.EXTERNAL -> uiText.text(R.string.ui_audio_output_headset_bluetooth_turn_on_speaker_0435ce)
    SpeechAudioRoute.UNKNOWN -> uiText.text(R.string.ui_audio_output_not_yet_confirmed_turn_on_speaker_e3d6ca)
}

private val LiveVoiceUiStatus.canChangeMute: Boolean
    get() = this in setOf(
        LiveVoiceUiStatus.LISTENING,
        LiveVoiceUiStatus.USER_SPEAKING,
        LiveVoiceUiStatus.HANS_SPEAKING,
        LiveVoiceUiStatus.WAITING_FOR_TASK,
    )

private fun callStatusLabel(status: LiveVoiceUiStatus, inputMuted: Boolean, uiText: HansTextResolver): String = when {
    status == LiveVoiceUiStatus.CONNECTING -> uiText.text(R.string.ui_calling_8cc2d7)
    status == LiveVoiceUiStatus.RECONNECTING -> uiText.text(R.string.ui_reconnecting_a4f94c)
    status == LiveVoiceUiStatus.FAILED -> uiText.text(R.string.ui_connection_interrupted_80731c)
    inputMuted -> uiText.text(R.string.ui_muted_d9b25d)
    status == LiveVoiceUiStatus.LISTENING -> uiText.text(R.string.ui_connected_033818)
    status == LiveVoiceUiStatus.USER_SPEAKING -> uiText.text(R.string.ui_hans_is_listening_3d4eed)
    status == LiveVoiceUiStatus.HANS_SPEAKING -> uiText.text(R.string.ui_hans_is_speaking_c0c599)
    status == LiveVoiceUiStatus.WAITING_FOR_TASK -> uiText.text(R.string.ui_hans_is_working_on_it_95d1be)
    else -> uiText.text(status.labelResource)
}
