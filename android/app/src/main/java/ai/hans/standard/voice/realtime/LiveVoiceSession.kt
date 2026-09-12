package ai.hans.standard.voice.realtime

import ai.hans.standard.voice.feedback.LiveResponseReadyTracker
import ai.hans.standard.voice.realtime.LiveVoiceStateMachine.Action
import ai.hans.standard.voice.realtime.LiveVoiceStateMachine.TimeoutKind
import java.util.UUID
import java.text.Normalizer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-scoped Realtime session controller. It has no Activity dependency,
 * so foreground app-control screens do not pause capture or playout. The host
 * should own it from a foreground service when Android process-retention is
 * required while another app remains visible for a long time.
 */
class LiveVoiceSession(
    private val credentialProvider: LiveVoiceCredentialProvider,
    private val transportFactory: LiveVoiceTransportFactory,
    private val taskExecutor: LiveVoiceTaskExecutor,
    /** Must return output from [LiveVoiceContextBuilder], including the persona asset. */
    private val instructionsProvider: LiveVoiceInstructionsProvider,
    private val observer: LiveVoiceObserver = object : LiveVoiceObserver {},
    private val config: LiveVoiceSessionConfig = LiveVoiceSessionConfig(),
    private val voiceSelectionProvider: LiveVoiceVoiceSelectionProvider =
        LiveVoiceVoiceSelectionProvider { LiveVoiceRealtimeVoiceMapper.resolve(config.voice) },
    scheduler: ScheduledExecutorService? = null,
) : AutoCloseable {
    private val ownedScheduler = if (scheduler == null) {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "hans-live-voice").apply { isDaemon = true }
        }
    } else {
        null
    }
    private val scheduler = scheduler ?: ownedScheduler!!
    private val stateMachine = LiveVoiceStateMachine(config)
    private val responseArbiter = LiveVoiceResponseArbiter()
    private val responseReady = LiveResponseReadyTracker(UUID.randomUUID().toString())
    private val closed = AtomicBoolean(false)
    private val userInputMuted = AtomicBoolean(false)
    private val timeoutJobs = mutableMapOf<TimeoutKind, ScheduledFuture<*>>()
    private val taskJobs = mutableMapOf<String, TaskRecord>()
    private val recoveredResults = ArrayDeque<RecoveredResult>()
    private var recoveryBatch: RecoveryBatch? = null
    private var nextRecoveredResultSequence = 1L
    private var recoveryCancellationRequestedByUser = false
    private val handledToolCallIds = LinkedHashSet<String>()
    private val spokenUserItemIds = LinkedHashSet<String>()
    private val finalUserItemIds = LinkedHashSet<String>()
    private var userTurnEpoch = 0L
    private var lastAcceptedTaskTurnEpoch: Long? = null
    private var unmatchedSpeechStartCount = 0
    private var speechInputActive = false
    private var userResponsePending = false
    private var awaitingInitialConfigurationGeneration: Long? = null
    private var contextRefreshInFlight: PendingContextRefresh? = null
    private var desiredSessionContext: LiveVoiceSessionContext? = null
    private var contextRefreshAckTimeout: ScheduledFuture<*>? = null
    private var nextContextRefreshSequence = 1L
    private var sessionContextDirty = false
    private var appliedContextKey: ContextKey? = null
    private var latestFinalUserUtterance: FinalUserUtterance? = null
    private var pendingEndCallAuthorization: PendingEndCallAuthorization? = null
    private var pendingModelHangup: PendingModelHangup? = null
    private val activeOutputAudioResponses = LinkedHashSet<String>()
    private var activeSessionConfig = config
    private var activeVoiceSelection = LiveVoiceRealtimeVoiceMapper.resolve(config.voice)

    @Volatile
    private var publishedSnapshot = stateMachine.snapshot
    private var activeTransport: LiveVoiceTransport? = null
    private var credentialRequest: LiveVoiceCancellation? = null
    private var pendingCredential: Pair<Long, RealtimeEphemeralCredential>? = null

    val snapshot: LiveVoiceSnapshot
        get() = publishedSnapshot

    fun start() = dispatch {
        if (!closed.get()) {
            if (stateMachine.snapshot.phase in STARTABLE_PHASES) resolveCallVoice()
            apply(stateMachine.start())
        }
    }

    /** Rebuilds workflow/chat continuity on the existing Realtime connection without reconnecting. */
    fun refreshContext() = dispatch {
        if (!closed.get()) refreshSessionContext(stateMachine.snapshot.generation)
    }

    /** Mutes only this call's outgoing WebRTC track; Android's microphone stays untouched. */
    fun setInputMuted(muted: Boolean): Boolean {
        if (closed.get() || publishedSnapshot.phase in TERMINAL_PHASES) return false
        val transport = activeTransport
        if (transport != null && !transport.setUserInputMuted(muted)) {
            emitFailure(LiveVoiceFailure("realtime_input_mute_failed", false))
            return false
        }
        userInputMuted.set(muted)
        dispatch { publishSnapshot() }
        return true
    }

    /**
     * Stops voice media but deliberately does not cancel work already accepted
     * by the task runtime. Calling [close] cancels those handles as well.
     */
    fun stop() = dispatch {
        if (!closed.get()) {
            sessionContextDirty = false
            userInputMuted.set(false)
            activeTransport?.setUserInputMuted(false)
            apply(stateMachine.stop())
        }
    }

    /**
     * Explicit user interruption. Normal VAD speech interruption never calls
     * this; the WebRTC Realtime service handles exact unheard-audio truncation.
     */
    fun interruptHans() = dispatch {
        if (publishedSnapshot.phase == LiveVoicePhase.HANS_SPEAKING) {
            responseReady.cancelActive()
            if (responseArbiter.isActive(LiveVoiceResponseArbiter.Purpose.RECOVERED_RESULT)) {
                // User interruption stops current audio, but never acknowledges or discards the
                // recovered task result. A cancelled terminal event retries it on a fresh session.
                recoveryCancellationRequestedByUser = true
            }
            activeTransport?.sendUtf8(OpenAiRealtimeProtocol.responseCancel())
            activeTransport?.clearOutputAudio()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        LiveVoiceDiagnostics.event("SESSION_CLOSE")
        dispatch(allowClosed = true) {
            sessionContextDirty = false
            apply(stateMachine.stop())
            taskJobs.values.forEach {
                it.timeout?.cancel(false)
                it.handle?.cancel()
            }
            taskJobs.clear()
            ownedScheduler?.shutdownNow()
        }
    }

    private fun apply(actions: List<Action>) {
        publishSnapshot()
        actions.forEach { action ->
            when (action) {
                is Action.AcquireCredential -> acquireCredential(action.generation)
                is Action.Connect -> connect(action.generation)
                is Action.SendSessionConfiguration -> configure(action.generation)
                is Action.Schedule -> schedule(action)
                is Action.CancelTimeout -> cancelTimeout(action.kind)
                Action.CancelAllTimeouts -> cancelAllTimeouts()
                Action.CloseTransport -> closeTransport()
            }
        }
        publishSnapshot()
    }

    private fun acquireCredential(generation: Long) {
        credentialRequest?.cancel()
        credentialRequest = try {
            credentialProvider.request(
                activeSessionConfig,
                object : LiveVoiceCredentialProvider.Callback {
                    override fun onCredential(credential: RealtimeEphemeralCredential) {
                        dispatch {
                            if (generation != stateMachine.snapshot.generation) return@dispatch
                            if (
                                credential.expiresAtEpochSeconds?.let {
                                    it <= (System.currentTimeMillis() / 1_000) +
                                        MIN_CREDENTIAL_VALIDITY_SECONDS
                                } == true
                            ) {
                                transportFailure(
                                    generation,
                                    "realtime_credential_expiring",
                                    true,
                                )
                                return@dispatch
                            }
                            pendingCredential = generation to credential
                            apply(stateMachine.credentialReady(generation))
                        }
                    }

                    override fun onFailure(failure: LiveVoiceFailure) {
                        dispatch {
                            if (generation != stateMachine.snapshot.generation) return@dispatch
                            emitFailure(failure)
                            apply(stateMachine.transportClosed(generation, failure))
                        }
                    }
                },
            )
        } catch (_: Exception) {
            emitFailure(LiveVoiceFailure("realtime_credential_failure", false))
            apply(
                stateMachine.transportClosed(
                    generation,
                    LiveVoiceFailure("realtime_credential_failure", false),
                ),
            )
            LiveVoiceCancellation.NONE
        }
    }

    private fun connect(generation: Long) {
        val credential = pendingCredential
            ?.takeIf { it.first == generation }
            ?.second
            ?: return transportFailure(generation, "realtime_credential_missing", false)
        pendingCredential = null
        closeTransport()
        val transport = try {
            transportFactory.create()
        } catch (_: Exception) {
            return transportFailure(generation, "realtime_transport_create_failed", false)
        }
        activeTransport = transport
        try {
            if (!transport.setUserInputMuted(userInputMuted.get())) {
                if (transport === activeTransport) activeTransport = null
                transport.close()
                return transportFailure(generation, "realtime_input_mute_failed", false)
            }
            transport.connect(
                credential,
                object : LiveVoiceTransport.Listener {
                    override fun onOpen() = dispatch {
                        if (transport !== activeTransport) return@dispatch
                        apply(stateMachine.transportOpen(generation))
                    }

                    override fun onEvent(event: String) = dispatch {
                        if (transport !== activeTransport) return@dispatch
                        handleServerEvent(generation, event)
                    }

                    override fun onClosed(failure: LiveVoiceFailure?) = dispatch {
                        if (transport !== activeTransport) return@dispatch
                        activeTransport = null
                        val actual = failure
                            ?: LiveVoiceFailure("realtime_transport_closed", true)
                        emitFailure(actual)
                        apply(stateMachine.transportClosed(generation, actual))
                    }
                },
            )
        } catch (_: Exception) {
            if (transport === activeTransport) activeTransport = null
            transport.close()
            transportFailure(generation, "realtime_transport_connect_failed", true)
        }
    }

    private fun configure(generation: Long) {
        val context = try {
            instructionsProvider.buildSessionContext().normalized()
        } catch (_: Exception) {
            null
        }
        if (context == null) {
            return transportFailure(generation, "realtime_context_invalid", false)
        }
        val event = try {
            OpenAiRealtimeProtocol.sessionUpdate(
                activeSessionConfig,
                context.instructions,
                context.taskRouting,
            )
        } catch (_: Exception) {
            return transportFailure(generation, "realtime_context_invalid", false)
        }
        awaitingInitialConfigurationGeneration = generation
        contextRefreshInFlight = null
        desiredSessionContext = null
        contextRefreshAckTimeout?.cancel(false)
        contextRefreshAckTimeout = null
        appliedContextKey = context.key()
        if (activeTransport?.sendUtf8(event) != true) {
            awaitingInitialConfigurationGeneration = null
            appliedContextKey = null
            transportFailure(generation, "realtime_session_update_failed", true)
        }
    }

    private fun refreshSessionContext(
        generation: Long,
    ) {
        if (generation != stateMachine.snapshot.generation) return
        val phase = stateMachine.snapshot.phase
        if (phase in DEFERRED_CONTEXT_REFRESH_PHASES) {
            sessionContextDirty = true
            LiveVoiceDiagnostics.event("CONTEXT_REFRESH_DEFERRED", "phase=${phase.name.lowercase()}")
            return
        }
        if (activeTransport == null) return
        if (phase in setOf(
                LiveVoicePhase.IDLE,
                LiveVoicePhase.FAILED,
                LiveVoicePhase.STOPPED,
            )
        ) return
        val context = try {
            instructionsProvider.buildSessionContext().normalized()
        } catch (_: Exception) {
            return
        }
        val key = context.key()
        if (deferContextRefreshForActiveSpeech(context, key)) return
        val inFlight = contextRefreshInFlight
        if (inFlight != null) {
            desiredSessionContext = if (key == inFlight.key) null else context
            LiveVoiceDiagnostics.event("CONTEXT_REFRESH_COALESCED")
            return
        }
        desiredSessionContext = null
        if (key == appliedContextKey) return
        sendContextRefresh(generation, context)
    }

    private fun sendContextRefresh(
        generation: Long,
        context: LiveVoiceSessionContext,
    ) {
        if (deferContextRefreshForActiveSpeech(context, context.key())) return
        check(contextRefreshInFlight == null) { "context_refresh_already_in_flight" }
        val event = try {
            OpenAiRealtimeProtocol.contextUpdate(context.instructions, context.taskRouting)
        } catch (_: Exception) {
            return
        }
        val pending = PendingContextRefresh(
            sequence = nextContextRefreshSequence++,
            key = context.key(),
        )
        contextRefreshInFlight = pending
        if (activeTransport?.sendUtf8(event) != true) {
            contextRefreshInFlight = null
            transportFailure(generation, "realtime_context_refresh_failed", true)
            return
        }
        contextRefreshAckTimeout?.cancel(false)
        contextRefreshAckTimeout = try {
            scheduler.schedule(
                {
                    dispatch {
                        if (
                            generation == stateMachine.snapshot.generation &&
                            contextRefreshInFlight?.sequence == pending.sequence
                        ) {
                            transportFailure(
                                generation,
                                "realtime_context_refresh_ack_timeout",
                                true,
                            )
                        }
                    }
                },
                config.configureTimeoutMillis,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RuntimeException) {
            transportFailure(generation, "realtime_context_refresh_timer_failed", true)
            return
        }
        LiveVoiceDiagnostics.event(
            "CONTEXT_REFRESH_SENT",
            "routing=${context.taskRouting.name.lowercase()}",
        )
    }

    private fun deferContextRefreshForActiveSpeech(
        context: LiveVoiceSessionContext,
        key: ContextKey,
    ): Boolean {
        if (!speechInputActive) return false
        // Never cut off an utterance that is already being captured. Remember only the latest
        // effective context and apply it at speech_stopped before accepting another turn.
        desiredSessionContext = if (key == appliedContextKey) null else context
        LiveVoiceDiagnostics.event("CONTEXT_REFRESH_DEFERRED_FOR_ACTIVE_SPEECH")
        return true
    }

    private fun handleServerEvent(generation: Long, raw: String) {
        when (val event = OpenAiRealtimeProtocol.parseServerEvent(raw)) {
            RealtimeServerEvent.SessionCreated -> Unit
            RealtimeServerEvent.SessionUpdated -> {
                if (awaitingInitialConfigurationGeneration == generation) {
                    awaitingInitialConfigurationGeneration = null
                    apply(stateMachine.sessionConfigured(generation))
                    if (sessionContextDirty) {
                        sessionContextDirty = false
                        refreshSessionContext(generation)
                    }
                    if (contextRefreshInFlight == null) {
                        requestPendingUserResponse(generation)
                        deliverRecoveredResults(generation)
                    }
                } else if (contextRefreshInFlight != null) {
                    val acknowledged = contextRefreshInFlight!!
                    contextRefreshInFlight = null
                    contextRefreshAckTimeout?.cancel(false)
                    contextRefreshAckTimeout = null
                    appliedContextKey = acknowledged.key
                    LiveVoiceDiagnostics.event("CONTEXT_REFRESH_ACKNOWLEDGED")
                    val desired = desiredSessionContext
                    desiredSessionContext = null
                    if (desired != null && desired.key() != appliedContextKey) {
                        sendContextRefresh(generation, desired)
                    }
                    if (
                        contextRefreshInFlight == null &&
                        activeTransport != null
                    ) {
                        requestPendingUserResponse(generation)
                        deliverRecoveredResults(generation)
                    }
                } else {
                    LiveVoiceDiagnostics.event("CONTEXT_REFRESH_ACK_IGNORED")
                }
            }
            is RealtimeServerEvent.UserSpeechStarted -> {
                val newTurn = recordUserSpeechStarted(event.itemId)
                if (newTurn) {
                    rejectPendingEndCallAuthorization(
                        generation,
                        "A newer user turn started before the farewell could be verified.",
                    )
                    clearPendingModelHangup()
                }
                responseReady.cancelActive()
                // Accept the already-observed utterance first. Any concurrently discovered
                // context revision is buffered until speech_stopped instead of cutting audio. A
                // context update already in flight is also harmless: response creation waits for
                // its acknowledgement.
                apply(stateMachine.userSpeechStarted(generation))
                refreshSessionContext(generation)
            }
            RealtimeServerEvent.UserSpeechStopped -> {
                val completedSpeech = speechInputActive
                speechInputActive = false
                apply(stateMachine.userSpeechStopped(generation))
                if (!completedSpeech) {
                    LiveVoiceDiagnostics.event("USER_SPEECH_STOP_IGNORED")
                    return
                }
                userResponsePending = true
                if (desiredSessionContext != null) {
                    refreshSessionContext(generation)
                }
                requestPendingUserResponse(generation)
            }
            is RealtimeServerEvent.UserTranscript -> {
                if (event.isFinal) {
                    if (recordFinalUserTurn(event.itemId)) {
                        latestFinalUserUtterance = FinalUserUtterance(userTurnEpoch, event.text)
                        resolvePendingEndCallAuthorization(generation)
                    }
                }
                emitUserTranscript(event.text, event.isFinal)
            }
            is RealtimeServerEvent.HansTranscript -> {
                responseReady.text(generation, event.responseId, event.text)?.let { ready ->
                    try {
                        observer.onHansResponseReady(ready)
                    } catch (_: RuntimeException) {
                        // Optional feedback must not interrupt transcript or audio delivery.
                    }
                }
                emitHansTranscript(event.text, event.isFinal)
            }
            is RealtimeServerEvent.ResponseStarted -> {
                responseReady.started(generation, event.responseId)
                responseArbiter.responseStarted(event.responseId)
                apply(stateMachine.responseStarted(generation))
            }
            is RealtimeServerEvent.ResponseFinished -> {
                pendingEndCallAuthorization
                    ?.takeIf { it.responseId == event.responseId }
                    ?.let { pending ->
                        if (event.status.equals("completed", ignoreCase = true)) {
                            pending.responseFinished = true
                        } else {
                            rejectPendingEndCallAuthorization(
                                generation,
                                "The farewell response did not complete successfully.",
                            )
                        }
                    }
                val hangup = pendingModelHangup?.takeIf { it.responseId == event.responseId }
                if (hangup != null) {
                    if (event.status.equals("completed", ignoreCase = true)) {
                        hangup.responseFinished = true
                    } else {
                        clearPendingModelHangup()
                    }
                }
                when (val finished = responseArbiter.responseFinished(event.responseId)) {
                    LiveVoiceResponseArbiter.FinishDisposition.Ignored ->
                        LiveVoiceDiagnostics.event(
                            "RESPONSE_DONE_IGNORED",
                            "response=${LiveVoiceDiagnostics.safeId(event.responseId)}",
                        )
                    is LiveVoiceResponseArbiter.FinishDisposition.Matched -> {
                        responseReady.finished(generation, event.responseId)
                        apply(stateMachine.responseFinished(generation))
                        val recoveredCompletion = finished.completed?.takeIf {
                            it.purpose == LiveVoiceResponseArbiter.Purpose.RECOVERED_RESULT
                        }
                        if (
                            recoveredCompletion != null &&
                            !event.status.isSuccessfulRecoveryStatus()
                        ) {
                            val userCancelled = recoveryCancellationRequestedByUser &&
                                event.status.equals("cancelled", ignoreCase = true)
                            recoveryCancellationRequestedByUser = false
                            LiveVoiceDiagnostics.event(
                                "RECOVERY_RESPONSE_RETRY",
                                "status=${event.status.toSafeStatus()} user_cancel=$userCancelled",
                            )
                            transportFailure(
                                generation,
                                if (userCancelled) {
                                    "realtime_recovery_cancelled_by_user"
                                } else {
                                    "realtime_recovery_not_completed"
                                },
                                true,
                            )
                            return
                        }
                        finished.completed?.let(::acknowledgeRecoveryBatch)
                        if (recoveredCompletion != null) {
                            recoveryCancellationRequestedByUser = false
                        }
                        finished.next?.let {
                            LiveVoiceDiagnostics.event(
                                "RESPONSE_DRAINED",
                                "purpose=${it.purpose.name} " +
                                    "response=${LiveVoiceDiagnostics.safeId(event.responseId)}",
                            )
                            sendResponseCreate(generation, it)
                        }
                        if (finished.next == null) deliverRecoveredResults(generation)
                    }
                }
                maybeFinishModelHangup(generation)
            }
            is RealtimeServerEvent.FunctionCall -> handleFunctionCall(generation, event)
            is RealtimeServerEvent.OutputAudioStarted -> {
                rememberOutputAudioResponse(activeOutputAudioResponses, event.responseId)
                pendingEndCallAuthorization?.takeIf { it.responseId == event.responseId }
                    ?.audioStarted = true
                pendingModelHangup?.takeIf { it.responseId == event.responseId }
                    ?.audioStarted = true
            }
            is RealtimeServerEvent.OutputAudioStopped -> {
                activeOutputAudioResponses.remove(event.responseId)
                pendingEndCallAuthorization?.takeIf { it.responseId == event.responseId }?.let {
                    it.audioStarted = true
                    it.audioStopped = true
                }
                pendingModelHangup?.takeIf { it.responseId == event.responseId }?.let {
                    it.audioStarted = true
                    it.audioStopped = true
                }
                maybeFinishModelHangup(generation)
            }
            is RealtimeServerEvent.OutputAudioCleared -> {
                event.responseId?.let(activeOutputAudioResponses::remove)
                if (
                    event.responseId == null ||
                    pendingEndCallAuthorization?.responseId == event.responseId
                ) {
                    rejectPendingEndCallAuthorization(
                        generation,
                        "The farewell audio was interrupted before hangup authorization completed.",
                    )
                }
                if (
                    event.responseId == null ||
                    pendingModelHangup?.responseId == event.responseId
                ) {
                    clearPendingModelHangup()
                }
            }
            is RealtimeServerEvent.ResponseSchedulingConflict -> {
                val matched = responseArbiter.responseSchedulingConflict(event.eventId)
                LiveVoiceDiagnostics.event(
                    if (matched) {
                        "RESPONSE_SCHEDULING_CONFLICT"
                    } else {
                        "RESPONSE_SCHEDULING_CONFLICT_IGNORED"
                    },
                    "event=${LiveVoiceDiagnostics.safeId(event.eventId)}",
                )
            }
            is RealtimeServerEvent.Failure -> {
                LiveVoiceDiagnostics.event(
                    "SESSION_FAILURE",
                    "code=${event.failure.code} retryable=${event.failure.retryable}",
                )
                emitFailure(event.failure)
                apply(stateMachine.transportClosed(generation, event.failure))
            }
            RealtimeServerEvent.Ignored -> Unit
        }
    }

    private fun handleFunctionCall(
        generation: Long,
        event: RealtimeServerEvent.FunctionCall,
    ) {
        if (!rememberToolCall(event.callId)) return
        if (event.name == OpenAiRealtimeProtocol.END_CALL_TOOL_NAME) {
            handleEndCallFunction(generation, event)
            return
        }
        val request = OpenAiRealtimeProtocol.parseTaskRequest(event)
        if (request == null) {
            sendFunctionResult(
                generation,
                event.callId,
                "The requested local task was invalid.",
                success = false,
            )
            return
        }

        if (lastAcceptedTaskTurnEpoch == userTurnEpoch) {
            LiveVoiceDiagnostics.event(
                "DUPLICATE_SUPPRESSED",
                "epoch=$userTurnEpoch call=${LiveVoiceDiagnostics.safeId(event.callId)}",
            )
            if (!sendFunctionOutput(
                generation = generation,
                callId = event.callId,
                output = DUPLICATE_TASK_OUTPUT,
                success = false,
                continuation = LiveVoiceResponseArbiter.Purpose.TASK_PROGRESS,
            )) {
                transportFailure(generation, "realtime_function_output_failed", true)
            }
            return
        }
        lastAcceptedTaskTurnEpoch = userTurnEpoch
        LiveVoiceDiagnostics.event(
            "TASK_START",
            "epoch=$userTurnEpoch call=${LiveVoiceDiagnostics.safeId(event.callId)}",
        )

        val record = TaskRecord(
            callId = request.callId,
            startedGeneration = generation,
            startedAtMillis = nowMillis(),
        )
        taskJobs[request.callId] = record
        apply(stateMachine.taskStarted(generation))
        record.timeout = scheduler.schedule(
            { dispatch { finishTaskTimeout(request.callId) } },
            config.taskTimeoutMillis,
            TimeUnit.MILLISECONDS,
        )
        record.handle = try {
            taskExecutor.execute(
                request,
                object : LiveVoiceTaskExecutor.Listener {
                    override fun onProgress(progress: LiveVoiceTaskProgress) = dispatch {
                        handleTaskProgress(request.callId, progress)
                    }

                    override fun onCompleted(result: LiveVoiceTaskResult) = dispatch {
                        finishTask(request.callId, result.output, success = true)
                    }

                    override fun onFailure(failure: LiveVoiceTaskFailure) = dispatch {
                        finishTask(
                            request.callId,
                            if (failure.retryable) {
                                "The task could not finish this time and may be retried."
                            } else {
                                "The task could not be completed."
                            },
                            success = false,
                        )
                    }
                },
            )
        } catch (_: Exception) {
            finishTask(
                request.callId,
                "The task runtime could not start this request.",
                success = false,
            )
            LiveVoiceTaskHandle.NONE
        }
    }

    private fun handleEndCallFunction(
        generation: Long,
        event: RealtimeServerEvent.FunctionCall,
    ) {
        if (!OpenAiRealtimeProtocol.isEndCallRequest(event)) {
            rejectEndCallFunction(generation, event)
            return
        }
        val current = latestFinalUserUtterance?.takeIf { it.turnEpoch == userTurnEpoch }
        if (current != null) {
            if (LiveVoiceFarewellPolicy.isExplicitFarewell(current.text)) {
                armModelHangup(generation, event, buffered = null)
            } else {
                rejectEndCallFunction(generation, event)
            }
            return
        }
        if (userTurnEpoch == 0L || pendingEndCallAuthorization != null) {
            rejectEndCallFunction(generation, event)
            return
        }
        val responseId = checkNotNull(event.responseId)
        val pending = PendingEndCallAuthorization(
            generation = generation,
            turnEpoch = userTurnEpoch,
            event = event,
            responseId = responseId,
            audioStarted = responseId in activeOutputAudioResponses,
        )
        pendingEndCallAuthorization = pending
        pending.timeout = try {
            scheduler.schedule(
                {
                    dispatch {
                        if (
                            pendingEndCallAuthorization === pending &&
                            pending.generation == stateMachine.snapshot.generation
                        ) {
                            rejectPendingEndCallAuthorization(
                                pending.generation,
                                "The final user transcript did not arrive in time.",
                            )
                        }
                    }
                },
                config.modelHangupTranscriptWaitMillis,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RuntimeException) {
            null
        }
        if (pending.timeout == null) {
            rejectPendingEndCallAuthorization(
                generation,
                "The farewell verification timer could not start.",
            )
        } else {
            LiveVoiceDiagnostics.event(
                "MODEL_HANGUP_TRANSCRIPT_WAIT",
                "call=${LiveVoiceDiagnostics.safeId(event.callId)} epoch=$userTurnEpoch",
            )
        }
    }

    private fun resolvePendingEndCallAuthorization(generation: Long) {
        val pending = pendingEndCallAuthorization ?: return
        if (pending.generation != generation || pending.turnEpoch != userTurnEpoch) {
            rejectPendingEndCallAuthorization(
                generation,
                "The final transcript did not match the original user turn.",
            )
            return
        }
        val utterance = latestFinalUserUtterance?.takeIf { it.turnEpoch == pending.turnEpoch }
            ?: return
        if (!LiveVoiceFarewellPolicy.isExplicitFarewell(utterance.text)) {
            rejectPendingEndCallAuthorization(
                generation,
                "The latest user utterance was not an explicit farewell.",
            )
            return
        }
        clearPendingEndCallAuthorization()
        armModelHangup(generation, pending.event, buffered = pending)
    }

    private fun armModelHangup(
        generation: Long,
        event: RealtimeServerEvent.FunctionCall,
        buffered: PendingEndCallAuthorization?,
    ) {
        val responseId = checkNotNull(event.responseId)
        clearPendingModelHangup()
        pendingModelHangup = PendingModelHangup(
            responseId = responseId,
            responseFinished = buffered?.responseFinished == true,
            audioStarted = buffered?.audioStarted ?: (responseId in activeOutputAudioResponses),
            audioStopped = buffered?.audioStopped == true,
        )
        if (!sendFunctionOutput(
                generation = generation,
                callId = event.callId,
                output = "The call will close after your farewell audio has finished playing.",
                success = true,
                continuation = null,
            )
        ) {
            clearPendingModelHangup()
            transportFailure(generation, "realtime_function_output_failed", true)
            return
        }
        LiveVoiceDiagnostics.event(
            "MODEL_HANGUP_ARMED",
            "response=${LiveVoiceDiagnostics.safeId(responseId)}",
        )
        maybeFinishModelHangup(generation)
    }

    private fun rejectEndCallFunction(
        generation: Long,
        event: RealtimeServerEvent.FunctionCall,
        detail: String = "The latest user utterance was not an explicit farewell. Keep the call open.",
    ) {
        LiveVoiceDiagnostics.event(
            "MODEL_HANGUP_REJECTED",
            "call=${LiveVoiceDiagnostics.safeId(event.callId)} epoch=$userTurnEpoch",
        )
        if (!sendFunctionOutput(
                generation = generation,
                callId = event.callId,
                output = detail,
                success = false,
                continuation = null,
            )
        ) {
            transportFailure(generation, "realtime_function_output_failed", true)
        }
    }

    private fun rejectPendingEndCallAuthorization(generation: Long, detail: String) {
        val pending = pendingEndCallAuthorization ?: return
        clearPendingEndCallAuthorization()
        if (
            generation == stateMachine.snapshot.generation &&
            pending.generation == generation &&
            activeTransport != null
        ) {
            rejectEndCallFunction(generation, pending.event, detail)
        }
    }

    private fun clearPendingEndCallAuthorization() {
        pendingEndCallAuthorization?.timeout?.cancel(false)
        pendingEndCallAuthorization = null
    }

    private fun maybeFinishModelHangup(generation: Long) {
        val pending = pendingModelHangup ?: return
        if (!pending.responseFinished) return
        if (!pending.audioStopped) {
            scheduleModelHangupAudioDrainFallback(generation, pending)
            return
        }
        completeModelHangup(pending, timedOut = false)
    }

    private fun scheduleModelHangupAudioDrainFallback(
        generation: Long,
        pending: PendingModelHangup,
    ) {
        if (pending.drainTimeout != null) return
        pending.drainTimeout = try {
            scheduler.schedule(
                {
                    dispatch {
                        val current = pendingModelHangup
                        if (
                            generation == stateMachine.snapshot.generation &&
                            current === pending &&
                            current.responseFinished &&
                            !current.audioStopped
                        ) {
                            completeModelHangup(current, timedOut = true)
                        }
                    }
                },
                config.modelHangupAudioDrainTimeoutMillis,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RuntimeException) {
            null
        }
        if (pending.drainTimeout == null) {
            // A rejected timer would otherwise leave a model-ended call open forever.
            completeModelHangup(pending, timedOut = true)
        } else {
            LiveVoiceDiagnostics.event(
                "MODEL_HANGUP_DRAIN_WAIT",
                "response=${LiveVoiceDiagnostics.safeId(pending.responseId)} " +
                    "timeout_ms=${config.modelHangupAudioDrainTimeoutMillis}",
            )
        }
    }

    private fun completeModelHangup(pending: PendingModelHangup, timedOut: Boolean) {
        if (pendingModelHangup !== pending) return
        clearPendingModelHangup()
        LiveVoiceDiagnostics.event(
            if (timedOut) "MODEL_HANGUP_DRAIN_TIMEOUT" else "MODEL_HANGUP_COMPLETED",
            "response=${LiveVoiceDiagnostics.safeId(pending.responseId)}",
        )
        userInputMuted.set(false)
        activeTransport?.setUserInputMuted(false)
        apply(stateMachine.stop())
    }

    private fun clearPendingModelHangup() {
        pendingModelHangup?.drainTimeout?.cancel(false)
        pendingModelHangup = null
    }

    private fun handleTaskProgress(callId: String, progress: LiveVoiceTaskProgress) {
        val record = taskJobs[callId] ?: return
        emitTaskProgress(callId, progress)
        val now = nowMillis()
        if (now - record.startedAtMillis < config.progressAnnouncementDelayMillis) return
        if (now - record.lastAnnouncementMillis < config.progressAnnouncementIntervalMillis) return
        if (stateMachine.snapshot.phase !in setOf(
                LiveVoicePhase.WAITING_FOR_TASK,
                LiveVoicePhase.LISTENING,
            )
        ) return
        val transport = activeTransport ?: return
        if (transport.sendUtf8(OpenAiRealtimeProtocol.localProgressContext(progress.summary))) {
            requestResponse(
                stateMachine.snapshot.generation,
                LiveVoiceResponseArbiter.Purpose.TASK_PROGRESS,
            )
            record.lastAnnouncementMillis = now
        }
    }

    private fun finishTaskTimeout(callId: String) {
        val record = taskJobs[callId] ?: return
        record.handle?.cancel()
        finishTask(callId, "The task timed out before it produced a result.", success = false)
    }

    private fun finishTask(callId: String, output: String, success: Boolean) {
        val record = taskJobs.remove(callId) ?: return
        record.timeout?.cancel(false)
        val currentGeneration = stateMachine.snapshot.generation
        apply(stateMachine.taskFinished(currentGeneration))
        if (
            record.startedGeneration == currentGeneration &&
            activeTransport != null &&
            stateMachine.snapshot.phase !in setOf(
                LiveVoicePhase.RECONNECTING,
                LiveVoicePhase.CONNECTING,
                LiveVoicePhase.CONFIGURING,
                LiveVoicePhase.FAILED,
                LiveVoicePhase.STOPPED,
            )
        ) {
            if (!sendFunctionOutput(
                generation = currentGeneration,
                callId = callId,
                output = output,
                success = success,
                continuation = LiveVoiceResponseArbiter.Purpose.TASK_RESULT,
            )) {
                enqueueRecoveredResult(callId, output, success)
                transportFailure(currentGeneration, "realtime_function_output_failed", true)
            }
        } else if (stateMachine.snapshot.phase != LiveVoicePhase.STOPPED) {
            enqueueRecoveredResult(callId, output, success)
        }
    }

    private fun sendFunctionResult(
        generation: Long,
        callId: String,
        output: String,
        success: Boolean,
    ) {
        if (!sendFunctionOutput(
                generation = generation,
                callId = callId,
                output = output,
                success = success,
                continuation = LiveVoiceResponseArbiter.Purpose.TASK_RESULT,
            )
        ) {
            enqueueRecoveredResult(callId, output, success)
            transportFailure(generation, "realtime_function_output_failed", true)
        }
    }

    private fun sendFunctionOutput(
        generation: Long,
        callId: String,
        output: String,
        success: Boolean,
        continuation: LiveVoiceResponseArbiter.Purpose?,
    ): Boolean {
        if (generation != stateMachine.snapshot.generation) return false
        val bounded = output.take(LiveVoiceTaskResult.MAX_OUTPUT_CHARACTERS)
        val transport = activeTransport ?: return false
        val sent = runCatching {
            transport.sendUtf8(OpenAiRealtimeProtocol.functionOutput(callId, bounded, success))
        }.getOrDefault(false)
        if (sent) {
            LiveVoiceDiagnostics.event(
                "FUNCTION_OUTPUT_SENT",
                "call=${LiveVoiceDiagnostics.safeId(callId)} success=$success",
            )
            continuation?.let { requestResponse(generation, it) }
        }
        return sent
    }

    private fun enqueueRecoveredResult(callId: String, output: String, success: Boolean) {
        val receipt = LiveVoiceDiagnostics.safeId(callId)
        recoveredResults.addLast(
            RecoveredResult(
                sequence = nextRecoveredResultSequence++,
                correlationReceipt = receipt,
                output = output.take(LiveVoiceTaskResult.MAX_OUTPUT_CHARACTERS),
                success = success,
            ),
        )
        LiveVoiceDiagnostics.event(
            "FUNCTION_OUTPUT_QUEUED",
            "call=$receipt success=$success",
        )
    }

    private fun deliverRecoveredResults(generation: Long) {
        val transport = activeTransport ?: return
        if (generation != stateMachine.snapshot.generation) return
        if (recoveryBatch != null || !responseArbiter.isIdle()) return
        if (recoveredResults.isEmpty()) return
        val batch = RecoveryBatch(recoveredResults.toList())
        recoveryBatch = batch
        for (result in batch.results) {
            val sent = runCatching {
                transport.sendUtf8(
                    OpenAiRealtimeProtocol.recoveredTaskResult(result.output, result.success),
                )
            }.getOrDefault(false)
            if (!sent) {
                transportFailure(generation, "realtime_recovered_result_failed", true)
                return
            }
            LiveVoiceDiagnostics.event(
                "RECOVERED_RESULT_SENT",
                "call=${result.correlationReceipt} success=${result.success}",
            )
        }
        requestResponse(generation, LiveVoiceResponseArbiter.Purpose.RECOVERED_RESULT)
    }

    private fun acknowledgeRecoveryBatch(command: LiveVoiceResponseArbiter.CreateCommand) {
        if (command.purpose != LiveVoiceResponseArbiter.Purpose.RECOVERED_RESULT) return
        val batch = recoveryBatch ?: return
        if (batch.responseEventId != command.eventId) return
        val acknowledged = batch.results.mapTo(hashSetOf()) { it.sequence }
        val remaining = recoveredResults.filterNot { it.sequence in acknowledged }
        recoveredResults.clear()
        recoveredResults.addAll(remaining)
        recoveryBatch = null
        LiveVoiceDiagnostics.event(
            "RECOVERY_BATCH_COMMITTED",
            "count=${acknowledged.size} event=${LiveVoiceDiagnostics.safeId(command.eventId)}",
        )
    }

    private fun requestPendingUserResponse(generation: Long) {
        if (!userResponsePending) return
        if (generation != stateMachine.snapshot.generation || activeTransport == null) return
        if (
            speechInputActive ||
            awaitingInitialConfigurationGeneration != null ||
            contextRefreshInFlight != null ||
            desiredSessionContext != null ||
            sessionContextDirty
        ) {
            LiveVoiceDiagnostics.event("USER_RESPONSE_WAITING_FOR_CONTEXT")
            return
        }
        userResponsePending = false
        LiveVoiceDiagnostics.event("USER_RESPONSE_CONTEXT_READY")
        requestResponse(generation, LiveVoiceResponseArbiter.Purpose.USER_TURN)
    }

    private fun requestResponse(
        generation: Long,
        purpose: LiveVoiceResponseArbiter.Purpose,
    ) {
        val ready = responseArbiter.request(purpose)
        if (ready == null) {
            LiveVoiceDiagnostics.event("RESPONSE_DEFERRED", "purpose=${purpose.name}")
        } else {
            sendResponseCreate(generation, ready)
        }
    }

    private fun sendResponseCreate(
        generation: Long,
        command: LiveVoiceResponseArbiter.CreateCommand,
    ) {
        if (generation != stateMachine.snapshot.generation) {
            responseArbiter.createSendFailed()
            return
        }
        val event = when (command.purpose) {
            LiveVoiceResponseArbiter.Purpose.USER_TURN ->
                OpenAiRealtimeProtocol.userResponseCreate(command.eventId)
            LiveVoiceResponseArbiter.Purpose.TASK_PROGRESS ->
                OpenAiRealtimeProtocol.progressResponseCreate(command.eventId)
            LiveVoiceResponseArbiter.Purpose.TASK_RESULT ->
                OpenAiRealtimeProtocol.responseCreate(command.eventId)
            LiveVoiceResponseArbiter.Purpose.RECOVERED_RESULT -> {
                val batch = recoveryBatch
                if (batch == null) {
                    responseArbiter.createSendFailed()
                    return
                }
                batch.responseEventId = command.eventId
                OpenAiRealtimeProtocol.recoveredResponseCreate(command.eventId)
            }
        }
        val sent = runCatching { activeTransport?.sendUtf8(event) == true }.getOrDefault(false)
        if (!sent) {
            responseArbiter.createSendFailed()
            transportFailure(generation, "realtime_response_create_failed", true)
        }
    }

    private fun recordFinalUserTurn(itemId: String?): Boolean {
        if (itemId != null) {
            if (!finalUserItemIds.add(itemId)) return false
            trimSet(finalUserItemIds, MAX_TRACKED_USER_ITEMS)
            if (itemId in spokenUserItemIds) return true
        }
        if (unmatchedSpeechStartCount > 0) {
            unmatchedSpeechStartCount -= 1
            return true
        }
        advanceUserTurnEpoch()
        LiveVoiceDiagnostics.event(
            "USER_EPOCH",
            "epoch=$userTurnEpoch source=transcript item=${LiveVoiceDiagnostics.safeId(itemId)}",
        )
        return true
    }

    private fun recordUserSpeechStarted(itemId: String?): Boolean {
        if (itemId != null) {
            if (!spokenUserItemIds.add(itemId)) return false
            trimSet(spokenUserItemIds, MAX_TRACKED_USER_ITEMS)
        } else {
            if (speechInputActive) return false
            unmatchedSpeechStartCount = (unmatchedSpeechStartCount + 1)
                .coerceAtMost(MAX_TRACKED_USER_ITEMS)
        }
        speechInputActive = true
        advanceUserTurnEpoch()
        LiveVoiceDiagnostics.event(
            "USER_EPOCH",
            "epoch=$userTurnEpoch source=speech item=${LiveVoiceDiagnostics.safeId(itemId)}",
        )
        return true
    }

    private fun advanceUserTurnEpoch() {
        userTurnEpoch = if (userTurnEpoch == Long.MAX_VALUE) {
            0L
        } else {
            userTurnEpoch + 1
        }
    }

    private fun rememberToolCall(callId: String): Boolean {
        if (!handledToolCallIds.add(callId)) return false
        trimSet(handledToolCallIds, MAX_TRACKED_TOOL_CALLS)
        return true
    }

    private fun trimSet(values: LinkedHashSet<String>, maximum: Int) {
        while (values.size > maximum) values.remove(values.first())
    }

    private fun schedule(action: Action.Schedule) {
        cancelTimeout(action.kind)
        timeoutJobs[action.kind] = scheduler.schedule(
            {
                dispatch {
                    timeoutJobs.remove(action.kind)
                    apply(stateMachine.timeout(action.generation, action.kind))
                }
            },
            action.delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelTimeout(kind: TimeoutKind) {
        timeoutJobs.remove(kind)?.cancel(false)
    }

    private fun cancelAllTimeouts() {
        timeoutJobs.values.forEach { it.cancel(false) }
        timeoutJobs.clear()
        credentialRequest?.cancel()
        credentialRequest = null
        pendingCredential = null
    }

    private fun closeTransport() {
        val transport = activeTransport
        activeTransport = null
        awaitingInitialConfigurationGeneration = null
        contextRefreshInFlight = null
        desiredSessionContext = null
        contextRefreshAckTimeout?.cancel(false)
        contextRefreshAckTimeout = null
        appliedContextKey = null
        // VAD state belongs to the discarded transport. Carrying it into the replacement session
        // would make a later idle context refresh look like it interrupted an active utterance.
        speechInputActive = false
        userResponsePending = false
        unmatchedSpeechStartCount = 0
        responseArbiter.reset()
        responseReady.cancelActive()
        recoveryBatch = null
        recoveryCancellationRequestedByUser = false
        clearPendingEndCallAuthorization()
        clearPendingModelHangup()
        activeOutputAudioResponses.clear()
        try {
            transport?.close()
        } catch (_: Exception) {
            Unit
        }
    }

    private fun transportFailure(generation: Long, code: String, retryable: Boolean) {
        LiveVoiceDiagnostics.event("SESSION_FAILURE", "code=$code retryable=$retryable")
        val failure = LiveVoiceFailure(code, retryable)
        emitFailure(failure)
        apply(stateMachine.transportClosed(generation, failure))
    }

    private fun resolveCallVoice() {
        val candidate = try {
            voiceSelectionProvider.resolve()
        } catch (_: Exception) {
            null
        }
        val selection = candidate?.takeIf {
            it.effectiveRealtimeVoice in LiveVoiceRealtimeVoiceMapper.supportedRealtimeVoices
        } ?: LiveVoiceRealtimeVoiceMapper.resolve(candidate?.requestedTtsVoice)
        activeVoiceSelection = selection
        activeSessionConfig = config.copy(voice = selection.effectiveRealtimeVoice)
        LiveVoiceDiagnostics.event(
            "VOICE_SELECTED",
            "requested=${LiveVoiceDiagnostics.safeId(selection.requestedTtsVoice)} " +
                "effective=${selection.effectiveRealtimeVoice} " +
                "resolution=${selection.resolution.name.lowercase()}",
        )
    }

    private fun publishSnapshot() {
        val next = stateMachine.snapshot.copy(
            inputMuted = userInputMuted.get(),
            voiceSelection = activeVoiceSelection,
        )
        if (next == publishedSnapshot) return
        publishedSnapshot = next
        try {
            observer.onSnapshot(next)
        } catch (_: RuntimeException) {
            Unit
        }
    }

    private fun dispatch(allowClosed: Boolean = false, block: () -> Unit) {
        if (closed.get() && !allowClosed) return
        try {
            scheduler.execute {
                if (!closed.get() || allowClosed) block()
            }
        } catch (_: RuntimeException) {
            Unit
        }
    }

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000

    private fun String?.isSuccessfulRecoveryStatus(): Boolean =
        equals("completed", ignoreCase = true)

    private fun String?.toSafeStatus(): String =
        this?.lowercase()?.takeIf { it in RECOVERY_TERMINAL_STATUSES } ?: "unknown"

    private fun emitFailure(failure: LiveVoiceFailure) {
        try {
            observer.onFailure(failure)
        } catch (_: RuntimeException) {
            Unit
        }
    }

    private fun emitUserTranscript(text: String, isFinal: Boolean) {
        try {
            observer.onUserTranscript(text, isFinal)
        } catch (_: RuntimeException) {
            Unit
        }
    }

    private fun emitHansTranscript(text: String, isFinal: Boolean) {
        try {
            observer.onHansTranscript(text, isFinal)
        } catch (_: RuntimeException) {
            Unit
        }
    }

    private fun emitTaskProgress(callId: String, progress: LiveVoiceTaskProgress) {
        try {
            observer.onTaskProgress(callId, progress)
        } catch (_: RuntimeException) {
            Unit
        }
    }

    private data class RecoveredResult(
        val sequence: Long,
        val correlationReceipt: String,
        val output: String,
        val success: Boolean,
    )

    private data class RecoveryBatch(
        val results: List<RecoveredResult>,
        var responseEventId: String? = null,
    )

    private data class TaskRecord(
        val callId: String,
        val startedGeneration: Long,
        val startedAtMillis: Long,
        var lastAnnouncementMillis: Long = Long.MIN_VALUE / 2,
        var handle: LiveVoiceTaskHandle? = null,
        var timeout: ScheduledFuture<*>? = null,
    )

    private data class ContextKey(
        val instructions: String,
        val taskRouting: LiveVoiceTaskRouting,
        val contextIdentity: String,
    )

    private data class PendingContextRefresh(
        val sequence: Long,
        val key: ContextKey,
    )

    private data class FinalUserUtterance(
        val turnEpoch: Long,
        val text: String,
    )

    private data class PendingModelHangup(
        val responseId: String,
        var responseFinished: Boolean = false,
        var audioStarted: Boolean = false,
        var audioStopped: Boolean = false,
        var drainTimeout: ScheduledFuture<*>? = null,
    )

    private data class PendingEndCallAuthorization(
        val generation: Long,
        val turnEpoch: Long,
        val event: RealtimeServerEvent.FunctionCall,
        val responseId: String,
        var responseFinished: Boolean = false,
        var audioStarted: Boolean = false,
        var audioStopped: Boolean = false,
        var timeout: ScheduledFuture<*>? = null,
    )

    private fun LiveVoiceSessionContext.normalized(): LiveVoiceSessionContext = copy(
        instructions = instructions.trim(),
    )

    private fun LiveVoiceSessionContext.key(): ContextKey = ContextKey(
        instructions = instructions,
        taskRouting = taskRouting,
        contextIdentity = contextIdentity,
    )

    private companion object {
        const val MAX_TRACKED_TOOL_CALLS = 256
        const val MAX_TRACKED_USER_ITEMS = 128
        const val MIN_CREDENTIAL_VALIDITY_SECONDS = 10
        val DEFERRED_CONTEXT_REFRESH_PHASES = setOf(
            LiveVoicePhase.CONNECTING,
            LiveVoicePhase.CONFIGURING,
            LiveVoicePhase.RECONNECTING,
        )
        val RECOVERY_TERMINAL_STATUSES = setOf("completed", "failed", "incomplete", "cancelled")
        const val DUPLICATE_TASK_OUTPUT =
            "A task for this user turn is already running. Wait for its original result and do " +
                "not start or announce another task."
        val TERMINAL_PHASES = setOf(
            LiveVoicePhase.FAILED,
            LiveVoicePhase.STOPPED,
        )
        val STARTABLE_PHASES = TERMINAL_PHASES + LiveVoicePhase.IDLE

        fun rememberOutputAudioResponse(responses: LinkedHashSet<String>, responseId: String) {
            responses += responseId
            while (responses.size > MAX_TRACKED_OUTPUT_AUDIO_RESPONSES) {
                responses.remove(responses.first())
            }
        }

        const val MAX_TRACKED_OUTPUT_AUDIO_RESPONSES = 16
    }
}

/** A local safety gate: prompting alone must never grant the model authority to end a call. */
internal object LiveVoiceFarewellPolicy {
    private val farewell = Regex(
        "^(?:(?:okay|ok|alles klar|gut|danke|vielen dank|danke dir|dann|na dann)[, ]+)*" +
            "(?:tschuss|tschuess|tschau|ciao|auf wiedersehen|mach(?:s| es) gut|" +
            "bis (?:bald|spater|morgen|dann|zum nachsten mal)|bye|goodbye|" +
            "see you|see you later|talk to you later)" +
            "(?:[, ]+(?:danke|danke dir|hans|danke hans))?$",
    )
    private val directHangup = Regex(
        "^(?:bitte[, ]+)?(?:leg(?:e)? auf|beende(?:n wir)? (?:jetzt )?(?:das )?gesprach|" +
            "beende(?:n wir)? (?:jetzt )?(?:den )?anruf|hang up|end (?:the |this )?call)$",
    )

    fun isExplicitFarewell(text: String): Boolean {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace('ß', 's')
            .replace(Regex("[\u2019']"), "")
            .trim()
            .replace(Regex("[.!?]+$"), "")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (normalized.isBlank() || normalized.length > 160) return false
        return farewell.matches(normalized) || directHangup.matches(normalized)
    }
}
