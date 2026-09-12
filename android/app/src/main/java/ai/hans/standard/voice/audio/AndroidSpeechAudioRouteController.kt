package ai.hans.standard.voice.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.HandlerThread
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.ExecutionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LiveVoiceAudioRouteException(
    val failureCode: String,
) : IllegalStateException(failureCode)

class AndroidSpeechAudioRouteController(context: Context) : SpeechAudioRouteController(
    { callback -> Handler(Looper.getMainLooper()).post(callback) },
) {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    // Serializes track release with requests without holding controller/UI locks across Android APIs.
    // This thread sleeps on its Looper when no route events occur; it does not poll.
    private val routeThread = HandlerThread("hans-speech-routing").apply { start() }
    private val handler = Handler(routeThread.looper)
    private var communicationPreferenceOwner: AndroidEndpoint? = null

    private fun <T> serial(block: () -> T): T {
        if (Looper.myLooper() == handler.looper) return block()
        val task = FutureTask<T> { block() }
        check(handler.post(task)) { "audio_route_thread_unavailable" }
        return try { task.get() } catch (error: ExecutionException) {
            throw (error.cause as? RuntimeException ?: IllegalStateException("audio_route_failed", error.cause))
        } catch (error: InterruptedException) {
            task.cancel(false)
            Thread.currentThread().interrupt()
            throw IllegalStateException("audio_route_interrupted", error)
        }
    }

    fun attachTrack(track: AudioTrack): SpeechAudioTrackRouteRegistration {
        val endpoint = AndroidEndpoint(track)
        val registration = attach(endpoint)
        return SpeechAudioTrackRouteRegistration(registration,
            requiresWarmup = { serial { endpoint.requiresPrivateWarmup() } },
            awaitRoute = {
                check(Looper.myLooper() != handler.looper && Looper.myLooper() != Looper.getMainLooper()) {
                    "speech_route_wait_requires_worker"
                }
                val signal = serial { endpoint.routeSignal() }
                check(signal.await(ROUTE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "speech_route_ack_timeout" }
            },
            verify = {
            try { serial { endpoint.verifyPrivateRoute() } } catch (error: RuntimeException) {
                reportRouteFailure()
                throw error
            }
        }, cancelWait = { endpoint.cancelWait() })
    }

    /**
     * Claims and verifies speaker output for one switchable built-in-phone Live call.
     * A successful Android preference is not enough: this method blocks on the routing worker
     * until `communicationDevice` is exactly [AudioDeviceInfo.TYPE_BUILTIN_SPEAKER]. Explicit
     * speaker/earpiece changes are acknowledged by Android, never inferred from acceptance.
     * Headset attachment does not override the selected built-in route; unexpected route loss
     * ends the call, including loss of a subsequently selected private earpiece route.
     */
    fun attachRequiredLiveCommunication(
        beforeRouteChange: () -> Boolean = { true },
        onConfirmedRoute: (SpeechAudioRoute) -> Boolean = { true },
        onRouteLost: () -> Unit,
    ): AutoCloseable {
        check(Looper.myLooper() != handler.looper && Looper.myLooper() != Looper.getMainLooper()) {
            "live_voice_route_wait_requires_worker"
        }
        if (manager == null) {
            throw LiveVoiceAudioRouteException("realtime_audio_manager_unavailable")
        }
        val endpoint = AndroidEndpoint(
            track = null,
            liveCommunication = true,
            onLiveRouteLost = onRouteLost,
            beforeLiveRouteChange = beforeRouteChange,
            onConfirmedLiveRoute = onConfirmedRoute,
        )
        val registration = try {
            attach(endpoint)
        } catch (error: RuntimeException) {
            runCatching { endpoint.close() }
            throw LiveVoiceAudioRouteException("realtime_speaker_route_probe_failed")
        }
        try {
            when (request(SpeechAudioRoute.SPEAKER)) {
                SpeechAudioRouteRequestResult.ACCEPTED -> Unit
                SpeechAudioRouteRequestResult.UNAVAILABLE ->
                    throw LiveVoiceAudioRouteException("realtime_speaker_unavailable")
                SpeechAudioRouteRequestResult.CALL_ACTIVE ->
                    throw LiveVoiceAudioRouteException("realtime_communication_audio_in_use")
                SpeechAudioRouteRequestResult.EXTERNAL_DEVICE,
                SpeechAudioRouteRequestResult.FAILED ->
                    throw LiveVoiceAudioRouteException("realtime_speaker_route_rejected")
            }
            val signal = serial { endpoint.routeSignal() }
            if (!signal.await(ROUTE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw LiveVoiceAudioRouteException("realtime_speaker_route_timeout")
            }
            try {
                serial { endpoint.verifyLiveRoute() }
            } catch (_: RuntimeException) {
                throw LiveVoiceAudioRouteException("realtime_speaker_route_not_effective")
            }
            serial { endpoint.armLiveRoute() }
            return registration
        } catch (error: RuntimeException) {
            runCatching { registration.close() }
            throw error
        }
    }

    private inner class AndroidEndpoint(
        private val track: AudioTrack?,
        private val liveCommunication: Boolean = false,
        private val onLiveRouteLost: () -> Unit = {},
        private val beforeLiveRouteChange: () -> Boolean = { true },
        private val onConfirmedLiveRoute: (SpeechAudioRoute) -> Boolean = { true },
    ) : SpeechAudioRouteEndpoint {
        override val retainsPlaybackPreference: Boolean get() = track != null
        private var closed = false
        private var requiresEarpiece = false
        @Volatile private var routeAck = CountDownLatch(1)
        private val waitCancelled = AtomicBoolean(false)
        private var ownedCommunicationDeviceId: Int? = null
        private var ownedTrackPreference = false
        private val liveRoute = if (liveCommunication) LiveVoiceCommunicationRoutePolicy() else null
        private var liveRouteArmed = false
        private var liveRouteTimeout: Runnable? = null

        private fun devices(): List<AudioDeviceInfo> = if (track == null)
            manager?.availableCommunicationDevices.orEmpty()
        else manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList().orEmpty()

        override fun available(): Set<SpeechAudioRoute> = serial {
            if (closed) emptySet() else devices().map(::routeOf)
                .filter { it == SpeechAudioRoute.SPEAKER || it == SpeechAudioRoute.EARPIECE }.toSet()
        }

        override fun effective(): SpeechAudioRoute = serial { if (closed) SpeechAudioRoute.UNKNOWN else routeOf(
            if (track == null) manager?.communicationDevice else track.routedDevice,
        ) }

        override fun externalDevicePresent(): Boolean = serial { manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.any { it.type in EXTERNAL_TYPES } == true }

        override fun callActive(): Boolean = serial { manager?.mode.let {
            it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_CALL_SCREENING ||
                (track != null && it != AudioManager.MODE_NORMAL) ||
                (track == null && it != AudioManager.MODE_IN_COMMUNICATION)
        } }

        override fun request(route: SpeechAudioRoute): Boolean = serial {
            if (closed || callActive()) return@serial false
            val device = devices().firstOrNull { routeOf(it) == route } ?: return@serial false
            requiresEarpiece = route == SpeechAudioRoute.EARPIECE
            routeAck.countDown()
            routeAck = CountDownLatch(1)
            if (track != null) {
                track.setPreferredDevice(device).also {
                    if (it) {
                        ownedTrackPreference = true
                        acknowledgeRoute()
                    }
                }
            } else {
                // Neutral gain must be verified before our explicit speaker/earpiece switch.
                // The callback must not wait on a transport worker awaiting this routing worker.
                if (liveCommunication && !beforeLiveRouteChange()) return@serial false
                manager?.setCommunicationDevice(device)?.also {
                    if (it) {
                        liveRoute?.requestAccepted(route)
                        ownedCommunicationDeviceId = device.id
                        communicationPreferenceOwner = this
                        acknowledgeRoute()
                        scheduleLiveRouteAcknowledgement()
                    }
                } == true
            }
        }

        override fun observeChanged(callback: () -> Unit): AutoCloseable = serial {
            val deviceListener = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    if (!liveCommunication) relinquishForExternalDevice()
                    routeChanged(callback)
                }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    routeChanged(callback)
                }
            }
            val routingListener = AudioRouting.OnRoutingChangedListener {
                if (!closed) routeChanged(callback)
            }
            val communicationListener = AudioManager.OnCommunicationDeviceChangedListener {
                if (!closed) routeChanged(callback)
            }
            val modeListener = AudioManager.OnModeChangedListener {
                // Telephony/another communication owner may change mode without changing the
                // physical device. Re-prove gain even when the built-in speaker is unchanged.
                if (!closed) routeChanged(callback)
            }
            manager?.registerAudioDeviceCallback(deviceListener, handler)
            try {
                if (track != null) track.addOnRoutingChangedListener(routingListener, handler)
                else manager?.addOnCommunicationDeviceChangedListener(Executor { handler.post(it) }, communicationListener)
                if (liveCommunication) manager?.addOnModeChangedListener(Executor { handler.post(it) }, modeListener)
            } catch (error: RuntimeException) {
                runCatching { manager?.unregisterAudioDeviceCallback(deviceListener) }
                runCatching {
                    if (track != null) track.removeOnRoutingChangedListener(routingListener)
                    else manager?.removeOnCommunicationDeviceChangedListener(communicationListener)
                }
                if (liveCommunication) runCatching { manager?.removeOnModeChangedListener(modeListener) }
                throw error
            }
            AutoCloseable { serial {
                try {
                    manager?.unregisterAudioDeviceCallback(deviceListener)
                } finally {
                    try {
                        if (track != null) track.removeOnRoutingChangedListener(routingListener)
                        else manager?.removeOnCommunicationDeviceChangedListener(communicationListener)
                    } finally {
                        if (liveCommunication) manager?.removeOnModeChangedListener(modeListener)
                    }
                }
            } }
        }

        private fun relinquishForExternalDevice() {
            if (!closed && externalDevicePresent()) releasePreference()
        }

        fun verifyPrivateRoute() {
            check(!closed && !waitCancelled.get()) { "speech_route_closed" }
            if (requiresEarpiece) {
                check(effectiveDevice()?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    "speech_private_route_not_effective"
                }
            }
        }

        fun requiresPrivateWarmup(): Boolean = !closed && requiresEarpiece &&
            effectiveDevice()?.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

        fun routeSignal(): CountDownLatch {
            if (
                closed || waitCancelled.get() || routeIsEffective()
            ) {
                routeAck.countDown()
            }
            return routeAck
        }
        fun cancelWait() { waitCancelled.set(true); routeAck.countDown() }

        fun verifyLiveRoute() {
            check(!closed && !waitCancelled.get() && liveRoute != null && routeIsEffective()) {
                "live_communication_route_not_effective"
            }
            check(updateLiveGain()) { "live_output_gain_unverified" }
        }

        fun armLiveRoute() {
            verifyLiveRoute()
            liveRoute?.routeChanged(effective())
            liveRouteArmed = true
        }

        private fun routeIsEffective(): Boolean = if (liveRoute != null) {
            effective() == liveRoute.requested
        } else {
            !requiresEarpiece || effectiveDevice()?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }

        private fun acknowledgeRoute() {
            if (
                closed || routeIsEffective()
            ) {
                if (!closed && !updateLiveGain()) {
                    failLiveRoute()
                    return
                }
                routeAck.countDown()
                liveRoute?.routeChanged(effective())
                liveRouteTimeout?.let(handler::removeCallbacks)
                liveRouteTimeout = null
            }
        }

        private fun scheduleLiveRouteAcknowledgement() {
            liveRouteTimeout?.let(handler::removeCallbacks)
            liveRouteTimeout = null
            if (!liveRouteArmed || routeIsEffective()) return
            // One bounded request timeout, cancelled by the route callback; no idle polling.
            liveRouteTimeout = Runnable {
                liveRouteTimeout = null
                if (!closed && liveRouteArmed &&
                    liveRoute?.acknowledgementExpired(effective()) == LiveVoiceCommunicationRoutePolicy.Result.LOST
                ) failLiveRoute()
            }.also { handler.postDelayed(it, ROUTE_ACK_TIMEOUT_MS) }
        }

        private fun failLiveRoute() {
            if (!liveRouteArmed || closed) return
            liveRouteArmed = false
            reportRouteFailure()
            onLiveRouteLost()
        }

        private fun effectiveDevice(): AudioDeviceInfo? =
            if (track == null) manager?.communicationDevice else track.routedDevice

        private fun routeChanged(callback: () -> Unit) {
            // OS-initiated changes can only be handled reactively, not intercepted beforehand.
            if (!updateLiveGain()) {
                failLiveRoute()
                return
            }
            acknowledgeRoute()
            callback()
            if (liveRoute?.routeChanged(effective()) == LiveVoiceCommunicationRoutePolicy.Result.LOST) {
                failLiveRoute()
            }
        }

        private fun updateLiveGain(): Boolean {
            if (!liveCommunication || closed) return true
            val actual = effective()
            val confirmed = if (actual == SpeechAudioRoute.SPEAKER &&
                (!routeIsEffective() || callActive())
            ) SpeechAudioRoute.UNKNOWN else actual
            return runCatching { onConfirmedLiveRoute(confirmed) }.getOrDefault(false)
        }

        private fun releasePreference() {
            if (ownedTrackPreference) {
                runCatching { track?.setPreferredDevice(null) }
                ownedTrackPreference = false
            }
            val owned = ownedCommunicationDeviceId
            ownedCommunicationDeviceId = null
            // clearCommunicationDevice removes this application's request, not another app's route.
            // Clear even if telephony now has priority, otherwise our request could survive teardown.
            if (owned != null && communicationPreferenceOwner === this) {
                communicationPreferenceOwner = null
                runCatching { manager?.clearCommunicationDevice() }
            }
        }

        override fun close() { cancelWait(); serial {
            closed = true
            liveRouteArmed = false
            liveRouteTimeout?.let(handler::removeCallbacks)
            liveRouteTimeout = null
            releasePreference()
            routeAck.countDown()
        } }
    }

    private companion object {
        const val ROUTE_ACK_TIMEOUT_MS = 1_500L
        val EXTERNAL_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)
        fun routeOf(device: AudioDeviceInfo?): SpeechAudioRoute = when (device?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> SpeechAudioRoute.SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> SpeechAudioRoute.EARPIECE
            in EXTERNAL_TYPES -> SpeechAudioRoute.EXTERNAL
            else -> SpeechAudioRoute.UNKNOWN
        }
    }
}

/** Verifies private-route enforcement before any speech PCM is handed to Android. */
class SpeechAudioTrackRouteRegistration internal constructor(
    private val registration: AutoCloseable,
    private val requiresWarmup: () -> Boolean,
    private val awaitRoute: () -> Unit,
    private val verify: () -> Unit,
    private val cancelWait: () -> Unit = {},
) : AutoCloseable {
    fun prepareBeforeWrite(writeOnlySilence: () -> Unit) {
        if (requiresWarmup()) { writeOnlySilence(); awaitRoute() }
        verify()
    }
    fun requiresPrivateWarmup() = requiresWarmup()
    fun awaitPrivateRoute() { awaitRoute(); verify() }
    fun verifyBeforeWrite() = verify()
    fun cancelPendingWait() = cancelWait()
    override fun close() { cancelWait(); registration.close() }
}
