package ai.hans.standard.voice.stt

import ai.hans.standard.voice.IncrementalSttSession
import ai.hans.standard.voice.PcmAudioChunk
import ai.hans.standard.voice.PcmAudioFormat
import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingProgress
import ai.hans.standard.voice.RecordingProgressListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Synthetic PCM only: none of these tests opens a microphone, authenticates, or calls a backend. */
class CodexBatchTranscriptionProviderTest {
    @Test fun openingAndCapturingNeverStartAnUploadOrEmitTextProgress() = Fixture().use { f ->
        assertTrue(f.gateway.requests.isEmpty())
        assertTrue(f.submit(bytes = pcm(24_000)).isSuccess)
        assertTrue(f.submit(index = 1, final = true, bytes = pcm(24_000)).isSuccess)
        assertTrue(f.gateway.requests.isEmpty())
        assertTrue(f.progress.isEmpty())
        assertTrue(f.failures.isEmpty())
    }

    @Test fun chunksAreCopiedAndConcatenatedInOrderIntoAnExactPcmWav() = Fixture().use { f ->
        val first = pcm(24_000, 3)
        val second = pcm(24_000, 7)
        val firstChunk = chunk(f.id, 0, false, first)
        first.fill(99)
        assertTrue(f.submit(firstChunk).isSuccess)
        assertTrue(f.submit(index = 1, final = true, bytes = second).isSuccess)
        val results = f.finish()
        assertTrue(results.isEmpty())
        val wave = f.gateway.requests.single().snapshot
        assertEquals(48_044, wave.size)
        assertEquals("RIFF", ascii(wave, 0, 4))
        assertEquals("WAVE", ascii(wave, 8, 4))
        assertEquals("fmt ", ascii(wave, 12, 4))
        assertEquals("data", ascii(wave, 36, 4))
        val header = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(48_036, header.getInt(4))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(24_000, header.getInt(24))
        assertEquals(48_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(48_000, header.getInt(40))
        assertArrayEquals(pcm(24_000, 3) + second, wave.copyOfRange(44, wave.size))
    }

    @Test fun emptyFinalMarkerAfterAcceptedAudioIsAValidFinishBoundary() = Fixture().use { f ->
        assertTrue(f.submit(bytes = pcm(48_000)).isSuccess)
        assertTrue(f.submit(index = 1, final = true, bytes = byteArrayOf()).isSuccess)
        f.finish()
        assertEquals(48_044, f.gateway.requests.single().snapshot.size)
    }

    @Test fun finishWithoutTheFinalMarkerFailsWithoutAnUpload() = Fixture().use { f ->
        assertTrue(f.submit(bytes = pcm(48_000)).isSuccess)
        val results = f.finish()
        assertFailure(results.single(), "invalid_audio")
        assertTrue(f.gateway.requests.isEmpty())
        assertEquals(listOf(f.id to "codex_transcription_invalid_audio"), f.failures)
    }

    @Test fun shorterThanOneSecondFailsLocallyWithoutAnUpload() = Fixture().use { f ->
        assertTrue(f.submit(final = true, bytes = pcm(47_998)).isSuccess)
        assertFailure(f.finish().single(), "too_short")
        assertTrue(f.gateway.requests.isEmpty())
    }

    @Test fun emptyRecordingFailsLocallyWithoutAnUpload() = Fixture().use { f ->
        assertTrue(f.submit(final = true, bytes = byteArrayOf()).isSuccess)
        assertFailure(f.finish().single(), "too_short")
        assertTrue(f.gateway.requests.isEmpty())
    }

    @Test fun exactMinimumDurationIsAccepted() = Fixture().use { f ->
        assertTrue(f.submit(final = true).isSuccess)
        f.finish()
        assertEquals(1, f.gateway.requests.size)
    }

    @Test fun exactMaximumDurationIsAcceptedButAnOverflowChunkNeverEntersTheWav() = Fixture().use { f ->
        val maximum = CodexBatchTranscriptionProvider.MAX_PCM_BYTES
        assertTrue(f.submit(bytes = pcm(maximum, 11)).isSuccess)
        assertFailure(f.submit(index = 1, bytes = pcm(2, 99)), "invalid_audio")
        // Rejected chunks must not consume their index or alter the accepted bytes.
        assertTrue(f.submit(index = 1, final = true, bytes = byteArrayOf()).isSuccess)
        f.finish()
        val wave = f.gateway.requests.single().snapshot
        assertEquals(maximum + 44, wave.size)
        assertTrue(wave.copyOfRange(44, wave.size).all { it == 11.toByte() })
    }

    @Test fun anOverlongFirstChunkFailsWithoutAllocatingAnUpload() = Fixture().use { f ->
        assertFailure(f.submit(final = true, bytes = pcm(CodexBatchTranscriptionProvider.MAX_PCM_BYTES + 2)),
            "invalid_audio")
        assertTrue(f.gateway.requests.isEmpty())
    }

    @Test fun outOfOrderChunkDoesNotAdvanceTheExpectedIndex() = Fixture().use { f ->
        assertFailure(f.submit(index = 1), "invalid_audio")
        assertTrue(f.submit(final = true).isSuccess)
        f.finish()
        assertEquals(1, f.gateway.requests.size)
    }

    @Test fun duplicateChunkDoesNotDuplicateAudioOrAdvanceTheExpectedIndex() = Fixture().use { f ->
        assertTrue(f.submit(bytes = pcm(24_000)).isSuccess)
        assertFailure(f.submit(bytes = pcm(24_000)), "invalid_audio")
        assertTrue(f.submit(index = 1, final = true, bytes = pcm(24_000)).isSuccess)
        f.finish()
        assertEquals(48_044, f.gateway.requests.single().snapshot.size)
    }

    @Test fun chunksFromAnotherRecordingAreRejectedWithoutContaminatingTheActiveRecording() = Fixture().use { f ->
        assertFailure(f.submit(chunk(RecordingId(777), 0, true, pcm(48_000, 99))), "invalid_audio")
        assertTrue(f.submit(final = true, bytes = pcm(48_000, 5)).isSuccess)
        f.finish()
        assertArrayEquals(pcm(48_000, 5), f.gateway.requests.single().snapshot.copyOfRange(44, 48_044))
    }

    @Test fun partialPcmFramesAreRejectedAndCannotReachTheGateway() = Fixture().use { f ->
        assertFailure(f.submit(final = true, bytes = byteArrayOf(1)), "invalid_audio")
        assertTrue(f.gateway.requests.isEmpty())
        assertTrue(f.submit(final = true).isSuccess)
    }

    @Test fun chunksAfterTheFinalMarkerAreRejected() = Fixture().use { f ->
        assertTrue(f.submit(final = true).isSuccess)
        assertFailure(f.submit(index = 1), "invalid_audio")
        f.finish()
        assertEquals(48_044, f.gateway.requests.single().snapshot.size)
    }

    @Test fun finishOnlyStartsOneRequestAndOnlyTheFirstBackendResultIsDelivered() = Fixture().use { f ->
        f.submit(final = true)
        val first = f.finish()
        val duplicateFinish = f.finish()
        assertEquals(1, f.gateway.requests.size)
        val request = f.gateway.requests.single()
        request.reply(Result.success("Open WhatsApp"))
        request.reply(Result.success("Duplicate text"))
        request.reply(Result.failure(CodexBatchTranscriptionFailure("codex_transcription_timeout")))
        assertEquals("Open WhatsApp", first.single().getOrThrow())
        assertTrue(duplicateFinish.isEmpty())
        assertEquals(listOf(RecordingProgress.TRANSCRIPT_DELTA), f.progress)
        assertTrue(f.failures.isEmpty())
        assertTrue(request.wav.all { it == 0.toByte() })
    }

    @Test fun synchronousBackendCompletionDoesNotRetainTheRequestOrDeliverTwice() {
        val gateway = FakeGateway().apply { synchronousResult = Result.success("Open settings") }
        Fixture(gateway).use { f ->
            f.submit(final = true)
            assertEquals("Open settings", f.finish().single().getOrThrow())
            val request = gateway.requests.single()
            assertEquals(1, request.cancellations.get())
            request.reply(Result.success("Duplicate"))
            assertEquals(1, f.progress.size)
            assertTrue(request.wav.all { it == 0.toByte() })
        }
    }

    @Test fun failureIsOnceOnlyAndASecondRecordingCanStartAfterFailure() = Fixture().use { f ->
        f.submit(final = true)
        val results = f.finish()
        val request = f.gateway.requests.single()
        request.reply(Result.failure(CodexBatchTranscriptionFailure("codex_transcription_auth_required")))
        request.reply(Result.success("Late text"))
        assertFailure(results.single(), "auth_required")
        assertEquals(listOf(f.id to "codex_transcription_auth_required"), f.failures)
        val next = f.provider.openSession(RecordingId(2), CodexBatchTranscriptionProvider.FORMAT)
        next.cancel()
    }

    @Test fun arbitraryGatewayFailureIsNormalizedAndItsPrivateMessageIsNeverDelivered() = Fixture().use { f ->
        f.submit(final = true)
        val results = f.finish()
        f.gateway.requests.single().reply(Result.failure(IllegalStateException("private response body and token")))
        assertFailure(results.single(), "unavailable")
        assertEquals("codex_transcription_unavailable", results.single().exceptionOrNull()?.message)
        assertEquals(listOf(f.id to "codex_transcription_unavailable"), f.failures)
    }

    @Test fun aThrowingGatewayFailsOnceWithASafeCode() {
        val provider = CodexBatchTranscriptionProvider(CodexBatchTranscriptionGateway { _, _ ->
            throw IllegalStateException("private transport detail")
        })
        provider.use { p ->
            val session = p.openSession(RecordingId(1), CodexBatchTranscriptionProvider.FORMAT)
            session.submitChunk(chunk(RecordingId(1), 0, true, pcm(48_000))) { assertTrue(it.isSuccess) }
            val results = mutableListOf<Result<String>>()
            session.finish(results::add)
            session.finish(results::add)
            assertFailure(results.single(), "unavailable")
        }
    }

    @Test fun blankBackendTranscriptFailsWithASafeCode() = Fixture().use { f ->
        f.submit(final = true)
        val results = f.finish()
        f.gateway.requests.single().reply(Result.success(" \t\n"))
        assertFailure(results.single(), "empty_transcript")
        assertTrue(f.progress.isEmpty())
    }

    @Test fun overlongBackendTranscriptFailsRatherThanBeingTruncatedOrDelivered() = Fixture().use { f ->
        f.submit(final = true)
        val results = f.finish()
        f.gateway.requests.single().reply(Result.success("x".repeat(CodexBatchTranscriptionProvider.MAX_TEXT_CHARACTERS + 1)))
        assertFailure(results.single(), "response_invalid")
        assertTrue(f.progress.isEmpty())
    }

    @Test fun maximumTranscriptLengthIsAcceptedWithoutTextMutation() = Fixture().use { f ->
        f.submit(final = true)
        val text = " x".repeat(CodexBatchTranscriptionProvider.MAX_TEXT_CHARACTERS / 2)
        val results = f.finish()
        f.gateway.requests.single().reply(Result.success(text))
        assertEquals(text, results.single().getOrThrow())
    }

    @Test fun cancelDuringCaptureNeverUploadsAndAllowsAnotherRecording() = Fixture().use { f ->
        f.submit()
        f.session.cancel()
        f.session.cancel()
        assertTrue(f.finish().isEmpty())
        assertTrue(f.gateway.requests.isEmpty())
        f.provider.openSession(RecordingId(2), CodexBatchTranscriptionProvider.FORMAT).cancel()
    }

    @Test fun cancelDuringUploadCancelsOnceWipesAudioAndDropsAllLateResults() = Fixture().use { f ->
        f.submit(final = true)
        val results = f.finish()
        val request = f.gateway.requests.single()
        f.session.cancel()
        f.session.cancel()
        request.reply(Result.success("Late text must not enter the agent"))
        request.reply(Result.failure(CodexBatchTranscriptionFailure("codex_transcription_timeout")))
        assertEquals(1, request.cancellations.get())
        assertTrue(request.wav.all { it == 0.toByte() })
        assertTrue(results.isEmpty())
        assertTrue(f.progress.isEmpty())
        assertTrue(f.failures.isEmpty())
    }

    @Test fun previousRecordingCannotCompleteOrCorruptANewRecording() = Fixture().use { f ->
        f.submit(final = true)
        val oldResults = f.finish()
        val oldRequest = f.gateway.requests.single()
        f.session.cancel()
        val id = RecordingId(2)
        val next = f.provider.openSession(id, CodexBatchTranscriptionProvider.FORMAT)
        next.submitChunk(chunk(id, 0, true, pcm(48_000))) { assertTrue(it.isSuccess) }
        val nextResults = mutableListOf<Result<String>>()
        next.finish(nextResults::add)
        oldRequest.reply(Result.success("Previous recording"))
        f.gateway.requests[1].reply(Result.success("Current recording"))
        assertTrue(oldResults.isEmpty())
        assertEquals("Current recording", nextResults.single().getOrThrow())
    }

    @Test fun twoConsecutiveSuccessfulRecordingsRemainIndependent() = Fixture().use { f ->
        f.submit(final = true)
        val first = f.finish()
        f.gateway.requests.single().reply(Result.success("First recording"))
        val nextId = RecordingId(2)
        val next = f.provider.openSession(nextId, CodexBatchTranscriptionProvider.FORMAT)
        next.submitChunk(chunk(nextId, 0, true, pcm(48_000, 8))) { assertTrue(it.isSuccess) }
        val second = mutableListOf<Result<String>>()
        next.finish(second::add)
        f.gateway.requests[1].reply(Result.success("Second recording"))
        assertEquals("First recording", first.single().getOrThrow())
        assertEquals("Second recording", second.single().getOrThrow())
        assertEquals(2, f.gateway.requests.size)
    }

    @Test fun concurrentRecordingIsRejectedDuringCaptureAndDuringUpload() = Fixture().use { f ->
        expectThrows<IllegalStateException> {
            f.provider.openSession(RecordingId(2), CodexBatchTranscriptionProvider.FORMAT)
        }
        f.submit(final = true)
        f.finish()
        expectThrows<IllegalStateException> {
            f.provider.openSession(RecordingId(2), CodexBatchTranscriptionProvider.FORMAT)
        }
    }

    @Test fun providerCloseCancelsUploadAndPermanentlyRejectsNewSessions() = Fixture().use { f ->
        f.submit(final = true)
        val results = f.finish()
        val request = f.gateway.requests.single()
        f.provider.close()
        f.provider.close()
        request.reply(Result.success("Late result"))
        assertEquals(1, request.cancellations.get())
        assertTrue(results.isEmpty())
        expectThrows<IllegalStateException> {
            f.provider.openSession(RecordingId(2), CodexBatchTranscriptionProvider.FORMAT)
        }
    }

    @Test fun invalidFormatsFailBeforeGatewayUse() {
        val gateway = FakeGateway()
        CodexBatchTranscriptionProvider(gateway).use { provider ->
            listOf(PcmAudioFormat(16_000, 1, 16), PcmAudioFormat(24_000, 2, 16),
                PcmAudioFormat(24_000, 1, 8)).forEach { format ->
                expectThrows<IllegalArgumentException> { provider.openSession(RecordingId(1), format) }
            }
            assertTrue(gateway.requests.isEmpty())
        }
    }

    @Test fun observerFailureCannotSuppressTheFinalFailureCallback() {
        val gateway = FakeGateway()
        CodexBatchTranscriptionProvider(gateway, object : CodexBatchTranscriptionObserver {
            override fun onFailure(recordingId: RecordingId, code: String) { error("observer bug") }
        }).use { provider ->
            val session = provider.openSession(RecordingId(1), CodexBatchTranscriptionProvider.FORMAT)
            session.submitChunk(chunk(RecordingId(1), 0, true, pcm(48_000))) { assertTrue(it.isSuccess) }
            val results = mutableListOf<Result<String>>()
            session.finish(results::add)
            gateway.requests.single().reply(Result.failure(CodexBatchTranscriptionFailure("codex_transcription_timeout")))
            assertFailure(results.single(), "timeout")
        }
    }

    @Test fun progressListenerFailureCannotSuppressTheFinalSuccessCallback() {
        val gateway = FakeGateway()
        CodexBatchTranscriptionProvider(gateway).use { provider ->
            val session = provider.openSession(RecordingId(1), CodexBatchTranscriptionProvider.FORMAT,
                RecordingProgressListener { error("listener bug") })
            session.submitChunk(chunk(RecordingId(1), 0, true, pcm(48_000))) { assertTrue(it.isSuccess) }
            val results = mutableListOf<Result<String>>()
            session.finish(results::add)
            gateway.requests.single().reply(Result.success("Still delivered"))
            assertEquals("Still delivered", results.single().getOrThrow())
        }
    }

    @Test fun aTransientNetworkLossWhileCapturingDoesNotDiscardLocalAudio() = Fixture().use { f ->
        f.submit(bytes = pcm(24_000, 4))
        f.provider.onNetworkUnavailable()
        assertTrue(f.submit(index = 1, final = true, bytes = pcm(24_000, 6)).isSuccess)
        val results = f.finish()
        f.gateway.requests.single().reply(Result.success("Preserved recording"))
        assertEquals("Preserved recording", results.single().getOrThrow())
        assertTrue(f.failures.isEmpty())
    }

    @Test fun networkLossWhileFinishingFailsOnceCancelsUploadAndIgnoresLateResults() = Fixture().use { f ->
        f.submit(final = true)
        val results = f.finish()
        val request = f.gateway.requests.single()
        f.provider.onNetworkUnavailable()
        f.provider.onNetworkUnavailable()
        request.reply(Result.success("Late result"))
        assertFailure(results.single(), "network_unavailable")
        assertEquals(1, request.cancellations.get())
        assertTrue(request.wav.all { it == 0.toByte() })
        assertEquals(listOf(f.id to "codex_transcription_network_unavailable"), f.failures)
        f.provider.openSession(RecordingId(2), CodexBatchTranscriptionProvider.FORMAT).cancel()
    }

    @Test fun cancellingWhileTheGatewayIsReturningItsHandleStillCancelsThatHandle() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicInteger()
        val gateway = CodexBatchTranscriptionGateway { _, _ ->
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            BatchTranscriptionCancellation { cancelled.incrementAndGet() }
        }
        CodexBatchTranscriptionProvider(gateway).use { provider ->
            val session = provider.openSession(RecordingId(1), CodexBatchTranscriptionProvider.FORMAT)
            session.submitChunk(chunk(RecordingId(1), 0, true, pcm(48_000))) { assertTrue(it.isSuccess) }
            val callbackCount = AtomicInteger()
            val thread = Thread { session.finish { callbackCount.incrementAndGet() } }
            thread.start()
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                session.cancel()
            } finally { release.countDown() }
            thread.join(3_000)
            assertFalse(thread.isAlive)
            assertEquals(1, cancelled.get())
            assertEquals(0, callbackCount.get())
        }
    }

    @Test fun networkLossWhileTheGatewayIsReturningItsHandleDeliversFailureAndCancelsTheLateHandle() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicInteger()
        val gateway = CodexBatchTranscriptionGateway { _, _ ->
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            BatchTranscriptionCancellation { cancelled.incrementAndGet() }
        }
        CodexBatchTranscriptionProvider(gateway).use { provider ->
            val session = provider.openSession(RecordingId(1), CodexBatchTranscriptionProvider.FORMAT)
            session.submitChunk(chunk(RecordingId(1), 0, true, pcm(48_000))) { assertTrue(it.isSuccess) }
            val results = java.util.concurrent.CopyOnWriteArrayList<Result<String>>()
            val thread = Thread { session.finish(results::add) }
            thread.start()
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                provider.onNetworkUnavailable()
                assertFailure(results.single(), "network_unavailable")
            } finally { release.countDown() }
            thread.join(3_000)
            assertFalse(thread.isAlive)
            assertEquals(1, cancelled.get())
            assertEquals(1, results.size)
        }
    }

    @Test fun failureCodesRejectArbitraryDetailsRatherThanEmbeddingThemInUiErrors() {
        listOf("insufficient_quota", "codex_transcription_private: token", "codex_transcription_UPPERCASE",
            "codex_transcription_", "codex_transcription_" + "x".repeat(65)).forEach { unsafe ->
            expectThrows<IllegalArgumentException> { CodexBatchTranscriptionFailure(unsafe) }
        }
    }

    @Test fun wavEncoderRejectsCountsOutsideTheBoundedCompletePcmFrames() {
        val bytes = pcm(48_002)
        listOf(0, 47_998, 48_001, 48_004, CodexBatchTranscriptionProvider.MAX_PCM_BYTES + 2).forEach { count ->
            expectThrows<IllegalArgumentException> { CodexBatchTranscriptionProvider.encodeWav(bytes, count) }
        }
    }

    private class Fixture(val gateway: FakeGateway = FakeGateway()) : AutoCloseable {
        val id = RecordingId(1)
        val failures = mutableListOf<Pair<RecordingId, String>>()
        val progress = mutableListOf<RecordingProgress>()
        val provider = CodexBatchTranscriptionProvider(gateway, object : CodexBatchTranscriptionObserver {
            override fun onFailure(recordingId: RecordingId, code: String) { failures.add(recordingId to code) }
        })
        val session = provider.openSession(id, CodexBatchTranscriptionProvider.FORMAT,
            RecordingProgressListener(progress::add))

        fun submit(index: Long = 0, final: Boolean = false, bytes: ByteArray = pcm(48_000)): Result<Unit> =
            submit(chunk(id, index, final, bytes))

        fun submit(chunk: PcmAudioChunk): Result<Unit> {
            var result: Result<Unit>? = null
            session.submitChunk(chunk) { assertTrue("chunk acknowledged only once", result == null); result = it }
            assertNotNull("local capture acknowledgement is synchronous", result)
            return checkNotNull(result)
        }

        fun finish(): MutableList<Result<String>> = mutableListOf<Result<String>>().also { session.finish(it::add) }
        override fun close() = provider.close()
    }

    private class FakeGateway : CodexBatchTranscriptionGateway {
        val requests = mutableListOf<Request>()
        var synchronousResult: Result<String>? = null
        override fun transcribe(wav: ByteArray, callback: (Result<String>) -> Unit): BatchTranscriptionCancellation {
            val request = Request(wav, callback)
            requests.add(request)
            synchronousResult?.let(callback)
            return BatchTranscriptionCancellation { request.cancellations.incrementAndGet() }
        }
    }

    private class Request(val wav: ByteArray, private val callback: (Result<String>) -> Unit) {
        val snapshot = wav.copyOf()
        val cancellations = AtomicInteger()
        fun reply(result: Result<String>) = callback(result)
    }

    companion object {
        private fun pcm(size: Int, value: Int = 7): ByteArray = ByteArray(size) { value.toByte() }
        private fun chunk(id: RecordingId, index: Long, final: Boolean, bytes: ByteArray): PcmAudioChunk =
            PcmAudioChunk.create(id, index, bytes, final, 0)
        private fun ascii(bytes: ByteArray, offset: Int, count: Int): String =
            bytes.copyOfRange(offset, offset + count).toString(Charsets.US_ASCII)
        private fun <T> assertFailure(result: Result<T>, suffix: String) {
            assertTrue("expected stable failure, got $result", result.isFailure)
            val failure = result.exceptionOrNull()
            assertTrue("failure must not expose arbitrary backend/transport details", failure is CodexBatchTranscriptionFailure)
            assertEquals("codex_transcription_$suffix", (failure as CodexBatchTranscriptionFailure).code)
        }
        private inline fun <reified T : Throwable> expectThrows(block: () -> Unit) {
            try { block(); fail("expected ${T::class.java.simpleName}") }
            catch (failure: Throwable) { if (failure !is T) throw failure }
        }
    }
}
