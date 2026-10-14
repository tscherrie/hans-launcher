package ai.hans.standard.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.hans.standard.HansApplication
import ai.hans.standard.R
import ai.hans.standard.voice.tts.SystemSpeechStatus
import ai.hans.standard.voice.tts.android.AndroidSystemSpeech

/** Same controls for initial setup and later settings; no sound until Preview is pressed. */
class SystemSpeechSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HansTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.system_speech_title), style = MaterialTheme.typography.headlineSmall)
                    SystemSpeechControls()
                    Button(onClick = { finish() }) { Text(stringResource(R.string.voice_settings_done)) }
                }
            }
        } }
    }
}

@Composable
fun SystemSpeechControls() {
    val context = LocalContext.current
    val owner = remember { AndroidSystemSpeech.get(context) }
    val runtime = owner.runtime
    val host by remember { lazy { (context.applicationContext as HansApplication).sessionHost } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf(runtime.snapshot) }
    var chooseVoice by remember { mutableStateOf(false) }
    var previewRejected by remember { mutableStateOf(false) }
    var previewOwned by remember { mutableStateOf(false) }
    LaunchedEffect(state.status) {
        if (state.status in setOf(SystemSpeechStatus.COMPLETED, SystemSpeechStatus.STOPPED,
                SystemSpeechStatus.FAILED, SystemSpeechStatus.UNAVAILABLE)) previewOwned = false
    }
    DisposableEffect(runtime, lifecycle) {
        val main = Handler(Looper.getMainLooper())
        var observing = true
        val subscription = runtime.observe { value -> main.post { if (observing) state = value } }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) runtime.refresh()
            if (event == Lifecycle.Event.ON_PAUSE && previewOwned) { host.stopSpeech(); previewOwned = false }
        }
        lifecycle.addObserver(observer)
        runtime.refresh()
        onDispose {
            observing = false; subscription.close(); lifecycle.removeObserver(observer)
            if (previewOwned) host.stopSpeech()
        }
    }
    Text(stringResource(R.string.system_speech_help))
    Text(stringResource(R.string.system_speech_routing_help))
    if (state.selectedVoice?.offline == false) Text(stringResource(R.string.system_speech_network_help))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.system_speech_enabled))
        Switch(state.options.enabled, modifier = Modifier.testTag("system_speech_enabled"), onCheckedChange = {
            runtime.update(state.options.copy(enabled = it)); if (!it) host.stopSpeech()
        })
    }
    Text(stringResource(when (state.status) {
        SystemSpeechStatus.IDLE, SystemSpeechStatus.CHECKING -> R.string.system_speech_checking
        SystemSpeechStatus.UNAVAILABLE -> R.string.system_speech_missing
        SystemSpeechStatus.FAILED -> R.string.system_speech_failed
        SystemSpeechStatus.SPEAKING -> R.string.system_speech_speaking
        SystemSpeechStatus.COMPLETED -> R.string.system_speech_completed
        SystemSpeechStatus.STOPPED -> R.string.system_speech_stopped
        SystemSpeechStatus.READY -> if (state.selectedVoice?.offline == true) R.string.system_speech_offline else R.string.system_speech_online
    }))
    OutlinedButton(onClick = { chooseVoice = true }, enabled = state.voices.isNotEmpty(),
        modifier = Modifier.testTag("system_speech_voice")) {
        Text(state.selectedVoice?.label ?: stringResource(R.string.system_speech_choose))
    }
    Text(stringResource(R.string.system_speech_speed, state.options.speed))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.8f, 1f, 1.2f).forEach { speed ->
            FilterChip(state.options.speed == speed, onClick = { runtime.update(state.options.copy(speed = speed)) },
                label = { Text("${speed}×") })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            previewOwned = host.previewSpeech(); previewRejected = !previewOwned
        }, modifier = Modifier.testTag("system_speech_preview"), enabled = state.options.enabled &&
            state.selectedVoice != null && state.status in setOf(SystemSpeechStatus.READY,
                SystemSpeechStatus.COMPLETED, SystemSpeechStatus.STOPPED, SystemSpeechStatus.FAILED)) {
            Text(stringResource(R.string.system_speech_preview))
        }
        if (previewOwned) TextButton(onClick = { host.stopSpeech(); previewOwned = false }) {
            Text(stringResource(R.string.system_speech_stop))
        }
    }
    if (previewRejected) Text(stringResource(R.string.system_speech_preview_unavailable))
    val install = owner.voiceDataIntent()
    if (install != null) OutlinedButton(onClick = {
        runCatching { context.startActivity(install) }.onFailure { previewRejected = true }
    }) { Text(stringResource(R.string.system_speech_install)) }
    if (state.status == SystemSpeechStatus.UNAVAILABLE && install == null) Text(stringResource(R.string.system_speech_install_help))
    if (chooseVoice) AlertDialog(onDismissRequest = { chooseVoice = false },
        title = { Text(stringResource(R.string.system_speech_choose)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            state.voices.forEach { voice -> TextButton(onClick = {
                runtime.update(state.options.copy(voiceId = voice.id)); chooseVoice = false
            }) { Text(voice.label + " · " + stringResource(if (voice.offline) R.string.system_speech_offline else R.string.system_speech_online)) } }
        } }, confirmButton = { TextButton(onClick = { chooseVoice = false }) { Text(stringResource(R.string.voice_settings_done)) } })
}
