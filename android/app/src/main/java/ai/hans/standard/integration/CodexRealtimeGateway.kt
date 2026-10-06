package ai.hans.standard.integration

/** Local media connects through the existing signed-in App Server; no token leaves that server. */
interface CodexRealtimeGateway {
    fun start(offerSdp: String, prompt: String, voice: String?, callbacks: CodexRealtimeCallbacks): CodexRealtimeCall?
    fun start(offerSdp: String, prompt: String, voice: String?, options: CodexRealtimeOptions,
        callbacks: CodexRealtimeCallbacks): CodexRealtimeCall? =
        if (options == CodexRealtimeOptions()) start(offerSdp, prompt, voice, callbacks) else null
    fun interruptCurrentTurn(): Boolean
}

data class CodexRealtimeOptions(
    val delegationAckFiller: Boolean = true,
    val includeStartupContext: Boolean = true,
    /** Controls automatic return speech, NOT the native admission of incoming agent requests. */
    val clientManagedHandoffs: Boolean = false,
)

fun interface CodexRealtimeCall {
    fun stop()
    /**
     * Raw mono PCM16LE at 24 kHz, at most one second per request. True accepts one callback;
     * false rejects without a callback. Success proves native queue admission, NOT consumption.
     * No retry is safe after ambiguous failure; callers must bound their whole audio stream.
     */
    fun appendAudio(base64: String, sampleRateHz: Int, callback: (Result<Unit>) -> Unit): Boolean = false
    /**
     * Supplies already-visible response text to a running native voice session. The ACK proves
     * queue admission only, not speech generation or playback. It is not a no-delegation mode.
     * True accepts exactly one callback; ambiguous failures must not be retried automatically.
     */
    fun appendSpeech(text: String, callback: (Result<Unit>) -> Unit): Boolean = false
    /** One-shot delivery after a confirmed, error-free native close with no handoff observed.
     * True accepts one callback; success means a correlated main-turn start/steer ACK.
     * Failure/ambiguity must never be retried automatically. */
    fun finishUnroutedDictation(text: String, callback: (Result<Unit>) -> Unit): Boolean = false
}

/** Safe classification only: never carries provider text, audio, SDP or account material. */
class CodexRealtimeFailure(val issue: CodexRealtimeIssue) : Exception(issue.name)

interface CodexRealtimeCallbacks {
    /** Local media-instance identity, captured once by the coordinator. Never serialized,
     * inferred from transcript text, or supplied by the user/model. */
    val voiceControlSessionId: String? get() = null
    /** This attempt was definitively rejected before creating a native session. Not implied by
     * a missing handle, timeout or generic error; other sessions may still exist. */
    fun onStartRejected() {}
    /** Server session readiness only, not proof of ICE connection or audible playback. */
    fun onStarted() {}
    fun onRemoteSdp(sdp: String) {}
    /** Display-only; native Codex already owns delegation. Never dispatch this text again. */
    fun onTranscript(role: String, text: String, isFinal: Boolean) {}
    fun onItemStarted(itemId: String, role: String) {}
    /** Canonical segment commit, which can occur before an utterance ends. Not playback proof. */
    fun onItemCompleted(itemId: String, role: String) {}
    fun onItemCompleted(itemId: String, role: String, text: String) { onItemCompleted(itemId, role) }
    /** Delegation was observed, not proof that backing Codex work has finished. */
    fun onHandoff() {}
    /** Lease-scoped main-thread lifecycle; never a global UI-idle inference. */
    fun onWorkState(state: ai.hans.standard.voice.realtime.CodexTaskVoiceWorkState) {}
    /** Exact confirmed native Voice-to-work ownership, not per-utterance audio correlation. */
    fun onWorkBound(scope: ai.hans.standard.voice.realtime.CodexVoiceWorkScope) {}
    fun onError(issue: CodexRealtimeIssue)
    /** Native requested-close barrier only, after queued handoff notifications were drained.
     * This does not prove that any handoff succeeded or that agent work completed. */
    fun onCloseConfirmed() {}
    /** Native close, never a local stop request or empty stop ACK. */
    fun onClosed() {}
}

enum class CodexRealtimeIssue {
    CHATGPT_LOGIN_REQUIRED, SESSION_NOT_READY, ALREADY_ACTIVE, INVALID_REQUEST,
    NOT_AVAILABLE, AUTHENTICATION, USAGE_LIMIT, CONNECTION_FAILED, MALFORMED_RESPONSE,
    TIMED_OUT, SESSION_CHANGED, REMOTE_ACCESS_ACTIVE, RECOVERY_REQUIRED,
}
