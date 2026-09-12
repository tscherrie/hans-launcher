package ai.hans.standard.automations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationJobCycleStateTest {
    private val scheduled = AutomationJobScheduleKey(41, "schedule-a")

    @Test
    fun independentlyCreatedSameScheduleKeyMatchesButAnotherRunDoesNot() {
        val state = AutomationJobCycleState(scheduled)
        assertTrue(state.matchesStop(AutomationJobScheduleKey(41, "schedule-a")))
        assertFalse(state.matchesStop(AutomationJobScheduleKey(41, "schedule-b")))
        assertFalse(state.matchesStop(AutomationJobScheduleKey(42, "schedule-a")))
        assertFalse(state.matchesStop(AutomationJobScheduleKey(41, null)))
    }

    @Test
    fun legacyScheduleMatchesOnlyLegacyForTheSameJob() {
        val state = AutomationJobCycleState(AutomationJobScheduleKey(41, null))
        assertTrue(state.matchesStop(AutomationJobScheduleKey(41, null)))
        assertFalse(state.matchesStop(scheduled))
        assertFalse(state.matchesStop(AutomationJobScheduleKey(42, null)))
    }

    @Test
    fun stopCancelsPublishedHandleExactlyOnceAndRejectsLaterCompletion() {
        val state = AutomationJobCycleState(scheduled)
        val epoch = requireNotNull(state.beginCycle())
        val handle = handle()
        var cancellations = 0
        handle.onCancellation { cancellations += 1 }
        state.attachHandle(epoch, handle)

        state.cancel()
        state.cancel()

        assertTrue(handle.isCancelled())
        assertEquals(1, cancellations)
        assertFalse(state.takeCompletion(epoch))
        assertFalse(state.matchesStop(scheduled))
        assertNull(state.beginCycle())
    }

    @Test
    fun stopBeforeHandlePublicationCancelsTheLateHandle() {
        val state = AutomationJobCycleState(scheduled)
        val epoch = requireNotNull(state.beginCycle())
        state.cancel()
        val handle = handle()

        state.attachHandle(epoch, handle)

        assertTrue(handle.isCancelled())
        assertFalse(state.takeCompletion(epoch))
    }

    @Test
    fun synchronousCompletionCannotLeaveALateHandleAttached() {
        val state = AutomationJobCycleState(scheduled)
        val epoch = requireNotNull(state.beginCycle())
        assertTrue(state.takeCompletion(epoch))
        val late = handle()

        state.attachHandle(epoch, late)

        assertTrue(late.isCancelled())
        assertFalse(state.takeCompletion(epoch))
    }

    @Test
    fun completionIsAcceptedOnceAndANewCycleGetsANewEpoch() {
        val state = AutomationJobCycleState(scheduled)
        val first = requireNotNull(state.beginCycle())
        state.attachHandle(first, handle())
        assertTrue(state.takeCompletion(first))
        assertFalse(state.takeCompletion(first))

        val second = requireNotNull(state.beginCycle())

        assertEquals(first + 1L, second)
        assertFalse(state.takeCompletion(first))
        assertTrue(state.takeCompletion(second))
    }

    @Test
    fun oldHandleCannotReplaceOrCancelTheNextCycleHandle() {
        val state = AutomationJobCycleState(scheduled)
        val first = requireNotNull(state.beginCycle())
        assertTrue(state.takeCompletion(first))
        val second = requireNotNull(state.beginCycle())
        val current = handle()
        state.attachHandle(second, current)
        val old = handle()

        state.attachHandle(first, old)

        assertTrue(old.isCancelled())
        assertFalse(current.isCancelled())
        assertFalse(state.takeCompletion(first))
        state.cancel()
        assertTrue(current.isCancelled())
    }

    @Test
    fun cancellationAfterCompletedCycleStillPreventsContinuation() {
        val state = AutomationJobCycleState(scheduled)
        val epoch = requireNotNull(state.beginCycle())
        assertTrue(state.takeCompletion(epoch))

        state.cancel()

        assertNull(state.beginCycle())
        assertFalse(state.takeCompletion(epoch))
    }

    @Test
    fun automaticRetryWithSameScheduleKeyHasIndependentRunState() {
        val old = AutomationJobCycleState(scheduled)
        val oldEpoch = requireNotNull(old.beginCycle())
        old.cancel()
        val retry = AutomationJobCycleState(scheduled)
        val retryEpoch = requireNotNull(retry.beginCycle())
        val retryHandle = handle()
        retry.attachHandle(retryEpoch, retryHandle)

        assertFalse(old.takeCompletion(oldEpoch))
        assertFalse(retryHandle.isCancelled())
        assertTrue(retry.takeCompletion(retryEpoch))
    }

    @Test(expected = IllegalStateException::class)
    fun overlappingCyclesAreRejected() {
        val state = AutomationJobCycleState(scheduled)
        state.beginCycle()
        state.beginCycle()
    }

    @Test(expected = IllegalStateException::class)
    fun secondHandleForOneCycleIsRejected() {
        val state = AutomationJobCycleState(scheduled)
        val epoch = requireNotNull(state.beginCycle())
        state.attachHandle(epoch, handle())
        state.attachHandle(epoch, handle())
    }

    private fun handle(): AutomationRuntimeCycleHandle = AutomationRuntimeCycleHandle(
        monotonicTimeSource = AutomationMonotonicTimeSource { 0L },
        deadlineNanos = 1_000_000_000L,
    )
}
