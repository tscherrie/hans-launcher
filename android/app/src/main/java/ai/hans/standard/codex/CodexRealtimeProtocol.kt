package ai.hans.standard.codex

import ai.hans.standard.integration.CodexRealtimeIssue
import ai.hans.standard.integration.CodexRealtimeOptions
import java.util.Base64
import org.json.JSONObject

/** Pinned App Server 0.155 experimental realtime subset, isolated from core chat parsing. */
internal object CodexRealtimeProtocol {
    const val PREFIX = "hans_realtime_"
    const val EVENT_PREFIX = "thread/realtime/"
    const val MAX_SDP_BYTES = 128 * 1024
    const val MAX_TEXT_BYTES = 64 * 1024
    const val AUDIO_SAMPLE_RATE_HZ = 24_000
    const val MAX_AUDIO_BYTES = AUDIO_SAMPLE_RATE_HZ * 2
    // The schema enum spans all versions. V3 uses the narrower builtin V1 voice list.
    private val voices = setOf("juniper", "maple", "spruce", "ember", "vale", "breeze", "arbor", "sol", "cove")

    fun start(id: String, threadId: String, sessionId: String, sdp: String, prompt: String, voice: String?,
        options: CodexRealtimeOptions = CodexRealtimeOptions()): String {
        requireOpaqueId(threadId, "Thread id")
        requireOpaqueId(sessionId, "Realtime session id")
        require(sdp.isNotBlank() && sdp.startsWith("v=0"))
        JsonContract.requireUtf8Bound(sdp, MAX_SDP_BYTES, "Realtime SDP")
        require(prompt.isNotBlank())
        JsonContract.requireUtf8Bound(prompt, MAX_TEXT_BYTES, "Realtime prompt")
        require(voice == null || voice in voices)
        val params = JSONObject().put("threadId", threadId).put("realtimeSessionId", sessionId)
            .put("transport", JSONObject().put("type", "webrtc").put("sdp", sdp))
            .put("version", "v3").put("model", "gpt-live-1-codex").put("outputModality", "audio")
            .put("prompt", prompt).put("includeStartupContext", options.includeStartupContext)
            .put("clientManagedHandoffs", options.clientManagedHandoffs).put("delegationAckFiller", options.delegationAckFiller)
            // Stopping must never submit an otherwise un-delegated partial utterance.
            .put("flushTranscriptTailOnSessionEnd", false)
        if (voice != null) params.put("voice", voice)
        return request(id, "thread/realtime/start", params)
    }

    fun stop(id: String, threadId: String): String {
        requireOpaqueId(threadId, "Thread id")
        return request(id, "thread/realtime/stop", JSONObject().put("threadId", threadId))
    }

    fun appendSpeech(id: String, threadId: String, text: String): String {
        requireOpaqueId(threadId, "Thread id")
        require(text.isNotBlank())
        JsonContract.requireUtf8Bound(text, MAX_TEXT_BYTES, "Realtime speech")
        return request(id, "thread/realtime/appendSpeech",
            JSONObject().put("threadId", threadId).put("text", text))
    }

    fun appendAudio(id: String, threadId: String, base64: String, sampleRateHz: Int): String {
        requireOpaqueId(threadId, "Thread id")
        // The pinned V3 writer forwards only data; it does not resample from this metadata.
        require(sampleRateHz == AUDIO_SAMPLE_RATE_HZ)
        require(base64.isNotEmpty() && base64.length <= ((MAX_AUDIO_BYTES + 2) / 3) * 4)
        val decoded = Base64.getDecoder().decode(base64)
        try {
            require(decoded.isNotEmpty() && decoded.size <= MAX_AUDIO_BYTES && decoded.size % 2 == 0)
            require(Base64.getEncoder().encodeToString(decoded) == base64)
            val audio = JSONObject().put("data", base64).put("sampleRate", sampleRateHz)
                .put("numChannels", 1).put("samplesPerChannel", decoded.size / 2)
            return request(id, "thread/realtime/appendAudio", JSONObject().put("threadId", threadId).put("audio", audio))
        } finally { decoded.fill(0) }
    }

    private fun request(id: String, method: String, params: JSONObject): String {
        require(id.startsWith(PREFIX))
        return JsonContract.encodeBounded(JSONObject().put("id", id).put("method", method).put("params", params),
            ProtocolLimits.MAX_OUTBOUND_FRAME_BYTES)
    }

    /** Error payloads may contain credentials. Export classifications only, never provider text. */
    fun issue(message: String, code: Int? = null): CodexRealtimeIssue {
        val text = message.take(8_192).lowercase()
        return when {
            code == -32601 || listOf("not supported", "unsupported", "not available", "not enabled", "entitlement", "not entitled").any(text::contains) -> CodexRealtimeIssue.NOT_AVAILABLE
            listOf("insufficient_quota", "usage limit", "rate limit", "rate_limit", "quota exceeded", "out of credits", "429").any(text::contains) -> CodexRealtimeIssue.USAGE_LIMIT
            listOf("unauthorized", "authentication", "not authenticated", "401", "login required").any(text::contains) -> CodexRealtimeIssue.AUTHENTICATION
            else -> CodexRealtimeIssue.CONNECTION_FAILED
        }
    }
}
