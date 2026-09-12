package ai.hans.standard.codex

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

internal fun parseThreadResume(
    request: EncodedRequest,
    result: JSONObject,
): ThreadResumeResult {
    val context = request.context as? RequestContext.ThreadResume
        ?: throw CrossCorrelationException("thread/resume request context is missing")
    val thread = parseThreadSummary(JsonContract.requiredObject(result, "thread"))
    if (thread.id != context.threadId) {
        throw CrossCorrelationException("thread/resume returned a different thread")
    }
    val initialTurns = parseOptionalInitialTurns(
        result = result,
        requestedLimit = context.requestedInitialTurnsLimit,
    )
    return ThreadResumeResult(
        thread = thread,
        effectiveModel = JsonContract.requiredString(result, "model", 128),
        effectiveEffort = JsonContract.optionalString(result, "reasoningEffort", 128)
            ?.let(ReasoningEffort::of),
        effectiveServiceTier = JsonContract.optionalString(result, "serviceTier", 128),
        initialTurnReceipts = initialTurns.receipts,
        recoveredItems = initialTurns.items,
        turnsBackwardsCursor = JsonContract.optionalString(result, "turnsBackwardsCursor", 1_024),
        itemsBackwardsCursor = JsonContract.optionalString(result, "itemsBackwardsCursor", 1_024),
        recoveredHistoryStatus = initialTurns.status,
    )
}

private data class ParsedInitialTurns(
    val receipts: List<ThreadTurnReceipt>,
    val items: List<RecoveredConversationItem>,
    val status: RecoveredHistoryStatus,
) {
    companion object {
        fun unavailable(): ParsedInitialTurns = ParsedInitialTurns(
            receipts = emptyList(),
            items = emptyList(),
            status = RecoveredHistoryStatus.UNAVAILABLE,
        )
    }
}

/**
 * Recent visible history is an optional projection layered on a successful thread resume.
 * Reject the whole projection transactionally when it is absent, malformed, or over budget;
 * never let optional display history invalidate the correlated resumed thread.
 */
private fun parseOptionalInitialTurns(
    result: JSONObject,
    requestedLimit: Int,
): ParsedInitialTurns {
    val page = result.opt("initialTurnsPage") as? JSONObject
        ?: return ParsedInitialTurns.unavailable()
    return try {
        parseInitialTurns(page, requestedLimit)
    } catch (_: IllegalArgumentException) {
        // JSON contract failures are ProtocolException subclasses, while model invariants such
        // as opaque IDs deliberately use require(). Both belong only to this optional projection.
        ParsedInitialTurns.unavailable()
    }
}

private fun parseInitialTurns(
    page: JSONObject,
    requestedLimit: Int,
): ParsedInitialTurns {
    val data = JsonContract.requiredArray(page, "data")
    if (data.length() > requestedLimit) {
        throw FrameLimitException("thread/resume returned too many initial turns")
    }
    var visibleItems = 0
    var recoveredTextBytes = 0
    val newestFirstItems = ArrayList<List<RecoveredConversationItem>>(data.length())
    val receipts = buildList(data.length()) {
        repeat(data.length()) { index ->
            val turn = data.requiredObject(index, "initial turn")
            val turnId = JsonContract.requiredString(
                turn,
                "id",
                ProtocolLimits.MAX_OPAQUE_ID_CHARS,
            )
            val status = TurnStatus.fromWire(
                JsonContract.requiredString(turn, "status", 64),
            )
            val itemsView = JsonContract.requiredString(turn, "itemsView", 32)
            if (itemsView != "summary") {
                throw CrossCorrelationException(
                    "thread/resume initial turn did not use the requested summary view",
                )
            }
            val items = JsonContract.requiredArray(turn, "items")
            visibleItems += items.length()
            if (visibleItems > ProtocolLimits.MAX_RECOVERED_HISTORY_ITEMS) {
                throw FrameLimitException("thread/resume returned too many summary items")
            }
            validateSummaryItemSequence(items)
            add(
                ThreadTurnReceipt(
                    turnId = turnId,
                    status = status,
                ),
            )
            val recovered = parseRecoveredTurnItems(
                turnId = turnId,
                turnStatus = status,
                items = items,
            )
            recoveredTextBytes += recovered.sumOf(RecoveredConversationItem::utf8TextBytes)
            if (recoveredTextBytes > ProtocolLimits.MAX_RECOVERED_HISTORY_TOTAL_BYTES) {
                throw FrameLimitException("thread/resume summary text exceeds the history limit")
            }
            newestFirstItems += recovered
        }
    }
    if (receipts.map(ThreadTurnReceipt::turnId).distinct().size != receipts.size) {
        throw CrossCorrelationException("thread/resume contains duplicate initial turn ids")
    }
    val chronologicalItems = newestFirstItems.asReversed().flatten()
    val deduplicated = LinkedHashMap<String, RecoveredConversationItem>()
    chronologicalItems.forEach { item ->
        val role = if (item is RecoveredConversationItem.User) "USER" else "HANS"
        val identity = if (item is RecoveredConversationItem.User) {
            item.clientId ?: item.itemId
        } else {
            item.itemId
        }
        deduplicated["$role:$identity"] = item
    }
    return ParsedInitialTurns(
        receipts = receipts,
        items = deduplicated.values.toList(),
        status = RecoveredHistoryStatus.LOADED,
    )
}

private fun validateSummaryItemSequence(items: JSONArray) {
    if (items.length() > 2) {
        throw CrossCorrelationException("thread/resume summary turn contains too many items")
    }
    val types = buildList(items.length()) {
        repeat(items.length()) { index ->
            add(
                JsonContract.requiredString(
                    items.requiredObject(index, "summary item"),
                    "type",
                    128,
                ),
            )
        }
    }
    val valid = types == emptyList<String>() ||
        types == listOf("userMessage") ||
        types == listOf("agentMessage") ||
        types == listOf("userMessage", "agentMessage")
    if (!valid) {
        throw CrossCorrelationException("thread/resume returned an invalid summary item sequence")
    }
}

private fun parseRecoveredTurnItems(
    turnId: String,
    turnStatus: TurnStatus,
    items: JSONArray,
): List<RecoveredConversationItem> {
    return buildList {
        repeat(items.length()) { index ->
            val item = items.requiredObject(index, "summary item")
            when (JsonContract.requiredString(item, "type", 128)) {
                "userMessage" -> {
                    val content = recoveredUserContent(item)
                    if (content.textParts.isEmpty() && !content.hasAttachment) return@repeat
                    add(
                        RecoveredConversationItem.User(
                            itemId = JsonContract.requiredString(
                                item,
                                "id",
                                ProtocolLimits.MAX_OPAQUE_ID_CHARS,
                            ),
                            clientId = JsonContract.optionalString(
                                item,
                                "clientId",
                                ProtocolLimits.MAX_OPAQUE_ID_CHARS,
                            ),
                            turnId = turnId,
                            textParts = content.textParts,
                            hasAttachment = content.hasAttachment,
                        ),
                    )
                }
                "agentMessage" -> {
                    val text = JsonContract.requiredString(
                        item,
                        "text",
                        ProtocolLimits.MAX_RECOVERED_HISTORY_MESSAGE_BYTES,
                        allowBlank = true,
                    )
                    if (text.isBlank()) return@repeat
                    val phase = JsonContract.optionalString(item, "phase", 64)
                        ?.let(AgentMessagePhase::fromWire)
                    add(
                        RecoveredConversationItem.Hans(
                            itemId = JsonContract.requiredString(
                                item,
                                "id",
                                ProtocolLimits.MAX_OPAQUE_ID_CHARS,
                            ),
                            turnId = turnId,
                            text = text,
                            complete = turnStatus != TurnStatus.IN_PROGRESS ||
                                phase == AgentMessagePhase.FINAL_ANSWER,
                            phase = phase,
                        ),
                    )
                }
                else -> throw CrossCorrelationException(
                    "thread/resume returned a non-display summary item",
                )
            }
        }
    }
}

private data class RecoveredUserContent(
    val textParts: List<String>,
    val hasAttachment: Boolean,
)

private fun recoveredUserContent(
    item: JSONObject,
): RecoveredUserContent {
    val content = JsonContract.requiredArray(item, "content")
    if (content.length() > ProtocolLimits.MAX_RECOVERED_HISTORY_INPUT_PARTS) {
        throw FrameLimitException("Recovered user message contains too many input parts")
    }
    var messageTextBytes = 0
    var hasAttachment = false
    val textParts = buildList {
        repeat(content.length()) { index ->
            val part = content.requiredObject(index, "user input")
            when (JsonContract.requiredString(part, "type", 64)) {
                "text" -> {
                    val text = JsonContract.requiredString(
                        part,
                        "text",
                        ProtocolLimits.MAX_RECOVERED_HISTORY_MESSAGE_BYTES,
                        allowBlank = true,
                    )
                    if (text.isNotBlank()) {
                        messageTextBytes += text.toByteArray(StandardCharsets.UTF_8).size
                        if (messageTextBytes > ProtocolLimits.MAX_RECOVERED_HISTORY_MESSAGE_BYTES) {
                            throw FrameLimitException(
                                "Recovered user message exceeds the history message limit",
                            )
                        }
                        add(text)
                    }
                }
                "image", "localImage", "audio", "localAudio" -> hasAttachment = true
                else -> Unit
            }
        }
    }
    return RecoveredUserContent(textParts, hasAttachment)
}

private fun RecoveredConversationItem.utf8TextBytes(): Int = when (this) {
    is RecoveredConversationItem.User -> textParts.sumOf {
        it.toByteArray(StandardCharsets.UTF_8).size
    }
    is RecoveredConversationItem.Hans -> text.toByteArray(StandardCharsets.UTF_8).size
}

internal fun parseThreadList(result: JSONObject): ThreadListResult {
    val data = JsonContract.requiredArray(result, "data")
    if (data.length() > ProtocolLimits.MAX_THREADS_PER_PAGE) {
        throw FrameLimitException("thread/list returned too many threads")
    }
    val threads = buildList(data.length()) {
        repeat(data.length()) { index ->
            add(parseThreadSummary(data.requiredObject(index, "thread")))
        }
    }
    if (threads.map { it.id }.distinct().size != threads.size) {
        throw CrossCorrelationException("thread/list contains duplicate thread ids")
    }
    return ThreadListResult(
        threads = threads,
        nextCursor = JsonContract.optionalString(result, "nextCursor", 1_024),
        backwardsCursor = JsonContract.optionalString(result, "backwardsCursor", 1_024),
    )
}

internal fun parseTurnInterrupt(
    request: EncodedRequest,
    result: JSONObject,
): TurnInterruptResult {
    JsonContract.requireOnlyKeys(result, emptySet(), "turn/interrupt result")
    val context = request.context as? RequestContext.TurnInterrupt
        ?: throw CrossCorrelationException("turn/interrupt request context is missing")
    return TurnInterruptResult(context.threadId, context.turnId)
}

internal fun parseSkillsList(result: JSONObject): SkillsListResult {
    val data = JsonContract.requiredArray(result, "data")
    if (data.length() > ProtocolLimits.MAX_SKILL_ROOTS) {
        throw FrameLimitException("skills/list returned too many roots")
    }
    var skillCount = 0
    val roots = buildList(data.length()) {
        repeat(data.length()) { rootIndex ->
            val root = data.requiredObject(rootIndex, "skill root")
            val skillsJson = JsonContract.requiredArray(root, "skills")
            skillCount += skillsJson.length()
            if (skillCount > ProtocolLimits.MAX_SKILLS_TOTAL) {
                throw FrameLimitException("skills/list returned too many skills")
            }
            val skills = buildList(skillsJson.length()) {
                repeat(skillsJson.length()) { skillIndex ->
                    add(parseSkill(skillsJson.requiredObject(skillIndex, "skill")))
                }
            }
            val errorsJson = JsonContract.requiredArray(root, "errors")
            val errors = buildList(errorsJson.length()) {
                repeat(errorsJson.length()) { errorIndex ->
                    val error = errorsJson.requiredObject(errorIndex, "skill error")
                    add(
                        SkillLoadError(
                            path = JsonContract.requiredString(
                                error,
                                "path",
                                ProtocolLimits.MAX_PATH_CHARS,
                            ),
                            message = JsonContract.requiredString(error, "message", 16_384),
                        ),
                    )
                }
            }
            add(
                SkillsAtRoot(
                    workingDirectory = JsonContract.requiredString(
                        root,
                        "cwd",
                        ProtocolLimits.MAX_PATH_CHARS,
                    ),
                    skills = skills,
                    errors = errors,
                ),
            )
        }
    }
    return SkillsListResult(roots)
}

internal fun parseThreadSummary(value: JSONObject): ThreadSummary = ThreadSummary(
    id = JsonContract.requiredString(value, "id", ProtocolLimits.MAX_OPAQUE_ID_CHARS),
    name = JsonContract.optionalString(value, "name", 1_024),
    preview = JsonContract.requiredString(value, "preview", 16_384, allowBlank = true),
    createdAtSeconds = JsonContract.requiredLong(value, "createdAt"),
    updatedAtSeconds = JsonContract.requiredLong(value, "updatedAt"),
    status = parseThreadStatus(JsonContract.requiredObject(value, "status")),
)

internal fun parseThreadStatus(value: JSONObject): ThreadStatusSnapshot {
    return when (val type = JsonContract.requiredString(value, "type", 64)) {
        "notLoaded" -> ThreadStatusSnapshot(ThreadRuntimeStatus.NOT_LOADED)
        "idle" -> ThreadStatusSnapshot(ThreadRuntimeStatus.IDLE)
        "systemError" -> ThreadStatusSnapshot(ThreadRuntimeStatus.SYSTEM_ERROR)
        "active" -> {
            val flags = JsonContract.requiredArray(value, "activeFlags")
            if (flags.length() > 8) throw FrameLimitException("Too many thread active flags")
            ThreadStatusSnapshot(
                status = ThreadRuntimeStatus.ACTIVE,
                activeFlags = buildSet {
                    repeat(flags.length()) { index ->
                        val flag = flags.opt(index) as? String
                            ?: throw MalformedEnvelopeException("Thread flag must be a string")
                        add(ThreadActiveFlag.fromWire(flag))
                    }
                },
            )
        }
        else -> throw UnsupportedProtocolValueException("Unknown thread status '$type'")
    }
}

private fun parseSkill(value: JSONObject): SkillSummary {
    val interfaceValue = if (!value.has("interface") || value.isNull("interface")) {
        null
    } else {
        JsonContract.requiredObject(value, "interface")
    }
    return SkillSummary(
        name = JsonContract.requiredString(value, "name", 512),
        path = JsonContract.requiredString(value, "path", ProtocolLimits.MAX_PATH_CHARS),
        description = JsonContract.requiredString(
            value,
            "description",
            32_768,
            allowBlank = true,
        ),
        enabled = JsonContract.requiredBoolean(value, "enabled"),
        scope = SkillScope.fromWire(JsonContract.requiredString(value, "scope", 64)),
        displayName = interfaceValue?.let {
            JsonContract.optionalString(it, "displayName", 512)
        },
        shortDescription = interfaceValue?.let {
            JsonContract.optionalString(it, "shortDescription", 2_048)
        } ?: JsonContract.optionalString(value, "shortDescription", 2_048),
        iconSmallPath = interfaceValue?.let {
            JsonContract.optionalString(it, "iconSmall", ProtocolLimits.MAX_PATH_CHARS)
        },
        iconSmallUrl = interfaceValue?.let {
            JsonContract.optionalString(it, "iconSmallUrl", 4_096)
        },
    )
}

private fun JSONArray.requiredObject(index: Int, label: String): JSONObject =
    opt(index) as? JSONObject
        ?: throw MalformedEnvelopeException("Expected $label object at index $index")
