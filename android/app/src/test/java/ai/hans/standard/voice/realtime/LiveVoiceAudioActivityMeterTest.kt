package ai.hans.standard.voice.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceAudioActivityMeterTest {
    @Test
    fun outputRequiresSustainedAttackAndQuietBeforeTransitions() {
        val harness = Harness(LiveVoiceAudioDirection.OUTPUT)
        assertTrue(harness.frames(1_000, 3).isEmpty())
        assertEquals(listOf(true), harness.frames(1_000, 1).map { it.speechActive })
        assertTrue(harness.frames(0, 29).isEmpty())
        assertEquals(listOf(false), harness.frames(0, 1).map { it.speechActive })
    }

    @Test
    fun inputIgnoresInitialFarewellTailUntilQuietThenDetectsNewSpeech() {
        val harness = Harness(LiveVoiceAudioDirection.INPUT)
        assertTrue(harness.frames(4_000, 10).isEmpty())
        assertTrue(harness.frames(0, 29).isEmpty())
        assertEquals(listOf(false), harness.frames(0, 1).map { it.speechActive })
        assertTrue(harness.frames(4_000, 3).isEmpty())
        assertEquals(listOf(true), harness.frames(4_000, 1).map { it.speechActive })
    }

    @Test
    fun userContinuationCannotBeHiddenBehindInitialFarewellTail() {
        val harness = Harness(LiveVoiceAudioDirection.INPUT)
        assertTrue(harness.frames(4_000, 19).isEmpty())
        assertFalse(harness.frames(4_000, 1).single().reliable)
        assertTrue(harness.frames(4_000, 100).isEmpty())
        assertTrue(harness.frames(0, 100).isEmpty())
    }

    @Test
    fun ambiguousInitialInputCountsCumulativelyUntilFullQuietIsEstablished() {
        val harness = Harness(LiveVoiceAudioDirection.INPUT)
        // INPUT quiet is below 256, attack begins at512: ambiguous audio also consumes budget.
        assertTrue(harness.frames(300, 10).isEmpty())
        assertTrue(harness.frames(0, 29).isEmpty())
        assertTrue(harness.frames(300, 9).isEmpty())
        assertFalse(harness.frames(300, 1).single().reliable)
        assertTrue(harness.frames(0, 100).isEmpty())
    }

    @Test
    fun quietHeartbeatsRequireActualFramesAndAreBounded() {
        val harness = Harness(LiveVoiceAudioDirection.OUTPUT)
        assertEquals(listOf(false), harness.frames(0, 30).map { it.speechActive })
        assertTrue(harness.frames(0, 24).isEmpty())
        assertEquals(listOf(false), harness.frames(0, 1).map { it.speechActive })
        assertEquals(4, harness.frames(0, 100).size)
    }

    @Test
    fun activeHeartbeatDoesNotForwardEveryPcmFrame() {
        val harness = Harness(LiveVoiceAudioDirection.OUTPUT)
        val events = harness.frames(1_000, 104)
        assertEquals(5, events.size)
        assertTrue(events.all { it.speechActive })
        assertTrue(events.zipWithNext().all { (first, second) ->
            second.observedAtNanos - first.observedAtNanos >= 250_000_000L
        })
    }

    @Test
    fun missingCallbacksNeverCountAsQuietDuration() {
        val harness = Harness(LiveVoiceAudioDirection.OUTPUT)
        harness.frames(1_000, 4)
        assertTrue(harness.frames(0, 20).isEmpty())
        harness.advance(5_000_000_000L)
        assertFalse(harness.frames(0, 1).single().reliable)
        assertTrue(harness.frames(0, 100).isEmpty())
    }

    @Test
    fun unsupportedPcmDoesNotConfirmQuiet() {
        val harness = Harness(LiveVoiceAudioDirection.OUTPUT)
        harness.frames(1_000, 4)
        harness.frames(0, 20)
        assertFalse(harness.observe(pcm(0), format = 4)!!.reliable)
        assertTrue(harness.frames(0, 100).isEmpty())
    }

    @Test
    fun malformedAndOversizedFramesFailOpen() {
        val harness = Harness(LiveVoiceAudioDirection.OUTPUT)
        assertFalse(harness.observe(byteArrayOf(0))!!.reliable)
        harness.meter.reset()
        assertFalse(harness.observe(ByteArray(80_000))!!.reliable)
        harness.meter.reset()
        assertFalse(harness.observe(pcm(1_000), channels = 0)!!.reliable)
        harness.meter.reset()
        assertFalse(harness.observe(pcm(1_000), rate = 0)!!.reliable)
        harness.meter.reset()
        assertFalse(harness.observe(ByteArray(3_202))!!.reliable) // More than 100 ms at 16 kHz.
        harness.meter.reset()
        assertTrue(harness.frames(1_000, 3).isEmpty())
        assertTrue(harness.frames(1_000, 1).single().speechActive)
    }

    @Test
    fun outOfOrderFramesCannotAdvanceAttackOrSilence() {
        val meter = LiveVoiceAudioActivityMeter(LiveVoiceAudioDirection.OUTPUT)
        repeat(3) { index ->
            assertNull(meter.observe(pcm(1_000), 2, 1, 16_000, (index + 1) * 10_000_000L))
        }
        assertFalse(meter.observe(pcm(1_000), 2, 1, 16_000, 20_000_000L)!!.reliable)
        assertNull(meter.observe(pcm(1_000), 2, 1, 16_000, 40_000_000L))
    }

    @Test
    fun invalidFramesCannotBridgeAnAlreadyEstablishedQuietInterval() {
        val harness = Harness(LiveVoiceAudioDirection.OUTPUT)
        harness.frames(1_000, 4)
        assertFalse(harness.frames(0, 30).single().speechActive)
        assertFalse(harness.observe(pcm(0), format = 4)!!.reliable)
        repeat(40) { assertNull(harness.observe(pcm(0), format = 4)) }
        assertTrue(harness.frames(0, 100).isEmpty())
        harness.meter.reset()
        assertTrue(harness.frames(0, 29).isEmpty())
        assertFalse(harness.frames(0, 1).single().speechActive)
    }

    @Test
    fun hysteresisDoesNotTreatIntermediateVolumeAsQuiet() {
        val harness = Harness(LiveVoiceAudioDirection.OUTPUT)
        harness.frames(1_000, 4)
        assertTrue(harness.frames(100, 200).isEmpty()) // Between quiet=64 and attack=128.
        assertTrue(harness.frames(0, 29).isEmpty())
        assertFalse(harness.frames(0, 1).single().speechActive)
    }

    @Test
    fun resetDropsCandidateStateAndRequiresNewInputQuietEvidence() {
        val harness = Harness(LiveVoiceAudioDirection.INPUT)
        harness.frames(0, 30)
        harness.frames(4_000, 4)
        harness.meter.reset()
        assertTrue(harness.frames(4_000, 10).isEmpty())
        assertFalse(harness.frames(0, 30).single().speechActive)
    }

    @Test
    fun signedPcmAndStereoFrameDurationAreHandledCorrectly() {
        val harness = Harness(LiveVoiceAudioDirection.OUTPUT)
        repeat(3) { assertNull(harness.observe(pcm(-32_768, samples = 320), channels = 2)) }
        assertTrue(harness.observe(pcm(-32_768, samples = 320), channels = 2)!!.speechActive)
    }

    private class Harness(direction: LiveVoiceAudioDirection) {
        val meter = LiveVoiceAudioActivityMeter(direction)
        private var now = 1_000_000_000L

        fun frames(level: Int, count: Int): List<LiveVoiceAudioActivity> =
            (0 until count).mapNotNull { observe(pcm(level)) }

        fun observe(
            bytes: ByteArray,
            format: Int = 2,
            channels: Int = 1,
            rate: Int = 16_000,
        ): LiveVoiceAudioActivity? {
            now += 10_000_000L
            return meter.observe(bytes, format, channels, rate, now)
        }

        fun advance(nanos: Long) { now += nanos }
    }

    private companion object {
        fun pcm(level: Int, samples: Int = 160): ByteArray = ByteArray(samples * 2).apply {
            for (index in 0 until samples) {
                this[index * 2] = (level and 0xff).toByte()
                this[index * 2 + 1] = ((level shr 8) and 0xff).toByte()
            }
        }
    }
}
