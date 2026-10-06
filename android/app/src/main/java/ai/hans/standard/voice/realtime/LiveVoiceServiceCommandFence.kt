package ai.hans.standard.voice.realtime

/**
 * In-process, one-shot ownership of a Live start. An Android intent is only a delivery
 * mechanism, never fresh authority to start a microphone or stop a later call.
 * Active ownership survives a stop request until the media's terminal receipt.
 */
internal class LiveVoiceServiceCommandFence {
    private enum class Phase { PENDING, ACTIVE, STOPPING }
    private data class Owner(val token: Long, var phase: Phase)
    private var sequence = 0L
    private var owner: Owner? = null
    private var pendingStop: Long? = null
    private var stoppedToken: Long? = null

    @Synchronized fun reserveStart(): Long? {
        if (owner != null) return null
        check(sequence < Long.MAX_VALUE)
        val token = ++sequence
        owner = Owner(token, Phase.PENDING)
        pendingStop = null
        stoppedToken = null
        return token
    }

    @Synchronized fun isPending(token: Long): Boolean =
        owner?.let { it.token == token && it.phase == Phase.PENDING } == true

    @Synchronized fun claimStart(token: Long): Boolean {
        val current = owner?.takeIf { it.token == token && it.phase == Phase.PENDING } ?: return false
        current.phase = Phase.ACTIVE
        return true
    }

    @Synchronized fun requestStop(expectedToken: Long? = null): Long? {
        val current = owner?.takeIf { expectedToken == null || it.token == expectedToken } ?: return null
        if (current.phase == Phase.STOPPING) return null
        pendingStop = current.token
        stoppedToken = current.token
        if (current.phase == Phase.PENDING) owner = null else current.phase = Phase.STOPPING
        return current.token
    }

    @Synchronized fun consumeStop(token: Long): Boolean {
        if (token <= 0 || token != sequence || pendingStop != token) return false
        pendingStop = null
        return true
    }

    /** A start/barrier failure cannot release an already running microphone. */
    @Synchronized fun failPendingStart(token: Long): Boolean {
        if (!isPending(token)) return false
        owner = null
        return true
    }

    /** Call only after media cleanup, or when construction failed before any media existed. */
    @Synchronized fun complete(token: Long): Boolean {
        if (owner?.token != token) return false
        owner = null
        return true
    }

    @Synchronized fun isLatest(token: Long): Boolean = token > 0 && token == sequence
    @Synchronized fun wasStopRequested(token: Long): Boolean = stoppedToken == token
    @Synchronized fun isRequestedOrActive(): Boolean = owner != null
    @Synchronized fun currentToken(): Long? = owner?.token

    /** Tests reset ownership, but never recycle a token that a delayed callback may still hold. */
    @Synchronized fun clearForTest() {
        check(sequence < Long.MAX_VALUE)
        sequence++
        owner = null
        pendingStop = null
        stoppedToken = null
    }
}
