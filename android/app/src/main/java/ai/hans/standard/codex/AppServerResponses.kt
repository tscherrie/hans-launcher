package ai.hans.standard.codex

import org.json.JSONArray
import org.json.JSONObject

sealed interface AppServerResult

/** Strictly decoded extension payload owned by a feature domain such as plugins. */
class ExtensionAppServerResult internal constructor(
    val payload: Any,
) : AppServerResult

data class InitializeResult(
    val userAgent: String,
    val codexHome: String,
    val platformFamily: String,
    val platformOs: String,
) : AppServerResult

sealed interface AccountIdentity {
    data object ApiKey : AccountIdentity
    data class ChatGpt(val email: String?, val planType: String) : AccountIdentity
    data object AmazonBedrock : AccountIdentity
}

data class AccountReadResult(
    val account: AccountIdentity?,
    val requiresOpenAiAuth: Boolean,
) : AppServerResult

data class DeviceCodeLoginResult(
    val loginId: String,
    val userCode: String,
    val verificationUrl: String,
) : AppServerResult

data class ModelListResult(
    val catalog: ModelCatalog,
    val nextCursor: String?,
    val requestedCursor: String? = null,
) : AppServerResult

data class ThreadStartResult(
    val threadId: String,
    val effectiveModel: String,
    val effectiveEffort: ReasoningEffort?,
    val effectiveServiceTier: String?,
    val requestedOptions: DispatchOptions,
) : AppServerResult {
    init {
        requireOpaqueId(threadId, "Thread id")
        requireWireToken(effectiveModel, "Effective model")
        effectiveServiceTier?.let { requireWireToken(it, "Effective service tier") }
    }
}

data object ThreadMemoryModeSetResult : AppServerResult

/** Receipt of the operation only; effective settings require thread/settings/updated. */
data object ThreadSettingsUpdateResult : AppServerResult

enum class TurnStatus(val wireValue: String) {
    COMPLETED("completed"),
    INTERRUPTED("interrupted"),
    FAILED("failed"),
    IN_PROGRESS("inProgress");

    companion object {
        fun fromWire(value: String): TurnStatus = entries.firstOrNull { it.wireValue == value }
            ?: throw UnsupportedProtocolValueException("Unknown turn status '$value'")
    }
}

data class TurnStartResult(
    val threadId: String,
    val turnId: String,
    val status: TurnStatus,
    val effectiveOptions: DispatchOptions,
) : AppServerResult {
    fun asActiveTurn(): ActiveTurn = ActiveTurn(threadId, turnId, effectiveOptions)
}

data class TurnSteerResult(
    val threadId: String,
    val turnId: String,
    /** Null for identity-only resume: the steer receipt does not report model settings. */
    val effectiveOptions: DispatchOptions?,
) : AppServerResult

data class RemoteError(
    val code: Long,
    val message: String,
    val dataJson: String?,
)

sealed interface CorrelatedResponse {
    val id: RequestId
    val method: AppServerMethod

    data class Success(
        override val id: RequestId,
        override val method: AppServerMethod,
        val result: AppServerResult,
    ) : CorrelatedResponse

    data class Failure(
        override val id: RequestId,
        override val method: AppServerMethod,
        val error: RemoteError,
    ) : CorrelatedResponse
}

/**
 * Correlates replies with outstanding requests and parses only the result type
 * belonging to that request. Unknown, duplicate and malformed replies never
 * mutate confirmed runtime state.
 */
class ResponseCorrelator(
    private val maxPending: Int = ProtocolLimits.MAX_PENDING_REQUESTS,
) {
    private val pending = LinkedHashMap<RequestId, EncodedRequest>()

    init {
        require(maxPending in 1..ProtocolLimits.MAX_PENDING_REQUESTS)
    }

    @Synchronized
    fun register(request: EncodedRequest) {
        if (pending.size >= maxPending) {
            throw FrameLimitException("Too many pending App Server requests")
        }
        if (pending.putIfAbsent(request.id, request) != null) {
            throw CrossCorrelationException("Request id is already pending")
        }
    }

    @Synchronized
    fun accept(raw: String): CorrelatedResponse {
        val envelope = JsonContract.parseObject(raw, ProtocolLimits.MAX_INBOUND_FRAME_BYTES)
        JsonContract.requireOnlyKeys(
            envelope,
            setOf("id", "result", "error", "jsonrpc"),
            "response envelope",
        )
        if (envelope.has("jsonrpc") && envelope.opt("jsonrpc") != "2.0") {
            throw MalformedEnvelopeException("Unsupported jsonrpc version")
        }
        val id = parseRequestId(envelope)
        val request = pending[id]
            ?: throw CrossCorrelationException("Response id is not pending")
        val hasResult = envelope.has("result") && !envelope.isNull("result")
        val hasError = envelope.has("error") && !envelope.isNull("error")
        if (hasResult == hasError) {
            throw MalformedEnvelopeException(
                "Response must contain exactly one of result or error",
            )
        }
        val response = if (hasError) {
            CorrelatedResponse.Failure(
                id = id,
                method = request.method,
                error = parseRemoteError(JsonContract.requiredObject(envelope, "error")),
            )
        } else {
            CorrelatedResponse.Success(
                id = id,
                method = request.method,
                result = parseResult(
                    request,
                    JsonContract.requiredObject(envelope, "result"),
                ),
            )
        }
        pending.remove(id)
        return response
    }

    @Synchronized
    fun pendingCount(): Int = pending.size

    /**
     * For an optional feature domain, a response may be rejected by that
     * domain's strict decoder without invalidating the core App Server
     * session. Forgetting the request here makes that isolation explicit and
     * prevents an unusable pending entry from surviving indefinitely.
     */
    @Synchronized
    fun discard(id: RequestId): Boolean = pending.remove(id) != null
}

private fun parseRequestId(envelope: JSONObject): RequestId {
    if (!envelope.has("id") || envelope.isNull("id")) {
        throw MalformedEnvelopeException("Response is missing id")
    }
    return when (val value = envelope.opt("id")) {
        is Byte -> RequestId.Number(value.toLong())
        is Short -> RequestId.Number(value.toLong())
        is Int -> RequestId.Number(value.toLong())
        is Long -> RequestId.Number(value)
        is String -> RequestId.Text(value)
        else -> throw MalformedEnvelopeException("Response id must be an integer or string")
    }
}

private fun parseRemoteError(error: JSONObject): RemoteError {
    JsonContract.requireOnlyKeys(error, setOf("code", "message", "data"), "error")
    val code = when (val value = error.opt("code")) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> throw MalformedEnvelopeException("Error code must be an integer")
    }
    val data = if (!error.has("data") || error.isNull("data")) {
        null
    } else {
        error.get("data").toString()
    }
    return RemoteError(
        code = code,
        message = JsonContract.requiredString(error, "message", 16_384),
        dataJson = data,
    )
}

private fun parseResult(request: EncodedRequest, result: JSONObject): AppServerResult =
    when (request.method) {
        AppServerMethod.INITIALIZE -> parseInitialize(result)
        AppServerMethod.ACCOUNT_READ -> parseAccountRead(result)
        AppServerMethod.ACCOUNT_LOGIN_START -> parseDeviceCodeLogin(result)
        AppServerMethod.ACCOUNT_LOGOUT -> parseAccountLogout(result)
        AppServerMethod.MODEL_LIST -> parseModelList(request, result)
        AppServerMethod.THREAD_START -> parseThreadStart(request, result)
        AppServerMethod.THREAD_RESUME -> parseThreadResume(request, result)
        AppServerMethod.THREAD_SETTINGS_UPDATE -> parseThreadSettingsUpdate(request, result)
        AppServerMethod.THREAD_MEMORY_MODE_SET -> parseThreadMemoryModeSet(result)
        AppServerMethod.THREAD_LIST -> parseThreadList(result)
        AppServerMethod.TURN_START -> parseTurnStart(request, result)
        AppServerMethod.TURN_STEER -> parseTurnSteer(request, result)
        AppServerMethod.TURN_INTERRUPT -> parseTurnInterrupt(request, result)
        AppServerMethod.SKILLS_LIST -> parseSkillsList(result)
        AppServerMethod.PLUGIN_LIST,
        AppServerMethod.PLUGIN_READ,
        AppServerMethod.PLUGIN_INSTALL,
        AppServerMethod.PLUGIN_UNINSTALL,
        AppServerMethod.MARKETPLACE_ADD,
        AppServerMethod.MARKETPLACE_UPGRADE,
        AppServerMethod.APP_LIST,
        AppServerMethod.SKILLS_CONFIG_WRITE,
        -> ExtensionAppServerResult(
            request.extensionResultDecoder?.decode(result)
                ?: throw CrossCorrelationException(
                    "Extension request is missing its result decoder",
                ),
        )
    }

private fun parseInitialize(result: JSONObject): InitializeResult = InitializeResult(
    userAgent = JsonContract.requiredString(result, "userAgent", 512),
    codexHome = JsonContract.requiredString(result, "codexHome", ProtocolLimits.MAX_PATH_CHARS),
    platformFamily = JsonContract.requiredString(result, "platformFamily", 64),
    platformOs = JsonContract.requiredString(result, "platformOs", 64),
)

private fun parseAccountRead(result: JSONObject): AccountReadResult {
    val account = if (!result.has("account") || result.isNull("account")) {
        null
    } else {
        val value = JsonContract.requiredObject(result, "account")
        when (val type = JsonContract.requiredString(value, "type", 64)) {
            "apiKey" -> AccountIdentity.ApiKey
            "chatgpt" -> AccountIdentity.ChatGpt(
                email = JsonContract.optionalString(value, "email", 512),
                planType = JsonContract.requiredString(value, "planType", 64),
            )
            "amazonBedrock" -> AccountIdentity.AmazonBedrock
            else -> throw UnsupportedProtocolValueException("Unknown account type '$type'")
        }
    }
    return AccountReadResult(
        account = account,
        requiresOpenAiAuth = JsonContract.requiredBoolean(result, "requiresOpenaiAuth"),
    )
}

private fun parseDeviceCodeLogin(result: JSONObject): DeviceCodeLoginResult {
    val type = JsonContract.requiredString(result, "type", 64)
    if (type != "chatgptDeviceCode") {
        throw UnsupportedProtocolValueException(
            "Expected chatgptDeviceCode login response, got '$type'",
        )
    }
    return DeviceCodeLoginResult(
        loginId = JsonContract.requiredString(result, "loginId", 512),
        userCode = JsonContract.requiredString(result, "userCode", 128),
        verificationUrl = JsonContract.requiredString(result, "verificationUrl", 4_096),
    )
}

private fun parseAccountLogout(result: JSONObject): AccountLogoutResult {
    JsonContract.requireOnlyKeys(result, emptySet(), "account/logout result")
    return AccountLogoutResult
}

private fun parseThreadMemoryModeSet(result: JSONObject): ThreadMemoryModeSetResult {
    JsonContract.requireOnlyKeys(result, emptySet(), "thread/memoryMode/set result")
    return ThreadMemoryModeSetResult
}

private fun parseThreadSettingsUpdate(
    request: EncodedRequest,
    result: JSONObject,
): ThreadSettingsUpdateResult {
    if (request.context !is RequestContext.ThreadSettingsUpdate) {
        throw CrossCorrelationException("thread/settings/update request context is missing")
    }
    JsonContract.requireOnlyKeys(result, emptySet(), "thread/settings/update result")
    return ThreadSettingsUpdateResult
}

private fun parseModelList(request: EncodedRequest, result: JSONObject): ModelListResult {
    val context = request.context as? RequestContext.ModelList
        ?: throw CrossCorrelationException("model/list request context is missing")
    val data = JsonContract.requiredArray(result, "data")
    if (data.length() > ProtocolLimits.MAX_MODELS_PER_PAGE) {
        throw FrameLimitException("model/list returned too many models")
    }
    val models = buildList(data.length()) {
        repeat(data.length()) { index ->
            add(parseModel(data.requiredObject(index, "model")))
        }
    }
    return ModelListResult(
        catalog = ModelCatalog(models),
        nextCursor = JsonContract.optionalString(result, "nextCursor", 1_024),
        requestedCursor = context.requestedCursor,
    )
}

private fun parseModel(value: JSONObject): CodexModel {
    val effortOptions = JsonContract.requiredArray(value, "supportedReasoningEfforts")
    if (effortOptions.length() > ProtocolLimits.MAX_EFFORTS_PER_MODEL) {
        throw FrameLimitException("Model advertises too many efforts")
    }
    val efforts = buildSet {
        repeat(effortOptions.length()) { index ->
            val option = effortOptions.requiredObject(index, "reasoning effort")
            add(
                ReasoningEffort.of(
                    JsonContract.requiredString(option, "reasoningEffort", 128),
                ),
            )
        }
    }
    val serviceTierArray = value.opt("serviceTiers")?.let {
        when (it) {
            is JSONArray -> it
            JSONObject.NULL -> JSONArray()
            else -> throw MalformedEnvelopeException("Expected array at 'serviceTiers'")
        }
    } ?: JSONArray()
    if (serviceTierArray.length() > ProtocolLimits.MAX_SERVICE_TIERS_PER_MODEL) {
        throw FrameLimitException("Model advertises too many service tiers")
    }
    val tiers = buildList(serviceTierArray.length()) {
        repeat(serviceTierArray.length()) { index ->
            val tier = serviceTierArray.requiredObject(index, "service tier")
            add(
                ModelServiceTier(
                    id = JsonContract.requiredString(tier, "id", 128),
                    name = JsonContract.requiredString(tier, "name", 256),
                    description = JsonContract.requiredString(
                        tier,
                        "description",
                        16_384,
                        allowBlank = true,
                    ),
                ),
            )
        }
    }
    return CodexModel(
        catalogId = JsonContract.requiredString(value, "id", 128),
        wireModel = JsonContract.requiredString(value, "model", 128),
        displayName = JsonContract.requiredString(value, "displayName", 256),
        description = JsonContract.requiredString(
            value,
            "description",
            16_384,
            allowBlank = true,
        ),
        hidden = JsonContract.requiredBoolean(value, "hidden"),
        isDefault = JsonContract.requiredBoolean(value, "isDefault"),
        defaultEffort = ReasoningEffort.of(
            JsonContract.requiredString(value, "defaultReasoningEffort", 128),
        ),
        supportedEfforts = efforts,
        defaultServiceTier = JsonContract.optionalString(value, "defaultServiceTier", 128),
        serviceTiers = tiers,
    )
}

private fun parseThreadStart(request: EncodedRequest, result: JSONObject): ThreadStartResult {
    val context = request.context as? RequestContext.ThreadStart
        ?: throw CrossCorrelationException("thread/start request context is missing")
    val thread = JsonContract.requiredObject(result, "thread")
    return ThreadStartResult(
        threadId = JsonContract.requiredString(
            thread,
            "id",
            ProtocolLimits.MAX_OPAQUE_ID_CHARS,
        ),
        effectiveModel = JsonContract.requiredString(result, "model", 128),
        effectiveEffort = JsonContract.optionalString(result, "reasoningEffort", 128)
            ?.let(ReasoningEffort::of),
        effectiveServiceTier = JsonContract.optionalString(result, "serviceTier", 128),
        requestedOptions = context.requestedOptions,
    )
}

private fun parseTurnStart(request: EncodedRequest, result: JSONObject): TurnStartResult {
    val context = request.context as? RequestContext.TurnStart
        ?: throw CrossCorrelationException("turn/start request context is missing")
    val turn = JsonContract.requiredObject(result, "turn")
    return TurnStartResult(
        threadId = context.threadId,
        turnId = JsonContract.requiredString(
            turn,
            "id",
            ProtocolLimits.MAX_OPAQUE_ID_CHARS,
        ),
        status = TurnStatus.fromWire(JsonContract.requiredString(turn, "status", 64)),
        effectiveOptions = context.requestedOptions,
    )
}

private fun parseTurnSteer(request: EncodedRequest, result: JSONObject): TurnSteerResult {
    val context = request.context as? RequestContext.TurnSteer
        ?: throw CrossCorrelationException("turn/steer request context is missing")
    val turnId = JsonContract.requiredString(
        result,
        "turnId",
        ProtocolLimits.MAX_OPAQUE_ID_CHARS,
    )
    if (turnId != context.expectedTurnId) {
        throw CrossCorrelationException("turn/steer returned a different active turn id")
    }
    return TurnSteerResult(
        threadId = context.threadId,
        turnId = turnId,
        effectiveOptions = context.effectiveOptions,
    )
}

private fun JSONArray.requiredObject(index: Int, label: String): JSONObject =
    opt(index) as? JSONObject
        ?: throw MalformedEnvelopeException("Expected $label object at index $index")
