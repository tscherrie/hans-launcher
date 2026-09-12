package ai.hans.standard.voice

/** Host boundary implemented by an Android Service, and faked in unit tests. */
interface RecordingForegroundHost {
    fun showRecordingForeground(state: RecordingState)

    fun leaveRecordingForeground()
}

/**
 * Lifecycle core owned by a foreground service. It has no Activity reference,
 * so app switching, computer use and launcher replacement cannot stop capture.
 */
class DictationForegroundServiceCore(
    config: DictationRecordingConfig,
    clock: MonotonicClock,
    permissionChecker: RecordAudioPermissionChecker,
    captureStartBarrier: DictationCaptureStartBarrier,
    audioFocus: RecordingAudioFocusCoordinator,
    captureFactory: PcmAudioCaptureFactory,
    sttProvider: IncrementalSttProvider,
    deadlineScheduler: RecordingDeadlineScheduler,
    dispatcher: RecordingTaskDispatcher,
    private val foregroundHost: RecordingForegroundHost,
    private val downstreamListener: DictationRecordingListener,
    nextRecordingId: () -> RecordingId = RecordingIdGenerator()::next,
) : DictationRecordingListener {
    private val coordinator = DictationRecordingCoordinator(
        config = config,
        clock = clock,
        permissionChecker = permissionChecker,
        captureStartBarrier = captureStartBarrier,
        audioFocus = audioFocus,
        captureFactory = captureFactory,
        sttProvider = sttProvider,
        deadlineScheduler = deadlineScheduler,
        dispatcher = dispatcher,
        listener = this,
        nextRecordingId = nextRecordingId,
    )

    fun state(): RecordingState = coordinator.state()

    fun startRecording(): RecordingId = coordinator.startRecording()

    fun stopRecording(recordingId: RecordingId) = coordinator.stopRecording(recordingId)

    fun onEnvironmentEvent(event: VoiceEnvironmentEvent) {
        coordinator.onEnvironmentEvent(event)
    }

    fun onProcessStopping(onStopped: () -> Unit = {}) {
        coordinator.onProcessStopping(onStopped)
    }

    override fun onRecordingStateChanged(state: RecordingState) {
        when (state) {
            is RecordingState.AwaitingAudioFocus,
            is RecordingState.Recording,
            is RecordingState.Stopping,
            is RecordingState.Finalizing,
            -> foregroundHost.showRecordingForeground(state)
            RecordingState.Idle,
            is RecordingState.Completed,
            is RecordingState.Failed,
            -> foregroundHost.leaveRecordingForeground()
        }
        downstreamListener.onRecordingStateChanged(state)
    }

    override fun onUserMessageReady(recordingId: RecordingId, transcript: String) {
        downstreamListener.onUserMessageReady(recordingId, transcript)
    }

    override fun onStartRejected(activeRecordingId: RecordingId) {
        downstreamListener.onStartRejected(activeRecordingId)
    }
}
