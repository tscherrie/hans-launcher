package ai.hans.standard.voice.realtime

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/** Ringback is audible only after its own playing AudioTrack proves the selected route. */
internal class AndroidLiveConnectionTone(
    audioManager: AudioManager?,
    sampleAmplitude: Double = 0.35,
    // Zero-amplitude samples and the real track allow silent on-device route verification.
    onRouteVerified: (AudioTrack) -> Unit = {},
) : LiveConnectionTone {
    private val handler = Handler(Looper.getMainLooper())
    private val tone = RoutedLiveConnectionTone(
        createPlayback = {
            AndroidConnectionTonePlayback.create(
                checkNotNull(audioManager), handler, sampleAmplitude, onRouteVerified,
            )
        },
        scheduleRouteTimeout = { callback ->
            val timeout = Runnable(callback)
            check(handler.postDelayed(timeout, ROUTE_TIMEOUT_MS))
            AutoCloseable { handler.removeCallbacks(timeout) }
        },
    )

    override fun start() = tone.start()
    override fun close() = tone.close()

    private companion object {
        const val ROUTE_TIMEOUT_MS = 1_500L
    }
}

private class AndroidConnectionTonePlayback(
    private val manager: AudioManager,
    private val handler: Handler,
    private val track: AudioTrack,
    private val verified: (AudioTrack) -> Unit,
) : LiveConnectionTonePlayback {
    private var communicationListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null

    override fun selectedRoute(): LiveConnectionToneRoute? = manager.communicationDevice
        ?.takeIf {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }?.asRoute()

    override fun routedRoute(): LiveConnectionToneRoute? = track.routedDevice?.asRoute()

    override fun bindRoute(route: LiveConnectionToneRoute): Boolean {
        val selected = manager.communicationDevice?.takeIf { it.asRoute() == route } ?: return false
        return track.setPreferredDevice(selected)
    }

    override fun setMuted(muted: Boolean) {
        check(track.setVolume(if (muted) 0f else 1f) == AudioTrack.SUCCESS)
    }

    override fun observeRoutes(onChanged: () -> Unit) {
        val outputListener = AudioRouting.OnRoutingChangedListener { onChanged() }
        routingListener = outputListener
        track.addOnRoutingChangedListener(outputListener, handler)
        val selectedListener = AudioManager.OnCommunicationDeviceChangedListener { onChanged() }
        communicationListener = selectedListener
        manager.addOnCommunicationDeviceChangedListener(
            Executor { command -> check(handler.post(command)) }, selectedListener,
        )
    }

    override fun play() = track.play()

    override fun onRouteVerified() {
        LiveVoiceDiagnostics.event("CONNECTION_TONE_ROUTE", "deviceType=${track.routedDevice?.type ?: 0}")
        runCatching { verified(track) }
    }

    override fun close() {
        communicationListener?.let { listener ->
            communicationListener = null
            runCatching { manager.removeOnCommunicationDeviceChangedListener(listener) }
        }
        routingListener?.let { listener ->
            routingListener = null
            runCatching { track.removeOnRoutingChangedListener(listener) }
        }
        try { track.stop() } finally { track.release() }
    }

    companion object {
        fun create(
            manager: AudioManager,
            handler: Handler,
            amplitude: Double,
            onRouteVerified: (AudioTrack) -> Unit,
        ): AndroidConnectionTonePlayback {
            val samples = LiveConnectionRingbackPcm.create(amplitude)
            val output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(LiveConnectionRingbackPcm.SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                .build()
            try {
                check(output.setVolume(0f) == AudioTrack.SUCCESS)
                check(output.write(samples, 0, samples.size) == samples.size)
                check(output.setLoopPoints(0, samples.size, -1) == AudioTrack.SUCCESS)
                return AndroidConnectionTonePlayback(manager, handler, output, onRouteVerified)
            } catch (error: RuntimeException) {
                runCatching { output.release() }
                throw error
            }
        }
    }
}

private fun AudioDeviceInfo.asRoute() = LiveConnectionToneRoute(id, type)
