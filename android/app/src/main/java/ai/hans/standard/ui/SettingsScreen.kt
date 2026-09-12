package ai.hans.standard.ui

import androidx.compose.material3.TextButton

import ai.hans.standard.phone.keys.ActionKeyTrigger
import ai.hans.standard.phone.display.DisplayMotionDecision
import ai.hans.standard.phone.display.DisplayMotionMode
import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import ai.hans.standard.phone.capabilities.PrivateSpaceAvailability
import ai.hans.standard.voice.stt.android.ConfirmedSttGlossaryPolicy
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    callbacks: SettingsUiCallbacks,
    modifier: Modifier = Modifier,
) {
    var navigation by remember { mutableStateOf(SettingsNavigation()) }
    val group = navigation.group
    val onBack = {
        if (group == null) callbacks.onBack() else navigation = navigation.backToGroups()
    }
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        ScreenHeader(title = navigation.title, onBack = onBack)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        key(group) {
            if (group == null) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    SettingsGroupOverview(
                        onGroupSelected = { selected ->
                            if (selected == SettingsGroup.ADVANCED_WORK) {
                                callbacks.onRemoteWorkerSettingsOpened()
                            }
                            if (selected == SettingsGroup.RUNTIME) {
                                callbacks.onModelSettingsOpened()
                            }
                            if (selected == SettingsGroup.REMOTE_CONTROL) {
                                callbacks.onRemoteControlSettingsOpened()
                            }
                            navigation = navigation.open(selected)
                        },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                SettingsContent(state, callbacks, group, Modifier.weight(1f))
            }
        }
    }
}

/** Navigation owns only the current group, never an effective setting or a pending choice. */
@Composable
internal fun SettingsGroupOverview(
    onGroupSelected: (SettingsGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("settings_group_overview")) {
        SettingsGroup.entries.forEach { group ->
            TextButton(
                onClick = { onGroupSelected(group) },
                modifier = Modifier.fillMaxWidth().testTag("settings_group_${group.id}"),
            ) {
                Text(
                    text = group.title,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}

/** Only the selected group's controls are composed; confirmed values still come from [state]. */
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    callbacks: SettingsUiCallbacks,
    group: SettingsGroup,
    modifier: Modifier = Modifier,
    displayMotion: DisplayMotionDecision = rememberDisplayMotion(state.displayMotionMode),
) {
    var showNativeMemoryHealth by remember { mutableStateOf(false) }
    var showThirdPartyNotices by remember { mutableStateOf(false) }
    // These are read-only diagnostic/legal documents, not another settings hierarchy.
    if (showNativeMemoryHealth || showThirdPartyNotices) {
        Dialog(
            onDismissRequest = {
                showNativeMemoryHealth = false
                showThirdPartyNotices = false
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize()) {
                if (showNativeMemoryHealth) {
                    NativeMemoryHealthScreen(onBack = { showNativeMemoryHealth = false })
                } else {
                    ThirdPartyNoticesScreen(onBack = { showThirdPartyNotices = false })
                }
            }
        }
    }
    Column(
        modifier = modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 36.dp)
            .testTag("settings_content"),
    ) {
        when (group) {
            SettingsGroup.PERMISSIONS -> PermissionSettings(state, callbacks)
            SettingsGroup.RUNTIME -> RuntimeSettings(state, callbacks)
            SettingsGroup.SPEECH -> SpeechSettings(state, callbacks)
            SettingsGroup.INPUT -> InputSettings(state, callbacks, displayMotion)
            SettingsGroup.PERSONAL -> PersonalSettings(state, callbacks)
            SettingsGroup.REMOTE_CONTROL -> RemoteControlSettings(state, callbacks)
            SettingsGroup.ADVANCED_WORK -> AdvancedWorkSettings(state, callbacks)
            SettingsGroup.MAINTENANCE -> AccessAndBackupSettings(
                state,
                callbacks,
                onOpenNativeMemoryHealth = { showNativeMemoryHealth = true },
                onOpenThirdPartyNotices = { showThirdPartyNotices = true },
            )
        }
    }
}

@Composable
private fun AdvancedWorkSettings(
    state: SettingsUiState,
    callbacks: SettingsUiCallbacks,
) {
    SettingsSection(title = "Diagnose") {
        OutlinedButton(onClick = callbacks.onOpenWorkbench,
            modifier = Modifier.fillMaxWidth().testTag("open_workbench")) {
            Text("Interne Arbeitsdateien")
        }
    }
    val remote = state.remoteWorker
    var draft by remember(remote.revision, remote.loaded) {
        mutableStateOf(remote.formDraft)
    }
    val draftMatchesSaved = draft.normalized() == remote.formDraft.normalized()
    SettingsSection(title = "Optionaler Arbeitsrechner") {
        Text(
            text = "Hans kann große, ausdrücklich freigegebene Arbeiten an einen eigenen Rechner übertragen. Das ist optional und beim Öffnen dieser Seite vollständig passiv.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        RemoteWorkerStateCard(remote)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = draft.workerId,
            onValueChange = { value -> draft = draft.copy(workerId = value.take(128)) },
            enabled = !remote.operationInProgress,
            singleLine = true,
            label = { Text("Worker-ID") },
            modifier = Modifier.fillMaxWidth().testTag("remote_worker_id"),
        )
        OutlinedTextField(
            value = draft.httpsOrigin,
            onValueChange = { value -> draft = draft.copy(httpsOrigin = value.take(2_048)) },
            enabled = !remote.operationInProgress,
            singleLine = true,
            label = { Text("HTTPS-Adresse") },
            supportingText = { Text("Nur die Adresse, ohne Pfad, Anmeldung oder Parameter") },
            modifier = Modifier.fillMaxWidth().testTag("remote_worker_origin"),
        )
        OutlinedTextField(
            value = draft.serverSpkiSha256,
            onValueChange = { value ->
                draft = draft.copy(
                    serverSpkiSha256 = value.filterNot(Char::isWhitespace).take(64),
                )
            },
            enabled = !remote.operationInProgress,
            singleLine = true,
            label = { Text("SPKI-Pin · SHA-256") },
            supportingText = { Text("64 Hex-Zeichen aus einer vertrauenswürdigen Quelle") },
            modifier = Modifier.fillMaxWidth().testTag("remote_worker_spki_pin"),
        )
    }

    SettingsSection(title = "Freigegebene Adapter") {
        Text(
            text = "Jede Zeile gibt genau eine Adapter-ID und Version frei. Telefonfunktionen bleiben immer lokal auf Android.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        draft.adapters.forEachIndexed { index, adapter ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = adapter.id,
                    onValueChange = { value ->
                        draft = draft.copy(
                            adapters = draft.adapters.replaceAt(
                                index,
                                adapter.copy(id = value.take(128)),
                            ),
                        )
                    },
                    enabled = !remote.operationInProgress,
                    singleLine = true,
                    label = { Text("Adapter-ID") },
                    modifier = Modifier.weight(2f).testTag("remote_adapter_id_$index"),
                )
                OutlinedTextField(
                    value = adapter.version,
                    onValueChange = { value ->
                        draft = draft.copy(
                            adapters = draft.adapters.replaceAt(
                                index,
                                adapter.copy(version = value.take(32)),
                            ),
                        )
                    },
                    enabled = !remote.operationInProgress,
                    singleLine = true,
                    label = { Text("Version") },
                    modifier = Modifier.weight(1f).testTag("remote_adapter_version_$index"),
                )
            }
            if (draft.adapters.size > 1 || adapter.id.isNotBlank() || adapter.version.isNotBlank()) {
                TextButton(
                    onClick = {
                        val remaining = draft.adapters.filterIndexed { itemIndex, _ ->
                            itemIndex != index
                        }
                        draft = draft.copy(
                            adapters = remaining.ifEmpty { listOf(RemoteWorkerAdapterUiDraft()) },
                        )
                    },
                    enabled = !remote.operationInProgress,
                    modifier = Modifier.testTag("remove_remote_adapter_$index"),
                ) {
                    Text("Adapter entfernen")
                }
            }
        }
        OutlinedButton(
            onClick = {
                if (draft.adapters.size < 128) {
                    draft = draft.copy(
                        adapters = draft.adapters + RemoteWorkerAdapterUiDraft(),
                    )
                }
            },
            enabled = !remote.operationInProgress && draft.adapters.size < 128,
            modifier = Modifier.fillMaxWidth().testTag("add_remote_adapter"),
        ) {
            Text("Adapter hinzufügen")
        }
    }

    SettingsSection(title = "Verwendung") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Fernarbeit anfordern", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Speichern allein stellt noch keine Verbindung her.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(
                checked = draft.requestedEnabled,
                onCheckedChange = { enabled ->
                    draft = draft.copy(requestedEnabled = enabled)
                },
                enabled = !remote.operationInProgress,
                modifier = Modifier.testTag("remote_worker_requested_enabled"),
            )
        }
        draft.validationMessage?.let { validation ->
            Text(
                validation,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("remote_worker_validation"),
            )
        }
        Button(
            onClick = { callbacks.onSaveRemoteWorkerConfiguration(draft.normalized()) },
            enabled = remote.loaded && remote.storageHealthyForUi &&
                !remote.operationInProgress && draft.canSave,
            modifier = Modifier.fillMaxWidth().testTag("save_remote_worker"),
        ) {
            Text("Konfiguration speichern")
        }
        if (
            remote.configured && remote.requestedEnabled && !remote.effective &&
            !draftMatchesSaved
        ) {
            Text(
                "Speichere die Änderungen, bevor du genau diese Verbindung aktivierst.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("remote_worker_unsaved_activation_notice"),
            )
        }
        Button(
            onClick = callbacks.onActivateRemoteWorker,
            enabled = remote.loaded && remote.configured && remote.requestedEnabled &&
                !remote.effective && remote.storageHealthyForUi &&
                !remote.operationInProgress && draftMatchesSaved,
            modifier = Modifier.fillMaxWidth().testTag("activate_remote_worker"),
        ) {
            Text("Verbindung prüfen und aktivieren")
        }
    }
}

@Composable
private fun RemoteWorkerStateCard(state: RemoteWorkerSettingsUiState) {
    val status = when (state.status) {
        RemoteWorkerSettingsUiStatus.NOT_LOADED -> "Lokale Konfiguration wird erst beim Öffnen gelesen."
        RemoteWorkerSettingsUiStatus.UNCONFIGURED -> "Noch nicht eingerichtet"
        RemoteWorkerSettingsUiStatus.DISABLED -> "Gespeichert, aber ausgeschaltet"
        RemoteWorkerSettingsUiStatus.NEEDS_ACTIVATION ->
            "Angefordert, aber noch nicht geprüft oder wirksam"
        RemoteWorkerSettingsUiStatus.EFFECTIVE -> "Geprüft und für Codex wirksam"
        RemoteWorkerSettingsUiStatus.STORAGE_CORRUPT ->
            "Die lokale Konfiguration konnte nicht sicher gelesen werden"
    }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("remote_worker_state"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(status, style = MaterialTheme.typography.titleMedium)
            Text(
                "Gewünscht: ${if (state.requestedEnabled) "Ein" else "Aus"} · " +
                    "Wirksam: ${if (state.effective) "Ja" else "Nein"}",
                modifier = Modifier.testTag("remote_worker_effective_state"),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.operationInProgress) {
                Text("Verbindung wird ausdrücklich geprüft …", style = MaterialTheme.typography.bodyMedium)
            }
            if (state.notice.isNotBlank()) {
                Text(
                    state.notice,
                    modifier = Modifier.testTag("remote_worker_notice"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private val RemoteWorkerSettingsUiState.storageHealthyForUi: Boolean
    get() = status != RemoteWorkerSettingsUiStatus.STORAGE_CORRUPT

private fun List<RemoteWorkerAdapterUiDraft>.replaceAt(
    index: Int,
    value: RemoteWorkerAdapterUiDraft,
): List<RemoteWorkerAdapterUiDraft> = mapIndexed { itemIndex, item ->
    if (itemIndex == index) value else item
}

private fun RemoteWorkerConfigurationUiDraft.normalized(): RemoteWorkerConfigurationUiDraft = copy(
    workerId = workerId.trim(),
    httpsOrigin = httpsOrigin.trim(),
    serverSpkiSha256 = serverSpkiSha256.trim().lowercase(),
    adapters = adapters.map { adapter ->
        RemoteWorkerAdapterUiDraft(adapter.id.trim(), adapter.version.trim())
    }.filterNot { it.id.isBlank() && it.version.isBlank() },
)

@Composable
private fun RuntimeSettings(
    state: SettingsUiState,
    callbacks: SettingsUiCallbacks,
) {
    // Confirmation and failures belong beside the controls, within the MP01 viewport.
    if (state.runtimeNotice.isNotBlank()) {
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("runtime_notice"),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(
                text = state.runtimeNotice,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    SettingsSection(title = "Modell") {
        OptionRows(state.models) { model ->
            SelectableOption(
                label = model.label,
                selected = state.selectedModelId == model.id,
                onClick = { callbacks.onModelSelected(model.id) },
                testTag = "model_${model.id}",
                modifier = Modifier.weight(1f),
            )
        }
    }

    SettingsSection(title = "Denkaufwand") {
        OptionRows(state.reasoningEfforts) { effort ->
            SelectableOption(
                label = effort.label,
                selected = state.selectedReasoningEffortId == effort.id,
                onClick = { callbacks.onReasoningEffortSelected(effort.id) },
                testTag = "effort_${effort.id}",
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (state.fastModeAvailable) {
        SettingsSection(title = "Antworttempo") {
            Text(
                text = "Fast nutzt die von Codex angebotene schnellere Warteschlange und verbraucht entsprechend mehr Kontingent.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            SelectableOption(
                label = "Standard",
                selected = !state.fastModeEnabled,
                onClick = { callbacks.onFastModeChanged(false) },
                testTag = "service_tier_standard",
            )
            SelectableOption(
                label = "Fast",
                selected = state.fastModeEnabled,
                onClick = { callbacks.onFastModeChanged(true) },
                testTag = "service_tier_fast",
            )
        }
    }

    SettingsSection(title = "Codex aktualisieren") {
        Text(
            text = "Installiert: Codex ${state.codexUpdate.bundledRuntimeVersion.ifBlank { "nicht verfügbar" }}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("codex_runtime_version"),
        )
        Text(
            text = if (state.codexUpdate.runtimeReady) {
                "Die eingebettete Codex-Runtime ist von Hans als bereit bestätigt."
            } else {
                "Die eingebettete Codex-Runtime ist noch nicht als bereit bestätigt."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("codex_runtime_state"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (state.codexUpdate.updateUrlConfigured) {
                "Die Aktualisierungsseite bietet die signierte Hans-Version mit der enthaltenen Codex-Runtime an. Android bestätigt die Installation."
            } else {
                "Codex ist fest in der signierten Hans-App enthalten und wird zusammen mit Hans aktualisiert. Für diesen internen Build ist noch keine Aktualisierungsseite hinterlegt."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("codex_update_delivery"),
        )
        OutlinedButton(
            onClick = callbacks.onUpdateCodex,
            modifier = Modifier.fillMaxWidth().testTag("codex_update"),
        ) {
            Text("Codex aktualisieren")
        }
    }

}

@Composable
private fun SpeechSettings(
    state: SettingsUiState,
    callbacks: SettingsUiCallbacks,
) {
    SettingsSection(title = "Was wird vorgelesen?") {
        ReadAloudUiMode.entries.forEach { mode ->
            SelectableOption(
                label = mode.label,
                selected = state.readAloudMode == mode,
                onClick = { callbacks.onReadAloudModeSelected(mode) },
                testTag = "read_aloud_${mode.name.lowercase()}",
            )
        }
    }

    SettingsSection(title = "Vorlesestimme") {
        if (state.voices.isEmpty()) {
            Text(
                text = "Die verfügbaren Stimmen werden von der Runtime geladen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            OptionRows(state.voices) { voice ->
                SelectableOption(
                    label = voice.label,
                    selected = state.selectedVoiceId == voice.id,
                    onClick = { callbacks.onVoiceSelected(voice.id) },
                    testTag = "voice_${voice.id}",
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedButton(
                onClick = callbacks.onPreviewVoice,
                enabled = state.selectedVoiceId != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preview_voice"),
            ) {
                Text("Stimme testen")
            }
        }
    }

    SettingsSection(title = "Geschwindigkeit · ${state.speechRateLabel}") {
        SpeechRateOptions.chunked(3).forEach { rowRates ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowRates.forEach { rate ->
                    SelectableOption(
                        label = formatSpeechRate(rate),
                        selected = state.speechRate == rate,
                        onClick = { callbacks.onSpeechRateSelected(rate) },
                        modifier = Modifier.weight(1f),
                        testTag = "speech_rate_${rate}",
                    )
                }
                repeat(3 - rowRates.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    SettingsSection(title = "Live-Stimme für neue Gespräche") {
        Text(
            text = "Die Auswahl gilt für das nächste Live-Gespräch. Ein laufendes Gespräch bleibt unverändert.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("live_voice_scope"),
        )
        state.activeLiveVoiceId?.let { activeVoice ->
            Text(
                text = buildString {
                    append("Dieses Gespräch: ").append(activeVoice)
                    state.selectedLiveVoiceId?.takeIf { it != activeVoice }?.let { nextVoice ->
                        append(" / Nächstes Gespräch: ").append(nextVoice)
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("live_voice_selection_summary"),
            )
        }
        if (state.liveVoices.isEmpty()) {
            Text(
                text = "Die verfügbaren Live-Stimmen werden geladen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            OptionRows(state.liveVoices) { voice ->
                SelectableOption(
                    label = voice.label,
                    selected = state.selectedLiveVoiceId == voice.id,
                    onClick = { callbacks.onLiveVoiceSelected(voice.id) },
                    testTag = "live_voice_${voice.id}",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun InputSettings(
    state: SettingsUiState,
    callbacks: SettingsUiCallbacks,
    displayMotion: DisplayMotionDecision,
) {
    SettingsSection(title = "Display-Darstellung") {
        listOf(
            DisplayMotionMode.AUTOMATIC to "Automatisch",
            DisplayMotionMode.E_INK to "E-Ink",
            DisplayMotionMode.STANDARD to "Normales Display",
        ).forEach { (mode, label) ->
            SelectableOption(
                label = label,
                selected = state.displayMotionMode == mode,
                onClick = { callbacks.onDisplayMotionModeSelected(mode) },
                testTag = "display_motion_${mode.storageValue}",
            )
        }
        Text(
            text = when {
                displayMotion.isEink -> "E-Ink-Darstellung aktiv: Cursor und Arbeitspunkte bleiben statisch."
                !displayMotion.systemAnimationsEnabled ->
                    "Normale Darstellung aktiv; Animationen sind aktuell deaktiviert."
                else -> "Normale Darstellung aktiv: Cursor und Arbeitspunkte animieren im sichtbaren Chat."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("display_motion_effective"),
        )
        Text(
            text = "Automatisch erkennt das Minimal Phone MP01. Wähle für andere E-Ink-Geräte E-Ink. " +
                "Androids Animationseinstellung hat Vorrang. Das aktive Live-Signal bleibt davon getrennt.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    SettingsSection(title = "Aktionstaste") {
        Text(
            text = "Wie soll die Diktattaste reagieren?",
            style = MaterialTheme.typography.titleMedium,
        )
        SelectableOption(
            label = "Einmal drücken startet, nochmals drücken sendet",
            selected = state.actionKey.dictationTrigger == ActionKeyTrigger.PRESS,
            onClick = {
                callbacks.onDictationTriggerSelected(ActionKeyTrigger.PRESS)
            },
            testTag = "dictation_trigger_toggle",
        )
        SelectableOption(
            label = "Gedrückt halten zum Sprechen",
            selected = state.actionKey.dictationTrigger == ActionKeyTrigger.HOLD_TO_TALK,
            onClick = {
                callbacks.onDictationTriggerSelected(ActionKeyTrigger.HOLD_TO_TALK)
            },
            testTag = "dictation_trigger_hold",
        )
        Text(
            text = when {
                state.actionKey.capturing ->
                    if (state.actionKey.dictationTrigger == ActionKeyTrigger.PRESS) {
                        "Drücke jetzt einmal kurz die Taste, mit der du Diktate starten und beenden möchtest."
                    } else {
                        "Drücke jetzt die Taste, die du später zum Sprechen gedrückt halten möchtest."
                    }
                state.actionKey.dictationTrigger == ActionKeyTrigger.HOLD_TO_TALK &&
                state.actionKey.mp01VendorConflict.kind ==
                    Mp01VendorActionConflictUiKind.STOCK_SYSTEM_POLICY &&
                    Mp01VendorShortcutUiKind.DICTATION in
                    state.actionKey.mp01VendorConflict.shortcutKinds ->
                    "Gespeichert, aber blockiert: Stock-MP01 reserviert das Halten dieser Taste. Verwende den Umschalter oder wähle eine andere Taste."
                state.actionKey.mp01VendorConflict.isStockPressToggleFor(
                    Mp01VendorShortcutUiKind.DICTATION,
                ) ->
                    "Eingerichtet. Einmal kurz drücken startet die Aufnahme, nochmals kurz drücken beendet und sendet."
                state.actionKey.mp01VendorConflict.replacementRequired &&
                    Mp01VendorShortcutUiKind.DICTATION in
                    state.actionKey.mp01VendorConflict.shortcutKinds ->
                    if (
                        state.actionKey.mp01VendorConflict.kind ==
                        Mp01VendorActionConflictUiKind.STOCK_SYSTEM_POLICY
                    ) {
                        "Gespeichert, aber blockiert. Diese Minimal-Systemtaste lässt sich in Hans Standard nicht übernehmen; wähle eine andere Taste."
                    } else {
                        "Gespeichert, aber blockiert. Die Taste wird erst nach der bestätigten Minimal-Neutralisierung für Diktate aktiv."
                    }
                state.actionKey.configured ->
                    if (state.actionKey.dictationTrigger == ActionKeyTrigger.PRESS) {
                        "Eingerichtet. Einmal drücken startet, nochmals drücken beendet und sendet."
                    } else {
                        "Eingerichtet. Gedrückt halten startet die Aufnahme; Loslassen beendet und sendet."
                    }
                else ->
                    "Lege eine frei wählbare Hardwaretaste für Diktate fest."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("action_key_status"),
        )
        if (state.actionKey.notice.isNotBlank()) {
            Text(
                text = state.actionKey.notice,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.actionKey.capturing) {
            OutlinedButton(
                onClick = callbacks.onCancelActionKeySetup,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cancel_action_key_setup"),
            ) {
                Text("Einrichtung abbrechen")
            }
        } else {
            OutlinedButton(
                onClick = callbacks.onStartActionKeySetup,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("setup_action_key"),
            ) {
                Text(
                    if (state.actionKey.dictationMappingStored) {
                        "Taste ändern"
                    } else {
                        "Taste einrichten"
                    },
                )
            }
        }
        if (state.actionKey.dictationMappingStored && !state.actionKey.capturing) {
            OutlinedButton(
                onClick = callbacks.onClearActionKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clear_action_key"),
            ) {
                Text("Aktionstaste entfernen")
            }
        }
        if (
            Mp01VendorShortcutUiKind.DICTATION in
            state.actionKey.mp01VendorConflict.shortcutKinds
        ) {
            Mp01VendorActionConflictCard(
                state.actionKey.mp01VendorConflict,
                callbacks,
            )
        }
    }

    SettingsSection(title = "Diktat über das Kamerasymbol · optional") {
        Text(
            text = if (state.cameraHoldToTalkEnabled) {
                "Aktiv: kurz tippen öffnet die Auswahl für Foto oder Video. Gedrückt halten startet ein Diktat; Loslassen beendet und sendet. Nur im sichtbaren Hans Launcher verfügbar."
            } else {
                "Aus: Das Kamerasymbol öffnet die Auswahl für Foto oder Video; Halten löst kein Diktat aus."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("camera_hold_to_talk_status"),
        )
        SelectableOption(
            label = "Aus",
            selected = !state.cameraHoldToTalkEnabled,
            onClick = { callbacks.onCameraHoldToTalkChanged(false) },
            testTag = "camera_hold_to_talk_off",
        )
        SelectableOption(
            label = "Kamerasymbol gedrückt halten",
            selected = state.cameraHoldToTalkEnabled,
            onClick = { callbacks.onCameraHoldToTalkChanged(true) },
            testTag = "camera_hold_to_talk_on",
        )
    }

    SettingsSection(title = "Modell-Wechseltaste · optional") {
        Text(
            text = when {
                state.actionKey.capturingModelToggle ->
                    "Drücke jetzt die Taste für den Wechsel zwischen Luna Max und Astra Ultra."
                state.actionKey.mp01VendorConflict.isStockPressToggleFor(
                    Mp01VendorShortcutUiKind.MODEL_TOGGLE,
                ) ->
                    "Eingerichtet. Ein kurzer Druck wechselt zwischen Luna Max und Astra Ultra; halte die Taste nicht."
                state.actionKey.mp01VendorConflict.replacementRequired &&
                    Mp01VendorShortcutUiKind.MODEL_TOGGLE in
                    state.actionKey.mp01VendorConflict.shortcutKinds ->
                    if (
                        state.actionKey.mp01VendorConflict.kind ==
                        Mp01VendorActionConflictUiKind.STOCK_SYSTEM_POLICY
                    ) {
                        "Gespeichert, aber blockiert. Diese Minimal-Systemtaste lässt sich in Hans Standard nicht übernehmen; wähle eine andere Taste."
                    } else {
                        "Gespeichert, aber blockiert. Die Taste wird erst nach der bestätigten Minimal-Neutralisierung als Modellwechsel aktiv."
                    }
                state.actionKey.modelToggleConfigured ->
                    "Eingerichtet. Ein Tastendruck wechselt zwischen Luna Max und Astra Ultra."
                else ->
                    "Du kannst eine zweite, frei wählbare Hardwaretaste als Schnellwechsel festlegen."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("model_toggle_key_status"),
        )
        if (state.actionKey.capturingModelToggle) {
            OutlinedButton(
                onClick = callbacks.onCancelActionKeySetup,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cancel_model_toggle_key_setup"),
            ) {
                Text("Einrichtung abbrechen")
            }
        } else {
            OutlinedButton(
                onClick = callbacks.onStartModelToggleKeySetup,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("setup_model_toggle_key"),
            ) {
                Text(
                    if (state.actionKey.modelToggleMappingStored) {
                        "Wechseltaste ändern"
                    } else {
                        "Wechseltaste einrichten"
                    },
                )
            }
        }
        if (
            state.actionKey.modelToggleMappingStored &&
            !state.actionKey.capturingModelToggle
        ) {
            OutlinedButton(
                onClick = callbacks.onClearModelToggleKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clear_model_toggle_key"),
            ) {
                Text("Wechseltaste entfernen")
            }
        }
        if (
            Mp01VendorShortcutUiKind.MODEL_TOGGLE in
            state.actionKey.mp01VendorConflict.shortcutKinds &&
            Mp01VendorShortcutUiKind.DICTATION !in
            state.actionKey.mp01VendorConflict.shortcutKinds
        ) {
            Mp01VendorActionConflictCard(
                state.actionKey.mp01VendorConflict,
                callbacks,
            )
        }
    }
}

@Composable
private fun PermissionSettings(
    state: SettingsUiState,
    callbacks: SettingsUiCallbacks,
) {
    SettingsSection(title = "Telefonzugriff") {
        Text(
            text = "Hans fragt nur nach Zugriffen, die du einschaltest. Jeder Zugriff lässt sich in Android wieder entziehen.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        state.capabilityAccess.forEach { capability ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = if (capability.granted) {
                            "✓  ${capability.title}"
                        } else {
                            capability.title
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = capability.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!capability.granted || capability.id == CapabilityAccessUiId.ALL_FILES) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                callbacks.onCapabilityAccessRequested(capability.id)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(
                                    "grant_${capability.id.name.lowercase()}",
                                ),
                        ) {
                            Text(if (capability.granted) "In Android verwalten" else "Einrichten")
                        }
                    }
                }
            }
        }
    }

    if (state.privateSpace.isRelevantToSettings) {
        SettingsSection(title = "Privater Bereich") {
            Text(
                text = privateSpaceSettingsDescription(state.privateSpace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("private_space_settings_status"),
            )
            if (state.privateSpace.profilePresent) {
                SelectableOption(
                    label = "Im App-Bereich anzeigen",
                    selected = state.privateSpace.containerVisible,
                    onClick = {
                        callbacks.onPrivateSpaceVisibilityChanged(true)
                    },
                    testTag = "show_private_space_container",
                )
                SelectableOption(
                    label = "Im App-Bereich ausblenden",
                    selected = !state.privateSpace.containerVisible,
                    onClick = {
                        callbacks.onPrivateSpaceVisibilityChanged(false)
                    },
                    testTag = "hide_private_space_container",
                )
                val profileId = state.privateSpace.profileId
                if (profileId != null) {
                    OutlinedButton(
                        onClick = {
                            callbacks.onPrivateSpaceLockChanged(
                                profileId,
                                !state.privateSpace.locked,
                            )
                        },
                        enabled = state.privateSpace.canChangeLock,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(
                                if (state.privateSpace.locked) {
                                    "settings_unlock_private_space"
                                } else {
                                    "settings_lock_private_space"
                                },
                            ),
                    ) {
                        Text(
                            if (state.privateSpace.locked) {
                                "Privaten Bereich entsperren"
                            } else {
                                "Privaten Bereich sperren"
                            },
                        )
                    }
                }
            }
            if (state.privateSpace.settingsAvailable) {
                OutlinedButton(
                    onClick = callbacks.onOpenPrivateSpaceSettings,
                    enabled = !state.privateSpace.operationInProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_open_private_space_settings"),
                ) {
                    Text("Privaten Bereich verwalten")
                }
            }
            if (state.privateSpace.operationInProgress) {
                Text(
                    text = "Android aktualisiert den privaten Bereich …",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("settings_private_space_operation_pending"),
                )
            } else if (state.privateSpace.notice.isNotBlank()) {
                Text(
                    text = state.privateSpace.notice,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("settings_private_space_notice"),
                )
            }
        }
    }

    val fullPhoneAccess = state.phoneActionPolicy ==
        HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS
    SettingsSection(title = if (fullPhoneAccess) "Vollzugriff / YOLO" else "Dauerfreigaben für Hans") {
        if (!fullPhoneAccess) {
            Button(
                onClick = callbacks.onEverydayAccessBundleRequested,
                enabled = !state.everydayAccessBundleActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("enable_everyday_access_bundle"),
            ) {
                Text(
                    when {
                        state.everydayAccessBundleActive -> "Alltagszugriff aktiv"
                        state.persistentAndroidConsents.isEmpty() ->
                            "Alltagszugriff aktivieren"
                        else -> "Alltagszugriff vervollständigen"
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (fullPhoneAccess) {
                "Keine zusätzlichen Hans-Rückfragen für deine Aufträge. Android-Berechtigungen und Bedienungshilfen bleiben erforderlich und in den Android-Einstellungen widerrufbar. Die optionale Link-Vorschau bleibt eine separate Einwilligung."
            } else if (state.everydayAccessBundleActive) {
                "Hans darf wiederkehrende Lese- und sichtbare Öffnungsaktionen ohne weitere Hans-Dialoge ausführen. Android-Systemberechtigungen bleiben separat."
            } else if (state.persistentAndroidConsents.isEmpty()) {
                "Keine Hans-Aktion ist dauerhaft freigegeben. Android-Systemberechtigungen werden separat eingerichtet und nie automatisch bestätigt."
            } else {
                "Einzelne Hans-Freigaben sind aktiv. Android-Systemberechtigungen sind davon getrennt und werden nie automatisch bestätigt."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("persistent_android_consent_notice"),
        )
        val linkMetadataDescriptor = PersistentAndroidConsentDescriptor.category(
            PersistentAndroidConsentScope.NOTIFICATION_LINK_METADATA,
        )
        val linkMetadataActive = state.persistentAndroidConsents.any {
            it.descriptor == linkMetadataDescriptor
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Optionale Link-Vorschau für wichtige Pushs: Hans kann sichere öffentliche HTTPS-Seiten ohne Cookies oder JavaScript nur für Titel und Beschreibung abrufen. Der Zielserver sieht dabei IP-Adresse und Zeitpunkt. Unsichere oder aktionsartige Links bleiben stumm.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("notification_link_metadata_disclosure"),
        )
        if (!linkMetadataActive) {
            OutlinedButton(
                onClick = callbacks.onNotificationLinkMetadataConsentRequested,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("enable_notification_link_metadata"),
            ) {
                Text("Link-Vorschau ausdrücklich aktivieren")
            }
        }
        state.persistentAndroidConsents.filter {
            !fullPhoneAccess || it.descriptor == linkMetadataDescriptor
        }.forEach { consent ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("persistent_android_consent_${consent.descriptor.localDisplayKey()}"),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(consent.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        consent.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            callbacks.onPersistentAndroidConsentRevoked(consent.descriptor)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(
                                "revoke_persistent_android_consent_${consent.descriptor.localDisplayKey()}",
                            ),
                    ) {
                        Text("Dauerfreigabe widerrufen")
                    }
                }
            }
        }
        if (!fullPhoneAccess && state.persistentAndroidConsents.isNotEmpty()) {
            OutlinedButton(
                onClick = callbacks.onAllPersistentAndroidConsentsRevoked,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("revoke_all_persistent_android_consents"),
            ) {
                Text("Alle Dauerfreigaben widerrufen")
            }
        }
    }
}

@Composable
private fun PersonalSettings(
    state: SettingsUiState,
    callbacks: SettingsUiCallbacks,
) {
    var editingGlossary by remember { mutableStateOf(false) }
    var glossaryDraft by remember(state.confirmedSttGlossary.editorText) {
        mutableStateOf(state.confirmedSttGlossary.editorText)
    }
    SettingsSection(title = "Hans einrichten") {
        Text(
            text = "Hans führt dich Schritt für Schritt durch Tasten, Android-Zugriffe, Sprache, Modellwahl und zuletzt dein persönliches Profil. Ein geöffneter Android-Dialog gilt erst nach einer erneuten Prüfung als erledigt.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = callbacks.onStartOrResumeSetup,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("start_or_resume_setup"),
        ) {
            Text("Setup starten")
        }
    }
    SettingsSection(title = "Hans & du") {
        Text(
            text = "Im freiwilligen Kennenlerngespräch lernt Hans dich Schritt für Schritt kennen. Das Profil wird lokal auf diesem Telefon gespeichert, bei Hans-Anfragen als Kontext verwendet und erst nach deiner Bestätigung übernommen.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = callbacks.onStartGettingToKnow,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("start_getting_to_know"),
        ) {
            Text("Kennenlerngespräch starten")
        }
    }
    SettingsSection(title = "Tempo der Spracheingabe") {
        Text(
            "Wunsch für die nächste Aufnahme. Low bietet etwas mehr Erkennungskontext; Minimal kann vorläufigen Text früher liefern, aber Namen und Zahlen schlechter erkennen. Keine feste Zeitersparnis ist garantiert.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ai.hans.standard.voice.stt.SttTranscriptionDelay.entries.forEach { delay ->
            OutlinedButton(
                onClick = { callbacks.onSttLatencyChanged(delay) },
                modifier = Modifier.fillMaxWidth().testTag("stt_delay_${delay.wireValue}"),
            ) {
                Text((if (state.sttLatency.preferred == delay) "✓ " else "") +
                    if (delay == ai.hans.standard.voice.stt.SttTranscriptionDelay.LOW) "Low · Standard" else "Minimal · früherer Text")
            }
        }
        state.sttLatency.confirmedActive?.let {
            Text("Für die laufende Aufnahme vom Server bestätigt: ${it.wireValue}",
                modifier = Modifier.testTag("stt_delay_confirmed"))
        }
        if (state.sttLatency.notice.isNotBlank()) Text(state.sttLatency.notice)
    }
    SettingsSection(title = "Eigennamen & besondere Begriffe") {
        Text(
            text = "Bestätigte Schreibweisen helfen der Spracheingabe bei Namen, Orten und Fachbegriffen. Sie bleiben app-privat auf diesem Telefon und gelten ab der nächsten Aufnahme.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "${state.confirmedSttGlossary.terms.size} von ${ConfirmedSttGlossaryPolicy.MAX_TERMS} Begriffen gespeichert",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("confirmed_stt_glossary_status"),
        )
        if (state.confirmedSttGlossary.notice.isNotBlank()) {
            Text(
                text = state.confirmedSttGlossary.notice,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("confirmed_stt_glossary_notice"),
            )
        }
        if (editingGlossary) {
            OutlinedTextField(
                value = glossaryDraft,
                onValueChange = { value ->
                    glossaryDraft = value.take(ConfirmedSttGlossaryPolicy.MAX_EDITOR_CHARACTERS)
                },
                label = { Text("Ein Begriff pro Zeile") },
                supportingText = {
                    Text(
                        "Maximal ${ConfirmedSttGlossaryPolicy.MAX_TERMS} Begriffe mit je " +
                            "${ConfirmedSttGlossaryPolicy.MAX_TERM_CHARACTERS} Zeichen",
                    )
                },
                minLines = 5,
                maxLines = 12,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("confirmed_stt_glossary_editor"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        glossaryDraft = state.confirmedSttGlossary.editorText
                        editingGlossary = false
                    },
                    modifier = Modifier.weight(1f).testTag("cancel_confirmed_stt_glossary"),
                ) {
                    Text("Abbrechen")
                }
                Button(
                    onClick = {
                        callbacks.onConfirmedSttGlossarySaved(glossaryDraft)
                        editingGlossary = false
                    },
                    modifier = Modifier.weight(1f).testTag("save_confirmed_stt_glossary"),
                ) {
                    Text("Bestätigen & speichern")
                }
            }
        } else {
            OutlinedButton(
                onClick = { editingGlossary = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("edit_confirmed_stt_glossary"),
            ) {
                Text(
                    if (state.confirmedSttGlossary.terms.isEmpty()) {
                        "Begriffe hinzufügen"
                    } else {
                        "Begriffe bearbeiten"
                    },
                )
            }
        }
    }
    SettingsSection(title = "Langfristiges Benachrichtigungsarchiv") {
        Text(NotificationFactArchiveStatus.DISCLOSURE, style = MaterialTheme.typography.bodyMedium)
        Text(state.notificationFactArchive.summary,
            modifier = Modifier.testTag("notification_fact_archive_status"),
            style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = callbacks.onRefreshNotificationFactArchive,
            enabled = !state.notificationFactArchive.loading,
            modifier = Modifier.testTag("refresh_notification_fact_archive")) {
            Text("Aktualisieren")
        }
    }
}

@Composable
private fun AccessAndBackupSettings(
    state: SettingsUiState,
    callbacks: SettingsUiCallbacks,
    onOpenNativeMemoryHealth: () -> Unit,
    onOpenThirdPartyNotices: () -> Unit,
) {
    SettingsSection(title = "OpenAI-Sprachzugang") {
        Text(
            text = when (state.speechCredentialStatus) {
                SpeechCredentialUiStatus.AVAILABLE ->
                    "OpenAI-Sprachzugang ist sicher eingerichtet."
                SpeechCredentialUiStatus.TEMPORARILY_UNAVAILABLE ->
                    "OpenAI-Sprachzugang ist vorhanden, aber erst nach dem Entsperren wieder verfügbar."
                SpeechCredentialUiStatus.MISSING ->
                    "OpenAI-Sprachzugang ist noch nicht eingerichtet. Du kannst den Schlüssel hier lokal und sicher im Android Keystore hinterlegen."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("speech_credential_status"),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = callbacks.onConfigureSpeechCredential,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("configure_speech_credential"),
        ) {
            Text(
                if (state.speechCredentialStatus == SpeechCredentialUiStatus.MISSING) {
                    "Sprachzugang einrichten"
                } else {
                    "Sprachzugang ersetzen"
                },
            )
        }
        if (state.speechCredentialStatus != SpeechCredentialUiStatus.MISSING) {
            OutlinedButton(
                onClick = callbacks.onRemoveSpeechCredential,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("remove_speech_credential"),
            ) {
                Text("Sprachzugang entfernen")
            }
        }
        Text(
            text = "Für Sprache benötigt Hans einen OpenAI API-Schlüssel. Erstelle ihn in deinem OpenAI API-Konto, kopiere ihn einmal und hinterlege ihn über „Sprachzugang einrichten“. ChatGPT-Abonnement und API-Guthaben werden getrennt abgerechnet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("speech_credential_help"),
        )
        OutlinedButton(
            onClick = callbacks.onOpenOpenAiApiKeyPage,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("open_openai_api_key_page"),
        ) {
            Text("OpenAI API-Schlüssel erstellen")
        }
        TextButton(
            onClick = callbacks.onOpenOpenAiBillingPage,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("open_openai_billing_page"),
        ) {
            Text("API-Guthaben und Limits prüfen")
        }
    }
    SettingsSection(title = "Sicherung") {
        Text(
            text = "Sichert portable Einstellungen, dein bestätigtes Profil, Automationen sowie Plugin-Auswahlen. Anmeldedaten, Schlüssel, Chats, Benachrichtigungen, Android-Freigaben und gerätegebundene Tastencodes bleiben immer auf diesem Telefon.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = callbacks.onExportBackup,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("export_hans_backup"),
        ) {
            Text("Sicherung exportieren")
        }
        OutlinedButton(
            onClick = callbacks.onImportBackup,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("import_hans_backup"),
        ) {
            Text("Sicherung importieren")
        }
    }
    SettingsSection(title = "Über Hans") {
        TextButton(onClick = onOpenNativeMemoryHealth, modifier = Modifier.testTag("native_memory_health_open")) {
            Text("Codex-Gedächtnis: Status")
        }
        TextButton(onClick = onOpenThirdPartyNotices, modifier = Modifier.testTag("open_source_licenses")) {
            Text("Open-Source-Lizenzen")
        }
    }
}

private fun privateSpaceSettingsDescription(state: PrivateSpaceUiState): String = when (
    state.availability
) {
    PrivateSpaceAvailability.UNSUPPORTED_PLATFORM ->
        "Der private Bereich ist erst ab Android 15 verfügbar."
    PrivateSpaceAvailability.HOME_ROLE_REQUIRED ->
        "Lege Hans zuerst als Standard-Startbildschirm fest. Erst dann darf Android den privaten Bereich zeigen oder steuern."
    PrivateSpaceAvailability.HIDDEN_PROFILE_ACCESS_REQUIRED ->
        "Dieser Hans-Build besitzt keinen wirksamen Zugriff auf verborgene Profile. Der private Bereich bleibt vollständig verborgen."
    PrivateSpaceAvailability.NOT_CONFIGURED ->
        if (state.settingsAvailable) {
            "Auf diesem Telefon ist noch kein privater Bereich eingerichtet."
        } else {
            "Auf diesem Telefon ist kein für Hans zugänglicher privater Bereich eingerichtet."
        }
    PrivateSpaceAvailability.AVAILABLE -> when {
        !state.containerVisible ->
            "Der private Bereich ist im App-Bereich ausgeblendet. Du kannst ihn hier jederzeit wieder einblenden."
        state.locked && state.entrypointHiddenWhenLocked ->
            "Android blendet den gesperrten privaten Bereich systemweit aus. Nach dem Entsperren erscheint er wieder im App-Bereich."
        state.locked ->
            "Der private Bereich ist sichtbar, aber gesperrt. Apps und Suchtreffer bleiben verborgen."
        else -> "Der private Bereich ist sichtbar und entsperrt."
    }
}

@Composable
private fun Mp01VendorActionConflictCard(
    state: Mp01VendorActionConflictUiState,
    callbacks: SettingsUiCallbacks,
) {
    if (!state.detected) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mp01_vendor_action_conflict"),
        color = if (state.replacementRequired) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (state.replacementRequired) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (
                    state.kind == Mp01VendorActionConflictUiKind.STOCK_SYSTEM_POLICY
                ) {
                    if (state.stockPressToggleCompatible) {
                        "Einmal kurz drücken startet die Aufnahme, nochmals kurz drücken beendet und sendet. Nicht halten; ab ca. 400 ms öffnet Minimal seine Display-Einstellungen. Die kurze E-Ink-Auffrischung ist normal."
                    } else {
                        "Das originale MP01-System verarbeitet das Halten dieser Seitentaste vor der App-Steuerung. Hans Standard kann diese Belegung nicht übernehmen. Wähle eine andere Taste."
                    }
                } else if (state.replacementConfirmed) {
                    val purpose = when (state.shortcutKinds) {
                        setOf(Mp01VendorShortcutUiKind.DICTATION) -> "für Diktate"
                        setOf(Mp01VendorShortcutUiKind.MODEL_TOGGLE) ->
                            "für den Modellwechsel"
                        else -> "für die gespeicherten Hans-Kurzbefehle"
                    }
                    "Die ursprüngliche Minimal-Belegung ist laut deiner Bestätigung deaktiviert. Hans verwendet die mittlere Seitentaste $purpose."
                } else {
                    "Die gespeicherte Hans-Belegung ist noch blockiert: Die mittlere MP01-Taste löst parallel eine Minimal-Displayaktion aus. Öffne die Minimal-Einstellungen und setze unter „Refresh Button“ alle sechs Aktionen auf „Nothing“: bei ein- und ausgeschaltetem Bildschirm jeweils kurzer, doppelter und langer Druck."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("mp01_vendor_action_instructions"),
            )
            if (
                state.kind == Mp01VendorActionConflictUiKind.LEGACY_ACCESSIBILITY &&
                !state.settingsActivityAvailable
            ) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Die originale Einstellungsseite lässt sich nicht direkt öffnen. Öffne sie bitte in den Minimal-Einstellungen manuell und ändere dort den Refresh Button.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("mp01_vendor_settings_unavailable"),
                )
            }
            if (state.kind == Mp01VendorActionConflictUiKind.LEGACY_ACCESSIBILITY) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = callbacks.onOpenMp01VendorSettings,
                    enabled = state.settingsActivityAvailable,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_mp01_vendor_settings"),
                ) {
                    Text("Minimal-Belegung ersetzen")
                }
                if (state.replacementRequired) {
                    Button(
                        onClick = callbacks.onConfirmMp01VendorActionCleared,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("confirm_mp01_vendor_action_cleared"),
                    ) {
                        Text("Alle sechs stehen auf Nothing")
                    }
                }
            }
        }
    }
}

private fun Mp01VendorActionConflictUiState.isStockPressToggleFor(
    shortcutKind: Mp01VendorShortcutUiKind,
): Boolean =
    detected &&
        kind == Mp01VendorActionConflictUiKind.STOCK_SYSTEM_POLICY &&
        stockPressToggleCompatible &&
        shortcutKind in shortcutKinds

@Composable
private fun <T> OptionRows(values: List<T>, content: @Composable RowScope.(T) -> Unit) {
    values.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { content(it) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(22.dp))
    Text(
        text = title,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.height(9.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@Composable
private fun SelectableOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String,
) {
    val optionModifier = modifier
        .fillMaxWidth()
        .semantics {
            this.selected = selected
            role = Role.RadioButton
        }
        .testTag(testTag)

    if (selected) {
        Button(
            onClick = onClick,
            modifier = optionModifier,
        ) {
            Text("✓  $label")
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = optionModifier,
        ) {
            Text(label)
        }
    }
}
