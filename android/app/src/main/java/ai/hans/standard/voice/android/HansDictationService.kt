package ai.hans.standard.voice.android

import ai.hans.standard.HansApplication
import ai.hans.standard.voice.DictationCaptureStartBarrier
import ai.hans.standard.voice.DictationForegroundServiceCore
import ai.hans.standard.voice.DictationRecordingConfig
import ai.hans.standard.voice.DictationRecordingListener
import ai.hans.standard.voice.ExecutorRecordingDeadlineScheduler
import ai.hans.standard.voice.ExecutorRecordingTaskDispatcher
import ai.hans.standard.voice.MonotonicClock
import ai.hans.standard.voice.PcmAudioFormat
import ai.hans.standard.voice.RecordAudioPermissionChecker
import ai.hans.standard.voice.RecordingFailure
import ai.hans.standard.voice.RecordingForegroundHost
import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingIdGenerator
import ai.hans.standard.voice.RecordingState
import ai.hans.standard.voice.openAiDictationFailure
import ai.hans.standard.voice.stt.android.AndroidSttTranscriptionContextSource
import ai.hans.standard.voice.stt.android.OpenAiRealtimeTranscriptionProvider
import ai.hans.standard.voice.stt.android.RealtimeTranscriptionObserver
import ai.hans.standard.voice.stt.android.SttLatencyPreferenceStore
import ai.hans.standard.voice.stt.SttTranscriptionDelay
import ai.hans.standard.voice.tts.android.SpeechCredentialStatus
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.Closeable

/** Concrete, root-free microphone owner. It survives launcher/app switches. */
class HansDictationService : BaseDictationForegroundService(), DictationRecordingListener {
    private var deadlineScheduler: ExecutorRecordingDeadlineScheduler? = null
    private var dispatcher: ExecutorRecordingTaskDispatcher? = null
    private var sttProvider: OpenAiRealtimeTranscriptionProvider? = null
    private var internetSubscription: Closeable? = null
    @Volatile private var lastTranscriptionFailureCode: String? = null

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
        val latencyPreferences = SttLatencyPreferenceStore(this)
        val newSttProvider = OpenAiRealtimeTranscriptionProvider(
            host.speechCredentialStore,
            delaySource = latencyPreferences::read,
            contextSource = AndroidSttTranscriptionContextSource(this),
            observer = object : RealtimeTranscriptionObserver {
                override fun onPartialTranscript(recordingId: RecordingId, transcript: String) {
                    serviceOwners.runIfOwner(this@HansDictationService) {
                        HansDictationRuntime.publishPartial(recordingId, transcript)
                    }
                }

                override fun onTranscriptionDelayConfirmed(
                    recordingId: RecordingId,
                    delay: SttTranscriptionDelay,
                ) {
                    serviceOwners.runIfOwner(this@HansDictationService) {
                        HansDictationRuntime.confirmTranscriptionDelay(recordingId, delay)
                    }
                }

                override fun onFailure(recordingId: RecordingId, code: String) {
                    lastTranscriptionFailureCode = code
                    Log.i(DIAGNOSTIC_TAG, "dictation_failure=$code")
                }

                override fun onInterruptedTranscript(recordingId: RecordingId, transcript: String) {
                    host.preserveInterruptedDictation(transcript)
                }
            },
        ).also { sttProvider = it }
        internetSubscription = (application as HansApplication).internetConnectivity.observe {
            if (!it.status.permitsExplicitRequest) newSttProvider.onNetworkUnavailable()
        }
        return DictationForegroundServiceCore(
            config = DictationRecordingConfig(
                audioFormat = PcmAudioFormat(sampleRateHz = 24_000),
                chunkDurationMillis = 250L,
                maximumDurationMillis = DictationRecordingConfig.HARD_MAXIMUM_DURATION_MILLIS,
            ),
            clock = MonotonicClock { SystemClock.elapsedRealtime() },
            permissionChecker = RecordAudioPermissionChecker {
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            },
            captureStartBarrier = DictationCaptureStartBarrier(
                host::awaitDictationCaptureReady,
            ),
            audioFocus = AndroidRecordingAudioFocusCoordinator(this),
            captureFactory = AndroidPcmAudioCaptureFactory(
                MonotonicClock { SystemClock.elapsedRealtime() },
            ),
            sttProvider = newSttProvider,
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
        when (intent?.action) {
            ACTION_START -> if (!recordingState().isActive()) requestRecording()
            ACTION_STOP -> {
                val current = recordingState()
                current.recordingIdOrNull()?.takeIf { current.isActive() }
                    ?.let(::stopRecording)
                    ?: stopSelf(startId)
            }
            ACTION_TOGGLE -> {
                val current = recordingState()
                current.recordingIdOrNull()?.takeIf { current.isActive() }
                    ?.let(::stopRecording)
                    ?: requestRecording()
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
        val published = if (
            state is RecordingState.Failed &&
            state.failure == RecordingFailure.TRANSCRIPTION_FAILED
        ) {
            state.copy(failure = openAiDictationFailure(lastTranscriptionFailureCode))
        } else {
            state
        }
        if (!serviceOwners.runIfOwner(this) {
                HansDictationRuntime.publish(published)
                (application as HansApplication).sessionHost.setDictationActive(published.isActive())
            }
        ) return
        if (published is RecordingState.Failed) stopSelf()
    }

    override fun onUserMessageReady(recordingId: RecordingId, transcript: String) {
        if (!serviceOwners.runIfOwner(this) {
                val saved = (application as HansApplication).sessionHost
                    .submitDictationTranscript(transcript, recordingId)
                if (!saved) HansDictationRuntime.reportStartFailure(RecordingFailure.INTERNAL_ERROR)
            }
        ) return
        stopSelf()
    }

    private fun requestRecording() {
        val credentialStatus = (application as HansApplication).sessionHost.speechCredentialStatus()
        when (credentialStatus) {
            SpeechCredentialStatus.MISSING -> {
                HansDictationRuntime.reportStartFailure(RecordingFailure.SPEECH_CREDENTIAL_MISSING)
                stopSelf()
                return
            }
            SpeechCredentialStatus.TEMPORARILY_UNAVAILABLE -> {
                HansDictationRuntime.reportStartFailure(
                    RecordingFailure.SPEECH_CREDENTIAL_TEMPORARILY_UNAVAILABLE,
                )
                stopSelf()
                return
            }
            SpeechCredentialStatus.AVAILABLE -> Unit
        }
        val internet = (application as HansApplication).internetConnectivity.snapshot().status
        if (!internet.permitsExplicitRequest) {
            HansDictationRuntime.reportStartFailure(RecordingFailure.NETWORK_UNAVAILABLE)
            android.widget.Toast.makeText(this, internet.notice, android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        lastTranscriptionFailureCode = null
        startRecording()
    }

    override fun onStartRejected(activeRecordingId: RecordingId) = Unit

    override fun releaseRecordingResources() {
        ignoreExpectedRuntimeFailure { internetSubscription?.close() }
        internetSubscription = null
        ignoreExpectedRuntimeFailure { sttProvider?.close() }
        sttProvider = null
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
            if (HansDictationRuntime.snapshotUi().phase.isActiveRecordingPhase()) {
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
