package ai.hans.standard.voice

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Activity-independent coordinator for one microphone session at a time.
 * Audio capture and STT callbacks are serialized through [dispatcher].
 */
class DictationRecordingCoordinator(
    private val config: DictationRecordingConfig,
    private val clock: MonotonicClock,
    private val permissionChecker: RecordAudioPermissionChecker,
    private val captureStartBarrier: DictationCaptureStartBarrier,
    private val audioFocus: RecordingAudioFocusCoordinator,
    private val captureFactory: PcmAudioCaptureFactory,
    private val sttProvider: IncrementalSttProvider,
    private val deadlineScheduler: RecordingDeadlineScheduler,
    private val dispatcher: RecordingTaskDispatcher,
    private val listener: DictationRecordingListener,
    private val speechActivityDetector: PcmSpeechActivityDetector =
        Pcm16SpeechActivityDetector(),
    private val nextRecordingId: () -> RecordingId = RecordingIdGenerator()::next,
) {
    private val stateMachine = RecordingStateMachine(config)
    private val processStopping = AtomicBoolean(false)
    // A stop can arrive while the serialized worker is inside the speech barrier.
    // Revoke only that startup's admission immediately; lifecycle effects stay serialized.
    private val captureStartupStops =
        ConcurrentHashMap<RecordingId, AtomicReference<RecordingStopReason?>>()
    private var resources: SessionResources? = null

    fun state(): RecordingState = stateMachine.state()

    fun startRecording(): RecordingId {
        val recordingId = nextRecordingId()
        captureStartupStops[recordingId] = AtomicReference<RecordingStopReason?>(null)
        dispatcher.dispatch {
            try {
                if (processStopping.get()) {
                    executeEffects(
                        stateMachine.rejectStart(
                            recordingId = recordingId,
                            failure = RecordingFailure.PROCESS_STOPPED,
                        ),
                    )
                } else {
                    executeEffects(
                        stateMachine.start(
                            recordingId = recordingId,
                            nowMillis = clock.nowMillis(),
                            permissionGranted = permissionChecker.isRecordAudioGranted(),
                        ),
                    )
                }
            } finally {
                captureStartupStops.remove(recordingId)
            }
        }
        return recordingId
    }

    fun stopRecording(
        recordingId: RecordingId,
        reason: RecordingStopReason = RecordingStopReason.USER,
    ) {
        captureStartupStops[recordingId]?.compareAndSet(null, reason)
        dispatcher.dispatch {
            executeEffects(stateMachine.requestStop(recordingId, reason))
        }
    }

    fun onEnvironmentEvent(event: VoiceEnvironmentEvent) {
        dispatcher.dispatch {
            executeEffects(stateMachine.onEnvironmentEvent(event))
        }
    }

    fun onProcessStopping(onStopped: () -> Unit = {}) {
        // Revoke admission immediately, even while the serialized worker is waiting
        // for the speech barrier or a provider/factory call to return.
        processStopping.set(true)
        dispatcher.dispatch {
            try {
                executeEffects(stateMachine.onProcessStopping())
            } finally {
                closeSessionResources(resources?.recordingId)
                onStopped()
            }
        }
    }

    private fun executeEffects(effects: List<RecordingEffect>) {
        effects.forEach(::executeEffect)
    }

    private fun executeEffect(effect: RecordingEffect) {
        when (effect) {
            is RecordingEffect.StateChanged -> {
                if (
                    effect.state is RecordingState.Completed ||
                    effect.state is RecordingState.Failed ||
                    effect.state is RecordingState.Idle
                ) {
                    closeSessionResources(effect.state.recordingIdOrNull())
                }
                // Terminal publication releases the app-wide microphone/speech
                // barrier. The physical capture must have been closed first.
                runCatching { listener.onRecordingStateChanged(effect.state) }
            }
            is RecordingEffect.RequestAudioFocus -> requestAudioFocus(effect.recordingId)
            is RecordingEffect.StartCapture -> startCapture(effect.recordingId)
            is RecordingEffect.StopCapture -> {
                runCatching { resourcesFor(effect.recordingId)?.maximumDurationDeadline?.cancel() }
                runCatching { resourcesFor(effect.recordingId)?.progressInactivityDeadline?.cancel() }
                runCatching { resourcesFor(effect.recordingId)?.capture?.requestStop() }
                    .onFailure {
                        dispatchAudioCaptureFailure(
                            effect.recordingId,
                            AudioCaptureFailure.READ_FAILED,
                        )
                    }
            }
            is RecordingEffect.SubmitAudioChunk -> submitAudioChunk(effect.chunk)
            is RecordingEffect.FinishTranscription -> finishTranscription(effect.recordingId)
            is RecordingEffect.CancelTranscription -> {
                runCatching { resourcesFor(effect.recordingId)?.sttSession?.cancel() }
            }
            is RecordingEffect.ReleaseAudioFocus -> runCatching { audioFocus.abandon() }
            is RecordingEffect.UserMessageReady -> {
                if (!processStopping.get()) {
                    runCatching {
                        listener.onUserMessageReady(effect.recordingId, effect.transcript)
                    }
                }
            }
            is RecordingEffect.StartRejectedBusy -> {
                runCatching { listener.onStartRejected(effect.activeRecordingId) }
            }
        }
    }

    private fun requestAudioFocus(recordingId: RecordingId) {
        val result = runCatching {
            audioFocus.request { change ->
                if (change == RecordingAudioFocusChange.LOST_PERMANENTLY) {
                    captureStartupStops[recordingId]?.compareAndSet(
                        null,
                        RecordingStopReason.PERMANENT_AUDIO_FOCUS_LOSS,
                    )
                }
                dispatcher.dispatch {
                    executeEffects(stateMachine.onAudioFocusChanged(recordingId, change))
                }
            }
        }.getOrDefault(AudioFocusRequestResult.DENIED)
        if (processStopping.get()) {
            // A synchronous focus request can return after shutdown was requested,
            // before the state machine has recorded ownership of the granted lease.
            if (result == AudioFocusRequestResult.GRANTED) runCatching { audioFocus.abandon() }
            executeEffects(stateMachine.onProcessStopping())
            return
        }
        // request() is synchronous. Resolve the state in the same serialized
        // event so an external stop cannot slip between a granted focus lease
        // and the machine learning that it owns that lease.
        executeEffects(
            stateMachine.onAudioFocusRequestResult(
                recordingId = recordingId,
                result = result,
                nowMillis = clock.nowMillis(),
            ),
        )
    }

    private fun startCapture(recordingId: RecordingId) {
        if (!captureStartAllowed(recordingId)) return
        // openSession starts the asynchronous transport handshake, not audio capture.
        // Let that work overlap stopping speech, for this explicit dictation only.
        val sttSession = try {
            sttProvider.openSession(
                recordingId = recordingId,
                format = config.audioFormat,
                progressListener = RecordingProgressListener { progress ->
                    dispatcher.dispatch { noteRecordingProgress(recordingId, progress) }
                },
            )
        } catch (_: Exception) {
            dispatcher.dispatch {
                executeEffects(stateMachine.onTranscriptionFailure(recordingId))
            }
            return
        }
        if (!captureStartAllowed(recordingId)) {
            runCatching { sttSession.cancel() }
            return
        }
        val sessionResources = SessionResources(
            recordingId = recordingId,
            sttSession = sttSession,
            lastProgressMillis = clock.nowMillis(),
        )
        resources = sessionResources

        val captureReady = runCatching { captureStartBarrier.awaitCaptureReady() }
            .getOrDefault(false)
        if (!captureStartAllowed(recordingId)) return
        if (!captureReady) {
            executeEffects(
                stateMachine.onAudioCaptureFailure(
                    recordingId,
                    AudioCaptureFailure.START_FAILED,
                ),
            )
            return
        }

        val capture = try {
            captureFactory.create(recordingId, config.audioFormat, config.chunkBytes)
        } catch (_: SecurityException) {
            dispatchAudioCaptureFailure(recordingId, AudioCaptureFailure.PERMISSION_DENIED)
            return
        } catch (_: Exception) {
            dispatchAudioCaptureFailure(recordingId, AudioCaptureFailure.INITIALIZATION_FAILED)
            return
        }
        if (!captureStartAllowed(recordingId) || resources !== sessionResources) {
            runCatching { capture.close() }
            return
        }
        sessionResources.capture = capture
        val recordingState = stateMachine.state() as? RecordingState.Recording
        val deadlineMillis = recordingState?.deadlineMillis
            ?: safeAdd(clock.nowMillis(), config.maximumDurationMillis)
        scheduleDeadline(sessionResources, deadlineMillis)
        scheduleProgressInactivityCheck(
            sessionResources,
            config.progressInactivityTimeoutMillis,
        )

        if (!captureStartAllowed(recordingId)) return
        try {
            capture.start(
                object : PcmAudioCapture.Listener {
                    override fun onAudioChunk(pcmBytes: ByteArray, capturedAtMillis: Long) {
                        dispatcher.dispatch {
                            if (
                                speechActivityDetector.containsSpeechLikeActivity(
                                    pcmBytes,
                                    config.audioFormat,
                                )
                            ) {
                                noteRecordingProgress(
                                    recordingId,
                                    RecordingProgress.LOCAL_SPEECH_ACTIVITY,
                                )
                            }
                            executeEffects(
                                stateMachine.onAudioChunk(
                                    recordingId,
                                    pcmBytes,
                                    capturedAtMillis,
                                ),
                            )
                        }
                    }

                    override fun onCaptureStopped(
                        finalPcmBytes: ByteArray,
                        capturedAtMillis: Long,
                    ) {
                        dispatcher.dispatch {
                            executeEffects(
                                stateMachine.onCaptureStopped(
                                    recordingId,
                                    finalPcmBytes,
                                    capturedAtMillis,
                                ),
                            )
                        }
                    }

                    override fun onCaptureFailure(failure: AudioCaptureFailure) {
                        dispatchAudioCaptureFailure(recordingId, failure)
                    }
                },
            )
        } catch (_: SecurityException) {
            dispatchAudioCaptureFailure(recordingId, AudioCaptureFailure.PERMISSION_DENIED)
        } catch (_: Exception) {
            dispatchAudioCaptureFailure(recordingId, AudioCaptureFailure.START_FAILED)
        }
    }

    /** Called again after every external startup operation, not just at enqueue time. */
    private fun captureStartAllowed(recordingId: RecordingId): Boolean {
        if (processStopping.get()) {
            executeEffects(stateMachine.onProcessStopping())
            closeSessionResources(recordingId)
            return false
        }
        captureStartupStops[recordingId]?.get()?.let { reason ->
            executeEffects(stateMachine.cancelBeforeCapture(recordingId, reason))
            return false
        }
        val current = stateMachine.state()
        if (current !is RecordingState.Recording || current.recordingId != recordingId) {
            return false
        }
        // Permission can be revoked while network setup or speech shutdown is in flight.
        if (!permissionChecker.isRecordAudioGranted()) {
            executeEffects(
                stateMachine.onAudioCaptureFailure(recordingId, AudioCaptureFailure.PERMISSION_DENIED),
            )
            return false
        }
        return true
    }

    private fun scheduleDeadline(resources: SessionResources, deadlineMillis: Long) {
        val delay = (deadlineMillis - clock.nowMillis()).coerceAtLeast(0L)
        resources.maximumDurationDeadline = deadlineScheduler.schedule(delay) {
            dispatcher.dispatch {
                val effects = stateMachine.onMaximumDurationReached(
                    resources.recordingId,
                    clock.nowMillis(),
                )
                executeEffects(effects)
                if (
                    effects.isEmpty() &&
                    stateMachine.state() is RecordingState.Recording &&
                    resourcesFor(resources.recordingId) === resources
                ) {
                    scheduleDeadline(resources, deadlineMillis)
                }
            }
        }
    }

    /**
     * One scheduled check is retained at a time. Progress only updates a
     * monotonic stamp; it does not continuously cancel and recreate timers for
     * every 250 ms audio chunk.
     */
    private fun scheduleProgressInactivityCheck(
        resources: SessionResources,
        delayMillis: Long,
    ) {
        resources.progressInactivityDeadline = deadlineScheduler.schedule(delayMillis) {
            dispatcher.dispatch {
                if (resourcesFor(resources.recordingId) !== resources) return@dispatch
                if (stateMachine.state() !is RecordingState.Recording) return@dispatch

                val now = clock.nowMillis()
                val inactivityDeadline = safeAdd(
                    resources.lastProgressMillis,
                    config.progressInactivityTimeoutMillis,
                )
                val remaining = inactivityDeadline - now
                if (remaining > 0L) {
                    scheduleProgressInactivityCheck(resources, remaining)
                } else {
                    executeEffects(
                        stateMachine.onTranscriptProgressTimeout(resources.recordingId),
                    )
                }
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun noteRecordingProgress(
        recordingId: RecordingId,
        progress: RecordingProgress,
    ) {
        val current = resourcesFor(recordingId) ?: return
        if (stateMachine.state() !is RecordingState.Recording) return
        current.lastProgressMillis = maxOf(current.lastProgressMillis, clock.nowMillis())
    }

    private fun submitAudioChunk(chunk: PcmAudioChunk) {
        if (processStopping.get()) return
        val sttSession = resourcesFor(chunk.recordingId)?.sttSession
        if (sttSession == null) {
            dispatcher.dispatch {
                executeEffects(stateMachine.onTranscriptionFailure(chunk.recordingId))
            }
            return
        }
        try {
            sttSession.submitChunk(chunk) { result ->
                dispatcher.dispatch {
                    executeEffects(
                        result.fold(
                            onSuccess = {
                                stateMachine.onChunkAccepted(chunk.recordingId, chunk.index)
                            },
                            onFailure = {
                                stateMachine.onChunkRejected(chunk.recordingId)
                            },
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            dispatcher.dispatch {
                executeEffects(stateMachine.onChunkRejected(chunk.recordingId))
            }
        }
    }

    private fun finishTranscription(recordingId: RecordingId) {
        if (processStopping.get()) return
        val sttSession = resourcesFor(recordingId)?.sttSession
        if (sttSession == null) {
            dispatcher.dispatch {
                executeEffects(stateMachine.onTranscriptionFailure(recordingId))
            }
            return
        }
        try {
            sttSession.finish { result ->
                dispatcher.dispatch {
                    executeEffects(
                        result.fold(
                            onSuccess = { stateMachine.onFinalTranscript(recordingId, it) },
                            onFailure = { stateMachine.onTranscriptionFailure(recordingId) },
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            dispatcher.dispatch {
                executeEffects(stateMachine.onTranscriptionFailure(recordingId))
            }
        }
    }

    private fun dispatchAudioCaptureFailure(
        recordingId: RecordingId,
        failure: AudioCaptureFailure,
    ) {
        dispatcher.dispatch {
            executeEffects(stateMachine.onAudioCaptureFailure(recordingId, failure))
        }
    }

    private fun closeSessionResources(recordingId: RecordingId?) {
        val current = resources ?: return
        if (recordingId != null && current.recordingId != recordingId) return
        resources = null
        runCatching { current.maximumDurationDeadline?.cancel() }
        runCatching { current.progressInactivityDeadline?.cancel() }
        runCatching { current.capture?.close() }
    }

    private fun resourcesFor(recordingId: RecordingId): SessionResources? {
        return resources?.takeIf { it.recordingId == recordingId }
    }

    private fun RecordingState.recordingIdOrNull(): RecordingId? = when (this) {
        RecordingState.Idle -> null
        is RecordingState.AwaitingAudioFocus -> recordingId
        is RecordingState.Recording -> recordingId
        is RecordingState.Stopping -> recordingId
        is RecordingState.Finalizing -> recordingId
        is RecordingState.Completed -> recordingId
        is RecordingState.Failed -> recordingId
    }

    private fun safeAdd(left: Long, right: Long): Long {
        return if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }

    private data class SessionResources(
        val recordingId: RecordingId,
        val sttSession: IncrementalSttSession,
        var capture: PcmAudioCapture? = null,
        var maximumDurationDeadline: ScheduledRecordingDeadline? = null,
        var progressInactivityDeadline: ScheduledRecordingDeadline? = null,
        var lastProgressMillis: Long,
    )
}
