package ai.hans.standard.voice.stt

import ai.hans.standard.voice.IncrementalSttProvider
import ai.hans.standard.voice.IncrementalSttSession
import ai.hans.standard.voice.PcmAudioChunk
import ai.hans.standard.voice.PcmAudioFormat
import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingProgress
import ai.hans.standard.voice.RecordingProgressListener
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun interface BatchTranscriptionCancellation { fun cancel() }

/** Authentication and network ownership stay in the native gateway, not this recorder. */
fun interface CodexBatchTranscriptionGateway {
    fun transcribe(wav: ByteArray, callback: (Result<String>) -> Unit): BatchTranscriptionCancellation
}

interface CodexBatchTranscriptionObserver {
    fun onFailure(recordingId: RecordingId, code: String)
    companion object {
        val NONE = object : CodexBatchTranscriptionObserver {
            override fun onFailure(recordingId: RecordingId, code: String) = Unit
        }
    }
}

class CodexBatchTranscriptionFailure(val code: String) : Exception(code) {
    init { require(code.matches(Regex("codex_transcription_[a-z_]{1,64}"))) }
}

/** Bounded local PCM capture; no network or agent action occurs before finish(). */
class CodexBatchTranscriptionProvider(
    private val gateway: CodexBatchTranscriptionGateway,
    private val observer: CodexBatchTranscriptionObserver = CodexBatchTranscriptionObserver.NONE,
) : IncrementalSttProvider, AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var current: Session? = null

    override fun openSession(recordingId: RecordingId, format: PcmAudioFormat,
        progressListener: RecordingProgressListener): IncrementalSttSession = synchronized(lock) {
        check(!closed) { "codex_transcription_closed" }
        require(format == FORMAT) { "codex_transcription_invalid_audio" }
        check(current?.isTerminal() != false) { "codex_transcription_busy" }
        Session(recordingId, progressListener).also { current = it }
    }

    override fun close() {
        val owner = synchronized(lock) { closed = true; current.also { current = null } }
        owner?.cancel()
    }

    /** A local recording is retained; only an in-flight upload has a network dependency. */
    fun onNetworkUnavailable() {
        synchronized(lock) { current }?.networkUnavailable()
    }

    private inner class Session(private val recordingId: RecordingId,
        private val progress: RecordingProgressListener) : IncrementalSttSession {
        private val sessionLock = Any()
        private var state = State.CAPTURING
        private var pcm: ByteArray? = ByteArray(MAX_PCM_BYTES)
        private var count = 0
        private var nextIndex = 0L
        private var finalSeen = false
        private var pendingWav: ByteArray? = null
        private var cancellation: BatchTranscriptionCancellation? = null
        private var firstChunk = true
        private var completion: ((Result<String>) -> Unit)? = null

        init { BatchTranscriptionDiagnostics.begin(recordingId) }
        fun isTerminal(): Boolean = synchronized(sessionLock) { state == State.TERMINAL }

        override fun submitChunk(chunk: PcmAudioChunk, callback: (Result<Unit>) -> Unit) {
            val copied = chunk.copyBytes()
            val result = synchronized(sessionLock) {
                if (state != State.CAPTURING || finalSeen || chunk.recordingId != recordingId ||
                    chunk.index != nextIndex || copied.size % 2 != 0 || copied.size > MAX_PCM_BYTES - count) {
                    Result.failure<Unit>(CodexBatchTranscriptionFailure("codex_transcription_invalid_audio"))
                } else {
                    copied.copyInto(checkNotNull(pcm), count)
                    count += copied.size
                    nextIndex++
                    finalSeen = chunk.isFinal
                    if (firstChunk && copied.isNotEmpty()) {
                        firstChunk = false
                        BatchTranscriptionDiagnostics.event(recordingId, BatchTranscriptionDiagnostics.Event.FIRST_AUDIO)
                    }
                    if (finalSeen) BatchTranscriptionDiagnostics.event(recordingId,
                        BatchTranscriptionDiagnostics.Event.INPUT_ENDED, count)
                    Result.success(Unit)
                }
            }
            copied.fill(0)
            callback(result)
        }

        override fun finish(callback: (Result<String>) -> Unit) {
            var failure: String? = null
            val wav = synchronized(sessionLock) {
                if (state != State.CAPTURING) return
                if (!finalSeen) failure = "codex_transcription_invalid_audio"
                else if (count < MIN_PCM_BYTES) failure = "codex_transcription_too_short"
                if (failure != null) {
                    state = State.TERMINAL
                    clearPcm()
                    null
                } else {
                    state = State.FINISHING
                    completion = callback
                    encodeWav(checkNotNull(pcm), count).also { pendingWav = it; clearPcm() }
                }
            }
            if (wav == null) {
                val code = checkNotNull(failure)
                runCatching { observer.onFailure(recordingId, code) }
                BatchTranscriptionDiagnostics.event(recordingId, BatchTranscriptionDiagnostics.Event.FAILED)
                callback(Result.failure(CodexBatchTranscriptionFailure(code)))
                return
            }
            BatchTranscriptionDiagnostics.event(recordingId, BatchTranscriptionDiagnostics.Event.REQUEST_STARTED)
            try {
                val request = gateway.transcribe(wav) { result ->
                    val accepted = synchronized(sessionLock) {
                        if (state != State.FINISHING) false else {
                            state = State.TERMINAL
                            pendingWav?.fill(0)
                            pendingWav = null
                            cancellation = null
                            completion = null
                            true
                        }
                    }
                    if (!accepted) return@transcribe
                    val safe = result.fold(onSuccess = { text ->
                        if (text.isBlank()) Result.failure(CodexBatchTranscriptionFailure("codex_transcription_empty_transcript"))
                        else if (text.length > MAX_TEXT_CHARACTERS)
                            Result.failure(CodexBatchTranscriptionFailure("codex_transcription_response_invalid"))
                        else Result.success(text)
                    }, onFailure = { Result.failure(it as? CodexBatchTranscriptionFailure
                        ?: CodexBatchTranscriptionFailure("codex_transcription_unavailable")) })
                    if (safe.isSuccess) {
                        BatchTranscriptionDiagnostics.event(recordingId, BatchTranscriptionDiagnostics.Event.TEXT_READY)
                        runCatching { progress.onProgress(RecordingProgress.TRANSCRIPT_DELTA) }
                    } else {
                        val code = (safe.exceptionOrNull() as? CodexBatchTranscriptionFailure)?.code
                            ?: "codex_transcription_unavailable"
                        runCatching { observer.onFailure(recordingId, code) }
                        BatchTranscriptionDiagnostics.event(recordingId, BatchTranscriptionDiagnostics.Event.FAILED)
                    }
                    callback(safe)
                }
                synchronized(sessionLock) {
                    if (state == State.FINISHING) cancellation = request else request.cancel()
                }
            } catch (_: Exception) {
                val accepted = synchronized(sessionLock) {
                    if (state != State.FINISHING) false else {
                        state = State.TERMINAL; pendingWav?.fill(0); pendingWav = null; completion = null; true
                    }
                }
                if (accepted) {
                    val code = "codex_transcription_unavailable"
                    runCatching { observer.onFailure(recordingId, code) }
                    BatchTranscriptionDiagnostics.event(recordingId, BatchTranscriptionDiagnostics.Event.FAILED)
                    callback(Result.failure(CodexBatchTranscriptionFailure(code)))
                }
            }
        }

        override fun cancel() {
            val request = synchronized(sessionLock) {
                if (state == State.TERMINAL) return
                state = State.TERMINAL
                clearPcm()
                completion = null
                cancellation.also { cancellation = null }
            }
            request?.cancel()
            synchronized(sessionLock) { pendingWav?.fill(0); pendingWav = null }
            BatchTranscriptionDiagnostics.event(recordingId, BatchTranscriptionDiagnostics.Event.CANCELLED)
        }

        fun networkUnavailable() {
            val terminal = synchronized(sessionLock) {
                if (state != State.FINISHING) return
                state = State.TERMINAL
                val delivery = completion
                completion = null
                val request = cancellation
                cancellation = null
                pendingWav?.fill(0)
                pendingWav = null
                Pair(request, delivery)
            }
            terminal.first?.cancel()
            val code = "codex_transcription_network_unavailable"
            runCatching { observer.onFailure(recordingId, code) }
            BatchTranscriptionDiagnostics.event(recordingId, BatchTranscriptionDiagnostics.Event.FAILED)
            terminal.second?.invoke(Result.failure(CodexBatchTranscriptionFailure(code)))
        }

        private fun clearPcm() { pcm?.fill(0); pcm = null }
    }

    private enum class State { CAPTURING, FINISHING, TERMINAL }
    companion object {
        val FORMAT = PcmAudioFormat(24_000, 1, 16)
        const val MIN_PCM_BYTES = 48_000
        const val MAX_PCM_BYTES = 5_760_000
        const val MAX_TEXT_CHARACTERS = 16_000

        internal fun encodeWav(pcm: ByteArray, count: Int): ByteArray {
            require(count in MIN_PCM_BYTES..MAX_PCM_BYTES && count <= pcm.size && count % 2 == 0)
            val wav = ByteBuffer.allocate(count + 44).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(count + 36)
                .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
                .putInt(24_000).putInt(48_000).putShort(2).putShort(16)
                .put("data".toByteArray(Charsets.US_ASCII)).putInt(count).put(pcm, 0, count)
            return wav.array()
        }
    }
}
