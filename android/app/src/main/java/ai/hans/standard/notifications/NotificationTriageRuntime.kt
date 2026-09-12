package ai.hans.standard.notifications

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Result of one durable state transition. No source notification content is included. */
internal enum class NotificationTriageStepResult {
    IDLE,
    PROGRESSED,
    RETRY_LATER,
    PAUSED_FOR_INTERACTION,
}

/**
 * Synchronous state-machine step kept separate from scheduling so every retry/lease transition can
 * be regression-tested without Android lifecycle timing.
 */
internal class NotificationTriageProcessor(
    private val queue: NotificationTriageQueue,
    private val restrictedExecutor: RestrictedNotificationTriageExecutor,
    private val suggestionSink: UserFacingNotificationSuggestionSink,
    private val processingPermit: () -> Boolean = { true },
) {
    // This processor is confined to the runtime's single scheduled worker. Alternation is only
    // a scheduling preference: leases, budgets and retry deadlines remain durable queue state.
    // A fresh processor permits at most one archive slice before ready classification work.
    private var preferArchiveSlice = true

    fun processOne(): NotificationTriageStepResult {
        if (!processingPermit()) return NotificationTriageStepResult.PAUSED_FOR_INTERACTION
        if (suggestionSink.isReady()) {
            queue.nextCommittedUserDelivery()?.let { delivery ->
                val result = queue.activateCommittedSuggestion(delivery, suggestionSink)
                return if (result.isRetryableUserDelivery()) {
                    NotificationTriageStepResult.RETRY_LATER
                } else {
                    NotificationTriageStepResult.PROGRESSED
                }
            }
            queue.claimNextUserDelivery()?.let { lease ->
                val result = queue.deliverClaimedSuggestion(lease, suggestionSink)
                return if (result.isRetryableUserDelivery()) {
                    NotificationTriageStepResult.RETRY_LATER
                } else {
                    NotificationTriageStepResult.PROGRESSED
                }
            }
        }

        // Per-batch backoff alone is insufficient: with many slow failing batches there may
        // always be another due entry. Give claimable classification the slice after any local
        // archive attempt; already validated user delivery above retains highest priority.
        if (preferArchiveSlice && drainArchiveSlice()) {
            return NotificationTriageStepResult.PROGRESSED
        }
        val work = queue.claimNextRestrictedTriage()
        if (work == null) {
            // Respect the existing debounce, rolling budget and live leases. When none can be
            // claimed, drain another due archive entry without inventing work or an idle poll.
            return if (!preferArchiveSlice && drainArchiveSlice()) {
                NotificationTriageStepResult.PROGRESSED
            } else {
                NotificationTriageStepResult.IDLE
            }
        }
        preferArchiveSlice = true
        if (!processingPermit()) {
            queue.releaseRestrictedTriage(work.receipt.id, work.claimToken)
            return NotificationTriageStepResult.PAUSED_FOR_INTERACTION
        }
        val decision = try {
            restrictedExecutor.triage(work)
        } catch (_: NotificationTriagePreemptedException) {
            // A concurrent removal may already have terminalized the record. release() is
            // deliberately lease-checked and therefore cannot resurrect it.
            queue.releaseRestrictedTriage(work.receipt.id, work.claimToken)
            return NotificationTriageStepResult.PAUSED_FOR_INTERACTION
        } catch (_: Exception) {
            val result = queue.failRestrictedTriage(work.receipt.id, work.claimToken)
            return if (result.isRetryableTriage()) {
                NotificationTriageStepResult.RETRY_LATER
            } else {
                NotificationTriageStepResult.PROGRESSED
            }
        }
        if (!processingPermit()) {
            queue.releaseRestrictedTriage(work.receipt.id, work.claimToken)
            return NotificationTriageStepResult.PAUSED_FOR_INTERACTION
        }
        return when (
            val result = queue.completeRestrictedTriage(
                id = work.receipt.id,
                claimToken = work.claimToken,
                decision = decision,
            )
        ) {
            is TriageCompletionResult.Accepted -> NotificationTriageStepResult.PROGRESSED
            TriageCompletionResult.InvalidDecision -> {
                val failed = queue.failRestrictedTriage(work.receipt.id, work.claimToken)
                if (failed.isRetryableTriage()) {
                    NotificationTriageStepResult.RETRY_LATER
                } else {
                    NotificationTriageStepResult.PROGRESSED
                }
            }
            TriageCompletionResult.MissingOrExpiredLease -> NotificationTriageStepResult.PROGRESSED
        }
    }

    private fun drainArchiveSlice(): Boolean {
        if (queue.drainNextFactOutbox() == NotificationFactOutboxDrainResult.IDLE) return false
        preferArchiveSlice = false
        return true
    }

    private fun TriageCompletionResult.isRetryableTriage(): Boolean =
        (this as? TriageCompletionResult.Accepted)?.receipt?.state ==
            NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE

    private fun UserDeliveryCompletionResult.isRetryableUserDelivery(): Boolean =
        (this as? UserDeliveryCompletionResult.Accepted)?.receipt?.state ==
            NotificationDeliveryState.SUGGESTED_TO_USER ||
            (this as? UserDeliveryCompletionResult.Accepted)?.receipt?.state ==
            NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION
}

/**
 * Event-driven background drain. It has no polling loop: work is woken by intake, sink attachment,
 * an exact lease expiry, or a bounded retry timer after a transient failure.
 */
internal class NotificationTriageRuntime(
    private val queue: NotificationTriageQueue,
    restrictedExecutor: RestrictedNotificationTriageExecutor,
    suggestionSink: UserFacingNotificationSuggestionSink,
    private val processingPermit: () -> Boolean = { true },
    private val scheduler: ScheduledExecutorService = newScheduler(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ownsScheduler: Boolean = true,
) : Closeable {
    private val processor = NotificationTriageProcessor(
        queue,
        restrictedExecutor,
        suggestionSink,
        processingPermit,
    )
    private val closeableExecutor = restrictedExecutor as? Closeable
    private val preemptibleExecutor =
        restrictedExecutor as? PreemptibleRestrictedNotificationTriageExecutor
    private val lock = Any()
    private var closed = false
    private var running = false
    private var requested = false
    private var wakeGeneration = 0L
    private var scheduled: ScheduledFuture<*>? = null
    private var consecutiveFailures = 0
    private var runningCompletion: CountDownLatch? = null

    fun requestDrain() {
        synchronized(lock) {
            if (closed) return
            requested = true
            wakeGeneration += 1
            if (!running) scheduleLocked(delayMillis = 0)
        }
    }

    fun preemptForInteraction() {
        preemptIfRunning()
    }

    fun preemptCancelledWork() {
        preemptIfRunning()
    }

    private fun preemptIfRunning() {
        val shouldPreempt = synchronized(lock) { running && !closed }
        if (shouldPreempt) preemptibleExecutor?.preemptCurrent()
    }

    override fun close() {
        val completion = synchronized(lock) {
            if (closed) return
            closed = true
            requested = false
            scheduled?.cancel(false)
            scheduled = null
            runningCompletion
        }
        preemptibleExecutor?.preemptCurrent()
        if (ownsScheduler) scheduler.shutdownNow()
        runCatching { closeableExecutor?.close() }
        runCatching {
            completion?.await(CLOSE_QUIESCENCE_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * A bounded close is not proof that a slow external executor has stopped. Resource owners
     * use this after close before releasing the archive. An open, currently idle scheduler is
     * deliberately not considered quiescent: another accepted wake could still start work.
     */
    fun awaitQuiescence(timeout: Long, unit: TimeUnit): Boolean {
        require(timeout >= 0)
        val completion = synchronized(lock) {
            if (!closed) return false
            runningCompletion
        }
        return completion?.await(timeout, unit) ?: true
    }

    private fun runDrain() {
        val completion = CountDownLatch(1)
        synchronized(lock) {
            if (closed) return
            scheduled = null
            running = true
            runningCompletion = completion
            requested = false
        }

        var processed = 0
        var retryLater = false
        var pausedForInteraction = false
        try {
            // One durable transition per scheduled slice gives an arriving foreground turn a
            // deterministic opportunity to acquire priority between notification model items.
            when (processor.processOne()) {
                NotificationTriageStepResult.IDLE -> Unit
                NotificationTriageStepResult.PROGRESSED -> processed = 1
                NotificationTriageStepResult.RETRY_LATER -> {
                    processed = 1
                    retryLater = true
                }
                NotificationTriageStepResult.PAUSED_FOR_INTERACTION ->
                    pausedForInteraction = true
            }
        } catch (_: Exception) {
            // Queue writes and source text are deliberately absent from logs. A later wake retries.
            retryLater = true
        } finally {
            try {
                // Queue/Privacy owns callbacks that request a drain or preempt this runtime.
                // Never take those data locks (including processingPermit's gates) while
                // holding our scheduler monitor: doing so also blocks foreground dispatch.
                // Keep running/completion live until every external read has finished so a
                // concurrent close cannot release the queue/archive underneath this slice.
                val followUp = synchronized(lock) {
                    if (closed) null else DrainFollowUpSnapshot(
                        wakeGeneration = wakeGeneration,
                        needsRecovery = !pausedForInteraction && !retryLater &&
                            processed == 0 && !requested,
                    )
                }
                var permittedAfterPause = false
                var recoveryDelayMillis: Long? = null
                var followUpFailed = false
                if (followUp != null) {
                    try {
                        if (pausedForInteraction) {
                            permittedAfterPause = processingPermit()
                        } else if (followUp.needsRecovery) {
                            recoveryDelayMillis = nextRecoveryDelayMillis()
                        }
                    } catch (_: Exception) {
                        followUpFailed = true
                    }
                }
                synchronized(lock) {
                    running = false
                    runningCompletion = null
                    if (!closed) {
                        when {
                            retryLater || followUpFailed -> {
                                consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(16)
                                scheduleLocked(
                                    NotificationTriageRetryPolicy.delayMillis(consecutiveFailures),
                                )
                            }
                            pausedForInteraction -> {
                                consecutiveFailures = 0
                                if (permittedAfterPause || wakeGeneration != followUp?.wakeGeneration) {
                                    // A wake after the external permit snapshot wins over its
                                    // stale false result. A still-busy next slice parks again;
                                    // there is no periodic polling or lost idle transition.
                                    scheduleLocked(0)
                                } else {
                                    // Drop accumulated intake wakes while busy. The integration
                                    // gate emits one exact wake when the complete interactive
                                    // pipeline becomes idle.
                                    requested = false
                                }
                            }
                            requested || processed > 0 -> {
                                consecutiveFailures = 0
                                scheduleLocked(0)
                            }
                            else -> {
                                consecutiveFailures = 0
                                recoveryDelayMillis?.let(::scheduleLocked)
                            }
                        }
                    }
                }
            } finally {
                completion.countDown()
            }
        }
    }

    private fun nextRecoveryDelayMillis(): Long? {
        val recoveryAt = listOfNotNull(
            queue.nextLeaseRecoveryAtEpochMillis(),
            queue.nextRestrictedTriageEligibilityAtEpochMillis(),
            queue.nextFactOutboxRecoveryAtEpochMillis(),
        ).minOrNull() ?: return null
        return (recoveryAt - clock().coerceAtLeast(0)).coerceAtLeast(0)
    }

    private data class DrainFollowUpSnapshot(val wakeGeneration: Long, val needsRecovery: Boolean)

    private fun scheduleLocked(delayMillis: Long) {
        if (closed || running) return
        val current = scheduled
        if (current != null && !current.isDone) {
            if (delayMillis > 0) return
            current.cancel(false)
        }
        try {
            scheduled = scheduler.schedule(::runDrain, delayMillis, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            scheduled = null
        }
    }

    private companion object {
        const val CLOSE_QUIESCENCE_MILLIS = 5_000L

        fun newScheduler(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "hans-notification-triage").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            }
    }
}

internal object NotificationTriageRetryPolicy {
    private const val BASE_DELAY_MILLIS = 5_000L
    private const val MAX_DELAY_MILLIS = 15 * 60 * 1_000L

    fun delayMillis(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 8)
        return (BASE_DELAY_MILLIS shl exponent).coerceAtMost(MAX_DELAY_MILLIS)
    }
}
