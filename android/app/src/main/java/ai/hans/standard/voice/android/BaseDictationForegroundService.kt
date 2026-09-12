package ai.hans.standard.voice.android

import ai.hans.standard.voice.DictationForegroundServiceCore
import ai.hans.standard.voice.RecordingForegroundHost
import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingState
import ai.hans.standard.voice.VoiceEnvironmentEvent
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Android lifecycle shell. A concrete app service supplies the provider-bound
 * core and must later be declared with microphone foreground-service and
 * RECORD_AUDIO permissions in the manifest integration phase.
 */
abstract class BaseDictationForegroundService : Service() {
    private val localBinder = LocalBinder()
    private val serviceCoreLock = Any()
    @Volatile
    private var serviceCore: DictationForegroundServiceCore? = null
    private var teardownStarted = false
    private val lifecycleHandler by lazy { Handler(Looper.getMainLooper()) }
    private lateinit var recordingForegroundHost: AndroidRecordingForegroundHost

    final override fun onCreate() {
        super.onCreate()
        recordingForegroundHost = AndroidRecordingForegroundHost(this)
    }

    protected abstract fun createServiceCore(
        foregroundHost: RecordingForegroundHost,
    ): DictationForegroundServiceCore

    final override fun onBind(intent: Intent?): IBinder = localBinder

    /**
     * The foreground acknowledgement intentionally lives in the final Android entry point.
     * [onCreate] must remain cheap: creating the recording core can lazily boot App Server and
     * therefore take longer than Android's foreground-service deadline on a cold process.
     */
    final override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        runDictationForegroundEntry(
            acknowledgeForeground = if (requiresForegroundAcknowledgement(intent)) {
                recordingForegroundHost::acknowledgeForegroundServiceStart
            } else {
                null
            },
            runCommand = { onRecordingStartCommand(intent, flags, startId) },
            onRuntimeFailure = { failure ->
                // Android can reject microphone promotion because the app lost its foreground
                // eligibility between dispatch and delivery. Core construction is fallible too.
                // Neither case is allowed to escape a Service callback and kill the shared Hans
                // process (which also owns Accessibility).
                ignoreExpectedRuntimeFailure { onRecordingRuntimeFailure(failure) }
                stopCoreAndReleaseResources()
                ignoreExpectedRuntimeFailure { stopSelf(startId) }
            },
        )

    protected open fun requiresForegroundAcknowledgement(intent: Intent?): Boolean = false

    /** Publishes a bounded user-visible failure without exposing exception details. */
    protected open fun onRecordingRuntimeFailure(failure: RuntimeException) = Unit

    protected open fun onRecordingStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY

    protected fun recordingState(): RecordingState = serviceCore?.state() ?: RecordingState.Idle

    protected fun startRecording(): RecordingId = requireServiceCore().startRecording()

    protected fun stopRecording(recordingId: RecordingId) {
        serviceCore?.stopRecording(recordingId)
    }

    final override fun onDestroy() {
        stopCoreAndReleaseResources()
        super.onDestroy()
    }

    private fun stopCoreAndReleaseResources() {
        val initializedCore = synchronized(serviceCoreLock) {
            if (teardownStarted) return
            teardownStarted = true
            serviceCore.also { serviceCore = null }
        }
        val release = {
            // A partial factory failure or an Idle core can still own a provisional
            // foreground notification. Release exactly once, including those paths.
            ignoreExpectedRuntimeFailure { recordingForegroundHost.leaveRecordingForeground() }
            ignoreExpectedRuntimeFailure { releaseRecordingResources() }
        }
        if (initializedCore == null) {
            release()
        } else {
            // Never close the provider/dispatcher or lower the microphone barrier
            // while its queued capture cleanup is pending. Do not wait on the UI thread.
            initializedCore.onProcessStopping {
                lifecycleHandler.post { release() }
            }
        }
    }

    protected open fun releaseRecordingResources() = Unit

    inner class LocalBinder : Binder() {
        fun state(): RecordingState = recordingState()

        fun startRecording(): RecordingId = requireServiceCore().startRecording()

        fun stopRecording(recordingId: RecordingId) {
            serviceCore?.stopRecording(recordingId)
        }

        fun onEnvironmentEvent(event: VoiceEnvironmentEvent) {
            serviceCore?.onEnvironmentEvent(event)
        }
    }

    private fun requireServiceCore(): DictationForegroundServiceCore {
        serviceCore?.let { return it }
        return synchronized(serviceCoreLock) {
            check(!teardownStarted) { "Dictation service is stopping" }
            serviceCore ?: createServiceCore(recordingForegroundHost).also { serviceCore = it }
        }
    }
}

class AndroidRecordingForegroundHost(
    private val service: Service,
) : RecordingForegroundHost {
    private val notificationManager = service.getSystemService(NotificationManager::class.java)
    private var lastStatus: String? = null

    // This reusable abstract shell cannot itself be declared in the manifest.
    // Every concrete subclass must declare foregroundServiceType="microphone".
    @SuppressLint("ForegroundServiceType")
    override fun showRecordingForeground(state: RecordingState) {
        val status = when (state) {
            is RecordingState.AwaitingAudioFocus -> "Preparing microphone"
            is RecordingState.Recording -> "Listening"
            is RecordingState.Stopping -> "Finishing audio"
            is RecordingState.Finalizing -> "Finalizing dictation"
            else -> return
        }
        // Initial promotion is handled synchronously by acknowledgeForegroundServiceStart(),
        // where rejection can stop the service cleanly. Later notification-only status updates
        // must not crash the process if the framework rejects a refresh during teardown.
        ignoreExpectedRuntimeFailure { showForegroundStatus(status) }
    }

    /** Synchronously fulfils Android's foreground-start contract before any fallible work. */
    @SuppressLint("ForegroundServiceType")
    fun acknowledgeForegroundServiceStart() {
        showForegroundStatus("Preparing microphone")
    }

    @SuppressLint("ForegroundServiceType")
    private fun showForegroundStatus(status: String) {
        if (status == lastStatus) return
        ensureChannel()
        service.startForeground(
            NOTIFICATION_ID,
            Notification.Builder(service, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Hans dictation")
                .setContentText(status)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        // Commit only after startForeground succeeds. A failed promotion must remain retryable
        // instead of poisoning the host with a status that was never actually published.
        lastStatus = status
    }

    override fun leaveRecordingForeground() {
        if (lastStatus == null) return
        lastStatus = null
        ignoreExpectedRuntimeFailure {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun ensureChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Dictation recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows when Hans is recording a dictation"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "hans_dictation_recording"
        private const val NOTIFICATION_ID = 7_401
    }
}

/**
 * Process-survival boundary for Android Service entry points.
 *
 * Only expected framework/application [RuntimeException] failures are contained. VM and linkage
 * [Error]s deliberately propagate because continuing after those would hide process corruption.
 */
internal fun runDictationForegroundEntry(
    acknowledgeForeground: (() -> Unit)?,
    runCommand: () -> Int,
    onRuntimeFailure: (RuntimeException) -> Unit,
): Int = try {
    acknowledgeForeground?.invoke()
    runCommand()
} catch (failure: RuntimeException) {
    ignoreExpectedRuntimeFailure { onRuntimeFailure(failure) }
    Service.START_NOT_STICKY
}

/** Best-effort cleanup boundary which intentionally does not catch [Error]. */
internal inline fun ignoreExpectedRuntimeFailure(block: () -> Unit) {
    try {
        block()
    } catch (_: RuntimeException) {
        // Expected Android lifecycle races and already-released resources are terminal here.
    }
}
