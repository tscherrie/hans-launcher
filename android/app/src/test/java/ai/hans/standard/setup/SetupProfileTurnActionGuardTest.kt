package ai.hans.standard.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupProfileTurnActionGuardTest {
    @Test
    fun duplicateTurnIsRejectedWhileRecentAndOldestReceiptIsEventuallyEvicted() {
        val guard = SetupProfileTurnActionGuard()
        val threadId = "thread_bounded_guard"
        val oldest = "turn_0"

        assertTrue(guard.claim(threadId, oldest))
        assertFalse(guard.claim(threadId, oldest))

        for (index in 1..SetupProfileTurnActionGuard.MAX_REMEMBERED_TURNS) {
            assertTrue(guard.claim(threadId, "turn_$index"))
        }

        assertFalse(guard.claim(threadId, "turn_${SetupProfileTurnActionGuard.MAX_REMEMBERED_TURNS}"))
        assertTrue(guard.claim(threadId, oldest))
    }

    @Test
    fun sameTurnIdInDifferentThreadsIsIndependent() {
        val guard = SetupProfileTurnActionGuard()

        assertTrue(guard.claim("thread_a", "turn_shared"))
        assertTrue(guard.claim("thread_b", "turn_shared"))
        assertFalse(guard.claim("thread_a", "turn_shared"))
    }

    @Test
    fun unusedValidationReservationCanBeReleasedWithoutAffectingOtherTurns() {
        val guard = SetupProfileTurnActionGuard()

        assertTrue(guard.claim("thread_a", "turn_retry"))
        assertTrue(guard.claim("thread_a", "turn_other"))
        assertTrue(guard.releaseUnused("thread_a", "turn_retry"))
        assertTrue(guard.claim("thread_a", "turn_retry"))
        assertFalse(guard.claim("thread_a", "turn_other"))
    }
}
