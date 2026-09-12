package ai.hans.standard.voice

enum class RecordingStopReason {
    USER,
    MAXIMUM_DURATION,
    PERMANENT_AUDIO_FOCUS_LOSS,
}

enum class RecordingFailure {
    PERMISSION_DENIED,
    AUDIO_FOCUS_DENIED,
    AUDIO_CAPTURE_FAILED,
    TRANSCRIPTION_FAILED,
    TRANSCRIPTION_CONFIGURATION_UNCONFIRMED,
    SPEECH_CREDENTIAL_MISSING,
    SPEECH_CREDENTIAL_TEMPORARILY_UNAVAILABLE,
    OPENAI_AUTHENTICATION_FAILED,
    OPENAI_PERMISSION_DENIED,
    OPENAI_QUOTA_EXHAUSTED,
    OPENAI_RATE_LIMITED,
    NETWORK_UNAVAILABLE,
    EMPTY_TRANSCRIPT,
    TRANSCRIPT_PROGRESS_TIMEOUT,
    PROCESS_STOPPED,
    INTERNAL_ERROR,
}

sealed interface RecordingState {
    data object Idle : RecordingState

    data class AwaitingAudioFocus(
        val recordingId: RecordingId,
        val requestedAtMillis: Long,
    ) : RecordingState

    data class Recording(
        val recordingId: RecordingId,
        val startedAtMillis: Long,
        val deadlineMillis: Long,
        val submittedChunkCount: Long,
        val pendingChunkCount: Int,
    ) : RecordingState

    data class Stopping(
        val recordingId: RecordingId,
        val reason: RecordingStopReason,
        val submittedChunkCount: Long,
        val pendingChunkCount: Int,
    ) : RecordingState

    data class Finalizing(
        val recordingId: RecordingId,
        val reason: RecordingStopReason,
        val submittedChunkCount: Long,
        val pendingChunkCount: Int,
        val finishRequested: Boolean,
    ) : RecordingState

    data class Completed(
        val recordingId: RecordingId,
        val reason: RecordingStopReason,
    ) : RecordingState

    data class Failed(
        val recordingId: RecordingId,
        val failure: RecordingFailure,
    ) : RecordingState
}

sealed interface RecordingEffect {
    data class StateChanged(val state: RecordingState) : RecordingEffect

    data class RequestAudioFocus(val recordingId: RecordingId) : RecordingEffect

    data class StartCapture(val recordingId: RecordingId) : RecordingEffect

    data class StopCapture(val recordingId: RecordingId) : RecordingEffect

    data class SubmitAudioChunk(val chunk: PcmAudioChunk) : RecordingEffect

    data class FinishTranscription(val recordingId: RecordingId) : RecordingEffect

    data class CancelTranscription(val recordingId: RecordingId) : RecordingEffect

    data class ReleaseAudioFocus(val recordingId: RecordingId) : RecordingEffect

    data class UserMessageReady(
        val recordingId: RecordingId,
        val transcript: String,
    ) : RecordingEffect

    data class StartRejectedBusy(val activeRecordingId: RecordingId) : RecordingEffect
}

/**
 * Deterministic dictation lifecycle. It contains no Activity, Codex, TTS or
 * Android process assumptions; callers serialize events and execute effects.
 */
class RecordingStateMachine(private val config: DictationRecordingConfig) {
    @Volatile
    private var visibleState: RecordingState = RecordingState.Idle
    private var active: ActiveSession? = null

    fun state(): RecordingState = visibleState

    @Synchronized
    fun start(
        recordingId: RecordingId,
        nowMillis: Long,
        permissionGranted: Boolean,
    ): List<RecordingEffect> {
        require(nowMillis >= 0)
        active?.let {
            return listOf(RecordingEffect.StartRejectedBusy(it.recordingId))
        }
        if (!permissionGranted) {
            return setTerminalState(
                RecordingState.Failed(recordingId, RecordingFailure.PERMISSION_DENIED),
            )
        }
        active = ActiveSession(
            recordingId = recordingId,
            requestedAtMillis = nowMillis,
        )
        return updateStateAnd(
            RecordingState.AwaitingAudioFocus(recordingId, nowMillis),
            RecordingEffect.RequestAudioFocus(recordingId),
        )
    }

    @Synchronized
    fun rejectStart(
        recordingId: RecordingId,
        failure: RecordingFailure,
    ): List<RecordingEffect> {
        active?.let {
            return listOf(RecordingEffect.StartRejectedBusy(it.recordingId))
        }
        return setTerminalState(RecordingState.Failed(recordingId, failure))
    }

    @Synchronized
    fun onAudioFocusRequestResult(
        recordingId: RecordingId,
        result: AudioFocusRequestResult,
        nowMillis: Long,
    ): List<RecordingEffect> {
        require(nowMillis >= 0)
        val session = activeFor(recordingId) ?: return emptyList()
        if (session.phase != Phase.AWAITING_AUDIO_FOCUS) return emptyList()
        if (result == AudioFocusRequestResult.DENIED) {
            return failActive(session, RecordingFailure.AUDIO_FOCUS_DENIED)
        }

        session.phase = Phase.CAPTURING
        session.focusHeld = true
        session.captureStarted = true
        session.sttOpened = true
        session.startedAtMillis = nowMillis
        session.deadlineMillis = safeDeadline(nowMillis, config.maximumDurationMillis)
        return updateStateAnd(
            session.snapshot(),
            RecordingEffect.StartCapture(recordingId),
        )
    }

    @Synchronized
    fun onAudioFocusChanged(
        recordingId: RecordingId,
        change: RecordingAudioFocusChange,
    ): List<RecordingEffect> {
        val session = activeFor(recordingId) ?: return emptyList()
        return if (
            change == RecordingAudioFocusChange.LOST_PERMANENTLY &&
            session.phase == Phase.CAPTURING
        ) {
            requestStop(session, RecordingStopReason.PERMANENT_AUDIO_FOCUS_LOSS)
        } else {
            // Gain, duck and transient loss are advisory. They must never let
            // TTS or an app foreground transition abort microphone capture.
            emptyList()
        }
    }

    @Synchronized
    fun onAudioChunk(
        recordingId: RecordingId,
        pcmBytes: ByteArray,
        capturedAtMillis: Long,
    ): List<RecordingEffect> {
        require(capturedAtMillis >= 0)
        if (pcmBytes.isEmpty()) return emptyList()
        val session = activeFor(recordingId) ?: return emptyList()
        if (session.phase != Phase.CAPTURING && session.phase != Phase.STOPPING) {
            return emptyList()
        }
        if (session.nextChunkIndex == Long.MAX_VALUE) {
            return failActive(session, RecordingFailure.INTERNAL_ERROR)
        }
        val chunk = PcmAudioChunk.create(
            recordingId = recordingId,
            index = session.nextChunkIndex,
            pcmBytes = pcmBytes,
            isFinal = false,
            capturedAtMillis = capturedAtMillis,
        )
        session.pendingChunkIndexes += session.nextChunkIndex
        session.nextChunkIndex += 1
        return updateStateAnd(
            session.snapshot(),
            RecordingEffect.SubmitAudioChunk(chunk),
        )
    }

    @Synchronized
    fun requestStop(
        recordingId: RecordingId,
        reason: RecordingStopReason = RecordingStopReason.USER,
    ): List<RecordingEffect> {
        val session = activeFor(recordingId) ?: return emptyList()
        return when (session.phase) {
            Phase.AWAITING_AUDIO_FOCUS -> {
                active = null
                updateStateAnd(RecordingState.Idle)
            }
            Phase.CAPTURING -> requestStop(session, reason)
            Phase.STOPPING, Phase.FINALIZING -> emptyList()
        }
    }

    /**
     * The coordinator alone knows whether the physical microphone has started.
     * A stop during startup has no final audio callback to await and no text to publish.
     */
    @Synchronized
    fun cancelBeforeCapture(
        recordingId: RecordingId,
        reason: RecordingStopReason,
    ): List<RecordingEffect> {
        val session = activeFor(recordingId) ?: return emptyList()
        if (
            session.nextChunkIndex != 0L ||
            (session.phase != Phase.CAPTURING && session.phase != Phase.STOPPING)
        ) {
            return emptyList()
        }
        val cleanupEffects = mutableListOf<RecordingEffect>()
        if (session.sttOpened) cleanupEffects += RecordingEffect.CancelTranscription(recordingId)
        if (session.focusHeld) cleanupEffects += RecordingEffect.ReleaseAudioFocus(recordingId)
        active = null
        val completed = RecordingState.Completed(recordingId, reason)
        visibleState = completed
        return cleanupEffects + RecordingEffect.StateChanged(completed)
    }

    @Synchronized
    fun onMaximumDurationReached(
        recordingId: RecordingId,
        nowMillis: Long,
    ): List<RecordingEffect> {
        require(nowMillis >= 0)
        val session = activeFor(recordingId) ?: return emptyList()
        if (
            session.phase != Phase.CAPTURING ||
            nowMillis < session.deadlineMillis
        ) {
            return emptyList()
        }
        return requestStop(session, RecordingStopReason.MAXIMUM_DURATION)
    }

    @Synchronized
    fun onCaptureStopped(
        recordingId: RecordingId,
        finalPcmBytes: ByteArray,
        capturedAtMillis: Long,
    ): List<RecordingEffect> {
        require(capturedAtMillis >= 0)
        val session = activeFor(recordingId) ?: return emptyList()
        if (session.phase != Phase.STOPPING) return emptyList()
        val finalChunk = PcmAudioChunk.create(
            recordingId = recordingId,
            index = session.nextChunkIndex,
            pcmBytes = finalPcmBytes,
            isFinal = true,
            capturedAtMillis = capturedAtMillis,
        )
        session.pendingChunkIndexes += session.nextChunkIndex
        session.nextChunkIndex += 1
        session.phase = Phase.FINALIZING
        session.captureStarted = false
        val releaseFocus = session.focusHeld
        session.focusHeld = false
        return buildList {
            add(stateEffect(session.snapshot()))
            if (releaseFocus) add(RecordingEffect.ReleaseAudioFocus(recordingId))
            add(RecordingEffect.SubmitAudioChunk(finalChunk))
        }
    }

    @Synchronized
    fun onChunkAccepted(
        recordingId: RecordingId,
        chunkIndex: Long,
    ): List<RecordingEffect> {
        val session = activeFor(recordingId) ?: return emptyList()
        if (!session.pendingChunkIndexes.remove(chunkIndex)) return emptyList()
        val followUpEffects = mutableListOf<RecordingEffect>()
        if (
            session.phase == Phase.FINALIZING &&
            session.pendingChunkIndexes.isEmpty() &&
            !session.finishRequested
        ) {
            session.finishRequested = true
            followUpEffects += RecordingEffect.FinishTranscription(recordingId)
        }
        return listOf(stateEffect(session.snapshot())) + followUpEffects
    }

    @Synchronized
    fun onChunkRejected(recordingId: RecordingId): List<RecordingEffect> {
        val session = activeFor(recordingId) ?: return emptyList()
        return failActive(session, RecordingFailure.TRANSCRIPTION_FAILED)
    }

    @Synchronized
    fun onFinalTranscript(
        recordingId: RecordingId,
        transcript: String,
    ): List<RecordingEffect> {
        val session = activeFor(recordingId) ?: return emptyList()
        if (session.phase != Phase.FINALIZING || !session.finishRequested) return emptyList()
        val finalText = transcript.trim()
        if (finalText.isEmpty()) return failActive(session, RecordingFailure.EMPTY_TRANSCRIPT)

        val reason = checkNotNull(session.stopReason)
        active = null
        val completed = RecordingState.Completed(recordingId, reason)
        visibleState = completed
        return listOf(
            RecordingEffect.StateChanged(completed),
            RecordingEffect.UserMessageReady(recordingId, finalText),
        )
    }

    @Synchronized
    fun onTranscriptionFailure(recordingId: RecordingId): List<RecordingEffect> {
        val session = activeFor(recordingId) ?: return emptyList()
        return failActive(session, RecordingFailure.TRANSCRIPTION_FAILED)
    }

    /** Called only after the coordinator's monotonic inactivity check has expired. */
    @Synchronized
    fun onTranscriptProgressTimeout(recordingId: RecordingId): List<RecordingEffect> {
        val session = activeFor(recordingId) ?: return emptyList()
        if (session.phase != Phase.CAPTURING) return emptyList()
        return failActive(session, RecordingFailure.TRANSCRIPT_PROGRESS_TIMEOUT)
    }

    @Synchronized
    fun onAudioCaptureFailure(
        recordingId: RecordingId,
        failure: AudioCaptureFailure,
    ): List<RecordingEffect> {
        val session = activeFor(recordingId) ?: return emptyList()
        val mapped = if (failure == AudioCaptureFailure.PERMISSION_DENIED) {
            RecordingFailure.PERMISSION_DENIED
        } else {
            RecordingFailure.AUDIO_CAPTURE_FAILED
        }
        return failActive(session, mapped)
    }

    @Synchronized
    fun onProcessStopping(): List<RecordingEffect> {
        val session = active ?: return emptyList()
        return failActive(session, RecordingFailure.PROCESS_STOPPED)
    }

    @Suppress("UNUSED_PARAMETER")
    @Synchronized
    fun onEnvironmentEvent(event: VoiceEnvironmentEvent): List<RecordingEffect> = emptyList()

    private fun requestStop(
        session: ActiveSession,
        reason: RecordingStopReason,
    ): List<RecordingEffect> {
        session.phase = Phase.STOPPING
        session.stopReason = reason
        session.captureStopRequested = true
        return updateStateAnd(
            session.snapshot(),
            RecordingEffect.StopCapture(session.recordingId),
        )
    }

    private fun failActive(
        session: ActiveSession,
        failure: RecordingFailure,
    ): List<RecordingEffect> {
        val cleanupEffects = mutableListOf<RecordingEffect>()
        if (session.captureStarted && !session.captureStopRequested) {
            session.captureStopRequested = true
            cleanupEffects += RecordingEffect.StopCapture(session.recordingId)
        }
        if (session.sttOpened) {
            cleanupEffects += RecordingEffect.CancelTranscription(session.recordingId)
        }
        if (session.focusHeld) {
            cleanupEffects += RecordingEffect.ReleaseAudioFocus(session.recordingId)
        }
        active = null
        val failed = RecordingState.Failed(session.recordingId, failure)
        visibleState = failed
        return cleanupEffects + RecordingEffect.StateChanged(failed)
    }

    private fun setTerminalState(state: RecordingState): List<RecordingEffect> {
        active = null
        visibleState = state
        return listOf(RecordingEffect.StateChanged(state))
    }

    private fun updateStateAnd(
        state: RecordingState,
        vararg precedingEffects: RecordingEffect,
    ): List<RecordingEffect> {
        visibleState = state
        return listOf(RecordingEffect.StateChanged(state)) + precedingEffects
    }

    private fun stateEffect(state: RecordingState): RecordingEffect.StateChanged {
        visibleState = state
        return RecordingEffect.StateChanged(state)
    }

    private fun activeFor(recordingId: RecordingId): ActiveSession? {
        return active?.takeIf { it.recordingId == recordingId }
    }

    private fun safeDeadline(startedAtMillis: Long, durationMillis: Long): Long {
        return if (Long.MAX_VALUE - startedAtMillis < durationMillis) {
            Long.MAX_VALUE
        } else {
            startedAtMillis + durationMillis
        }
    }

    private enum class Phase {
        AWAITING_AUDIO_FOCUS,
        CAPTURING,
        STOPPING,
        FINALIZING,
    }

    private data class ActiveSession(
        val recordingId: RecordingId,
        val requestedAtMillis: Long,
        var phase: Phase = Phase.AWAITING_AUDIO_FOCUS,
        var startedAtMillis: Long = 0L,
        var deadlineMillis: Long = 0L,
        var nextChunkIndex: Long = 0L,
        val pendingChunkIndexes: MutableSet<Long> = linkedSetOf(),
        var stopReason: RecordingStopReason? = null,
        var finishRequested: Boolean = false,
        var focusHeld: Boolean = false,
        var captureStarted: Boolean = false,
        var captureStopRequested: Boolean = false,
        var sttOpened: Boolean = false,
    ) {
        fun snapshot(): RecordingState = when (phase) {
            Phase.AWAITING_AUDIO_FOCUS -> RecordingState.AwaitingAudioFocus(
                recordingId = recordingId,
                requestedAtMillis = requestedAtMillis,
            )
            Phase.CAPTURING -> RecordingState.Recording(
                recordingId = recordingId,
                startedAtMillis = startedAtMillis,
                deadlineMillis = deadlineMillis,
                submittedChunkCount = nextChunkIndex,
                pendingChunkCount = pendingChunkIndexes.size,
            )
            Phase.STOPPING -> RecordingState.Stopping(
                recordingId = recordingId,
                reason = checkNotNull(stopReason),
                submittedChunkCount = nextChunkIndex,
                pendingChunkCount = pendingChunkIndexes.size,
            )
            Phase.FINALIZING -> RecordingState.Finalizing(
                recordingId = recordingId,
                reason = checkNotNull(stopReason),
                submittedChunkCount = nextChunkIndex,
                pendingChunkCount = pendingChunkIndexes.size,
                finishRequested = finishRequested,
            )
        }
    }
}
