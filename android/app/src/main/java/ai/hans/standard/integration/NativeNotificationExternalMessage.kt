package ai.hans.standard.integration

import ai.hans.standard.codex.ProtocolLimits
import ai.hans.standard.codex.TurnStatus
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Host-only external facts, never user input or a model-callable dispatch capability. */
internal data class NativeNotificationExternalMessage(
    val eventId: String,
    val payloadJson: String,
    val payloadSha256: String,
) {
    init {
        requireValidIdentity(eventId)
        require(payloadJson.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES)
        val payload = JSONObject(payloadJson)
        require(payload.opt("schema") == SCHEMA && payload.opt("eventId") == eventId)
        require(payloadSha256 == sha256(payloadJson))
    }

    companion object {
        const val SCHEMA = "hans.notification.external-event.v1"
        const val TOOL_NAME = "push_event"
        const val TOOL_NAMESPACE = "hans_notifications"
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        fun create(eventId: String, payloadJson: String): NativeNotificationExternalMessage =
            NativeNotificationExternalMessage(eventId, payloadJson, sha256(payloadJson))
        internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun requireValidIdentity(value: String) {
            require(value.isNotBlank() && value.length <= ProtocolLimits.MAX_OPAQUE_ID_CHARS &&
                value.none(Char::isISOControl))
        }
    }
}

internal sealed interface NativeNotificationDispatchResult {
    data object RejectedBeforeTransport : NativeNotificationDispatchResult
    /** A sent frame is not a runtime acceptance receipt. */
    data class Submitted(val threadId: String, val eventId: String) : NativeNotificationDispatchResult
    data object TransportOutcomeAmbiguous : NativeNotificationDispatchResult
}

internal sealed interface NativeNotificationDispatchReceipt {
    data class Accepted(val threadId: String, val turnId: String) : NativeNotificationDispatchReceipt
    data object RejectedByRuntime : NativeNotificationDispatchReceipt
    data object OutcomeAmbiguous : NativeNotificationDispatchReceipt
}

/** No text is retained in the proof: only the exact persisted origin and payload fingerprint. */
internal data class NativeNotificationExternalReceipt(
    val eventId: String,
    val payloadSha256: String,
    val threadId: String,
    val turnId: String,
    val toolName: String = NativeNotificationExternalMessage.TOOL_NAME,
    val toolNamespace: String = NativeNotificationExternalMessage.TOOL_NAMESPACE,
)

internal data class NativeNotificationExternalHistory(
    val threadId: String,
    val receipts: List<NativeNotificationExternalReceipt>,
    val turnStatuses: Map<String, TurnStatus>,
)

/** Only the native persisted functionCallOutput item can prove ingestion after a restart. */
internal object NativeNotificationExternalHistoryDecoder {
    fun item(threadId: String, turnId: String, item: JSONObject): NativeNotificationExternalReceipt? {
        if (item.opt("type") != "functionCallOutput" ||
            item.opt("name") != NativeNotificationExternalMessage.TOOL_NAME ||
            item.opt("namespace") != NativeNotificationExternalMessage.TOOL_NAMESPACE) return null
        val itemId = item.opt("id") as? String ?: return null
        if (itemId.isBlank() || itemId.length > ProtocolLimits.MAX_OPAQUE_ID_CHARS || itemId.any(Char::isISOControl)) return null
        val output = item.opt("output") as? String ?: return null
        if (output.toByteArray(StandardCharsets.UTF_8).size > NativeNotificationExternalMessage.MAX_PAYLOAD_BYTES) return null
        return try {
            val payload = JSONObject(output)
            val eventId = payload.opt("eventId") as? String ?: return null
            val message = NativeNotificationExternalMessage.create(eventId, output)
            NativeNotificationExternalReceipt(message.eventId, message.payloadSha256, threadId, turnId)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }

    fun page(threadId: String, result: JSONObject): NativeNotificationExternalHistory? = try {
        val turns = result.getJSONArray("data")
        require(turns.length() <= 8)
        val statuses = linkedMapOf<String, TurnStatus>()
        val receipts = arrayListOf<NativeNotificationExternalReceipt>()
        var itemCount = 0
        repeat(turns.length()) { index ->
            val turn = turns.getJSONObject(index)
            val turnId = turn.opt("id") as? String ?: throw IllegalArgumentException("invalid_turn_identity")
            require(turnId.isNotBlank() && turnId.length <= ProtocolLimits.MAX_OPAQUE_ID_CHARS &&
                turnId.none(Char::isISOControl))
            require(turn.opt("itemsView") == "full")
            val status = turn.opt("status") as? String ?: throw IllegalArgumentException("invalid_turn_status")
            require(statuses.put(turnId, TurnStatus.fromWire(status)) == null)
            val items = turn.getJSONArray("items")
            itemCount += items.length()
            require(itemCount <= 512)
            repeat(items.length()) { itemIndex ->
                item(threadId, turnId, items.getJSONObject(itemIndex))?.let(receipts::add)
            }
        }
        // Multiple native events in one turn are allowed; contradictory ingestion is not proof.
        require(receipts.groupBy { it.eventId }.values.none { values ->
            values.map { it.payloadSha256 to it.turnId }.distinct().size > 1
        })
        NativeNotificationExternalHistory(threadId, receipts.distinct(), statuses.toMap())
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: org.json.JSONException) {
        null
    }
}
