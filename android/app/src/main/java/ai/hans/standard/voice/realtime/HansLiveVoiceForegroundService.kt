package ai.hans.standard.voice.realtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import ai.hans.standard.R
import ai.hans.standard.HansApplication
import ai.hans.standard.network.InternetStatus
import ai.hans.standard.settings.SharedPreferencesHansSettingsStore
import ai.hans.standard.voice.tts.android.AndroidKeystoreSpeechCredentialStore
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

data class LiveVoiceRuntimeDependencies(
    val taskExecutor: LiveVoiceTaskExecutor,
    val instructionsProvider: LiveVoiceInstructionsProvider,
    val captureStartBarrier: LiveVoiceCaptureStartBarrier,
    val sessionConfig: LiveVoiceSessionConfig = LiveVoiceSessionConfig(
        voice = LiveVoiceApiVoiceResolver.DEFAULT_VOICE,
    ),
)

/** Physical-output barrier that must acknowledge silence before Realtime can request capture. */
fun interface LiveVoiceCaptureStartBarrier {
    fun awaitCaptureReady(): Boolean
}

enum class LiveVoiceServiceCommandResult {
    REQUESTED,
    NETWORK_UNAVAILABLE,
    NOT_CONFIGURED,
    MICROPHONE_PERMISSION_MISSING,
    AUDIO_OUTPUT_NOT_STOPPED,
    START_NOT_ALLOWED,
    SECURITY_FAILURE,
}

/**
 * Process-wide host API. The launcher configures dependencies once and then
 * starts/stops the service through explicit intents. Observers receive only
 * bounded domain events; the Keystore credential never enters this API.
 */
object AndroidLiveVoiceRuntime {
    private val monitor = Any()
    private val observerHub = LiveVoiceObserverHub()
    private val captureActivity = LiveVoiceCaptureActivityState()
    private var dependencies: LiveVoiceRuntimeDependencies? = null
    private var session: LiveApiVoiceSession? = null

    fun configure(dependencies: LiveVoiceRuntimeDependencies) {
        val previous = synchronized(monitor) {
            check(
                session?.snapshot?.phase in setOf(
                    null,
                    LiveVoicePhase.IDLE,
                    LiveVoicePhase.STOPPED,
                    LiveVoicePhase.FAILED,
                ),
            ) {
                "live_voice_runtime_active"
            }
            this.dependencies = dependencies
            session.also { session = null }
        }
        previous?.close()
    }

    fun addObserver(observer: LiveVoiceObserver): LiveVoiceCancellation =
        observerHub.add(observer)

    /** Includes the start-request window before the first Realtime snapshot exists. */
    fun addCaptureActivityObserver(
        observer: LiveVoiceCaptureActivityObserver,
    ): LiveVoiceCancellation = captureActivity.addObserver(observer)

    fun snapshot(): LiveVoiceSnapshot = synchronized(monitor) {
        session?.snapshot ?: LiveVoiceSnapshot()
    }

    fun isCaptureRequestedOrActive(): Boolean = captureActivity.isRequestedOrActive()

    /** Changes only the outgoing WebRTC track for the active call. */
    fun setInputMuted(muted: Boolean): Boolean = synchronized(monitor) { session }
        ?.setInputMuted(muted) == true

    /** Safe no-op while stopped; active sessions deduplicate unchanged context locally. */
    fun refreshContext() {
        synchronized(monitor) { session }?.refreshContext()
    }

    fun start(context: Context): LiveVoiceServiceCommandResult {
        val appContext = context.applicationContext
        if (!currentInternetStatus(appContext).permitsExplicitRequest) {
            return LiveVoiceServiceCommandResult.NETWORK_UNAVAILABLE
        }
        val configured = synchronized(monitor) {
            if (captureActivity.isRequestedOrActive()) {
                return LiveVoiceServiceCommandResult.START_NOT_ALLOWED
            }
            dependencies
        }
        if (configured == null) {
            return LiveVoiceServiceCommandResult.NOT_CONFIGURED
        }
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return LiveVoiceServiceCommandResult.MICROPHONE_PERMISSION_MISSING
        }
        return requestLiveVoiceStartWithCaptureActivity(
            captureActivity = captureActivity,
            captureStartBarrier = configured.captureStartBarrier,
            internetStatus = currentInternetStatus(appContext),
            requestStart = {
                try {
                    ContextCompat.startForegroundService(
                        appContext,
                        HansLiveVoiceForegroundService.intent(appContext, ACTION_START),
                    )
                    LiveVoiceServiceCommandResult.REQUESTED
                } catch (_: SecurityException) {
                    LiveVoiceServiceCommandResult.SECURITY_FAILURE
                } catch (_: IllegalStateException) {
                    LiveVoiceServiceCommandResult.START_NOT_ALLOWED
                } catch (_: RuntimeException) {
                    LiveVoiceServiceCommandResult.START_NOT_ALLOWED
                }
            },
        )
    }

    fun stop(context: Context): LiveVoiceServiceCommandResult {
        val appContext = context.applicationContext
        stopSession()
        return try {
            appContext.startService(
                HansLiveVoiceForegroundService.intent(appContext, ACTION_STOP),
            )
            LiveVoiceServiceCommandResult.REQUESTED
        } catch (_: SecurityException) {
            LiveVoiceServiceCommandResult.SECURITY_FAILURE
        } catch (_: IllegalStateException) {
            // The service may already be gone; the in-process session was still
            // stopped synchronously above.
            LiveVoiceServiceCommandResult.START_NOT_ALLOWED
        } catch (_: RuntimeException) {
            LiveVoiceServiceCommandResult.START_NOT_ALLOWED
        }
    }

    internal fun startSession(context: Context): Boolean {
        val target = synchronized(monitor) {
            val configured = dependencies ?: return false
            session ?: createSession(context.applicationContext, configured).also {
                session = it
            }
        }
        target.start()
        return true
    }

    internal fun stopSession() {
        val active = synchronized(monitor) {
            session
        }
        // Keep other speech output blocked until the transport's terminal snapshot confirms
        // capture disposal. session.close drains asynchronously, even after the UI hangs up.
        if (active == null) captureActivity.publish(false) else active.stop()
    }

    internal fun reportServiceFailure(failure: LiveVoiceFailure) {
        captureActivity.publish(false)
        observerHub.onFailure(failure)
    }

    internal fun clearForTest() {
        val toClose = synchronized(monitor) {
            val previous = session
            session = null
            dependencies = null
            previous
        }
        captureActivity.publish(false)
        toClose?.close()
        observerHub.clear()
        captureActivity.clearForTest()
    }

    private fun createSession(
        context: Context,
        configured: LiveVoiceRuntimeDependencies,
    ): LiveApiVoiceSession {
        val keystore = AndroidKeystoreSpeechCredentialStore(context)
        val settings = SharedPreferencesHansSettingsStore(context)
        return LiveApiVoiceSession(
            transportFactory = LiveVoiceTransportFactory {
                AndroidWebRtcRealtimeTransport(
                    context,
                    sessionProvider = OpenAiLiveSessionProvider(
                        LiveVoiceStandardKeySource(keystore::loadBearerToken),
                    ),
                    audioRoutes = (context.applicationContext as? HansApplication)?.speechAudioRoutes,
                )
            },
            taskExecutor = configured.taskExecutor,
            instructionsProvider = configured.instructionsProvider,
            observer = object : LiveVoiceObserver {
                override fun onSnapshot(snapshot: LiveVoiceSnapshot) {
                    captureActivity.publish(snapshot.phase !in TERMINAL_PHASES)
                    observerHub.onSnapshot(snapshot)
                }

                override fun onUserTranscript(text: String, isFinal: Boolean) {
                    observerHub.onUserTranscript(text, isFinal)
                }

                override fun onHansTranscript(text: String, isFinal: Boolean) {
                    observerHub.onHansTranscript(text, isFinal)
                }

                override fun onHansResponseReady(event: LiveVoiceResponseReady) {
                    observerHub.onHansResponseReady(event)
                }

                override fun onTaskProgress(
                    callId: String,
                    progress: LiveVoiceTaskProgress,
                ) {
                    observerHub.onTaskProgress(callId, progress)
                }

                override fun onFailure(failure: LiveVoiceFailure) {
                    // A retryable error may still be inside this call's reconnect window.
                    // Only the following effective snapshot releases the capture barrier.
                    observerHub.onFailure(failure)
                }
            },
            config = configured.sessionConfig,
            voiceSelectionProvider = LiveVoiceVoiceSelectionProvider {
                LiveVoiceApiVoiceResolver.resolve(settings.read().liveVoice)
            },
        )
    }

    internal const val ACTION_START = "ai.hans.standard.voice.realtime.action.START"
    internal const val ACTION_STOP = "ai.hans.standard.voice.realtime.action.STOP"
    private val TERMINAL_PHASES = setOf(
        LiveVoicePhase.IDLE,
        LiveVoicePhase.FAILED,
        LiveVoicePhase.STOPPED,
    )
}

fun interface LiveVoiceCaptureActivityObserver {
    fun onCaptureActivityChanged(active: Boolean)
}

internal class LiveVoiceCaptureActivityState {
    private val monitor = Any()
    private val observers = CopyOnWriteArraySet<LiveVoiceCaptureActivityObserver>()
    private var requestedOrActive = false

    fun addObserver(observer: LiveVoiceCaptureActivityObserver): LiveVoiceCancellation {
        observers += observer
        runCatching { observer.onCaptureActivityChanged(isRequestedOrActive()) }
        return OnceCancellation { observers -= observer }
    }

    fun isRequestedOrActive(): Boolean = synchronized(monitor) { requestedOrActive }

    fun publish(active: Boolean) {
        val changed = synchronized(monitor) {
            if (requestedOrActive == active) {
                false
            } else {
                requestedOrActive = active
                true
            }
        }
        if (!changed) return
        observers.forEach { observer ->
            runCatching { observer.onCaptureActivityChanged(active) }
        }
    }

    fun clearForTest() {
        publish(false)
        observers.clear()
    }
}

internal fun requestLiveVoiceStartWithCaptureActivity(
    captureActivity: LiveVoiceCaptureActivityState,
    captureStartBarrier: LiveVoiceCaptureStartBarrier,
    requestStart: () -> LiveVoiceServiceCommandResult,
    internetStatus: InternetStatus = InternetStatus.UNKNOWN,
): LiveVoiceServiceCommandResult {
    if (!internetStatus.permitsExplicitRequest) return LiveVoiceServiceCommandResult.NETWORK_UNAVAILABLE
    captureActivity.publish(true)
    val result = requestLiveVoiceStartAfterAudioBarrier(captureStartBarrier, requestStart)
    if (result != LiveVoiceServiceCommandResult.REQUESTED) captureActivity.publish(false)
    return result
}

/** Shared below-Activity ordering boundary: a failed/throwing stop can never request capture. */
internal fun requestLiveVoiceStartAfterAudioBarrier(
    captureStartBarrier: LiveVoiceCaptureStartBarrier,
    requestStart: () -> LiveVoiceServiceCommandResult,
): LiveVoiceServiceCommandResult {
    val captureReady = runCatching { captureStartBarrier.awaitCaptureReady() }
        .getOrDefault(false)
    if (!captureReady) return LiveVoiceServiceCommandResult.AUDIO_OUTPUT_NOT_STOPPED
    return requestStart()
}

/**
 * Manifest hook (owned by the app host):
 *
 * `<service android:name=".voice.realtime.HansLiveVoiceForegroundService"
 * android:exported="false" android:stopWithTask="false"
 * android:foregroundServiceType="microphone|mediaPlayback" />`
 */
class HansLiveVoiceForegroundService : Service() {
    private var terminalSubscription: LiveVoiceCancellation? = null
    private var internetSubscription: Closeable? = null
    private var proximityScreenController: LiveVoiceProximityScreenController? = null
    private var audioRouteSubscription: AutoCloseable? = null
    @Volatile
    private var foregroundEntered = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        proximityScreenController = LiveVoiceProximityScreenController.create(applicationContext)
        audioRouteSubscription = (application as HansApplication).speechAudioRoutes.observe {
            proximityScreenController?.onAudioRoute(it)
        }
        terminalSubscription = AndroidLiveVoiceRuntime.addObserver(
            object : LiveVoiceObserver {
                override fun onSnapshot(snapshot: LiveVoiceSnapshot) {
                    proximityScreenController?.onSnapshot(snapshot)
                    if (
                        foregroundEntered &&
                        snapshot.phase in setOf(LiveVoicePhase.FAILED, LiveVoicePhase.STOPPED)
                    ) {
                        finishForeground()
                    }
                }

                override fun onFailure(failure: LiveVoiceFailure) {
                    // A failure may be published shortly before its terminal snapshot. Never
                    // leave the display under a stale proximity lease in that window.
                    proximityScreenController?.release()
                }
            },
        )
        internetSubscription = (application as HansApplication).internetConnectivity.observe {
            if (!it.status.permitsExplicitRequest && AndroidLiveVoiceRuntime.isCaptureRequestedOrActive()) {
                // Stop media promptly, but keep already accepted Codex tasks running. No replay.
                AndroidLiveVoiceRuntime.stopSession()
                AndroidLiveVoiceRuntime.reportServiceFailure(
                    LiveVoiceFailure("realtime_network_unavailable", true),
                )
                finishForeground()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            AndroidLiveVoiceRuntime.ACTION_START -> {
                if (!currentInternetStatus(applicationContext).permitsExplicitRequest) {
                    AndroidLiveVoiceRuntime.reportServiceFailure(
                        LiveVoiceFailure("realtime_network_unavailable", true),
                    )
                    finishForeground()
                    START_NOT_STICKY
                } else if (!enterForeground()) {
                    stopSelf(startId)
                    START_NOT_STICKY
                } else if (!AndroidLiveVoiceRuntime.startSession(applicationContext)) {
                    AndroidLiveVoiceRuntime.reportServiceFailure(
                        LiveVoiceFailure("realtime_runtime_not_configured", false),
                    )
                    finishForeground()
                    START_NOT_STICKY
                } else {
                    START_NOT_STICKY
                }
            }
            AndroidLiveVoiceRuntime.ACTION_STOP -> {
                AndroidLiveVoiceRuntime.stopSession()
                finishForeground()
                START_NOT_STICKY
            }
            else -> {
                finishForeground()
                START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        internetSubscription?.close()
        internetSubscription = null
        terminalSubscription?.cancel()
        terminalSubscription = null
        audioRouteSubscription?.close()
        audioRouteSubscription = null
        proximityScreenController?.close()
        proximityScreenController = null
        AndroidLiveVoiceRuntime.stopSession()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        foregroundEntered = true
        true
    } catch (_: SecurityException) {
        AndroidLiveVoiceRuntime.reportServiceFailure(
            LiveVoiceFailure("realtime_foreground_service_forbidden", false),
        )
        false
    } catch (_: RuntimeException) {
        AndroidLiveVoiceRuntime.reportServiceFailure(
            LiveVoiceFailure("realtime_foreground_service_failed", false),
        )
        false
    }

    private fun finishForeground() {
        proximityScreenController?.release()
        foregroundEntered = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Hans Live Voice",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Aktive Sprachunterhaltung mit Hans"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification = Notification.Builder(
        this,
        NOTIFICATION_CHANNEL_ID,
    )
        .setSmallIcon(R.drawable.ic_hans)
        .setContentTitle("Hans hört zu")
        .setContentText("Live Voice ist aktiv")
        .setCategory(Notification.CATEGORY_SERVICE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        internal fun intent(context: Context, action: String): Intent =
            Intent(context, HansLiveVoiceForegroundService::class.java).setAction(action)

        private const val NOTIFICATION_CHANNEL_ID = "hans_live_voice_v1"
        private const val NOTIFICATION_ID = 0x484C56
    }
}

private fun currentInternetStatus(context: Context): InternetStatus =
    (context.applicationContext as? HansApplication)?.internetConnectivity
        ?.snapshot()?.status ?: InternetStatus.UNKNOWN

internal class LiveVoiceObserverHub : LiveVoiceObserver {
    private val monitor = Any()
    private val observers = LinkedHashSet<LiveVoiceObserver>()
    private val completedTranscripts = ArrayDeque<ReplayTranscript>()
    private var latestSnapshot = LiveVoiceSnapshot()
    private var partialUserTranscript: ReplayTranscript? = null
    private var partialHansTranscript: ReplayTranscript? = null
    private var nextTranscriptSequence = 0L

    fun add(observer: LiveVoiceObserver): LiveVoiceCancellation {
        synchronized(monitor) {
            observers += observer
            safely { observer.onSnapshot(latestSnapshot) }
            replayTranscriptsLocked(observer)
        }
        return OnceCancellation {
            synchronized(monitor) {
                observers -= observer
            }
        }
    }

    fun clear() = synchronized(monitor) {
        observers.clear()
        latestSnapshot = LiveVoiceSnapshot()
        completedTranscripts.clear()
        partialUserTranscript = null
        partialHansTranscript = null
        nextTranscriptSequence = 0L
    }

    override fun onSnapshot(snapshot: LiveVoiceSnapshot) = synchronized(monitor) {
        if (latestSnapshot.phase in TERMINAL_PHASES && snapshot.phase !in TERMINAL_PHASES) {
            completedTranscripts.clear()
            partialUserTranscript = null
            partialHansTranscript = null
            nextTranscriptSequence = 0L
        }
        latestSnapshot = snapshot
        deliverLocked {
            it.onSnapshot(snapshot)
        }
    }

    override fun onUserTranscript(text: String, isFinal: Boolean) = synchronized(monitor) {
        rememberTranscriptLocked(TranscriptAuthor.USER, text, isFinal)
        deliverLocked {
            it.onUserTranscript(text, isFinal)
        }
    }

    override fun onHansTranscript(text: String, isFinal: Boolean) = synchronized(monitor) {
        rememberTranscriptLocked(TranscriptAuthor.HANS, text, isFinal)
        deliverLocked {
            it.onHansTranscript(text, isFinal)
        }
    }

    override fun onHansResponseReady(event: LiveVoiceResponseReady) = synchronized(monitor) {
        deliverLocked {
            it.onHansResponseReady(event)
        }
    }

    override fun onTaskProgress(callId: String, progress: LiveVoiceTaskProgress) =
        synchronized(monitor) {
            deliverLocked {
                it.onTaskProgress(callId, progress)
            }
        }

    override fun onFailure(failure: LiveVoiceFailure) = synchronized(monitor) {
        deliverLocked {
            it.onFailure(failure)
        }
    }

    private fun rememberTranscriptLocked(
        author: TranscriptAuthor,
        rawText: String,
        isFinal: Boolean,
    ) {
        val text = rawText.take(MAX_REPLAY_TRANSCRIPT_CHARACTERS)
        val current = when (author) {
            TranscriptAuthor.USER -> partialUserTranscript
            TranscriptAuthor.HANS -> partialHansTranscript
        }
        val sequence = current?.sequence ?: nextTranscriptSequence++
        val replay = ReplayTranscript(sequence, author, text, isFinal)
        if (isFinal) {
            when (author) {
                TranscriptAuthor.USER -> partialUserTranscript = null
                TranscriptAuthor.HANS -> partialHansTranscript = null
            }
            if (text.isNotBlank()) {
                completedTranscripts.addLast(replay)
                while (completedTranscripts.size > MAX_REPLAY_TRANSCRIPTS) {
                    completedTranscripts.removeFirst()
                }
            }
        } else {
            when (author) {
                TranscriptAuthor.USER -> partialUserTranscript = replay
                TranscriptAuthor.HANS -> partialHansTranscript = replay
            }
        }
    }

    private fun replayTranscriptsLocked(observer: LiveVoiceObserver) {
        val replay = buildList {
            addAll(completedTranscripts)
            partialUserTranscript?.let(::add)
            partialHansTranscript?.let(::add)
        }.sortedBy(ReplayTranscript::sequence)
        replay.forEach { transcript ->
            safely {
                when (transcript.author) {
                    TranscriptAuthor.USER -> observer.onUserTranscript(
                        transcript.text,
                        transcript.isFinal,
                    )
                    TranscriptAuthor.HANS -> observer.onHansTranscript(
                        transcript.text,
                        transcript.isFinal,
                    )
                }
            }
        }
    }

    private inline fun deliverLocked(block: (LiveVoiceObserver) -> Unit) {
        // Snapshotting also makes re-entrant observer registration/cancellation safe while
        // retaining the monitor as the replay-versus-delivery ordering boundary.
        observers.toList().forEach { observer ->
            safely { block(observer) }
        }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            // One UI observer must never break media or other observers.
        }
    }

    private enum class TranscriptAuthor { USER, HANS }

    private data class ReplayTranscript(
        val sequence: Long,
        val author: TranscriptAuthor,
        val text: String,
        val isFinal: Boolean,
    )

    companion object {
        private val TERMINAL_PHASES = setOf(
            LiveVoicePhase.IDLE,
            LiveVoicePhase.FAILED,
            LiveVoicePhase.STOPPED,
        )
        private const val MAX_REPLAY_TRANSCRIPTS = 32
        private const val MAX_REPLAY_TRANSCRIPT_CHARACTERS = 32_000
    }
}
