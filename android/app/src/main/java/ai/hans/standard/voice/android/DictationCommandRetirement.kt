package ai.hans.standard.voice.android

import ai.hans.standard.voice.RecordingId

/**
 * Bounded Android start-command ownership, independent of asynchronous recorder callbacks.
 * A newer START is visible here even while its coordinator work is queued behind old delivery.
 * Android's stopSelf(startId) remains the final fence against stopping an unrelated command.
 */
internal class DictationCommandRetirement(private val maximumEntries: Int = 32) {
    init { require(maximumEntries > 0) }

    private data class Entry(
        var startId: Int? = null,
        var terminal: Boolean = false,
        var delivered: Boolean = false,
        var finalizationSeen: Boolean = false,
        var admission: (() -> Boolean)? = null,
    )

    private val entries = LinkedHashMap<RecordingId, Entry>()
    // Idle has no recordingId. Retain the ordered command's explicit target instead of
    // borrowing whichever recording happens to be newest when the callback arrives.
    private val startupCancellations = LinkedHashSet<RecordingId>()
    private var activeRecording: RecordingId? = null

    @Synchronized
    fun started(recordingId: RecordingId, startId: Int, admission: () -> Boolean) {
        require(startId > 0)
        val entry = entries.getOrPut(recordingId) { Entry() }
        entry.startId = startId
        entry.admission = admission
        // An immediate startup failure may arrive before startRecording() returns its ID.
        if (!entry.terminal) activeRecording = recordingId
        trim()
    }

    @Synchronized
    fun activeRecordingId(): RecordingId? = activeRecording

    @Synchronized
    fun associateCommand(recordingId: RecordingId, startId: Int): Boolean {
        require(startId > 0)
        val entry = entries[recordingId] ?: return false
        if (activeRecording != recordingId || entry.terminal) return false
        entry.startId = startId
        return true
    }

    @Synchronized
    fun terminal(recordingId: RecordingId) {
        entries.getOrPut(recordingId) { Entry() }.terminal = true
        startupCancellations.remove(recordingId)
        if (activeRecording == recordingId) activeRecording = null
        trim()
    }

    @Synchronized
    fun expectStartupCancellation(recordingId: RecordingId): Boolean {
        val entry = entries[recordingId] ?: return false
        if (activeRecording != recordingId || entry.terminal || entry.finalizationSeen) return false
        startupCancellations += recordingId
        return true
    }

    @Synchronized
    fun takeIdleStartupCancellation(): RecordingId? {
        val recordingId = startupCancellations.firstOrNull() ?: return null
        terminal(recordingId)
        return recordingId
    }

    @Synchronized
    fun finalizing(recordingId: RecordingId) {
        entries.getOrPut(recordingId) { Entry() }.finalizationSeen = true
        startupCancellations.remove(recordingId)
        trim()
    }

    @Synchronized
    fun completedWithoutTranscript(recordingId: RecordingId): Boolean =
        entries[recordingId]?.finalizationSeen != true

    /** Returns this recording's original lease, never the lease of a queued replacement. */
    @Synchronized
    fun admission(recordingId: RecordingId): (() -> Boolean)? = entries[recordingId]?.admission

    @Synchronized
    fun claimDelivery(recordingId: RecordingId): Boolean {
        val entry = entries[recordingId] ?: return false
        if (entry.delivered || entry.admission == null) return false
        entry.delivered = true
        return true
    }

    /** Invoke on the main thread after the START callback has returned and bound its ID. */
    @Synchronized
    fun takeRetirementStartId(recordingId: RecordingId): Int? {
        val entry = entries[recordingId] ?: return null
        if (!entry.terminal || entry.startId == null) return null
        entries.remove(recordingId)
        startupCancellations.remove(recordingId)
        return entry.startId
    }

    @Synchronized
    fun clear() {
        activeRecording = null
        entries.clear()
        startupCancellations.clear()
    }

    @Synchronized
    fun entryCount(): Int = entries.size

    private fun trim() {
        while (entries.size > maximumEntries) {
            val oldest = entries.keys.firstOrNull { it != activeRecording } ?: return
            entries.remove(oldest)
            startupCancellations.remove(oldest)
        }
    }
}
