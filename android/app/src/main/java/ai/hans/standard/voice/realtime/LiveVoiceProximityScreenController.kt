package ai.hans.standard.voice.realtime

import android.content.Context
import android.os.PowerManager
import ai.hans.standard.voice.audio.SpeechAudioRoute
import ai.hans.standard.voice.audio.SpeechAudioRouteState
import java.io.Closeable

/**
 * Owns the public Android proximity screen-off wake lock for one active Live call.
 * The lock exposes no proximity readings and silently degrades on devices without
 * a proximity sensor or a supported proximity wake-lock level.
 */
internal class LiveVoiceProximityScreenController private constructor(
    private val lease: ProximityScreenLease?,
) : Closeable {
    private var held = false
    private var callActive = false
    private var earpieceEffective = false

    @Synchronized
    fun onSnapshot(snapshot: LiveVoiceSnapshot) {
        callActive = snapshot.phase in ACTIVE_CALL_PHASES
        updateLease()
    }

    /** Only Android-confirmed earpiece routing may blank the display; preferences are not proof. */
    @Synchronized
    fun onAudioRoute(route: SpeechAudioRouteState) {
        earpieceEffective = route.active && route.effective == SpeechAudioRoute.EARPIECE
        updateLease()
    }

    private fun updateLease() {
        if (callActive && earpieceEffective) acquire() else releaseLease()
    }

    @Synchronized
    private fun acquire() {
        if (held) return
        val target = lease ?: return
        held = runCatching { target.acquire() }.getOrDefault(false)
    }

    @Synchronized
    fun release() {
        callActive = false
        releaseLease()
    }

    private fun releaseLease() {
        if (!held) return
        val released = runCatching { lease?.release() }.isSuccess
        // Retain the local held state on an exceptional platform release so a terminal
        // snapshot and Service.onDestroy still get independent release attempts.
        if (released) held = false
    }

    override fun close() = release()

    internal fun isHeldForTest(): Boolean = synchronized(this) { held }

    companion object {
        private val ACTIVE_CALL_PHASES = setOf(
            LiveVoicePhase.CONNECTING,
            LiveVoicePhase.CONFIGURING,
            LiveVoicePhase.LISTENING,
            LiveVoicePhase.USER_SPEAKING,
            LiveVoicePhase.HANS_SPEAKING,
            LiveVoicePhase.WAITING_FOR_TASK,
            LiveVoicePhase.RECONNECTING,
        )

        fun create(context: Context): LiveVoiceProximityScreenController {
            val powerManager = context.getSystemService(PowerManager::class.java)
                ?: return LiveVoiceProximityScreenController(null)
            val supported = runCatching {
                powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
            }.getOrDefault(false)
            if (!supported) return LiveVoiceProximityScreenController(null)
            val wakeLock = runCatching {
                powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "ai.hans.standard:live-voice-proximity",
                ).apply { setReferenceCounted(false) }
            }.getOrNull()
            return LiveVoiceProximityScreenController(
                wakeLock?.let(::AndroidProximityScreenLease),
            )
        }

        internal fun forTest(lease: ProximityScreenLease?): LiveVoiceProximityScreenController =
            LiveVoiceProximityScreenController(lease)
    }
}

internal interface ProximityScreenLease {
    /** Returns true only after the platform confirms that the lease is held. */
    fun acquire(): Boolean
    fun release()
}

private class AndroidProximityScreenLease(
    private val wakeLock: PowerManager.WakeLock,
) : ProximityScreenLease {
    override fun acquire(): Boolean {
        if (!wakeLock.isHeld) wakeLock.acquire()
        return wakeLock.isHeld
    }

    override fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }
}
