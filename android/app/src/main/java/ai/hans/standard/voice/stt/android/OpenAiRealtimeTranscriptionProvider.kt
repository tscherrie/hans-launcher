package ai.hans.standard.voice.stt.android

import ai.hans.standard.voice.IncrementalSttProvider
import ai.hans.standard.voice.IncrementalSttSession
import ai.hans.standard.voice.PcmAudioChunk
import ai.hans.standard.voice.PcmAudioFormat
import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingProgress
import ai.hans.standard.voice.RecordingProgressListener
import ai.hans.standard.voice.stt.SttTranscriptionContextSource
import ai.hans.standard.voice.stt.SttTranscriptionDelay
import ai.hans.standard.voice.stt.SttTranscriptionPromptBuilder
import ai.hans.standard.voice.tts.android.BearerTokenSource
import ai.hans.standard.voice.openai.OpenAiApiFailureClassifier
import java.io.Closeable
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** Small transport seam so the stateful protocol can be exercised without a network. */
interface RealtimeTranscriptionSocket {
    fun send(text: String): Boolean

    fun queuedBytes(): Long

    fun close(code: Int, reason: String): Boolean

    fun cancel()
}

interface RealtimeTranscriptionSocketListener {
    fun onOpen()

    fun onText(text: String)

    fun onFailure()

    /** Optional stable, content-free provider reason. Older transports stay compatible. */
    fun onFailure(code: String) = onFailure()

    fun onClosed()
}

fun interface RealtimeTranscriptionSocketFactory {
    fun open(
        bearerToken: String,
        listener: RealtimeTranscriptionSocketListener,
    ): RealtimeTranscriptionSocket
}

fun interface SttDeadline {
    fun cancel()
}

fun interface SttDeadlineScheduler {
    fun schedule(delayMillis: Long, block: () -> Unit): SttDeadline
}

/** Lifecycle telemetry plus explicitly separate, local-only transcript presentation callbacks. */
interface RealtimeTranscriptionObserver {
    fun onSessionReady(recordingId: RecordingId) = Unit

    /** Called only when the server echoes the requested setting in its session update. */
    fun onTranscriptionDelayConfirmed(recordingId: RecordingId, delay: SttTranscriptionDelay) = Unit

    fun onAudioCommitted(recordingId: RecordingId) = Unit

    fun onCompleted(recordingId: RecordingId) = Unit

    fun onFailure(recordingId: RecordingId, code: String) = Unit

    /** Provisional, bounded tail for display only. Never submit this text as a conversation turn. */
    fun onPartialTranscript(recordingId: RecordingId, transcript: String) = Unit

    /** Local recovery only. This incomplete text must never be sent to Codex automatically. */
    fun onInterruptedTranscript(recordingId: RecordingId, transcript: String) = Unit

    companion object {
        val NONE = object : RealtimeTranscriptionObserver {}
    }
}

data class OpenAiRealtimeTranscriptionConfig(
    val sessionReadyTimeoutMillis: Long = 15_000L,
    val finalTranscriptTimeoutMillis: Long = 45_000L,
    val maximumPendingAudioBytes: Int = 8 * 1_024 * 1_024,
    val transcriptionDelay: SttTranscriptionDelay = SttTranscriptionDelay.LOW,
) {
    init {
        require(sessionReadyTimeoutMillis in 1_000L..60_000L)
        require(finalTranscriptTimeoutMillis in 1_000L..120_000L)
        require(maximumPendingAudioBytes in 48_000..32 * 1_024 * 1_024)
    }
}

/**
 * One Realtime transcription session per dictation.
 *
 * Audio is streamed while the user speaks. Only a completed transcript crosses
 * [IncrementalSttSession.finish], the conversation boundary. On interruption a
 * separate recovery callback may preserve the partial text as a local unsent draft.
 */
class OpenAiRealtimeTranscriptionProvider(
    private val tokenSource: BearerTokenSource,
    private val socketFactory: RealtimeTranscriptionSocketFactory =
        OkHttpRealtimeTranscriptionSocketFactory(),
    private val config: OpenAiRealtimeTranscriptionConfig =
        OpenAiRealtimeTranscriptionConfig(),
    deadlineScheduler: SttDeadlineScheduler? = null,
    private val observer: RealtimeTranscriptionObserver =
        RealtimeTranscriptionObserver.NONE,
    private val contextSource: SttTranscriptionContextSource =
        SttTranscriptionContextSource.EMPTY,
    private val delaySource: () -> SttTranscriptionDelay = { config.transcriptionDelay },
) : IncrementalSttProvider, Closeable {
    private val ownedScheduler: ScheduledExecutorService? = if (deadlineScheduler == null) {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "hans-realtime-stt-deadline").apply { isDaemon = true }
        }
    } else {
        null
    }
    private val deadlines = deadlineScheduler ?: SttDeadlineScheduler { delay, block ->
        val future: ScheduledFuture<*> = checkNotNull(ownedScheduler)
            .schedule(block, delay, TimeUnit.MILLISECONDS)
        SttDeadline { future.cancel(false) }
    }
    private val active = Collections.synchronizedSet(mutableSetOf<Session>())

    override fun openSession(
        recordingId: RecordingId,
        format: PcmAudioFormat,
        progressListener: RecordingProgressListener,
    ): IncrementalSttSession {
        require(format.sampleRateHz == REQUIRED_SAMPLE_RATE_HZ) {
            "realtime_stt_requires_24khz_pcm"
        }
        require(format.channelCount == 1 && format.bitsPerSample == 16) {
            "realtime_stt_requires_mono_pcm16"
        }
        val token = tokenSource.loadBearerToken()
            ?.takeIf(::isPlausibleBearerToken)
            ?: error("speech_credential_unavailable")
        // Snapshot once: edits made while speaking intentionally apply to the next recording.
        val transcriptionPrompt = runCatching {
            SttTranscriptionPromptBuilder.build(contextSource.snapshot())
        }.getOrNull()
        val transcriptionDelay = delaySource()
        val session = Session(recordingId, token, progressListener, transcriptionPrompt, transcriptionDelay)
        active += session
        session.connect()
        return session
    }

    override fun close() {
        synchronized(active) { active.toList() }.forEach(Session::cancel)
        ownedScheduler?.shutdownNow()
        (socketFactory as? Closeable)?.close()
    }

    /** A proven loss/block stops capture promptly through the ordinary failure path. */
    fun onNetworkUnavailable() {
        synchronized(active) { active.toList() }.forEach { session ->
            session.networkUnavailable()
        }
    }

    private inner class Session(
        @Suppress("unused") private val recordingId: RecordingId,
        private val bearerToken: String,
        private val progressListener: RecordingProgressListener,
        private val transcriptionPrompt: String?,
        private val transcriptionDelay: SttTranscriptionDelay,
    ) : IncrementalSttSession, RealtimeTranscriptionSocketListener {
        private val pending = ArrayDeque<PendingChunk>()
        private val deltaText = StringBuilder()
        private val seenDeltaEventIds = linkedSetOf<String>()
        private var transcriptItemId: String? = null
        private var lastPartialTranscript = ""
        private var socket: RealtimeTranscriptionSocket? = null
        private var transportOpened = false
        private var sessionUpdateSent = false
        private var sessionReady = false
        private var terminal = false
        private var pendingAudioBytes = 0
        private var finishCallback: ((Result<String>) -> Unit)? = null
        private var commitSent = false
        private var readyDeadline: SttDeadline? = null
        private var finalDeadline: SttDeadline? = null

        fun networkUnavailable() = fail(IllegalStateException("realtime_stt_network_unavailable"))

        fun connect() {
            val opened = try {
                socketFactory.open(bearerToken, this)
            } catch (_: Exception) {
                null
            }
            synchronized(this) {
                if (terminal) {
                    opened?.cancel()
                    return
                }
                if (opened == null) {
                    failLocked(IllegalStateException("realtime_stt_connection_failed"))
                    return
                }
                socket = opened
                if (transportOpened) sendSessionUpdateLocked()
                readyDeadline = deadlines.schedule(config.sessionReadyTimeoutMillis) {
                    fail(IllegalStateException("realtime_stt_session_timeout"))
                }
            }
        }

        override fun submitChunk(chunk: PcmAudioChunk, callback: (Result<Unit>) -> Unit) {
            val bytes = chunk.copyBytes()
            var immediateFailure: Throwable? = null
            synchronized(this) {
                when {
                    terminal -> immediateFailure =
                        IllegalStateException("realtime_stt_session_closed")
                    chunk.recordingId != recordingId -> immediateFailure =
                        IllegalArgumentException("realtime_stt_recording_id_mismatch")
                    chunk.isFinal && bytes.isEmpty() -> {
                        // The ordered empty final marker carries no audio.
                    }
                    pendingAudioBytes.toLong() + bytes.size > config.maximumPendingAudioBytes -> {
                        immediateFailure = IllegalStateException("realtime_stt_pending_audio_limit")
                    }
                    else -> {
                        pending += PendingChunk(bytes, callback)
                        pendingAudioBytes += bytes.size
                    }
                }
                if (immediateFailure == null && chunk.isFinal && bytes.isEmpty()) {
                    callback(Result.success(Unit))
                    return
                }
                if (immediateFailure == null && sessionReady) flushPendingLocked()
            }
            immediateFailure?.let { callback(Result.failure(it)) }
        }

        override fun finish(callback: (Result<String>) -> Unit) {
            var immediateFailure: Throwable? = null
            synchronized(this) {
                when {
                    terminal -> immediateFailure =
                        IllegalStateException("realtime_stt_session_closed")
                    finishCallback != null -> immediateFailure =
                        IllegalStateException("realtime_stt_finish_already_requested")
                    else -> {
                        finishCallback = callback
                        if (sessionReady) {
                            flushPendingLocked()
                            sendCommitIfReadyLocked()
                        }
                    }
                }
            }
            immediateFailure?.let { callback(Result.failure(it)) }
        }

        override fun cancel() {
            val target: RealtimeTranscriptionSocket?
            val callbacks: List<(Result<Unit>) -> Unit>
            val completion: ((Result<String>) -> Unit)?
            synchronized(this) {
                if (terminal) return
                terminal = true
                target = socket
                socket = null
                callbacks = pending.map { it.callback }
                pending.clear()
                pendingAudioBytes = 0
                completion = finishCallback
                finishCallback = null
                cancelDeadlinesLocked()
            }
            active.remove(this)
            val failure = Result.failure<Unit>(IllegalStateException("realtime_stt_cancelled"))
            callbacks.forEach { runCatching { it(failure) } }
            completion?.let {
                runCatching {
                    it(Result.failure(IllegalStateException("realtime_stt_cancelled")))
                }
            }
            runCatching { target?.close(NORMAL_CLOSE_CODE, "dictation cancelled") }
            target?.cancel()
        }

        override fun onOpen() {
            synchronized(this) {
                if (terminal) return
                transportOpened = true
                sendSessionUpdateLocked()
            }
        }

        override fun onText(text: String) {
            val parsed = OpenAiRealtimeTranscriptionProtocol.parseServerEvent(text)
            when (parsed) {
                is RealtimeTranscriptionEvent.SessionReady -> synchronized(this) {
                    if (terminal || sessionReady || !sessionUpdateSent) return
                    // session.updated acknowledges an accepted update. The optional delay
                    // echo is not guaranteed; its absence must not disable dictation.
                    sessionReady = true
                    readyDeadline?.cancel()
                    readyDeadline = null
                    parsed.transcriptionDelay?.let { confirmedDelay ->
                        runCatching {
                            observer.onTranscriptionDelayConfirmed(recordingId, confirmedDelay)
                        }
                    }
                    if (terminal) return
                    runCatching { observer.onSessionReady(recordingId) }
                    if (terminal) return
                    runCatching {
                        progressListener.onProgress(RecordingProgress.TRANSCRIPTION_SESSION_READY)
                    }
                    flushPendingLocked()
                    sendCommitIfReadyLocked()
                }
                is RealtimeTranscriptionEvent.Delta -> synchronized(this) {
                    if (terminal || !sessionReady || !acceptTranscriptItemLocked(parsed.itemId)) return
                    parsed.eventId?.let { eventId ->
                        // Deduplicate identified deliveries, not equal words: repetitions are speech too.
                        if (!seenDeltaEventIds.add(eventId)) return
                        if (seenDeltaEventIds.size > MAX_REMEMBERED_DELTA_EVENT_IDS) {
                            seenDeltaEventIds.remove(seenDeltaEventIds.first())
                        }
                    }
                    if (deltaText.length < MAX_TRANSCRIPT_CHARACTERS) {
                        val room = MAX_TRANSCRIPT_CHARACTERS - deltaText.length
                        deltaText.append(parsed.text.take(room))
                    }
                    val partial = deltaText.substring(
                        (deltaText.length - MAX_PARTIAL_TRANSCRIPT_CHARACTERS).coerceAtLeast(0),
                    ).trim().let { tail ->
                        // A bounded UTF-16 tail must not begin halfway through an emoji/code point.
                        if (tail.firstOrNull()?.isLowSurrogate() == true) tail.drop(1) else tail
                    }
                    if (partial.isNotEmpty() && partial != lastPartialTranscript) {
                        lastPartialTranscript = partial
                        runCatching { observer.onPartialTranscript(recordingId, partial) }
                    }
                    if (terminal) return
                    if (parsed.text.isNotBlank()) {
                        runCatching {
                            progressListener.onProgress(RecordingProgress.TRANSCRIPT_DELTA)
                        }
                    }
                }
                is RealtimeTranscriptionEvent.Completed -> complete(parsed)
                is RealtimeTranscriptionEvent.Error -> {
                    val classified = OpenAiApiFailureClassifier.classifyServerError(
                        parsed.code,
                        parsed.errorType,
                    )
                    val providerCode = parsed.code
                        .lowercase()
                        .takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) }
                    val stableCode = if (
                        classified.code == OpenAiApiFailureClassifier.HTTP_CLIENT_ERROR &&
                        providerCode != null
                    ) {
                        "provider_$providerCode"
                    } else {
                        classified.code
                    }
                    fail(IllegalStateException("realtime_stt_$stableCode"))
                }
                RealtimeTranscriptionEvent.Ignored -> Unit
            }
        }

        private fun acceptTranscriptItemLocked(itemId: String?): Boolean {
            check(Thread.holdsLock(this))
            if (itemId == null) return true
            val accepted = transcriptItemId
            if (accepted != null) return accepted == itemId
            transcriptItemId = itemId
            return true
        }

        override fun onFailure() {
            fail(IllegalStateException("realtime_stt_transport_failed"))
        }

        override fun onFailure(code: String) {
            val safe = code.takeIf { it.matches(Regex("[a-z0-9_]{1,96}")) }
                ?: "realtime_stt_transport_failed"
            fail(IllegalStateException(safe))
        }

        override fun onClosed() {
            val shouldFail = synchronized(this) { !terminal }
            if (shouldFail) fail(IllegalStateException("realtime_stt_transport_closed"))
        }

        private fun flushPendingLocked() {
            check(Thread.holdsLock(this))
            val target = socket ?: return
            while (!terminal && sessionReady && pending.isNotEmpty()) {
                val next = pending.removeFirst()
                pendingAudioBytes -= next.bytes.size
                val payload = OpenAiRealtimeTranscriptionProtocol.audioAppend(next.bytes)
                if (
                    target.queuedBytes() + payload.toByteArray(Charsets.UTF_8).size >
                    config.maximumPendingAudioBytes
                ) {
                    runCatching {
                        next.callback(
                            Result.failure(
                                IllegalStateException("realtime_stt_socket_queue_limit"),
                            ),
                        )
                    }
                    failLocked(IllegalStateException("realtime_stt_socket_queue_limit"))
                    return
                }
                if (!target.send(payload)) {
                    runCatching {
                        next.callback(
                            Result.failure(
                                IllegalStateException("realtime_stt_audio_send_rejected"),
                            ),
                        )
                    }
                    failLocked(IllegalStateException("realtime_stt_audio_send_rejected"))
                    return
                }
                runCatching { next.callback(Result.success(Unit)) }
            }
        }

        private fun sendCommitIfReadyLocked() {
            check(Thread.holdsLock(this))
            if (
                terminal ||
                !sessionReady ||
                commitSent ||
                finishCallback == null ||
                pending.isNotEmpty()
            ) {
                return
            }
            val accepted = socket?.send(OpenAiRealtimeTranscriptionProtocol.commit()) ?: false
            if (!accepted) {
                failLocked(IllegalStateException("realtime_stt_commit_rejected"))
                return
            }
            commitSent = true
            runCatching { observer.onAudioCommitted(recordingId) }
            finalDeadline = deadlines.schedule(config.finalTranscriptTimeoutMillis) {
                fail(IllegalStateException("realtime_stt_final_timeout"))
            }
        }

        private fun sendSessionUpdateLocked() {
            check(Thread.holdsLock(this))
            if (terminal || !transportOpened || sessionUpdateSent) return
            val target = socket ?: return
            val accepted = target.send(
                OpenAiRealtimeTranscriptionProtocol.sessionUpdate(transcriptionPrompt, transcriptionDelay),
            )
            if (!accepted) {
                failLocked(IllegalStateException("realtime_stt_session_update_rejected"))
                return
            }
            sessionUpdateSent = true
        }

        private fun complete(event: RealtimeTranscriptionEvent.Completed) {
            val callback: ((Result<String>) -> Unit)?
            val target: RealtimeTranscriptionSocket?
            val transcript: String
            synchronized(this) {
                if (terminal || !commitSent || !acceptTranscriptItemLocked(event.itemId)) return
                // Corrections and empty final results are authoritative. A delta is never a final fallback.
                transcript = event.transcript.trim()
                if (transcript.isEmpty()) {
                    failLocked(IllegalStateException("realtime_stt_empty_transcript"))
                    return
                }
                terminal = true
                callback = finishCallback
                finishCallback = null
                target = socket
                socket = null
                cancelDeadlinesLocked()
            }
            active.remove(this)
            runCatching { observer.onCompleted(recordingId) }
            runCatching { callback?.invoke(Result.success(transcript)) }
            runCatching { target?.close(NORMAL_CLOSE_CODE, "dictation completed") }
        }

        private fun fail(failure: Throwable) {
            synchronized(this) { failLocked(failure) }
        }

        private fun failLocked(failure: Throwable) {
            check(Thread.holdsLock(this))
            if (terminal) return
            terminal = true
            val target = socket
            socket = null
            val callbacks = pending.map { it.callback }
            pending.clear()
            pendingAudioBytes = 0
            val completion = finishCallback
            finishCallback = null
            cancelDeadlinesLocked()
            active.remove(this)
            deltaText.toString().trim().takeIf(String::isNotBlank)?.let { partial ->
                runCatching { observer.onInterruptedTranscript(recordingId, partial) }
            }
            runCatching { observer.onFailure(recordingId, failure.safeSttCode()) }
            val chunkFailure = Result.failure<Unit>(failure)
            callbacks.forEach { runCatching { it(chunkFailure) } }
            completion?.let { runCatching { it(Result.failure(failure)) } }
            runCatching { target?.close(INTERNAL_ERROR_CLOSE_CODE, "transcription failed") }
            target?.cancel()
        }

        private fun cancelDeadlinesLocked() {
            readyDeadline?.cancel()
            readyDeadline = null
            finalDeadline?.cancel()
            finalDeadline = null
        }
    }

    private data class PendingChunk(
        val bytes: ByteArray,
        val callback: (Result<Unit>) -> Unit,
    )

    private companion object {
        const val REQUIRED_SAMPLE_RATE_HZ = 24_000
        const val MAX_TRANSCRIPT_CHARACTERS = 1_000_000
        const val MAX_PARTIAL_TRANSCRIPT_CHARACTERS = 4_000
        const val MAX_REMEMBERED_DELTA_EVENT_IDS = 512
        const val NORMAL_CLOSE_CODE = 1_000
        const val INTERNAL_ERROR_CLOSE_CODE = 1_011

        fun isPlausibleBearerToken(token: String): Boolean =
            token.length in 16..8 * 1_024 && token.all { it.code in 0x21..0x7e }
    }
}

private fun Throwable.safeSttCode(): String = message
    ?.takeIf { it.matches(Regex("[a-z0-9_:.-]{1,160}")) }
    ?: "realtime_stt_failure"

sealed interface RealtimeTranscriptionEvent {
    data class SessionReady(val transcriptionDelay: SttTranscriptionDelay?) : RealtimeTranscriptionEvent

    data class Delta(
        val text: String,
        val eventId: String? = null,
        val itemId: String? = null,
    ) : RealtimeTranscriptionEvent

    data class Completed(val transcript: String, val itemId: String? = null) : RealtimeTranscriptionEvent

    data class Error(
        val code: String,
        val errorType: String = "",
    ) : RealtimeTranscriptionEvent

    data object Ignored : RealtimeTranscriptionEvent
}

object OpenAiRealtimeTranscriptionProtocol {
    // A dedicated transcription connection is selected by intent. Supplying a
    // model query parameter selects a conversational Realtime session instead
    // and makes the transcription-only session.update fail with invalid_model.
    const val ENDPOINT = "wss://api.openai.com/v1/realtime?intent=transcription"
    const val MODEL = "gpt-live-transcribe"
    const val SAMPLE_RATE_HZ = 24_000

    fun sessionUpdate(
        transcriptionPrompt: String? = null,
        transcriptionDelay: SttTranscriptionDelay = SttTranscriptionDelay.LOW,
    ): String = JSONObject()
        .put("type", "session.update")
        .put(
            "session",
            JSONObject()
                .put("type", "transcription")
                .put(
                    "audio",
                    JSONObject().put(
                        "input",
                        JSONObject()
                            .put(
                                "format",
                                JSONObject()
                                    .put("type", "audio/pcm")
                                    .put("rate", SAMPLE_RATE_HZ),
                            )
                            .put(
                                "transcription",
                                JSONObject()
                                    .put("model", MODEL)
                                    .put("delay", transcriptionDelay.wireValue)
                                    .also { transcription ->
                                        transcriptionPrompt
                                            ?.takeIf(String::isNotBlank)
                                            ?.let { transcription.put("prompt", it) }
                                    },
                            )
                            .put("turn_detection", JSONObject.NULL),
                    ),
                ),
        )
        .toString()

    fun audioAppend(pcm16: ByteArray): String {
        require(pcm16.isNotEmpty())
        require(pcm16.size % 2 == 0)
        return JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", Base64.getEncoder().encodeToString(pcm16))
            .toString()
    }

    fun commit(): String = JSONObject()
        .put("type", "input_audio_buffer.commit")
        .toString()

    fun parseServerEvent(raw: String): RealtimeTranscriptionEvent = runCatching {
        require(raw.length <= MAX_EVENT_CHARACTERS)
        val json = JSONObject(raw)
        when (json.optString("type")) {
            "session.updated", "transcription_session.updated" -> {
                val session = json.optJSONObject("session")
                val transcription = session?.optJSONObject("audio")
                    ?.optJSONObject("input")?.optJSONObject("transcription")
                    ?: session?.optJSONObject("input_audio_transcription")
                RealtimeTranscriptionEvent.SessionReady(
                    SttTranscriptionDelay.fromWireValue(transcription?.optString("delay")),
                )
            }
            "conversation.item.input_audio_transcription.delta" ->
                RealtimeTranscriptionEvent.Delta(
                    json.optString("delta").take(MAX_DELTA_CHARACTERS),
                    json.boundedEventIdentifier("event_id"),
                    json.boundedEventIdentifier("item_id"),
                )
            "conversation.item.input_audio_transcription.completed" ->
                RealtimeTranscriptionEvent.Completed(
                    json.optString("transcript").take(MAX_TRANSCRIPT_CHARACTERS),
                    json.boundedEventIdentifier("item_id"),
                )
            "error",
            "conversation.item.input_audio_transcription.failed" -> {
                val error = json.optJSONObject("error")
                val code = error?.optString("code")
                    ?.takeIf(String::isNotBlank)
                    ?: "unknown"
                val errorType = error?.optString("type")
                    ?.takeIf(String::isNotBlank)
                    .orEmpty()
                RealtimeTranscriptionEvent.Error(
                    code.take(MAX_ERROR_CODE_CHARACTERS),
                    errorType.take(MAX_ERROR_CODE_CHARACTERS),
                )
            }
            else -> RealtimeTranscriptionEvent.Ignored
        }
    }.getOrDefault(RealtimeTranscriptionEvent.Ignored)

    private fun JSONObject.boundedEventIdentifier(key: String): String? = optString(key)
        .takeIf { it.isNotBlank() && it.length <= 256 }

    private const val MAX_EVENT_CHARACTERS = 256 * 1_024
    private const val MAX_DELTA_CHARACTERS = 32 * 1_024
    private const val MAX_TRANSCRIPT_CHARACTERS = 1_000_000
    private const val MAX_ERROR_CODE_CHARACTERS = 128
}

class OkHttpRealtimeTranscriptionSocketFactory(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build(),
    private val ownsClient: Boolean = true,
) : RealtimeTranscriptionSocketFactory, Closeable {
    override fun open(
        bearerToken: String,
        listener: RealtimeTranscriptionSocketListener,
    ): RealtimeTranscriptionSocket {
        val request = Request.Builder()
            .url(OpenAiRealtimeTranscriptionProtocol.ENDPOINT)
            .header("Authorization", "Bearer $bearerToken")
            .build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    listener.onText(text)
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    val safeCode = response?.use { rejected ->
                        val boundedError = OpenAiApiFailureClassifier.readBoundedErrorBody(
                            rejected.body?.byteStream(),
                        )
                        val classified = OpenAiApiFailureClassifier.classifyHttp(
                            rejected.code,
                            boundedError,
                        )
                        "realtime_stt_${classified.code}"
                    } ?: "realtime_stt_${OpenAiApiFailureClassifier.classifyTransport(t).code}"
                    listener.onFailure(safeCode)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed()
                }
            },
        )
        return OkHttpSocketAdapter(socket)
    }

    override fun close() {
        if (!ownsClient) return
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private class OkHttpSocketAdapter(
        private val socket: WebSocket,
    ) : RealtimeTranscriptionSocket {
        override fun send(text: String): Boolean = socket.send(text)

        override fun queuedBytes(): Long = socket.queueSize()

        override fun close(code: Int, reason: String): Boolean = socket.close(code, reason)

        override fun cancel() = socket.cancel()
    }
}
