package ai.hans.standard.voice.android

import ai.hans.standard.localization.AndroidHansTextResolver

import ai.hans.standard.HansApplication
import ai.hans.standard.voice.DictationCaptureStartBarrier
import ai.hans.standard.voice.DictationForegroundServiceCore
import ai.hans.standard.voice.DictationRecordingListener
import ai.hans.standard.voice.ExecutorRecordingDeadlineScheduler
import ai.hans.standard.voice.ExecutorRecordingTaskDispatcher
import ai.hans.standard.voice.MonotonicClock
import ai.hans.standard.voice.RecordAudioPermissionChecker
import ai.hans.standard.voice.RecordingFailure
import ai.hans.standard.voice.RecordingForegroundHost
import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingIdGenerator
import ai.hans.standard.voice.RecordingState
import ai.hans.standard.voice.realtime.AndroidLiveVoiceRuntime
import ai.hans.standard.voice.realtime.LiveVoiceEntryPoint
import ai.hans.standard.voice.stt.CodexBatchTranscriptionObserver
import ai.hans.standard.voice.stt.CodexBatchTranscriptionProvider
import ai.hans.standard.voice.stt.BoundedPcmInputProvider
import ai.hans.standard.voice.stt.android.AndroidCodexBatchTranscriptionGateway
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.Closeable

/** Concrete, root-free microphone owner. It survives launcher/app switches. */
class HansDictationService : BaseDictationForegroundService(), DictationRecordingListener {
    private var deadlineScheduler: ExecutorRecordingDeadlineScheduler? = null
    private var dispatcher: ExecutorRecordingTaskDispatcher? = null
    private var sttProvider: AutoCloseable? = null
    private var internetSubscription: Closeable? = null
    private var batchGateway: AndroidCodexBatchTranscriptionGateway? = null
    @Volatile private var admissionStillValid: () -> Boolean = { false }
    private val commandRetirement = DictationCommandRetirement()
    private val retirementHandler = Handler(Looper.getMainLooper())

    override fun createServiceCore(
        foregroundHost: RecordingForegroundHost,
    ): DictationForegroundServiceCore {
        check(serviceOwners.acquire(this)) { "Previous dictation service is still stopping" }
        val newDeadlineScheduler = ExecutorRecordingDeadlineScheduler().also {
            deadlineScheduler = it
        }
        val newDispatcher = ExecutorRecordingTaskDispatcher().also {
            dispatcher = it
        }
        val host = (application as HansApplication).sessionHost
        // Each recording gets a fresh context lease. The gateway consults the currently active
        // upload lease; final text delivery consults that recording's retained original lease.
        val gateway = AndroidCodexBatchTranscriptionGateway(this) { admissionStillValid() }.also {
            batchGateway = it
        }
        val newSttProvider = CodexBatchTranscriptionProvider(
            gateway = gateway,
            observer = object : CodexBatchTranscriptionObserver {
                override fun onFailure(recordingId: RecordingId, code: String) {
                    serviceOwners.runIfOwner(this@HansDictationService) {
                        ai.hans.standard.voice.feedback.HansSpeechFailureRuntime.report(code)
                    }
                    Log.i(DIAGNOSTIC_TAG, "dictation_failure=$code")
                }
            },
        )
        sttProvider = AutoCloseable {
            try { newSttProvider.close() } finally { gateway.close() }
        }
        internetSubscription = (application as HansApplication).internetConnectivity.observe {
            if (!it.status.permitsExplicitRequest) newSttProvider.onNetworkUnavailable()
        }
        val boundedInputProvider = BoundedPcmInputProvider(
            delegate = newSttProvider,
            maximumPcmBytes = CodexBatchTranscriptionProvider.MAX_PCM_BYTES,
            maximumChunkBytes = CodexDictationIntegration.recordingConfig.chunkBytes,
            onLimitReached = { recordingId ->
                Log.i(DIAGNOSTIC_TAG, "dictation_pcm_limit_reached")
                stopRecording(recordingId)
            },
        )
        return DictationForegroundServiceCore(
            config = CodexDictationIntegration.recordingConfig,
            clock = MonotonicClock { SystemClock.elapsedRealtime() },
            permissionChecker = RecordAudioPermissionChecker {
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            },
            captureStartBarrier = DictationCaptureStartBarrier {
                !phoneOwnsCapture() && host.awaitDictationCaptureReady() && !phoneOwnsCapture()
            },
            audioFocus = AndroidRecordingAudioFocusCoordinator(this),
            captureFactory = AndroidPcmAudioCaptureFactory(
                MonotonicClock { SystemClock.elapsedRealtime() },
            ),
            sttProvider = boundedInputProvider,
            deadlineScheduler = newDeadlineScheduler,
            dispatcher = newDispatcher,
            foregroundHost = foregroundHost,
            downstreamListener = this,
            nextRecordingId = processRecordingIds::next,
        )
    }

    override fun onRecordingStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val current = recordingState()
        // Pending STARTs must be visible before their queued AwaitingAudioFocus publication.
        val activeRecordingId = commandRetirement.activeRecordingId()
            ?: current.recordingIdOrNull()?.takeIf { current.isActive() }
        when (intent?.action) {
            ACTION_START -> if (activeRecordingId != null) {
                commandRetirement.associateCommand(activeRecordingId, startId)
            } else requestRecording(startId)
            ACTION_STOP -> {
                if (activeRecordingId != null) {
                    commandRetirement.associateCommand(activeRecordingId, startId)
                    commandRetirement.expectStartupCancellation(activeRecordingId)
                    stopRecording(activeRecordingId)
                } else stopSelf(startId)
            }
            ACTION_TOGGLE -> {
                if (activeRecordingId != null) {
                    commandRetirement.associateCommand(activeRecordingId, startId)
                    if (current.recordingIdOrNull() == activeRecordingId &&
                        (current is RecordingState.Stopping || current is RecordingState.Finalizing)) {
                        // Refresh retirement ownership only; never retry a finalizing recording.
                        Unit
                    } else {
                        commandRetirement.expectStartupCancellation(activeRecordingId)
                        stopRecording(activeRecordingId)
                    }
                } else {
                    requestRecording(startId)
                }
            }
            else -> stopSelf(startId)
        }
        return Service.START_NOT_STICKY
    }

    override fun requiresForegroundAcknowledgement(intent: Intent?): Boolean =
        requiresForegroundStart(intent?.action)

    override fun onRecordingRuntimeFailure(failure: RuntimeException) {
        // The exception itself can carry device/app details. Publish only a bounded diagnostic
        // code and a stable UI failure while keeping the shared Hans process alive.
        Log.i(DIAGNOSTIC_TAG, "dictation_lifecycle_rejected")
        HansDictationRuntime.reportStartFailure(RecordingFailure.INTERNAL_ERROR)
    }

    override fun onRecordingStateChanged(state: RecordingState) {
        if (state is RecordingState.Finalizing) commandRetirement.finalizing(state.recordingId)
        val cancelledStartup = if (state == RecordingState.Idle) {
            commandRetirement.takeIdleStartupCancellation()
        } else null
        // cancelBeforeCapture emits Completed without a transcript callback. A successful
        // upload always passed through Finalizing and must retain its lease until delivery.
        val completedWithoutTranscript = state is RecordingState.Completed &&
            commandRetirement.completedWithoutTranscript(state.recordingId)
        if (state is RecordingState.Completed || state is RecordingState.Failed) {
            state.recordingIdOrNull()?.let(commandRetirement::terminal)
        }
        val published = if (
            state is RecordingState.Failed &&
            state.failure == RecordingFailure.TRANSCRIPTION_FAILED
        ) {
            state.copy(failure = RecordingFailure.CODEX_TRANSCRIPTION_FAILED)
        } else {
            state
        }
        if (!serviceOwners.runIfOwner(this) {
                HansDictationRuntime.publish(published)
                (application as HansApplication).sessionHost.setDictationActive(published.isActive())
            }
        ) return
        if (published is RecordingState.Failed) retireRecording(published.recordingId)
        if (published is RecordingState.Completed && completedWithoutTranscript) {
            retireRecording(published.recordingId)
        }
        cancelledStartup?.let(::retireRecording)
    }

    override fun onUserMessageReady(recordingId: RecordingId, transcript: String) {
        val recordingAdmission = commandRetirement.admission(recordingId) ?: return
        var finishCurrentRecording = false
        if (!serviceOwners.runIfOwner(this) {
                finishCurrentRecording = true
                if (recordingAdmission() && commandRetirement.claimDelivery(recordingId)) {
                    // Existing durable Start/Steer delivery owns the only submitted user message.
                    val saved = (application as HansApplication).sessionHost.submitDictationTranscript(
                        transcript, recordingId, admissionStillValid = recordingAdmission,
                    )
                    if (!saved && HansDictationRuntime.snapshotUi().activeRecordingId == recordingId) {
                        HansDictationRuntime.reportStartFailure(RecordingFailure.INTERNAL_ERROR)
                    }
                } else if (!recordingAdmission() &&
                    HansDictationRuntime.snapshotUi().activeRecordingId == recordingId) {
                    HansDictationRuntime.reportStartFailure(RecordingFailure.CODEX_TRANSCRIPTION_FAILED)
                }
            }) return
        if (finishCurrentRecording) retireRecording(recordingId)
    }

    private fun requestRecording(startId: Int) {
        // Capture starts locally; there is no Live connection and never a paid API fallback.
        // The recording core is lazy: probe without requiring batchGateway to exist yet.
        val helperAvailable = batchGateway?.isAvailable() ?: AndroidCodexBatchTranscriptionGateway(this)
            .use { it.isAvailable() }
        if (!(application as HansApplication).sessionHost.hasCodexSpeechAccess() ||
            !helperAvailable || phoneOwnsCapture()) {
            HansDictationRuntime.reportStartFailure(RecordingFailure.CODEX_TRANSCRIPTION_FAILED)
            stopSelf(startId)
            return
        }
        val internet = (application as HansApplication).internetConnectivity.snapshot().status
        if (!internet.permitsExplicitRequest) {
            HansDictationRuntime.reportStartFailure(RecordingFailure.NETWORK_UNAVAILABLE)
            android.widget.Toast.makeText(this, internet.notice(AndroidHansTextResolver(this)), android.widget.Toast.LENGTH_LONG).show()
            stopSelf(startId)
            return
        }
        val lease = newAdmissionLease()
        admissionStillValid = lease
        val recordingId = startRecording()
        commandRetirement.started(recordingId, startId, lease)
    }

    private fun newAdmissionLease(): () -> Boolean {
        // The helper owns credentials; the lease contains only existing account/context state.
        val host = (application as HansApplication).sessionHost
        val admitted = host.snapshot()
        return {
            val current = host.snapshot()
            host.hasCodexSpeechAccess() && admitted != null && current != null &&
                current.generation == admitted.generation &&
                current.session.currentThreadId == admitted.session.currentThreadId &&
                current.session.account.identity == admitted.session.account.identity
        }
    }

    private fun retireRecording(recordingId: RecordingId) {
        // Posting lets a just-returning startRecording() bind its command ID after an early
        // async failure. An unrelated later START has a newer Android startId and survives.
        retirementHandler.post {
            commandRetirement.takeRetirementStartId(recordingId)?.let { startId -> stopSelf(startId) }
        }
    }

    override fun onStartRejected(activeRecordingId: RecordingId) = Unit

    override fun releaseRecordingResources() {
        ignoreExpectedRuntimeFailure { internetSubscription?.close() }
        internetSubscription = null
        ignoreExpectedRuntimeFailure { sttProvider?.close() }
        sttProvider = null
        batchGateway = null
        admissionStillValid = { false }
        retirementHandler.removeCallbacksAndMessages(null)
        commandRetirement.clear()
        ignoreExpectedRuntimeFailure { deadlineScheduler?.close() }
        deadlineScheduler = null
        ignoreExpectedRuntimeFailure { dispatcher?.close() }
        dispatcher = null
        serviceOwners.release(this) {
            ignoreExpectedRuntimeFailure {
                (application as? HansApplication)?.sessionHost?.setDictationActive(false)
            }
        }
    }

    companion object {
        private val serviceOwners = DictationServiceOwnerGate()
        private val processRecordingIds = RecordingIdGenerator()
        private const val DIAGNOSTIC_TAG = "HansSpeech"
        private const val ACTION_START = "ai.hans.standard.action.START_DICTATION"
        private const val ACTION_STOP = "ai.hans.standard.action.STOP_DICTATION"
        private const val ACTION_TOGGLE = "ai.hans.standard.action.TOGGLE_DICTATION"

        fun start(context: Context) {
            if (!hasRecordAudioPermission(context)) {
                HansDictationRuntime.reportStartFailure(RecordingFailure.PERMISSION_DENIED)
                return
            }
            if (phoneOwnsCapture()) {
                HansDictationRuntime.reportStartFailure(RecordingFailure.CODEX_TRANSCRIPTION_FAILED)
                return
            }
            dispatchDictationServiceCommand(
                dispatch = {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, HansDictationService::class.java).setAction(ACTION_START),
                    )
                    true
                },
                onRejected = ::reportDispatchFailure,
            )
        }

        fun stop(context: Context) {
            dispatchDictationServiceCommand(
                dispatch = {
                    context.startService(
                        Intent(context, HansDictationService::class.java).setAction(ACTION_STOP),
                    ) != null
                },
                // A rejected STOP must not replace an actually active microphone state with a
                // false terminal UI state. The running service retains its normal hard deadline.
                onRejected = {},
            )
        }

        fun toggle(context: Context) {
            val phase = HansDictationRuntime.snapshotUi().phase
            if (phase == DictationUiPhase.FINALIZING) return
            if (phase.isActiveRecordingPhase()) {
                stop(context)
                return
            }
            if (!hasRecordAudioPermission(context)) {
                HansDictationRuntime.reportStartFailure(RecordingFailure.PERMISSION_DENIED)
                return
            }
            dispatchDictationServiceCommand(
                dispatch = {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, HansDictationService::class.java).setAction(ACTION_TOGGLE),
                    )
                    true
                },
                onRejected = ::reportDispatchFailure,
            )
        }

        internal fun requiresForegroundStart(action: String?): Boolean =
            action == ACTION_START || action == ACTION_TOGGLE

        internal fun phoneOwnsCapture(): Boolean =
            AndroidLiveVoiceRuntime.entryPoint() == LiveVoiceEntryPoint.PHONE &&
                ai.hans.standard.ui.VoiceInputTransitionPolicy.liveOwnsVoice(
                    AndroidLiveVoiceRuntime.snapshot().phase,
                    AndroidLiveVoiceRuntime.isCaptureRequestedOrActive(),
                )

        private fun hasRecordAudioPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        private fun reportDispatchFailure() {
            Log.i(DIAGNOSTIC_TAG, "dictation_dispatch_rejected")
            HansDictationRuntime.reportStartFailure(RecordingFailure.INTERNAL_ERROR)
        }
    }
}

/** Contains Android 12+ background/while-in-use dispatch rejection without catching [Error]. */
internal fun dispatchDictationServiceCommand(
    dispatch: () -> Boolean,
    onRejected: () -> Unit,
): Boolean {
    val accepted = try {
        dispatch()
    } catch (_: RuntimeException) {
        false
    }
    if (!accepted) ignoreExpectedRuntimeFailure(onRejected)
    return accepted
}

internal fun DictationUiPhase.isActiveRecordingPhase(): Boolean = when (this) {
    DictationUiPhase.PREPARING,
    DictationUiPhase.LISTENING,
    DictationUiPhase.FINALIZING,
    -> true
    DictationUiPhase.IDLE,
    DictationUiPhase.WAITING_TO_SEND,
    DictationUiPhase.SENT,
    DictationUiPhase.NATIVE_COMPLETED,
    DictationUiPhase.FAILED,
    -> false
}

private fun RecordingState.isActive(): Boolean = when (this) {
    is RecordingState.AwaitingAudioFocus,
    is RecordingState.Recording,
    is RecordingState.Stopping,
    is RecordingState.Finalizing,
    -> true
    RecordingState.Idle,
    is RecordingState.Completed,
    is RecordingState.Failed,
    -> false
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
