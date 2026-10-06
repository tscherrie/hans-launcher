package ai.hans.standard.notifications.agentchannel

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Android/app-issued metadata, never inferred from notification display names or message text. */
data class WhatsAppNotificationSource(
    val packageName: String,
    val androidUserId: Int,
    val postingUid: Int,
    val notificationKey: String,
    val shortcutId: String?,
    val ownPersonIdentity: String?,
    val isGroupConversation: Boolean,
    val isGroupSummary: Boolean,
    val messages: List<WhatsAppNotificationMessage>,
    val messagesTruncated: Boolean = false,
    val displayTitle: String = "",
) {
    fun identity(): String? {
        if (packageName != WHATSAPP_PACKAGE || androidUserId < 0 || postingUid < 0 ||
            shortcutId.isNullOrBlank() || shortcutId.length > 1_024 ||
            (ownPersonIdentity != null && !ownPersonIdentity.matches(HEX_DIGEST)) || isGroupConversation || isGroupSummary
        ) return null
        // The user explicitly identifies the actual self-chat locally; the app-issued conversation
        // ID pins that choice. Person names are never evidence, and some apps omit Person key/URI.
        return channelDigest(packageName, androidUserId.toString(), postingUid.toString(), shortcutId)
    }
}

data class WhatsAppNotificationMessage(
    val text: String,
    val timestampEpochMillis: Long,
    /** Null sender in Android MessagingStyle means the style's own user, not an unknown sender. */
    val senderIdentity: String?,
    val senderIsOwnUser: Boolean,
    val truncated: Boolean = false,
    val hasAttachment: Boolean = false,
)

data class AgentChannelEnrollmentCandidate(
    val id: String,
    val sourceIdentity: String,
    val source: WhatsAppNotificationSource,
    val observedAtEpochMillis: Long,
)

data class AgentChannelBinding(
    val generation: String,
    val sourceIdentity: String,
    val confirmedAtEpochMillis: Long,
    val source: WhatsAppNotificationSource,
)

enum class AgentChannelWorkflowStatus { READY, CLAIMED, DISPATCHED, UNCERTAIN, COMPLETED, FAILED, CANCELLED }

/** Persisted BEFORE transport; a later positive receipt may fill in the immutable turn id. */
data class AgentChannelDispatchCorrelation(
    val threadId: String,
    val clientUserMessageId: String,
    val turnId: String? = null,
) {
    init {
        requireChannelCorrelationId(threadId)
        requireChannelCorrelationId(clientUserMessageId)
        turnId?.let(::requireChannelCorrelationId)
    }
}

/** A source-bound receipt is local enrollment proof, NOT a cryptographic signature or sender proof. */
data class AgentChannelRequestReceipt(
    val id: String,
    val bindingGeneration: String,
    val sourceIdentity: String,
    val idempotencyKey: String,
    val notificationKey: String,
    val requestId: String?,
    val requestText: String,
    val receivedAtEpochMillis: Long,
    val messageAtEpochMillis: Long,
    val status: AgentChannelWorkflowStatus,
    /** Null on old ledgers: uncertainty stays visible, never converted into a retry. */
    val dispatchCorrelation: AgentChannelDispatchCorrelation? = null,
    /** Terminal tombstone retention starts at settlement, not at the original input time. */
    val settledAtEpochMillis: Long? = null,
)

/** This may authorize only a lookup of this notification/chat, never execution of the preview. */
data class AgentChannelRetrievalHint(
    val id: String,
    val bindingGeneration: String,
    val sourceIdentity: String,
    val notificationKey: String,
    val shortcutId: String,
    val reason: String,
    val observedAtEpochMillis: Long,
)

data class AgentChannelState(
    val binding: AgentChannelBinding? = null,
    val enrollmentCandidates: List<AgentChannelEnrollmentCandidate> = emptyList(),
    val requests: List<AgentChannelRequestReceipt> = emptyList(),
    val retrievalHints: List<AgentChannelRetrievalHint> = emptyList(),
)

data class AgentChannelStatus(
    val available: Boolean,
    val binding: AgentChannelBinding?,
    val readyCount: Int,
    val uncertainCount: Int,
    val lookupRequiredCount: Int = 0,
)

data class AgentChannelIngressResult(
    val readyReceipts: List<AgentChannelRequestReceipt> = emptyList(),
    val retrievalHints: List<AgentChannelRetrievalHint> = emptyList(),
    val ignoredCount: Int = 0,
    val failureCode: String? = null,
)

interface AgentChannelStorage {
    /** Null means unavailable/corrupt. Never silently reset a previously enrolled ledger. */
    fun read(): AgentChannelState?
    fun write(state: AgentChannelState)
}

object AgentChannelLimits {
    const val MAX_MESSAGES = 12
    const val MAX_MESSAGE_BYTES = 4_096
    const val MAX_REQUEST_BYTES = 2_048
    const val MAX_REQUESTS = 256
    const val MAX_ENROLLMENT_CANDIDATES = 8
    const val MAX_RETRIEVAL_HINTS = 16
    const val CANDIDATE_TTL_MILLIS = 10L * 60 * 1_000
    const val REQUEST_TTL_MILLIS = 24L * 60 * 60 * 1_000
    const val TOMBSTONE_TTL_MILLIS = 2 * REQUEST_TTL_MILLIS
    const val MAX_STORAGE_BYTES = 1_048_576
}

private fun requireChannelCorrelationId(value: String) {
    require(value.isNotBlank() && value.length <= 256 && !value.any(Char::isISOControl))
}

internal const val WHATSAPP_PACKAGE = "com.whatsapp"
internal val HEX_DIGEST = Regex("[0-9a-f]{64}")
internal fun channelDigest(vararg values: String): String = MessageDigest.getInstance("SHA-256")
    .digest(values.joinToString("") { "${it.length}:$it;" }.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
