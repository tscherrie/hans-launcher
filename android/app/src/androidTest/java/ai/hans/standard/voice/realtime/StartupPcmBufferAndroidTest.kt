package ai.hans.standard.voice.realtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic PCM/control contract only: never opens a microphone, peer, speaker or network. */
@RunWith(AndroidJUnit4::class)
class StartupPcmBufferAndroidTest {
    @Test fun muteDuringStartupPreservesSpeechButNeverAdmitsLaterAmbientAudio() {
        var drains = 0
        val input = StartupPcmBuffer(onDrained = { drains++ })
        (1..4).forEach { assertArrayEquals(frame(0), process(input, it)) }
        assertTrue(input.setCaptureEnabled(false))
        assertFalse(input.acceptsPhysicalFrames)
        assertEquals(40L, input.queuedMillis)
        repeat(5) { assertArrayEquals(frame(0), process(input, 88)) }
        input.setTransmissionEnabled(true)
        repeat(2) { assertArrayEquals(frame(0), process(input, 88)) }
        (1..4).forEach { assertArrayEquals(frame(it), process(input, 88)) }
        assertEquals(0, drains)
        assertTrue(input.hasPendingDrain)
        assertArrayEquals(frame(0), process(input, 88))
        assertEquals(1, drains)
        assertFalse(input.hasPendingDrain)
        assertEquals(4L to 4L, input.counts())
        repeat(5) { assertArrayEquals(frame(0), process(input, 88)) }
        assertEquals(1, drains)
        input.close()
    }

    @Test fun recordingAndSendingStopAfterDrainWhileSpeakerStaysEnabled() {
        val input = StartupPcmBuffer()
        process(input, 7)
        input.setCaptureEnabled(false)
        assertEquals(EarlyCapturePipelineState(false, true, false, false), state(input, ready = false))
        assertEquals(EarlyCapturePipelineState(false, true, true, true), state(input, ready = true))
        input.setTransmissionEnabled(true)
        repeat(4) { process(input, 88) }
        assertEquals(EarlyCapturePipelineState(false, false, false, true), state(input, ready = true))
        assertEquals(EarlyCapturePipelineState(false, false, false, false),
            state(input, ready = true, ended = true))
        input.close()
    }

    @Test fun alreadyMutedEmptyStartupDoesNotNeedAFirstPhysicalFrame() {
        val input = StartupPcmBuffer()
        input.setCaptureEnabled(false)
        assertFalse(input.hasCapturedFrame)
        assertFalse(input.hasPendingDrain)
        assertEquals(EarlyCapturePipelineState(false, false, false, true), state(input, ready = true))
        input.close()
    }

    @Test fun unmuteNeverReplaysAmbientFramesAndCloseDiscardsRemainingPrefix() {
        val input = StartupPcmBuffer()
        process(input, 7)
        input.setCaptureEnabled(false)
        input.setTransmissionEnabled(true)
        repeat(2) { process(input, 88) }
        assertArrayEquals(frame(7), process(input, 88))
        repeat(5) { assertArrayEquals(frame(0), process(input, 88)) }
        input.setCaptureEnabled(true)
        assertArrayEquals(frame(11), process(input, 11))
        input.setTransmissionEnabled(false)
        process(input, 12)
        input.close()
        assertEquals(0L, input.queuedMillis)
        assertFalse(input.hasPendingDrain)
        assertFalse(input.setCaptureEnabled(true))
        assertArrayEquals(frame(0), process(input, 99))
    }

    private fun state(input: StartupPcmBuffer, ready: Boolean, ended: Boolean = false) =
        EarlyCapturePipelinePolicy.resolve(muted = !input.acceptsPhysicalFrames,
            pendingPrefix = input.hasPendingDrain, ready = ready, contextEnabled = true, ended = ended)

    private fun frame(value: Int) = ByteArray(StartupPcmBuffer.FRAME_BYTES) { value.toByte() }
    private fun process(input: StartupPcmBuffer, value: Int): ByteArray =
        ByteBuffer.wrap(frame(value)).also { input.render(it, StartupPcmBuffer.PCM_16_BIT, 1,
            StartupPcmBuffer.SAMPLE_RATE_HZ, StartupPcmBuffer.FRAME_BYTES, value.toLong()) }.array()
}
