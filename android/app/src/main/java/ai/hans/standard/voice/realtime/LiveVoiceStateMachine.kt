package ai.hans.standard.voice.realtime

/** Pure lifecycle reducer used both by the controller and deterministic tests. */
internal class LiveVoiceStateMachine(
    private val config: LiveVoiceSessionConfig,
) {
    var snapshot: LiveVoiceSnapshot = LiveVoiceSnapshot()
        private set

    fun start(): List<Action> {
        if (snapshot.phase !in setOf(LiveVoicePhase.IDLE, LiveVoicePhase.STOPPED, LiveVoicePhase.FAILED)) {
            return emptyList()
        }
        snapshot = LiveVoiceSnapshot(
            phase = LiveVoicePhase.CONNECTING,
            generation = snapshot.generation + 1,
        )
        return listOf(Action.AcquireCredential(snapshot.generation))
    }

    fun credentialReady(generation: Long): List<Action> = whenGeneration(generation) {
        snapshot = snapshot.copy(phase = LiveVoicePhase.CONNECTING, lastFailureCode = null)
        listOf(
            Action.Connect(generation),
            Action.Schedule(generation, TimeoutKind.CONNECT, config.connectTimeoutMillis),
        )
    }

    fun transportOpen(generation: Long): List<Action> = whenGeneration(generation) {
        snapshot = snapshot.copy(phase = LiveVoicePhase.CONFIGURING)
        listOf(
            Action.CancelTimeout(TimeoutKind.CONNECT),
            Action.SendSessionConfiguration(generation),
            Action.Schedule(generation, TimeoutKind.CONFIGURE, config.configureTimeoutMillis),
        )
    }

    fun sessionConfigured(generation: Long): List<Action> = whenGeneration(generation) {
        snapshot = snapshot.copy(
            phase = if (snapshot.pendingTaskCount == 0) {
                LiveVoicePhase.LISTENING
            } else {
                LiveVoicePhase.WAITING_FOR_TASK
            },
            reconnectAttempt = 0,
            lastFailureCode = null,
        )
        listOf(
            Action.CancelTimeout(TimeoutKind.CONFIGURE),
            Action.Schedule(generation, TimeoutKind.RENEW, config.sessionRenewalMillis),
        )
    }

    /**
     * WebRTC Realtime owns playout accounting. Server VAD automatically
     * cancels the response and truncates only unheard audio, so this transition
     * deliberately emits no cancel, clear, or truncate action.
     */
    fun userSpeechStarted(generation: Long): List<Action> = whenGeneration(generation) {
        snapshot = snapshot.copy(phase = LiveVoicePhase.USER_SPEAKING)
        listOf(Action.CancelTimeout(TimeoutKind.RESPONSE))
    }

    fun userSpeechStopped(generation: Long): List<Action> = whenGeneration(generation) {
        if (snapshot.pendingTaskCount == 0) {
            snapshot = snapshot.copy(phase = LiveVoicePhase.LISTENING)
        }
        emptyList()
    }

    fun responseStarted(generation: Long): List<Action> = whenGeneration(generation) {
        snapshot = snapshot.copy(phase = LiveVoicePhase.HANS_SPEAKING)
        listOf(Action.Schedule(generation, TimeoutKind.RESPONSE, config.responseTimeoutMillis))
    }

    fun responseFinished(generation: Long): List<Action> = whenGeneration(generation) {
        snapshot = snapshot.copy(
            phase = if (snapshot.pendingTaskCount == 0) {
                LiveVoicePhase.LISTENING
            } else {
                LiveVoicePhase.WAITING_FOR_TASK
            },
        )
        listOf(Action.CancelTimeout(TimeoutKind.RESPONSE))
    }

    fun taskStarted(generation: Long): List<Action> = whenGeneration(generation) {
        snapshot = snapshot.copy(
            phase = LiveVoicePhase.WAITING_FOR_TASK,
            pendingTaskCount = snapshot.pendingTaskCount + 1,
        )
        emptyList()
    }

    fun taskFinished(generation: Long): List<Action> = whenGeneration(generation) {
        val remaining = (snapshot.pendingTaskCount - 1).coerceAtLeast(0)
        snapshot = snapshot.copy(
            // A task callback and response.done are independent streams. Finishing the task must
            // not make the UI claim that Hans is listening while an active response is speaking.
            phase = when (snapshot.phase) {
                LiveVoicePhase.HANS_SPEAKING,
                LiveVoicePhase.USER_SPEAKING -> snapshot.phase
                else -> if (remaining == 0) {
                    LiveVoicePhase.LISTENING
                } else {
                    LiveVoicePhase.WAITING_FOR_TASK
                }
            },
            pendingTaskCount = remaining,
        )
        emptyList()
    }

    fun transportClosed(
        generation: Long,
        failure: LiveVoiceFailure,
    ): List<Action> = whenGeneration(generation) {
        if (!failure.retryable || snapshot.reconnectAttempt >= config.maximumReconnectAttempts) {
            snapshot = snapshot.copy(
                phase = LiveVoicePhase.FAILED,
                lastFailureCode = failure.code,
            )
            listOf(Action.CloseTransport)
        } else {
            val attempt = snapshot.reconnectAttempt + 1
            val delay = reconnectDelay(attempt)
            snapshot = snapshot.copy(
                phase = LiveVoicePhase.RECONNECTING,
                reconnectAttempt = attempt,
                lastFailureCode = failure.code,
            )
            listOf(
                Action.CloseTransport,
                Action.Schedule(generation, TimeoutKind.RECONNECT, delay),
            )
        }
    }

    fun timeout(generation: Long, kind: TimeoutKind): List<Action> = whenGeneration(generation) {
        when (kind) {
            TimeoutKind.RECONNECT -> {
                val nextGeneration = snapshot.generation + 1
                snapshot = snapshot.copy(
                    phase = LiveVoicePhase.CONNECTING,
                    generation = nextGeneration,
                )
                listOf(Action.AcquireCredential(nextGeneration))
            }
            TimeoutKind.RENEW -> {
                val nextGeneration = snapshot.generation + 1
                snapshot = snapshot.copy(
                    phase = LiveVoicePhase.RECONNECTING,
                    generation = nextGeneration,
                )
                listOf(Action.CloseTransport, Action.AcquireCredential(nextGeneration))
            }
            TimeoutKind.CONNECT -> transportClosed(
                generation,
                LiveVoiceFailure("realtime_connect_timeout", true),
            )
            TimeoutKind.CONFIGURE -> transportClosed(
                generation,
                LiveVoiceFailure("realtime_configure_timeout", true),
            )
            TimeoutKind.RESPONSE -> transportClosed(
                generation,
                LiveVoiceFailure("realtime_response_timeout", true),
            )
            TimeoutKind.TASK -> emptyList()
        }
    }

    fun stop(): List<Action> {
        snapshot = snapshot.copy(
            phase = LiveVoicePhase.STOPPED,
            generation = snapshot.generation + 1,
            reconnectAttempt = 0,
            pendingTaskCount = 0,
            lastFailureCode = null,
        )
        return listOf(Action.CancelAllTimeouts, Action.CloseTransport)
    }

    private fun reconnectDelay(attempt: Int): Long {
        var delay = config.reconnectBaseDelayMillis
        repeat((attempt - 1).coerceAtLeast(0)) {
            delay = (delay * 2).coerceAtMost(config.reconnectMaximumDelayMillis)
        }
        return delay
    }

    private inline fun whenGeneration(generation: Long, block: () -> List<Action>): List<Action> =
        if (generation == snapshot.generation && snapshot.phase != LiveVoicePhase.STOPPED) {
            block()
        } else {
            emptyList()
        }

    enum class TimeoutKind {
        CONNECT,
        CONFIGURE,
        RESPONSE,
        TASK,
        RENEW,
        RECONNECT,
    }

    sealed interface Action {
        data class AcquireCredential(val generation: Long) : Action
        data class Connect(val generation: Long) : Action
        data class SendSessionConfiguration(val generation: Long) : Action
        data class Schedule(
            val generation: Long,
            val kind: TimeoutKind,
            val delayMillis: Long,
        ) : Action

        data class CancelTimeout(val kind: TimeoutKind) : Action
        data object CancelAllTimeouts : Action
        data object CloseTransport : Action
    }
}
