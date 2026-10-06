package ai.hans.standard.voice.realtime

/** Token-scoped desired state; its owner serializes access with session creation and stop. */
internal class DictationInputMuteState {
    private var token: Long? = null
    private var muted = false

    fun begin(token: Long) {
        this.token = token
        muted = false
    }

    fun clear() {
        token = null
        muted = false
    }

    fun valueFor(token: Long): Boolean = this.token == token && muted

    /** Null apply is permitted only before the owner has created any capture session. */
    fun toggle(token: Long, apply: ((Boolean) -> Boolean)?): Boolean {
        if (this.token != token) return false
        val next = !muted
        if (apply != null && !runCatching { apply(next) }.getOrDefault(false)) return false
        muted = next
        return true
    }

    /** Apply the pending privacy choice before start can create a microphone. */
    fun applyBeforeStart(token: Long, apply: (Boolean) -> Boolean): Boolean =
        this.token == token && (!muted || apply(true))
}
