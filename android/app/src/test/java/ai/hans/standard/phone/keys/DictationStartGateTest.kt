package ai.hans.standard.phone.keys

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationStartGateTest {
    @Test
    fun releasedHoldCancelsActivityThatHasNotStartedMicrophoneYet() {
        val gate = DictationStartGate(timeoutMillis = 1_000)
        val token = gate.prepare(nowMillis = 100)

        assertTrue(gate.hasPending(nowMillis = 101))
        assertTrue(gate.cancelAll())
        assertFalse(gate.consume(token, nowMillis = 102))
    }

    @Test
    fun tokenIsOneShotAndExpiredLaunchCannotStartRecording() {
        val gate = DictationStartGate(timeoutMillis = 1_000)
        val first = gate.prepare(nowMillis = 100)
        assertTrue(gate.consume(first, nowMillis = 200))
        assertFalse(gate.consume(first, nowMillis = 201))

        val second = gate.prepare(nowMillis = 300)
        assertNotEquals(first, second)
        assertFalse(gate.consume(second, nowMillis = 1_300))
        assertFalse(gate.hasPending(nowMillis = 1_301))
    }

    @Test
    fun clockRollbackFailsClosed() {
        val gate = DictationStartGate(timeoutMillis = 1_000)
        val token = gate.prepare(nowMillis = 100)

        assertFalse(gate.consume(token, nowMillis = 99))
    }
}

