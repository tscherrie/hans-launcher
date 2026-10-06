package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.localization.rememberHansTextResolver
import androidx.compose.ui.res.stringResource

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import ai.hans.standard.remotecontrol.RemoteControlCapability
import ai.hans.standard.remotecontrol.RemoteControlIssue
import ai.hans.standard.remotecontrol.RemoteControlOperation
import ai.hans.standard.remotecontrol.RemoteControlSnapshot
import ai.hans.standard.remotecontrol.RemoteControlStatus
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Render only authoritative state. No timer, saved state, implicit pairing, or optimistic ACK. */
@Composable
internal fun RemoteControlSettings(state: SettingsUiState, callbacks: SettingsUiCallbacks) {
    val uiText = rememberHansTextResolver()
    val remote = state.remoteControl
    val presentation = remoteControlPresentation(remote, uiText)
    var revokeClientId by remember { mutableStateOf<String?>(null) }
    val selectedClient = remote.clients.firstOrNull { it.clientId == revokeClientId }
    if (selectedClient != null) {
        AlertDialog(
            onDismissRequest = { revokeClientId = null },
            title = { Text(stringResource(R.string.ui_revoke_pairing_79d8a6)) },
            text = { Text(stringResource(R.string.ui_value_should_no_longer_have_remote_access_77a40f, remoteClientLabel(selectedClient.displayName, uiText)) +
                uiText.text(R.string.ui_pairing_is_considered_revoked_only_after_confirm_dd242b)) },
            confirmButton = {
                TextButton(
                    enabled = presentation.canManageClients,
                    onClick = { revokeClientId = null; callbacks.onRemoteControlRevoke(selectedClient.clientId) },
                    modifier = Modifier.testTag("remote_control_confirm_revoke"),
                ) { Text(stringResource(R.string.ui_revoke_pairing_e01e4b)) }
            },
            dismissButton = { TextButton(onClick = { revokeClientId = null }) { Text(stringResource(R.string.ui_cancel_f7ff11)) } },
        )
    }
    Column(Modifier.fillMaxWidth().testTag("remote_control_settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.remote_control_direction), style = MaterialTheme.typography.titleLarge)
        RemoteControlAccessControls(remote, callbacks)

        HorizontalDivider()
        Text(stringResource(R.string.remote_control_pair_desktop), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ui_pairing_alone_does_not_replace_your_approval_on__fb8000))
        OutlinedButton(
            enabled = presentation.canPair,
            onClick = callbacks.onRemoteControlPair,
            modifier = Modifier.fillMaxWidth().testTag("remote_control_pair"),
        ) { Text(if (remote.pairing == null) uiText.text(R.string.ui_create_pairing_code_a67848) else uiText.text(R.string.ui_create_new_pairing_code_27d909)) }
        // Enrollment material is rendered directly from its transient coordinator snapshot.
        // Only an explicit tap copies it. No saved state, formatting log, persistence, or countdown.
        remote.pairing?.takeIf { remote.runtimeReady && remote.localConsentGranted }?.let { pairing ->
            Text(stringResource(R.string.remote_control_pairing_help))
            RemoteControlCopyableValue(
                value = pairing.manualPairingCode ?: pairing.pairingCode,
                copyLabel = stringResource(R.string.remote_control_copy_pairing_code),
                sensitive = true,
                testTag = "remote_control_pairing_code",
            )
            Text(stringResource(R.string.remote_control_pairing_ephemeral))
            OutlinedButton(
                enabled = presentation.canRefresh,
                onClick = callbacks.onRemoteControlCheckPairing,
                modifier = Modifier.fillMaxWidth().testTag("remote_control_check_pairing"),
            ) { Text(stringResource(R.string.ui_check_pairing_344bae)) }
        }
        if (remote.pairingClaimed) Text(stringResource(R.string.ui_pairing_confirmed_a33414), modifier = Modifier.testTag("remote_control_pairing_claimed"))

        HorizontalDivider()
        Text(stringResource(R.string.ui_existing_hans_conversation_57d835), style = MaterialTheme.typography.titleMedium)
        if (state.remoteControlThreadId != null) {
            Text(stringResource(R.string.remote_control_existing_conversation_help))
            state.remoteControlThreadName?.takeIf(String::isNotBlank)?.let {
                Text(it, modifier = Modifier.testTag("remote_control_thread_name"))
            }
            RemoteControlCopyableValue(
                value = state.remoteControlThreadId,
                copyLabel = stringResource(R.string.remote_control_copy_thread_id),
                sensitive = false,
                testTag = "remote_control_thread_id",
            )
        } else Text(stringResource(R.string.ui_no_existing_hans_conversation_is_available_yet_9942c3))
        Text(stringResource(if (state.remotePhoneToolsAvailable) R.string.remote_control_new_task_tools
            else R.string.remote_control_tools_unavailable), modifier = Modifier.testTag("remote_control_new_task_tools"))

        state.remoteControlProjectPath?.let { projectPath ->
            HorizontalDivider()
            Text(stringResource(R.string.remote_control_project_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.remote_control_project_help))
            RemoteControlCopyableValue(
                value = projectPath,
                copyLabel = stringResource(R.string.remote_control_copy_project_path),
                sensitive = false,
                testTag = "remote_control_project_path",
            )
            Text(stringResource(R.string.remote_control_project_permissions))
        }

        HorizontalDivider()
        Text(stringResource(R.string.ui_paired_devices_2a7a3b), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            enabled = presentation.canManageClients,
            onClick = callbacks.onRemoteControlRefreshClients,
            modifier = Modifier.fillMaxWidth().testTag("remote_control_refresh_clients"),
        ) { Text(stringResource(R.string.ui_refresh_paired_devices_fe7334)) }
        if (remote.clients.isEmpty()) {
            Text(if (remote.clientsComplete) uiText.text(R.string.ui_no_paired_devices_confirmed_eb710b)
                else uiText.text(R.string.ui_the_device_list_has_not_been_fully_confirmed_yet_f30cc5))
        }
        remote.clients.forEachIndexed { index, client ->
            key(client.clientId) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    // No hardware model, OS version, last-seen details, thumbnails, or raw IDs.
                    Text(remoteClientLabel(client.displayName, uiText))
                    OutlinedButton(
                        enabled = presentation.canManageClients,
                        onClick = { revokeClientId = client.clientId },
                        modifier = Modifier.testTag("remote_control_revoke_$index"),
                    ) { Text(stringResource(R.string.ui_revoke_pairing_e01e4b)) }
                }
            }
        }
        if (remote.nextClientCursor != null) {
            OutlinedButton(
                enabled = presentation.canManageClients,
                onClick = callbacks.onRemoteControlLoadMoreClients,
                modifier = Modifier.fillMaxWidth().testTag("remote_control_more_clients"),
            ) { Text(stringResource(R.string.ui_load_more_devices_234e22)) }
        } else if (remote.clients.isNotEmpty() && !remote.clientsComplete) {
            Text(stringResource(R.string.ui_the_device_list_is_incomplete_refresh_it_again_4c8c2b))
        }
    }
}

/** Both entry points operate on the same consent and runtime state; neither grants Android access. */
@Composable
internal fun RemoteControlAccessControls(
    remote: RemoteControlSnapshot,
    callbacks: SettingsUiCallbacks,
    tagPrefix: String = "remote_control",
) {
    val uiText = rememberHansTextResolver()
    val presentation = remoteControlPresentation(remote, uiText)
    var confirmEnable by remember { mutableStateOf(false) }
    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text(stringResource(R.string.remote_control_allow_title)) },
            text = { Text(remoteControlConsent(uiText)) },
            confirmButton = {
                TextButton(
                    enabled = presentation.canEnable,
                    onClick = { confirmEnable = false; callbacks.onRemoteControlEnable() },
                    modifier = Modifier.testTag("${tagPrefix}_confirm_enable"),
                ) { Text(stringResource(R.string.ui_allow_access_now_48d3a5)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnable = false }) { Text(stringResource(R.string.ui_cancel_f7ff11)) }
            },
        )
    }
    Column(Modifier.fillMaxWidth().testTag("${tagPrefix}_access"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.remote_control_readiness_help))
        Text(presentation.consent, modifier = Modifier.testTag("${tagPrefix}_consent"))
        Text(stringResource(R.string.remote_control_service_status, presentation.status),
            modifier = Modifier.testTag("${tagPrefix}_status"))
        presentation.notice?.let { Text(it, modifier = Modifier.testTag("${tagPrefix}_notice")) }
        remote.pendingOperation?.let {
            Text(remoteOperationLabel(it, uiText), modifier = Modifier.testTag("${tagPrefix}_pending"))
        }
        Button(
            enabled = presentation.canEnable,
            onClick = { confirmEnable = true },
            modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_enable"),
        ) { Text(stringResource(R.string.ui_allow_remote_access_6aed75)) }
        OutlinedButton(
            enabled = presentation.canDisable,
            onClick = callbacks.onRemoteControlDisable,
            modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_disable"),
        ) { Text(stringResource(R.string.ui_turn_off_remote_access_15f7ed)) }
        OutlinedButton(
            enabled = presentation.canRefresh,
            onClick = callbacks.onRemoteControlRefresh,
            modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_refresh"),
        ) { Text(stringResource(R.string.ui_refresh_status_c761bf)) }
    }
}

@Composable
private fun RemoteControlCopyableValue(value: String, copyLabel: String, sensitive: Boolean, testTag: String) {
    val context = LocalContext.current
    Text(
        value,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
            .clickable(role = Role.Button, onClickLabel = copyLabel) {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(remoteControlClipboardEntry(value, copyLabel, sensitive))
                // Android 13+ provides its own confirmation, with sensitive previews suppressed.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, R.string.remote_control_copied, Toast.LENGTH_SHORT).show()
                }
            }
            .padding(vertical = 8.dp)
            .testTag(testTag),
    )
}

internal fun remoteControlClipboardEntry(value: String, label: String, sensitive: Boolean): ClipData =
    ClipData.newPlainText(label, value).apply {
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
    }

internal fun remoteControlConsent(uiText: HansTextResolver): String = uiText.text(R.string.ui_a_paired_desktop_can_access_private_hans_data_an_16b285) +
    uiText.text(R.string.ui_android_tools_you_have_authorized_access_lasts_u_0f1b39) +
    uiText.text(R.string.ui_or_until_you_turn_remote_access_off_android_perm_4ca3f4) +
    uiText.text(R.string.ui_this_access_does_not_grant_root_privileges_or_th_cf0dc5)

internal data class RemoteControlPresentation(
    val consent: String,
    val status: String,
    val notice: String?,
    val canEnable: Boolean,
    val canDisable: Boolean,
    val canPair: Boolean,
    val canRefresh: Boolean,
    val canManageClients: Boolean,
)

internal fun remoteControlPresentation(state: RemoteControlSnapshot, uiText: HansTextResolver): RemoteControlPresentation {
    val available = state.runtimeReady && state.capability == RemoteControlCapability.SUPPORTED
    val idle = state.pendingOperation == null
    return RemoteControlPresentation(
        consent = uiText.text(if (state.localConsentGranted) R.string.remote_control_permission_allowed
            else R.string.remote_control_permission_not_allowed),
        status = when (state.status) {
            RemoteControlStatus.UNKNOWN -> uiText.text(R.string.remote_control_status_unknown)
            RemoteControlStatus.DISABLED -> uiText.text(R.string.remote_control_status_off)
            RemoteControlStatus.CONNECTING -> uiText.text(R.string.remote_control_status_starting)
            RemoteControlStatus.CONNECTED -> uiText.text(R.string.remote_control_status_ready)
            RemoteControlStatus.ERRORED -> uiText.text(R.string.remote_control_status_error)
        },
        notice = state.issue?.let { remoteIssueLabel(it, uiText) } ?: when {
            !state.runtimeReady -> uiText.text(R.string.ui_the_hans_runtime_is_not_ready_at_the_moment_54bdfa)
            state.capability == RemoteControlCapability.UNSUPPORTED -> uiText.text(R.string.ui_this_runtime_does_not_support_desktop_remote_acc_dbcfd0)
            state.capability == RemoteControlCapability.UNAVAILABLE -> uiText.text(R.string.ui_desktop_remote_access_is_currently_unavailable_f720c4)
            state.capability == RemoteControlCapability.UNKNOWN -> uiText.text(R.string.ui_availability_has_not_been_confirmed_yet_8ac951)
            else -> null
        },
        canEnable = available && idle && (!state.localConsentGranted || !state.isEnabledConfirmed),
        // Stale/unknown RPC state must never trap local consent behind a disabled control.
        canDisable = state.pendingOperation != RemoteControlOperation.DISABLE &&
            (state.localConsentGranted || state.runtimeReady || state.connection != null || state.pairing != null),
        canPair = available && idle && state.localConsentGranted && state.isEnabledConfirmed,
        canRefresh = state.runtimeReady && idle,
        canManageClients = available && idle && state.connection?.environmentId != null,
    )
}

private fun remoteClientLabel(name: String?, uiText: HansTextResolver): String = name?.filterNot(Char::isISOControl)
    ?.trim()?.take(100)?.takeIf(String::isNotEmpty) ?: uiText.text(R.string.ui_paired_desktop_c249e8)

private fun remoteOperationLabel(operation: RemoteControlOperation, uiText: HansTextResolver): String = when (operation) {
    RemoteControlOperation.STATUS -> uiText.text(R.string.remote_control_checking_readiness)
    RemoteControlOperation.ENABLE -> uiText.text(R.string.ui_requesting_access_approval_a540a7)
    RemoteControlOperation.DISABLE -> uiText.text(R.string.ui_turning_off_remote_access_8a7757)
    RemoteControlOperation.PAIR -> uiText.text(R.string.ui_requesting_pairing_code_faee83)
    RemoteControlOperation.PAIR_STATUS -> uiText.text(R.string.ui_checking_pairing_996b2f)
    RemoteControlOperation.CLIENTS -> uiText.text(R.string.ui_loading_paired_devices_e5c3c4)
    RemoteControlOperation.REVOKE -> uiText.text(R.string.ui_checking_revocation_84517f)
}

private fun remoteIssueLabel(issue: RemoteControlIssue, uiText: HansTextResolver): String = when (issue) {
    RemoteControlIssue.UNSUPPORTED -> uiText.text(R.string.ui_this_runtime_does_not_support_desktop_remote_acc_dbcfd0)
    RemoteControlIssue.AUTH_REQUIRED -> uiText.text(R.string.ui_chatgpt_sign_in_needs_to_be_checked_8ffd12)
    RemoteControlIssue.POLICY_BLOCKED -> uiText.text(R.string.ui_desktop_remote_access_is_blocked_by_policy_c4b295)
    RemoteControlIssue.UNAVAILABLE, RemoteControlIssue.TRANSPORT_UNAVAILABLE -> uiText.text(R.string.ui_the_connection_to_the_hans_runtime_is_currently__993a96)
    RemoteControlIssue.RPC_FAILED, RemoteControlIssue.MALFORMED_RESPONSE, RemoteControlIssue.UNEXPECTED_STATUS ->
        uiText.text(R.string.ui_the_runtime_did_not_confirm_the_change_check_the_6c8d1a)
    RemoteControlIssue.TIMED_OUT -> uiText.text(R.string.ui_no_confirmation_was_received_the_status_is_uncer_455cfb)
    RemoteControlIssue.CONSENT_REQUIRED -> uiText.text(R.string.ui_phone_access_requires_your_explicit_approval_her_a19feb)
    RemoteControlIssue.PAIRING_EXPIRED -> uiText.text(R.string.ui_the_pairing_code_has_expired_create_a_new_one_if_56573e)
}
