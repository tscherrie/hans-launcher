package ai.hans.standard.fastphone.routines.android

import org.junit.Assert.*
import org.junit.Test

class PhoneRoutineDailyJobPolicyTest {
    private val state = PhoneRoutineDailyWorkState(true, 2L, 1_000L, true)
    private val ready = PhoneRoutineDailyEnvironment(true, true, true, true)

    @Test fun enabledDueWorkSchedulesInexactOpportunityWithoutArtificialDelay() {
        val plan = PhoneRoutineDailyJobPolicy.plan(state, ready, 2_000L)
        assertEquals(PhoneRoutineDailyScheduleStatus.SCHEDULED, plan.status)
        assertEquals(2_000L, plan.wakeAtMillis)
        assertTrue(PhoneRoutineDailyJobPolicy.mayRun(state, ready, true, 2_000L))
    }

    @Test fun futureDailyCheckpointRemainsScheduledEvenWithoutNewDataToday() {
        val future = state.copy(nextEligibleAtMillis = 86_400_000L)
        assertEquals(86_400_000L, PhoneRoutineDailyJobPolicy.plan(future, ready, 2_000L).wakeAtMillis)
        assertFalse(PhoneRoutineDailyJobPolicy.mayRun(future, ready, true, 2_000L))
    }

    @Test fun disabledOrBackupBlockedNeverDispatchOrRetainOwnedJob() {
        assertNull(PhoneRoutineDailyJobPolicy.plan(state.copy(enabled = false), ready, 2_000L).wakeAtMillis)
        val backup = ready.copy(backupReady = false)
        assertEquals(PhoneRoutineDailyScheduleStatus.WAITING_FOR_BACKUP,
            PhoneRoutineDailyJobPolicy.plan(state, backup, 2_000L).status)
        assertNull(PhoneRoutineDailyJobPolicy.plan(state, backup, 2_000L).wakeAtMillis)
        assertFalse(PhoneRoutineDailyJobPolicy.mayRun(state, backup, true, 2_000L))
    }

    @Test fun initialUnlockAndForegroundInteractionDeferButDoNotForgetWork() {
        listOf(ready.copy(userStorageUnlocked = false), ready.copy(interactiveIdle = false)).forEach { env ->
            assertEquals(2_000L + PhoneRoutineDailyJobPolicy.DEFER_MILLIS,
                PhoneRoutineDailyJobPolicy.plan(state, env, 2_000L).wakeAtMillis)
            assertFalse(PhoneRoutineDailyJobPolicy.mayRun(state, env, true, 2_000L))
        }
    }

    @Test fun missingLoginWithNoCoreDeadlineGetsBoundedRecoveryOpportunity() {
        val loginMissing = state.copy(authenticationReady = false, nextEligibleAtMillis = null)
        val plan = PhoneRoutineDailyJobPolicy.plan(loginMissing, ready, 2_000L)
        assertEquals(PhoneRoutineDailyScheduleStatus.WAITING_FOR_LOGIN, plan.status)
        assertEquals(2_000L + PhoneRoutineDailyJobPolicy.AUTH_DEFER_MILLIS, plan.wakeAtMillis)
        assertFalse(PhoneRoutineDailyJobPolicy.mayRun(loginMissing, ready, true, 2_000L))
    }

    @Test fun assignedAndDefaultNetworkMustBothBeValidated() {
        assertFalse(PhoneRoutineDailyJobPolicy.mayRun(state, ready, false, 2_000L))
        assertFalse(PhoneRoutineDailyJobPolicy.mayRun(state,
            ready.copy(defaultNetworkValidated = false), true, 2_000L))
        assertEquals(PhoneRoutineDailyScheduleStatus.WAITING_FOR_NETWORK,
            PhoneRoutineDailyJobPolicy.plan(state, ready.copy(defaultNetworkValidated = false), 2_000L).status)
    }

    @Test fun deferralSaturatesWithoutOverflow() {
        assertEquals(Long.MAX_VALUE, PhoneRoutineDailyJobPolicy.plan(state,
            ready.copy(interactiveIdle = false), Long.MAX_VALUE - 1).wakeAtMillis)
    }
}
