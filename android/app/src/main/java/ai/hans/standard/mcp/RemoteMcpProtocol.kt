package ai.hans.standard.mcp

import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exact, bounded metadata advertised for one remote MCP tool.
 *
 * JSON-bearing fields are canonicalized at construction time. This makes equality and approval
 * digests independent of object-key order without dropping annotations, icons, or `_meta` that a
 * policy review may display or use for classification. The object deliberately has a redacted
 * [toString]; remote descriptions and metadata can contain private tenant data.
 */
internal class RemoteMcpTool(
    name: String,
    title: String?,
    description: String?,
    inputSchemaJson: String,
    outputSchemaJson: String?,
    annotationsJson: String? = null,
    iconsJson: String? = null,
    metaJson: String? = null,
) {
    val name: String = name
    val title: String? = title
    val description: String? = description
    val inputSchemaJson: String = canonicalRemoteMcpJsonObject(
        inputSchemaJson,
        MAX_REMOTE_MCP_SCHEMA_BYTES,
    )
    val outputSchemaJson: String? = outputSchemaJson?.let {
        canonicalRemoteMcpJsonObject(it, MAX_REMOTE_MCP_SCHEMA_BYTES)
    }
    val annotationsJson: String? = annotationsJson?.let {
        canonicalRemoteMcpJsonObject(it, MAX_REMOTE_MCP_ANNOTATIONS_BYTES)
    }
    val iconsJson: String? = iconsJson?.let {
        canonicalRemoteMcpJsonArray(it, MAX_REMOTE_MCP_ICONS_BYTES)
    }
    val metaJson: String? = metaJson?.let {
        canonicalRemoteMcpJsonObject(it, MAX_REMOTE_MCP_META_BYTES)
    }

    init {
        require(name.matches(TOOL_NAME))
        title?.let { requireBoundedText(it, 512) }
        description?.let { requireBoundedText(it, 16 * 1024) }
        validateRemoteMcpToolAnnotations(this.annotationsJson)
        validateRemoteMcpToolIcons(this.iconsJson)
    }

    override fun equals(other: Any?): Boolean = other is RemoteMcpTool &&
        name == other.name &&
        title == other.title &&
        description == other.description &&
        inputSchemaJson == other.inputSchemaJson &&
        outputSchemaJson == other.outputSchemaJson &&
        annotationsJson == other.annotationsJson &&
        iconsJson == other.iconsJson &&
        metaJson == other.metaJson

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + inputSchemaJson.hashCode()
        result = 31 * result + (outputSchemaJson?.hashCode() ?: 0)
        result = 31 * result + (annotationsJson?.hashCode() ?: 0)
        result = 31 * result + (iconsJson?.hashCode() ?: 0)
        result = 31 * result + (metaJson?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "RemoteMcpTool(metadata=redacted)"
}

internal data class RemoteMcpInitializeResult(
    val protocolVersion: String,
    val toolsCapability: Boolean,
)

internal data class RemoteMcpDiscoverResult(
    val supportedVersions: Set<String>,
    val toolsCapability: Boolean,
)

internal enum class RemoteMcpProtocolEra {
    MODERN_2026,
    LEGACY_2025,
}

/** One bounded JSON-RPC message plus the routing values mirrored by Streamable HTTP. */
internal class RemoteMcpProtocolMessage(
    val body: ByteArray,
    val method: String,
    val name: String? = null,
    val parameterHeaders: Map<String, String> = emptyMap(),
) {
    init {
        require(body.isNotEmpty())
        require(method.matches(Regex("[a-z][a-z0-9._/-]{0,127}")))
        name?.let { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}"))) }
        require(parameterHeaders.size <= MAX_MCP_PARAMETER_HEADERS)
        parameterHeaders.forEach { (headerName, value) ->
            require(headerName.matches(MCP_HEADER_NAME_TOKEN))
            requireBoundedText(value, MAX_MCP_PARAMETER_HEADER_VALUE_BYTES)
        }
    }

    override fun toString(): String = "RemoteMcpProtocolMessage(body=redacted, routing=redacted)"
}

internal data class RemoteMcpToolsPage(
    val tools: List<RemoteMcpTool>,
    val nextCursor: String?,
)

internal data class RemoteMcpToolResult(
    val isError: Boolean,
    /** Strictly decoded and bounded JSON, safe to project without transport headers/tokens. */
    val resultJson: String,
)

internal sealed interface RemoteMcpResponseEnvelope {
    data class Success(val result: JSONObject) : RemoteMcpResponseEnvelope
    data class Error(val code: Int, val safeMessage: String) : RemoteMcpResponseEnvelope
    data class UnsupportedProtocolVersion(
        val requested: String,
        val supported: Set<String>,
    ) : RemoteMcpResponseEnvelope
    data class ModernProtocolError(val code: Int) : RemoteMcpResponseEnvelope
    data object LegacyEraRequired : RemoteMcpResponseEnvelope
}

internal object RemoteMcpProtocol {
    const val MODERN_PROTOCOL_VERSION = "2026-07-28"
    const val LEGACY_PROTOCOL_VERSION = "2025-06-18"

    /** Compatibility alias for persisted legacy fixtures; new code must select an explicit era. */
    const val PROTOCOL_VERSION = LEGACY_PROTOCOL_VERSION
    private const val JSON_RPC = "2.0"

    fun modernDiscoverRequest(id: Long): RemoteMcpProtocolMessage = request(
        id,
        "server/discover",
        JSONObject(),
        RemoteMcpProtocolEra.MODERN_2026,
    )

    fun initializeRequest(id: Long): RemoteMcpProtocolMessage = request(
        id,
        "initialize",
        JSONObject()
            .put("protocolVersion", LEGACY_PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put(
                "clientInfo",
                JSONObject().put("name", "Hans Standard").put("version", "1"),
            ),
        RemoteMcpProtocolEra.LEGACY_2025,
    )

    fun initializedNotification(): RemoteMcpProtocolMessage = RemoteMcpProtocolMessage(
        body = encode(
            JSONObject()
                .put("jsonrpc", JSON_RPC)
                .put("method", "notifications/initialized"),
        ),
        method = "notifications/initialized",
    )

    fun toolsListRequest(
        id: Long,
        cursor: String?,
        era: RemoteMcpProtocolEra,
    ): RemoteMcpProtocolMessage {
        cursor?.let { requireCursor(it) }
        return request(
            id,
            "tools/list",
            JSONObject().apply { cursor?.let { put("cursor", it) } },
            era,
        )
    }

    fun toolsCallRequest(
        id: Long,
        tool: RemoteMcpTool,
        argumentsJson: String,
        era: RemoteMcpProtocolEra,
    ): RemoteMcpProtocolMessage {
        val name = tool.name
        require(name.matches(TOOL_NAME))
        val arguments = JSONObject(argumentsJson)
        require(arguments.toString().toByteArray(StandardCharsets.UTF_8).size <= 1024 * 1024)
        return request(
            id,
            "tools/call",
            JSONObject().put("name", name).put("arguments", arguments),
            era,
            name,
            if (era == RemoteMcpProtocolEra.MODERN_2026) {
                extractMcpParameterHeaders(tool.inputSchemaJson, arguments)
            } else {
                emptyMap()
            },
        )
    }

    fun decodeEnvelope(
        response: RemoteMcpHttpResponse,
        expectedId: Long,
        maxFrameBytes: Int,
        era: RemoteMcpProtocolEra = RemoteMcpProtocolEra.LEGACY_2025,
    ): RemoteMcpResponseEnvelope {
        if (response.statusCode !in 200..299 && response.statusCode != 400) {
            throw IllegalArgumentException("mcp_http_failure")
        }
        if (response.statusCode == 400 && response.body.isEmpty()) {
            require(era == RemoteMcpProtocolEra.MODERN_2026)
            return RemoteMcpResponseEnvelope.LegacyEraRequired
        }
        val frame = decodeSingleFrame(response, expectedId, maxFrameBytes)
        validateRemoteMcpJsonStructuralDepth(frame)
        val envelope = JSONObject(frame)
        validateRemoteMcpJsonValue(envelope, maxFrameBytes)
        envelope.requireOnly("jsonrpc", "id", "result", "error")
        require(envelope.getString("jsonrpc") == JSON_RPC) { "mcp_jsonrpc_version_mismatch" }
        require(envelope.has("id") && envelope.optLong("id", Long.MIN_VALUE) == expectedId) {
            "mcp_response_correlation_mismatch"
        }
        val hasResult = envelope.has("result") && !envelope.isNull("result")
        val hasError = envelope.has("error") && !envelope.isNull("error")
        require(hasResult.xor(hasError)) { "mcp_response_shape_invalid" }
        if (hasResult) {
            require(response.statusCode in 200..299) { "mcp_http_failure" }
            val result = envelope.getJSONObject("result")
            if (era == RemoteMcpProtocolEra.MODERN_2026) {
                when (result.optString("resultType")) {
                    "complete" -> Unit
                    "input_required" ->
                        throw IllegalArgumentException("mcp_modern_input_required_unsupported")
                    else -> throw IllegalArgumentException("mcp_modern_result_type_unsupported")
                }
                result.remove("resultType")
            }
            return RemoteMcpResponseEnvelope.Success(result)
        }
        val error = envelope.getJSONObject("error")
        error.requireOnly("code", "message", "data")
        require(error.get("code") is Number && error.get("message") is String) {
            "mcp_error_shape_invalid"
        }
        val code = error.getInt("code")
        requireBoundedText(error.getString("message"), 16 * 1024)
        if (
            era == RemoteMcpProtocolEra.MODERN_2026 &&
            response.statusCode == 400 &&
            code == UNSUPPORTED_PROTOCOL_VERSION_ERROR
        ) {
            val data = error.getJSONObject("data")
            data.requireOnly("supported", "requested")
            val requested = data.getString("requested")
            require(requested == MODERN_PROTOCOL_VERSION) {
                "mcp_unsupported_version_uncorrelated"
            }
            val supportedArray = data.getJSONArray("supported")
            require(supportedArray.length() in 1..MAX_SUPPORTED_PROTOCOL_VERSIONS) {
                "mcp_supported_versions_invalid"
            }
            val supported = (0 until supportedArray.length()).mapTo(linkedSetOf()) { index ->
                supportedArray.getString(index).also(::requireProtocolVersion)
            }
            require(supported.size == supportedArray.length()) {
                "mcp_supported_versions_invalid"
            }
            return RemoteMcpResponseEnvelope.UnsupportedProtocolVersion(requested, supported)
        }
        if (era == RemoteMcpProtocolEra.MODERN_2026 && response.statusCode == 400) {
            return if (code == JSON_RPC_METHOD_NOT_FOUND) {
                RemoteMcpResponseEnvelope.LegacyEraRequired
            } else {
                RemoteMcpResponseEnvelope.ModernProtocolError(code)
            }
        }
        require(response.statusCode in 200..299) { "mcp_http_failure" }
        val message = error.optString("message", "remote_error")
        return RemoteMcpResponseEnvelope.Error(
            code = code,
            safeMessage = message.sanitizeRemoteMessage(),
        )
    }

    fun parseDiscover(result: JSONObject): RemoteMcpDiscoverResult {
        validateRemoteMcpJsonValue(result, MAX_REMOTE_MCP_INITIALIZE_BYTES)
        result.requireOnly(
            "supportedVersions",
            "capabilities",
            "_meta",
            "instructions",
            "ttlMs",
            "cacheScope",
        )
        val versions = result.getJSONArray("supportedVersions")
        require(versions.length() in 1..MAX_SUPPORTED_PROTOCOL_VERSIONS) {
            "mcp_supported_versions_invalid"
        }
        val supported = (0 until versions.length()).mapTo(linkedSetOf()) { index ->
            versions.getString(index).also(::requireProtocolVersion)
        }
        require(supported.size == versions.length() && MODERN_PROTOCOL_VERSION in supported) {
            "mcp_modern_version_not_advertised"
        }
        val capabilities = result.getJSONObject("capabilities")
        require(capabilities.keys().asSequence().count() <= 32)
        val tools = capabilities.has("tools") && !capabilities.isNull("tools") &&
            capabilities.get("tools") is JSONObject
        result.optionalObject("_meta")
        result.optionalString("instructions")?.let { requireBoundedText(it, 32 * 1024) }
        validateCacheHints(result)
        return RemoteMcpDiscoverResult(supported, tools)
    }

    fun parseInitialize(result: JSONObject): RemoteMcpInitializeResult {
        validateRemoteMcpJsonValue(result, MAX_REMOTE_MCP_INITIALIZE_BYTES)
        result.requireOnly("protocolVersion", "capabilities", "serverInfo", "instructions")
        val protocolVersion = result.getString("protocolVersion")
        require(protocolVersion == LEGACY_PROTOCOL_VERSION) { "mcp_protocol_version_unsupported" }
        val capabilities = result.getJSONObject("capabilities")
        require(capabilities.keys().asSequence().count() <= 32)
        val tools = if (capabilities.has("tools") && !capabilities.isNull("tools")) {
            capabilities.getJSONObject("tools").also { it.requireOnly("listChanged") }
            true
        } else {
            false
        }
        val serverInfo = result.getJSONObject("serverInfo")
        serverInfo.requireOnly("name", "version", "title", "description", "websiteUrl", "icons")
        requireBoundedText(serverInfo.getString("name"), 512)
        requireBoundedText(serverInfo.getString("version"), 512)
        result.optString("instructions", "").takeIf(String::isNotEmpty)?.let {
            requireBoundedText(it, 32 * 1024)
        }
        return RemoteMcpInitializeResult(protocolVersion, tools)
    }

    fun parseToolsPage(
        result: JSONObject,
        era: RemoteMcpProtocolEra = RemoteMcpProtocolEra.LEGACY_2025,
    ): RemoteMcpToolsPage {
        validateRemoteMcpJsonValue(result, MAX_REMOTE_MCP_TOOLS_PAGE_BYTES)
        if (era == RemoteMcpProtocolEra.MODERN_2026) {
            result.requireOnly("tools", "nextCursor", "_meta", "ttlMs", "cacheScope")
            result.optionalObject("_meta")
            validateCacheHints(result)
        } else {
            result.requireOnly("tools", "nextCursor")
        }
        val array = result.getJSONArray("tools")
        require(array.length() <= 256) { "mcp_tools_page_too_large" }
        val tools = (0 until array.length()).mapNotNull { index ->
            val tool = array.getJSONObject(index)
            tool.requireOnly(
                "name",
                "title",
                "description",
                "inputSchema",
                "outputSchema",
                "annotations",
                "icons",
                "_meta",
            )
            val input = tool.getJSONObject("inputSchema")
            require(input.optString("type", "object") == "object")
            if (era == RemoteMcpProtocolEra.MODERN_2026) {
                try {
                    validateMcpHeaderSchema(input)
                } catch (_: InvalidMcpHeaderSchema) {
                    return@mapNotNull null
                }
            }
            val output = tool.optionalObject("outputSchema")
            RemoteMcpTool(
                name = tool.getString("name"),
                title = tool.optionalString("title"),
                description = tool.optionalString("description"),
                inputSchemaJson = input.toString(),
                outputSchemaJson = output?.toString(),
                annotationsJson = tool.optionalObject("annotations")?.toString(),
                iconsJson = tool.optionalArray("icons")?.toString(),
                metaJson = tool.optionalObject("_meta")?.toString(),
            )
        }
        require(tools.map(RemoteMcpTool::name).distinct().size == tools.size) {
            "mcp_duplicate_tool"
        }
        val cursor = result.optionalString("nextCursor")?.also(::requireCursor)
        return RemoteMcpToolsPage(tools, cursor)
    }

    fun parseToolResult(result: JSONObject): RemoteMcpToolResult {
        validateRemoteMcpJsonValue(result, MAX_REMOTE_MCP_TOOL_RESULT_BYTES)
        result.requireOnly("content", "structuredContent", "isError", "_meta")
        result.optionalObject("_meta")
        val content = result.getJSONArray("content")
        require(content.length() <= 128) { "mcp_tool_content_too_large" }
        repeat(content.length()) { index -> validateContent(content.getJSONObject(index)) }
        if (result.has("structuredContent") && !result.isNull("structuredContent")) {
            val structured = result.get("structuredContent")
            validateRemoteMcpJsonValue(
                JSONObject().put("value", structured),
                1024 * 1024,
            )
        }
        val encoded = result.toString()
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_REMOTE_MCP_TOOL_RESULT_BYTES)
        return RemoteMcpToolResult(result.optBoolean("isError", false), encoded)
    }

    private fun validateContent(content: JSONObject) {
        val type = content.getString("type")
        when (type) {
            "text" -> {
                content.requireOnly("type", "text", "annotations", "_meta")
                requireBoundedText(content.getString("text"), 1024 * 1024)
            }
            "image", "audio" -> {
                content.requireOnly("type", "data", "mimeType", "annotations", "_meta")
                require(content.getString("data").length <= 2 * 1024 * 1024)
                require(content.getString("mimeType").matches(MIME_TYPE))
            }
            "resource_link" -> {
                content.requireOnly(
                    "type", "uri", "name", "title", "description", "mimeType", "size",
                    "icons", "annotations", "_meta",
                )
                requireBoundedText(content.getString("uri"), 8 * 1024)
                requireBoundedText(content.getString("name"), 512)
            }
            "resource" -> {
                content.requireOnly("type", "resource", "annotations", "_meta")
                val resource = content.getJSONObject("resource")
                resource.requireOnly("uri", "mimeType", "text", "blob", "_meta")
                requireBoundedText(resource.getString("uri"), 8 * 1024)
                resource.optionalString("mimeType")?.let { require(it.matches(MIME_TYPE)) }
                val hasText = resource.has("text") && !resource.isNull("text")
                val hasBlob = resource.has("blob") && !resource.isNull("blob")
                require(hasText.xor(hasBlob)) { "mcp_embedded_resource_shape_invalid" }
                if (hasText) {
                    requireBoundedText(resource.getString("text"), 1024 * 1024)
                } else {
                    require(resource.getString("blob").length <= 2 * 1024 * 1024) {
                        "mcp_embedded_resource_too_large"
                    }
                }
            }
            else -> throw IllegalArgumentException("mcp_content_type_unsupported")
        }
    }

    private fun request(
        id: Long,
        method: String,
        params: JSONObject,
        era: RemoteMcpProtocolEra,
        name: String? = null,
        parameterHeaders: Map<String, String> = emptyMap(),
    ): RemoteMcpProtocolMessage {
        require(id > 0)
        if (era == RemoteMcpProtocolEra.MODERN_2026) {
            require(!params.has("_meta")) { "mcp_reserved_meta_collision" }
            params.put("_meta", modernRequestMetadata())
        }
        return RemoteMcpProtocolMessage(
            body = encode(
                JSONObject()
                    .put("jsonrpc", JSON_RPC)
                    .put("id", id)
                    .put("method", method)
                    .put("params", params),
            ),
            method = method,
            name = name,
            parameterHeaders = parameterHeaders,
        )
    }

    private fun modernRequestMetadata(): JSONObject = JSONObject()
        .put("io.modelcontextprotocol/protocolVersion", MODERN_PROTOCOL_VERSION)
        .put(
            "io.modelcontextprotocol/clientInfo",
            JSONObject().put("name", "Hans Standard").put("version", "1"),
        )
        .put("io.modelcontextprotocol/clientCapabilities", JSONObject())

    private fun validateCacheHints(result: JSONObject) {
        require(result.has("ttlMs") && !result.isNull("ttlMs")) { "mcp_cache_ttl_invalid" }
        val rawTtl = result.get("ttlMs")
        require(rawTtl is Number) { "mcp_cache_ttl_invalid" }
        val ttlText = rawTtl.toString()
        require(ttlText.matches(Regex("0|[1-9][0-9]*"))) { "mcp_cache_ttl_invalid" }
        val ttl = runCatching { java.math.BigInteger(ttlText) }
            .getOrElse { throw IllegalArgumentException("mcp_cache_ttl_invalid") }
        require(ttl <= MAX_REMOTE_MCP_CACHE_TTL_MS) { "mcp_cache_ttl_invalid" }
        val scope = result.optionalString("cacheScope")
            ?: throw IllegalArgumentException("mcp_cache_scope_invalid")
        require(scope == "private" || scope == "public") { "mcp_cache_scope_invalid" }
    }

    private fun encode(value: JSONObject): ByteArray = value.toString()
        .toByteArray(StandardCharsets.UTF_8)
        .also { require(it.size <= MAX_REMOTE_MCP_TOOL_RESULT_BYTES) }

    private fun decodeSingleFrame(
        response: RemoteMcpHttpResponse,
        expectedId: Long,
        maxFrameBytes: Int,
    ): String {
        require(response.body.size in 1..maxFrameBytes) { "mcp_frame_size_invalid" }
        val text = response.body.toString(StandardCharsets.UTF_8)
        require(text.toByteArray(StandardCharsets.UTF_8).contentEquals(response.body)) {
            "mcp_frame_not_utf8"
        }
        if (response.contentType.startsWith("application/json", ignoreCase = true)) return text
        require(response.contentType.startsWith("text/event-stream", ignoreCase = true)) {
            "mcp_content_type_unsupported"
        }
        val correlatedResponses = mutableListOf<String>()
        var eventCount = 0
        var eventName: String? = null
        val data = mutableListOf<String>()

        fun flushEvent() {
            if (eventName == null && data.isEmpty()) return
            eventCount += 1
            require(eventCount <= MAX_REMOTE_MCP_SSE_EVENTS) { "mcp_sse_event_limit_exceeded" }
            val exactEventName = eventName ?: "message"
            val payload = data.joinToString("\n").also {
                require(it.toByteArray(StandardCharsets.UTF_8).size <= maxFrameBytes) {
                    "mcp_frame_size_invalid"
                }
            }
            when (exactEventName) {
                "endpoint" -> Unit
                "message" -> {
                    require(payload.isNotEmpty()) { "mcp_sse_frame_invalid" }
                    validateRemoteMcpJsonStructuralDepth(payload)
                    val message = JSONObject(payload)
                    validateRemoteMcpJsonValue(message, maxFrameBytes)
                    require(message.optString("jsonrpc") == JSON_RPC) {
                        "mcp_jsonrpc_version_mismatch"
                    }
                    if (!message.has("id") || message.isNull("id")) {
                        require(message.has("method") && !message.isNull("method")) {
                            "mcp_sse_notification_shape_invalid"
                        }
                    } else {
                        val id = message.get("id")
                        require(id is Number) { "mcp_response_correlation_mismatch" }
                        require(id.toLong() == expectedId) { "mcp_response_correlation_mismatch" }
                        require(
                            (message.has("result") && !message.isNull("result")) xor
                                (message.has("error") && !message.isNull("error")),
                        ) { "mcp_response_shape_invalid" }
                        correlatedResponses += payload
                    }
                }
                else -> throw IllegalArgumentException("mcp_sse_event_unsupported")
            }
            eventName = null
            data.clear()
        }

        text.lineSequence().forEach { line ->
            require(line.length <= maxFrameBytes) { "mcp_sse_line_too_large" }
            when {
                line.isEmpty() -> flushEvent()
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> {
                    require(eventName == null) { "mcp_sse_frame_invalid" }
                    eventName = line.removePrefix("event:").trim()
                }
                line.startsWith("data:") -> data += line.removePrefix("data:").trimStart()
                line.startsWith("id:") || line.startsWith("retry:") -> Unit
                else -> throw IllegalArgumentException("mcp_sse_field_unsupported")
            }
        }
        flushEvent()
        require(correlatedResponses.size == 1) { "mcp_sse_frame_invalid" }
        return correlatedResponses.single()
    }
}

private fun JSONObject.requireOnly(vararg allowed: String) {
    val actual = keys().asSequence().toSet()
    require(actual.all { it in allowed }) { "mcp_json_fields_unsupported" }
}

private fun JSONObject.optionalString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name)

private fun JSONObject.optionalObject(name: String): JSONObject? {
    if (!has(name)) return null
    require(!isNull(name)) { "mcp_json_null_unsupported" }
    return getJSONObject(name)
}

private fun JSONObject.optionalArray(name: String): JSONArray? {
    if (!has(name)) return null
    require(!isNull(name)) { "mcp_json_null_unsupported" }
    return getJSONArray(name)
}

private fun requireCursor(cursor: String) {
    require(cursor.length in 1..2_048 && cursor.none(Char::isISOControl)) { "mcp_cursor_invalid" }
}

private fun requireProtocolVersion(version: String) {
    require(version.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) {
        "mcp_protocol_version_invalid"
    }
}

private fun requireBoundedText(value: String, maxBytes: Int) {
    require(value.toByteArray(StandardCharsets.UTF_8).size <= maxBytes)
    require('\u0000' !in value)
}

private fun String.sanitizeRemoteMessage(): String = lowercase()
    .replace(Regex("[^a-z0-9_.:-]+"), "_")
    .trim('_')
    .take(96)
    .ifEmpty { "remote_error" }

/** Canonicalizes one bounded JSON object, rejecting pathological trees before recursion. */
internal fun canonicalRemoteMcpJsonObject(raw: String, maxBytes: Int): String =
    canonicalRemoteMcpJson(raw, maxBytes, expectObject = true)

/** Canonicalizes one bounded JSON array, rejecting pathological trees before recursion. */
internal fun canonicalRemoteMcpJsonArray(raw: String, maxBytes: Int): String =
    canonicalRemoteMcpJson(raw, maxBytes, expectObject = false)

private fun canonicalRemoteMcpJson(
    raw: String,
    maxBytes: Int,
    expectObject: Boolean,
): String {
    require(raw.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) {
        "mcp_json_size_invalid"
    }
    validateRemoteMcpJsonStructuralDepth(raw)
    val parsed: Any = if (expectObject) JSONObject(raw) else JSONArray(raw)
    validateRemoteMcpJsonValue(parsed, maxBytes)
    val canonical = buildString(raw.length) { appendCanonicalRemoteMcpJson(parsed) }
    require(canonical.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) {
        "mcp_json_size_invalid"
    }
    return canonical
}

/**
 * Applies depth, fan-out, node-count, and encoded-size limits to an already parsed JSON tree.
 * The traversal is iterative and cycle-aware so hostile programmatic JSONObject values also fail
 * closed rather than overflowing the stack in `toString()`.
 */
private fun validateRemoteMcpJsonValue(root: Any, maxBytes: Int) {
    data class Pending(val value: Any, val depth: Int)

    val pending = ArrayDeque<Pending>()
    val containers = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    pending.add(Pending(root, 1))
    var nodeCount = 0
    var encodedBytes = 0L
    fun account(bytes: Long) {
        encodedBytes += bytes
        require(encodedBytes <= maxBytes.toLong()) { "mcp_json_size_invalid" }
    }
    while (pending.isNotEmpty()) {
        val (value, depth) = pending.removeLast()
        require(depth <= MAX_REMOTE_MCP_JSON_DEPTH) { "mcp_json_depth_exceeded" }
        nodeCount += 1
        require(nodeCount <= MAX_REMOTE_MCP_JSON_NODES) { "mcp_json_nodes_exceeded" }
        when (value) {
            is JSONObject -> {
                require(containers.add(value)) { "mcp_json_cycle_or_alias" }
                val keys = value.keys().asSequence().toList()
                require(keys.size <= MAX_REMOTE_MCP_OBJECT_MEMBERS) {
                    "mcp_json_object_too_large"
                }
                account(2L + (keys.size - 1).coerceAtLeast(0) + keys.size)
                keys.forEach { key ->
                    requireBoundedText(key, MAX_REMOTE_MCP_JSON_KEY_BYTES)
                    account(JSONObject.quote(key).toByteArray(StandardCharsets.UTF_8).size.toLong())
                    pending.add(Pending(value.get(key), depth + 1))
                }
            }
            is JSONArray -> {
                require(containers.add(value)) { "mcp_json_cycle_or_alias" }
                require(value.length() <= MAX_REMOTE_MCP_ARRAY_ITEMS) {
                    "mcp_json_array_too_large"
                }
                account(2L + (value.length() - 1).coerceAtLeast(0))
                repeat(value.length()) { index ->
                    pending.add(Pending(value.get(index), depth + 1))
                }
            }
            is String -> {
                requireBoundedText(value, maxBytes)
                account(JSONObject.quote(value).toByteArray(StandardCharsets.UTF_8).size.toLong())
            }
            is Double -> {
                require(value.isFinite()) { "mcp_json_number_invalid" }
                account(value.toString().length.toLong())
            }
            is Float -> {
                require(value.isFinite()) { "mcp_json_number_invalid" }
                account(value.toString().length.toLong())
            }
            is Number -> account(value.toString().toByteArray(StandardCharsets.UTF_8).size.toLong())
            is Boolean -> account(if (value) 4L else 5L)
            JSONObject.NULL -> account(4L)
            else -> throw IllegalArgumentException("mcp_json_value_unsupported")
        }
    }
    require(root is JSONObject || root is JSONArray) { "mcp_json_root_unsupported" }
}

private fun validateRemoteMcpJsonStructuralDepth(raw: String) {
    var depth = 0
    var inString = false
    var escaped = false
    raw.forEach { character ->
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    require(depth <= MAX_REMOTE_MCP_JSON_DEPTH) {
                        "mcp_json_depth_exceeded"
                    }
                }
                '}', ']' -> {
                    depth -= 1
                    require(depth >= 0) { "mcp_json_structure_invalid" }
                }
            }
        }
    }
    require(!inString && depth == 0) { "mcp_json_structure_invalid" }
}

private fun StringBuilder.appendCanonicalRemoteMcpJson(value: Any?) {
    when (value) {
        null, JSONObject.NULL -> append("null")
        is JSONObject -> {
            append('{')
            value.keys().asSequence().toList().sorted().forEachIndexed { index, key ->
                if (index > 0) append(',')
                append(JSONObject.quote(key))
                append(':')
                appendCanonicalRemoteMcpJson(value.get(key))
            }
            append('}')
        }
        is JSONArray -> {
            append('[')
            repeat(value.length()) { index ->
                if (index > 0) append(',')
                appendCanonicalRemoteMcpJson(value.get(index))
            }
            append(']')
        }
        is String -> append(JSONObject.quote(value))
        is Boolean, is Number -> append(value.toString())
        else -> throw IllegalArgumentException("mcp_json_value_unsupported")
    }
}

private fun validateRemoteMcpToolAnnotations(raw: String?) {
    if (raw == null) return
    val value = JSONObject(raw)
    value.optionalString("title")?.let { requireBoundedText(it, 512) }
    listOf("readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint").forEach { key ->
        if (value.has(key)) {
            require(!value.isNull(key) && value.get(key) is Boolean) {
                "mcp_tool_annotation_hint_invalid"
            }
        }
    }
}

private fun validateRemoteMcpToolIcons(raw: String?) {
    if (raw == null) return
    val icons = JSONArray(raw)
    require(icons.length() <= MAX_REMOTE_MCP_TOOL_ICONS) { "mcp_tool_icons_too_large" }
    repeat(icons.length()) { index ->
        val icon = icons.getJSONObject(index)
        requireBoundedText(icon.getString("src"), 8 * 1024)
        icon.optionalString("mimeType")?.let {
            require(it.matches(MIME_TYPE)) { "mcp_tool_icon_mime_invalid" }
        }
        icon.optionalString("theme")?.let {
            require(it == "light" || it == "dark") { "mcp_tool_icon_theme_invalid" }
        }
        if (icon.has("sizes")) {
            require(!icon.isNull("sizes")) { "mcp_tool_icon_sizes_invalid" }
            val sizes = icon.getJSONArray("sizes")
            require(sizes.length() <= MAX_REMOTE_MCP_ICON_SIZES) {
                "mcp_tool_icon_sizes_invalid"
            }
            repeat(sizes.length()) { sizeIndex ->
                requireBoundedText(sizes.getString(sizeIndex), 64)
            }
        }
    }
}

private data class McpHeaderBinding(
    val headerName: String,
    val propertyPath: List<String>,
    val type: String,
)

private class InvalidMcpHeaderSchema : IllegalArgumentException("mcp_header_schema_invalid")

/**
 * Validates every x-mcp-header occurrence, including occurrences hidden under composition,
 * arrays, conditionals or refs. Only a chain of `properties` objects from the schema root is a
 * statically reachable header source under the 2026-07-28 Streamable HTTP contract.
 */
private fun validateMcpHeaderSchema(schema: JSONObject): List<McpHeaderBinding> {
    val bindings = mutableListOf<McpHeaderBinding>()
    val caseInsensitiveNames = linkedSetOf<String>()

    fun visit(value: Any, staticPath: List<String>?) {
        when (value) {
            is JSONObject -> {
                if (value.has("x-mcp-header")) {
                    val path = staticPath?.takeIf { it.isNotEmpty() }
                        ?: throw InvalidMcpHeaderSchema()
                    val header = runCatching { value.getString("x-mcp-header") }
                        .getOrElse { throw InvalidMcpHeaderSchema() }
                    if (!header.matches(MCP_HEADER_NAME_TOKEN)) throw InvalidMcpHeaderSchema()
                    val normalized = header.lowercase()
                    if (!caseInsensitiveNames.add(normalized)) throw InvalidMcpHeaderSchema()
                    val type = runCatching { value.getString("type") }
                        .getOrElse { throw InvalidMcpHeaderSchema() }
                    if (type !in MCP_HEADER_PRIMITIVE_TYPES) throw InvalidMcpHeaderSchema()
                    bindings += McpHeaderBinding(header, path, type)
                }

                value.keys().asSequence().toList().forEach { key ->
                    if (key == "x-mcp-header") return@forEach
                    val child = value.get(key)
                    if (key == "properties" && staticPath != null) {
                        val properties = child as? JSONObject
                        if (properties != null) {
                            properties.keys().asSequence().toList().forEach { property ->
                                visit(properties.get(property), staticPath + property)
                            }
                        } else {
                            visit(child, null)
                        }
                    } else {
                        visit(child, null)
                    }
                }
            }
            is JSONArray -> repeat(value.length()) { index -> visit(value.get(index), null) }
        }
    }

    visit(schema, emptyList())
    if (bindings.size > MAX_MCP_PARAMETER_HEADERS) throw InvalidMcpHeaderSchema()
    return bindings.sortedBy { it.headerName.lowercase() }
}

private fun extractMcpParameterHeaders(
    inputSchemaJson: String,
    arguments: JSONObject,
): Map<String, String> {
    val bindings = validateMcpHeaderSchema(JSONObject(inputSchemaJson))
    val output = linkedMapOf<String, String>()
    for (binding in bindings) {
        var current: Any = arguments
        var present = true
        for ((index, property) in binding.propertyPath.withIndex()) {
            val objectValue = current as? JSONObject
            if (objectValue == null || !objectValue.has(property) || objectValue.isNull(property)) {
                present = false
                break
            }
            current = objectValue.get(property)
            if (index < binding.propertyPath.lastIndex && current !is JSONObject) {
                present = false
                break
            }
        }
        if (!present) continue
        val encoded = when (binding.type) {
            "string" -> current as? String
                ?: throw IllegalArgumentException("mcp_header_argument_type_invalid")
            "boolean" -> (current as? Boolean)?.toString()
                ?: throw IllegalArgumentException("mcp_header_argument_type_invalid")
            "integer" -> canonicalSafeMcpInteger(current)
            else -> error("unreachable")
        }
        requireBoundedText(encoded, MAX_MCP_PARAMETER_HEADER_VALUE_BYTES)
        output[binding.headerName] = encoded
    }
    return output
}

private fun canonicalSafeMcpInteger(value: Any): String {
    val text = value.toString()
    require(text.matches(Regex("-?(0|[1-9][0-9]*)"))) {
        "mcp_header_argument_type_invalid"
    }
    val number = runCatching { java.math.BigInteger(text) }
        .getOrElse { throw IllegalArgumentException("mcp_header_argument_type_invalid") }
    require(number.abs() <= MAX_SAFE_MCP_INTEGER) { "mcp_header_argument_integer_unsafe" }
    return number.toString()
}

private val TOOL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
private val MIME_TYPE = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+", RegexOption.IGNORE_CASE)
private val MCP_HEADER_NAME_TOKEN = Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]+")
private val MCP_HEADER_PRIMITIVE_TYPES = setOf("string", "integer", "boolean")
private const val JSON_RPC_METHOD_NOT_FOUND = -32601
private val MAX_SAFE_MCP_INTEGER = java.math.BigInteger("9007199254740991")
private val MAX_REMOTE_MCP_CACHE_TTL_MS = java.math.BigInteger.valueOf(Long.MAX_VALUE)
private const val MAX_REMOTE_MCP_SCHEMA_BYTES = 256 * 1024
private const val MAX_REMOTE_MCP_ANNOTATIONS_BYTES = 64 * 1024
private const val MAX_REMOTE_MCP_ICONS_BYTES = 64 * 1024
private const val MAX_REMOTE_MCP_META_BYTES = 64 * 1024
private const val MAX_REMOTE_MCP_INITIALIZE_BYTES = 256 * 1024
private const val MAX_REMOTE_MCP_TOOLS_PAGE_BYTES = 2 * 1024 * 1024
private const val MAX_REMOTE_MCP_TOOL_RESULT_BYTES = 2 * 1024 * 1024
private const val MAX_REMOTE_MCP_SSE_EVENTS = 128
private const val MAX_REMOTE_MCP_JSON_DEPTH = 32
private const val MAX_REMOTE_MCP_JSON_NODES = 65_536
private const val MAX_REMOTE_MCP_OBJECT_MEMBERS = 1_024
private const val MAX_REMOTE_MCP_ARRAY_ITEMS = 4_096
private const val MAX_REMOTE_MCP_JSON_KEY_BYTES = 8 * 1024
private const val MAX_REMOTE_MCP_TOOL_ICONS = 64
private const val MAX_REMOTE_MCP_ICON_SIZES = 32
private const val MAX_SUPPORTED_PROTOCOL_VERSIONS = 16
private const val UNSUPPORTED_PROTOCOL_VERSION_ERROR = -32022
private const val MAX_MCP_PARAMETER_HEADERS = 64
private const val MAX_MCP_PARAMETER_HEADER_VALUE_BYTES = 8 * 1024
