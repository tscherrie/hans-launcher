package ai.hans.standard.voice.tts.android

import ai.hans.standard.voice.tts.StreamingTtsPlayer
import ai.hans.standard.voice.tts.TtsAudioChunk
import ai.hans.standard.voice.tts.TtsAudioStreamFormat
import ai.hans.standard.voice.tts.TtsMessageId
import ai.hans.standard.voice.tts.TtsPlayerFailure
import ai.hans.standard.voice.tts.TtsSegmentId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStreamingTtsPlayerTest {
    @Test fun stopSignalsRouteWaitBeforeTakingPlayerMonitor() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val wake = java.util.concurrent.CountDownLatch(1)
        val stopped = java.util.concurrent.CountDownLatch(1)
        val sink = FakeSink().apply {
            beforeWrite = { entered.countDown(); check(wake.await(2, java.util.concurrent.TimeUnit.SECONDS)) }
            cancelRouteWait = { wake.countDown() }
        }
        val playback = player(sink).open(segment(), pcmFormat(), RecordingListener())
        val writer = Thread { playback.write(chunk(0, 2)) }
        val stopper = Thread { try { playback.stop() } finally { stopped.countDown() } }
        writer.start()
        assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
        stopper.start()
        assertTrue(stopped.await(1, java.util.concurrent.TimeUnit.SECONDS))
        writer.join(1000)
        stopper.join(1000)
        assertTrue(sink.released)
    }
    @Test
    fun streamsOrderedPcmInBoundedWritesAndCompletesOnlyAfterDrainMarker() {
        val sink = FakeSink(maximumWrite = 3)
        val listener = RecordingListener()
        val playback = player(sink).open(segment(), pcmFormat(), listener)

        playback.write(chunk(0, 20))
        playback.finishInput()

        assertTrue(sink.writeRequests.all { it <= 8 * 1_024 })
        assertArrayEquals(ByteArray(20) { it.toByte() }, sink.written.toByteArray())
        assertEquals(10, sink.markerFrame)
        assertEquals(0, listener.completed)
        assertFalse(sink.released)

        sink.fireMarker()
        assertEquals(1, listener.completed)
        assertTrue(sink.stopped)
        assertTrue(sink.released)
        assertFalse(sink.flushed)

        sink.fireMarker()
        assertEquals(1, listener.completed)
    }

    @Test
    fun alreadyDrainedTrackCompletesImmediatelyAfterMarkerIsArmed() {
        val sink = FakeSink().apply { playedFrames = 2 }
        val listener = RecordingListener()
        val playback = player(sink).open(segment(), pcmFormat(), listener)

        playback.write(chunk(0, 4))
        playback.finishInput()

        assertEquals(1, listener.completed)
        assertTrue(sink.released)
    }

    @Test
    fun synchronousMarkerCallbackCannotBeLostDuringFinish() {
        val sink = FakeSink().apply { fireMarkerSynchronously = true }
        val listener = RecordingListener()
        val playback = player(sink).open(segment(), pcmFormat(), listener)

        playback.write(chunk(0, 2))
        playback.finishInput()

        assertEquals(1, listener.completed)
        assertTrue(sink.released)
    }

    @Test
    fun pauseBuffersOnlyAConfiguredBoundAndResumePreservesOrder() {
        val sink = FakeSink()
        val listener = RecordingListener()
        val playback = player(sink).open(segment(), pcmFormat(), listener)

        playback.pause()
        playback.pause()
        playback.write(chunk(0, 4, fill = 1))
        playback.write(chunk(1, 4, fill = 2))
        assertTrue(sink.written.isEmpty())
        assertEquals(1, sink.pauseCalls)

        playback.resume()
        playback.resume()
        assertArrayEquals(byteArrayOf(1, 1, 1, 1, 2, 2, 2, 2), sink.written.toByteArray())
        assertEquals(2, sink.playCalls)
        assertTrue(listener.failures.isEmpty())
    }

    @Test
    fun pausedBackpressureIsBoundedAndFailsExactlyOnce() {
        val sink = FakeSink()
        val listener = RecordingListener()
        val playback = player(sink).open(segment(), pcmFormat(), listener)
        playback.pause()

        playback.write(chunk(0, 64 * 1_024))
        playback.write(chunk(1, 64 * 1_024))
        playback.write(chunk(2, 2))
        playback.write(chunk(3, 2))

        assertEquals(listOf(TtsPlayerFailure("paused_backpressure", true)), listener.failures)
        assertTrue(sink.flushed)
        assertTrue(sink.released)
    }

    @Test
    fun sequenceSizeAlignmentAndFinishViolationsAreStableNonSensitiveFailures() {
        val scenarios = listOf(
            Pair(chunk(1, 2), "chunk_out_of_order"),
            Pair(chunk(0, 64 * 1_024 + 2), "chunk_too_large"),
            Pair(chunk(0, 3), "malformed_pcm"),
        )
        scenarios.forEach { (input, code) ->
            val sink = FakeSink()
            val listener = RecordingListener()
            val playback = player(sink).open(segment(), pcmFormat(), listener)

            playback.write(input)

            assertEquals(code, listener.failures.single().code)
            assertFalse(listener.failures.single().retryable)
            assertTrue(sink.released)
        }

        val sink = FakeSink()
        val listener = RecordingListener()
        val playback = player(sink).open(segment(), pcmFormat(), listener)
        playback.write(chunk(0, 2))
        playback.finishInput()
        playback.write(chunk(1, 2))
        assertEquals("write_after_finish", listener.failures.single().code)
    }

    @Test
    fun emptyAudioOrUnavailableDrainFailsAndReleasesOutput() {
        val emptySink = FakeSink()
        val emptyListener = RecordingListener()
        player(emptySink).open(segment(), pcmFormat(), emptyListener).finishInput()
        assertEquals(TtsPlayerFailure("empty_audio", true), emptyListener.failures.single())
        assertTrue(emptySink.released)

        val markerSink = FakeSink().apply { markerSucceeds = false }
        val markerListener = RecordingListener()
        player(markerSink).open(segment(), pcmFormat(), markerListener).apply {
            write(chunk(0, 2))
            finishInput()
        }
        assertEquals(TtsPlayerFailure("drain_unavailable", true), markerListener.failures.single())
        assertTrue(markerSink.released)
    }

    @Test
    fun stopIsIdempotentSuppressesMarkerAndFlushesQueuedAudio() {
        val sink = FakeSink()
        val listener = RecordingListener()
        val playback = player(sink).open(segment(), pcmFormat(), listener)
        playback.write(chunk(0, 2))
        playback.finishInput()

        playback.stop()
        playback.stop()
        sink.fireMarker()

        assertEquals(1, sink.stopCalls)
        assertEquals(1, sink.releaseCalls)
        assertTrue(sink.flushed)
        assertEquals(0, listener.completed)
        assertTrue(listener.failures.isEmpty())
    }

    @Test
    fun explicitStopFailsClosedWhenPhysicalSinkCannotConfirmTerminalState() {
        val sink = FakeSink().apply {
            throwOnStop = true
            throwOnFlush = true
            throwOnRelease = true
        }
        val playback = player(sink).open(segment(), pcmFormat(), RecordingListener())

        val firstFailure = runCatching { playback.stop() }.exceptionOrNull()
        val secondFailure = runCatching { playback.stop() }.exceptionOrNull()

        assertEquals("output_terminal_unconfirmed", firstFailure?.message)
        assertEquals("output_terminal_unconfirmed", secondFailure?.message)
        assertEquals(1, sink.stopCalls)
        assertEquals(1, sink.flushCalls)
        assertEquals(1, sink.releaseCalls)
    }

    @Test
    fun unsupportedFormatAndSinkCreationFailureHaveStableCodes() {
        val unsupportedListener = RecordingListener()
        val factory = RecordingFactory(FakeSink())
        AndroidStreamingTtsPlayer(factory).open(
            segment(),
            TtsAudioStreamFormat("audio/mpeg", 24_000, 1),
            unsupportedListener,
        )
        assertEquals("unsupported_format", unsupportedListener.failures.single().code)
        assertEquals(0, factory.createCalls)

        val failedListener = RecordingListener()
        AndroidStreamingTtsPlayer(
            Pcm16AudioSinkFactory { _, _ -> throw IllegalStateException("driver detail") },
        ).open(segment(), pcmFormat(), failedListener)
        assertEquals(TtsPlayerFailure("output_unavailable", true), failedListener.failures.single())
        assertFalse(failedListener.failures.single().code.contains("driver"))
    }

    @Test
    fun sinkWriteAndPauseResumeFailuresReleaseAndNotifyOnce() {
        val writeSink = FakeSink().apply { writeResult = -1 }
        val writeListener = RecordingListener()
        player(writeSink).open(segment(), pcmFormat(), writeListener).write(chunk(0, 2))
        assertEquals(TtsPlayerFailure("output_write_failed", true), writeListener.failures.single())

        val pauseSink = FakeSink().apply { throwOnPause = true }
        val pauseListener = RecordingListener()
        player(pauseSink).open(segment(), pcmFormat(), pauseListener).pause()
        assertEquals(TtsPlayerFailure("output_pause_failed", true), pauseListener.failures.single())

        val resumeSink = FakeSink()
        val resumeListener = RecordingListener()
        val resumePlayback = player(resumeSink).open(segment(), pcmFormat(), resumeListener)
        resumePlayback.pause()
        resumeSink.throwOnPlay = true
        resumePlayback.resume()
        assertEquals(TtsPlayerFailure("output_resume_failed", true), resumeListener.failures.single())
    }

    private fun player(sink: FakeSink): AndroidStreamingTtsPlayer =
        AndroidStreamingTtsPlayer(RecordingFactory(sink))

    private fun pcmFormat() = TtsAudioStreamFormat("audio/pcm", 24_000, 1)

    private fun segment() = TtsSegmentId(TtsMessageId("answer"), 0)

    private fun chunk(sequence: Long, size: Int, fill: Int? = null): TtsAudioChunk =
        TtsAudioChunk.create(
            sequence,
            if (fill == null) ByteArray(size) { it.toByte() } else ByteArray(size) { fill.toByte() },
        )

    private class RecordingListener : StreamingTtsPlayer.Listener {
        var completed = 0
        val failures = mutableListOf<TtsPlayerFailure>()
        override fun onCompleted() {
            completed += 1
        }

        override fun onFailure(failure: TtsPlayerFailure) {
            failures += failure
        }
    }

    private class RecordingFactory(
        private val sink: FakeSink,
    ) : Pcm16AudioSinkFactory {
        var createCalls = 0
        override fun create(sampleRateHz: Int, channelCount: Int): Pcm16AudioSink {
            createCalls += 1
            assertEquals(24_000, sampleRateHz)
            assertEquals(1, channelCount)
            return sink
        }
    }

    private class FakeSink(
        private val maximumWrite: Int = Int.MAX_VALUE,
    ) : Pcm16AudioSink {
        val written = mutableListOf<Byte>()
        val writeRequests = mutableListOf<Int>()
        var playedFrames = 0L
        var markerFrame: Int? = null
        var markerSucceeds = true
        var fireMarkerSynchronously = false
        var markerCallback: (() -> Unit)? = null
        var writeResult: Int? = null
        var playCalls = 0
        var pauseCalls = 0
        var stopCalls = 0
        var flushCalls = 0
        var releaseCalls = 0
        var flushed = false
        var stopped = false
        var released = false
        var throwOnPlay = false
        var throwOnPause = false
        var throwOnStop = false
        var throwOnFlush = false
        var throwOnRelease = false
        var beforeWrite: () -> Unit = {}
        var cancelRouteWait: () -> Unit = {}

        override fun cancelPendingRouteWait() = cancelRouteWait()

        override fun play() {
            if (throwOnPlay) throw IllegalStateException("play detail")
            playCalls += 1
        }

        override fun pause() {
            if (throwOnPause) throw IllegalStateException("pause detail")
            pauseCalls += 1
        }

        override fun write(bytes: ByteArray, offset: Int, byteCount: Int): Int {
            beforeWrite()
            writeRequests += byteCount
            writeResult?.let { return it }
            val count = minOf(maximumWrite, byteCount)
            repeat(count) { written += bytes[offset + it] }
            return count
        }

        override fun playedFrames(): Long = playedFrames

        override fun armCompletionMarker(
            framePosition: Int,
            onReached: () -> Unit,
        ): Boolean {
            markerFrame = framePosition
            markerCallback = onReached
            if (fireMarkerSynchronously) onReached()
            return markerSucceeds
        }

        override fun stop() {
            stopped = true
            stopCalls += 1
            if (throwOnStop) throw IllegalStateException("stop detail")
        }

        override fun flush() {
            flushed = true
            flushCalls += 1
            if (throwOnFlush) throw IllegalStateException("flush detail")
        }

        override fun release() {
            released = true
            releaseCalls += 1
            if (throwOnRelease) throw IllegalStateException("release detail")
        }

        fun fireMarker() {
            markerCallback?.invoke()
        }
    }
}
