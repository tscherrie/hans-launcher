package ai.hans.standard.automations

/**
 * Identity supplied when scheduling, not JobParameters reference identity. Android can parcel
 * start and stop independently. Null is the explicit legacy identity for already queued jobs;
 * framework retries may reuse either key, so each onStartJob still needs its own run object.
 */
internal data class AutomationJobScheduleKey(
    val jobId: Int,
    val schedulingNonce: String?,
)

/**
 * Per-onStartJob state. An epoch belongs to one owner cycle, not to a durable work generation.
 * The Android service additionally checks its current run object and coordinator registration
 * on the main thread before accepting completion. Call cancellation listeners outside our lock.
 */
internal class AutomationJobCycleState(
    val scheduleKey: AutomationJobScheduleKey,
) {
    private var stopped = false
    private var epoch = 0L
    private var awaitingCompletion = false
    private var handle: AutomationRuntimeCycleHandle? = null

    @Synchronized
    fun beginCycle(): Long? {
        if (stopped) return null
        check(!awaitingCompletion) { "automation_job_cycle_already_pending" }
        check(epoch < Long.MAX_VALUE) { "automation_job_cycle_epoch_exhausted" }
        epoch += 1L
        awaitingCompletion = true
        return epoch
    }

    fun attachHandle(cycleEpoch: Long, candidate: AutomationRuntimeCycleHandle) {
        val retained = synchronized(this) {
            if (stopped || !awaitingCompletion || epoch != cycleEpoch) {
                false
            } else {
                check(handle == null) { "automation_job_cycle_handle_already_attached" }
                handle = candidate
                true
            }
        }
        if (!retained) candidate.cancel()
    }

    @Synchronized
    fun takeCompletion(cycleEpoch: Long): Boolean {
        if (stopped || !awaitingCompletion || epoch != cycleEpoch) return false
        awaitingCompletion = false
        handle = null
        return true
    }

    @Synchronized
    fun matchesStop(candidate: AutomationJobScheduleKey): Boolean =
        !stopped && scheduleKey == candidate

    fun cancel() {
        val handleToCancel = synchronized(this) {
            if (stopped) return
            stopped = true
            awaitingCompletion = false
            handle.also { handle = null }
        }
        handleToCancel?.cancel()
    }
}
