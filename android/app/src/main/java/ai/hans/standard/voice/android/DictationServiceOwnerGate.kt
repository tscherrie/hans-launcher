package ai.hans.standard.voice.android

/**
 * One service instance owns the process-wide dictation publication and audio barrier until its
 * capture and provider cleanup has finished. Android may construct its replacement before then.
 */
internal class DictationServiceOwnerGate {
    private var current: Any? = null

    @Synchronized
    fun acquire(owner: Any): Boolean {
        if (current != null && current !== owner) return false
        current = owner
        return true
    }

    @Synchronized
    fun runIfOwner(owner: Any, action: () -> Unit): Boolean {
        if (current !== owner) return false
        action()
        return true
    }

    @Synchronized
    fun release(owner: Any, beforeRelease: () -> Unit): Boolean {
        if (current !== owner) return false
        try {
            beforeRelease()
        } finally {
            current = null
        }
        return true
    }
}
