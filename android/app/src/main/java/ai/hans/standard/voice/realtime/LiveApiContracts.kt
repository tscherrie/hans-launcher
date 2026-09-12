package ai.hans.standard.voice.realtime

/** Startup-only Live configuration. The frontend model is fixed by [OpenAiLiveProtocol]. */
data class LiveSessionSetup(
    val config: LiveVoiceSessionConfig,
    val instructions: String,
    val history: List<LiveHistoryMessage> = emptyList(),
)

enum class LiveHistoryRole(val wireValue: String) {
    USER("user"),
    ASSISTANT("assistant"),
    DEVELOPER("developer"),
}

data class LiveHistoryMessage(val role: LiveHistoryRole, val text: String)

/** The SDP contains transport material and is deliberately absent from string rendering. */
data class LiveSessionAnswer(val sessionId: String, val sdp: String) {
    override fun toString(): String = "LiveSessionAnswer(sessionId=$sessionId, sdp=redacted)"
}

/** Creates one session from one offer; credentials never cross this boundary. */
interface LiveSessionProvider {
    fun create(
        setup: LiveSessionSetup,
        offerSdp: String,
        callback: Callback,
    ): LiveVoiceCancellation

    interface Callback {
        fun onCreated(answer: LiveSessionAnswer)
        fun onFailure(failure: LiveVoiceFailure)
    }
}

/** Live transcripts describe intervals, not complete turns or confirmed audio playback. */
sealed interface LiveServerEvent {
    data class Started(
        val sessionId: String,
        val model: String,
        val voice: String?,
        val expiresAtEpochSeconds: Double?,
        val eventId: String,
    ) : LiveServerEvent

    data class Closed(
        val sessionId: String,
        val reason: String,
        val usageSeconds: Double,
        val eventId: String,
        val clientEventId: String?,
    ) : LiveServerEvent

    data class InputTranscript(
        val text: String,
        val eventId: String,
        val startMs: Double,
        val endMs: Double,
    ) : LiveServerEvent

    data class OutputTranscript(
        val text: String,
        val eventId: String,
        val startMs: Double,
        val endMs: Double,
    ) : LiveServerEvent

    /** Contains no request text. Build work from application-owned transcript and task state. */
    data class Delegation(
        val delegationId: String,
        val offsetMs: Double,
        val eventId: String,
    ) : LiveServerEvent

    /** Acceptance/context injection only; this never confirms spoken delivery or task success. */
    data class Acknowledged(
        val type: String,
        val clientEventId: String?,
        val eventId: String,
        val startMs: Double? = null,
        val endMs: Double? = null,
    ) : LiveServerEvent

    /** Cumulative snapshots, not amounts to add together. */
    data class Usage(
        val seconds: Double,
        val contextUsageRatio: Double?,
        val eventId: String,
    ) : LiveServerEvent

    data class Failure(
        val failure: LiveVoiceFailure,
        val clientEventId: String? = null,
    ) : LiveServerEvent

    data object Ignored : LiveServerEvent
}
