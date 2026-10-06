package ai.hans.standard.voice.realtime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import ai.hans.standard.voice.audio.AndroidSpeechAudioRouteController
import ai.hans.standard.voice.audio.LiveVoiceAudioRouteException
import ai.hans.standard.voice.audio.LiveVoiceCommunicationModeRequest
import ai.hans.standard.voice.audio.SpeechAudioRoute
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import livekit.org.webrtc.AudioSource
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.audio.AudioProcessingOptions
import livekit.org.webrtc.audio.JavaAudioDeviceModule

data class LiveVoiceAudioCapabilities(
    val hardwareAcousticEchoCancellation: Boolean,
    val hardwareNoiseSuppression: Boolean,
)

/** Buffered modes never let WebRTC open the physical microphone or own Android audio focus. */
enum class LiveVoiceMediaMode {
    DUPLEX,
    EARLY_CAPTURE_DUPLEX,
    OUTPUT_ONLY,
    BUFFERED_DICTATION_SILENT,
    BUFFERED_DICTATION_PRIMARY_SILENT;

    internal val physicalCapture: Boolean get() = this == DUPLEX || this == EARLY_CAPTURE_DUPLEX
    internal val takesAudioFocus: Boolean get() = physicalCapture || this == OUTPUT_ONLY
    internal val playsOutput: Boolean get() = physicalCapture || this == OUTPUT_ONLY
    internal val playsConnectionTones: Boolean get() = this == DUPLEX
    internal val receivesOnly: Boolean get() = this == OUTPUT_ONLY || this == BUFFERED_DICTATION_SILENT
    internal val usesBufferedPrimaryAudio: Boolean get() = this == BUFFERED_DICTATION_PRIMARY_SILENT

    internal fun permitsCapture(started: Boolean, muted: Boolean, ended: Boolean): Boolean =
        physicalCapture && (started || this == EARLY_CAPTURE_DUPLEX) && !muted && !ended

    internal fun permitsOutput(started: Boolean, ended: Boolean): Boolean =
        playsOutput && !ended && (this != OUTPUT_ONLY && this != EARLY_CAPTURE_DUPLEX || started)
}

/**
 * Native, duplex Android WebRTC transport for the OpenAI Live API.
 *
 * It owns no Activity and never reacts to foreground/background callbacks.
 * Android `VOICE_COMMUNICATION`, WebRTC communication processing, hardware AEC
 * when available, and hardware NS when available remain active for the whole
 * transport lifetime in [LiveVoiceMediaMode.DUPLEX]. The remote audio track is WebRTC playout,
 * not an Android TTS queue, so capture and playback can operate concurrently. Buffered dictation
 * is silent: it owns neither physical audio devices nor Android audio focus. The primary
 * buffered mode sends recorder-owned PCM over RTP using the public ADM buffer callback.
 * OUTPUT_ONLY receives audio without creating an input source or track; physical recording is
 * permanently disabled on its ADM. It owns output focus/routing, but plays no connection tones
 * and holds remote playout until the owner confirms the native session and media readiness.
 * EARLY_CAPTURE_DUPLEX is explicitly user-started after the owner's microphone safety barrier.
 * It uses the same communication ADM as DUPLEX, but retains early PCM in volatile memory and
 * emits no connection tone. Lossless real-time prefix playback retains the startup input lag;
 * this transport deliberately performs no heuristic speech dropping or time compression.
 */
class AndroidWebRtcRealtimeTransport(
    context: Context,
    private val sessionProvider: LiveSessionProvider,
    scheduler: ScheduledExecutorService? = null,
    private val audioRoutes: AndroidSpeechAudioRouteController? = null,
    private val mediaMode: LiveVoiceMediaMode = LiveVoiceMediaMode.DUPLEX,
    private val outputRoute: SpeechAudioRoute? = null,
) : LiveVoiceTransport {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val ownedScheduler = if (scheduler == null) {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "hans-webrtc-control").apply { isDaemon = true }
        }
    } else {
        null
    }
    private val scheduler = scheduler ?: ownedScheduler!!
    private val control = LiveVoiceTransportControl()
    private val terminal = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val disposalReceipt = LiveVoiceDisposalReceipt {
        listener?.onMediaDisposed()
    }
    private val bufferedInput = if (mediaMode.usesBufferedPrimaryAudio) PacedPcmInput(
        onFailure = { code -> reportClosed(LiveVoiceFailure(code, false)) },
    ) else null
    private val startupPcm = if (mediaMode == LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX) StartupPcmBuffer(
        onFailure = { code -> reportClosed(LiveVoiceFailure(code, false)) },
        onDrained = {
            // ADM callback never stops/joins its own recorder thread. Re-read current mute
            // state on control: a queued drained event must not stop a subsequent unmute.
            dispatchControl {
                if (!applyEarlyCaptureState()) reportClosed(LiveVoiceFailure("live_input_mute_failed", false))
            }
        },
    ) else null
    @Volatile private var earlyCaptureModule: JavaAudioDeviceModule? = null
    private var earlyCaptureRequested = false // Native-control-worker owned explicit recorder lease.
    private val earlyRecorderStarted = AtomicBoolean(false)
    private val earlyRecorderStopped = AtomicBoolean(false)
    private val earlyCaptureNotified = AtomicBoolean(false)
    private val speakerGain = LiveVoiceSpeakerGain(
        speakerStillEffective = {
            audioManager?.let { manager ->
                manager.mode == AudioManager.MODE_IN_COMMUNICATION &&
                    manager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } == true
        },
        onVerified = { route, requested, actual ->
            LiveVoiceDiagnostics.event("LIVE_OUTPUT_GAIN",
                "route=${route.name} requested=$requested effective=$actual")
        },
        onUnsafeGain = { reportClosed(LiveVoiceFailure("live_output_gain_unverified", false)) },
    )
    private val offerPosted = AtomicBoolean(false)
    private val opened = AtomicBoolean(false)
    private val contextInputEnabled = AtomicBoolean(true)
    private val userInputMuted = AtomicBoolean(false)
    private val inputFinishedForOutputTail = AtomicBoolean(false)
    private val startupInputEnabled = AtomicBoolean(false)
    private val audioActivityMonitoringEnabled = AtomicBoolean(false)
    private val audioActivityMonitorLock = Any()
    private var audioActivityMonitorGeneration = 0L
    private val inputActivityMeter = LiveVoiceAudioActivityMeter(
        LiveVoiceAudioDirection.INPUT,
        requireInitialInputQuiet = mediaMode != LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX,
        recoverAfterUnreliable = mediaMode == LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX,
    )
    private val outputActivityMeter = LiveVoiceAudioActivityMeter(
        LiveVoiceAudioDirection.OUTPUT,
        recoverAfterUnreliable = mediaMode == LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX,
    )
    private val connectionAudio = if (mediaMode.playsConnectionTones) {
        LiveConnectionAudioGate(AndroidLiveConnectionTone(audioManager)) { enabled ->
            startupInputEnabled.set(enabled)
            check(applyPhysicalInputState()) { "live_input_recording_state_rejected" }
        }
    } else null

    @Volatile
    private var listener: LiveVoiceTransport.Listener? = null
    @Volatile
    private var peerConnection: PeerConnection? = null
    @Volatile
    private var peerFactory: PeerConnectionFactory? = null
    @Volatile
    private var audioSource: AudioSource? = null
    @Volatile
    private var localAudioTrack: AudioTrack? = null
    @Volatile
    private var dataChannel: DataChannel? = null
    @Volatile
    private var sessionRequest: LiveVoiceCancellation? = null
    private var pendingSetup: LiveSessionSetup? = null
    @Volatile
    private var offerFallback: ScheduledFuture<*>? = null
    @Volatile
    private var disconnectedFailure: ScheduledFuture<*>? = null
    @Volatile
    private var initializationStage = "not_started"
    private var audioFocusRequest: AudioFocusRequest? = null
    private var communicationModeRequest: LiveVoiceCommunicationModeRequest? = null
    private var routeRegistration: AutoCloseable? = null
    private val outputOnlyPlayback = OutputOnlyAudioPlaybackGate()

    override val inputDelayMillis: Long get() = startupPcm?.queuedMillis ?: 0L

    override fun connect(
        credential: RealtimeEphemeralCredential,
        listener: LiveVoiceTransport.Listener,
    ) {
        listener.onClosed(LiveVoiceFailure("live_session_setup_required", false))
    }

    override fun connect(
        setup: LiveSessionSetup,
        listener: LiveVoiceTransport.Listener,
    ) {
        try {
            control.call {
                if (terminal.get() || disposed.get()) return@call
                check(this.listener == null) { "transport_already_started" }
                this.listener = listener
                if (mediaMode.takesAudioFocus) {
                    initializationStage = "communication_audio"
                    configureCommunicationAudio()
                }
                if (terminal.get()) return@call
                connectionAudio?.connecting()
                initializationStage = "native_library"
                ensureWebRtcInitialized(appContext)
                if (terminal.get()) return@call
                pendingSetup = setup
                initializationStage = "peer_connection"
                createPeerConnection()
            }
        } catch (error: LiveVoiceAudioRouteException) {
            reportClosed(LiveVoiceFailure(error.failureCode, false))
        } catch (_: SecurityException) {
            reportClosed(LiveVoiceFailure("realtime_microphone_forbidden", false))
        } catch (_: UnsatisfiedLinkError) {
            reportClosed(LiveVoiceFailure("realtime_webrtc_native_unavailable", false))
        } catch (error: RuntimeException) {
            LiveVoiceDiagnostics.event(
                "TRANSPORT_INIT_FAILURE",
                "stage=$initializationStage type=${error.javaClass.simpleName.take(64)}",
            )
            reportClosed(LiveVoiceFailure("realtime_webrtc_initialization_failed", false))
        }
    }

    override fun sendUtf8(event: String): Boolean {
        val bytes = event.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_CLIENT_EVENT_BYTES) return false
        return controlResult {
            val channel = dataChannel ?: return@controlResult false
            if (channel.state() != DataChannel.State.OPEN) return@controlResult false
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        }
    }

    override fun appendInputAudio(
        pcm: ByteArray,
        sampleRateHz: Int,
        callback: (Result<Unit>) -> Unit,
    ): Boolean {
        if (terminal.get() || disposed.get() || inputFinishedForOutputTail.get()) return false
        return bufferedInput?.append(pcm, sampleRateHz, callback) == true
    }

    override fun setInputAudioEnabled(enabled: Boolean): Boolean = controlResult {
        if (inputFinishedForOutputTail.get()) {
            return@controlResult if (enabled) false else applyFinishedInputForOutputTail()
        }
        contextInputEnabled.set(enabled)
        applyLocalInputState()
    }

    override fun setUserInputMuted(muted: Boolean): Boolean {
        if (inputFinishedForOutputTail.get()) {
            return muted && acknowledgeFinishedInputForOutputTail()
        }
        if (mediaMode == LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX) {
            // Immediate, monitor-serialized PCM cutoff even if the native worker is connecting.
            // Only previously accepted frames remain eligible for delayed RTP delivery.
            userInputMuted.set(muted)
            if (muted && startupPcm?.setCaptureEnabled(false) != true) return false
        }
        return controlResult {
            if (inputFinishedForOutputTail.get()) {
                return@controlResult muted && applyFinishedInputForOutputTail()
            }
            if (mediaMode == LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX) {
                // Unlike mute, resume waits for serial native ownership. A preceding drain
                // cannot stop a recorder after it has already accepted newly unmuted speech.
                if (!muted && !userInputMuted.get() && startupPcm?.setCaptureEnabled(true) != true) {
                    return@controlResult false
                }
            } else userInputMuted.set(muted)
            applyPhysicalInputState().also { applied ->
                if (!applied) reportClosed(LiveVoiceFailure("live_input_mute_failed", false))
            }
        }
    }

    override fun finishInputForOutputTail(): Boolean {
        if (mediaMode != LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX || terminal.get() || disposed.get()) return false
        inputFinishedForOutputTail.set(true)
        userInputMuted.set(true)
        contextInputEnabled.set(false)
        // Synchronous sample admission cutoff and zeroization, even while native control is busy.
        // This intentionally discards the FIFO; ordinary user mute continues to drain it.
        startupPcm?.close()
        return acknowledgeFinishedInputForOutputTail()
    }

    private fun acknowledgeFinishedInputForOutputTail(): Boolean = control.callBounded(500L) {
        !terminal.get() && !disposed.get() && applyFinishedInputForOutputTail()
    }

    override fun confirmSessionStarted(): Boolean = controlResult {
        if (mediaMode == LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX) {
            val input = startupPcm ?: return@controlResult false
            if (!opened.get() || dataChannel?.state() != DataChannel.State.OPEN ||
                peerConnection == null || (input.acceptsPhysicalFrames &&
                    (!earlyRecorderStarted.get() || !input.hasCapturedFrame))
            ) return@controlResult false
            startupInputEnabled.set(true)
            applyEarlyCaptureState().also { confirmed ->
                if (confirmed) LiveVoiceDiagnostics.event("ACTION_VOICE_INPUT_READY",
                    "queuedMillis=${input.queuedMillis}")
            }
        } else if (mediaMode.physicalCapture) {
            connectionAudio?.connected()
            applyLocalInputState()
        } else {
            // A native session ACK alone is not media readiness. This branch never opens a
            // recorder: input is absent, or PCM belongs to the independent recording service.
            if (!opened.get() || dataChannel?.state() != DataChannel.State.OPEN ||
                peerConnection == null
            ) return@controlResult false
            startupInputEnabled.set(true)
            applyPhysicalInputState() &&
                (mediaMode != LiveVoiceMediaMode.OUTPUT_ONLY || applyOutputOnlyState())
        }
    }

    // Live handles conversational interruption from input audio. No legacy buffer-clear event.
    override fun clearOutputAudio(): Boolean = false

    override fun setAudioActivityMonitoringEnabled(enabled: Boolean): Boolean = controlResult {
        if (!mediaMode.playsOutput) return@controlResult !enabled
        synchronized(audioActivityMonitorLock) {
            if (audioActivityMonitoringEnabled.get() != enabled) {
                audioActivityMonitoringEnabled.set(enabled)
                audioActivityMonitorGeneration += 1
                inputActivityMeter.reset()
                outputActivityMeter.reset()
            }
        }
        true
    }

    override fun close() {
        terminal.set(true)
        bufferedInput?.close()
        startupPcm?.close()
        // External callers are not platform callback threads. Route/native callbacks instead
        // use reportClosed(), which enqueues cleanup and never waits for this worker.
        runCatching { control.call { disposeResources() } }
    }

    override fun closeAndAwait(timeoutMillis: Long): Boolean {
        if (timeoutMillis < 0) return false
        val deadlineNanos = System.nanoTime()
        terminal.set(true)
        startupPcm?.close()
        // No caller-side native operation or lock wait: a busy native worker may outlive the
        // requested deadline. Cleanup remains queued, never cancelled because waiting timed out.
        control.execute { disposeResources() }
        val remainingNanos = (TimeUnit.MILLISECONDS.toNanos(timeoutMillis) -
            (System.nanoTime() - deadlineNanos)).coerceAtLeast(0L)
        return disposalReceipt.awaitNanos(remainingNanos)
    }

    private fun controlResult(block: () -> Boolean): Boolean = try {
        control.call {
            if (terminal.get() || disposed.get()) false else block()
        }
    } catch (_: RuntimeException) {
        reportClosed(LiveVoiceFailure("live_native_control_failed", false))
        false
    }

    private fun dispatchControl(block: () -> Unit) {
        control.execute {
            if (!terminal.get() && !disposed.get()) {
                try { block() } catch (_: RuntimeException) {
                    reportClosed(LiveVoiceFailure("live_native_control_failed", false))
                }
            }
        }
    }

    private fun configureCommunicationAudio() {
        val manager = audioManager ?: throw IllegalStateException("audio_manager_unavailable")
        check(manager.mode == AudioManager.MODE_NORMAL) { "communication_audio_in_use" }
        val routes = audioRoutes
            ?: throw LiveVoiceAudioRouteException("realtime_audio_route_controller_unavailable")
        // Observe the output preference before communication mode can change Android's default.
        val outputOnlyTarget = if (mediaMode == LiveVoiceMediaMode.OUTPUT_ONLY) {
            routes.selectOutputOnlyRoute(outputRoute)
        } else null
        val previousMode = manager.mode
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        communicationModeRequest = LiveVoiceCommunicationModeRequest(previousMode) { ownedMode ->
            manager.mode = ownedMode
        }
        val attributes = communicationAudioAttributes()
        check(speakerGain.beginAudioFocusRequest()) { "live_output_gain_unverified" }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                if (mediaMode == LiveVoiceMediaMode.OUTPUT_ONLY && change != AudioManager.AUDIOFOCUS_GAIN) {
                    // Read-aloud never competes with another audio owner or resumes itself.
                    reportClosed(LiveVoiceFailure("live_output_focus_lost", false))
                    return@setOnAudioFocusChangeListener
                }
                // Focus changes need not change communicationDevice or mode. Stay duplex, but
                // never retain speaker boost after focus loss or restore it from a route alone.
                if (!terminal.get() && !speakerGain.audioFocusChanged(change == AudioManager.AUDIOFOCUS_GAIN)) {
                    reportClosed(LiveVoiceFailure("live_output_gain_unverified", false))
                }
            }
            .build()
        audioFocusRequest = request
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw IllegalStateException("audio_focus_denied")
        }
        check(speakerGain.confirmInitialAudioFocusGrant()) { "live_output_gain_unverified" }
        routeRegistration = if (outputOnlyTarget != null) routes.attachRequiredOutputOnlyCommunication(
            target = outputOnlyTarget,
            beforeRouteChange = {
                outputOnlyPlayback.setRouteConfirmed(false) && speakerGain.beforeRouteChange()
            },
            onConfirmedRoute = { route ->
                if (route == SpeechAudioRoute.UNKNOWN || terminal.get() || disposed.get()) {
                    outputOnlyPlayback.setRouteConfirmed(false) && speakerGain.routeConfirmed(route)
                } else speakerGain.routeConfirmed(route) && outputOnlyPlayback.setRouteConfirmed(true)
            },
        ) {
            reportClosed(LiveVoiceFailure("live_audio_route_lost", false))
        } else routes.attachRequiredLiveCommunication(
            beforeRouteChange = speakerGain::beforeRouteChange,
            onConfirmedRoute = speakerGain::routeConfirmed,
        ) {
            reportClosed(LiveVoiceFailure("live_audio_route_lost", false))
        }
    }

    private fun createPeerConnection() {
        initializationStage = "audio_device_module"
        val audioModule = if (mediaMode.physicalCapture) JavaAudioDeviceModule.builder(appContext)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseHardwareAcousticEchoCanceler(
                JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported(),
            )
            .setUseHardwareNoiseSuppressor(
                JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported(),
            )
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setUseLowLatency(true)
            .setAudioAttributes(communicationAudioAttributes())
            .setEnableVolumeLogger(false)
            .apply {
                if (mediaMode == LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX) {
                    setInputSampleRate(StartupPcmBuffer.SAMPLE_RATE_HZ)
                    setAudioFormat(StartupPcmBuffer.PCM_16_BIT)
                    setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                        override fun onWebRtcAudioRecordStart() {
                            earlyRecorderStopped.set(false)
                            earlyRecorderStarted.set(true)
                        }
                        override fun onWebRtcAudioRecordStop() {
                            earlyRecorderStopped.set(true)
                        }
                    })
                    setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                        override fun onWebRtcAudioRecordInitError(message: String) =
                            reportClosed(LiveVoiceFailure("action_voice_capture_init_failed", false))
                        override fun onWebRtcAudioRecordStartError(
                            errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                            message: String,
                        ) = reportClosed(LiveVoiceFailure("action_voice_capture_start_failed", false))
                        override fun onWebRtcAudioRecordError(message: String) =
                            reportClosed(LiveVoiceFailure("action_voice_capture_failed", false))
                    })
                    setAudioBufferCallback { buffer, format, channels, rate, bytesRead, timestamp ->
                        observeEarlyPhysicalInput(buffer, format, channels, rate, bytesRead)
                        val input = checkNotNull(startupPcm)
                        val submittedAt = input.render(buffer, format, channels, rate, bytesRead, timestamp)
                        if (!inputFinishedForOutputTail.get() && input.hasCapturedFrame &&
                            earlyCaptureNotified.compareAndSet(false, true)) {
                            dispatchControl { listener?.onInputCaptureStarted() }
                        }
                        submittedAt
                    }
                }
            }
            .setSamplesReadyCallback { samples ->
                // Early-mode samples here have already been replaced by the delayed prefix.
                // Barge-in and hangup evidence must instead use the physical input above.
                if (mediaMode != LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX) {
                    observeAudioActivity(LiveVoiceAudioDirection.INPUT, samples)
                }
            }
            // In pinned WebRTC 144.7559.12 this callback follows blocking AudioTrack.write.
            // It proves PCM submission, not physical speaker drain; the session retains a
            // conservative quiet grace and never treats a missing callback as drained audio.
            .setPlaybackSamplesReadyCallback { samples ->
                observeAudioActivity(LiveVoiceAudioDirection.OUTPUT, samples)
            }
            .createAudioDeviceModule()
        else if (mediaMode == LiveVoiceMediaMode.OUTPUT_ONLY) JavaAudioDeviceModule.builder(appContext)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setUseLowLatency(true)
            .setAudioAttributes(communicationAudioAttributes())
            .setEnableVolumeLogger(false)
            .setPlaybackSamplesReadyCallback { samples ->
                observeAudioActivity(LiveVoiceAudioDirection.OUTPUT, samples)
            }
            .createAudioDeviceModule().apply {
                // Permanent physical-microphone prohibition, before factory or SDP creation.
                // Muting alone is insufficient: it would still allocate/start AudioRecord.
                setAudioRecordEnabled(false)
                setMicrophoneMute(true)
                setSpeakerMute(false)
            }
        else if (mediaMode.usesBufferedPrimaryAudio) JavaAudioDeviceModule.builder(appContext)
            .setInputSampleRate(PacedPcmInput.SAMPLE_RATE_HZ)
            .setAudioFormat(PacedPcmInput.PCM_16_BIT)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setEnableVolumeLogger(false)
            .setAudioBufferCallback { buffer, format, channels, rate, _, _ ->
                checkNotNull(bufferedInput).render(buffer, format, channels, rate)
            }
            .createAudioDeviceModule().apply {
                // Public pinned WebRTC API: disables creation AND start of physical AudioRecord.
                // The callback supplies PCM to nativeDataIsRecorded and therefore primary RTP.
                // This must happen BEFORE the factory can initialize any recording pipeline.
                setAudioRecordEnabled(false)
                setMicrophoneMute(true)
                setSpeakerMute(true)
            }
        else JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setEnableVolumeLogger(false)
            .createAudioDeviceModule().apply {
                // Defense in depth only: physical devices are disabled on the peer, and no
                // local media source/track is created. Muting alone would not stop capture.
                setMicrophoneMute(true)
                setSpeakerMute(true)
            }
        if (mediaMode == LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX) {
            earlyCaptureModule = audioModule
            if (inputFinishedForOutputTail.get()) {
                audioModule.setAudioRecordEnabled(false)
                audioModule.setMicrophoneMute(true)
            }
            initializationStage = "early_microphone"
            // Explicit, tap-scoped ownership starts the physical recorder before networking.
            // Its public ADM callback retains PCM even before native recording initializes.
            // The same recorder is subsequently reused by native WebRTC; never a second mic.
            // A mute can arrive while the route/peer worker is still starting. Do not open an
            // empty, already-muted microphone just to establish receive-only session readiness.
            if (!inputFinishedForOutputTail.get() &&
                (!userInputMuted.get() || startupPcm?.hasPendingDrain == true)) {
                earlyCaptureRequested = true
                audioModule.requestStartRecording(AudioProcessingOptions.communication())
            }
            if (terminal.get()) return
        }
        initializationStage = "peer_factory"
        val factory = try {
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioModule)
                .createPeerConnectionFactory()
        } finally {
            // The native factory retains its own reference.
            // The early-mode Java owner must keep its explicit recorder lease until teardown.
            if (mediaMode != LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX) audioModule.release()
        }
        peerFactory = factory
        if (terminal.get()) return

        initializationStage = "peer_instance"
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            audioJitterBufferMaxPackets = AUDIO_JITTER_BUFFER_PACKETS
            audioJitterBufferFastAccelerate = true
        }
        val peer = factory.createPeerConnection(configuration, peerObserver())
            ?: throw IllegalStateException("peer_connection_unavailable")
        peerConnection = peer
        if (terminal.get()) return
        // Disable physical recording before adding a track or negotiating SDP. Connection tones
        // must never enter the model input, even while Android has a microphone foreground service.
        peer.setAudioRecording(false)
        if (!mediaMode.permitsOutput(startupInputEnabled.get(), terminal.get() || disposed.get())) {
            peer.setAudioPlayout(false)
        }
        if (mediaMode.receivesOnly) {
            initializationStage = "receive_only_audio"
            val receiver = checkNotNull(peer.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )) { "receive_only_audio_rejected" }
            check(receiver.direction == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY &&
                receiver.sender.track() == null) { "receive_only_audio_not_effective" }
        } else {
            initializationStage = "local_audio_source"
            val source = factory.createAudioSource(if (mediaMode.usesBufferedPrimaryAudio)
                bufferedInputConstraints() else communicationConstraints())
            audioSource = source
            if (terminal.get()) return
            initializationStage = "local_audio_track"
            val track = factory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, source)
            localAudioTrack = track
            if (terminal.get()) return
            initializationStage = "local_audio_track_state"
            check(applyLocalInputState()) { "local_audio_track_state_rejected" }
            // WebRTC software processing remains the fallback when platform effects
            // are absent. A rejected late option update is non-fatal because the
            // source constraints and Java ADM configuration still apply.
            if (mediaMode.physicalCapture) {
                track.setAudioProcessingOptions(AudioProcessingOptions.communication())
            }
            initializationStage = "local_audio_track_add"
            checkNotNull(peer.addTrack(track, listOf(LOCAL_STREAM_ID))) {
                "local_audio_track_rejected"
            }
        }

        initializationStage = "data_channel"
        val channel = peer.createDataChannel(DATA_CHANNEL_LABEL, DataChannel.Init())
        attachDataChannel(channel)

        initializationStage = "local_offer"
        peer.createOffer(
            offerObserver(),
            MediaConstraints(),
        )
        initializationStage = "offer_pending"
    }

    private fun peerObserver(): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.FAILED) {
                reportClosed(LiveVoiceFailure("realtime_ice_failed", true))
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = dispatchControl {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    disconnectedFailure?.cancel(false)
                    disconnectedFailure = null
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    disconnectedFailure?.cancel(false)
                    disconnectedFailure = scheduler.schedule(
                        { dispatchControl {
                            if (
                                !terminal.get() &&
                                peerConnection?.connectionState() ==
                                PeerConnection.PeerConnectionState.DISCONNECTED
                            ) {
                                reportClosed(
                                    LiveVoiceFailure("realtime_peer_disconnected", true),
                                )
                            }
                        } },
                        DISCONNECT_GRACE_MILLIS,
                        TimeUnit.MILLISECONDS,
                    )
                }
                PeerConnection.PeerConnectionState.FAILED ->
                    reportClosed(LiveVoiceFailure("realtime_peer_failed", true))
                PeerConnection.PeerConnectionState.CLOSED -> {
                    if (!terminal.get()) {
                        reportClosed(LiveVoiceFailure("realtime_peer_closed", true))
                    }
                }
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                dispatchControl { postLocalOffer() }
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onAddStream(stream: MediaStream) = admitRemoteAudio { stream.audioTracks }

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) = dispatchControl {
            if (channel.label() == DATA_CHANNEL_LABEL) attachDataChannel(channel)
        }

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = admitRemoteAudio {
            listOfNotNull(receiver.track() as? AudioTrack)
        }

        override fun onTrack(transceiver: RtpTransceiver) = admitRemoteAudio {
            listOfNotNull(transceiver.receiver.track() as? AudioTrack)
        }
    }

    private fun admitRemoteAudio(tracks: () -> List<AudioTrack>) {
        if (mediaMode != LiveVoiceMediaMode.OUTPUT_ONLY) {
            // Preserve the existing DUPLEX/buffered callback and control-worker behavior.
            dispatchControl { tracks().forEach(::enableRemoteAudio) }
            return
        }
        OutputOnlyRemoteTrackAdmission.receive(
            tracks = tracks,
            mute = { track -> LiveVoiceLocalTrackState.apply(false, track::setEnabled, track::enabled) },
            enqueue = { muted -> dispatchControl { muted.forEach(::enableRemoteAudio) } },
            onFailure = {
                // A newly created native track is enabled by default. If it rejects direct
                // mute, stop peer playout before asynchronous teardown; never admit the track.
                ignoreRuntimeFailure { peerConnection?.setAudioPlayout(false) }
                reportClosed(LiveVoiceFailure("output_only_remote_audio_not_disabled", false))
            },
        )
    }

    private fun offerObserver(): SdpObserver =
        object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = dispatchControl {
                val peer = peerConnection ?: return@dispatchControl
                peer.setLocalDescription(
                    object : SdpObserver {
                        override fun onCreateSuccess(description: SessionDescription) = Unit

                        override fun onSetSuccess() = dispatchControl {
                            offerFallback?.cancel(false)
                            offerFallback = scheduler.schedule(
                                { dispatchControl { postLocalOffer() } },
                                ICE_GATHERING_FALLBACK_MILLIS,
                                TimeUnit.MILLISECONDS,
                            )
                        }

                        override fun onCreateFailure(error: String) = Unit

                        override fun onSetFailure(error: String) {
                            reportClosed(
                                LiveVoiceFailure("realtime_local_description_failed", true),
                            )
                        }
                    },
                    description,
                )
            }

            override fun onSetSuccess() = Unit

            override fun onCreateFailure(error: String) {
                reportClosed(LiveVoiceFailure("realtime_offer_failed", true))
            }

            override fun onSetFailure(error: String) = Unit
        }

    private fun postLocalOffer() {
        if (terminal.get() || !offerPosted.compareAndSet(false, true)) return
        offerFallback?.cancel(false)
        offerFallback = null
        val offer = peerConnection?.localDescription?.description
        if (offer.isNullOrBlank() || offer.length > MAX_SDP_CHARACTERS) {
            reportClosed(LiveVoiceFailure("realtime_local_sdp_invalid", true))
            return
        }
        val setup = pendingSetup
        pendingSetup = null
        if (setup == null) {
            reportClosed(LiveVoiceFailure("live_session_setup_missing", false))
            return
        }
        sessionRequest = sessionProvider.create(
            setup, offer,
            object : LiveSessionProvider.Callback {
                override fun onFailure(failure: LiveVoiceFailure) {
                    if (!terminal.get()) reportClosed(failure)
                }

                override fun onCreated(answer: LiveSessionAnswer) {
                    dispatchControl { setRemoteAnswer(answer.sdp) }
                }
            },
        )
    }

    private fun setRemoteAnswer(answer: String) {
        val peer = peerConnection ?: return
        peer.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String) = Unit
                override fun onSetFailure(error: String) {
                    reportClosed(LiveVoiceFailure("realtime_remote_description_failed", true))
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, answer),
        )
    }

    private fun attachDataChannel(channel: DataChannel) {
            val previous = dataChannel
            if (previous !== channel) {
                ignoreRuntimeFailure { previous?.unregisterObserver() }
                ignoreRuntimeFailure { previous?.close() }
                ignoreRuntimeFailure { previous?.dispose() }
                dataChannel = channel
            }
            channel.registerObserver(
                object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) = Unit

                    override fun onStateChange() = dispatchControl {
                        if (channel !== dataChannel) return@dispatchControl
                        when (channel.state()) {
                            DataChannel.State.OPEN -> {
                                if (opened.compareAndSet(false, true) && !terminal.get()) {
                                    notifyOpen()
                                }
                            }
                            DataChannel.State.CLOSED -> {
                                if (!terminal.get()) {
                                    reportClosed(
                                        LiveVoiceFailure("realtime_data_channel_closed", true),
                                    )
                                }
                            }
                            else -> Unit
                        }
                    }

                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (terminal.get() || buffer.binary) return
                        val source = buffer.data.slice()
                        if (source.remaining() !in 1..OpenAiRealtimeProtocol.MAX_EVENT_BYTES) {
                            dispatchControl { if (channel === dataChannel) notifyEvent("{}") }
                            return
                        }
                        val bytes = ByteArray(source.remaining())
                        source.get(bytes)
                        // The native ByteBuffer is callback-scoped; copy it before returning.
                        val event = String(bytes, StandardCharsets.UTF_8)
                        dispatchControl { if (channel === dataChannel) notifyEvent(event) }
                    }
                },
            )
            if (!terminal.get() && channel.state() == DataChannel.State.OPEN && opened.compareAndSet(false, true)) {
                notifyOpen()
            }
    }

    private fun enableRemoteAudio(track: AudioTrack) {
        if (!mediaMode.playsOutput) {
            check(LiveVoiceLocalTrackState.apply(false, track::setEnabled, track::enabled)) {
                "silent_remote_audio_not_effective"
            }
            peerConnection?.setAudioPlayout(false)
            return
        }
        if (mediaMode == LiveVoiceMediaMode.OUTPUT_ONLY) {
            // A track can arrive before the data channel or native Started receipt. Disable
            // it before verifying gain, then let the explicit output-only gate enable it.
            check(LiveVoiceLocalTrackState.apply(false, track::setEnabled, track::enabled)) {
                "output_only_remote_audio_not_disabled"
            }
        }
        if (mediaMode != LiveVoiceMediaMode.OUTPUT_ONLY) track.setEnabled(true)
        if (!speakerGain.attach(track.id(), object : LiveVoiceSpeakerGainTrack {
                override fun setGain(value: Double) = track.setVolume(value)
                override fun readGain(): Double = track.getVolume()
                override fun disable() { track.setEnabled(false) }
            })) {
            track.setEnabled(false)
            reportClosed(LiveVoiceFailure("live_output_gain_unverified", false))
            return
        }
        if (mediaMode == LiveVoiceMediaMode.OUTPUT_ONLY &&
            (!outputOnlyPlayback.attach(track.id(), track::setEnabled, track::enabled) || !applyOutputOnlyState())) {
            reportClosed(LiveVoiceFailure("live_output_state_rejected", false))
        }
    }

    private fun observeAudioActivity(
        direction: LiveVoiceAudioDirection,
        samples: JavaAudioDeviceModule.AudioSamples,
    ) {
        if (!audioActivityMonitoringEnabled.get() || terminal.get() || disposed.get()) return
        val observedAtNanos = System.nanoTime()
        val pending = synchronized(audioActivityMonitorLock) {
            if (!audioActivityMonitoringEnabled.get() || terminal.get() || disposed.get()) return
            if (direction == LiveVoiceAudioDirection.INPUT &&
                (inputFinishedForOutputTail.get() ||
                    !mediaMode.permitsCapture(startupInputEnabled.get(), userInputMuted.get(), false) ||
                    !contextInputEnabled.get())
            ) return
            if (direction == LiveVoiceAudioDirection.OUTPUT &&
                !mediaMode.permitsOutput(startupInputEnabled.get(), false)) return
            val meter = if (direction == LiveVoiceAudioDirection.INPUT) inputActivityMeter else outputActivityMeter
            val activity = meter.observe(
                pcm = samples.data,
                audioFormat = samples.audioFormat,
                channelCount = samples.channelCount,
                sampleRate = samples.sampleRate,
                observedAtNanos = observedAtNanos,
            ) ?: return
            if (!activity.reliable && mediaMode != LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX) {
                // Unknown PCM or a callback discontinuity invalidates the entire candidate.
                // A telephone farewell never automatically resumes after this gap.
                audioActivityMonitoringEnabled.set(false)
                audioActivityMonitorGeneration += 1
                inputActivityMeter.reset()
                outputActivityMeter.reset()
            }
            // Continuous dictation retains observation after forwarding the unreliable event.
            // Its direction-specific meter requires NEW valid PCM before rebuilding evidence;
            // the gap never counts as silence and queued invalidations must still be delivered.
            audioActivityMonitorGeneration to activity
        }
        // The realtime audio thread must never synchronously close/join its own native track.
        // Only activity metadata crosses this boundary; no PCM is retained or logged.
        dispatchControl {
            val current = synchronized(audioActivityMonitorLock) {
                audioActivityMonitorGeneration == pending.first &&
                    (audioActivityMonitoringEnabled.get() || !pending.second.reliable)
            }
            if (current) listener?.onAudioActivity(pending.second)
        }
    }

    /** Observe real physical input before the buffer callback replaces it with older speech. */
    private fun observeEarlyPhysicalInput(
        buffer: ByteBuffer,
        format: Int,
        channels: Int,
        rate: Int,
        bytesRead: Int,
    ) {
        if (!audioActivityMonitoringEnabled.get() || terminal.get() || disposed.get()) return
        if (userInputMuted.get() || inputFinishedForOutputTail.get()) return // Drain/tail callbacks are not user activity.
        if (format != StartupPcmBuffer.PCM_16_BIT || channels != 1 ||
            rate != StartupPcmBuffer.SAMPLE_RATE_HZ || bytesRead != StartupPcmBuffer.FRAME_BYTES ||
            buffer.capacity() != StartupPcmBuffer.FRAME_BYTES
        ) return // The PCM gate reports this failure; malformed bytes never become quiet evidence.
        try {
            val pcm = ByteArray(bytesRead)
            buffer.duplicate().apply { clear(); get(pcm) }
            try {
                observeAudioActivity(LiveVoiceAudioDirection.INPUT,
                    JavaAudioDeviceModule.AudioSamples(format, channels, rate, pcm))
            } finally { pcm.fill(0) }
        } catch (_: RuntimeException) {
            reportClosed(LiveVoiceFailure("action_voice_pcm_callback_failed", false))
        }
    }

    private fun applyLocalInputState(): Boolean {
        if (mediaMode == LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX) return applyEarlyCaptureState()
        if (mediaMode.usesBufferedPrimaryAudio) return applyBufferedInputState()
        if (!mediaMode.physicalCapture) return applyReceiveOnlyState()
        val track = localAudioTrack ?: return !opened.get() && !disposed.get()
        val enabled = mediaMode.permitsCapture(startupInputEnabled.get(), userInputMuted.get(),
            terminal.get() || disposed.get()) && contextInputEnabled.get()
        return LiveVoiceLocalTrackState.apply(
            desiredEnabled = enabled,
            setEnabled = track::setEnabled,
            readEnabled = track::enabled,
        )
    }

    /** Executed only by the native control worker, including startup, mute and teardown. */
    private fun applyPhysicalInputState(): Boolean = try {
        if (mediaMode == LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX) applyEarlyCaptureState()
        else if (mediaMode.usesBufferedPrimaryAudio) applyBufferedInputState()
        else {
            val enabled = mediaMode.permitsCapture(startupInputEnabled.get(), userInputMuted.get(),
                terminal.get() || disposed.get())
            val peer = peerConnection
            if (enabled && peer == null) false else {
                peer?.setAudioRecording(enabled)
                if (!mediaMode.physicalCapture) applyReceiveOnlyState()
                else if (localAudioTrack == null) !enabled else applyLocalInputState()
            }
        }
    } catch (_: RuntimeException) {
        false
    }

    /** Separate explicit physical capture ownership from native/RTP admission. */
    private fun applyEarlyCaptureState(): Boolean {
        if (inputFinishedForOutputTail.get()) return applyFinishedInputForOutputTail()
        val input = startupPcm ?: return false
        val ended = terminal.get() || disposed.get()
        if (!input.isOpen) return false
        // Only the caller's atomic mute boundary changes physical-frame admission. A queued
        // control update must never reopen it using an older userInputMuted snapshot.
        val pipeline = EarlyCapturePipelinePolicy.resolve(
            muted = !input.acceptsPhysicalFrames, pendingPrefix = input.hasPendingDrain,
            ready = startupInputEnabled.get(), contextEnabled = contextInputEnabled.get(), ended = ended,
        )
        val module = earlyCaptureModule
        val peer = peerConnection
        if (!pipeline.keepRecorderRunning && earlyCaptureRequested) {
            // Either owner alone keeps ADM callbacks alive. Revoke both only after the saved
            // prefix crossed its final callback boundary; remote speaker output stays separate.
            input.setTransmissionEnabled(false)
            peer?.setAudioRecording(false)
            module?.requestStopRecording()
            earlyCaptureRequested = false
        } else if (pipeline.keepRecorderRunning && module != null && !earlyCaptureRequested) {
            earlyCaptureRequested = true
            module.requestStartRecording(AudioProcessingOptions.communication())
        }
        val send = pipeline.sendNativeInput
        // Disable consumption before disabling native input. Do not reset the warmup barrier
        // during an unrelated same-state update: readiness transitions alone open this gate.
        if (!send && !input.setTransmissionEnabled(false)) return false
        val track = localAudioTrack
        if (track == null || peer == null) return !send
        if (!LiveVoiceLocalTrackState.apply(send, track::setEnabled, track::enabled)) return false
        peer.setAudioRecording(send)
        peer.setAudioPlayout(pipeline.playOutput)
        return input.setTransmissionEnabled(send)
    }

    /** Native worker only: revoke both recorder owners without touching remote output tracks. */
    private fun applyFinishedInputForOutputTail(): Boolean {
        if (mediaMode != LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX || !inputFinishedForOutputTail.get()) return false
        userInputMuted.set(true)
        contextInputEnabled.set(false)
        val peer = peerConnection
        val module = earlyCaptureModule
        val track = localAudioTrack
        val stopped = LiveVoiceOutputTailInputShutdown.apply(
            disableLocalTrack = {
                track == null || LiveVoiceLocalTrackState.apply(false, track::setEnabled, track::enabled)
            },
            disableNativeRecording = { peer?.setAudioRecording(false) },
            disablePhysicalRecording = { module?.setAudioRecordEnabled(false) },
            stopExplicitRecorder = {
                module?.requestStopRecording()
                earlyCaptureRequested = false
            },
            preservePlayout = {
                peer?.setAudioPlayout(mediaMode.permitsOutput(startupInputEnabled.get(), terminal.get() || disposed.get()))
            },
        )
        return stopped && (!earlyRecorderStarted.get() || earlyRecorderStopped.get())
    }

    private fun applyReceiveOnlyState(): Boolean {
        val peer = peerConnection ?: return localAudioTrack == null && audioSource == null
        return LiveVoiceReceiveOnlyMediaState.apply(
            mode = mediaMode,
            started = startupInputEnabled.get(),
            ended = terminal.get() || disposed.get(),
            localInputAbsent = localAudioTrack == null && audioSource == null,
            setRecording = peer::setAudioRecording,
            setPlayout = peer::setAudioPlayout,
        )
    }

    private fun applyOutputOnlyState(): Boolean {
        val enabled = mediaMode.permitsOutput(startupInputEnabled.get(), terminal.get() || disposed.get())
        val tracksReady = outputOnlyPlayback.setReady(enabled)
        if (!tracksReady) {
            peerConnection?.setAudioPlayout(false)
            return false
        }
        return applyReceiveOnlyState()
    }

    private fun applyBufferedInputState(): Boolean {
        val input = bufferedInput ?: return false
        // Do not consume a buffered prefix until both native session and data channel are ready.
        // Disabling AudioRecord on the ADM is permanent; enabling this peer flag starts only
        // its PCM/RTP processing pipeline, never a second physical microphone recorder.
        if (!input.setEnabled(false)) return false
        val enabled = startupInputEnabled.get() && contextInputEnabled.get() &&
            !userInputMuted.get() && !terminal.get() && !disposed.get()
        val track = localAudioTrack ?: return !enabled
        val peer = peerConnection ?: return !enabled
        if (!LiveVoiceLocalTrackState.apply(enabled, track::setEnabled, track::enabled)) return false
        peer.setAudioPlayout(false)
        peer.setAudioRecording(enabled)
        return input.setEnabled(enabled)
    }

    private fun bufferedInputConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "false")
        mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "false")
        mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "false")
        mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "false")
    }

    private fun communicationConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
        mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
        mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
        mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
    }

    private fun communicationAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun reportClosed(failure: LiveVoiceFailure) {
        if (!terminal.compareAndSet(false, true)) return
        bufferedInput?.close()
        startupPcm?.close()
        LiveVoiceDiagnostics.event(
            "TRANSPORT_CLOSED",
            "code=${failure.code.take(96)} stage=$initializationStage",
        )
        // Never synchronously wait here: Android route and WebRTC callbacks can be needed by
        // native calls already in progress on the control worker.
        control.execute {
            val callback = listener
            disposeResources()
            try { callback?.onClosed(failure) } catch (_: RuntimeException) { Unit }
        }
    }

    private fun notifyOpen() {
        try {
            listener?.onOpen()
        } catch (_: RuntimeException) {
            reportClosed(LiveVoiceFailure("realtime_listener_failed", false))
        }
    }

    private fun notifyEvent(event: String) {
        try {
            listener?.onEvent(event)
        } catch (_: RuntimeException) {
            reportClosed(LiveVoiceFailure("realtime_listener_failed", false))
        }
    }

    private fun disposeResources() {
        if (!disposed.compareAndSet(false, true)) return
        var failed = false
        var completedSuccessfully = false
        fun disposeStep(action: () -> Unit) {
            try { action() } catch (_: RuntimeException) { failed = true }
        }
        try {
        disposeStep { bufferedInput?.close() }
        disposeStep { startupPcm?.close() }
        if (mediaMode == LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX) {
            // First stop both recorder owners, before disposing the native factory/ADM.
            disposeStep { peerConnection?.setAudioRecording(false) }
            disposeStep { earlyCaptureModule?.requestStopRecording() }
            earlyCaptureRequested = false
        }
        synchronized(audioActivityMonitorLock) {
            audioActivityMonitoringEnabled.set(false)
            audioActivityMonitorGeneration += 1
            inputActivityMeter.reset()
            outputActivityMeter.reset()
        }
        disposeStep { connectionAudio?.close() }
        disposeStep { offerFallback?.cancel(false) }
        disposeStep { disconnectedFailure?.cancel(false) }
        disposeStep { sessionRequest?.cancel() }
        pendingSetup = null
        if (mediaMode == LiveVoiceMediaMode.OUTPUT_ONLY) {
            disposeStep { peerConnection?.setAudioRecording(false) }
            disposeStep { peerConnection?.setAudioPlayout(false) }
            disposeStep { check(outputOnlyPlayback.close()) { "output_only_track_stop_unconfirmed" } }
        }
        disposeStep { dataChannel?.unregisterObserver() }
        disposeStep { dataChannel?.close() }
        disposeStep { dataChannel?.dispose() }
        // Stop accepting route-driven gain operations and drain native gain reads/writes before
        // releasing the PeerConnection-owned remote audio tracks.
        disposeStep { speakerGain.close() }
        disposeStep { peerConnection?.close() }
        disposeStep { peerConnection?.dispose() }
        disposeStep { localAudioTrack?.dispose() }
        disposeStep { audioSource?.dispose() }
        disposeStep { peerFactory?.dispose() }
        disposeStep { earlyCaptureModule?.release() }
        earlyCaptureModule = null
        if (mediaMode == LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX) {
            disposeStep { check(!earlyRecorderStarted.get() || earlyRecorderStopped.get()) {
                "action_voice_capture_stop_unconfirmed"
            } }
        }
        dataChannel = null
        peerConnection = null
        localAudioTrack = null
        audioSource = null
        peerFactory = null
        if (mediaMode.takesAudioFocus) disposeStep { check(restoreCommunicationAudio()) { "audio_restore_failed" } }
        disposeStep { ownedScheduler?.shutdownNow() }
        disposeStep { control.close() }
        completedSuccessfully = !failed
        } finally {
            // This is intentionally after teardown, never the disposed CAS above. Even an
            // unexpected fatal failure cannot turn a partially released endpoint into success.
            disposalReceipt.complete(completedSuccessfully)
        }
    }

    private inline fun ignoreRuntimeFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            Unit
        }
    }

    private fun restoreCommunicationAudio(): Boolean {
        var successful = true
        runCatching { routeRegistration?.close() }.onFailure { successful = false }
        routeRegistration = null
        val manager = audioManager ?: return audioFocusRequest == null && communicationModeRequest == null && successful
        audioFocusRequest?.let {
            try {
                if (manager.abandonAudioFocusRequest(it) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) successful = false
            } catch (_: RuntimeException) {
                successful = false
            }
        }
        audioFocusRequest = null
        // Since API 31 AudioManager arbitrates mode requests by owner priority. Always release
        // Hans' own request even while Telephony effectively exposes MODE_IN_CALL; otherwise the
        // latent MODE_IN_COMMUNICATION request can become effective after the real call ends.
        runCatching { communicationModeRequest?.close() }.onFailure { successful = false }
        communicationModeRequest = null
        return successful
    }

    companion object {
        fun probeAudioCapabilities(): LiveVoiceAudioCapabilities = LiveVoiceAudioCapabilities(
            hardwareAcousticEchoCancellation =
                JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported(),
            hardwareNoiseSuppression =
                JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported(),
        )

        private val initializationMonitor = Any()
        @Volatile
        private var initialized = false

        private fun ensureWebRtcInitialized(context: Context) {
            if (initialized) return
            synchronized(initializationMonitor) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions(),
                )
                initialized = true
            }
        }

        private const val DATA_CHANNEL_LABEL = "oai-events"
        private const val LOCAL_AUDIO_TRACK_ID = "hans-live-audio"
        private const val LOCAL_STREAM_ID = "hans-live-stream"
        private const val MAX_CLIENT_EVENT_BYTES = 128 * 1_024
        private const val MAX_SDP_CHARACTERS = 1_000_000
        private const val AUDIO_JITTER_BUFFER_PACKETS = 50
        private const val ICE_GATHERING_FALLBACK_MILLIS = 1_500L
        private const val DISCONNECT_GRACE_MILLIS = 5_000L
    }
}

/** One truthful completion result, reusable by concurrent/repeated bounded teardown waiters. */
internal class LiveVoiceDisposalReceipt(
    private val onSuccessfulDisposal: () -> Unit = {},
) {
    private val finished = CountDownLatch(1)
    private val completed = AtomicBoolean(false)
    @Volatile private var successful = false

    fun complete(success: Boolean) {
        if (!completed.compareAndSet(false, true)) return
        successful = success
        finished.countDown()
        // Event-driven recovery remains possible after any bounded waiter timed out. This is
        // emitted once and only for actual success, never for a connection-close/requested flag.
        if (success) runCatching(onSuccessfulDisposal)
    }

    fun awaitNanos(timeoutNanos: Long): Boolean = try {
        finished.await(timeoutNanos.coerceAtLeast(0L), TimeUnit.NANOSECONDS) && successful
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}

/** Every input owner gets its stop attempt even if a different native stop rejects or throws. */
internal object LiveVoiceOutputTailInputShutdown {
    fun apply(
        disableLocalTrack: () -> Boolean,
        disableNativeRecording: () -> Unit,
        disablePhysicalRecording: () -> Unit,
        stopExplicitRecorder: () -> Unit,
        preservePlayout: () -> Unit,
    ): Boolean {
        var successful = try { disableLocalTrack() } catch (_: RuntimeException) { false }
        listOf(disableNativeRecording, disablePhysicalRecording, stopExplicitRecorder, preservePlayout).forEach { step ->
            try { step() } catch (_: RuntimeException) { successful = false }
        }
        return successful
    }
}

/** Testable receive-only native boundary. Input flags/mute can never enable physical recording. */
internal object LiveVoiceReceiveOnlyMediaState {
    fun apply(
        mode: LiveVoiceMediaMode,
        started: Boolean,
        ended: Boolean,
        localInputAbsent: Boolean,
        setRecording: (Boolean) -> Unit,
        setPlayout: (Boolean) -> Unit,
    ): Boolean {
        if (!mode.receivesOnly) return false
        return try {
            setRecording(false)
            setPlayout(localInputAbsent && mode.permitsOutput(started, ended))
            localInputAbsent
        } catch (_: RuntimeException) {
            runCatching { setPlayout(false) }
            false
        }
    }
}

/**
 * WebRTC's `setEnabled` result means "the native value changed", not "the request worked".
 * An already-enabled track therefore returns false for `setEnabled(true)`. Verify the effective
 * state instead, otherwise an ordinary unmuted call is torn down before its SDP offer is created.
 */
internal object LiveVoiceLocalTrackState {
    fun apply(
        desiredEnabled: Boolean,
        setEnabled: (Boolean) -> Boolean,
        readEnabled: () -> Boolean,
    ): Boolean {
        return try {
            if (readEnabled() == desiredEnabled) {
                true
            } else {
                setEnabled(desiredEnabled)
                readEnabled() == desiredEnabled
            }
        } catch (_: RuntimeException) {
            false
        }
    }
}
