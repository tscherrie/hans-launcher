package ai.hans.standard.voice.realtime

import java.util.concurrent.TimeUnit

/** Content-free local playback proof. A deadline alone never authorizes closing a Live call. */
internal class LiveAutoHangupController {
    private var sequence = 0L
    private var candidate: Candidate? = null

    val candidateId: Long? get() = candidate?.id
    val expiresAtNanos: Long? get() = candidate?.let { it.armedAt + MAX_AGE_NANOS }
    val quietDeadlineNanos: Long? get() = candidate?.quietSince?.plus(QUIET_GRACE_NANOS)

    fun arm(ownTranscript: String, nowNanos: Long): Long? {
        cancel()
        if (!LiveVoiceFarewellPolicy.isExplicitFarewell(ownTranscript)) return null
        candidate = Candidate(++sequence, ownTranscript, nowNanos)
        return candidate!!.id
    }

    fun cancel() { candidate = null }

    fun assistantTranscript(fullText: String, observedAtNanos: Long? = null) {
        val active = candidate ?: return
        if (active.assistantStartedAt == null) {
            active.assistantStartedAt = observedAtNanos ?: active.armedAt
            // Audio from a prior answer/background task cannot be credited to a farewell
            // whose transcript only appeared later. Require another active→quiet sequence.
            active.outputHeard = false
            active.quietSince = null
        }
        active.assistantText = fullText
    }

    fun activity(activity: LiveVoiceAudioActivity, nowNanos: Long) {
        val active = candidate ?: return
        val observed = activity.observedAtNanos
        // Epoch fencing is also enforced by the session; these checks reject delayed samples
        // from an earlier monitoring window and impossible future/out-of-order observations.
        if (observed <= active.armedAt || observed > nowNanos) return
        when (activity.direction) {
            LiveVoiceAudioDirection.INPUT -> {
                if (observed <= active.lastInput) {
                    if (activity.speechActive || !activity.reliable) cancel()
                    return
                }
                if (!activity.reliable) { cancel(); return }
                active.lastInput = observed
                if (activity.speechActive) cancel() else active.inputQuietObserved = true
            }
            LiveVoiceAudioDirection.OUTPUT -> {
                if (observed <= active.lastOutput) {
                    if (activity.speechActive || !activity.reliable) cancel()
                    return
                }
                if (!activity.reliable) { cancel(); return }
                active.lastOutput = observed
                val assistantStarted = active.assistantStartedAt ?: return
                if (observed <= assistantStarted) return
                if (activity.speechActive) {
                    active.outputHeard = true
                    active.quietSince = null
                } else if (active.outputHeard && active.quietSince == null) {
                    active.quietSince = observed
                }
            }
        }
    }

    fun mayClose(id: Long, latestOwnTranscript: String, nowNanos: Long): Boolean {
        val active = candidate ?: return false
        val quietSince = active.quietSince ?: return false
        return active.id == id && nowNanos - active.armedAt in 0 until MAX_AGE_NANOS &&
            latestOwnTranscript == active.ownText &&
            LiveVoiceFarewellPolicy.isExplicitFarewell(latestOwnTranscript) &&
            LiveVoiceFarewellPolicy.isExplicitFarewell(active.assistantText) &&
            active.inputQuietObserved && active.outputHeard && nowNanos >= quietSince + QUIET_GRACE_NANOS &&
            // Require real quiet observations spanning the grace, not a timer following one
            // sample. A stopped/broken audio callback stream therefore always fails open.
            active.lastOutput >= quietSince + QUIET_GRACE_NANOS &&
            nowNanos - active.lastOutput in 0..MAX_PROOF_AGE_NANOS &&
            nowNanos - active.lastInput in 0..MAX_PROOF_AGE_NANOS
    }

    private data class Candidate(
        val id: Long,
        val ownText: String,
        val armedAt: Long,
        var assistantText: String = "",
        var assistantStartedAt: Long? = null,
        var outputHeard: Boolean = false,
        var inputQuietObserved: Boolean = false,
        var quietSince: Long? = null,
        var lastInput: Long = Long.MIN_VALUE,
        var lastOutput: Long = Long.MIN_VALUE,
    )

    companion object {
        val QUIET_GRACE_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(1_200)
        val MAX_AGE_NANOS: Long = TimeUnit.SECONDS.toNanos(20)
        val MAX_PROOF_AGE_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(500)
    }
}
