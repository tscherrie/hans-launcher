package ai.hans.standard.remotecontrol

import java.io.Serializable

enum class RemoteControlStatus { UNKNOWN, DISABLED, CONNECTING, CONNECTED, ERRORED }
enum class RemoteControlCapability { UNKNOWN, SUPPORTED, UNSUPPORTED, UNAVAILABLE }
enum class RemoteControlOperation { STATUS, ENABLE, DISABLE, PAIR, PAIR_STATUS, CLIENTS, REVOKE }
enum class RemoteControlIssue {
    UNSUPPORTED, AUTH_REQUIRED, POLICY_BLOCKED, UNAVAILABLE, RPC_FAILED, MALFORMED_RESPONSE,
    TRANSPORT_UNAVAILABLE, TIMED_OUT, CONSENT_REQUIRED, UNEXPECTED_STATUS, PAIRING_EXPIRED,
}

data class RemoteControlConnection(
    val status: RemoteControlStatus,
    val installationId: String,
    val serverName: String,
    val environmentId: String?,
) : Serializable

/** Bearer enrollment material: memory-only UI state. Never persist, log, or stringify the code. */
class RemoteControlPairing internal constructor(
    val pairingCode: String,
    val manualPairingCode: String?,
    val environmentId: String,
    val expiresAtEpochSeconds: Long,
) {
    val expiresAtMillis: Long get() = expiresAtEpochSeconds * 1_000
    override fun toString(): String = "RemoteControlPairing(<redacted>)"
}

data class RemoteControlClient(
    val clientId: String,
    val displayName: String?,
    val deviceType: String?,
    val deviceModel: String?,
    val platform: String?,
    val osVersion: String?,
    val appVersion: String?,
    val lastSeenAt: Long?,
) : Serializable

/** The pairing secret is deliberately excluded from Java serialization and diagnostic output. */
data class RemoteControlSnapshot(
    val generation: Long? = null,
    val runtimeReady: Boolean = false,
    val capability: RemoteControlCapability = RemoteControlCapability.UNKNOWN,
    val connection: RemoteControlConnection? = null,
    val statusConfirmedForCurrentRuntime: Boolean = false,
    val localConsentGranted: Boolean = false,
    val pendingOperation: RemoteControlOperation? = null,
    @Transient val pairing: RemoteControlPairing? = null,
    val pairingClaimed: Boolean = false,
    val clients: List<RemoteControlClient> = emptyList(),
    val nextClientCursor: String? = null,
    val clientsComplete: Boolean = false,
    val issue: RemoteControlIssue? = null,
    val nextDeadlineAtMillis: Long? = null,
) : Serializable {
    val status: RemoteControlStatus get() =
        connection?.status.takeIf { statusConfirmedForCurrentRuntime } ?: RemoteControlStatus.UNKNOWN
    val isEnabledConfirmed: Boolean get() = status == RemoteControlStatus.CONNECTED || status == RemoteControlStatus.CONNECTING
    val isDisabledConfirmed: Boolean get() = status == RemoteControlStatus.DISABLED
    val mayUsePhoneToolsRemotely: Boolean get() = runtimeReady && localConsentGranted &&
        capability == RemoteControlCapability.SUPPORTED &&
        statusConfirmedForCurrentRuntime && status == RemoteControlStatus.CONNECTED &&
        pendingOperation != RemoteControlOperation.DISABLE
}

/** Complete wire frame is confidential (pairing/status contains a bearer code). */
class RemoteControlRequest internal constructor(
    val id: String,
    val method: String,
    val wireJson: String,
    val timeoutMillis: Long = 15_000,
) {
    override fun toString(): String = "RemoteControlRequest(method=$method, body=<redacted>)"
}
