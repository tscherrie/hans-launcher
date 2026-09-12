package ai.hans.standard.phone.accessibility.resume

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.JsonContract
import ai.hans.standard.codex.MAX_DYNAMIC_TOOL_ARGUMENT_BYTES
import ai.hans.standard.phone.accessibility.UiInteractionAvailability
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Content-free identity for one interrupted App Server dynamic-tool call.
 *
 * Raw arguments are deliberately never persisted. The digest lets the journal reject a later
 * call which reuses the same opaque identity with different arguments without exposing message,
 * contact, credential, or screen content in the recovery file.
 */
internal data class UiTaskContinuationIdentity(
    val threadId: String,
    val turnId: String,
    val callId: String,
    val tool: String,
    val argumentFingerprint: String,
) {
    init {
        UiTaskContinuationBounds.requireOpaqueId(threadId, "thread")
        UiTaskContinuationBounds.requireOpaqueId(turnId, "turn")
        UiTaskContinuationBounds.requireOpaqueId(callId, "call")
        UiTaskContinuationBounds.requireTool(tool)
        UiTaskContinuationBounds.requireDigest(argumentFingerprint)
    }

    val key: UiTaskContinuationKey
        get() = UiTaskContinuationKey(threadId, turnId, callId)

    val continuationId: String
        get() = sha256Hex(
            buildString {
                append("hans-ui-continuation-v1\u0000")
                append(threadId)
                append('\u0000')
                append(turnId)
                append('\u0000')
                append(callId)
            },
        )

    companion object {
        fun from(call: DynamicToolCallParams): UiTaskContinuationIdentity? {
            if (call.namespace != UiTaskContinuationBounds.ACCESSIBILITY_NAMESPACE) return null
            val canonicalArguments = runCatching {
                JsonContract.parseObject(call.argumentsJson, MAX_DYNAMIC_TOOL_ARGUMENT_BYTES)
                    .toString()
            }.getOrNull() ?: return null
            return runCatching {
                UiTaskContinuationIdentity(
                    threadId = call.threadId,
                    turnId = call.turnId,
                    callId = call.callId,
                    tool = call.tool,
                    argumentFingerprint = sha256Hex(canonicalArguments),
                )
            }.getOrNull()
        }
    }
}

internal data class UiTaskContinuationKey(
    val threadId: String,
    val turnId: String,
    val callId: String,
) {
    init {
        UiTaskContinuationBounds.requireOpaqueId(threadId, "thread")
        UiTaskContinuationBounds.requireOpaqueId(turnId, "turn")
        UiTaskContinuationBounds.requireOpaqueId(callId, "call")
    }
}

internal enum class UiTaskContinuationBlockedReason {
    DEVICE_LOCKED,
    SCREEN_NOT_INTERACTIVE,
    DEVICE_LOCKED_AND_SCREEN_NOT_INTERACTIVE,
    ;

    companion object {
        fun from(availability: UiInteractionAvailability): UiTaskContinuationBlockedReason? =
            when (availability) {
                UiInteractionAvailability.DEVICE_LOCKED -> DEVICE_LOCKED
                UiInteractionAvailability.SCREEN_NOT_INTERACTIVE -> SCREEN_NOT_INTERACTIVE
                UiInteractionAvailability.DEVICE_LOCKED_AND_SCREEN_NOT_INTERACTIVE ->
                    DEVICE_LOCKED_AND_SCREEN_NOT_INTERACTIVE
                UiInteractionAvailability.AVAILABLE,
                UiInteractionAvailability.STATE_UNAVAILABLE,
                -> null
            }
    }
}

internal enum class UiTaskContinuationStatus {
    WAITING_USER_PRESENT,
    CLAIMED,
    FINAL,
    MANUAL_REVIEW,
}

internal data class UiTaskContinuationRecord(
    val identity: UiTaskContinuationIdentity,
    val blockedReason: UiTaskContinuationBlockedReason,
    val status: UiTaskContinuationStatus,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val claimToken: String? = null,
) {
    init {
        require(createdAtEpochMillis >= 0L) { "Invalid continuation creation time" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Invalid continuation update time"
        }
        when (status) {
            UiTaskContinuationStatus.CLAIMED ->
                UiTaskContinuationBounds.requireClaimToken(checkNotNull(claimToken))
            UiTaskContinuationStatus.WAITING_USER_PRESENT,
            UiTaskContinuationStatus.FINAL,
            UiTaskContinuationStatus.MANUAL_REVIEW,
            -> require(claimToken == null) { "Only claimed continuations may have a claim token" }
        }
    }
}

internal data class UiTaskContinuationDocument(
    val records: List<UiTaskContinuationRecord> = emptyList(),
) {
    init {
        require(records.size <= UiTaskContinuationBounds.MAX_RECORDS) {
            "Too many UI task continuations"
        }
        require(records.map { it.identity.key }.distinct().size == records.size) {
            "Duplicate UI task continuation identity"
        }
    }
}

sealed interface UiTaskContinuationCheckpoint {
    data class Persisted(val continuationId: String) : UiTaskContinuationCheckpoint
    data object NotPersisted : UiTaskContinuationCheckpoint
}

fun interface UiTaskContinuationCheckpointer {
    fun checkpoint(
        call: DynamicToolCallParams,
        availability: UiInteractionAvailability,
    ): UiTaskContinuationCheckpoint

    companion object {
        val NONE = UiTaskContinuationCheckpointer { _, _ ->
            UiTaskContinuationCheckpoint.NotPersisted
        }
    }
}

internal object UiTaskContinuationBounds {
    const val ACCESSIBILITY_NAMESPACE = "android_ui"
    const val MAX_RECORDS = 32
    const val MAX_DOCUMENT_BYTES = 64 * 1_024
    const val MAX_OPAQUE_ID_CHARS = 256
    const val MAX_TOOL_CHARS = 128
    const val SHA256_HEX_CHARS = 64
    const val MAX_CLAIM_TOKEN_CHARS = 160

    fun requireOpaqueId(value: String, label: String): String {
        require(value.isNotBlank() && value.length <= MAX_OPAQUE_ID_CHARS) {
            "Invalid UI continuation $label id"
        }
        require(value.none(Char::isISOControl)) { "Invalid UI continuation $label id" }
        return value
    }

    fun requireTool(value: String): String {
        require(value.isNotBlank() && value.length <= MAX_TOOL_CHARS) {
            "Invalid UI continuation tool"
        }
        require(value.none(Char::isISOControl)) { "Invalid UI continuation tool" }
        return value
    }

    fun requireDigest(value: String): String {
        require(
            value.length == SHA256_HEX_CHARS &&
                value.all { it in '0'..'9' || it in 'a'..'f' },
        ) { "Invalid UI continuation digest" }
        return value
    }

    fun requireClaimToken(value: String): String {
        require(value.isNotBlank() && value.length <= MAX_CLAIM_TOKEN_CHARS) {
            "Invalid UI continuation claim token"
        }
        require(value.none(Char::isISOControl)) { "Invalid UI continuation claim token" }
        return value
    }
}

internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte ->
        String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
    }
