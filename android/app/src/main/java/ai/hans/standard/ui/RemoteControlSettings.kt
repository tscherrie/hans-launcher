package ai.hans.standard.ui

import ai.hans.standard.remotecontrol.RemoteControlCapability
import ai.hans.standard.remotecontrol.RemoteControlIssue
import ai.hans.standard.remotecontrol.RemoteControlOperation
import ai.hans.standard.remotecontrol.RemoteControlSnapshot
import ai.hans.standard.remotecontrol.RemoteControlStatus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Render only authoritative state. No timer, saved state, implicit pairing, or optimistic ACK. */
@Composable
internal fun RemoteControlSettings(state: SettingsUiState, callbacks: SettingsUiCallbacks) {
    val remote = state.remoteControl
    val presentation = remoteControlPresentation(remote)
    var confirmEnable by remember { mutableStateOf(false) }
    var revokeClientId by remember { mutableStateOf<String?>(null) }
    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text("Zugriff vom Mac auf dieses Telefon freigeben?") },
            text = { Text(REMOTE_CONTROL_CONSENT) },
            confirmButton = {
                TextButton(
                    enabled = presentation.canEnable,
                    onClick = { confirmEnable = false; callbacks.onRemoteControlEnable() },
                    modifier = Modifier.testTag("remote_control_confirm_enable"),
                ) { Text("Jetzt freigeben") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnable = false }) { Text("Abbrechen") }
            },
        )
    }
    val selectedClient = remote.clients.firstOrNull { it.clientId == revokeClientId }
    if (selectedClient != null) {
        AlertDialog(
            onDismissRequest = { revokeClientId = null },
            title = { Text("Kopplung widerrufen?") },
            text = { Text("${remoteClientLabel(selectedClient.displayName)} soll keinen Fernzugriff mehr erhalten. " +
                "Die Kopplung gilt erst nach Bestätigung der Laufzeit als widerrufen.") },
            confirmButton = {
                TextButton(
                    enabled = presentation.canManageClients,
                    onClick = { revokeClientId = null; callbacks.onRemoteControlRevoke(selectedClient.clientId) },
                    modifier = Modifier.testTag("remote_control_confirm_revoke"),
                ) { Text("Kopplung widerrufen") }
            },
            dismissButton = { TextButton(onClick = { revokeClientId = null }) { Text("Abbrechen") } },
        )
    }
    Column(Modifier.fillMaxWidth().testTag("remote_control_settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mac → Telefon", style = MaterialTheme.typography.titleLarge)
        Text("ChatGPT Desktop kann dieses vorhandene Hans-Gespräch auf deinem Telefon weiterführen. " +
            "Das ist kein Auftrag von Hans an einen anderen Computer.")
        Text(presentation.consent, modifier = Modifier.testTag("remote_control_consent"))
        Text("Verbindung: ${presentation.status}", modifier = Modifier.testTag("remote_control_status"))
        presentation.notice?.let { Text(it, modifier = Modifier.testTag("remote_control_notice")) }
        remote.pendingOperation?.let {
            Text(remoteOperationLabel(it), modifier = Modifier.testTag("remote_control_pending"))
        }
        Button(
            enabled = presentation.canEnable,
            onClick = { confirmEnable = true },
            modifier = Modifier.fillMaxWidth().testTag("remote_control_enable"),
        ) { Text("Fernzugriff freigeben") }
        OutlinedButton(
            enabled = presentation.canDisable,
            onClick = callbacks.onRemoteControlDisable,
            modifier = Modifier.fillMaxWidth().testTag("remote_control_disable"),
        ) { Text("Fernzugriff ausschalten") }
        OutlinedButton(
            enabled = presentation.canRefresh,
            onClick = callbacks.onRemoteControlRefresh,
            modifier = Modifier.fillMaxWidth().testTag("remote_control_refresh"),
        ) { Text("Status aktualisieren") }

        HorizontalDivider()
        Text("Mac koppeln", style = MaterialTheme.typography.titleMedium)
        Text("Die Kopplung allein ersetzt nicht deine Freigabe auf diesem Telefon.")
        OutlinedButton(
            enabled = presentation.canPair,
            onClick = callbacks.onRemoteControlPair,
            modifier = Modifier.fillMaxWidth().testTag("remote_control_pair"),
        ) { Text(if (remote.pairing == null) "Kopplungscode erstellen" else "Neuen Kopplungscode erstellen") }
        // Enrollment material is rendered directly from its transient coordinator snapshot.
        // No remember/rememberSaveable, clipboard, formatting log, persistence, or countdown.
        remote.pairing?.takeIf { remote.runtimeReady && remote.localConsentGranted }?.let { pairing ->
            Text("Gib diesen temporären Code auf deinem Mac beim Koppeln in ChatGPT Desktop ein. " +
                "Teile ihn nur mit deinem eigenen Desktop.")
            Text(pairing.manualPairingCode ?: pairing.pairingCode,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("remote_control_pairing_code"))
            Text("Der Code wird bei Ablauf automatisch entfernt. Er wird nicht gespeichert.")
            OutlinedButton(
                enabled = presentation.canRefresh,
                onClick = callbacks.onRemoteControlCheckPairing,
                modifier = Modifier.fillMaxWidth().testTag("remote_control_check_pairing"),
            ) { Text("Kopplung prüfen") }
        }
        if (remote.pairingClaimed) Text("Kopplung wurde bestätigt.", modifier = Modifier.testTag("remote_control_pairing_claimed"))

        HorizontalDivider()
        Text("Vorhandenes Hans-Gespräch", style = MaterialTheme.typography.titleMedium)
        if (state.remoteControlThreadId != null) {
            Text("Öffne auf deinem Mac in ChatGPT Desktop dieses vorhandene Gespräch:")
            state.remoteControlThreadName?.takeIf(String::isNotBlank)?.let {
                Text(it, modifier = Modifier.testTag("remote_control_thread_name"))
            }
            Text(state.remoteControlThreadId, modifier = Modifier.testTag("remote_control_thread_id"))
        } else Text("Noch kein bestehendes Hans-Gespräch verfügbar.")
        Text("Neue Aufgaben, die du am Desktop anlegst, erhalten in dieser Version noch keine Telefonwerkzeuge.")

        HorizontalDivider()
        Text("Gekoppelte Geräte", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            enabled = presentation.canManageClients,
            onClick = callbacks.onRemoteControlRefreshClients,
            modifier = Modifier.fillMaxWidth().testTag("remote_control_refresh_clients"),
        ) { Text("Gekoppelte Geräte aktualisieren") }
        if (remote.clients.isEmpty()) {
            Text(if (remote.clientsComplete) "Keine gekoppelten Geräte bestätigt."
                else "Die Geräteliste ist noch nicht vollständig bestätigt.")
        }
        remote.clients.forEachIndexed { index, client ->
            key(client.clientId) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    // No hardware model, OS version, last-seen details, thumbnails, or raw IDs.
                    Text(remoteClientLabel(client.displayName))
                    OutlinedButton(
                        enabled = presentation.canManageClients,
                        onClick = { revokeClientId = client.clientId },
                        modifier = Modifier.testTag("remote_control_revoke_$index"),
                    ) { Text("Kopplung widerrufen") }
                }
            }
        }
        if (remote.nextClientCursor != null) {
            OutlinedButton(
                enabled = presentation.canManageClients,
                onClick = callbacks.onRemoteControlLoadMoreClients,
                modifier = Modifier.fillMaxWidth().testTag("remote_control_more_clients"),
            ) { Text("Weitere Geräte laden") }
        } else if (remote.clients.isNotEmpty() && !remote.clientsComplete) {
            Text("Die Geräteliste ist unvollständig; bitte erneut aktualisieren.")
        }
    }
}

internal const val REMOTE_CONTROL_CONSENT = "Ein gekoppelter Desktop kann auf private Hans-Daten und die " +
    "von dir freigegebenen Android-Werkzeuge zugreifen. Das gilt bis zum Neustart oder Beenden von Hans " +
    "oder bis du den Fernzugriff ausschaltest. Android-Berechtigungen und Bestätigungen bleiben erforderlich. " +
    "Der Zugriff verleiht weder Root-Rechte noch die Möglichkeit, das Telefon zu entsperren."

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

internal fun remoteControlPresentation(state: RemoteControlSnapshot): RemoteControlPresentation {
    val available = state.runtimeReady && state.capability == RemoteControlCapability.SUPPORTED
    val idle = state.pendingOperation == null
    return RemoteControlPresentation(
        consent = when {
            state.mayUsePhoneToolsRemotely -> "Telefonzugriff: freigegeben und verbunden"
            state.localConsentGranted -> "Telefonzugriff: lokal freigegeben, noch nicht verbunden bestätigt"
            else -> "Telefonzugriff: nicht freigegeben"
        },
        status = when (state.status) {
            RemoteControlStatus.UNKNOWN -> "Unbekannt – noch nicht bestätigt"
            RemoteControlStatus.DISABLED -> "Nicht verbunden (ausgeschaltet)"
            RemoteControlStatus.CONNECTING -> "Verbindung wird aufgebaut"
            RemoteControlStatus.CONNECTED -> "Verbunden"
            RemoteControlStatus.ERRORED -> "Fehler"
        },
        notice = state.issue?.let(::remoteIssueLabel) ?: when {
            !state.runtimeReady -> "Die Hans-Laufzeit ist gerade nicht bereit."
            state.capability == RemoteControlCapability.UNSUPPORTED -> "Diese Laufzeit unterstützt den Desktop-Fernzugriff nicht."
            state.capability == RemoteControlCapability.UNAVAILABLE -> "Desktop-Fernzugriff ist gerade nicht verfügbar."
            state.capability == RemoteControlCapability.UNKNOWN -> "Verfügbarkeit wurde noch nicht bestätigt."
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

private fun remoteClientLabel(name: String?): String = name?.filterNot(Char::isISOControl)
    ?.trim()?.take(100)?.takeIf(String::isNotEmpty) ?: "Gekoppelter Desktop"

private fun remoteOperationLabel(operation: RemoteControlOperation): String = when (operation) {
    RemoteControlOperation.STATUS -> "Verbindungsstatus wird geprüft …"
    RemoteControlOperation.ENABLE -> "Freigabe wird angefordert …"
    RemoteControlOperation.DISABLE -> "Fernzugriff wird ausgeschaltet …"
    RemoteControlOperation.PAIR -> "Kopplungscode wird angefordert …"
    RemoteControlOperation.PAIR_STATUS -> "Kopplung wird geprüft …"
    RemoteControlOperation.CLIENTS -> "Gekoppelte Geräte werden geladen …"
    RemoteControlOperation.REVOKE -> "Widerruf wird geprüft …"
}

private fun remoteIssueLabel(issue: RemoteControlIssue): String = when (issue) {
    RemoteControlIssue.UNSUPPORTED -> "Diese Laufzeit unterstützt den Desktop-Fernzugriff nicht."
    RemoteControlIssue.AUTH_REQUIRED -> "Die ChatGPT-Anmeldung muss geprüft werden."
    RemoteControlIssue.POLICY_BLOCKED -> "Desktop-Fernzugriff ist durch eine Richtlinie gesperrt."
    RemoteControlIssue.UNAVAILABLE, RemoteControlIssue.TRANSPORT_UNAVAILABLE -> "Die Verbindung zur Hans-Laufzeit ist gerade nicht verfügbar."
    RemoteControlIssue.RPC_FAILED, RemoteControlIssue.MALFORMED_RESPONSE, RemoteControlIssue.UNEXPECTED_STATUS ->
        "Die Laufzeit hat die Änderung nicht bestätigt. Bitte den Status erneut prüfen."
    RemoteControlIssue.TIMED_OUT -> "Die Bestätigung ist ausgeblieben. Der Status ist unklar; Ausschalten bleibt möglich."
    RemoteControlIssue.CONSENT_REQUIRED -> "Für Telefonzugriff ist deine ausdrückliche Freigabe hier erforderlich."
    RemoteControlIssue.PAIRING_EXPIRED -> "Der Kopplungscode ist abgelaufen. Bei Bedarf einen neuen erstellen."
}
