package ai.hans.standard.voice

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtual-time synthetic PCM regression, not a real-hour microphone, OpenAI, audio-routing,
 * wall-clock latency or power measurement. Only bounded read/chunk buffers are retained.
 */
class DictationLongCaptureTest {
    @Test
    fun fullHourStreamsEveryPcmByteAndStopsOnlyAtHardDeadline() {
        val f = StreamFixture()
        val id = f.coordinator.startRecording()
        val expected = MessageDigest.getInstance("SHA-256")
        val read = ByteArray(4094) // Deliberately does not divide the production 250-ms chunk.
        var bytes = 0L
        var nextEnvironmentEvent = 30_000L
        while (bytes < HOUR_BYTES) {
            val count = minOf(read.size.toLong(), HOUR_BYTES - bytes).toInt()
            for (i in 0 until count) read[i] = ((bytes + i) xor ((bytes + i) ushr 11)).toByte()
            expected.update(read, 0, count)
            // The final AudioRecord-like read is delivered just before the deadline callback.
            f.time.advanceTo(minOf((bytes + count) / 48L, HOUR_MILLIS - 1))
            f.capture.read(read, count)
            bytes += count
            if (f.time.now >= nextEnvironmentEvent) {
                f.coordinator.onEnvironmentEvent(VoiceEnvironmentEvent.AppForegroundChanged("example.maps"))
                f.coordinator.onEnvironmentEvent(VoiceEnvironmentEvent.TextToSpeechActivityChanged(true))
                f.coordinator.onEnvironmentEvent(VoiceEnvironmentEvent.RuntimeActivityChanged(true))
                nextEnvironmentEvent += 30_000L
            }
            assertEquals(0, f.capture.stopRequests)
            assertTrue(f.coordinator.state() is RecordingState.Recording)
            assertTrue(f.messages.isEmpty())
            assertTrue(f.capture.chunker.bufferedBytes() < f.config.chunkBytes)
        }
        assertEquals(172_800_000L, bytes)
        assertEquals(bytes, f.session.bytes)
        assertEquals(14_400L, f.session.nextIndex)
        assertEquals(14_400, f.session.progressEvents)
        assertEquals(0, f.session.finishCalls)
        assertEquals(HOUR_MILLIS - 1, f.time.now)
        f.time.advanceTo(HOUR_MILLIS)
        assertEquals(listOf(HOUR_MILLIS), f.capture.stopTimes)
        assertEquals(RecordingStopReason.MAXIMUM_DURATION,
            (f.coordinator.state() as RecordingState.Stopping).reason)
        f.capture.finish()
        assertEquals(14_401L, f.session.nextIndex) // Empty final marker has its own ordered index.
        assertEquals(1, f.session.finalMarkers)
        assertEquals(0, f.session.finalBytes)
        assertEquals(bytes, f.session.bytes)
        assertArrayEquals(expected.digest(), f.session.digest.digest())
        assertEquals(1, f.session.finishCalls)
        assertTrue(f.messages.isEmpty())
        f.session.finalResult!!.invoke(Result.success("synthetic hour complete"))
        f.session.finalResult!!.invoke(Result.success("late duplicate"))
        assertEquals(listOf(id to "synthetic hour complete"), f.messages)
        assertTrue(f.coordinator.state() is RecordingState.Completed)
        assertEquals(1, f.capture.closeCalls)
        f.time.advanceTo(HOUR_MILLIS + 60_000)
        assertEquals(1, f.capture.stopRequests)
        assertEquals(0, f.time.pendingCount())
    }

    @Test
    fun shortFinalChunkCannotOvertakeAnUnacknowledgedFullChunk() {
        val f = StreamFixture()
        val id = f.coordinator.startRecording()
        f.session.holdIndex = 0L
        val input = ByteArray(f.config.chunkBytes + 246) { (it xor (it ushr 9)).toByte() }
        f.time.advanceTo(1000)
        f.capture.read(input, input.size)
        assertEquals(246, f.capture.chunker.bufferedBytes())
        assertEquals(1L, f.session.nextIndex)
        f.coordinator.stopRecording(id)
        f.capture.finish()
        assertEquals(246, f.session.finalBytes)
        assertEquals(input.size.toLong(), f.session.bytes)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(input), f.session.digest.digest())
        assertEquals(0, f.session.finishCalls)
        assertTrue(f.messages.isEmpty())
        f.session.heldAck!!.invoke(Result.success(Unit))
        assertEquals(1, f.session.finishCalls)
        assertTrue(f.messages.isEmpty())
        f.session.finalResult!!.invoke(Result.success("complete tail"))
        f.session.heldAck!!.invoke(Result.success(Unit))
        f.coordinator.stopRecording(id)
        assertEquals(listOf(id to "complete tail"), f.messages)
        assertEquals(1, f.session.finishCalls)
        assertEquals(1, f.capture.stopRequests)
    }

    private class StreamFixture {
        // Match HansDictationService/OpenAiRealtimeTranscriptionProvider, not config defaults.
        val config = DictationRecordingConfig(
            audioFormat = PcmAudioFormat(sampleRateHz = 24_000), chunkDurationMillis = 250L,
        )
        val time = VirtualTime()
        val capture = StreamingCapture(config.chunkBytes, time)
        val messages = mutableListOf<Pair<RecordingId, String>>()
        lateinit var session: CheckingStt
        val coordinator = DictationRecordingCoordinator(
            config, time, RecordAudioPermissionChecker { true }, DictationCaptureStartBarrier { true },
            object : RecordingAudioFocusCoordinator {
                override fun request(onChange: (RecordingAudioFocusChange) -> Unit) = AudioFocusRequestResult.GRANTED
                override fun abandon() = Unit
            },
            PcmAudioCaptureFactory { _, format, chunkBytes ->
                assertEquals(PcmAudioFormat(24_000, 1, 16), format)
                assertEquals(12_000, chunkBytes)
                capture
            },
            object : IncrementalSttProvider {
                override fun openSession(recordingId: RecordingId, format: PcmAudioFormat,
                    progressListener: RecordingProgressListener): IncrementalSttSession =
                    CheckingStt(recordingId, config.chunkBytes, progressListener).also { session = it }
            },
            time, RecordingTaskDispatcher { it() },
            object : DictationRecordingListener {
                override fun onRecordingStateChanged(state: RecordingState) = Unit
                override fun onUserMessageReady(recordingId: RecordingId, transcript: String) {
                    messages += recordingId to transcript
                }
                override fun onStartRejected(activeRecordingId: RecordingId) = error("Unexpected busy rejection")
            },
            // Force inactivity survival to depend on the simulated incremental STT progress,
            // not on accidental speech classification of a deterministic byte pattern.
            object : PcmSpeechActivityDetector {
                override fun containsSpeechLikeActivity(pcmBytes: ByteArray, format: PcmAudioFormat) = false
            },
        )
    }

    private class CheckingStt(val id: RecordingId, val chunkSize: Int,
        val progress: RecordingProgressListener) : IncrementalSttSession {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        var nextIndex = 0L
        var finalMarkers = 0
        var finalBytes = -1
        var finishCalls = 0
        var progressEvents = 0
        var holdIndex: Long? = null
        var heldAck: ((Result<Unit>) -> Unit)? = null
        var finalResult: ((Result<String>) -> Unit)? = null
        private var previousTimestamp = 0L
        override fun submitChunk(chunk: PcmAudioChunk, callback: (Result<Unit>) -> Unit) {
            assertEquals(id, chunk.recordingId)
            assertEquals(nextIndex++, chunk.index)
            assertTrue(chunk.capturedAtMillis >= previousTimestamp)
            previousTimestamp = chunk.capturedAtMillis
            assertEquals(0, finalMarkers)
            if (chunk.isFinal) {
                finalMarkers++
                finalBytes = chunk.byteCount
                assertTrue(chunk.byteCount < chunkSize)
            } else {
                assertEquals(chunkSize, chunk.byteCount)
                progress.onProgress(RecordingProgress.TRANSCRIPT_DELTA)
                progressEvents++
            }
            digest.update(chunk.copyBytes())
            bytes += chunk.byteCount
            if (chunk.index == holdIndex) heldAck = callback else callback(Result.success(Unit))
        }
        override fun finish(callback: (Result<String>) -> Unit) {
            assertEquals(1, finalMarkers)
            finishCalls++
            finalResult = callback
        }
        override fun cancel() = error("Unexpected STT cancellation")
    }

    private class StreamingCapture(chunkBytes: Int, val time: VirtualTime) : PcmAudioCapture {
        val chunker = FixedPcmChunker(chunkBytes)
        lateinit var listener: PcmAudioCapture.Listener
        var stopRequests = 0
        var closeCalls = 0
        val stopTimes = mutableListOf<Long>()
        override fun start(listener: PcmAudioCapture.Listener) { this.listener = listener }
        override fun requestStop() { stopRequests++; stopTimes += time.now }
        override fun close() { closeCalls++ }
        fun read(bytes: ByteArray, length: Int) {
            check(stopRequests == 0)
            chunker.append(bytes, length = length).forEach { listener.onAudioChunk(it, time.now) }
        }
        fun finish() {
            check(stopRequests == 1)
            listener.onCaptureStopped(chunker.finish(), time.now)
        }
    }

    /** Runs due callbacks chronologically, including newly scheduled inactivity checks. */
    private class VirtualTime : MonotonicClock, RecordingDeadlineScheduler {
        var now = 0L
            private set
        private val entries = mutableListOf<Entry>()
        override fun nowMillis() = now
        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledRecordingDeadline {
            require(delayMillis >= 0)
            val entry = Entry(now + delayMillis, task)
            entries += entry
            return ScheduledRecordingDeadline { entries.remove(entry) }
        }
        fun advanceTo(target: Long) {
            require(target >= now)
            while (true) {
                val entry = entries.filter { it.due <= target }.minByOrNull { it.due } ?: break
                entries.remove(entry)
                now = entry.due
                entry.task()
            }
            now = target
        }
        fun pendingCount() = entries.size
        private class Entry(val due: Long, val task: () -> Unit)
    }

    companion object {
        private const val HOUR_MILLIS = 3_600_000L
        private const val HOUR_BYTES = 172_800_000L
    }
}
