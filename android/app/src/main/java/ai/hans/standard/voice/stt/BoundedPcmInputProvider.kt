package ai.hans.standard.voice.stt

import ai.hans.standard.voice.IncrementalSttProvider
import ai.hans.standard.voice.IncrementalSttSession
import ai.hans.standard.voice.PcmAudioChunk
import ai.hans.standard.voice.PcmAudioFormat
import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingProgressListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Retains a bounded whole-frame prefix without losing an otherwise valid recording when a
 * physical stop leaves an AudioRecord read or final short chunk in flight. Reaching the budget
 * requests an actual capture stop through [onLimitReached]; it does not itself finish/upload.
 * Only bounded, correctly ordered tail chunks are acknowledged and discarded. The eventual
 * final marker is still delivered exactly once, with the delegate's own consecutive indexes.
 *
 * The caller separately owns the delegate's lifetime and network cancellation.
 */
class BoundedPcmInputProvider(
    private val delegate: IncrementalSttProvider,
    private val maximumPcmBytes: Int,
    private val maximumChunkBytes: Int,
    private val onLimitReached: (RecordingId) -> Unit = {},
) : IncrementalSttProvider {
    private val providerLock = Any()
    private var current: Session? = null

    init {
        require(maximumPcmBytes > 0)
        require(maximumChunkBytes in 1..maximumPcmBytes)
    }

    override fun openSession(
        recordingId: RecordingId,
        format: PcmAudioFormat,
        progressListener: RecordingProgressListener,
    ): IncrementalSttSession = synchronized(providerLock) {
        require(maximumPcmBytes % format.bytesPerFrame == 0)
        require(maximumChunkBytes % format.bytesPerFrame == 0)
        check(current?.isTerminal() != false) { "codex_transcription_busy" }
        Session(recordingId, format.bytesPerFrame,
            delegate.openSession(recordingId, format, progressListener)).also { current = it }
    }

    private inner class Session(
        private val recordingId: RecordingId,
        private val frameBytes: Int,
        private val destination: IncrementalSttSession,
    ) : IncrementalSttSession {
        private val lock = Any()
        private var phase = Phase.CAPTURING
        private var nextInputIndex = 0L
        private var nextForwardedIndex = 0L
        private var retainedBytes = 0
        private var finalSeen = false
        private var limitReported = false
        private val pending = LinkedHashSet<Long>()
        private val delegateCancelled = AtomicBoolean(false)

        fun isTerminal(): Boolean = synchronized(lock) { phase == Phase.TERMINAL }

        override fun submitChunk(chunk: PcmAudioChunk, callback: (Result<Unit>) -> Unit) {
            var invalid = false
            var reportLimit = false
            val forwarded = synchronized(lock) {
                if (phase != Phase.CAPTURING || finalSeen || chunk.recordingId != recordingId ||
                    chunk.index != nextInputIndex || nextInputIndex == Long.MAX_VALUE ||
                    chunk.byteCount > maximumChunkBytes || chunk.byteCount % frameBytes != 0) {
                    invalid = true
                    null
                } else {
                    nextInputIndex++
                    finalSeen = chunk.isFinal
                    val retain = minOf(chunk.byteCount, maximumPcmBytes - retainedBytes)
                    retainedBytes += retain
                    if (retainedBytes == maximumPcmBytes && !limitReported) {
                        limitReported = true
                        reportLimit = true
                    }
                    if (retain == 0 && !chunk.isFinal) {
                        null
                    } else {
                        val source = chunk.copyBytes()
                        val prefix = source.copyOf(retain)
                        source.fill(0)
                        val forwardedIndex = nextForwardedIndex++
                        pending.add(forwardedIndex)
                        PcmAudioChunk.create(recordingId, forwardedIndex, prefix, chunk.isFinal,
                            chunk.capturedAtMillis).also { prefix.fill(0) }
                    }
                }
            }
            if (invalid) {
                callback(invalidAudio())
                return
            }
            if (forwarded == null) {
                callback(Result.success(Unit))
            } else {
                val delivered = AtomicBoolean(false)
                try {
                    destination.submitChunk(forwarded) response@{ result ->
                        if (!delivered.compareAndSet(false, true)) return@response
                        val accepted = synchronized(lock) {
                            if (phase == Phase.TERMINAL || !pending.remove(forwarded.index)) false else {
                                if (result.isFailure) { phase = Phase.TERMINAL; pending.clear() }
                                true
                            }
                        }
                        if (!accepted) return@response
                        if (result.isFailure) cancelDelegate()
                        callback(result.fold(onSuccess = { Result.success(Unit) }, onFailure = {
                            Result.failure(it as? CodexBatchTranscriptionFailure
                                ?: CodexBatchTranscriptionFailure("codex_transcription_unavailable"))
                        }))
                    }
                } catch (_: Exception) {
                    if (delivered.compareAndSet(false, true)) {
                        val accepted = synchronized(lock) {
                            if (phase == Phase.TERMINAL) false else {
                                phase = Phase.TERMINAL; pending.clear(); true
                            }
                        }
                        if (accepted) {
                            cancelDelegate()
                            callback(Result.failure(CodexBatchTranscriptionFailure("codex_transcription_unavailable")))
                        }
                    }
                }
            }
            if (reportLimit && !isTerminal()) runCatching { onLimitReached(recordingId) }
        }

        override fun finish(callback: (Result<String>) -> Unit) {
            var invalid = false
            synchronized(lock) {
                if (phase != Phase.CAPTURING) return
                if (!finalSeen || pending.isNotEmpty()) { phase = Phase.TERMINAL; invalid = true }
                else phase = Phase.FINISHING
            }
            if (invalid) {
                cancelDelegate()
                callback(invalidAudio())
                return
            }
            val delivered = AtomicBoolean(false)
            try {
                destination.finish response@{ result ->
                    if (!delivered.compareAndSet(false, true)) return@response
                    val accepted = synchronized(lock) {
                        if (phase != Phase.FINISHING) false else { phase = Phase.TERMINAL; true }
                    }
                    if (accepted) callback(result.fold(onSuccess = { Result.success(it) }, onFailure = {
                        Result.failure(it as? CodexBatchTranscriptionFailure
                            ?: CodexBatchTranscriptionFailure("codex_transcription_unavailable"))
                    }))
                }
            } catch (_: Exception) {
                if (delivered.compareAndSet(false, true)) {
                    val accepted = synchronized(lock) {
                        if (phase != Phase.FINISHING) false else { phase = Phase.TERMINAL; true }
                    }
                    if (accepted) {
                        cancelDelegate()
                        callback(Result.failure(CodexBatchTranscriptionFailure("codex_transcription_unavailable")))
                    }
                }
            }
        }

        override fun cancel() {
            synchronized(lock) {
                if (phase == Phase.TERMINAL) return
                phase = Phase.TERMINAL
                pending.clear()
            }
            cancelDelegate()
        }

        private fun cancelDelegate() {
            if (delegateCancelled.compareAndSet(false, true)) runCatching { destination.cancel() }
        }
    }

    private enum class Phase { CAPTURING, FINISHING, TERMINAL }
    private fun <T> invalidAudio(): Result<T> =
        Result.failure(CodexBatchTranscriptionFailure("codex_transcription_invalid_audio"))
}
