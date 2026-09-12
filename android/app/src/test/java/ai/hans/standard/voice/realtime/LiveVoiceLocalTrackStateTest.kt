package ai.hans.standard.voice.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceLocalTrackStateTest {
    @Test
    fun alreadyEnabledInitialTrackIsAcceptedWithoutNativeMutation() {
        var enabled = true
        var setterCalls = 0

        val applied = LiveVoiceLocalTrackState.apply(
            desiredEnabled = true,
            setEnabled = { requested ->
                setterCalls += 1
                val changed = requested != enabled
                enabled = requested
                changed
            },
            readEnabled = { enabled },
        )

        assertTrue(applied)
        assertEquals(0, setterCalls)
    }

    @Test
    fun repeatedMuteAndUnmuteRequestsAreIdempotent() {
        var enabled = true
        val nativeMutations = mutableListOf<Boolean>()

        fun apply(desiredEnabled: Boolean): Boolean = LiveVoiceLocalTrackState.apply(
            desiredEnabled = desiredEnabled,
            setEnabled = { requested ->
                nativeMutations += requested
                val changed = requested != enabled
                enabled = requested
                changed
            },
            readEnabled = { enabled },
        )

        assertTrue(apply(false))
        assertTrue(apply(false))
        assertTrue(apply(true))
        assertTrue(apply(true))
        assertEquals(listOf(false, true), nativeMutations)
    }

    @Test
    fun effectiveStateMismatchStillFailsClosed() {
        val applied = LiveVoiceLocalTrackState.apply(
            desiredEnabled = false,
            setEnabled = { true },
            readEnabled = { true },
        )

        assertFalse(applied)
    }

    @Test
    fun nativeSetterFailureStillFailsClosed() {
        val applied = LiveVoiceLocalTrackState.apply(
            desiredEnabled = false,
            setEnabled = { throw IllegalStateException("native track unavailable") },
            readEnabled = { true },
        )

        assertFalse(applied)
    }
}
