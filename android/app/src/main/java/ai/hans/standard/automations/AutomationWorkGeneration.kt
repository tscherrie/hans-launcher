package ai.hans.standard.automations

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class AutomationRuntimeWorkKind {
    TIMER,
    MANUAL_RUN,
    CONNECTIVITY_RESTORED,
    DEFINITION_CHANGED,
    BOOT,
    TIME_ZONE_CHANGED,
    WALL_CLOCK_CHANGED,
    USER_PRESENT,
}

internal data class AutomationRuntimeWorkTicket(
    val kind: AutomationRuntimeWorkKind,
    val generation: Long,
    val observedAt: Instant?,
) {
    init {
        require(generation >= 1)
        require(
            observedAt != null || kind !in OBSERVED_AT_KINDS,
        ) { "Reschedule work requires its durable observation time" }
    }

    private companion object {
        val OBSERVED_AT_KINDS = setOf(
            AutomationRuntimeWorkKind.BOOT,
            AutomationRuntimeWorkKind.TIME_ZONE_CHANGED,
            AutomationRuntimeWorkKind.WALL_CLOCK_CHANGED,
        )
    }
}

/** Durable monotone request/ack boundary used in addition to JobScheduler's coalesced signal. */
internal interface AutomationRuntimeWorkLedger {
    fun record(kind: AutomationRuntimeWorkKind, observedAt: Instant?): AutomationRuntimeWorkTicket
    fun pending(kind: AutomationRuntimeWorkKind): AutomationRuntimeWorkTicket?
    fun allPending(): List<AutomationRuntimeWorkTicket>
    fun acknowledge(ticket: AutomationRuntimeWorkTicket): AutomationRuntimeWorkTicket?
}

/**
 * Coordinates the final worker checkpoint and platform scheduling under one lock. A trigger that
 * arrives while a worker acknowledges its last observed generation either becomes that worker's
 * next generation or sees the worker released and schedules a new job; there is no lost gap.
 */
internal class AutomationRuntimeWorkCoordinator(
    private val ledger: AutomationRuntimeWorkLedger,
) {
    internal sealed interface StartResult {
        data object NoWork : StartResult
        data class Started(
            val registration: Registration,
            val superseded: Registration?,
        ) : StartResult
    }

    internal sealed interface CheckpointResult {
        data class Continue(val ticket: AutomationRuntimeWorkTicket) : CheckpointResult
        data object ReadyToFinish : CheckpointResult
        data object Superseded : CheckpointResult
    }

    internal sealed interface FinalizationResult {
        data class Continue(val ticket: AutomationRuntimeWorkTicket) : FinalizationResult
        data object Finished : FinalizationResult
        data object Superseded : FinalizationResult
    }

    internal enum class RecoveryGuardResult {
        STALE,
        NO_PENDING_WORK,
        ACTIVE_REARMED,
        EXECUTION_SCHEDULED,
        REARMED_EXECUTION_REJECTED,
        REARM_REJECTED,
    }

    internal class Registration internal constructor(
        val jobId: Int,
        initialTicket: AutomationRuntimeWorkTicket,
    ) {
        internal var ticket: AutomationRuntimeWorkTicket = initialTicket
        private val cancelled = AtomicBoolean(false)
        private val cancellation = AtomicReference<(() -> Unit)?>(null)

        fun attachCancellation(listener: () -> Unit) {
            if (!cancellation.compareAndSet(null, listener)) {
                error("Automation work cancellation already attached")
            }
            if (cancelled.get()) runCatching(listener)
        }

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                cancellation.get()?.let { runCatching(it) }
            }
        }
    }

    private val active = linkedMapOf<Int, Registration>()

    @Synchronized
    fun request(
        jobId: Int,
        kind: AutomationRuntimeWorkKind,
        observedAt: Instant?,
        schedulePlatformJob: () -> Boolean,
    ): Boolean {
        // Acceptance is based on the committed generation plus an active owner or a successful
        // schedule call. A pending OS job without our active registration is never trusted as the
        // sole handoff because it may be the worker currently finishing this same ID.
        ledger.record(kind, observedAt)
        if (active.containsKey(jobId)) return true
        return schedulePlatformJob()
    }

    @Synchronized
    fun recoverPending(
        excludedKinds: Set<AutomationRuntimeWorkKind> = emptySet(),
        jobId: (AutomationRuntimeWorkKind) -> Int,
        schedulePlatformJob: (AutomationRuntimeWorkTicket) -> Boolean,
    ): Boolean {
        var accepted = true
        ledger.allPending()
            .filterNot { it.kind in excludedKinds }
            .forEach { ticket ->
                if (!active.containsKey(jobId(ticket.kind)) && !schedulePlatformJob(ticket)) {
                    accepted = false
                }
            }
        return accepted
    }

    @Synchronized
    fun start(jobId: Int, kind: AutomationRuntimeWorkKind): StartResult {
        val ticket = ledger.pending(kind) ?: return StartResult.NoWork
        val registration = Registration(jobId, ticket)
        val previous = active.put(jobId, registration)
        return StartResult.Started(registration, previous)
    }

    /**
     * Establish a process-independent recovery wakeup before every accepted runtime cycle,
     * including a coalesced continuation. A guard protects the latest durable generation, not
     * only the older generation this registration may currently be processing. Installation is
     * atomic with request/finalization; it neither acknowledges work nor grants dispatch rights.
     * Callers must not enter the runtime when this returns false.
     */
    @Synchronized
    fun installRecoveryGuard(
        registration: Registration,
        installGuard: (AutomationRuntimeWorkTicket) -> Boolean,
    ): Boolean {
        if (active[registration.jobId] !== registration) return false
        val ticket = ledger.pending(registration.ticket.kind) ?: return false
        val installed = try {
            installGuard(ticket)
        } catch (_: Exception) {
            false
        }
        // Platform callbacks are normally non-reentrant. Preserve the exact registration even
        // if an injected callback synchronously supersedes or finishes it while installing.
        return installed && active[registration.jobId] === registration &&
            ledger.pending(registration.ticket.kind) != null
    }

    /**
     * A guard restores a platform execution signal for already committed work, never a new user
     * request. The Android adapter validates the owned ID/kind binding and supplies an exact,
     * process-independent schedule-nonce check. An old generation may protect newer coalesced
     * work, but a future generation or superseded schedule must have no scheduling side effects.
     *
     * Secure the next one-shot guard before considering execution. An active registration may
     * be queued behind another cycle, so do not dispatch or cancel it. These callbacks perform
     * only platform scheduling; runtime/user work and cancellation listeners stay outside this
     * monitor. A failed nonce probe propagates instead of claiming the schedule is stale.
     *
     * Only REARM_REJECTED needs a framework retry of the delivered guard. Once a replacement
     * guard exists, retrying the old guard could overwrite it; an execution rejection leaves
     * that replacement intact. No outcome here records or acknowledges a work generation.
     */
    @Synchronized
    fun recoverFromGuard(
        jobId: Int,
        kind: AutomationRuntimeWorkKind,
        generation: Long,
        isCurrentSchedule: () -> Boolean,
        rearmGuard: (AutomationRuntimeWorkTicket) -> Boolean,
        schedulePlatformJob: (AutomationRuntimeWorkTicket) -> Boolean,
    ): RecoveryGuardResult {
        if (!isCurrentSchedule()) return RecoveryGuardResult.STALE
        if (generation < 1) return RecoveryGuardResult.STALE
        val ticket = ledger.pending(kind) ?: return RecoveryGuardResult.NO_PENDING_WORK
        if (generation > ticket.generation) return RecoveryGuardResult.STALE
        if (active[jobId]?.ticket?.kind?.let { it != kind } == true) {
            return RecoveryGuardResult.STALE
        }
        val rearmed = try {
            rearmGuard(ticket)
        } catch (_: Exception) {
            false
        }
        if (!rearmed) return RecoveryGuardResult.REARM_REJECTED

        // Capture again for a synchronous/coalesced request or completion from a test adapter.
        // An already acknowledged ticket must never cause execution; the extra one-shot guard
        // can safely discover that there is no work when it runs.
        val remaining = try {
            ledger.pending(kind)
        } catch (_: Exception) {
            // The next guard is already durable. Never turn a later ledger/read failure into a
            // framework retry of the old guard, which could overwrite that new schedule.
            return RecoveryGuardResult.REARMED_EXECUTION_REJECTED
        } ?: return RecoveryGuardResult.NO_PENDING_WORK
        if (active.containsKey(jobId)) return RecoveryGuardResult.ACTIVE_REARMED
        val scheduled = try {
            schedulePlatformJob(remaining)
        } catch (_: Exception) {
            false
        }
        return if (scheduled) {
            RecoveryGuardResult.EXECUTION_SCHEDULED
        } else {
            RecoveryGuardResult.REARMED_EXECUTION_REJECTED
        }
    }

    /** Called only after a complete, non-yielded cycle whose effects have been checkpointed. */
    @Synchronized
    fun acknowledgeAndRecheck(registration: Registration): CheckpointResult {
        if (active[registration.jobId] !== registration) return CheckpointResult.Superseded
        val next = ledger.acknowledge(registration.ticket)
        if (next != null) {
            registration.ticket = next
            return CheckpointResult.Continue(next)
        }
        // Keep the registration active until JobService performs the final platform finish under
        // [finishIfCaughtUp]. This closes the ack -> jobFinished coalescing window.
        return CheckpointResult.ReadyToFinish
    }

    @Synchronized
    fun finishIfCaughtUp(
        registration: Registration,
        finishPlatformJob: () -> Unit,
    ): FinalizationResult {
        if (active[registration.jobId] !== registration) return FinalizationResult.Superseded
        val late = ledger.pending(registration.ticket.kind)
        if (late != null) {
            registration.ticket = late
            return FinalizationResult.Continue(late)
        }
        // request() shares this lock. It can only observe inactive after jobFinished returned, and
        // therefore must issue a fresh schedule instead of trusting the old running job.
        finishPlatformJob()
        active.remove(registration.jobId)
        return FinalizationResult.Finished
    }

    /** Releases a failed/stopped worker without acknowledging its durable generation. */
    @Synchronized
    fun release(registration: Registration): Boolean =
        active[registration.jobId] === registration &&
            active.remove(registration.jobId, registration)

    /**
     * Preserve pending work while making retry installation/handoff atomic with request(). Install
     * an explicit bounded retry before retiring this worker, when the platform accepts it. If
     * installation fails, retain Android's framework retry instead of dropping the only wakeup.
     * A request after the callback must schedule again instead of coalescing into a finished worker.
     * The caller must cancel local handles/listeners before acquiring this monitor. None of these
     * handoff paths acknowledges the still-pending generation.
     */
    @Synchronized
    fun releaseForRetry(
        registration: Registration,
        installReplacement: () -> Boolean = { false },
        finishPlatformJob: (needsFrameworkRetry: Boolean) -> Unit,
    ): Boolean {
        if (active[registration.jobId] !== registration) return false
        val replacementAccepted = try {
            installReplacement()
        } catch (_: Exception) {
            false
        }
        finishPlatformJob(!replacementAccepted)
        active.remove(registration.jobId)
        return true
    }

    @Synchronized
    fun isActive(jobId: Int): Boolean = active.containsKey(jobId)

    @Synchronized
    fun isCurrent(registration: Registration): Boolean =
        active[registration.jobId] === registration
}

internal fun AutomationRuntimeTrigger.workKind(): AutomationRuntimeWorkKind = when (this) {
    AutomationRuntimeTrigger.Timer -> AutomationRuntimeWorkKind.TIMER
    AutomationRuntimeTrigger.ManualRun -> AutomationRuntimeWorkKind.MANUAL_RUN
    AutomationRuntimeTrigger.ConnectivityRestored ->
        AutomationRuntimeWorkKind.CONNECTIVITY_RESTORED
    AutomationRuntimeTrigger.DefinitionChanged -> AutomationRuntimeWorkKind.DEFINITION_CHANGED
    is AutomationRuntimeTrigger.Boot -> AutomationRuntimeWorkKind.BOOT
    is AutomationRuntimeTrigger.TimeZoneChanged -> AutomationRuntimeWorkKind.TIME_ZONE_CHANGED
    is AutomationRuntimeTrigger.WallClockChanged -> AutomationRuntimeWorkKind.WALL_CLOCK_CHANGED
    AutomationRuntimeTrigger.UserPresent -> AutomationRuntimeWorkKind.USER_PRESENT
}

internal fun AutomationRuntimeTrigger.observedAtOrNull(): Instant? = when (this) {
    is AutomationRuntimeTrigger.Boot -> observedAt
    is AutomationRuntimeTrigger.TimeZoneChanged -> observedAt
    is AutomationRuntimeTrigger.WallClockChanged -> observedAt
    else -> null
}

internal fun AutomationRuntimeWorkTicket.toRuntimeTrigger(): AutomationRuntimeTrigger = when (kind) {
    AutomationRuntimeWorkKind.TIMER -> AutomationRuntimeTrigger.Timer
    AutomationRuntimeWorkKind.MANUAL_RUN -> AutomationRuntimeTrigger.ManualRun
    AutomationRuntimeWorkKind.CONNECTIVITY_RESTORED ->
        AutomationRuntimeTrigger.ConnectivityRestored
    AutomationRuntimeWorkKind.DEFINITION_CHANGED -> AutomationRuntimeTrigger.DefinitionChanged
    AutomationRuntimeWorkKind.BOOT -> AutomationRuntimeTrigger.Boot(checkNotNull(observedAt))
    AutomationRuntimeWorkKind.TIME_ZONE_CHANGED ->
        AutomationRuntimeTrigger.TimeZoneChanged(checkNotNull(observedAt))
    AutomationRuntimeWorkKind.WALL_CLOCK_CHANGED ->
        AutomationRuntimeTrigger.WallClockChanged(checkNotNull(observedAt))
    AutomationRuntimeWorkKind.USER_PRESENT -> AutomationRuntimeTrigger.UserPresent
}
