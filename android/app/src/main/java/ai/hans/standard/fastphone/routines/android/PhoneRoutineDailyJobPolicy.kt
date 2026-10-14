package ai.hans.standard.fastphone.routines.android

/** A read-only projection of the core's durable daily claim/retry state. */
data class PhoneRoutineDailyWorkState(
    val enabled: Boolean,
    val generation: Long,
    val nextEligibleAtMillis: Long?,
    val authenticationReady: Boolean,
) {
    init {
        require(generation >= 0)
        require(nextEligibleAtMillis == null || nextEligibleAtMillis >= 0)
    }
}

/** No UI-unlock requirement: text-only inference may run after first unlock with a locked screen. */
internal data class PhoneRoutineDailyEnvironment(
    val backupReady: Boolean,
    val userStorageUnlocked: Boolean,
    val interactiveIdle: Boolean,
    val defaultNetworkValidated: Boolean,
)

enum class PhoneRoutineDailyScheduleStatus {
    DISABLED,
    SCHEDULED,
    RUNNING,
    WAITING_FOR_BACKUP,
    WAITING_FOR_FIRST_UNLOCK,
    WAITING_FOR_LOGIN,
    WAITING_FOR_INTERACTION,
    WAITING_FOR_NETWORK,
    SCHEDULING_FAILED,
    CLOSED,
}

data class PhoneRoutineDailySchedulingSnapshot(
    val status: PhoneRoutineDailyScheduleStatus,
    /** True only after JobScheduler accepted, or reports, this precise owned wakeup. */
    val scheduled: Boolean,
    val nextWakeAtMillis: Long?,
)

internal data class PhoneRoutineDailyJobPlan(
    val status: PhoneRoutineDailyScheduleStatus,
    val wakeAtMillis: Long?,
)

internal object PhoneRoutineDailyJobPolicy {
    const val DEFER_MILLIS = 15 * 60 * 1_000L
    const val AUTH_DEFER_MILLIS = 60 * 60 * 1_000L

    fun plan(
        state: PhoneRoutineDailyWorkState,
        environment: PhoneRoutineDailyEnvironment,
        nowMillis: Long,
    ): PhoneRoutineDailyJobPlan {
        require(nowMillis >= 0)
        if (!state.enabled) {
            return PhoneRoutineDailyJobPlan(PhoneRoutineDailyScheduleStatus.DISABLED, null)
        }
        if (!environment.backupReady) {
            return PhoneRoutineDailyJobPlan(PhoneRoutineDailyScheduleStatus.WAITING_FOR_BACKUP, null)
        }
        if (state.nextEligibleAtMillis == null && state.authenticationReady) {
            return PhoneRoutineDailyJobPlan(PhoneRoutineDailyScheduleStatus.DISABLED, null)
        }
        val due = maxOf(state.nextEligibleAtMillis ?: nowMillis, nowMillis)
        val status = when {
            !environment.userStorageUnlocked -> PhoneRoutineDailyScheduleStatus.WAITING_FOR_FIRST_UNLOCK
            !state.authenticationReady -> PhoneRoutineDailyScheduleStatus.WAITING_FOR_LOGIN
            !environment.interactiveIdle -> PhoneRoutineDailyScheduleStatus.WAITING_FOR_INTERACTION
            !environment.defaultNetworkValidated -> PhoneRoutineDailyScheduleStatus.WAITING_FOR_NETWORK
            else -> PhoneRoutineDailyScheduleStatus.SCHEDULED
        }
        val deferral = when (status) {
            PhoneRoutineDailyScheduleStatus.WAITING_FOR_LOGIN -> AUTH_DEFER_MILLIS
            PhoneRoutineDailyScheduleStatus.WAITING_FOR_FIRST_UNLOCK,
            PhoneRoutineDailyScheduleStatus.WAITING_FOR_INTERACTION,
            PhoneRoutineDailyScheduleStatus.WAITING_FOR_NETWORK -> DEFER_MILLIS
            else -> 0L
        }
        val earliest = if (deferral == 0L) due else maxOf(due, saturatedAdd(nowMillis, deferral))
        return PhoneRoutineDailyJobPlan(status, earliest)
    }

    fun mayRun(
        state: PhoneRoutineDailyWorkState,
        environment: PhoneRoutineDailyEnvironment,
        assignedNetworkValidated: Boolean,
        nowMillis: Long,
    ): Boolean = state.enabled && state.nextEligibleAtMillis != null &&
        state.nextEligibleAtMillis <= nowMillis && state.authenticationReady &&
        environment.backupReady && environment.userStorageUnlocked &&
        environment.interactiveIdle && environment.defaultNetworkValidated && assignedNetworkValidated

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
