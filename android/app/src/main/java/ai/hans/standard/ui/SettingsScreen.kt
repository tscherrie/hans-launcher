package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.BuildConfig
import ai.hans.standard.remotecontrol.DesktopRemoteAccessPolicy
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.localization.rememberHansTextResolver
import androidx.compose.ui.res.stringResource

import androidx.compose.material3.TextButton

import ai.hans.standard.phone.display.DisplayMotionDecision
import ai.hans.standard.phone.display.DisplayMotionMode
import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import ai.hans.standard.phone.capabilities.PrivateSpaceAvailability
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    val uiText = rememberHansTextResolver()
    var navigation by remember { mutableStateOf(SettingsNavigation()) }
    val group = navigation.group
    val onBack = {
        if (group == null) callbacks.onBack() else navigation = navigation.backToGroups()
    }
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        ScreenHeader(title = stringResource(navigation.titleResource), onBack = onBack)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        key(group) {
            if (group == null) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    SettingsGroupOverview(
                        onGroupSelected = select@{ selected ->
                            if (!selected.available) return@select
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
    val uiText = rememberHansTextResolver()
    Column(modifier.fillMaxWidth().testTag("settings_group_overview")) {
        SettingsGroup.availableGroups.forEach { group ->
            TextButton(
                onClick = { if (group.available) onGroupSelected(group) },
                modifier = Modifier.fillMaxWidth().testTag("settings_group_${group.id}"),
            ) {
                Text(
                    text = stringResource(group.titleResource),
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
    val effectiveGroup = group.destination ?: return
    LaunchedEffect(effectiveGroup) {
        if (effectiveGroup == SettingsGroup.MAINTENANCE) callbacks.onRemoteWorkerSettingsOpened()
    }
    val uiText = rememberHansTextResolver()
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
        when (effectiveGroup) {
            SettingsGroup.PERMISSIONS -> PermissionSettings(state, callbacks)
            SettingsGroup.RUNTIME -> RuntimeSettings(state, callbacks)
            SettingsGroup.SPEECH -> SpeechSettings(state, callbacks)
            SettingsGroup.INPUT -> InputSettings(state, callbacks, displayMotion)
            SettingsGroup.PERSONAL -> PersonalSettings(state, callbacks,
                onOpenNativeMemoryHealth = { showNativeMemoryHealth = true })
            SettingsGroup.REMOTE_CONTROL -> RemoteControlSettings(state, callbacks)
            SettingsGroup.ADVANCED_WORK, SettingsGroup.MAINTENANCE -> SystemSettings(
                state,
                callbacks,
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
    val uiText = rememberHansTextResolver()
    val remote = state.remoteWorker
    var draft by remember(remote.revision, remote.loaded) {
        mutableStateOf(remote.formDraft)
    }
    val draftMatchesSaved = draft.normalized() == remote.formDraft.normalized()
    var showWorkerConfiguration by remember { mutableStateOf(false) }
    SettingsSection(title = uiText.text(R.string.ui_optional_work_computer_bbef1a)) {
        Text(
            text = stringResource(R.string.ui_hans_can_delegate_large_explicitly_authorized_ta_85d9e0),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        RemoteWorkerStateCard(remote)
        if (!draftMatchesSaved) {
            Text(stringResource(R.string.settings_unsaved_changes),
                modifier = Modifier.testTag("remote_worker_unsaved_changes"),
                style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(
            onClick = { showWorkerConfiguration = !showWorkerConfiguration },
            modifier = Modifier.fillMaxWidth().testTag("remote_worker_configuration_toggle"),
        ) { Text(stringResource(R.string.settings_worker_configuration)) }
    }
    if (showWorkerConfiguration) {
        SettingsSection(title = stringResource(R.string.settings_worker_configuration)) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = draft.workerId,
            onValueChange = { value -> draft = draft.copy(workerId = value.take(128)) },
            enabled = !remote.operationInProgress,
            singleLine = true,
            label = { Text(stringResource(R.string.ui_worker_id)) },
            modifier = Modifier.fillMaxWidth().testTag("remote_worker_id"),
        )
        OutlinedTextField(
            value = draft.httpsOrigin,
            onValueChange = { value -> draft = draft.copy(httpsOrigin = value.take(2_048)) },
            enabled = !remote.operationInProgress,
            singleLine = true,
            label = { Text(stringResource(R.string.ui_https_address)) },
            supportingText = { Text(stringResource(R.string.ui_address_only_without_a_path_sign_in_details_or_p_e4c03d)) },
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
            label = { Text(stringResource(R.string.ui_spki_pin_sha_256_fd576d)) },
            supportingText = { Text(stringResource(R.string.ui_64_hexadecimal_characters_from_a_trusted_source_e713e6)) },
            modifier = Modifier.fillMaxWidth().testTag("remote_worker_spki_pin"),
        )
    }

    SettingsSection(title = uiText.text(R.string.ui_approved_adapters_25141a)) {
        Text(
            text = stringResource(R.string.ui_each_row_approves_exactly_one_adapter_id_and_ver_06f0fc),
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
                    label = { Text(stringResource(R.string.ui_adapter_id)) },
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
                    label = { Text(stringResource(R.string.ui_version_dd1679)) },
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
                    Text(stringResource(R.string.ui_remove_adapter_942337))
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
            Text(stringResource(R.string.ui_add_adapter_c14091))
        }
    }

    SettingsSection(title = uiText.text(R.string.ui_usage_947c0a)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_request_remote_work_c2fe08), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.ui_saving_alone_does_not_establish_a_connection_cd426c),
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
        draft.validationMessage(uiText)?.let { validation ->
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
            Text(stringResource(R.string.ui_save_configuration_a8acdf))
        }
        if (
            remote.configured && remote.requestedEnabled && !remote.effective &&
            !draftMatchesSaved
        ) {
            Text(
                stringResource(R.string.ui_save_your_changes_before_activating_this_connect_02dd24),
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
            Text(stringResource(R.string.ui_verify_and_activate_connection_7793b1))
        }
    }
    }
}

@Composable
private fun RemoteWorkerStateCard(state: RemoteWorkerSettingsUiState) {
    val uiText = rememberHansTextResolver()
    val status = when (state.status) {
        RemoteWorkerSettingsUiStatus.NOT_LOADED -> uiText.text(R.string.ui_the_local_configuration_is_read_only_when_this_p_723885)
        RemoteWorkerSettingsUiStatus.UNCONFIGURED -> uiText.text(R.string.ui_not_set_up_yet_d89c2d)
        RemoteWorkerSettingsUiStatus.DISABLED -> uiText.text(R.string.ui_saved_but_switched_off_404c67)
        RemoteWorkerSettingsUiStatus.NEEDS_ACTIVATION ->
            uiText.text(R.string.ui_requested_but_not_yet_verified_or_effective_63a6fa)
        RemoteWorkerSettingsUiStatus.EFFECTIVE -> uiText.text(R.string.ui_verified_and_effective_for_codex_136423)
        RemoteWorkerSettingsUiStatus.STORAGE_CORRUPT ->
            uiText.text(R.string.ui_the_local_configuration_could_not_be_read_safely_c4b6d6)
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
                stringResource(R.string.ui_requested_effective,
                    stringResource(if (state.requestedEnabled) R.string.ui_on else R.string.ui_off_f0ce94),
                    stringResource(if (state.effective) R.string.ui_yes else R.string.ui_no)),
                modifier = Modifier.testTag("remote_worker_effective_state"),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.operationInProgress) {
                Text(stringResource(R.string.ui_verifying_the_requested_connection_712160), style = MaterialTheme.typography.bodyMedium)
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
    val uiText = rememberHansTextResolver()
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
    SettingsSection(title = uiText.text(R.string.ui_model_ff461e)) {
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

    SettingsSection(title = uiText.text(R.string.ui_reasoning_effort_cd8569)) {
        OptionRows(state.reasoningEfforts) { effort ->
            SelectableOption(
                label = stringResource(effort.labelResource),
                selected = state.selectedReasoningEffortId == effort.id,
                onClick = { callbacks.onReasoningEffortSelected(effort.id) },
                testTag = "effort_${effort.id}",
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (state.fastModeAvailable) {
        SettingsSection(title = uiText.text(R.string.ui_response_speed_3ec789)) {
            Text(
                text = stringResource(R.string.ui_fast_uses_the_faster_processing_queue_offered_by_16a626),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            SelectableOption(
                label = uiText.text(R.string.ui_standard_ef6691),
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

}

@Composable
private fun UpdateSettings(state: SettingsUiState, callbacks: SettingsUiCallbacks) {
    val uiText = rememberHansTextResolver()
    SettingsSection(title = stringResource(R.string.settings_updates_versions)) {
        Text(stringResource(R.string.settings_hans_version, BuildConfig.VERSION_NAME),
            modifier = Modifier.testTag("hans_app_version"),
            style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.ui_installed_codex, state.codexUpdate.bundledRuntimeVersion.ifBlank { uiText.text(R.string.ui_unavailable_5db96a) }),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("codex_runtime_version"),
        )
        Text(
            text = if (state.codexUpdate.runtimeReady) {
                uiText.text(R.string.ui_hans_has_confirmed_that_the_embedded_codex_runti_473987)
            } else {
                uiText.text(R.string.ui_the_embedded_codex_runtime_has_not_yet_been_conf_78f589)
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("codex_runtime_state"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (state.codexUpdate.updateUrlConfigured) {
                uiText.text(R.string.ui_the_update_page_provides_the_signed_hans_version_3db988)
            } else {
                uiText.text(R.string.ui_codex_is_bundled_in_the_signed_hans_app_and_upda_37f0a3)
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("codex_update_delivery"),
        )
        OutlinedButton(
            onClick = callbacks.onUpdateCodex,
            modifier = Modifier.fillMaxWidth().testTag("codex_update"),
        ) {
            Text(stringResource(R.string.ui_update_codex_62b143))
        }
    }

}

@Composable
private fun SpeechSettings(state: SettingsUiState, callbacks: SettingsUiCallbacks) {
    LiveSpeechSettings(state, callbacks)
    DictationSettings(callbacks)
    ReadAloudSettings(state, callbacks)
}

@Composable
private fun ReadAloudSettings(state: SettingsUiState, callbacks: SettingsUiCallbacks) {
    SettingsSection(title = stringResource(R.string.voice_settings_text_replies_title)) {
        Text(
            text = stringResource(R.string.voice_settings_text_replies_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("read_aloud_live_voice_help"),
        )
        ReadAloudUiMode.entries.forEach { mode ->
            SelectableOption(
                label = stringResource(mode.labelResource),
                selected = state.readAloudMode == mode,
                onClick = { callbacks.onReadAloudModeSelected(mode) },
                testTag = "read_aloud_${mode.name.lowercase()}",
            )
        }
    }
}

@Composable
private fun LiveSpeechSettings(state: SettingsUiState, callbacks: SettingsUiCallbacks) {
    var showVoiceChooser by remember { mutableStateOf(false) }
    val selectedVoice = state.liveVoices.firstOrNull { it.id == state.selectedLiveVoiceId }
    fun voiceLabel(id: String): String =
        (state.liveVoices.firstOrNull { it.id == id }?.label ?: id).replaceFirstChar { it.titlecase() }
    SettingsSection(title = stringResource(R.string.voice_settings_voice_title)) {
        OutlinedButton(
            onClick = { showVoiceChooser = true },
            enabled = state.liveVoices.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("open_voice_chooser"),
        ) {
            Text(
                if (selectedVoice == null) stringResource(R.string.voice_settings_choose_voice)
                else stringResource(R.string.voice_settings_selected_voice, voiceLabel(selectedVoice.id)),
            )
        }
        Text(
            text = stringResource(R.string.voice_settings_voice_scope),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("live_voice_scope"),
        )
        state.activeLiveVoiceId?.let { activeVoice ->
            Text(
                text = stringResource(R.string.voice_settings_current_voice, voiceLabel(activeVoice)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("live_voice_selection_summary"),
            )
        }
        if (state.liveVoices.isEmpty()) {
            Text(
                text = stringResource(R.string.ui_loading_available_live_voices_be810d),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    if (showVoiceChooser) {
        AlertDialog(
            onDismissRequest = { showVoiceChooser = false },
            title = { Text(stringResource(R.string.voice_settings_voice_title)) },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()).testTag("voice_chooser_options"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.liveVoices.forEach { voice ->
                        SelectableOption(
                            label = voiceLabel(voice.id),
                            selected = state.selectedLiveVoiceId == voice.id,
                            onClick = { callbacks.onLiveVoiceSelected(voice.id) },
                            testTag = "live_voice_${voice.id}",
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = callbacks.onPreviewVoice,
                    enabled = selectedVoice != null && !state.voiceSessionActive && state.activeLiveVoiceId == null,
                    modifier = Modifier.testTag("preview_voice"),
                ) { Text(stringResource(R.string.ui_test_voice_24c27d)) }
            },
            confirmButton = {
                TextButton(
                    onClick = { showVoiceChooser = false },
                    modifier = Modifier.testTag("close_voice_chooser"),
                ) { Text(stringResource(R.string.voice_settings_done)) }
            },
        )
    }
}

@Composable
private fun InputSettings(
    state: SettingsUiState,
    callbacks: SettingsUiCallbacks,
    displayMotion: DisplayMotionDecision,
) {
    val uiText = rememberHansTextResolver()
    var showDisplayHelp by remember { mutableStateOf(false) }
    SettingsSection(title = stringResource(R.string.ui_display_mode)) {
        listOf(
            DisplayMotionMode.AUTOMATIC to uiText.text(R.string.ui_automatic_4c2a39),
            DisplayMotionMode.E_INK to "E-Ink",
            DisplayMotionMode.STANDARD to uiText.text(R.string.ui_standard_display_2c2a5d),
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
                displayMotion.isEink -> uiText.text(R.string.ui_e_ink_display_mode_active_the_cursor_and_working_3a248d)
                !displayMotion.systemAnimationsEnabled ->
                    uiText.text(R.string.ui_standard_display_mode_active_animations_are_curr_3d5711)
                else -> uiText.text(R.string.ui_standard_display_mode_active_the_cursor_and_work_9889b8)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("display_motion_effective"),
        )
        TextButton(
            onClick = { showDisplayHelp = !showDisplayHelp },
            modifier = Modifier.testTag("display_motion_help_toggle"),
        ) { Text(stringResource(if (showDisplayHelp) R.string.voice_settings_less else R.string.voice_settings_more)) }
        if (showDisplayHelp) {
            Text(
                text = stringResource(R.string.ui_automatic_mode_detects_the_minimal_phone_mp01_ch_f11af3) +
                    uiText.text(R.string.ui_android_s_animation_setting_takes_priority_the_a_8baa1a),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("display_motion_help"),
            )
        }
    }
    SettingsSection(title = uiText.text(R.string.ui_action_key_4c8d79)) {
        Text(
            text = when {
                state.actionKey.capturing -> uiText.text(R.string.voice_settings_key_capture)
                state.actionKey.mp01VendorConflict.replacementRequired &&
                    Mp01VendorShortcutUiKind.DICTATION in
                    state.actionKey.mp01VendorConflict.shortcutKinds ->
                    uiText.text(R.string.voice_settings_key_blocked)
                state.actionKey.configured -> uiText.text(R.string.voice_settings_key_ready)
                state.actionKey.assigned -> uiText.text(R.string.voice_settings_key_unavailable)
                else -> uiText.text(R.string.voice_settings_key_unassigned)
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
                Text(stringResource(R.string.ui_cancel_setup_0e8d88))
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
                        uiText.text(R.string.ui_change_key_9c71f6)
                    } else {
                        uiText.text(R.string.ui_set_up_key_d44680)
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
                Text(stringResource(R.string.ui_remove_action_key_af7739))
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

    SettingsSection(title = uiText.text(R.string.ui_model_toggle_key_optional_4fdd43)) {
        Text(
            text = when {
                state.actionKey.capturingModelToggle ->
                    uiText.text(R.string.ui_press_the_key_you_want_to_use_to_switch_between__0faa2a)
                state.actionKey.mp01VendorConflict.isStockPressToggleFor(
                    Mp01VendorShortcutUiKind.MODEL_TOGGLE,
                ) ->
                    uiText.text(R.string.ui_configured_a_short_press_switches_between_luna_m_cc8385)
                state.actionKey.mp01VendorConflict.replacementRequired &&
                    Mp01VendorShortcutUiKind.MODEL_TOGGLE in
                    state.actionKey.mp01VendorConflict.shortcutKinds ->
                    if (
                        state.actionKey.mp01VendorConflict.kind ==
                        Mp01VendorActionConflictUiKind.STOCK_SYSTEM_POLICY
                    ) {
                        uiText.text(R.string.ui_saved_but_blocked_hans_standard_cannot_take_over_c2c8b7)
                    } else {
                        uiText.text(R.string.ui_saved_but_blocked_this_key_becomes_available_for_5067ed)
                    }
                state.actionKey.modelToggleConfigured ->
                    uiText.text(R.string.ui_configured_press_the_key_to_switch_between_luna__7f33f4)
                else ->
                    uiText.text(R.string.ui_you_can_choose_a_second_available_hardware_key_f_7a1c4c)
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
                Text(stringResource(R.string.ui_cancel_setup_0e8d88))
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
                        uiText.text(R.string.ui_change_toggle_key_140bec)
                    } else {
                        uiText.text(R.string.ui_set_up_toggle_key_6515fb)
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
                Text(stringResource(R.string.ui_remove_toggle_key_7c3e92))
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
    state.capabilityAccess.firstOrNull { it.id == CapabilityAccessUiId.QUICK_SETTINGS_TILE }?.let { tile ->
        SettingsSection(title = stringResource(R.string.settings_more_ways_to_start)) {
            Text(tile.description, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { callbacks.onCapabilityAccessRequested(CapabilityAccessUiId.QUICK_SETTINGS_TILE) },
                modifier = Modifier.fillMaxWidth().testTag("grant_quick_settings_tile"),
            ) { Text(tile.title) }
        }
    }

}

@Composable
private fun PermissionSettings(
    state: SettingsUiState,
    callbacks: SettingsUiCallbacks,
) {
    val uiText = rememberHansTextResolver()
    if (DesktopRemoteAccessPolicy.enabled) {
        SettingsSection(title = uiText.text(R.string.presentation_settings_remote_control)) {
            Text(stringResource(R.string.remote_control_hans_permission),
                modifier = Modifier.testTag("permissions_remote_control_kind"))
            RemoteControlAccessControls(state.remoteControl, callbacks, tagPrefix = "permissions_remote_control")
        }
    }
    SettingsSection(title = uiText.text(R.string.ui_phone_access_8e7d84)) {
        Text(
            text = stringResource(R.string.ui_hans_requests_only_the_access_you_enable_you_can_373773),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        val androidAccessOrder = listOf(
            CapabilityAccessUiId.HOME_APP, CapabilityAccessUiId.ACCESSIBILITY,
            CapabilityAccessUiId.MICROPHONE, CapabilityAccessUiId.APP_NOTIFICATIONS,
            CapabilityAccessUiId.NOTIFICATION_ACCESS, CapabilityAccessUiId.ALL_FILES,
            CapabilityAccessUiId.CONTACTS, CapabilityAccessUiId.CALENDAR, CapabilityAccessUiId.LOCATION,
            CapabilityAccessUiId.PHOTOS_AND_VIDEOS, CapabilityAccessUiId.AUDIO_MEDIA,
            CapabilityAccessUiId.EXACT_ALARMS,
        )
        state.capabilityAccess.filter { it.id != CapabilityAccessUiId.QUICK_SETTINGS_TILE }
            .sortedBy { androidAccessOrder.indexOf(it.id).let { index -> if (index < 0) Int.MAX_VALUE else index } }
            .forEach { capability ->
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
                            Text(if (capability.granted) uiText.text(R.string.ui_manage_in_android_08081a) else uiText.text(R.string.ui_set_up_375145))
                        }
                    }
                }
            }
        }
    }

    val fullPhoneAccess = state.phoneActionPolicy ==
        HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS
    SettingsSection(title = if (fullPhoneAccess) uiText.text(R.string.ui_full_access_yolo_32c778) else uiText.text(R.string.ui_persistent_approvals_for_hans_7f1a84)) {
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
                        state.everydayAccessBundleActive -> uiText.text(R.string.ui_everyday_access_active_013c05)
                        state.persistentAndroidConsents.isEmpty() ->
                            uiText.text(R.string.ui_enable_everyday_access_2aa7c4)
                        else -> uiText.text(R.string.ui_complete_everyday_access_aa0d45)
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (fullPhoneAccess) {
                uiText.text(R.string.ui_no_additional_hans_confirmation_prompts_for_your_d7ecc1)
            } else if (state.everydayAccessBundleActive) {
                uiText.text(R.string.ui_hans_may_perform_recurring_read_and_visible_open_c91d0a)
            } else if (state.persistentAndroidConsents.isEmpty()) {
                uiText.text(R.string.ui_no_hans_action_has_persistent_approval_android_s_2a96fb)
            } else {
                uiText.text(R.string.ui_some_hans_approvals_are_active_android_system_pe_6ddc98)
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
            text = stringResource(R.string.ui_optional_link_previews_for_important_notificatio_837856),
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
                Text(stringResource(R.string.ui_explicitly_enable_link_previews_be7e5b))
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
                        Text(stringResource(R.string.ui_revoke_persistent_approval_fa5f02))
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
                Text(stringResource(R.string.ui_revoke_all_persistent_approvals_19dd14))
            }
        }
    }
    NotificationSettings(state, callbacks)
    if (state.privateSpace.isRelevantToSettings) {
        SettingsSection(title = uiText.text(R.string.ui_private_space_94f1f6)) {
            Text(
                text = privateSpaceSettingsDescription(state.privateSpace, uiText),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("private_space_settings_status"),
            )
            if (state.privateSpace.profilePresent) {
                SelectableOption(
                    label = uiText.text(R.string.ui_show_in_apps_6332f1),
                    selected = state.privateSpace.containerVisible,
                    onClick = {
                        callbacks.onPrivateSpaceVisibilityChanged(true)
                    },
                    testTag = "show_private_space_container",
                )
                SelectableOption(
                    label = uiText.text(R.string.ui_hide_from_apps_1162a8),
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
                                uiText.text(R.string.ui_unlock_private_space_040c6d)
                            } else {
                                uiText.text(R.string.ui_lock_private_space_cb38f0)
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
                    Text(stringResource(R.string.ui_manage_private_space_37de3e))
                }
            }
            if (state.privateSpace.operationInProgress) {
                Text(
                    text = stringResource(R.string.ui_android_is_updating_private_space_0645f5),
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


}

@Composable
private fun PersonalSettings(state: SettingsUiState, callbacks: SettingsUiCallbacks, onOpenNativeMemoryHealth: () -> Unit) {
    val uiText = rememberHansTextResolver()
    SettingsSection(title = uiText.text(R.string.ui_set_up_hans_d88de6)) {
        Text(
            text = stringResource(R.string.ui_hans_guides_you_through_keys_android_access_voic_bcef2d),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = callbacks.onStartOrResumeSetup,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("start_or_resume_setup"),
        ) {
            Text(stringResource(R.string.ui_start_setup_134b41))
        }
    }
    SettingsSection(title = uiText.text(R.string.ui_hans_you_d78dca)) {
        Text(
            text = stringResource(R.string.ui_in_an_optional_introductory_conversation_hans_ge_84458a),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = callbacks.onStartGettingToKnow,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("start_getting_to_know"),
        ) {
            Text(stringResource(R.string.ui_start_introductory_conversation_2b4332))
        }
    }

    SettingsSection(title = stringResource(R.string.settings_memory)) {
        TextButton(onClick = onOpenNativeMemoryHealth, modifier = Modifier.testTag("native_memory_health_open")) {
            Text(stringResource(R.string.ui_codex_memory_status_5f1ab9))
        }
    }
}

@Composable
private fun DictationSettings(callbacks: SettingsUiCallbacks) {
    var showVoiceHelp by remember { mutableStateOf(false) }
    SettingsSection(title = stringResource(R.string.voice_settings_usage_title)) {
        Text(
            stringResource(R.string.voice_settings_task_overview),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("voice_task_overview"),
        )
        Text(
            stringResource(R.string.voice_settings_call_overview),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("voice_call_overview"),
        )
        TextButton(
            onClick = { showVoiceHelp = !showVoiceHelp },
            modifier = Modifier.testTag("voice_usage_help_toggle"),
        ) {
            Text(stringResource(if (showVoiceHelp) R.string.voice_settings_less else R.string.voice_settings_how_it_works))
        }
        if (showVoiceHelp) {
            Text(
                stringResource(R.string.voice_settings_task_title),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.voice_settings_task_help),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("codex_dictation_access"),
            )
            Text(
                stringResource(R.string.voice_settings_task_idle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("voice_task_idle_help"),
            )
            Text(
                stringResource(R.string.voice_settings_call_title),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.voice_settings_call_help),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("voice_call_help"),
            )
            Text(
                text = stringResource(R.string.voice_settings_voice_usage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.voice_settings_account_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("codex_live_access"),
            )
        }
    }
    // Legacy glossary and delay preferences are intentionally retained in storage, but the
    // current continuous Codex voice path does not consume them. Do not offer ineffective controls.
    TextButton(
        onClick = { callbacks.onCapabilityAccessRequested(CapabilityAccessUiId.MICROPHONE) },
        modifier = Modifier.fillMaxWidth().testTag("dictation_microphone_access"),
    ) { Text(stringResource(R.string.settings_microphone_access)) }
}

@Composable
private fun NotificationSettings(state: SettingsUiState, callbacks: SettingsUiCallbacks) {
    val uiText = rememberHansTextResolver()
    var showArchiveDetails by remember { mutableStateOf(false) }
    SettingsSection(title = stringResource(R.string.agent_channel_title)) {
        WhatsAppAgentChannelSettings(
            state = state.whatsAppAgentChannel,
            onConfigure = callbacks.onConfigureWhatsAppAgentChannel,
            onDisable = callbacks.onDisableWhatsAppAgentChannel,
        )
    }
    SettingsSection(title = uiText.text(R.string.ui_long_term_notification_archive_3e70ed)) {
        Text(NotificationFactArchiveStatus.disclosure(uiText), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(when {
            state.notificationFactArchive.loading -> R.string.presentation_archive_loading
            state.notificationFactArchive.available == true -> R.string.presentation_archive_available
            state.notificationFactArchive.available == false -> R.string.presentation_archive_unavailable
            else -> R.string.presentation_archive_unknown
        }),
            modifier = Modifier.testTag("notification_fact_archive_status"),
            style = MaterialTheme.typography.bodyMedium)
        if (state.notificationFactArchive.capacityExceeded == true) {
            Text(stringResource(R.string.presentation_archive_capacity),
                modifier = Modifier.testTag("notification_fact_archive_capacity"),
                style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { showArchiveDetails = !showArchiveDetails },
            modifier = Modifier.testTag("notification_fact_archive_details_toggle")) {
            Text(stringResource(R.string.settings_archive_details))
        }
        if (showArchiveDetails) {
            Text(state.notificationFactArchive.summary(uiText),
                modifier = Modifier.testTag("notification_fact_archive_details"),
                style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = callbacks.onRefreshNotificationFactArchive,
            enabled = !state.notificationFactArchive.loading,
            modifier = Modifier.testTag("refresh_notification_fact_archive")) {
            Text(stringResource(R.string.ui_refresh_96bf00))
        }
    }
}

@Composable
private fun SpeechCredentialSettings(state: SettingsUiState, callbacks: SettingsUiCallbacks) {
    val uiText = rememberHansTextResolver()
    SettingsSection(title = uiText.text(R.string.ui_openai_voice_access_88d085)) {
        Text(
            text = when (state.speechCredentialStatus) {
                SpeechCredentialUiStatus.AVAILABLE ->
                    uiText.text(R.string.ui_openai_voice_access_is_securely_configured_74f9e0)
                SpeechCredentialUiStatus.TEMPORARILY_UNAVAILABLE ->
                    uiText.text(R.string.ui_openai_voice_access_is_configured_but_becomes_av_4eafb6)
                SpeechCredentialUiStatus.MISSING ->
                    uiText.text(R.string.ui_openai_voice_access_is_not_configured_yet_you_ca_1ba533)
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
                    uiText.text(R.string.ui_set_up_voice_access_cb7423)
                } else {
                    uiText.text(R.string.ui_replace_voice_access_68f54f)
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
                Text(stringResource(R.string.ui_remove_voice_access_2e1f4d))
            }
        }
        Text(
            text = stringResource(if (ai.hans.standard.voice.android.CodexDictationIntegration.enabled)
                R.string.settings_read_aloud_api_help else R.string.ui_hans_requires_an_openai_api_key_for_voice_create_ea85fb),
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
            Text(stringResource(R.string.ui_create_openai_api_key_56cab8))
        }
        TextButton(
            onClick = callbacks.onOpenOpenAiBillingPage,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("open_openai_billing_page"),
        ) {
            Text(stringResource(R.string.ui_check_api_credit_and_limits_1182e0))
        }
    }

}

@Composable
private fun SystemSettings(state: SettingsUiState, callbacks: SettingsUiCallbacks, onOpenThirdPartyNotices: () -> Unit) {
    val uiText = rememberHansTextResolver()
    UpdateSettings(state, callbacks)
    SettingsSection(title = uiText.text(R.string.ui_backup_07ad3e)) {
        Text(
            text = stringResource(R.string.ui_backs_up_portable_settings_your_confirmed_profil_3f5c92),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = callbacks.onExportBackup,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("export_hans_backup"),
        ) {
            Text(stringResource(R.string.ui_export_backup_828578))
        }
        OutlinedButton(
            onClick = callbacks.onImportBackup,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("import_hans_backup"),
        ) {
            Text(stringResource(R.string.ui_import_backup_ff454a))
        }
    }

    AdvancedWorkSettings(state, callbacks)
    SettingsSection(title = uiText.text(R.string.ui_diagnostics_64dd65)) {
        OutlinedButton(onClick = callbacks.onOpenWorkbench,
            modifier = Modifier.fillMaxWidth().testTag("open_workbench")) {
            Text(stringResource(R.string.ui_internal_work_files_aac9b5))
        }
    }
    SettingsSection(title = uiText.text(R.string.ui_about_hans_c582da)) {
        TextButton(onClick = onOpenThirdPartyNotices, modifier = Modifier.testTag("open_source_licenses")) {
            Text(stringResource(R.string.ui_open_source_licenses))
        }
    }
}

private fun privateSpaceSettingsDescription(state: PrivateSpaceUiState, uiText: HansTextResolver): String = when (
    state.availability
) {
    PrivateSpaceAvailability.UNSUPPORTED_PLATFORM ->
        uiText.text(R.string.ui_private_space_requires_android_15_or_later_20b9b0)
    PrivateSpaceAvailability.HOME_ROLE_REQUIRED ->
        uiText.text(R.string.ui_set_hans_as_your_default_home_screen_first_only__df9262)
    PrivateSpaceAvailability.HIDDEN_PROFILE_ACCESS_REQUIRED ->
        uiText.text(R.string.ui_this_hans_build_has_no_effective_access_to_hidde_9616fb)
    PrivateSpaceAvailability.NOT_CONFIGURED ->
        if (state.settingsAvailable) {
            uiText.text(R.string.ui_private_space_is_not_set_up_on_this_phone_yet_bad3e6)
        } else {
            uiText.text(R.string.ui_no_private_space_accessible_to_hans_is_set_up_on_131022)
        }
    PrivateSpaceAvailability.AVAILABLE -> when {
        !state.containerVisible ->
            uiText.text(R.string.ui_private_space_is_hidden_from_apps_you_can_show_i_69e5a0)
        state.locked && state.entrypointHiddenWhenLocked ->
            uiText.text(R.string.ui_android_hides_locked_private_space_throughout_th_ca4b31)
        state.locked ->
            uiText.text(R.string.ui_private_space_is_visible_but_locked_its_apps_and_0f3497)
        else -> uiText.text(R.string.ui_private_space_is_visible_and_unlocked_46a205)
    }
}

@Composable
private fun Mp01VendorActionConflictCard(
    state: Mp01VendorActionConflictUiState,
    callbacks: SettingsUiCallbacks,
) {
    val uiText = rememberHansTextResolver()
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
                        uiText.text(R.string.voice_settings_mp01_press)
                    } else {
                        uiText.text(R.string.ui_the_stock_mp01_system_handles_holding_this_side__bb95d9)
                    }
                } else if (state.replacementConfirmed) {
                    val purpose = when (state.shortcutKinds) {
                        setOf(Mp01VendorShortcutUiKind.DICTATION) -> uiText.text(R.string.ui_for_dictation_c0dd50)
                        setOf(Mp01VendorShortcutUiKind.MODEL_TOGGLE) ->
                            uiText.text(R.string.ui_for_model_switching_6b1456)
                        else -> uiText.text(R.string.ui_for_your_saved_hans_shortcuts_869537)
                    }
                    uiText.text(R.string.ui_you_confirmed_that_the_original_minimal_mapping__ee1290, purpose)
                } else {
                    uiText.text(R.string.ui_the_saved_hans_mapping_is_still_blocked_the_midd_621298)
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
                    text = stringResource(R.string.ui_the_original_settings_page_cannot_be_opened_dire_f19948),
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
                    Text(stringResource(R.string.ui_replace_minimal_mapping_b76334))
                }
                if (state.replacementRequired) {
                    Button(
                        onClick = callbacks.onConfirmMp01VendorActionCleared,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("confirm_mp01_vendor_action_cleared"),
                    ) {
                        Text(stringResource(R.string.ui_all_six_are_set_to_nothing_ae5f40))
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
    val uiText = rememberHansTextResolver()
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
    val uiText = rememberHansTextResolver()
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
