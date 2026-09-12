package ai.hans.standard.phone.display

/** Identity fields that Android exposes without privileged access. */
data class AndroidDeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
)

/** Evidence collected afresh before every MP01 display operation. */
data class Mp01DisplayTrustEvidence(
    val device: AndroidDeviceIdentity,
    val trustedMinimalSystemPackage: Boolean,
)

enum class Mp01DisplayAvailability(val code: String) {
    AVAILABLE("available"),
    NOT_MP01("not_mp01"),
    VENDOR_UNTRUSTED("vendor_untrusted"),
    SOCKET_UNREACHABLE("socket_unreachable"),
}

data class Mp01DisplayProbeResult(
    val availability: Mp01DisplayAvailability,
) {
    val available: Boolean
        get() = availability == Mp01DisplayAvailability.AVAILABLE
}

enum class Mp01EinkProfile(
    val apiName: String,
    internal val wireCommand: Byte,
) {
    BALANCED("balanced", 'b'.code.toByte()),
    SMOOTH("smooth", 's'.code.toByte()),
    SPEED("speed", 'p'.code.toByte()),
    ;

    companion object {
        fun fromApiName(value: String): Mp01EinkProfile? = entries.firstOrNull {
            it.apiName == value
        }
    }
}

enum class Mp01DisplayCommandStatus(val code: String) {
    SENT_UNVERIFIED("sent_unverified"),
    UNAVAILABLE("unavailable"),
    FAILED("failed"),
}

data class Mp01DisplayCommandResult(
    val status: Mp01DisplayCommandStatus,
    val availability: Mp01DisplayAvailability,
    val action: String,
)

fun interface Mp01DisplayTrustEvidenceSource {
    fun current(): Mp01DisplayTrustEvidence
}

/** A narrow transport boundary; implementations must never fall back to sysfs or Root. */
interface Mp01EinkSocketTransport {
    fun canConnect(): Boolean

    fun send(command: Byte)
}

object Mp01DisplayTrustPolicy {
    /** Hardware identification only; this does not grant access to vendor display controls. */
    fun isMp01(identity: AndroidDeviceIdentity): Boolean =
        identity.manufacturer.equals("ALONG", ignoreCase = true) &&
            identity.brand.equals("Minimal_Phone", ignoreCase = true) &&
            identity.model.equals("MP01", ignoreCase = true) &&
            identity.device.equals("MP01", ignoreCase = true)

    fun evaluate(evidence: Mp01DisplayTrustEvidence): Mp01DisplayAvailability {
        if (!isMp01(evidence.device)) return Mp01DisplayAvailability.NOT_MP01
        if (!evidence.trustedMinimalSystemPackage) {
            return Mp01DisplayAvailability.VENDOR_UNTRUSTED
        }
        return Mp01DisplayAvailability.AVAILABLE
    }
}

/**
 * Root-free controller for the documented vendor socket contract.
 *
 * The socket has no readback/acknowledgement contract. A successful write is therefore always
 * reported as [Mp01DisplayCommandStatus.SENT_UNVERIFIED], never as an applied profile.
 */
class Mp01DisplayController(
    private val evidenceSource: Mp01DisplayTrustEvidenceSource,
    private val transport: Mp01EinkSocketTransport,
) {
    fun probe(): Mp01DisplayProbeResult {
        val trust = runCatching { Mp01DisplayTrustPolicy.evaluate(evidenceSource.current()) }
            .getOrDefault(Mp01DisplayAvailability.VENDOR_UNTRUSTED)
        if (trust != Mp01DisplayAvailability.AVAILABLE) {
            return Mp01DisplayProbeResult(trust)
        }
        val socketReachable = runCatching { transport.canConnect() }.getOrDefault(false)
        return Mp01DisplayProbeResult(
            if (socketReachable) {
                Mp01DisplayAvailability.AVAILABLE
            } else {
                Mp01DisplayAvailability.SOCKET_UNREACHABLE
            },
        )
    }

    fun setProfile(profile: Mp01EinkProfile): Mp01DisplayCommandResult = execute(
        action = "set_profile:${profile.apiName}",
        command = profile.wireCommand,
    )

    fun fullRefresh(): Mp01DisplayCommandResult = execute(
        action = "full_refresh",
        command = FULL_REFRESH_COMMAND,
    )

    private fun execute(action: String, command: Byte): Mp01DisplayCommandResult {
        // This is deliberately a new probe for every mutation. A cached process-start result must
        // not grant access after the device, package, permission, or service state has changed.
        val freshProbe = probe()
        if (!freshProbe.available) {
            return Mp01DisplayCommandResult(
                status = Mp01DisplayCommandStatus.UNAVAILABLE,
                availability = freshProbe.availability,
                action = action,
            )
        }
        return try {
            transport.send(command)
            Mp01DisplayCommandResult(
                status = Mp01DisplayCommandStatus.SENT_UNVERIFIED,
                availability = Mp01DisplayAvailability.AVAILABLE,
                action = action,
            )
        } catch (_: Exception) {
            Mp01DisplayCommandResult(
                status = Mp01DisplayCommandStatus.FAILED,
                availability = Mp01DisplayAvailability.SOCKET_UNREACHABLE,
                action = action,
            )
        }
    }

    private companion object {
        val FULL_REFRESH_COMMAND: Byte = 'r'.code.toByte()
    }
}
