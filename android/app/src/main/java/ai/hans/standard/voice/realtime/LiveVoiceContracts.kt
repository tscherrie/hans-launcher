package ai.hans.standard.voice.realtime

import java.util.concurrent.atomic.AtomicBoolean

/** Coarse, UI-safe phases. No credential or server payload is exposed here. */
enum class LiveVoicePhase {
    IDLE,
    CONNECTING,
    CONFIGURING,
    LISTENING,
    USER_SPEAKING,
    HANS_SPEAKING,
    WAITING_FOR_TASK,
    RECONNECTING,
    FAILED,
    STOPPED,
}

data class LiveVoiceSnapshot(
    val phase: LiveVoicePhase = LiveVoicePhase.IDLE,
    val generation: Long = 0,
    val reconnectAttempt: Int = 0,
    val pendingTaskCount: Int = 0,
    val lastFailureCode: String? = null,
    /** Requested local WebRTC-track state; this never changes Android's global microphone state. */
    val inputMuted: Boolean = false,
    /** Effective, per-call voice resolution; approximate/fallback identities remain explicit. */
    val voiceSelection: LiveVoiceVoiceSelection? = null,
)

data class LiveVoiceFailure(
    val code: String,
    val retryable: Boolean,
) {
    init {
        require(code.matches(Regex("[a-z0-9_]{1,80}"))) { "invalid_failure_code" }
    }
}

/**
 * A short-lived Realtime client secret. Its value is intentionally excluded
 * from equality, data-class generation, and string rendering.
 */
class RealtimeEphemeralCredential internal constructor(
    internal val value: String,
    val expiresAtEpochSeconds: Long?,
) {
    init {
        require(value.isNotBlank()) { "credential_blank" }
        require(value.length <= MAX_CREDENTIAL_CHARACTERS) { "credential_too_large" }
    }

    override fun toString(): String = "RealtimeEphemeralCredential(redacted)"

    companion object {
        const val MAX_CREDENTIAL_CHARACTERS = 4_096

        fun of(value: String, expiresAtEpochSeconds: Long? = null) =
            RealtimeEphemeralCredential(value, expiresAtEpochSeconds)
    }
}

fun interface LiveVoiceCancellation {
    fun cancel()

    companion object {
        val NONE = LiveVoiceCancellation {}
    }
}

interface LiveVoiceCredentialProvider {
    fun request(
        session: LiveVoiceSessionConfig,
        callback: Callback,
    ): LiveVoiceCancellation

    interface Callback {
        fun onCredential(credential: RealtimeEphemeralCredential)
        fun onFailure(failure: LiveVoiceFailure)
    }
}

enum class LiveVoiceTaskRouting {
    AUTO,
    REQUIRED,
}

/**
 * One atomic view of the instructions and routing policy sent to Realtime.
 * [contextIdentity] is local-only deduplication input and is never transmitted.
 */
data class LiveVoiceSessionContext(
    val instructions: String,
    val taskRouting: LiveVoiceTaskRouting = LiveVoiceTaskRouting.AUTO,
    val contextIdentity: String = "",
    /** Active-call state update, without reintroducing the startup persona or welcome. */
    val refreshInstructions: String = instructions,
) {
    init {
        require(instructions.isNotBlank()) { "live_voice_instructions_blank" }
        require(refreshInstructions.isNotBlank()) { "live_voice_refresh_instructions_blank" }
        require(contextIdentity.length <= MAX_CONTEXT_IDENTITY_CHARACTERS) {
            "live_voice_context_identity_too_large"
        }
        require(contextIdentity.none(Char::isISOControl)) {
            "live_voice_context_identity_invalid"
        }
    }

    companion object {
        private const val MAX_CONTEXT_IDENTITY_CHARACTERS = 1_024
    }
}

fun interface LiveVoiceInstructionsProvider {
    fun buildInstructions(): String

    /** Implementations with dynamic workflow state override this to return one coherent view. */
    fun buildSessionContext(): LiveVoiceSessionContext =
        LiveVoiceSessionContext(buildInstructions())
}

/**
 * Generic local work interface. The implementation may use any agent runtime;
 * Live Voice never exposes that implementation detail to the person speaking.
 */
interface LiveVoiceTaskExecutor {
    fun execute(
        request: LiveVoiceTaskRequest,
        listener: Listener,
    ): LiveVoiceTaskHandle

    interface Listener {
        fun onProgress(progress: LiveVoiceTaskProgress)
        fun onCompleted(result: LiveVoiceTaskResult)
        fun onFailure(failure: LiveVoiceTaskFailure)
    }
}

data class LiveVoiceTaskRequest(
    val callId: String,
    val request: String,
) {
    init {
        require(callId.isNotBlank() && callId.length <= 512) { "task_call_id_invalid" }
        require(request.isNotBlank() && request.length <= MAX_TASK_CHARACTERS) {
            "task_request_invalid"
        }
    }

    companion object {
        const val MAX_TASK_CHARACTERS = 32_000
    }
}

data class LiveVoiceTaskProgress(
    val summary: String,
) {
    init {
        require(summary.isNotBlank() && summary.length <= MAX_PROGRESS_CHARACTERS) {
            "task_progress_invalid"
        }
    }

    companion object {
        const val MAX_PROGRESS_CHARACTERS = 2_000
    }
}

data class LiveVoiceTaskResult(
    val output: String,
) {
    init {
        require(output.length <= MAX_OUTPUT_CHARACTERS) { "task_output_too_large" }
    }

    companion object {
        const val MAX_OUTPUT_CHARACTERS = 64_000
    }
}

data class LiveVoiceTaskFailure(
    val code: String,
    val retryable: Boolean,
) {
    init {
        require(code.matches(Regex("[a-z0-9_]{1,80}"))) { "invalid_task_failure_code" }
    }
}

interface LiveVoiceTaskHandle {
    fun cancel()

    companion object {
        val NONE = object : LiveVoiceTaskHandle {
            override fun cancel() = Unit
        }
    }
}

interface LiveVoiceObserver {
    fun onSnapshot(snapshot: LiveVoiceSnapshot) = Unit
    fun onUserTranscript(text: String, isFinal: Boolean) = Unit
    fun onHansTranscript(text: String, isFinal: Boolean) = Unit
    /** First correlated output text, not proof that audio has reached the speaker. */
    fun onHansResponseReady(event: LiveVoiceResponseReady) = Unit
    fun onTaskProgress(callId: String, progress: LiveVoiceTaskProgress) = Unit
    fun onFailure(failure: LiveVoiceFailure) = Unit
}

/** Content-free event identity; the session instance prevents generation reuse after a restart. */
data class LiveVoiceResponseReady(
    val sessionInstanceId: String,
    val generation: Long,
    val responseId: String,
) {
    init {
        require(sessionInstanceId.isNotBlank() && sessionInstanceId.length <= 128)
        require(sessionInstanceId.none(Char::isISOControl))
        require(generation >= 0)
        require(responseId.isNotBlank() && responseId.length <= MAX_RESPONSE_ID_CHARACTERS)
        require(responseId.none(Char::isISOControl))
    }

    companion object {
        const val MAX_RESPONSE_ID_CHARACTERS = 512
    }
}

enum class LiveVoiceAudioDirection { INPUT, OUTPUT }

/** Content-free, local PCM activity; never proof of physical speaker drain or user intent. */
data class LiveVoiceAudioActivity(
    val direction: LiveVoiceAudioDirection,
    val speechActive: Boolean,
    val observedAtNanos: Long,
    /** False invalidates the candidate; unsupported PCM or gaps must never count as silence. */
    val reliable: Boolean = true,
)

interface LiveVoiceTransport {
    /** Live API startup uses a server-side session exchange; no API key enters this boundary. */
    fun connect(setup: LiveSessionSetup, listener: Listener) {
        throw UnsupportedOperationException("live_session_transport_required")
    }

    /** Stops connection audio before enabling physical microphone capture. */
    fun confirmSessionStarted(): Boolean = setInputAudioEnabled(true)

    fun connect(
        credential: RealtimeEphemeralCredential,
        listener: Listener,
    )

    fun sendUtf8(event: String): Boolean

    /**
     * Gates microphone capture while a context update is awaiting acknowledgement.
     * This is deliberately transport-local; it does not renegotiate the WebRTC connection.
     */
    fun setInputAudioEnabled(enabled: Boolean): Boolean

    /**
     * User-facing call mute. Implementations must apply this to their own outgoing media track,
     * never to Android's global microphone state. It is independent from the context-update gate
     * controlled by [setInputAudioEnabled].
     */
    fun setUserInputMuted(muted: Boolean): Boolean = true

    /** Explicit user interruption only; server VAD interruptions do not call this. */
    fun clearOutputAudio(): Boolean

    /** Optional, bounded farewell-only activity observation. No new recording is started. */
    fun setAudioActivityMonitoringEnabled(enabled: Boolean): Boolean = false

    fun close()

    interface Listener {
        fun onOpen()
        fun onEvent(event: String)
        fun onClosed(failure: LiveVoiceFailure?)
        fun onAudioActivity(activity: LiveVoiceAudioActivity) = Unit
    }
}

fun interface LiveVoiceTransportFactory {
    fun create(): LiveVoiceTransport
}

/** Idempotent helper for cancellation implementations backed by a lambda. */
internal class OnceCancellation(
    private val block: () -> Unit,
) : LiveVoiceCancellation, LiveVoiceTaskHandle {
    private val cancelled = AtomicBoolean(false)

    override fun cancel() {
        if (cancelled.compareAndSet(false, true)) block()
    }
}
