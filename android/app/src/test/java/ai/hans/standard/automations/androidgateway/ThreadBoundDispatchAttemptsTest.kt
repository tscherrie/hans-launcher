package ai.hans.standard.automations.androidgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadBoundDispatchAttemptsTest {
    @Test
    fun locallyRejectedDispatchGetsAttemptSpecificClientIds() {
        val attempts = ThreadBoundDispatchAttempts()

        val first = attempts.prepare("logical-run-key", "thread-1").prepared()
        val second = attempts.prepare("logical-run-key", "thread-1").prepared()

        assertEquals(1, first.attemptNumber)
        assertEquals(2, second.attemptNumber)
        assertNotEquals(first.clientUserMessageId, second.clientUserMessageId)
        assertTrue(first.clientUserMessageId.startsWith("hans-automation-"))
        assertTrue(first.clientUserMessageId.length <= 256)
    }

    @Test
    fun logicalKeyCannotMoveToAnotherThreadBetweenPreDispatchAttempts() {
        val attempts = ThreadBoundDispatchAttempts()
        attempts.prepare("logical-run-key", "thread-1")

        assertEquals(
            ThreadBoundDispatchAttemptResult.Rejected(
                code = "automation_idempotency_conflict",
                retryable = false,
            ),
            attempts.prepare("logical-run-key", "thread-2"),
        )
    }

    @Test
    fun attemptsAndRetentionAreBounded() {
        val attempts = ThreadBoundDispatchAttempts(
            maximumTrackedKeys = 2,
            maximumAttemptsPerKey = 2,
        )
        attempts.prepare("run-a", "thread-1")
        attempts.prepare("run-a", "thread-1")
        assertEquals(
            ThreadBoundDispatchAttemptResult.Rejected(
                code = "codex_pre_dispatch_attempts_exhausted",
                retryable = false,
            ),
            attempts.prepare("run-a", "thread-1"),
        )

        attempts.prepare("run-b", "thread-1")
        attempts.prepare("run-c", "thread-1")
        assertEquals(2, attempts.trackedKeyCountForTest())
        assertEquals(1, attempts.prepare("run-a", "thread-1").prepared().attemptNumber)
    }

    @Test
    fun acceptedAttemptClearsOnlyPreDispatchBookkeeping() {
        val attempts = ThreadBoundDispatchAttempts()
        attempts.prepare("logical-run-key", "thread-1")
        attempts.prepare("logical-run-key", "thread-1")

        attempts.accepted("logical-run-key")

        assertEquals(1, attempts.prepare("logical-run-key", "thread-1").prepared().attemptNumber)
    }

    private fun ThreadBoundDispatchAttemptResult.prepared(): ThreadBoundDispatchAttemptResult.Prepared =
        this as ThreadBoundDispatchAttemptResult.Prepared
}
