package ai.hans.standard.voice.realtime

/** Connection feedback is call-owned, never an Activity/Compose timer. */
interface LiveConnectionTone : AutoCloseable {
    fun start()
    override fun close()
}

/** Independent of the user's mute and the context gate; never restarts after terminal close. */
internal class LiveConnectionAudioGate(
    private val tone: LiveConnectionTone,
    private val recording: (Boolean) -> Unit,
) : AutoCloseable {
    private var started = false
    private var ready = false
    private var closed = false

    @Synchronized
    fun connecting() {
        if (closed || started) return
        recording(false)
        started = true
        tone.start()
    }

    @Synchronized
    fun connected() {
        if (closed || ready) return
        tone.close()
        recording(true)
        ready = true
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { tone.close() } finally { recording(false) }
    }
}
