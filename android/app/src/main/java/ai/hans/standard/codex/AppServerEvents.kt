package ai.hans.standard.codex

import org.json.JSONObject

enum class AccountAuthMode(val wireValue: String) {
    API_KEY("apikey"),
    CHATGPT("chatgpt"),
    CHATGPT_AUTH_TOKENS("chatgptAuthTokens"),
    HEADERS("headers"),
    AGENT_IDENTITY("agentIdentity"),
    PERSONAL_ACCESS_TOKEN("personalAccessToken"),
    BEDROCK_API_KEY("bedrockApiKey");

    companion object {
        fun fromWire(value: String): AccountAuthMode = entries.firstOrNull {
            it.wireValue == value
        } ?: throw UnsupportedProtocolValueException("Unknown auth mode '$value'")
    }
}

enum class AccountPlanType(val wireValue: String) {
    FREE("free"),
    GO("go"),
    PLUS("plus"),
    PRO("pro"),
    PROLITE("prolite"),
    TEAM("team"),
    SELF_SERVE_BUSINESS_PROLITE("self_serve_business_prolite"),
    SELF_SERVE_BUSINESS_USAGE_BASED("self_serve_business_usage_based"),
    BUSINESS("business"),
    ENT26("ent26"),
    ENTERPRISE_CBP_AUTOMATION("enterprise_cbp_automation"),
    ENTERPRISE_CBP_USAGE_BASED("enterprise_cbp_usage_based"),
    ENTERPRISE("enterprise"),
    EDU("edu"),
    EDU_PLUS("edu_plus"),
    EDU_PRO("edu_pro"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String): AccountPlanType = entries.firstOrNull {
            it.wireValue == value
        } ?: throw UnsupportedProtocolValueException("Unknown plan type '$value'")
    }
}

enum class AgentMessagePhase(val wireValue: String) {
    COMMENTARY("commentary"),
    FINAL_ANSWER("final_answer");

    companion object {
        fun fromWire(value: String): AgentMessagePhase = entries.firstOrNull {
            it.wireValue == value
        } ?: throw UnsupportedProtocolValueException("Unknown message phase '$value'")
    }
}

enum class ToolStatus(val wireValue: String) {
    IN_PROGRESS("inProgress"),
    COMPLETED("completed"),
    FAILED("failed"),
    DECLINED("declined");

    companion object {
        fun fromWire(value: String): ToolStatus = entries.firstOrNull {
            it.wireValue == value
        } ?: throw UnsupportedProtocolValueException("Unknown tool status '$value'")
    }
}

data class TurnSnapshot(
    val id: String,
    val status: TurnStatus,
    val errorMessage: String?,
    val startedAtSeconds: Long?,
    val completedAtSeconds: Long?,
)

sealed interface StreamItem {
    val id: String
    val type: String

    data class AgentMessage(
        override val id: String,
        val text: String,
        val phase: AgentMessagePhase?,
    ) : StreamItem {
        override val type: String = "agentMessage"
    }

    data class Command(
        override val id: String,
        val command: String,
        val status: ToolStatus,
        val aggregatedOutput: String?,
    ) : StreamItem {
        override val type: String = "commandExecution"
    }

    data class McpTool(
        override val id: String,
        val server: String,
        val tool: String,
        val status: ToolStatus,
    ) : StreamItem {
        override val type: String = "mcpToolCall"
    }

    data class DynamicTool(
        override val id: String,
        val tool: String,
        val status: ToolStatus,
    ) : StreamItem {
        override val type: String = "dynamicToolCall"
    }

    data class Other(
        override val id: String,
        override val type: String,
        val boundedJson: String,
    ) : StreamItem
}

sealed interface ServerEvent {
    val method: String

    data class AccountLoginCompleted(
        val success: Boolean,
        val loginId: String?,
        val error: String?,
    ) : ServerEvent {
        override val method = "account/login/completed"
    }

    data class AccountUpdated(
        val authMode: AccountAuthMode?,
        val planType: AccountPlanType?,
    ) : ServerEvent {
        override val method = "account/updated"
    }

    data class ThreadStarted(val thread: ThreadSummary) : ServerEvent {
        override val method = "thread/started"
    }

    data class ThreadStatusChanged(
        val threadId: String,
        val status: ThreadStatusSnapshot,
    ) : ServerEvent {
        override val method = "thread/status/changed"
    }

    /** Narrow projection of the runtime's applied, thread-owned settings snapshot. */
    data class ThreadSettingsUpdated(
        val threadId: String,
        val model: String,
        val effort: ReasoningEffort?,
        val serviceTier: String?,
    ) : ServerEvent {
        override val method = "thread/settings/updated"

        init {
            requireOpaqueId(threadId, "Thread id")
            requireWireToken(model, "Effective model")
            serviceTier?.let { requireWireToken(it, "Effective service tier") }
        }
    }

    data class ThreadTokenUsageUpdated(
        val threadId: String,
        val turnId: String,
        val tokenUsage: ThreadTokenUsageSnapshot,
    ) : ServerEvent {
        override val method = "thread/tokenUsage/updated"
    }

    data class TurnStarted(
        val threadId: String,
        val turn: TurnSnapshot,
    ) : ServerEvent {
        override val method = "turn/started"
    }

    data class TurnCompleted(
        val threadId: String,
        val turn: TurnSnapshot,
    ) : ServerEvent {
        override val method = "turn/completed"
    }

    data class ItemStarted(
        val threadId: String,
        val turnId: String,
        val item: StreamItem,
        val startedAtMillis: Long,
    ) : ServerEvent {
        override val method = "item/started"
    }

    data class ItemCompleted(
        val threadId: String,
        val turnId: String,
        val item: StreamItem,
        val completedAtMillis: Long,
    ) : ServerEvent {
        override val method = "item/completed"
    }

    data class AgentMessageDelta(
        val threadId: String,
        val turnId: String,
        val itemId: String,
        val delta: String,
    ) : ServerEvent {
        override val method = "item/agentMessage/delta"
    }

    data class CommandOutputDelta(
        val threadId: String,
        val turnId: String,
        val itemId: String,
        val delta: String,
    ) : ServerEvent {
        override val method = "item/commandExecution/outputDelta"
    }

    data object SkillsChanged : ServerEvent {
        override val method = "skills/changed"
    }

    data class Raw(
        override val method: String,
        val boundedParamsJson: String,
    ) : ServerEvent
}

object AppServerEventDecoder {
    private val knownMethods = setOf(
        "account/login/completed",
        "account/updated",
        "thread/started",
        "thread/status/changed",
        "thread/settings/updated",
        "thread/tokenUsage/updated",
        "turn/started",
        "turn/completed",
        "item/started",
        "item/completed",
        "item/agentMessage/delta",
        "item/commandExecution/outputDelta",
        "skills/changed",
    )

    fun decode(raw: String): ServerEvent {
        val envelope = JsonContract.parseObject(raw, ProtocolLimits.MAX_EVENT_FRAME_BYTES)
        JsonContract.requireOnlyKeys(
            envelope,
            setOf("method", "params", "jsonrpc", "emittedAtMs"),
            "notification envelope",
        )
        if (envelope.has("jsonrpc") && envelope.opt("jsonrpc") != "2.0") {
            throw MalformedEnvelopeException("Unsupported jsonrpc version")
        }
        if (envelope.has("emittedAtMs")) {
            JsonContract.requiredLong(envelope, "emittedAtMs")
                .requireNonNegative("emittedAtMs")
        }
        val method = JsonContract.requiredString(
            envelope,
            "method",
            ProtocolLimits.MAX_METHOD_CHARS,
        )
        if (!method.matches(Regex("[A-Za-z0-9][A-Za-z0-9_./-]{0,127}"))) {
            throw MalformedEnvelopeException("Invalid notification method")
        }
        if (!envelope.has("params")) {
            throw MalformedEnvelopeException("Notification is missing params")
        }
        val rawParams = envelope.opt("params")
        if (method !in knownMethods) {
            return ServerEvent.Raw(
                method = method,
                boundedParamsJson = if (rawParams === JSONObject.NULL) {
                    "null"
                } else {
                    rawParams?.toString() ?: "null"
                },
            )
        }
        val params = rawParams as? JSONObject
            ?: throw MalformedEnvelopeException("Expected object at 'params'")
        return when (method) {
            "account/login/completed" -> parseLoginCompleted(params)
            "account/updated" -> parseAccountUpdated(params)
            "thread/started" -> ServerEvent.ThreadStarted(
                parseThreadSummary(JsonContract.requiredObject(params, "thread")),
            )
            "thread/status/changed" -> ServerEvent.ThreadStatusChanged(
                threadId = requiredId(params, "threadId"),
                status = parseThreadStatus(JsonContract.requiredObject(params, "status")),
            )
            "thread/settings/updated" -> parseThreadSettingsUpdated(params)
            "thread/tokenUsage/updated" -> ServerEvent.ThreadTokenUsageUpdated(
                threadId = requiredId(params, "threadId"),
                turnId = requiredId(params, "turnId"),
                tokenUsage = parseThreadTokenUsage(JsonContract.requiredObject(params, "tokenUsage")),
            )
            "turn/started" -> ServerEvent.TurnStarted(
                threadId = requiredId(params, "threadId"),
                turn = parseTurn(JsonContract.requiredObject(params, "turn")),
            )
            "turn/completed" -> ServerEvent.TurnCompleted(
                threadId = requiredId(params, "threadId"),
                turn = parseTurn(JsonContract.requiredObject(params, "turn")),
            )
            "item/started" -> ServerEvent.ItemStarted(
                threadId = requiredId(params, "threadId"),
                turnId = requiredId(params, "turnId"),
                item = parseStreamItem(JsonContract.requiredObject(params, "item")),
                startedAtMillis = JsonContract.requiredLong(params, "startedAtMs")
                    .requireNonNegative("startedAtMs"),
            )
            "item/completed" -> ServerEvent.ItemCompleted(
                threadId = requiredId(params, "threadId"),
                turnId = requiredId(params, "turnId"),
                item = parseStreamItem(JsonContract.requiredObject(params, "item")),
                completedAtMillis = JsonContract.requiredLong(params, "completedAtMs")
                    .requireNonNegative("completedAtMs"),
            )
            "item/agentMessage/delta" -> ServerEvent.AgentMessageDelta(
                threadId = requiredId(params, "threadId"),
                turnId = requiredId(params, "turnId"),
                itemId = requiredId(params, "itemId"),
                delta = JsonContract.requiredString(
                    params,
                    "delta",
                    ProtocolLimits.MAX_INPUT_TEXT_BYTES,
                    allowBlank = true,
                ),
            )
            "item/commandExecution/outputDelta" -> ServerEvent.CommandOutputDelta(
                threadId = requiredId(params, "threadId"),
                turnId = requiredId(params, "turnId"),
                itemId = requiredId(params, "itemId"),
                delta = JsonContract.requiredString(
                    params,
                    "delta",
                    ProtocolLimits.MAX_STRING_BYTES,
                    allowBlank = true,
                ),
            )
            "skills/changed" -> {
                // This notification is an invalidation hint. Future Codex
                // versions may add metadata without changing that meaning.
                ServerEvent.SkillsChanged
            }
            else -> error("Known notification method is not decoded")
        }
    }
}

private fun parseThreadSettingsUpdated(params: JSONObject): ServerEvent.ThreadSettingsUpdated {
    val settings = JsonContract.requiredObject(params, "threadSettings")
    return ServerEvent.ThreadSettingsUpdated(
        threadId = requiredId(params, "threadId"),
        model = JsonContract.requiredString(settings, "model", 128),
        effort = JsonContract.optionalString(settings, "effort", 128)?.let(ReasoningEffort::of),
        serviceTier = JsonContract.optionalString(settings, "serviceTier", 128),
    )
}

private fun parseThreadTokenUsage(value: JSONObject): ThreadTokenUsageSnapshot =
    ThreadTokenUsageSnapshot(
        last = parseTokenUsageBreakdown(JsonContract.requiredObject(value, "last")),
        total = parseTokenUsageBreakdown(JsonContract.requiredObject(value, "total")),
        modelContextWindow = JsonContract.optionalLong(value, "modelContextWindow")
            ?.takeIf { it > 0 },
    )

private fun parseTokenUsageBreakdown(value: JSONObject): TokenUsageBreakdown = TokenUsageBreakdown(
    inputTokens = JsonContract.requiredLong(value, "inputTokens").requireNonNegative("inputTokens"),
    cachedInputTokens = JsonContract.requiredLong(value, "cachedInputTokens")
        .requireNonNegative("cachedInputTokens"),
    outputTokens = JsonContract.requiredLong(value, "outputTokens").requireNonNegative("outputTokens"),
    reasoningOutputTokens = JsonContract.requiredLong(value, "reasoningOutputTokens")
        .requireNonNegative("reasoningOutputTokens"),
    totalTokens = JsonContract.requiredLong(value, "totalTokens").requireNonNegative("totalTokens"),
    cacheWriteInputTokens = if (value.has("cacheWriteInputTokens")) {
        JsonContract.requiredLong(value, "cacheWriteInputTokens")
            .requireNonNegative("cacheWriteInputTokens")
    } else {
        null
    },
)

private fun parseLoginCompleted(params: JSONObject): ServerEvent.AccountLoginCompleted =
    ServerEvent.AccountLoginCompleted(
        success = JsonContract.requiredBoolean(params, "success"),
        loginId = JsonContract.optionalString(params, "loginId", 512),
        error = JsonContract.optionalString(params, "error", 16_384),
    )

private fun parseAccountUpdated(params: JSONObject): ServerEvent.AccountUpdated =
    ServerEvent.AccountUpdated(
        authMode = JsonContract.optionalString(params, "authMode", 64)
            ?.let(AccountAuthMode::fromWire),
        planType = JsonContract.optionalString(params, "planType", 128)
            ?.let(AccountPlanType::fromWire),
    )

private fun parseTurn(value: JSONObject): TurnSnapshot {
    val items = JsonContract.requiredArray(value, "items")
    if (items.length() > ProtocolLimits.MAX_JSON_CONTAINER_ENTRIES) {
        throw FrameLimitException("Turn contains too many items")
    }
    val errorMessage = if (!value.has("error") || value.isNull("error")) {
        null
    } else {
        JsonContract.requiredString(
            JsonContract.requiredObject(value, "error"),
            "message",
            16_384,
        )
    }
    return TurnSnapshot(
        id = requiredId(value, "id"),
        status = TurnStatus.fromWire(JsonContract.requiredString(value, "status", 64)),
        errorMessage = errorMessage,
        startedAtSeconds = JsonContract.optionalLong(value, "startedAt"),
        completedAtSeconds = JsonContract.optionalLong(value, "completedAt"),
    )
}

private fun parseStreamItem(value: JSONObject): StreamItem {
    val id = requiredId(value, "id")
    return when (val type = JsonContract.requiredString(value, "type", 128)) {
        "agentMessage" -> StreamItem.AgentMessage(
            id = id,
            text = JsonContract.requiredString(
                value,
                "text",
                ProtocolLimits.MAX_STRING_BYTES,
                allowBlank = true,
            ),
            phase = JsonContract.optionalString(value, "phase", 64)
                ?.let(AgentMessagePhase::fromWire),
        )
        "commandExecution" -> {
            JsonContract.requiredArray(value, "commandActions")
            JsonContract.requiredString(value, "cwd", ProtocolLimits.MAX_PATH_CHARS)
            StreamItem.Command(
                id = id,
                command = JsonContract.requiredString(value, "command", 128 * 1024),
                status = parseToolStatus(value, allowDeclined = true),
                aggregatedOutput = JsonContract.optionalString(
                    value,
                    "aggregatedOutput",
                    ProtocolLimits.MAX_STRING_BYTES,
                ),
            )
        }
        "mcpToolCall" -> {
            if (!value.has("arguments")) {
                throw MalformedEnvelopeException("MCP tool item is missing arguments")
            }
            StreamItem.McpTool(
                id = id,
                server = JsonContract.requiredString(value, "server", 512),
                tool = JsonContract.requiredString(value, "tool", 512),
                status = parseToolStatus(value, allowDeclined = false),
            )
        }
        "dynamicToolCall" -> {
            if (!value.has("arguments")) {
                throw MalformedEnvelopeException("Dynamic tool item is missing arguments")
            }
            StreamItem.DynamicTool(
                id = id,
                tool = JsonContract.requiredString(value, "tool", 512),
                status = parseToolStatus(value, allowDeclined = false),
            )
        }
        else -> StreamItem.Other(id, type, value.toString())
    }
}

private fun parseToolStatus(value: JSONObject, allowDeclined: Boolean): ToolStatus {
    val status = ToolStatus.fromWire(JsonContract.requiredString(value, "status", 64))
    if (!allowDeclined && status == ToolStatus.DECLINED) {
        throw UnsupportedProtocolValueException(
            "Status 'declined' is valid only for commandExecution items",
        )
    }
    return status
}

private fun requiredId(value: JSONObject, key: String): String = JsonContract.requiredString(
    value,
    key,
    ProtocolLimits.MAX_OPAQUE_ID_CHARS,
).also { requireOpaqueId(it, key) }

private fun Long.requireNonNegative(label: String): Long {
    if (this < 0) throw MalformedEnvelopeException("$label must not be negative")
    return this
}
