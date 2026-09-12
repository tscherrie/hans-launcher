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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
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

/**
 * Native, duplex Android WebRTC transport for the OpenAI Live API.
 *
 * It owns no Activity and never reacts to foreground/background callbacks.
 * Android `VOICE_COMMUNICATION`, WebRTC communication processing, hardware AEC
 * when available, and hardware NS when available remain active for the whole
 * transport lifetime. The remote audio track is WebRTC playout, not an Android
 * TTS queue, so capture and playback can operate concurrently.
 */
class AndroidWebRtcRealtimeTransport(
    context: Context,
    private val sessionProvider: LiveSessionProvider,
    scheduler: ScheduledExecutorService? = null,
    private val audioRoutes: AndroidSpeechAudioRouteController? = null,
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
    private val startupInputEnabled = AtomicBoolean(false)
    private val audioActivityMonitoringEnabled = AtomicBoolean(false)
    private val audioActivityMonitorLock = Any()
    private var audioActivityMonitorGeneration = 0L
    private val inputActivityMeter = LiveVoiceAudioActivityMeter(LiveVoiceAudioDirection.INPUT)
    private val outputActivityMeter = LiveVoiceAudioActivityMeter(LiveVoiceAudioDirection.OUTPUT)
    private val connectionAudio = LiveConnectionAudioGate(AndroidLiveConnectionTone(audioManager)) { enabled ->
        startupInputEnabled.set(enabled)
        check(applyPhysicalInputState()) { "live_input_recording_state_rejected" }
    }

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
                initializationStage = "communication_audio"
                configureCommunicationAudio()
                if (terminal.get()) return@call
                connectionAudio.connecting()
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

    override fun setInputAudioEnabled(enabled: Boolean): Boolean = controlResult {
        contextInputEnabled.set(enabled)
        applyLocalInputState()
    }

    override fun setUserInputMuted(muted: Boolean): Boolean = controlResult {
        userInputMuted.set(muted)
        applyPhysicalInputState().also { applied ->
            if (!applied) reportClosed(LiveVoiceFailure("live_input_mute_failed", false))
        }
    }

    override fun confirmSessionStarted(): Boolean = controlResult {
        connectionAudio.connected()
        applyLocalInputState()
    }

    // Live handles conversational interruption from input audio. No legacy buffer-clear event.
    override fun clearOutputAudio(): Boolean = false

    override fun setAudioActivityMonitoringEnabled(enabled: Boolean): Boolean = controlResult {
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
        // External callers are not platform callback threads. Route/native callbacks instead
        // use reportClosed(), which enqueues cleanup and never waits for this worker.
        runCatching { control.call { disposeResources() } }
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
        val routes = audioRoutes
            ?: throw LiveVoiceAudioRouteException("realtime_audio_route_controller_unavailable")
        routeRegistration = routes.attachRequiredLiveCommunication(
            beforeRouteChange = speakerGain::beforeRouteChange,
            onConfirmedRoute = speakerGain::routeConfirmed,
        ) {
            reportClosed(LiveVoiceFailure("live_audio_route_lost", false))
        }
    }

    private fun createPeerConnection() {
        initializationStage = "audio_device_module"
        val audioModule = JavaAudioDeviceModule.builder(appContext)
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
            .setSamplesReadyCallback { samples ->
                observeAudioActivity(LiveVoiceAudioDirection.INPUT, samples)
            }
            // In pinned WebRTC 144.7559.12 this callback follows blocking AudioTrack.write.
            // It proves PCM submission, not physical speaker drain; the session retains a
            // conservative quiet grace and never treats a missing callback as drained audio.
            .setPlaybackSamplesReadyCallback { samples ->
                observeAudioActivity(LiveVoiceAudioDirection.OUTPUT, samples)
            }
            .createAudioDeviceModule()
        initializationStage = "peer_factory"
        val factory = try {
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioModule)
                .createPeerConnectionFactory()
        } finally {
            // The native factory retains its own reference.
            audioModule.release()
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

        initializationStage = "local_audio_source"
        val source = factory.createAudioSource(communicationConstraints())
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
        track.setAudioProcessingOptions(AudioProcessingOptions.communication())
        initializationStage = "local_audio_track_add"
        checkNotNull(peer.addTrack(track, listOf(LOCAL_STREAM_ID))) {
            "local_audio_track_rejected"
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

        override fun onAddStream(stream: MediaStream) = dispatchControl {
            stream.audioTracks.forEach(::enableRemoteAudio)
        }

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) = dispatchControl {
            if (channel.label() == DATA_CHANNEL_LABEL) attachDataChannel(channel)
        }

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = dispatchControl {
            (receiver.track() as? AudioTrack)?.let(::enableRemoteAudio)
        }

        override fun onTrack(transceiver: RtpTransceiver) = dispatchControl {
            (transceiver.receiver.track() as? AudioTrack)?.let(::enableRemoteAudio)
        }
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
        track.setEnabled(true)
        if (!speakerGain.attach(track.id(), object : LiveVoiceSpeakerGainTrack {
                override fun setGain(value: Double) = track.setVolume(value)
                override fun readGain(): Double = track.getVolume()
                override fun disable() { track.setEnabled(false) }
            })) {
            track.setEnabled(false)
            reportClosed(LiveVoiceFailure("live_output_gain_unverified", false))
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
                (!startupInputEnabled.get() || !contextInputEnabled.get() || userInputMuted.get())
            ) return
            val meter = if (direction == LiveVoiceAudioDirection.INPUT) inputActivityMeter else outputActivityMeter
            val activity = meter.observe(
                pcm = samples.data,
                audioFormat = samples.audioFormat,
                channelCount = samples.channelCount,
                sampleRate = samples.sampleRate,
                observedAtNanos = observedAtNanos,
            ) ?: return
            if (!activity.reliable) {
                // Unknown PCM or a callback discontinuity invalidates the entire candidate.
                // Do not invent speech/quiet evidence or resume automatically after this gap.
                audioActivityMonitoringEnabled.set(false)
                audioActivityMonitorGeneration += 1
                inputActivityMeter.reset()
                outputActivityMeter.reset()
            }
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

    private fun applyLocalInputState(): Boolean {
        val track = localAudioTrack ?: return !opened.get() && !disposed.get()
        val enabled = !terminal.get() && !disposed.get() && startupInputEnabled.get() &&
            contextInputEnabled.get() && !userInputMuted.get()
        return LiveVoiceLocalTrackState.apply(
            desiredEnabled = enabled,
            setEnabled = track::setEnabled,
            readEnabled = track::enabled,
        )
    }

    /** Executed only by the native control worker, including startup, mute and teardown. */
    private fun applyPhysicalInputState(): Boolean = try {
        val enabled = !terminal.get() && !disposed.get() && startupInputEnabled.get() && !userInputMuted.get()
        val peer = peerConnection
        if (enabled && peer == null) false else {
            peer?.setAudioRecording(enabled)
            if (localAudioTrack == null) !enabled else applyLocalInputState()
        }
    } catch (_: RuntimeException) {
        false
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
        synchronized(audioActivityMonitorLock) {
            audioActivityMonitoringEnabled.set(false)
            audioActivityMonitorGeneration += 1
            inputActivityMeter.reset()
            outputActivityMeter.reset()
        }
        ignoreRuntimeFailure { connectionAudio.close() }
        ignoreRuntimeFailure { offerFallback?.cancel(false) }
        ignoreRuntimeFailure { disconnectedFailure?.cancel(false) }
        ignoreRuntimeFailure { sessionRequest?.cancel() }
        pendingSetup = null
        ignoreRuntimeFailure { dataChannel?.unregisterObserver() }
        ignoreRuntimeFailure { dataChannel?.close() }
        ignoreRuntimeFailure { dataChannel?.dispose() }
        // Stop accepting route-driven gain operations and drain native gain reads/writes before
        // releasing the PeerConnection-owned remote audio tracks.
        ignoreRuntimeFailure { speakerGain.close() }
        ignoreRuntimeFailure { peerConnection?.close() }
        ignoreRuntimeFailure { peerConnection?.dispose() }
        ignoreRuntimeFailure { localAudioTrack?.dispose() }
        ignoreRuntimeFailure { audioSource?.dispose() }
        ignoreRuntimeFailure { peerFactory?.dispose() }
        dataChannel = null
        peerConnection = null
        localAudioTrack = null
        audioSource = null
        peerFactory = null
        ignoreRuntimeFailure { restoreCommunicationAudio() }
        ignoreRuntimeFailure { ownedScheduler?.shutdownNow() }
        control.close()
    }

    private inline fun ignoreRuntimeFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            Unit
        }
    }

    private fun restoreCommunicationAudio() {
        runCatching { routeRegistration?.close() }
        routeRegistration = null
        val manager = audioManager ?: return
        audioFocusRequest?.let {
            try {
                manager.abandonAudioFocusRequest(it)
            } catch (_: RuntimeException) {
                Unit
            }
        }
        // Since API 31 AudioManager arbitrates mode requests by owner priority. Always release
        // Hans' own request even while Telephony effectively exposes MODE_IN_CALL; otherwise the
        // latent MODE_IN_COMMUNICATION request can become effective after the real call ends.
        runCatching { communicationModeRequest?.close() }
        communicationModeRequest = null
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
