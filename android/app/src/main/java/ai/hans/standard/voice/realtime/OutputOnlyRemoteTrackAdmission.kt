package ai.hans.standard.voice.realtime

/**
 * Runs on the RTC callback before any queue/terminal check. New native tracks default to enabled.
 * Do not take the playback-gate/control-worker lock here: another thread can hold it while
 * waiting for this signaling callback. Only the initial native mute and readback happen here;
 * route/gain/readiness admission remains on the control worker.
 */
internal object OutputOnlyRemoteTrackAdmission {
    fun <T> receive(tracks: () -> List<T>, mute: (T) -> Boolean,
        enqueue: (List<T>) -> Unit, onFailure: () -> Unit) {
        val observed = try { tracks().toList() } catch (_: RuntimeException) { onFailure(); return }
        var safe = true
        // Mute all tracks before admitting any, even if an earlier track rejected the mute.
        observed.forEach { track ->
            val muted = try { mute(track) } catch (_: RuntimeException) { false }
            if (!muted) safe = false
        }
        if (safe) enqueue(observed) else onFailure()
    }
}
