package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.localization.rememberHansTextResolver
import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Persistent automation management. Every value is a confirmed storage projection and every
 * change returns through a fresh projection; this composable never mutates switches optimistically.
 */
@Composable
fun AutomationsScreen(
    state: AutomationsUiState,
    callbacks: AutomationsUiCallbacks,
    modifier: Modifier = Modifier,
) {
    val uiText = rememberHansTextResolver()
    BackHandler(onBack = callbacks.onBack)
    var pendingDelete by remember { mutableStateOf<AutomationUiModel?>(null) }
    var pendingHistory by remember { mutableStateOf<AutomationUiModel?>(null) }
    val operationInProgress = state.operationAutomationId != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("automations_screen"),
    ) {
        ScreenHeader(title = uiText.text(R.string.ui_automations_1a2219), onBack = callbacks.onBack)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = callbacks.onCreateInChat,
                enabled = !state.loading && !operationInProgress,
                modifier = Modifier.weight(1f).testTag("create_automation_in_chat"),
            ) {
                Text(stringResource(R.string.ui_new_automation_40e06b))
            }
            OutlinedButton(
                onClick = callbacks.onRefresh,
                enabled = !state.loading && !operationInProgress,
                modifier = Modifier.weight(1f).testTag("refresh_automations"),
            ) {
                Text(if (state.loading) uiText.text(R.string.ui_checking_490232) else uiText.text(R.string.ui_check_again_adbde2))
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("automation_inventory"),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.notice.isNotBlank()) {
                item(key = "notice") {
                    AutomationNotice(state.notice, error = false, testTag = "automation_notice")
                }
            }
            if (state.errorMessage.isNotBlank()) {
                item(key = "error") {
                    AutomationNotice(state.errorMessage, error = true, testTag = "automation_error")
                }
            }
            if (state.items.isEmpty() && !state.loading && state.errorMessage.isBlank()) {
                item(key = "empty") {
                    AutomationNotice(
                        text = uiText.text(R.string.ui_no_automations_configured_yet_tell_hans_in_chat__719efe),
                        error = false,
                        testTag = "automations_empty",
                    )
                }
            }
            items(state.items, key = AutomationUiModel::id) { item ->
                AutomationCard(
                    item = item,
                    operationInProgress = operationInProgress,
                    callbacks = callbacks,
                    onHistoryRequested = { pendingHistory = item },
                    onDeleteRequested = { pendingDelete = item },
                )
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.ui_delete_automation_fa45b9)) },
            text = {
                Text(
                    stringResource(R.string.ui_value_will_be_disabled_and_removed_records_of_co_df6feb, item.id),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        callbacks.onDelete(item.id, item.revision)
                    },
                    enabled = !operationInProgress,
                    modifier = Modifier.testTag("confirm_delete_automation"),
                ) { Text(stringResource(R.string.ui_delete_6c2d35)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.ui_cancel_f7ff11)) }
            },
        )
    }

    pendingHistory?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingHistory = null },
            title = { Text(stringResource(R.string.ui_run_history_8e3c44)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = item.id,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (item.history.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ui_no_saved_runs_for_this_automation_yet_d53dcd),
                            modifier = Modifier.testTag("automation_history_empty"),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .testTag("automation_history_list"),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(item.history) { entry ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                ) {
                                    Text(
                                        text = entry.headline,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        text = entry.scheduledLabel,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    entry.failureLabel?.let { failure ->
                                        Text(
                                            text = failure,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            if (item.historyTruncated) {
                                item {
                                    Text(
                                        text = stringResource(R.string.ui_showing_the_20_most_recent_runs_cc9543),
                                        modifier = Modifier.testTag("automation_history_truncated"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { pendingHistory = null },
                    modifier = Modifier.testTag("close_automation_history"),
                ) { Text(stringResource(R.string.ui_close_b808f6)) }
            },
        )
    }
}

@Composable
private fun AutomationCard(
    item: AutomationUiModel,
    operationInProgress: Boolean,
    callbacks: AutomationsUiCallbacks,
    onHistoryRequested: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    val uiText = rememberHansTextResolver()
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("automation_${item.id}"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Text(
                text = item.id,
                modifier = Modifier.semantics { heading() },
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
            )
            if (item.instructionPreview.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.instructionPreview,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(8.dp))
            AutomationDetailLine(item.scheduleLabel)
            AutomationDetailLine(stringResource(item.missedRunMode.labelResource))
            AutomationDetailLine(item.timingLabel)
            AutomationDetailLine(item.nextRunLabel)
            AutomationDetailLine(uiText.text(R.string.ui_last_run_value_9bbc36, item.lastRunLabel))
            item.lastFailureLabel?.let { AutomationDetailLine(uiText.text(R.string.ui_latest_notice_value_caf68b, it)) }
            if (item.pendingCount > 0) {
                AutomationDetailLine(uiText.quantity(R.plurals.ui_pending_operations, item.pendingCount, item.pendingCount))
            }
            if (item.requiresUnlockedDevice) {
                AutomationDetailLine(uiText.text(R.string.ui_the_phone_must_be_unlocked_to_run_this_1aaa1d))
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 9.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            AutomationSwitchRow(
                label = uiText.text(R.string.ui_active_816345),
                supporting = if (item.enabled) uiText.text(R.string.ui_schedule_enabled_b5f696) else uiText.text(R.string.ui_schedule_paused_0df1d8),
                checked = item.enabled,
                enabled = !operationInProgress,
                testTag = "automation_enabled_${item.id}",
                onCheckedChange = {
                    callbacks.onEnabledChanged(item.id, item.revision, it)
                },
            )
            AutomationSwitchRow(
                label = uiText.text(R.string.ui_run_unattended_001022),
                supporting = if (item.unattended) {
                    uiText.text(R.string.ui_runs_without_confirmation_for_each_scheduled_occ_23cc31)
                } else {
                    uiText.text(R.string.ui_explicit_confirmation_is_required_before_each_sc_e0ad88)
                },
                checked = item.unattended,
                enabled = !operationInProgress,
                testTag = "automation_unattended_${item.id}",
                onCheckedChange = {
                    callbacks.onUnattendedChanged(item.id, item.revision, it)
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { callbacks.onEditInChat(item.id, item.revision) },
                    enabled = !operationInProgress,
                    modifier = Modifier.weight(1f).testTag("edit_automation_${item.id}"),
                ) { Text(stringResource(R.string.ui_edit_84e45e)) }
                OutlinedButton(
                    onClick = onHistoryRequested,
                    enabled = !operationInProgress,
                    modifier = Modifier.weight(1f).testTag("history_automation_${item.id}"),
                ) { Text(stringResource(R.string.ui_history_5fd703)) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { callbacks.onRunNow(item.id, item.revision) },
                    enabled = item.enabled && !operationInProgress,
                    modifier = Modifier.weight(1f).testTag("run_automation_${item.id}"),
                ) { Text(stringResource(R.string.ui_run_now_119b40)) }
                TextButton(
                    onClick = onDeleteRequested,
                    enabled = !operationInProgress,
                    modifier = Modifier.testTag("delete_automation_${item.id}"),
                ) { Text(stringResource(R.string.ui_delete_6c2d35)) }
            }
        }
    }
}

@Composable
private fun AutomationSwitchRow(
    label: String,
    supporting: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val uiText = rememberHansTextResolver()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(
                text = supporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun AutomationDetailLine(text: String) {
    val uiText = rememberHansTextResolver()
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun AutomationNotice(text: String, error: Boolean, testTag: String) {
    val uiText = rememberHansTextResolver()
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp).testTag(testTag),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
}
