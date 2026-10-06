package ai.hans.standard.integration

/** Host-owned identity for one admitted notification speech attempt. */
internal data class NotificationSpeechAttempt(
    val announcementId: String,
    val playbackId: String,
    val gateEpoch: Long,
    val deliveryEpoch: Long = gateEpoch,
)

internal data class NotificationSpeechFailureRetryTicket(
    val announcementId: String,
    val sequence: Long,
    val gateEpoch: Long,
    val attemptNumber: Int,
)

internal sealed interface NotificationSpeechFailureDisposition {
    data class Retry(val ticket: NotificationSpeechFailureRetryTicket) :
        NotificationSpeechFailureDisposition

    data class Deferred(val announcementId: String) : NotificationSpeechFailureDisposition
}

/**
 * Mutable notification-speech bookkeeping. Callers serialize access with the host monitor. Keeping
 * the privacy transition here makes its invariants independently deterministic: a global clear
 * advances admission, forgets every deferred retry, and removes the in-flight correlation before
 * the asynchronous player stop can emit a stale completion callback.
 */
internal class NotificationSpeechHostState {
    var inFlight: NotificationSpeechAttempt? = null
    val deferredAnnouncementIds = linkedSetOf<String>()
    var gateEpoch: Long = 0L
        private set
    private var attemptSequence: Long = 0L
    private var failureRetrySequence: Long = 0L
    private val automaticFailureCounts = mutableMapOf<String, Int>()
    private val scheduledFailureRetries =
        mutableMapOf<String, NotificationSpeechFailureRetryTicket>()
    private var physicalStopRequired: Boolean = false
    private var lastResumedGateEpoch: Long = -1L

    fun nextAttemptSequence(): Long {
        attemptSequence += 1
        return attemptSequence
    }

    fun advanceGate() {
        gateEpoch += 1
    }

    fun currentForPlayback(playbackId: String): NotificationSpeechAttempt? =
        inFlight?.takeIf { it.playbackId == playbackId }

    /** Releases the failed transport correlation and, when safe, reserves one delayed retry. */
    fun registerPlaybackFailure(
        playbackId: String,
        retryable: Boolean,
        maximumAutomaticRetries: Int,
    ): NotificationSpeechFailureDisposition? {
        require(maximumAutomaticRetries >= 0)
        val failed = currentForPlayback(playbackId) ?: return null
        inFlight = null
        deferredAnnouncementIds += failed.announcementId
        val attemptNumber = (automaticFailureCounts[failed.announcementId] ?: 0) + 1
        automaticFailureCounts[failed.announcementId] = attemptNumber
        scheduledFailureRetries.remove(failed.announcementId)
        if (!retryable || attemptNumber > maximumAutomaticRetries) {
            return NotificationSpeechFailureDisposition.Deferred(failed.announcementId)
        }
        failureRetrySequence += 1L
        val ticket = NotificationSpeechFailureRetryTicket(
            announcementId = failed.announcementId,
            sequence = failureRetrySequence,
            gateEpoch = gateEpoch,
            attemptNumber = attemptNumber,
        )
        scheduledFailureRetries[failed.announcementId] = ticket
        return NotificationSpeechFailureDisposition.Retry(ticket)
    }

    /** Returns true only when this exact delayed task may make the durable item eligible again. */
    fun releaseFailureRetry(ticket: NotificationSpeechFailureRetryTicket): Boolean {
        if (scheduledFailureRetries[ticket.announcementId] != ticket) return false
        scheduledFailureRetries.remove(ticket.announcementId)
        if (ticket.gateEpoch != gateEpoch || physicalStopRequired) return false
        deferredAnnouncementIds -= ticket.announcementId
        return true
    }

    /** A rejected Android schedule keeps the item deferred until a real recovery signal. */
    fun cancelFailureRetry(ticket: NotificationSpeechFailureRetryTicket) {
        if (scheduledFailureRetries[ticket.announcementId] == ticket) {
            scheduledFailureRetries.remove(ticket.announcementId)
        }
    }

    fun completePlayback(
        attempt: NotificationSpeechAttempt,
        completionPersisted: Boolean,
    ): Boolean {
        if (inFlight != attempt) return false
        inFlight = null
        scheduledFailureRetries.remove(attempt.announcementId)
        automaticFailureCounts.remove(attempt.announcementId)
        if (completionPersisted) {
            deferredAnnouncementIds -= attempt.announcementId
        } else {
            deferredAnnouncementIds += attempt.announcementId
        }
        return true
    }

    /** Explicit recovery signals start a new bounded retry generation. */
    fun resumeDeferred() {
        lastResumedGateEpoch = gateEpoch
        failureRetrySequence += 1L
        scheduledFailureRetries.clear()
        automaticFailureCounts.clear()
        deferredAnnouncementIds.clear()
    }

    /** A queued stop receipt cannot re-defer work after its interaction already resumed. */
    fun dropPlayback(attempt: NotificationSpeechAttempt): Boolean {
        if (inFlight != attempt) return false
        inFlight = null
        if (attempt.gateEpoch < lastResumedGateEpoch) {
            deferredAnnouncementIds -= attempt.announcementId
        } else {
            deferredAnnouncementIds += attempt.announcementId
        }
        return true
    }

    /**
     * Returns true until every playback that existed at a privacy boundary has a physical stop
     * acknowledgement. Clearing the correlation alone is deliberately not treated as proof.
     */
    fun cancelAllForPrivacyPurge(physicalOutputActive: Boolean = false): Boolean {
        advanceGate()
        deferredAnnouncementIds.clear()
        scheduledFailureRetries.clear()
        automaticFailureCounts.clear()
        physicalStopRequired = physicalStopRequired || inFlight != null || physicalOutputActive
        inFlight = null
        return physicalStopRequired
    }

    /**
     * Muting is not a request to catch up later. Forget retry correlation while the Center
     * separately suppresses speech (without deleting notification context or facts).
     * Physical stop remains sticky until the player acknowledges it.
     */
    fun pauseForInaudiblePolicy(physicalOutputActive: Boolean = false): Boolean {
        advanceGate()
        deferredAnnouncementIds.clear()
        scheduledFailureRetries.clear()
        automaticFailureCounts.clear()
        physicalStopRequired = physicalStopRequired || inFlight != null || physicalOutputActive
        inFlight = null
        return physicalStopRequired
    }

    fun acknowledgePhysicalStop() {
        physicalStopRequired = false
    }

    fun requiresPhysicalStop(): Boolean = physicalStopRequired
}

/** Detect a real unmute so its entire preceding silent period can be speech-suppressed. */
internal class NotificationSpeechAudibilityTransitionTracker {
    private var previous: Boolean? = null

    @Synchronized
    fun becameAudible(current: Boolean): Boolean {
        val transitioned = previous == false && current
        previous = current
        return transitioned
    }
}

/** All methods are called under the host monitor; persistence itself runs outside that lock. */
internal class NotificationSpeechAudibilitySuppressionState(initiallyAudible: Boolean) {
    private val transitions = NotificationSpeechAudibilityTransitionTracker()
        .apply { becameAudible(initiallyAudible) }
    private var requestedThrough: Long? = null
    private var reconciling = false

    val blocksSpeech: Boolean get() = reconciling || requestedThrough != null

    fun observe(audible: Boolean, nowEpochMillis: Long) {
        val becameAudible = transitions.becameAudible(audible)
        if (!audible || becameAudible) {
            requestedThrough = maxOf(requestedThrough ?: 0L, nowEpochMillis.coerceAtLeast(0))
        }
    }

    fun beginReconciliation(): Boolean {
        if (reconciling || requestedThrough == null) return false
        reconciling = true
        return true
    }

    /** Taking the last null cutoff and reopening the gate must be one atomic host operation. */
    fun nextCutoffOrFinish(): Long? {
        val cutoff = requestedThrough
        requestedThrough = null
        if (cutoff == null) reconciling = false
        return cutoff
    }

    fun retryAfterFailure(cutoff: Long) {
        requestedThrough = maxOf(requestedThrough ?: 0L, cutoff)
        reconciling = false
    }
}

/**
 * Retries an unacknowledged physical player stop without relying on another Android audio-policy
 * callback. Only one generation may be active. Every delayed task carries that generation, and
 * [close] invalidates all already-posted tasks before they can touch playback state.
 *
 * Exhaustion deliberately leaves the host's sticky physical-stop gate intact. A later real policy
 * event may start another bounded generation, but speech is never re-admitted without an
 * acknowledged stop.
 */
internal class NotificationPhysicalStopRetryController(
    private val requiresPhysicalStop: () -> Boolean,
    private val stopPlaybackAndAwait: () -> Boolean,
    private val acknowledgePlaybackStopped: () -> Unit,
    private val onRecovered: () -> Unit,
    private val scheduleRetry: (delayMillis: Long, task: () -> Unit) -> Boolean,
    private val retryDelayMillis: Long,
    private val maximumAttempts: Int,
) {
    private val lock = Any()
    private var generation = 0L
    private var activeGeneration: Long? = null
    private var attempts = 0
    private var closed = false

    init {
        require(retryDelayMillis >= 0L)
        require(maximumAttempts >= 1)
    }

    /** Starts immediately or coalesces into the already-active bounded generation. */
    fun request() {
        val requestedGeneration = synchronized(lock) {
            if (closed || activeGeneration != null) return
            generation += 1L
            activeGeneration = generation
            attempts = 0
            generation
        }
        attempt(requestedGeneration)
    }

    /** Process/lifecycle fence for delayed work that Android's Handler may still deliver. */
    fun close() {
        synchronized(lock) {
            closed = true
            generation += 1L
            activeGeneration = null
            attempts = 0
        }
    }

    private fun attempt(requestedGeneration: Long) {
        if (!isCurrent(requestedGeneration)) return
        val stopRequired = runCatching(requiresPhysicalStop).getOrDefault(true)
        if (!stopRequired) {
            finish(requestedGeneration)
            return
        }

        val stopped = runCatching(stopPlaybackAndAwait).getOrDefault(false)
        val acknowledged = stopped && runCatching(acknowledgePlaybackStopped).isSuccess
        if (acknowledged) {
            if (finish(requestedGeneration)) runCatching(onRecovered)
            return
        }
        retry(requestedGeneration)
    }

    private fun retry(requestedGeneration: Long) {
        val shouldSchedule = synchronized(lock) {
            if (closed || activeGeneration != requestedGeneration) return
            attempts += 1
            if (attempts >= maximumAttempts) {
                activeGeneration = null
                attempts = 0
                false
            } else {
                true
            }
        }
        if (!shouldSchedule) return

        val accepted = runCatching {
            scheduleRetry(retryDelayMillis) { attempt(requestedGeneration) }
        }.getOrDefault(false)
        if (!accepted) finish(requestedGeneration)
    }

    private fun isCurrent(requestedGeneration: Long): Boolean = synchronized(lock) {
        !closed && activeGeneration == requestedGeneration
    }

    private fun finish(requestedGeneration: Long): Boolean = synchronized(lock) {
        if (closed || activeGeneration != requestedGeneration) return@synchronized false
        activeGeneration = null
        attempts = 0
        true
    }
}

/**
 * The shared privacy fence is already REQUIRED before this boundary runs, so Center reads are
 * fail-closed. Revoke the host correlation and stop physical playback before potentially blocking
 * Center I/O; synchronous stop callbacks are inert because the epoch/correlation changed first.
 */
internal class NotificationSpeechPrivacyClearBoundary(
    private val clearValidatedCenter: () -> Boolean,
    private val cancelHostSpeech: () -> Boolean,
    private val stopPlaybackAndAwait: () -> Boolean,
    private val acknowledgePlaybackStopped: () -> Unit,
) {
    fun clear(): Boolean {
        val stopRequired = cancelHostSpeech()
        val playbackStopped = !stopRequired ||
            runCatching(stopPlaybackAndAwait).getOrDefault(false)
        if (stopRequired && playbackStopped) {
            runCatching(acknowledgePlaybackStopped)
                .getOrElse { return false }
        }
        val centerCleared = runCatching(clearValidatedCenter).getOrDefault(false)
        return playbackStopped && centerCleared
    }
}

/** Update/removal revocation uses the same irreversible-audio ordering as global privacy clear. */
internal fun revokeValidatedNotificationAfterStopping(
    cancelAndStopPlayback: () -> Boolean,
    mutateValidatedCenter: () -> Boolean,
): Boolean {
    val playbackStopped = runCatching(cancelAndStopPlayback).getOrDefault(false)
    val centerMutated = runCatching(mutateValidatedCenter).getOrDefault(false)
    return playbackStopped && centerMutated
}
