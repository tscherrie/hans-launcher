package ai.hans.standard.voice.realtime

import ai.hans.standard.voice.openai.OpenAiApiFailureClassifier
import org.json.JSONArray
import org.json.JSONObject

/**
 * GPT-Live WebRTC/client-delegation wire contract. This is intentionally separate from Realtime.
 * Sources: /api/docs/guides/live-conversations, /live-delegation and /api/reference/resources/live.
 */
object OpenAiLiveProtocol {
    const val MODEL = "gpt-live-1"
    const val MAX_EVENT_BYTES = 256_000
    const val MAX_SDP_BYTES = 256_000
    const val MAX_APPEND_BYTES = 500
    const val MAX_INSTRUCTIONS_BYTES = 65_536
    const val MAX_HISTORY_MESSAGES = 128
    // Conservative local text budget; the service also validates rendered startup-history tokens.
    const val MAX_HISTORY_BYTES = 8_192
    private const val MAX_TRANSCRIPT_CHARACTERS = 32_000
    private val safeIdentifier = Regex("[A-Za-z0-9._:-]{1,512}")

    val supportedVoices: Set<String> = setOf(
        "alloy", "ash", "ballad", "beacon", "bossa", "cedar", "cinder", "coral",
        "delta", "echo", "gleam", "marin", "meridian", "quartz", "ripple", "sage",
        "shimmer", "stone", "tempo", "verse", "vesper", "willow",
    )

    private val clientEvents = listOf(
        "session.instructions.append", "session.thinking.append", "session.commentary.append",
        "session.input_audio.mute", "session.input_audio.unmute", "session.close",
    )
    private val appendAcks = setOf(
        "session.instructions.appended", "session.thinking.appended", "session.commentary.appended",
    )
    private val muteAcks = setOf("session.input_audio.muted", "session.input_audio.unmuted")
    private val serverEvents = listOf(
        "session.started", "session.closed", "session.delegation.created",
        "session.input_transcript.delta", "session.output_transcript.delta",
        "session.instructions.appended", "session.thinking.appended", "session.commentary.appended",
        "session.input_audio.muted", "session.input_audio.unmuted", "session.usage.updated",
        "error", "info",
    )

    fun sessionCreate(setup: LiveSessionSetup, offerSdp: String): String {
        require(setup.instructions.isNotBlank()) { "live_instructions_blank" }
        require(setup.instructions.utf8Size() <= MAX_INSTRUCTIONS_BYTES) {
            "live_instructions_too_large"
        }
        require(setup.config.voice in supportedVoices) { "live_voice_unsupported" }
        require(offerSdp.isNotBlank() && offerSdp.utf8Size() <= MAX_SDP_BYTES) { "live_sdp_invalid" }
        require(setup.history.size <= MAX_HISTORY_MESSAGES) { "live_history_too_many_messages" }
        require(setup.history.sumOf { it.text.utf8Size().toLong() } <= MAX_HISTORY_BYTES) {
            "live_history_too_large"
        }
        val input = JSONArray()
        setup.history.forEach { message ->
            require(message.text.isNotBlank()) { "live_history_message_blank" }
            input.put(
                JSONObject()
                    .put("type", "message")
                    .put("role", message.role.wireValue)
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put(
                                    "type",
                                    if (message.role == LiveHistoryRole.ASSISTANT) "output_text" else "input_text",
                                )
                                .put("text", message.text),
                        ),
                    ),
            )
        }
        val allowedServerEvents = JSONArray()
        serverEvents.forEach { allowedServerEvents.put(JSONObject().put("type", it)) }
        val session = JSONObject()
            // Live is not a model alias on the Realtime endpoint.
            .put("model", MODEL)
            .put("instructions", setup.instructions)
            .put("delegation", JSONObject().put("type", "client"))
            .put("audio", JSONObject().put("output", JSONObject().put("voice", setup.config.voice)))
            .put("input", input)
            .put("store", false)
            .put(
                "client",
                JSONObject().put(
                    "data_channel",
                    JSONObject()
                        .put("allowed_client_events", JSONArray(clientEvents))
                        .put("allowed_server_events", allowedServerEvents),
                ),
            )
        return JSONObject()
            .put("session", session)
            .put("transport", JSONObject().put("type", "webrtc").put("sdp", offerSdp))
            .toString()
    }

    fun parseSessionAnswer(raw: String): LiveSessionAnswer? = runCatching {
        require(raw.length <= MAX_EVENT_BYTES && raw.utf8Size() <= MAX_EVENT_BYTES)
        val root = JSONObject(raw)
        val sessionId = root.getJSONObject("session").identifier("id")
        val transport = root.getJSONObject("transport")
        require(transport.string("type") == "webrtc")
        val sdp = transport.string("sdp")
        require(sdp.isNotBlank() && sdp.utf8Size() <= MAX_SDP_BYTES)
        LiveSessionAnswer(sessionId, sdp)
    }.getOrNull()

    fun instructionsAppend(content: String, eventId: String, delegationId: String? = null): String =
        append("session.instructions.append", content, eventId, delegationId)

    fun thinkingAppend(content: String, eventId: String, delegationId: String? = null): String =
        append("session.thinking.append", content, eventId, delegationId)

    fun commentaryAppend(content: String, eventId: String, delegationId: String? = null): String =
        append("session.commentary.append", content, eventId, delegationId)

    /**
     * Exact-content, code-point-safe chunks. Each <=500 UTF-8 bytes is a conservative bound for
     * the API's 500-token content limit. Callers decide whether/how many chunks should be spoken.
     */
    fun contentChunks(text: String): List<String> {
        require(text.isNotBlank()) { "live_append_blank" }
        val chunks = mutableListOf<String>()
        var start = 0
        var index = 0
        var bytes = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            require(codePoint !in 0xD800..0xDFFF) { "live_append_invalid_unicode" }
            val width = Character.charCount(codePoint)
            val codePointBytes = when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
            if (bytes + codePointBytes > MAX_APPEND_BYTES) {
                chunks += text.substring(start, index)
                start = index
                bytes = 0
            }
            bytes += codePointBytes
            index += width
        }
        if (start < text.length) chunks += text.substring(start)
        return chunks
    }

    fun mute(eventId: String): String = command("session.input_audio.mute", eventId).toString()
    fun unmute(eventId: String): String = command("session.input_audio.unmute", eventId).toString()
    fun close(eventId: String): String = command("session.close", eventId).toString()

    private fun append(type: String, content: String, eventId: String, delegationId: String?): String {
        require(content.isNotEmpty() && content.utf8Size() <= MAX_APPEND_BYTES) { "live_append_invalid" }
        var index = 0
        while (index < content.length) {
            val codePoint = content.codePointAt(index)
            require(codePoint !in 0xD800..0xDFFF) { "live_append_invalid_unicode" }
            index += Character.charCount(codePoint)
        }
        require(delegationId == null || delegationId.matches(safeIdentifier)) { "live_delegation_id_invalid" }
        return command(type, eventId)
            .put("delegation_id", delegationId ?: JSONObject.NULL)
            .put("content", content)
            .toString()
    }

    private fun command(type: String, eventId: String): JSONObject {
        require(eventId.matches(safeIdentifier)) { "live_event_id_invalid" }
        return JSONObject().put("type", type).put("event_id", eventId)
    }

    fun parseServerEvent(raw: String): LiveServerEvent {
        if (raw.length > MAX_EVENT_BYTES || raw.utf8Size() > MAX_EVENT_BYTES) {
            return invalidEvent("live_event_too_large")
        }
        return try {
            val root = JSONObject(raw)
            val type = root.string("type")
            when {
                type == "session.started" -> {
                    val session = root.getJSONObject("session")
                    val output = session.optJSONObject("audio")?.optJSONObject("output")
                    val voice = output?.optionalString("voice")
                    LiveServerEvent.Started(
                        sessionId = session.identifier("id"),
                        model = session.identifier("model"),
                        voice = voice,
                        expiresAtEpochSeconds = session.optionalNonNegativeNumber("expires_at"),
                        eventId = root.identifier("event_id"),
                    )
                }
                type == "session.closed" -> LiveServerEvent.Closed(
                    sessionId = root.getJSONObject("session").identifier("id"),
                    reason = root.identifier("reason"),
                    usageSeconds = root.getJSONObject("usage").nonNegativeNumber("seconds"),
                    eventId = root.identifier("event_id"),
                    clientEventId = root.optionalIdentifier("client_event_id"),
                )
                type == "session.input_transcript.delta" || type == "session.output_transcript.delta" -> {
                    val text = root.string("delta")
                    require(text.length <= MAX_TRANSCRIPT_CHARACTERS)
                    val start = root.nonNegativeNumber("start_ms")
                    val end = root.nonNegativeNumber("end_ms")
                    require(end >= start)
                    val eventId = root.identifier("event_id")
                    if (type == "session.input_transcript.delta") {
                        LiveServerEvent.InputTranscript(text, eventId, start, end)
                    } else {
                        LiveServerEvent.OutputTranscript(text, eventId, start, end)
                    }
                }
                type == "session.delegation.created" -> {
                    val delegation = root.getJSONObject("delegation")
                    require(delegation.string("type") == "delegation")
                    if (delegation.string("target") != "client") {
                        LiveServerEvent.Ignored
                    } else {
                        LiveServerEvent.Delegation(
                            delegationId = delegation.identifier("id"),
                            offsetMs = root.nonNegativeNumber("offset_ms"),
                            eventId = root.identifier("event_id"),
                        )
                    }
                }
                type in appendAcks || type in muteAcks -> {
                    val start = if (type in appendAcks) root.nonNegativeNumber("start_ms") else null
                    val end = if (type in appendAcks) root.nonNegativeNumber("end_ms") else null
                    require(start == null || end!! >= start)
                    LiveServerEvent.Acknowledged(
                        type = type,
                        clientEventId = root.optionalIdentifier("client_event_id"),
                        eventId = root.identifier("event_id"),
                        startMs = start,
                        endMs = end,
                    )
                }
                type == "session.usage.updated" -> LiveServerEvent.Usage(
                    seconds = root.getJSONObject("usage").nonNegativeNumber("seconds"),
                    contextUsageRatio = root.optJSONObject("context_window")
                        ?.optionalNonNegativeNumber("usage_ratio"),
                    eventId = root.identifier("event_id"),
                )
                type == "error" -> {
                    val error = root.getJSONObject("error")
                    val classified = OpenAiApiFailureClassifier.classifyServerError(
                        error.optionalString("code"), error.optionalString("type"),
                    )
                    LiveServerEvent.Failure(
                        failure = LiveVoiceFailure("live_${classified.code}", classified.retryable),
                        clientEventId = error.optionalIdentifier("client_event_id")
                            ?: root.optionalIdentifier("client_event_id"),
                    )
                }
                // No legacy Realtime, frontend response lifecycle, or audio-drained semantics.
                else -> LiveServerEvent.Ignored
            }
        } catch (_: Exception) {
            invalidEvent("live_event_invalid")
        }
    }

    private fun invalidEvent(code: String): LiveServerEvent.Failure =
        LiveServerEvent.Failure(LiveVoiceFailure(code, retryable = false))

    private fun JSONObject.string(name: String): String =
        (get(name) as? String) ?: throw IllegalArgumentException("live_string_invalid")

    private fun JSONObject.optionalString(name: String): String? =
        if (!has(name) || isNull(name)) null else string(name)

    private fun JSONObject.identifier(name: String): String = string(name).also {
        require(it.matches(safeIdentifier)) { "live_identifier_invalid" }
    }

    private fun JSONObject.optionalIdentifier(name: String): String? =
        if (!has(name) || isNull(name)) null else identifier(name)

    private fun JSONObject.nonNegativeNumber(name: String): Double {
        val value = (get(name) as? Number)?.toDouble()
            ?: throw IllegalArgumentException("live_number_invalid")
        require(value.isFinite() && value >= 0) { "live_number_invalid" }
        return value
    }

    private fun JSONObject.optionalNonNegativeNumber(name: String): Double? =
        if (!has(name) || isNull(name)) null else nonNegativeNumber(name)

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
}
