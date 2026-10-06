package ai.hans.standard.voice.realtime

/**
 * Serializes route- and media-callback track gates without waiting for the transport worker.
 * A route switch mutes every remote track before Android receives the request. Readiness alone
 * cannot unmute a pending/lost route. The owner disposes native tracks only after close().
 */
internal class OutputOnlyAudioPlaybackGate {
    private data class Track(val setEnabled: (Boolean) -> Boolean, val enabled: () -> Boolean)
    private val tracks = linkedMapOf<String, Track>()
    private var ready = false
    private var routeConfirmed = false
    private var closed = false

    @Synchronized fun attach(id: String, setEnabled: (Boolean) -> Boolean, enabled: () -> Boolean): Boolean {
        val track = Track(setEnabled, enabled)
        if (closed) return LiveVoiceLocalTrackState.apply(false, setEnabled, enabled) && false
        tracks[id] = track
        return apply()
    }

    @Synchronized fun setReady(value: Boolean): Boolean {
        ready = value
        return apply()
    }

    @Synchronized fun setRouteConfirmed(value: Boolean): Boolean {
        routeConfirmed = value
        return apply()
    }

    @Synchronized fun close(): Boolean {
        closed = true
        val muted = apply()
        tracks.clear()
        return muted
    }

    private fun apply(): Boolean {
        val enabled = ready && routeConfirmed && !closed
        var safe = true
        tracks.values.forEach { track ->
            if (!LiveVoiceLocalTrackState.apply(enabled, track.setEnabled, track.enabled)) safe = false
        }
        if (!safe) {
            closed = true
            tracks.values.forEach { track ->
                // Attempt every track even when an earlier native track rejected the mute.
                LiveVoiceLocalTrackState.apply(false, track.setEnabled, track.enabled)
            }
        }
        return safe
    }
}
