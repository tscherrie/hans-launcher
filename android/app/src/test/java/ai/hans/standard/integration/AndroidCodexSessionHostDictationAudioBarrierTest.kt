package ai.hans.standard.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCodexSessionHostDictationAudioBarrierTest {
    @Test
    fun acknowledgedStopUsesExactBoundedTimeoutAndAdmitsCapture() {
        var observedTimeout = -1L

        val ready = awaitMicrophoneCaptureAudioSilence(timeoutMillis = 2_000L) { timeout ->
            observedTimeout = timeout
            true
        }

        assertTrue(ready)
        assertEquals(2_000L, observedTimeout)
    }

    @Test
    fun timeoutOrStopFailureCannotAdmitCapture() {
        assertFalse(
            awaitMicrophoneCaptureAudioSilence(timeoutMillis = 2_000L) { false },
        )
        assertFalse(
            awaitMicrophoneCaptureAudioSilence(timeoutMillis = 2_000L) {
                error("dispatcher unavailable")
            },
        )
        assertFalse(
            awaitMicrophoneCaptureAudioSilence(timeoutMillis = 0L) {
                error("must not run")
            },
        )
    }
}
