package ai.hans.standard.voice.realtime

import org.junit.Assert.*
import org.junit.Test

class DictationInputMuteStateTest {
    @Test fun repeatedTapsMuteAndUnmuteTheSameSessionWithoutRequestingStop() {
        val state = DictationInputMuteState()
        val applied = mutableListOf<Boolean>()
        state.begin(10)
        assertFalse(state.valueFor(10))
        repeat(4) { assertTrue(state.toggle(10) { applied += it; true }) }
        assertEquals(listOf(true, false, true, false), applied)
        assertFalse(state.valueFor(10))
    }

    @Test fun muteDuringPendingStartIsAppliedBeforeCaptureCanBeCreated() {
        val state = DictationInputMuteState()
        val events = mutableListOf<String>()
        state.begin(10)
        assertTrue(state.toggle(10, null))
        assertTrue(state.valueFor(10))
        assertTrue(state.applyBeforeStart(10) { events += "mute:$it"; true })
        events += "start"
        assertEquals(listOf("mute:true", "start"), events)
        assertTrue(state.valueFor(10))
    }

    @Test fun twoStartupTapsCancelOnlyTheMuteChoiceNotThePendingStart() {
        val state = DictationInputMuteState()
        state.begin(10)
        assertTrue(state.toggle(10, null))
        assertTrue(state.toggle(10, null))
        assertTrue(state.applyBeforeStart(10) { error("No mute to apply") })
        assertFalse(state.valueFor(10))
    }

    @Test fun rejectedMuteDoesNotInventAConfirmedStateOrInvertTheNextTap() {
        val state = DictationInputMuteState()
        state.begin(10)
        assertFalse(state.toggle(10) { false })
        assertFalse(state.valueFor(10))
        assertTrue(state.toggle(10) { assertTrue(it); true })
        assertTrue(state.valueFor(10))
        assertFalse(state.applyBeforeStart(10) { false })
    }

    @Test fun expiredSessionCannotMuteItsReplacementAndNewSessionDoesNotInheritMute() {
        val state = DictationInputMuteState()
        state.begin(10)
        assertTrue(state.toggle(10, null))
        state.begin(11)
        assertFalse(state.toggle(10) { error("Old call") })
        assertFalse(state.applyBeforeStart(10) { error("Old call") })
        assertFalse(state.valueFor(11))
        state.clear()
        assertFalse(state.toggle(11, null))
    }
}
