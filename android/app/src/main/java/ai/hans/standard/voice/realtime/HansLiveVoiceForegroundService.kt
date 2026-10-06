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
import ai.hans.standard.integration.CodexRealtimeGateway
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

data class LiveVoiceRuntimeDependencies(
    val taskExecutor: LiveVoiceTaskExecutor,
    val instructionsProvider: LiveVoiceInstructionsProvider,
    val captureStartBarrier: LiveVoiceCaptureStartBarrier,
    val codexRealtimeGateway: CodexRealtimeGateway? = null,
    val sessionConfig: LiveVoiceSessionConfig = LiveVoiceSessionConfig(
        model = CodexLiveVoiceSession.MODEL,
        voice = CodexLiveVoiceVoiceResolver.DEFAULT_VOICE,
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

/** One effective session reference, published atomically with its command identity. */
internal data class LiveVoiceRuntimeSessionBinding(
    val session: HansLiveVoiceSession,
    val token: Long,
)

/**
 * Process-wide host API. The launcher configures dependencies once and then
 * starts/stops the service through explicit intents. Observers receive only
 * bounded domain events; the Keystore credential never enters this API.
 */
object AndroidLiveVoiceRuntime {
    private val monitor = Any()
    private val observerHub = LiveVoiceObserverHub()
    private val captureActivity = LiveVoiceCaptureActivityState()
    private val commandFence = LiveVoiceServiceCommandFence()
    private val dictationMute = DictationInputMuteState()
    private var dependencies: LiveVoiceRuntimeDependencies? = null
    private var session: HansLiveVoiceSession? = null
    private var sessionToken: Long? = null
    // The session host may query these controls while holding its own admission monitor.
    // Never make those reads wait for this runtime's command/effect monitor: a start or
    // terminal transition can call the host in the opposite direction. The referenced
    // session publishes its actual immutable snapshot; no requested phase is fabricated.
    @Volatile private var publishedSession: LiveVoiceRuntimeSessionBinding? = null
    @Volatile private var callbackToken = 0L
    @Volatile private var entryPoint = LiveVoiceEntryPoint.PHONE
    private var activatedAtNanos = 0L
    private var ownerContext: Context? = null

    fun entryPoint(): LiveVoiceEntryPoint = entryPoint
    fun currentVoiceSessionId(): String? {
        val active = publishedSession ?: return null
        val token = commandFence.currentToken() ?: return null
        if (active.token != token || callbackToken != token) return null
        val id = active.session.voiceSessionId ?: return null
        // Revalidate after the session read. A replaced or completed call is not authority
        // for a late tool invocation, even if its old object is still safely reachable.
        return id.takeIf {
            publishedSession === active && callbackToken == token &&
                commandFence.currentToken() == token
        }
    }
    fun currentCallToken(): Long? = commandFence.currentToken()

    /** Tool callers capture the token before scheduling; a late request cannot end a new call. */
    fun stopIfCurrent(context: Context, expectedToken: Long): Boolean {
        val token = stopSession(expectedToken) ?: return false
        requestServiceStop(context.applicationContext, token)
        return true
    }

    fun configure(dependencies: LiveVoiceRuntimeDependencies) {
        val previous = synchronized(monitor) {
            check(!commandFence.isRequestedOrActive()) { "live_voice_runtime_active" }
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
            sessionToken = null
            publishedSession = null
            callbackToken = 0L
            dictationMute.clear()
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

    fun snapshot(): LiveVoiceSnapshot = publishedSession?.session?.snapshot ?: LiveVoiceSnapshot()

    fun isCaptureRequestedOrActive(): Boolean = captureActivity.isRequestedOrActive()

    /** Changes only the outgoing WebRTC track for the active call. */
    fun setInputMuted(muted: Boolean): Boolean = publishedSession?.session
        ?.setInputMuted(muted) == true

    /** The action key belongs to dictation only; it never hangs up or changes a phone call. */
    fun toggleDictationInputMuted(): Boolean = synchronized(monitor) {
        if (entryPoint != LiveVoiceEntryPoint.DICTATION || !commandFence.isRequestedOrActive()) {
            return@synchronized false
        }
        val token = commandFence.currentToken() ?: return@synchronized false
        if (commandFence.wasStopRequested(token)) return@synchronized false
        val active = session?.takeIf { sessionToken == token }
        if (active == null && !commandFence.isPending(token)) return@synchronized false
        val apply = active?.let { target -> { muted: Boolean -> target.setInputMuted(muted) } }
        if (!dictationMute.toggle(token, apply)) return@synchronized false
        // Pending starts have no capture; active setters acknowledge the effective mute gate.
        ai.hans.standard.voice.android.HansDictationRuntime.confirmInputMuted(
            ai.hans.standard.voice.RecordingId(token), dictationMute.valueFor(token))
        true
    }

    /** Safe no-op while stopped; active sessions deduplicate unchanged context locally. */
    fun refreshContext() {
        publishedSession?.session?.refreshContext()
    }

    fun start(context: Context, requestedEntryPoint: LiveVoiceEntryPoint = LiveVoiceEntryPoint.PHONE): LiveVoiceServiceCommandResult {
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
        if (configured?.codexRealtimeGateway == null) {
            return LiveVoiceServiceCommandResult.NOT_CONFIGURED
        }
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return LiveVoiceServiceCommandResult.MICROPHONE_PERMISSION_MISSING
        }
        val token = synchronized(monitor) {
            if (dependencies !== configured) return LiveVoiceServiceCommandResult.NOT_CONFIGURED
            val reserved = commandFence.reserveStart() ?: return LiveVoiceServiceCommandResult.START_NOT_ALLOWED
            entryPoint = requestedEntryPoint
            ownerContext = appContext
            activatedAtNanos = System.nanoTime()
            callbackToken = reserved
            if (requestedEntryPoint == LiveVoiceEntryPoint.DICTATION) {
                dictationMute.begin(reserved)
                (appContext as? HansApplication)?.sessionHost?.setContinuousDictationActive(true)
                ai.hans.standard.voice.android.HansDictationRuntime.publish(
                    ai.hans.standard.voice.RecordingState.AwaitingAudioFocus(
                        ai.hans.standard.voice.RecordingId(reserved), android.os.SystemClock.elapsedRealtime()))
            } else {
                dictationMute.clear()
            }
            captureActivity.publish(true, reserved)
            reserved
        }
        val result = requestLiveVoiceStartAfterAudioBarrier(configured.captureStartBarrier) {
            synchronized(monitor) {
                // Stop can cancel the reservation while the physical-output barrier waits.
                if (!commandFence.isPending(token)) return@synchronized LiveVoiceServiceCommandResult.START_NOT_ALLOWED
                if (!currentInternetStatus(appContext).permitsExplicitRequest) {
                    return@synchronized LiveVoiceServiceCommandResult.NETWORK_UNAVAILABLE
                }
                try {
                    ContextCompat.startForegroundService(
                        appContext,
                        HansLiveVoiceForegroundService.intent(appContext, ACTION_START, token),
                    )
                    LiveVoiceServiceCommandResult.REQUESTED
                } catch (_: SecurityException) {
                    LiveVoiceServiceCommandResult.SECURITY_FAILURE
                } catch (_: IllegalStateException) {
                    LiveVoiceServiceCommandResult.START_NOT_ALLOWED
                } catch (_: RuntimeException) {
                    LiveVoiceServiceCommandResult.START_NOT_ALLOWED
                }
            }
        }
        if (result != LiveVoiceServiceCommandResult.REQUESTED) synchronized(monitor) {
            if (commandFence.failPendingStart(token)) {
                releaseDictation(appContext, token, failed = true)
                captureActivity.publish(false, token)
            }
        }
        return result
    }

    fun stop(context: Context): LiveVoiceServiceCommandResult {
        val appContext = context.applicationContext
        val token = stopSession() ?: return LiveVoiceServiceCommandResult.REQUESTED
        return requestServiceStop(appContext, token)
    }

    private fun requestServiceStop(appContext: Context, token: Long): LiveVoiceServiceCommandResult {
        return try {
            appContext.startService(
                HansLiveVoiceForegroundService.intent(appContext, ACTION_STOP, token),
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

    internal fun startSession(context: Context, token: Long): Boolean {
        var previous: HansLiveVoiceSession? = null
        val started = synchronized(monitor) {
            val configured = dependencies ?: return@synchronized false
            if (!commandFence.claimStart(token)) return@synchronized false
            try {
                val target = createSession(context.applicationContext, configured, token)
                previous = session
                session = target
                sessionToken = token
                publishedSession = LiveVoiceRuntimeSessionBinding(target, token)
                if (entryPoint == LiveVoiceEntryPoint.DICTATION) {
                    check(dictationMute.applyBeforeStart(token, target::setInputMuted))
                }
                // Keep assignment and the nonblocking start together: stop cannot overtake it.
                target.start()
                true
            } catch (_: RuntimeException) {
                if (sessionToken == token) {
                    session?.close()
                    session = null
                    sessionToken = null
                    publishedSession = null
                }
                if (commandFence.complete(token)) captureActivity.publish(false, token)
                releaseDictation(context, token, failed = true)
                false
            }
        }
        runCatching { previous?.close() }
        return started
    }

    internal fun stopSession(expectedToken: Long? = null): Long? {
        val request = synchronized(monitor) {
            val token = commandFence.requestStop(expectedToken) ?: return null
            Triple(token, session?.takeIf { sessionToken == token }, ownerContext)
        }
        val (token, active, context) = request
        // Pending starts have no microphone. Active starts keep the lease until the effective
        // terminal snapshot, even when Android delivers the matching STOP intent earlier.
        if (active == null) {
            captureActivity.publish(commandFence.isRequestedOrActive(), token)
            context?.let { releaseDictation(it, token, failed = false) }
        } else {
            // Physical mute/stop may await the media worker. Do not hold the global monitor
            // during that work; commandFence already owns this exact stopping generation.
            active.stop()
        }
        return token
    }

    // The command fence already serializes its bounded, callback-free state operations.
    // In particular a service observer must not wait for the broader effects monitor.
    internal fun isPendingStart(token: Long): Boolean = commandFence.isPending(token)
    internal fun consumeStop(token: Long): Boolean = commandFence.consumeStop(token)
    internal fun isLatestCommand(token: Long): Boolean = commandFence.isLatest(token)
    internal fun wasStopRequested(token: Long): Boolean = commandFence.wasStopRequested(token)

    internal fun reportServiceFailure(failure: LiveVoiceFailure) {
        val token = commandFence.currentToken() ?: return
        reportServiceFailure(failure, token)
    }

    internal fun reportServiceFailure(failure: LiveVoiceFailure, token: Long) {
        synchronized(monitor) {
            if (!commandFence.isLatest(token)) return
            if (commandFence.failPendingStart(token)) {
                ownerContext?.let { releaseDictation(it, token, failed = true) }
                captureActivity.publish(false, token)
            }
        }
        // Active capture is released only by its terminal snapshot, not by an error label.
        observerHub.ifCurrentSource({ callbackToken == token }) {
            ai.hans.standard.voice.feedback.HansSpeechFailureRuntime.report(failure.code)
            observerHub.onFailure(failure)
        }
    }

    internal fun clearForTest() {
        val toClose = synchronized(monitor) {
            val previous = session
            session = null
            sessionToken = null
            publishedSession = null
            callbackToken = 0L
            dependencies = null
            commandFence.clearForTest()
            dictationMute.clear()
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
        token: Long,
    ): HansLiveVoiceSession {
        val settings = SharedPreferencesHansSettingsStore(context)
        val mode = entryPoint
        return CodexLiveVoiceSession(
            gateway = checkNotNull(configured.codexRealtimeGateway),
            transportFactory = { sessionProvider ->
                AndroidWebRtcRealtimeTransport(
                    context,
                    sessionProvider = sessionProvider,
                    audioRoutes = (context.applicationContext as? HansApplication)?.speechAudioRoutes,
                    mediaMode = if (mode == LiveVoiceEntryPoint.DICTATION) LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX
                        else LiveVoiceMediaMode.DUPLEX,
                )
            },
            instructionsProvider = configured.instructionsProvider,
            observer = object : LiveVoiceObserver {
                override fun onSnapshot(snapshot: LiveVoiceSnapshot) {
                    if (callbackToken != token) return
                    if (snapshot.phase in TERMINAL_PHASES) commandFence.complete(token)
                    captureActivity.publish(commandFence.isRequestedOrActive(), token)
                    if (mode == LiveVoiceEntryPoint.DICTATION) {
                        if (snapshot.phase in TERMINAL_PHASES) {
                            releaseDictation(context, token, snapshot.phase == LiveVoicePhase.FAILED)
                        } else if (snapshot.phase in setOf(LiveVoicePhase.LISTENING,
                                LiveVoicePhase.USER_SPEAKING, LiveVoicePhase.HANS_SPEAKING,
                                LiveVoicePhase.WAITING_FOR_TASK)) {
                            ai.hans.standard.voice.android.HansDictationRuntime.publish(
                                ai.hans.standard.voice.RecordingState.Recording(
                                    ai.hans.standard.voice.RecordingId(token),
                                    android.os.SystemClock.elapsedRealtime(), Long.MAX_VALUE, 0, 0))
                        }
                        ai.hans.standard.voice.android.HansDictationRuntime.confirmNativeTranscriptAwaiting(
                            ai.hans.standard.voice.RecordingId(token), snapshot.awaitingFirstUserTranscript)
                        if (snapshot.phase !in TERMINAL_PHASES) {
                            ai.hans.standard.voice.android.HansDictationRuntime.confirmInputMuted(
                                ai.hans.standard.voice.RecordingId(token), snapshot.inputMuted)
                        }
                    }
                    observerHub.ifCurrentSource({ callbackToken == token }) { observerHub.onSnapshot(snapshot) }
                }

                override fun onUserTranscript(text: String, isFinal: Boolean) {
                    observerHub.ifCurrentSource({ callbackToken == token }) { observerHub.onUserTranscript(text, isFinal) }
                }

                override fun onHansTranscript(text: String, isFinal: Boolean) {
                    observerHub.ifCurrentSource({ callbackToken == token }) { observerHub.onHansTranscript(text, isFinal) }
                }

                override fun onTranscriptRevision(event: LiveVoiceTranscriptRevision) {
                    observerHub.ifCurrentSource({ callbackToken == token }) {
                        if (mode == LiveVoiceEntryPoint.DICTATION && event.author == LiveVoiceTranscriptAuthor.USER &&
                            event.text.isNotBlank()) {
                            ai.hans.standard.voice.android.HansDictationRuntime.observeNativeUserTranscript(
                                ai.hans.standard.voice.RecordingId(token), event.text)
                        }
                        observerHub.onTranscriptRevision(event)
                    }
                }

                override fun onHansResponseReady(event: LiveVoiceResponseReady) {
                    observerHub.ifCurrentSource({ callbackToken == token }) { observerHub.onHansResponseReady(event) }
                }

                override fun onTaskProgress(
                    callId: String,
                    progress: LiveVoiceTaskProgress,
                ) {
                    observerHub.ifCurrentSource({ callbackToken == token }) { observerHub.onTaskProgress(callId, progress) }
                }

                override fun onFailure(failure: LiveVoiceFailure) = observerHub.ifCurrentSource({ callbackToken == token }) {
                    // A retryable error may still be inside this call's reconnect window.
                    // Only the following effective snapshot releases the capture barrier.
                    ai.hans.standard.voice.feedback.HansSpeechFailureRuntime.report(failure.code)
                    observerHub.onFailure(failure)
                }
            },
            config = configured.sessionConfig,
            entryPoint = mode,
            activatedAtNanos = activatedAtNanos,
            voiceSelectionProvider = LiveVoiceVoiceSelectionProvider {
                CodexLiveVoiceVoiceResolver.resolve(settings.read().codexLiveVoice)
            },
        )
    }

    private fun releaseDictation(context: Context, token: Long, failed: Boolean) {
        if (entryPoint != LiveVoiceEntryPoint.DICTATION || callbackToken != token) return
        (context.applicationContext as? HansApplication)?.sessionHost?.setContinuousDictationActive(false)
        val id = ai.hans.standard.voice.RecordingId(token)
        if (failed) ai.hans.standard.voice.android.HansDictationRuntime.publish(
            ai.hans.standard.voice.RecordingState.Failed(id, ai.hans.standard.voice.RecordingFailure.CODEX_LIVE_FAILED))
        else ai.hans.standard.voice.android.HansDictationRuntime.completeNativeSession(id)
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
    private var latestToken = 0L

    fun addObserver(observer: LiveVoiceCaptureActivityObserver): LiveVoiceCancellation {
        observers += observer
        runCatching { observer.onCaptureActivityChanged(isRequestedOrActive()) }
        return OnceCancellation { observers -= observer }
    }

    fun isRequestedOrActive(): Boolean = synchronized(monitor) { requestedOrActive }

    fun publish(active: Boolean, token: Long? = null) {
        val changed = synchronized(monitor) {
            if (token != null) {
                if (token < latestToken) return
                latestToken = token
            }
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
        synchronized(monitor) { latestToken = 0L }
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
    private var captureSubscription: LiveVoiceCancellation? = null
    private var internetSubscription: Closeable? = null
    private var proximityScreenController: LiveVoiceProximityScreenController? = null
    private var audioRouteSubscription: AutoCloseable? = null
    @Volatile
    private var foregroundEntered = false
    @Volatile
    private var serviceToken: Long? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Same channel identity; refresh labels only through the normal Android event.
        createNotificationChannel()
        if (foregroundEntered) {
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification())
            }
        }
        // Do not update, reconnect or greet an existing call because the UI locale changed.
    }

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
                    if (snapshot.entryPoint == LiveVoiceEntryPoint.PHONE) proximityScreenController?.onSnapshot(snapshot)
                    else proximityScreenController?.release()
                    if (
                        foregroundEntered &&
                        serviceToken?.let(AndroidLiveVoiceRuntime::isLatestCommand) == true &&
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
        captureSubscription = AndroidLiveVoiceRuntime.addCaptureActivityObserver { active ->
            if (!active && foregroundEntered) finishForegroundIfIdle()
        }
        internetSubscription = (application as HansApplication).internetConnectivity.observe {
            if (!it.status.permitsExplicitRequest && AndroidLiveVoiceRuntime.isCaptureRequestedOrActive()) {
                // Stop media promptly, but keep already accepted Codex tasks running. No replay.
                val token = AndroidLiveVoiceRuntime.stopSession()
                if (token != null) AndroidLiveVoiceRuntime.reportServiceFailure(
                    LiveVoiceFailure("realtime_network_unavailable", true), token,
                )
                finishForegroundIfIdle()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            AndroidLiveVoiceRuntime.ACTION_START -> {
                val token = intent.getLongExtra(EXTRA_COMMAND_TOKEN, 0L)
                // Even a cancelled queued start must satisfy Android's foreground-service
                // deadline. Entering foreground does not open the microphone.
                if (!enterForeground(token)) {
                    if (!AndroidLiveVoiceRuntime.isCaptureRequestedOrActive()) stopSelf(startId)
                } else if (!AndroidLiveVoiceRuntime.isPendingStart(token)) {
                    // Duplicate/old/unscoped intents cannot create media or tear down a newer call.
                    finishForegroundIfIdle()
                } else if (!currentInternetStatus(applicationContext).permitsExplicitRequest) {
                    AndroidLiveVoiceRuntime.reportServiceFailure(
                        LiveVoiceFailure("realtime_network_unavailable", true), token,
                    )
                    finishForegroundIfIdle()
                } else {
                    serviceToken = token
                    if (!AndroidLiveVoiceRuntime.startSession(applicationContext, token)) {
                        if (!AndroidLiveVoiceRuntime.wasStopRequested(token)) {
                            AndroidLiveVoiceRuntime.reportServiceFailure(
                                LiveVoiceFailure("realtime_runtime_not_configured", false), token,
                            )
                        }
                        finishForegroundIfIdle()
                    }
                }
                START_NOT_STICKY
            }
            AndroidLiveVoiceRuntime.ACTION_STOP -> {
                val token = intent.getLongExtra(EXTRA_COMMAND_TOKEN, 0L)
                if (AndroidLiveVoiceRuntime.consumeStop(token)) {
                    // stop() already revoked the request synchronously. Do not let a late
                    // Android STOP intent target whichever session happens to exist now.
                    AndroidLiveVoiceRuntime.stopSession(token)
                    finishForegroundIfIdle()
                }
                START_NOT_STICKY
            }
            else -> {
                finishForegroundIfIdle()
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
        captureSubscription?.cancel()
        captureSubscription = null
        audioRouteSubscription?.close()
        audioRouteSubscription = null
        proximityScreenController?.close()
        proximityScreenController = null
        serviceToken?.let(AndroidLiveVoiceRuntime::stopSession)
        serviceToken = null
        super.onDestroy()
    }

    private fun enterForeground(token: Long): Boolean {
        if (foregroundEntered) return true
        return try {
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
                LiveVoiceFailure("realtime_foreground_service_forbidden", false), token,
            )
            false
        } catch (_: RuntimeException) {
            AndroidLiveVoiceRuntime.reportServiceFailure(
                LiveVoiceFailure("realtime_foreground_service_failed", false), token,
            )
            false
        }
    }

    private fun finishForeground() {
        proximityScreenController?.release()
        foregroundEntered = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishForegroundIfIdle() {
        if (!AndroidLiveVoiceRuntime.isCaptureRequestedOrActive()) finishForeground()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Hans Live Voice",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.integration_active_voice_conversation_with_hans_098fa27)
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
        .setContentTitle(getString(R.string.integration_hans_is_listening_3d4eedb))
        .setContentText(getString(if (AndroidLiveVoiceRuntime.entryPoint() == LiveVoiceEntryPoint.DICTATION)
            R.string.dictation_continuous_active else R.string.integration_live_voice_is_active_d6c75ea))
        .setCategory(Notification.CATEGORY_SERVICE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        internal fun intent(context: Context, action: String, token: Long? = null): Intent =
            Intent(context, HansLiveVoiceForegroundService::class.java).setAction(action).also {
                if (token != null) it.putExtra(EXTRA_COMMAND_TOKEN, token)
            }

        private const val EXTRA_COMMAND_TOKEN = "ai.hans.standard.voice.realtime.COMMAND_TOKEN"
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

    /** The predicate must be a nonblocking source-token read, never another object's lock. */
    fun ifCurrentSource(isCurrent: () -> Boolean, deliver: () -> Unit) = synchronized(monitor) {
        if (isCurrent()) deliver()
    }

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

    override fun onTranscriptRevision(event: LiveVoiceTranscriptRevision) = synchronized(monitor) {
        val author = when (event.author) {
            LiveVoiceTranscriptAuthor.USER -> TranscriptAuthor.USER
            LiveVoiceTranscriptAuthor.HANS -> TranscriptAuthor.HANS
        }
        rememberTranscriptLocked(author, event.text, event.isFinal, event)
        deliverLocked { it.onTranscriptRevision(event) }
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
        identity: LiveVoiceTranscriptRevision? = null,
    ) {
        val text = rawText.take(MAX_REPLAY_TRANSCRIPT_CHARACTERS)
        val current = when (author) {
            TranscriptAuthor.USER -> partialUserTranscript
            TranscriptAuthor.HANS -> partialHansTranscript
        }
        val completedIdentity = identity?.let { event ->
            completedTranscripts.firstOrNull { it.identity?.displayId == event.displayId }
        }
        val sequence = completedIdentity?.sequence ?: current?.sequence ?: nextTranscriptSequence++
        val replay = ReplayTranscript(sequence, author, text, isFinal, identity?.copy(text = text))
        if (isFinal) {
            when (author) {
                TranscriptAuthor.USER -> partialUserTranscript = null
                TranscriptAuthor.HANS -> partialHansTranscript = null
            }
            if (text.isNotBlank()) {
                // Replaying/revising the exact same typed utterance is not a new utterance.
                // Equal wording with a different source identity remains a separate message.
                if (identity != null) completedTranscripts.removeAll {
                    it.identity?.displayId == identity.displayId
                }
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
                if (transcript.identity != null) {
                    observer.onTranscriptRevision(transcript.identity)
                } else when (transcript.author) {
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
        val identity: LiveVoiceTranscriptRevision? = null,
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
