package ai.hans.standard.voice.realtime

/** Shared call controls; ownership of agent delegation stays with the selected backend. */
interface HansLiveVoiceSession : AutoCloseable {
    val snapshot: LiveVoiceSnapshot
    val voiceSessionId: String? get() = null
    fun start()
    fun refreshContext()
    fun setInputMuted(value: Boolean): Boolean
    fun interruptHans()
    fun stop()
}
