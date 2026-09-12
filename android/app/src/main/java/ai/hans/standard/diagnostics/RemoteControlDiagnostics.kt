package ai.hans.standard.diagnostics

import ai.hans.standard.phone.lifecycle.HansActiveWorkForegroundSnapshot
import ai.hans.standard.phone.lifecycle.HansActiveWorkReason
import ai.hans.standard.remotecontrol.RemoteControlSnapshot
import org.json.JSONObject

/** Passive, content-free DUMP evidence: never include pairing codes, client IDs or thread content. */
object RemoteControlDiagnostics {
    fun encode(remote: RemoteControlSnapshot?, foreground: HansActiveWorkForegroundSnapshot): String = JSONObject()
        .put("schema", "hans.remote-control-diagnostics.v1")
        .put("runtimeReady", remote?.runtimeReady == true)
        .put("capability", remote?.capability?.name ?: "UNKNOWN")
        .put("status", remote?.status?.name ?: "UNKNOWN")
        .put("statusConfirmed", remote?.statusConfirmedForCurrentRuntime == true)
        .put("localConsent", remote?.localConsentGranted == true)
        .put("phoneToolsAllowed", remote?.mayUsePhoneToolsRemotely == true)
        .put("pending", remote?.pendingOperation?.name ?: JSONObject.NULL)
        .put("issue", remote?.issue?.name ?: JSONObject.NULL)
        .put("pairingPresent", remote?.pairing != null)
        .put("pairingClaimed", remote?.pairingClaimed == true)
        .put("knownClients", remote?.clients?.size ?: 0)
        .put("foregroundProtected", HansActiveWorkReason.REMOTE_CONTROL in foreground.protectedReasons)
        .toString()
}
