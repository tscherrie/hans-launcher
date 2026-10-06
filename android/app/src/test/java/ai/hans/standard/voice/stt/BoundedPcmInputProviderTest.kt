package ai.hans.standard.voice.stt

import ai.hans.standard.voice.AudioFocusRequestResult
import ai.hans.standard.voice.DictationCaptureStartBarrier
import ai.hans.standard.voice.DictationRecordingConfig
import ai.hans.standard.voice.DictationRecordingCoordinator
import ai.hans.standard.voice.DictationRecordingListener
import ai.hans.standard.voice.FixedPcmChunker
import ai.hans.standard.voice.IncrementalSttProvider
import ai.hans.standard.voice.IncrementalSttSession
import ai.hans.standard.voice.MonotonicClock
import ai.hans.standard.voice.PcmAudioCapture
import ai.hans.standard.voice.PcmAudioCaptureFactory
import ai.hans.standard.voice.PcmAudioChunk
import ai.hans.standard.voice.PcmAudioFormat
import ai.hans.standard.voice.PcmSpeechActivityDetector
import ai.hans.standard.voice.RecordAudioPermissionChecker
import ai.hans.standard.voice.RecordingAudioFocusChange
import ai.hans.standard.voice.RecordingAudioFocusCoordinator
import ai.hans.standard.voice.RecordingDeadlineScheduler
import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingProgressListener
import ai.hans.standard.voice.RecordingState
import ai.hans.standard.voice.RecordingStopReason
import ai.hans.standard.voice.RecordingTaskDispatcher
import ai.hans.standard.voice.ScheduledRecordingDeadline
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Synthetic capture-stop races only; no microphone, credentials, backend or wall-clock waits. */
class BoundedPcmInputProviderTest {
    @Test fun belowTheBudgetAllOrderedPcmAndFinalTailPassThroughUnchanged() {
        val f = Fixture()
        assertTrue(f.submit(bytes = byteArrayOf(1, 2, 3, 4)).isSuccess)
        assertTrue(f.submit(index = 1, final = true, bytes = byteArrayOf(5, 6)).isSuccess)
        assertEquals(listOf(0L, 1L), f.destination.chunks.map { it.index })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), f.destination.chunks[0].copyBytes())
        assertArrayEquals(byteArrayOf(5, 6), f.destination.chunks[1].copyBytes())
        assertTrue(f.destination.chunks.last().isFinal)
        assertTrue(f.limits.isEmpty())
    }

    @Test fun reachingTheExactByteBudgetRequestsCaptureStopOnceButDoesNotFinishOrUpload() {
        val f = Fixture()
        f.submit(bytes = ByteArray(8) { 1 })
        f.submit(index = 1, bytes = ByteArray(4) { 2 })
        assertEquals(listOf(f.id), f.limits)
        assertEquals(0, f.destination.finishCalls)
        assertEquals(0, f.destination.cancelCalls)
        assertFalse(f.destination.chunks.last().isFinal)
    }

    @Test fun crossingTheBudgetRetainsOnlyWholeFramesAndRequestsOneStop() {
        val f = Fixture()
        f.submit(bytes = ByteArray(8) { 1 })
        assertTrue(f.submit(index = 1, bytes = byteArrayOf(2, 3, 4, 5, 6, 7, 8, 9)).isSuccess)
        assertArrayEquals(byteArrayOf(2, 3, 4, 5), f.destination.chunks.last().copyBytes())
        assertEquals(12, f.destination.chunks.sumOf { it.byteCount })
        assertEquals(listOf(f.id), f.limits)
    }

    @Test fun boundedInFlightChunksBeyondTheBudgetAreAcknowledgedWithoutDuplicatingAudio() {
        val f = Fixture()
        f.submit(bytes = ByteArray(8))
        f.submit(index = 1, bytes = ByteArray(4))
        repeat(5) { extra -> assertTrue(f.submit(index = 2L + extra, bytes = ByteArray(8) { 99 }).isSuccess) }
        assertEquals(2, f.destination.chunks.size)
        assertEquals(listOf(f.id), f.limits)
        assertEquals(0, f.destination.finishCalls)
    }

    @Test fun overflowFinalTailStillProducesOneEmptyConsecutiveFinalMarker() {
        val f = Fixture()
        f.submit(bytes = ByteArray(8))
        f.submit(index = 1, bytes = ByteArray(4))
        f.submit(index = 2, bytes = ByteArray(8))
        assertTrue(f.submit(index = 3, final = true, bytes = ByteArray(6)).isSuccess)
        assertEquals(listOf(0L, 1L, 2L), f.destination.chunks.map { it.index })
        val marker = f.destination.chunks.last()
        assertTrue(marker.isFinal)
        assertEquals(0, marker.byteCount)
        assertEquals(listOf(f.id), f.limits)
        f.session.finish {}
        assertEquals(1, f.destination.finishCalls)
    }

    @Test fun finalChunkThatCrossesTheBudgetKeepsItsAllowedPrefixAndFinalFlag() {
        val f = Fixture()
        f.submit(bytes = ByteArray(8) { 1 })
        f.submit(index = 1, final = true, bytes = ByteArray(8) { 2 })
        val final = f.destination.chunks.last()
        assertEquals(4, final.byteCount)
        assertArrayEquals(ByteArray(4) { 2 }, final.copyBytes())
        assertTrue(final.isFinal)
        f.session.finish {}
        assertEquals(1, f.destination.finishCalls)
    }

    @Test fun finalMarkerCannotBeOvertakenByAnUnacknowledgedForwardedChunk() {
        val f = Fixture()
        f.destination.autoAck = false
        var accepted = 0
        f.session.submitChunk(chunk(f.id, 0, false, ByteArray(8))) { accepted++ }
        f.session.submitChunk(chunk(f.id, 1, true, ByteArray(4))) { accepted++ }
        assertEquals(0, accepted)
        val results = mutableListOf<Result<String>>()
        f.session.finish(results::add)
        assertFailure(results.single(), "invalid_audio")
        assertEquals(0, f.destination.finishCalls)
        assertEquals(1, f.destination.cancelCalls)
    }

    @Test fun invalidIdOrderFrameAndOversizedChunksDoNotConsumeTheNextIndexOrByteBudget() {
        val f = Fixture()
        assertFailure(f.submit(chunk(RecordingId(2), 0, false, ByteArray(8))), "invalid_audio")
        assertFailure(f.submit(index = 1), "invalid_audio")
        assertFailure(f.submit(bytes = ByteArray(3)), "invalid_audio")
        assertFailure(f.submit(bytes = ByteArray(10)), "invalid_audio")
        assertTrue(f.submit(bytes = ByteArray(8)).isSuccess)
        assertEquals(1, f.destination.chunks.size)
        assertTrue(f.limits.isEmpty())
    }

    @Test fun malformedChunksAreRejectedEvenAfterTheBudgetIsReached() {
        val f = Fixture()
        f.submit(bytes = ByteArray(8))
        f.submit(index = 1, bytes = ByteArray(4))
        assertFailure(f.submit(index = 2, bytes = ByteArray(10)), "invalid_audio")
        assertFailure(f.submit(index = 2, bytes = ByteArray(3)), "invalid_audio")
        assertFailure(f.submit(index = 3), "invalid_audio")
        assertTrue(f.submit(index = 2, final = true, bytes = ByteArray(4)).isSuccess)
    }

    @Test fun afterFinalizationNoAdditionalInputOrRepeatedFinalMarkerIsAccepted() {
        val f = Fixture()
        f.submit(final = true)
        assertFailure(f.submit(index = 1, final = true), "invalid_audio")
        f.session.finish {}
        assertFailure(f.submit(index = 1), "invalid_audio")
    }

    @Test fun duplicateDelegateAcknowledgementsCannotReplayInputOrCompleteTwice() {
        val f = Fixture()
        f.destination.autoAck = false
        var count = 0
        f.session.submitChunk(chunk(f.id, 0, true, ByteArray(8))) { count++ }
        val ack = f.destination.acks.single()
        ack(Result.success(Unit))
        ack(Result.success(Unit))
        ack(Result.failure(IllegalStateException("private detail")))
        assertEquals(1, count)
        val results = mutableListOf<Result<String>>()
        f.session.finish(results::add)
        f.session.finish(results::add)
        f.destination.finishCallback!!(Result.success("Once"))
        f.destination.finishCallback!!(Result.success("Twice"))
        assertEquals("Once", results.single().getOrThrow())
        assertEquals(1, f.destination.finishCalls)
    }

    @Test fun cancellationIsOnceOnlyAndSuppressesLateChunkAndFinishCallbacks() {
        val f = Fixture()
        f.destination.autoAck = false
        var acks = 0
        f.session.submitChunk(chunk(f.id, 0, true, ByteArray(8))) { acks++ }
        f.session.cancel()
        f.session.cancel()
        f.destination.acks.single()(Result.success(Unit))
        f.session.finish { error("cancelled recording must not deliver") }
        assertEquals(0, acks)
        assertEquals(1, f.destination.cancelCalls)
        assertEquals(0, f.destination.finishCalls)
    }

    @Test fun delegateFailureIsSafeAndCannotLeaveTheBusyGatePermanentlyHeld() {
        val f = Fixture()
        f.destination.autoAck = false
        val results = mutableListOf<Result<Unit>>()
        f.session.submitChunk(chunk(f.id, 0, false, ByteArray(8)), results::add)
        f.destination.acks.single()(Result.failure(IllegalStateException("private response")))
        assertFailure(results.single(), "unavailable")
        assertEquals(1, f.destination.cancelCalls)
        f.wrapper.openSession(RecordingId(2), FORMAT).cancel()
    }

    @Test fun finishWithoutFinalInputFailsLocallyAndCancelsTheDelegate() {
        val f = Fixture()
        f.submit()
        val results = mutableListOf<Result<String>>()
        f.session.finish(results::add)
        assertFailure(results.single(), "invalid_audio")
        assertEquals(0, f.destination.finishCalls)
        assertEquals(1, f.destination.cancelCalls)
    }

    @Test fun twoRecordingsGetIndependentBudgetsAndLimitNotifications() {
        val f = Fixture()
        f.submit(bytes = ByteArray(8))
        f.submit(index = 1, final = true, bytes = ByteArray(4))
        f.session.finish {}
        f.destination.finishCallback!!(Result.success("First"))
        val secondId = RecordingId(2)
        val second = f.wrapper.openSession(secondId, FORMAT)
        second.submitChunk(chunk(secondId, 0, false, ByteArray(8))) { assertTrue(it.isSuccess) }
        second.submitChunk(chunk(secondId, 1, true, ByteArray(4))) { assertTrue(it.isSuccess) }
        assertEquals(listOf(f.id, secondId), f.limits)
        assertEquals(12, f.delegate.sessions[1].chunks.sumOf { it.byteCount })
    }

    @Test fun aThrowingLimitCallbackCannotDiscardAnAcceptedRecording() {
        val delegate = FakeProvider()
        val wrapper = BoundedPcmInputProvider(delegate, 8, 8) { error("host stop notification failed") }
        val session = wrapper.openSession(RecordingId(1), FORMAT)
        session.submitChunk(chunk(RecordingId(1), 0, true, ByteArray(8))) { assertTrue(it.isSuccess) }
        val results = mutableListOf<Result<String>>()
        session.finish(results::add)
        delegate.sessions.single().finishCallback!!(Result.success("Retained"))
        assertEquals("Retained", results.single().getOrThrow())
    }

    @Test fun providerRejectsConcurrentOpenAndFrameMisalignedBudgetsBeforeOpeningDelegate() {
        val f = Fixture()
        expectThrows<IllegalStateException> { f.wrapper.openSession(RecordingId(2), FORMAT) }
        val delegate = FakeProvider()
        expectThrows<IllegalArgumentException> {
            BoundedPcmInputProvider(delegate, 11, 8).openSession(RecordingId(1), FORMAT)
        }
        expectThrows<IllegalArgumentException> {
            BoundedPcmInputProvider(delegate, 12, 7).openSession(RecordingId(1), FORMAT)
        }
        assertTrue(delegate.sessions.isEmpty())
    }

    @Test fun invalidConstructorBudgetsFailImmediately() {
        val delegate = FakeProvider()
        listOf(0 to 1, -1 to 1, 10 to 0, 10 to 11).forEach { (maximum, chunk) ->
            expectThrows<IllegalArgumentException> { BoundedPcmInputProvider(delegate, maximum, chunk) }
        }
    }

    @Test fun theBatchDelegatesOwnFormatGateIsNeverBypassed() {
        var uploads = 0
        CodexBatchTranscriptionProvider(CodexBatchTranscriptionGateway { _, _ ->
            uploads++
            BatchTranscriptionCancellation {}
        }).use { batch ->
            val wrapper = BoundedPcmInputProvider(batch, CodexBatchTranscriptionProvider.MAX_PCM_BYTES, 24_000)
            expectThrows<IllegalArgumentException> {
                wrapper.openSession(RecordingId(1), PcmAudioFormat(16_000, 1, 16))
            }
            assertEquals(0, uploads)
            wrapper.openSession(RecordingId(2), FORMAT).cancel()
        }
    }

    @Test fun productionMaximumDurationAndDelayedFinalReadRetainTheWholeClipAndUploadOnce() {
        val clock = MutableClock()
        val deadlines = DeferredDeadlines(clock)
        val capture = ChunkedCapture(clock)
        var uploadedWave: ByteArray? = null
        var completion: ((Result<String>) -> Unit)? = null
        var uploads = 0
        val batch = CodexBatchTranscriptionProvider(CodexBatchTranscriptionGateway { wave, callback ->
            uploads++
            uploadedWave = wave.copyOf()
            completion = callback
            BatchTranscriptionCancellation {}
        })
        val messages = mutableListOf<Pair<RecordingId, String>>()
        val limits = mutableListOf<RecordingId>()
        lateinit var coordinator: DictationRecordingCoordinator
        val bounded = BoundedPcmInputProvider(batch, CodexBatchTranscriptionProvider.MAX_PCM_BYTES, 24_000) { id ->
            limits += id
            coordinator.stopRecording(id)
        }
        coordinator = DictationRecordingCoordinator(
            config = DictationRecordingConfig(FORMAT, 500, 120_000),
            clock = clock,
            permissionChecker = RecordAudioPermissionChecker { true },
            captureStartBarrier = DictationCaptureStartBarrier { true },
            audioFocus = object : RecordingAudioFocusCoordinator {
                override fun request(onChange: (RecordingAudioFocusChange) -> Unit) = AudioFocusRequestResult.GRANTED
                override fun abandon() = Unit
            },
            captureFactory = PcmAudioCaptureFactory { _, _, chunkBytes ->
                assertEquals(24_000, chunkBytes)
                capture
            },
            sttProvider = bounded,
            deadlineScheduler = deadlines,
            dispatcher = RecordingTaskDispatcher { it() },
            listener = object : DictationRecordingListener {
                override fun onRecordingStateChanged(state: RecordingState) = Unit
                override fun onUserMessageReady(recordingId: RecordingId, transcript: String) {
                    messages += recordingId to transcript
                }
                override fun onStartRejected(activeRecordingId: RecordingId) = error("unexpected busy")
            },
            speechActivityDetector = object : PcmSpeechActivityDetector {
                override fun containsSpeechLikeActivity(pcmBytes: ByteArray, format: PcmAudioFormat) = true
            },
        )
        batch.use {
            val id = coordinator.startRecording()
            // Fill119.9s, leaving a partial chunk and keeping the scheduler callback deferred.
            val inputBytes = CodexBatchTranscriptionProvider.MAX_PCM_BYTES - 4_800
            var emitted = 0
            while (emitted < inputBytes) {
                val length = minOf(16_384, inputBytes - emitted)
                clock.now = (emitted + length) / 48L
                capture.read(ByteArray(length) { 7 })
                emitted += length
            }
            assertEquals(0, capture.stopRequests)
            assertEquals(0, uploads)
            clock.now = 120_020
            deadlines.fireMaximum()
            assertEquals(RecordingStopReason.MAXIMUM_DURATION, (coordinator.state() as RecordingState.Stopping).reason)
            assertEquals(1, capture.stopRequests)
            // One already-blocking AudioRecord read returns16KiB despite requestStop.
            capture.read(ByteArray(16_384) { 9 })
            capture.completeStop()
            assertEquals(1, capture.stopRequests)
            assertEquals(listOf(id), limits)
            assertEquals(1, uploads)
            val wave = checkNotNull(uploadedWave)
            assertEquals(CodexBatchTranscriptionProvider.MAX_PCM_BYTES + 44, wave.size)
            assertEquals(CodexBatchTranscriptionProvider.MAX_PCM_BYTES,
                ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
            assertTrue(wave.copyOfRange(44, inputBytes + 44).all { it == 7.toByte() })
            assertArrayEquals(ByteArray(4_800) { 9 }, wave.copyOfRange(inputBytes + 44, wave.size))
            assertTrue(messages.isEmpty())
            completion!!(Result.success("The complete bounded recording"))
            completion!!(Result.success("Late duplicate"))
            assertEquals(listOf(id to "The complete bounded recording"), messages)
            assertEquals(RecordingStopReason.MAXIMUM_DURATION, (coordinator.state() as RecordingState.Completed).reason)
        }
    }

    private class Fixture {
        val id = RecordingId(1)
        val delegate = FakeProvider()
        val limits = mutableListOf<RecordingId>()
        val wrapper = BoundedPcmInputProvider(delegate, 12, 8, limits::add)
        val session = wrapper.openSession(id, FORMAT)
        val destination get() = delegate.sessions.first()
        fun submit(index: Long = 0, final: Boolean = false, bytes: ByteArray = ByteArray(8)): Result<Unit> =
            submit(chunk(id, index, final, bytes))
        fun submit(chunk: PcmAudioChunk): Result<Unit> {
            var result: Result<Unit>? = null
            session.submitChunk(chunk) { check(result == null); result = it }
            return checkNotNull(result)
        }
    }

    private class FakeProvider : IncrementalSttProvider {
        val sessions = mutableListOf<Destination>()
        override fun openSession(recordingId: RecordingId, format: PcmAudioFormat,
            progressListener: RecordingProgressListener): IncrementalSttSession = Destination().also { sessions += it }
    }

    private class Destination : IncrementalSttSession {
        val chunks = mutableListOf<PcmAudioChunk>()
        val acks = mutableListOf<(Result<Unit>) -> Unit>()
        var autoAck = true
        var finishCalls = 0
        var cancelCalls = 0
        var finishCallback: ((Result<String>) -> Unit)? = null
        override fun submitChunk(chunk: PcmAudioChunk, callback: (Result<Unit>) -> Unit) {
            chunks += chunk
            acks += callback
            if (autoAck) callback(Result.success(Unit))
        }
        override fun finish(callback: (Result<String>) -> Unit) { finishCalls++; finishCallback = callback }
        override fun cancel() { cancelCalls++ }
    }

    private class MutableClock : MonotonicClock {
        var now = 0L
        override fun nowMillis() = now
    }

    private class DeferredDeadlines(private val clock: MutableClock) : RecordingDeadlineScheduler {
        private data class Entry(val due: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()
        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledRecordingDeadline {
            val entry = Entry(clock.now + delayMillis, task)
            entries += entry
            return ScheduledRecordingDeadline { entry.cancelled = true }
        }
        fun fireMaximum() = entries.single { it.due == 120_000L && !it.cancelled }.task()
    }

    private class ChunkedCapture(private val clock: MutableClock) : PcmAudioCapture {
        private val chunker = FixedPcmChunker(24_000)
        private lateinit var listener: PcmAudioCapture.Listener
        var stopRequests = 0
        override fun start(listener: PcmAudioCapture.Listener) { this.listener = listener }
        override fun requestStop() { stopRequests++ }
        override fun close() = Unit
        fun read(bytes: ByteArray) = chunker.append(bytes).forEach { listener.onAudioChunk(it, clock.now) }
        fun completeStop() = listener.onCaptureStopped(chunker.finish(), clock.now)
    }

    companion object {
        private val FORMAT = CodexBatchTranscriptionProvider.FORMAT
        private fun chunk(id: RecordingId, index: Long, final: Boolean, bytes: ByteArray): PcmAudioChunk =
            PcmAudioChunk.create(id, index, bytes, final, 0)
        private fun <T> assertFailure(result: Result<T>, suffix: String) {
            assertTrue(result.isFailure)
            assertEquals("codex_transcription_$suffix", (result.exceptionOrNull() as CodexBatchTranscriptionFailure).code)
        }
        private inline fun <reified T : Throwable> expectThrows(block: () -> Unit) {
            try { block(); fail("expected ${T::class.java.simpleName}") }
            catch (failure: Throwable) { if (failure !is T) throw failure }
        }
    }
}
