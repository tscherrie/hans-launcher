package ai.hans.standard.voice.audio

import org.junit.Assert.*
import org.junit.Test

class SpeechPlaybackFrameOffsetTest {
    @Test fun standardSpeakerHasNoExtraFrames() {
        val offset = SpeechPlaybackFrameOffset()
        assertEquals(60L, offset.speechFrames(60))
        assertEquals(60, offset.marker(60))
    }
    @Test fun silenceIsExcludedFromSpeechProgressAndAddedToCompletionMarker() {
        val offset = SpeechPlaybackFrameOffset()
        offset.addSilence(2048)
        assertEquals(0L, offset.speechFrames(1024))
        assertEquals(0L, offset.speechFrames(2048))
        assertEquals(25L, offset.speechFrames(2073))
        assertEquals(2073, offset.marker(25))
    }
    @Test fun unsignedPlaybackHeadWrapRemainsMonotonic() {
        val offset = SpeechPlaybackFrameOffset()
        offset.addSilence(8)
        assertEquals(0xffff_fff7L, offset.speechFrames(0xffff_ffffL))
        assertEquals(0xffff_fff8L, offset.speechFrames(0))
        assertEquals(0xffff_fff9L, offset.speechFrames(1))
    }
    @Test fun markerWrapPreservesUnsignedBitsAndRejectsDisabledZero() {
        val offset = SpeechPlaybackFrameOffset()
        offset.addSilence(Int.MAX_VALUE)
        assertEquals(-2, offset.marker(Int.MAX_VALUE))
        offset.addSilence(2)
        assertNull(offset.marker(Int.MAX_VALUE))
        assertEquals(Int.MIN_VALUE + 2, offset.marker(1))
    }
    @Test fun malformedCountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SpeechPlaybackFrameOffset().addSilence(-1) }
        assertThrows(IllegalArgumentException::class.java) { SpeechPlaybackFrameOffset().speechFrames(-1) }
        assertNull(SpeechPlaybackFrameOffset().marker(0))
    }
}
