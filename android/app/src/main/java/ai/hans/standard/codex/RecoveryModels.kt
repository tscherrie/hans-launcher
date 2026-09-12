package ai.hans.standard.codex

data object AccountLogoutResult : AppServerResult

enum class ThreadRuntimeStatus {
    NOT_LOADED,
    IDLE,
    SYSTEM_ERROR,
    ACTIVE,
}

enum class ThreadActiveFlag(val wireValue: String) {
    WAITING_ON_APPROVAL("waitingOnApproval"),
    WAITING_ON_USER_INPUT("waitingOnUserInput");

    companion object {
        fun fromWire(value: String): ThreadActiveFlag = entries.firstOrNull {
            it.wireValue == value
        } ?: throw UnsupportedProtocolValueException("Unknown thread active flag '$value'")
    }
}

data class ThreadStatusSnapshot(
    val status: ThreadRuntimeStatus,
    val activeFlags: Set<ThreadActiveFlag> = emptySet(),
)

data class ThreadSummary(
    val id: String,
    val name: String?,
    val preview: String,
    val createdAtSeconds: Long,
    val updatedAtSeconds: Long,
    val status: ThreadStatusSnapshot,
) {
    init {
        requireOpaqueId(id, "Thread id")
        require(preview.length <= 16_384) { "Thread preview is too long" }
    }
}

data class ThreadResumeResult(
    val thread: ThreadSummary,
    val effectiveModel: String,
    val effectiveEffort: ReasoningEffort?,
    val effectiveServiceTier: String?,
    /** Bounded receipts requested expressly for exact restart recovery. */
    val initialTurnReceipts: List<ThreadTurnReceipt>,
    /** Oldest-first history candidates. Never contains tools, reasoning, paths, or retry state. */
    val recoveredItems: List<RecoveredConversationItem> = emptyList(),
    val turnsBackwardsCursor: String?,
    val itemsBackwardsCursor: String?,
    /** Content-free outcome; parser failures never retain payload details. */
    val recoveredHistoryStatus: RecoveredHistoryStatus = RecoveredHistoryStatus.UNAVAILABLE,
) : AppServerResult

enum class RecoveredHistoryStatus {
    LOADED,
    UNAVAILABLE,
}

sealed interface RecoveredConversationItem {
    val itemId: String
    val turnId: String

    data class User(
        override val itemId: String,
        val clientId: String?,
        override val turnId: String,
        /** Wire text parts are candidates only; the controller must prove visible digests. */
        val textParts: List<String>,
        val hasAttachment: Boolean,
    ) : RecoveredConversationItem {
        init {
            requireOpaqueId(itemId, "Recovered item id")
            clientId?.let { requireOpaqueId(it, "Recovered client id") }
            requireOpaqueId(turnId, "Recovered turn id")
            require(textParts.isNotEmpty() || hasAttachment) {
                "Recovered user item must contain text or an attachment"
            }
        }
    }

    data class Hans(
        override val itemId: String,
        override val turnId: String,
        val text: String,
        val complete: Boolean,
        val phase: AgentMessagePhase?,
    ) : RecoveredConversationItem {
        init {
            requireOpaqueId(itemId, "Recovered item id")
            requireOpaqueId(turnId, "Recovered turn id")
            require(text.isNotBlank()) { "Recovered Hans message must contain visible text" }
        }
    }
}

data class ThreadTurnReceipt(
    val turnId: String,
    val status: TurnStatus,
) {
    init {
        requireOpaqueId(turnId, "Turn id")
    }
}

data class ThreadListResult(
    val threads: List<ThreadSummary>,
    val nextCursor: String?,
    val backwardsCursor: String?,
) : AppServerResult

data class TurnInterruptResult(
    val threadId: String,
    val turnId: String,
) : AppServerResult

enum class SkillScope(val wireValue: String) {
    USER("user"),
    REPO("repo"),
    SYSTEM("system"),
    ADMIN("admin");

    companion object {
        fun fromWire(value: String): SkillScope = entries.firstOrNull { it.wireValue == value }
            ?: throw UnsupportedProtocolValueException("Unknown skill scope '$value'")
    }
}

data class SkillSummary(
    val name: String,
    val path: String,
    val description: String,
    val enabled: Boolean,
    val scope: SkillScope,
    val displayName: String?,
    val shortDescription: String?,
    val iconSmallPath: String?,
    val iconSmallUrl: String?,
)

data class SkillLoadError(
    val path: String,
    val message: String,
)

data class SkillsAtRoot(
    val workingDirectory: String,
    val skills: List<SkillSummary>,
    val errors: List<SkillLoadError>,
)

data class SkillsListResult(
    val roots: List<SkillsAtRoot>,
) : AppServerResult

/** Merges cursor pages and rejects loops or conflicting duplicate model records. */
class ModelListAccumulator {
    private val models = LinkedHashMap<String, CodexModel>()
    private val seenCursors = LinkedHashSet<String>()
    private var started = false
    private var expectedCursor: String? = null

    @Synchronized
    fun append(page: ModelListResult): ModelCatalog {
        if (page.requestedCursor != expectedCursor) {
            throw CrossCorrelationException(
                "model/list page cursor does not match the expected cursor",
            )
        }
        if (started && expectedCursor == null) {
            throw CrossCorrelationException("model/list pagination is already complete")
        }
        page.catalog.models.forEach { incoming ->
            val previous = models[incoming.wireModel]
            if (previous != null && previous != incoming) {
                throw CrossCorrelationException(
                    "model/list returned a conflicting duplicate '${incoming.wireModel}'",
                )
            }
            models[incoming.wireModel] = incoming
        }
        if (models.size > ProtocolLimits.MAX_MODELS_TOTAL) {
            throw FrameLimitException("Merged model catalog is too large")
        }
        page.nextCursor?.let { cursor ->
            if (!seenCursors.add(cursor)) {
                throw CrossCorrelationException("model/list cursor loop detected")
            }
        }
        started = true
        expectedCursor = page.nextCursor
        return ModelCatalog(models.values.toList())
    }

    @Synchronized
    fun nextCursor(): String? = expectedCursor

    @Synchronized
    fun isComplete(): Boolean = started && expectedCursor == null
}
