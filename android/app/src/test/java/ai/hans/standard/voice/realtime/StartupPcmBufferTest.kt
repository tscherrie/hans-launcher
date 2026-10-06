package ai.hans.standard.voice.realtime

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPcmBufferTest {
    @Test fun shortUtteranceCompletedBeforeReadinessRetainsItsEntirePrefix() {
        val input = StartupPcmBuffer()
        val expected = (1..4).map(::frame)
        expected.forEachIndexed { index, pcm ->
            val buffer = ByteBuffer.wrap(pcm.copyOf())
            assertEquals(index.toLong(), render(input, buffer, index.toLong()))
            assertArrayEquals(frame(0), buffer.array())
        }
        assertTrue(input.hasCapturedFrame)
        assertEquals(40L, input.queuedMillis)
        assertTrue(input.setTransmissionEnabled(true))
        repeat(StartupPcmBuffer.ACTIVATION_FRAMES) {
            val output = ByteBuffer.wrap(frame(0))
            render(input, output, 10L + it)
            assertArrayEquals(frame(0), output.array())
        }
        expected.forEachIndexed { index, pcm ->
            val output = ByteBuffer.wrap(frame(0))
            assertEquals(index.toLong(), render(input, output, 100L + index))
            assertArrayEquals(pcm, output.array())
        }
        assertEquals(60L, input.queuedMillis) // Lossless continuous FIFO does not invent catch-up.
        input.close()
    }

    @Test fun concurrentLiveSpeechContinuesInExactOrderWithoutRepeatLossOrTimeCompression() {
        val input = StartupPcmBuffer()
        repeat(8) { render(input, ByteBuffer.wrap(frame(it + 1)), it.toLong()) }
        input.setTransmissionEnabled(true)
        val emitted = mutableListOf<Int>()
        for (index in 8 until 100) {
            val output = ByteBuffer.wrap(frame(index + 1))
            val timestamp = render(input, output, index.toLong())
            if (index >= 8 + StartupPcmBuffer.ACTIVATION_FRAMES) {
                emitted += output.array()[0].toInt()
                assertEquals((emitted.size - 1).toLong(), timestamp)
            }
        }
        assertEquals((1..90).toList(), emitted)
        assertEquals(100L to 90L, input.counts())
        assertEquals(100L, input.queuedMillis)
        input.close()
    }

    @Test fun nativeGateNeverConsumesBeforeConfirmedAndRepeatedEnableDoesNotRestartWarmup() {
        val input = StartupPcmBuffer()
        render(input, ByteBuffer.wrap(frame(8)), 1)
        input.setTransmissionEnabled(true)
        repeat(2) {
            input.setTransmissionEnabled(true)
            assertArrayEquals(frame(0), process(input, it + 10))
        }
        input.setTransmissionEnabled(true)
        assertArrayEquals(frame(8), process(input, 20))
        input.setTransmissionEnabled(false)
        repeat(5) { assertArrayEquals(frame(0), process(input, 30 + it)) }
        assertEquals(1L, input.counts().second)
        input.close()
    }

    @Test fun byteBufferPositionCannotSkipInputPrefixOrAppendToOldTail() {
        val input = StartupPcmBuffer()
        val incoming = ByteBuffer.wrap(frame(7)).apply { position(200); limit(500) }
        render(input, incoming, 1)
        assertEquals(0, incoming.position())
        assertEquals(StartupPcmBuffer.FRAME_BYTES, incoming.limit())
        input.setTransmissionEnabled(true)
        repeat(2) { process(input, 2) }
        assertArrayEquals(frame(7), process(input, 4))
        input.close()
    }

    @Test fun bufferCopiesNativeReusableFrames() {
        val input = StartupPcmBuffer()
        val reused = ByteBuffer.wrap(frame(42))
        render(input, reused, 1)
        reused.array().fill(9)
        input.setTransmissionEnabled(true)
        repeat(2) { process(input, 2) }
        assertArrayEquals(frame(42), process(input, 3))
        input.close()
    }

    @Test fun mutePreservesAcceptedPrefixAndDrainsOnlyAfterNextLocalCallbackBoundary() {
        var drained = 0
        val input = StartupPcmBuffer(onDrained = { drained++ })
        (1..4).forEach { process(input, it) }
        assertTrue(input.setCaptureEnabled(false))
        assertEquals(40L, input.queuedMillis)
        assertFalse(input.acceptsPhysicalFrames)
        assertArrayEquals(frame(0), process(input, 88))
        assertEquals(4L to 0L, input.counts())
        input.setTransmissionEnabled(true)
        repeat(2) { assertArrayEquals(frame(0), process(input, 88)) }
        (1..4).forEach {
            assertArrayEquals(frame(it), process(input, 88))
            assertEquals(0, drained)
        }
        assertTrue(input.hasPendingDrain) // Final copy is not yet an observed JNI-loop boundary.
        assertEquals(10L, input.queuedMillis)
        assertArrayEquals(frame(0), process(input, 88))
        assertEquals(1, drained)
        assertFalse(input.hasPendingDrain)
        assertEquals(4L to 4L, input.counts())
        repeat(5) { assertArrayEquals(frame(0), process(input, 88)) }
        assertEquals(1, drained)
        input.close()
    }

    @Test fun muteBeforeFirstFrameNeedsNoPhysicalCaptureOrDrainToReceiveOutput() {
        var drained = 0
        val input = StartupPcmBuffer(onDrained = { drained++ })
        assertTrue(input.setCaptureEnabled(false))
        assertFalse(input.hasCapturedFrame)
        assertFalse(input.hasPendingDrain)
        input.setTransmissionEnabled(true)
        repeat(5) { assertArrayEquals(frame(0), process(input, 88)) }
        assertEquals(0L to 0L, input.counts())
        assertEquals(0, drained)
        val state = pipeline(input, ready = true)
        assertFalse(state.capturePhysicalFrames)
        assertFalse(state.keepRecorderRunning)
        assertFalse(state.sendNativeInput)
        assertTrue(state.playOutput)
        input.close()
    }

    @Test fun repeatedMuteDoesNotRestartWarmupOrDiscardPrefix() {
        var drained = 0
        val input = StartupPcmBuffer(onDrained = { drained++ })
        process(input, 7)
        input.setCaptureEnabled(false)
        input.setTransmissionEnabled(true)
        repeat(2) {
            input.setCaptureEnabled(false)
            input.setTransmissionEnabled(true)
            assertArrayEquals(frame(0), process(input, 88))
        }
        assertArrayEquals(frame(7), process(input, 88))
        input.setCaptureEnabled(false)
        assertArrayEquals(frame(0), process(input, 88))
        assertEquals(1, drained)
        input.close()
    }

    @Test fun unmuteAfterDrainNeverReplaysMutedAmbientFrames() {
        lateinit var input: StartupPcmBuffer
        input = StartupPcmBuffer(onDrained = { input.setTransmissionEnabled(false) })
        process(input, 7)
        input.setCaptureEnabled(false)
        input.setTransmissionEnabled(true)
        repeat(2) { process(input, 88) }
        assertArrayEquals(frame(7), process(input, 88))
        repeat(5) { assertArrayEquals(frame(0), process(input, 88)) }
        assertTrue(input.setCaptureEnabled(true))
        process(input, 11)
        input.setTransmissionEnabled(true)
        repeat(2) { process(input, 12 + it) }
        assertArrayEquals(frame(11), process(input, 14))
        assertArrayEquals(frame(12), process(input, 15))
        assertArrayEquals(frame(13), process(input, 16))
        input.close()
    }

    @Test fun unmuteBeforeDrainReceiptCancelsStaleStopAndPreservesSequence() {
        var drained = 0
        val input = StartupPcmBuffer(onDrained = { drained++ })
        process(input, 7)
        input.setCaptureEnabled(false)
        input.setTransmissionEnabled(true)
        repeat(2) { process(input, 88) }
        assertArrayEquals(frame(7), process(input, 88))
        assertTrue(input.hasPendingDrain)
        input.setCaptureEnabled(true)
        assertArrayEquals(frame(11), process(input, 11))
        assertEquals(0, drained)
        input.setCaptureEnabled(false) // Preserve this already copied frame's local boundary.
        assertTrue(input.hasPendingDrain)
        assertArrayEquals(frame(0), process(input, 88))
        assertEquals(1, drained)
        input.close()
    }

    @Test fun fullPrefixDoesNotOverflowWhileMutedAmbientSamplesAreDiscarded() {
        val failures = mutableListOf<String>()
        val input = StartupPcmBuffer(maxFrames = 3, onFailure = failures::add)
        (1..3).forEach { process(input, it) }
        input.setCaptureEnabled(false)
        repeat(10) { assertArrayEquals(frame(0), process(input, 88)) }
        input.setTransmissionEnabled(true)
        repeat(2) { assertArrayEquals(frame(0), process(input, 88)) }
        (1..3).forEach { assertArrayEquals(frame(it), process(input, 88)) }
        assertArrayEquals(frame(0), process(input, 88))
        assertTrue(failures.isEmpty())
        assertEquals(3L to 3L, input.counts())
        input.close()
    }

    @Test fun recorderDrainAndNativeSendAreSeparateFromSpeakerOutput() {
        val input = StartupPcmBuffer()
        process(input, 7)
        input.setCaptureEnabled(false)
        assertEquals(EarlyCapturePipelineState(false, true, false, false), pipeline(input, ready = false))
        assertEquals(EarlyCapturePipelineState(false, true, true, true), pipeline(input, ready = true))
        assertEquals(EarlyCapturePipelineState(false, true, false, true),
            pipeline(input, ready = true, contextEnabled = false))
        assertEquals(EarlyCapturePipelineState(false, false, false, false),
            pipeline(input, ready = true, ended = true))
        input.setTransmissionEnabled(true)
        repeat(4) { process(input, 88) }
        assertEquals(EarlyCapturePipelineState(false, false, false, true), pipeline(input, ready = true))
        input.setCaptureEnabled(true)
        assertEquals(EarlyCapturePipelineState(true, true, true, true), pipeline(input, ready = true))
        input.close()
    }

    @Test fun overflowFailsVisiblyOnceAndNeverTruncatesToLatestAudio() {
        val failures = mutableListOf<String>()
        val input = StartupPcmBuffer(maxFrames = 3, onFailure = failures::add)
        repeat(3) { process(input, it + 1) }
        assertEquals(30L, input.queuedMillis)
        assertArrayEquals(frame(0), process(input, 4))
        assertEquals(listOf("action_voice_startup_buffer_full"), failures)
        assertEquals(0L, input.queuedMillis)
        assertFalse(input.setTransmissionEnabled(true))
        assertArrayEquals(frame(0), process(input, 5))
        assertEquals(1, failures.size)
    }

    @Test fun everyUnsupportedPhysicalFormatFailsClosed() {
        listOf(
            listOf(3, 1, 48_000, 960, 960),
            listOf(2, 2, 48_000, 960, 960),
            listOf(2, 1, 24_000, 960, 960),
            listOf(2, 1, 48_000, 0, 960),
            listOf(2, 1, 48_000, 480, 960),
            listOf(2, 1, 48_000, 960, 480),
        ).forEach { (format, channels, rate, read, capacity) ->
            val failures = mutableListOf<String>()
            val input = StartupPcmBuffer(onFailure = failures::add)
            val buffer = ByteBuffer.wrap(ByteArray(capacity) { 12 })
            input.render(buffer, format, channels, rate, read, 1)
            assertEquals(listOf("action_voice_pcm_format_unsupported"), failures)
            assertTrue(buffer.array().all { it == 0.toByte() })
            assertFalse(input.hasCapturedFrame)
            assertFalse(input.setCaptureEnabled(true))
        }
    }

    @Test fun callbackExceptionsCannotEscapeIntoNativeAudioThread() {
        val input = StartupPcmBuffer(onFailure = { error("synthetic observer exception") })
        val buffer = ByteBuffer.wrap(frame(1)).asReadOnlyBuffer()
        assertEquals(10L, render(input, buffer, 10))
        assertFalse(input.setTransmissionEnabled(true))
    }

    @Test fun drainCallbackIsOutsideBufferMonitorAndCannotEscapeIntoNativeAudioThread() {
        lateinit var input: StartupPcmBuffer
        var observedDrainedOutsideMonitor = false
        input = StartupPcmBuffer(onDrained = {
            // A different thread can acquire the monitor while this observer runs.
            val check = java.util.concurrent.FutureTask { input.hasPendingDrain }
            Thread(check).start()
            observedDrainedOutsideMonitor = !check.get(2, java.util.concurrent.TimeUnit.SECONDS)
            error("synthetic drain observer exception")
        })
        process(input, 7)
        input.setCaptureEnabled(false)
        input.setTransmissionEnabled(true)
        repeat(3) { process(input, 88) }
        assertArrayEquals(frame(0), process(input, 88))
        assertTrue(observedDrainedOutsideMonitor)
        assertTrue(input.isOpen)
        input.close()
    }

    @Test fun closeZeroesRetainedDataAndCannotBeReenabled() {
        val input = StartupPcmBuffer()
        process(input, 55)
        input.close()
        input.close()
        assertEquals(0L, input.queuedMillis)
        assertFalse(input.setCaptureEnabled(true))
        assertFalse(input.setTransmissionEnabled(true))
        assertArrayEquals(frame(0), process(input, 2))
    }

    @Test fun freshSessionNeverSeesPreviousSessionsPrefix() {
        StartupPcmBuffer().apply { process(this, 42); close() }
        val fresh = StartupPcmBuffer()
        assertFalse(fresh.hasCapturedFrame)
        fresh.setTransmissionEnabled(true)
        repeat(2) { process(fresh, 1) }
        assertArrayEquals(frame(1), process(fresh, 1))
        fresh.close()
    }

    @Test fun thirtySecondHardBoundHasPredictableMemoryAndNoTimerOrWorker() {
        assertEquals(2_880_000, StartupPcmBuffer.MAX_FRAMES * StartupPcmBuffer.FRAME_BYTES)
        val input = StartupPcmBuffer()
        repeat(3_000) { process(input, 1) }
        assertEquals(30_000L, input.queuedMillis)
        input.close()
    }

    private fun frame(value: Int) = ByteArray(StartupPcmBuffer.FRAME_BYTES) { value.toByte() }
    private fun pipeline(input: StartupPcmBuffer, ready: Boolean, contextEnabled: Boolean = true,
                         ended: Boolean = false) = EarlyCapturePipelinePolicy.resolve(
        muted = !input.acceptsPhysicalFrames, pendingPrefix = input.hasPendingDrain,
        ready = ready, contextEnabled = contextEnabled, ended = ended,
    )
    private fun render(input: StartupPcmBuffer, buffer: ByteBuffer, timestamp: Long) = input.render(
        buffer, StartupPcmBuffer.PCM_16_BIT, 1, StartupPcmBuffer.SAMPLE_RATE_HZ,
        StartupPcmBuffer.FRAME_BYTES, timestamp,
    )
    private fun process(input: StartupPcmBuffer, value: Int): ByteArray =
        ByteBuffer.wrap(frame(value)).also { render(input, it, value.toLong()) }.array()
}
