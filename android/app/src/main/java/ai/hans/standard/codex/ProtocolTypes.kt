package ai.hans.standard.codex

import org.json.JSONObject

object CodexProtocolContract {
    const val APP_SERVER_VERSION = "0.154.0"
    const val SCHEMA_GENERATION_MODE = "v2"
    const val APP_LIST_UPDATED_NOTIFICATION = "app/list/updated"
}

/**
 * The subset of the Codex App Server 0.154.0 v2 protocol owned by Hans.
 *
 * App Server uses JSON-RPC request/response semantics but intentionally omits
 * the `jsonrpc` member on the wire. Keeping the method set closed makes an
 * upstream schema change fail visibly instead of silently changing behavior.
 */
enum class AppServerMethod(val wireName: String) {
    INITIALIZE("initialize"),
    ACCOUNT_READ("account/read"),
    ACCOUNT_LOGIN_START("account/login/start"),
    ACCOUNT_LOGOUT("account/logout"),
    MODEL_LIST("model/list"),
    THREAD_START("thread/start"),
    THREAD_RESUME("thread/resume"),
    THREAD_SETTINGS_UPDATE("thread/settings/update"),
    THREAD_MEMORY_MODE_SET("thread/memoryMode/set"),
    THREAD_LIST("thread/list"),
    TURN_START("turn/start"),
    TURN_STEER("turn/steer"),
    TURN_INTERRUPT("turn/interrupt"),
    SKILLS_LIST("skills/list"),
    SKILLS_CONFIG_WRITE("skills/config/write"),
    PLUGIN_LIST("plugin/list"),
    PLUGIN_READ("plugin/read"),
    PLUGIN_INSTALL("plugin/install"),
    PLUGIN_UNINSTALL("plugin/uninstall"),
    MARKETPLACE_ADD("marketplace/add"),
    MARKETPLACE_UPGRADE("marketplace/upgrade"),
    APP_LIST("app/list"),
}

sealed interface RequestId {
    data class Number(val value: Long) : RequestId {
        init {
            require(value > 0) { "Numeric request ids must be positive" }
        }
    }

    data class Text(val value: String) : RequestId {
        init {
            require(value.isNotBlank()) { "Text request ids must not be blank" }
            require(value.length <= ProtocolLimits.MAX_REQUEST_ID_CHARS) {
                "Text request id is too long"
            }
            require(value.none { it.isISOControl() }) {
                "Text request id contains control characters"
            }
        }
    }
}

internal sealed interface RequestContext {
    data object None : RequestContext

    data class ThreadStart(
        val requestedOptions: DispatchOptions,
    ) : RequestContext

    data class ThreadResume(
        val threadId: String,
        val requestedInitialTurnsLimit: Int,
    ) : RequestContext {
        init {
            require(requestedInitialTurnsLimit == ProtocolLimits.RECENT_HISTORY_TURN_LIMIT) {
                "Hans thread/resume history must request exactly " +
                    "${ProtocolLimits.RECENT_HISTORY_TURN_LIMIT} turns"
            }
        }
    }

    data class ModelList(
        val requestedCursor: String?,
    ) : RequestContext

    data class ThreadSettingsUpdate(
        val threadId: String,
        val model: String,
        val effort: ReasoningEffort,
        val serviceTier: String?,
    ) : RequestContext

    data class TurnStart(
        val threadId: String,
        val requestedOptions: DispatchOptions,
    ) : RequestContext

    data class TurnSteer(
        val threadId: String,
        val expectedTurnId: String,
        val effectiveOptions: DispatchOptions?,
    ) : RequestContext

    data class TurnInterrupt(
        val threadId: String,
        val turnId: String,
    ) : RequestContext
}

class EncodedRequest internal constructor(
    val id: RequestId,
    val method: AppServerMethod,
    val json: String,
    internal val context: RequestContext = RequestContext.None,
    internal val extensionResultDecoder: ExtensionResultDecoder? = null,
)

internal fun interface ExtensionResultDecoder {
    fun decode(result: JSONObject): Any
}

class RequestIdSequence(startAt: Long = 1L) {
    private var nextValue: Long = startAt

    init {
        require(startAt > 0) { "Request id sequence must start above zero" }
    }

    @Synchronized
    fun next(): RequestId.Number {
        check(nextValue != Long.MAX_VALUE) { "Request id sequence exhausted" }
        return RequestId.Number(nextValue++)
    }
}

open class ProtocolException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class FrameLimitException(message: String) : ProtocolException(message)

class MalformedEnvelopeException(message: String, cause: Throwable? = null) :
    ProtocolException(message, cause)

class CrossCorrelationException(message: String) : ProtocolException(message)

class UnsupportedProtocolValueException(message: String) : ProtocolException(message)

object ProtocolLimits {
    const val MAX_OUTBOUND_FRAME_BYTES = 4 * 1024 * 1024
    const val MAX_INBOUND_FRAME_BYTES = 8 * 1024 * 1024
    const val MAX_EVENT_FRAME_BYTES = 8 * 1024 * 1024
    const val MAX_INPUT_TEXT_BYTES = 1024 * 1024
    const val MAX_STRING_BYTES = 2 * 1024 * 1024
    const val MAX_DEVELOPER_INSTRUCTIONS_BYTES = 512 * 1024
    const val MAX_JSON_DEPTH = 48
    const val MAX_JSON_CONTAINER_ENTRIES = 16_384
    const val MAX_MODELS_PER_PAGE = 256
    const val MAX_MODELS_TOTAL = 1_024
    const val MAX_THREADS_PER_PAGE = 100
    const val MAX_TURNS_PER_PAGE = 256
    /** Recent display history requested during resume; Codex itself defaults this page to 25. */
    const val RECENT_HISTORY_TURN_LIMIT = 25
    const val MAX_RECOVERED_HISTORY_ITEMS = RECENT_HISTORY_TURN_LIMIT * 2
    const val MAX_RECOVERED_HISTORY_INPUT_PARTS = 64
    const val MAX_RECOVERED_HISTORY_MESSAGE_BYTES = 128 * 1024
    const val MAX_RECOVERED_HISTORY_TOTAL_BYTES = 512 * 1024
    const val MAX_SKILL_ROOTS = 32
    const val MAX_SKILLS_TOTAL = 2_048
    const val MAX_PLUGIN_MARKETPLACES = 128
    const val MAX_PLUGINS_PER_MARKETPLACE = 4_096
    const val MAX_PLUGINS_TOTAL = 8_192
    const val MAX_PLUGIN_CAPABILITIES = 256
    const val MAX_APPS_PER_PAGE = 512
    const val MAX_APPS_TOTAL = 2_048
    const val MAX_PLUGIN_OPERATIONS = 64
    const val MAX_MESSAGES_PER_THREAD = 1_024
    const val MAX_TOOLS_PER_THREAD = 512
    const val MAX_DEDUPLICATION_KEYS = 8_192
    const val MAX_UI_MESSAGE_CHARS = 262_144
    const val MAX_UI_TOOL_OUTPUT_CHARS = 65_536
    const val MAX_EFFORTS_PER_MODEL = 16
    const val MAX_SERVICE_TIERS_PER_MODEL = 16
    const val MAX_PENDING_REQUESTS = 256
    const val MAX_REQUEST_ID_CHARS = 128
    const val MAX_OPAQUE_ID_CHARS = 256
    const val MAX_METHOD_CHARS = 128
    const val MAX_PATH_CHARS = 4_096
}
