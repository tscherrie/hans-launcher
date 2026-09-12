package ai.hans.standard.voice.realtime

import ai.hans.standard.voice.audio.SpeechAudioRoute
import java.util.concurrent.Executors
import kotlin.math.abs

/** Public WebRTC gain, not Android's system volume index. */
internal interface LiveVoiceSpeakerGainTrack {
    fun setGain(value: Double)
    fun readGain(): Double
    fun disable()
}

internal object LiveVoiceSpeakerGainPolicy {
    const val NEUTRAL = 1.0
    const val SPEAKER = 2.0
    fun desired(confirmedRoute: SpeechAudioRoute, speakerStillEffective: Boolean): Double =
        if (confirmedRoute == SpeechAudioRoute.SPEAKER && speakerStillEffective) SPEAKER else NEUTRAL

    fun matches(actual: Double, expected: Double): Boolean = actual.isFinite() && abs(actual - expected) < 0.0001
}

/**
 * A separate sleeping worker avoids routing-worker <-> transport-control deadlock at startup.
 * It owns gain access only; teardown drains it before the PeerConnection disposes remote tracks.
 * No polling, audio samples, system-volume writes, or persistent route/gain preference.
 */
internal class LiveVoiceSpeakerGain(
    private val speakerStillEffective: () -> Boolean,
    private val onVerified: (SpeechAudioRoute, Double, Double) -> Unit = { _, _, _ -> },
    private val onUnsafeGain: () -> Unit = {},
) : AutoCloseable {
    private val worker = LiveVoiceTransportControl(Executors.newSingleThreadExecutor {
        Thread(it, "hans-live-speaker-gain").apply { isDaemon = true }
    })
    private val tracks = linkedMapOf<String, LiveVoiceSpeakerGainTrack>()
    private var confirmedRoute = SpeechAudioRoute.UNKNOWN
    private var audioFocusGranted = true
    private var observedFocusCallback = false
    @Volatile private var closed = false

    fun attach(id: String, track: LiveVoiceSpeakerGainTrack): Boolean = call {
        tracks[id] = track
        apply(track)
    }

    /** Must succeed before a requested Android route mutation is allowed. */
    fun beforeRouteChange(): Boolean = call {
        confirmedRoute = SpeechAudioRoute.UNKNOWN
        applyAll()
    }

    fun routeConfirmed(route: SpeechAudioRoute): Boolean = call {
        confirmedRoute = route
        applyAll()
    }

    /** Route callbacks cannot restore boost while another application owns audio focus. */
    fun audioFocusChanged(granted: Boolean): Boolean = call {
        observedFocusCallback = true
        audioFocusGranted = granted
        applyAll()
    }

    fun beginAudioFocusRequest(): Boolean = call {
        observedFocusCallback = false
        audioFocusGranted = false
        applyAll()
    }

    /** A newer LOSS callback wins even if it races requestAudioFocus's synchronous GRANTED reply. */
    fun confirmInitialAudioFocusGrant(): Boolean = call {
        if (!observedFocusCallback) audioFocusGranted = true
        applyAll()
    }

    private fun call(block: () -> Boolean): Boolean {
        if (closed) return false
        return runCatching { worker.call { if (closed) false else block() } }.getOrDefault(false)
    }

    private fun applyAll(): Boolean {
        var safe = true
        tracks.values.forEach { if (!apply(it)) safe = false }
        return safe
    }

    private fun apply(track: LiveVoiceSpeakerGainTrack): Boolean {
        val requested = LiveVoiceSpeakerGainPolicy.desired(confirmedRoute,
            audioFocusGranted && runCatching { speakerStillEffective() }.getOrDefault(false))
        var actual = requested
        if (!setAndVerify(track, requested)) {
            // Boost is optional. An unavailable boost may continue only at verified neutral.
            if (requested == LiveVoiceSpeakerGainPolicy.NEUTRAL ||
                !setAndVerify(track, LiveVoiceSpeakerGainPolicy.NEUTRAL)
            ) return failSafe(track)
            actual = LiveVoiceSpeakerGainPolicy.NEUTRAL
        }
        // Recheck Android after a native gain write as well. An unexpected OS switch cannot
        // be intercepted in advance; its route callback and this recheck promptly neutralize.
        if (actual > LiveVoiceSpeakerGainPolicy.NEUTRAL &&
            !runCatching { speakerStillEffective() }.getOrDefault(false)
        ) {
            if (!setAndVerify(track, LiveVoiceSpeakerGainPolicy.NEUTRAL)) return failSafe(track)
            actual = LiveVoiceSpeakerGainPolicy.NEUTRAL
        }
        onVerified(confirmedRoute, requested, actual)
        return true
    }

    private fun setAndVerify(track: LiveVoiceSpeakerGainTrack, gain: Double): Boolean = try {
        if (!LiveVoiceSpeakerGainPolicy.matches(track.readGain(), gain)) track.setGain(gain)
        LiveVoiceSpeakerGainPolicy.matches(track.readGain(), gain)
    } catch (_: RuntimeException) { false }

    private fun failSafe(track: LiveVoiceSpeakerGainTrack): Boolean {
        runCatching { track.disable() }
        onUnsafeGain()
        return false
    }

    override fun close() {
        if (closed) return
        worker.call {
            if (!closed) {
                closed = true
                confirmedRoute = SpeechAudioRoute.UNKNOWN
                applyAll()
                tracks.clear()
            }
        }
        worker.close()
    }
}
