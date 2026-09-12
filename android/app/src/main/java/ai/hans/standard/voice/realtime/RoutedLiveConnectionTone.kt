package ai.hans.standard.voice.realtime

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Device identity, not a requested route or an optimistic UI preference. */
internal data class LiveConnectionToneRoute(val deviceId: Int, val deviceType: Int)

internal interface LiveConnectionTonePlayback : AutoCloseable {
    /** Null suppresses feedback when no supported built-in communication route is selected. */
    fun selectedRoute(): LiveConnectionToneRoute?
    fun routedRoute(): LiveConnectionToneRoute?
    fun bindRoute(route: LiveConnectionToneRoute): Boolean
    fun setMuted(muted: Boolean)
    fun observeRoutes(onChanged: () -> Unit)
    fun play()
    fun onRouteVerified()
}

/** Optional ringback cannot open capture, claim a route, or make the call fail. */
internal class RoutedLiveConnectionTone(
    private val createPlayback: () -> LiveConnectionTonePlayback,
    private val scheduleRouteTimeout: (() -> Unit) -> AutoCloseable,
) : LiveConnectionTone {
    private var started = false
    private var closed = false
    private var playback: LiveConnectionTonePlayback? = null
    private var selected: LiveConnectionToneRoute? = null
    private var audible = false
    private var routeTimeout: AutoCloseable? = null
    private var timeoutGeneration = 0L

    @Synchronized
    override fun start() {
        if (started || closed) return
        started = true
        suppressFailure {
            val output = createPlayback().also { playback = it }
            output.setMuted(true)
            output.observeRoutes(::routeChanged)
            updateRoute(startPlayback = true)
        }
    }

    @Synchronized
    private fun routeChanged() {
        if (closed || playback == null) return
        suppressFailure { updateRoute() }
    }

    private fun updateRoute(startPlayback: Boolean = false, atDeadline: Boolean = false) {
        val output = playback ?: return
        val desired = output.selectedRoute() ?: return close()
        val selectionChanged = desired != selected
        if (selectionChanged) {
            output.setMuted(true)
            audible = false
            cancelTimeout()
            selected = desired
            check(output.bindRoute(desired)) { "connection_tone_route_rejected" }
        }
        if (startPlayback) output.play()
        // Preferred-device acceptance and AudioManager state alone are never playback proof.
        if (output.routedRoute() == desired && output.selectedRoute() == desired) {
            cancelTimeout()
            if (!audible) {
                output.setMuted(false)
                audible = true
                output.onRouteVerified()
            }
        } else {
            output.setMuted(true)
            audible = false
            // A delayed old deadline must not consume a newly selected route's grace period.
            if (atDeadline && !selectionChanged) close() else awaitRoute()
        }
    }

    private fun awaitRoute() {
        if (routeTimeout != null) return
        val generation = ++timeoutGeneration
        routeTimeout = scheduleRouteTimeout { routeDeadline(generation) }
    }

    @Synchronized
    private fun routeDeadline(generation: Long) {
        if (closed || generation != timeoutGeneration) return
        suppressFailure { updateRoute(atDeadline = true) }
    }

    private fun cancelTimeout() {
        ++timeoutGeneration
        val previous = routeTimeout
        routeTimeout = null
        runCatching { previous?.close() }
    }

    private inline fun suppressFailure(block: () -> Unit) {
        try { block() } catch (_: RuntimeException) { close() }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cancelTimeout()
        val previous = playback
        playback = null
        audible = false
        runCatching { previous?.setMuted(true) }
        runCatching { previous?.close() }
    }
}

/** A short static buffer supplies cadence without timer-driven work or microphone access. */
internal object LiveConnectionRingbackPcm {
    const val SAMPLE_RATE_HZ = 16_000
    const val TONE_FRAMES = SAMPLE_RATE_HZ
    const val LOOP_FRAMES = SAMPLE_RATE_HZ * 4

    fun create(amplitude: Double): ShortArray {
        require(amplitude.isFinite() && amplitude in 0.0..1.0)
        val fadeFrames = SAMPLE_RATE_HZ / 200 // 5 ms avoids a click at either tone edge.
        return ShortArray(LOOP_FRAMES) { frame ->
            if (frame >= TONE_FRAMES) 0 else {
                val envelope = min(1.0, min(frame, TONE_FRAMES - 1 - frame).toDouble() / fadeFrames)
                (sin(2.0 * PI * 425.0 * frame / SAMPLE_RATE_HZ) *
                    Short.MAX_VALUE * amplitude * envelope).toInt().toShort()
            }
        }
    }
}
