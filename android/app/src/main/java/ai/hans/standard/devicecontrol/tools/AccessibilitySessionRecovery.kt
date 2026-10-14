package ai.hans.standard.devicecontrol.tools

import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.diagnostics.ToolFailureDiagnostic
import org.json.JSONObject

/** Fresh local probes, not a cached preference or a reason to replay any UI action. */
internal object AccessibilitySessionRecovery {
    fun unavailable(
        specialAccess: AccessibilitySpecialAccessProbe,
        serviceConnection: AccessibilityServiceConnectionProbe,
    ): DynamicToolExecutionResult {
        val connected = runCatching { serviceConnection.isConnected() }.getOrNull()
        if (connected == true) return DeviceControlProjection.serviceReconnecting()
        val enabled = runCatching { specialAccess.isEnabled() }.getOrNull()
        if (enabled == true) return DeviceControlProjection.serviceReconnecting()
        if (connected == false && enabled == false) return DeviceControlProjection.permissionRequired()
        // A failed probe proves neither a grant nor a denial. Never send the user through
        // setup again solely because the platform's temporary state could not be read.
        val code = "accessibility_state_unavailable"
        return DynamicToolExecutionResult(
            JSONObject().put("status", "failed").put("capability", "android_accessibility")
                .put("availability", "state_unavailable").put("retryable", true)
                .put("errorCode", code).toString(),
            success = false,
            failureDiagnostic = ToolFailureDiagnostic.fromCodes(code),
        )
    }
}
