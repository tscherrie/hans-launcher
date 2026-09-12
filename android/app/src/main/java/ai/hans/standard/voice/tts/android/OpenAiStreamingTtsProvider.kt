package ai.hans.standard.voice.tts.android

import ai.hans.standard.voice.tts.StreamingTtsProvider
import ai.hans.standard.voice.tts.StreamingTtsSynthesis
import ai.hans.standard.voice.tts.TtsAudioChunk
import ai.hans.standard.voice.tts.TtsAudioStreamFormat
import ai.hans.standard.voice.tts.TtsProviderFailure
import ai.hans.standard.voice.tts.TtsSynthesisRequest
import ai.hans.standard.voice.openai.OpenAiApiFailureClassifier
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

/** Reads an API credential at request time without ever exposing it to TTS domain models. */
fun interface BearerTokenSource {
    fun loadBearerToken(): String?
}

fun interface TtsHttpConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

data class OpenAiTtsTransportConfig(
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 30_000,
    val instructions: String = OpenAiTtsRequest.DEFAULT_INSTRUCTIONS,
) {
    init {
        require(connectTimeoutMs in 1..60_000) { "connect_timeout_out_of_range" }
        require(readTimeoutMs in 1..120_000) { "read_timeout_out_of_range" }
        require(instructions.isNotBlank()) { "tts_instructions_blank" }
        require(instructions.length <= OpenAiTtsRequest.MAX_INSTRUCTIONS_CHARACTERS) {
            "tts_instructions_too_large"
        }
    }
}

/**
 * Dependency-free streaming adapter for the public OpenAI Speech endpoint.
 * One worker owns every callback for a synthesis, so callbacks cannot reorder.
 * Pause halts network reads and keeps at most one fixed-size PCM buffer in
 * memory; TCP then supplies the remaining backpressure.
 */
class OpenAiStreamingTtsProvider(
    private val tokenSource: BearerTokenSource,
    private val config: OpenAiTtsTransportConfig = OpenAiTtsTransportConfig(),
    private val connectionFactory: TtsHttpConnectionFactory = TtsHttpConnectionFactory { url ->
        url.openConnection() as HttpURLConnection
    },
    executor: Executor? = null,
) : StreamingTtsProvider, AutoCloseable {
    private val ownedExecutor: ExecutorService? = if (executor == null) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hans-openai-streaming-tts").apply { isDaemon = true }
        }
    } else {
        null
    }
    private val executor: Executor = executor ?: ownedExecutor!!
    private val active = Collections.synchronizedSet(mutableSetOf<Synthesis>())

    override fun start(
        request: TtsSynthesisRequest,
        listener: StreamingTtsProvider.Listener,
    ): StreamingTtsSynthesis {
        val synthesis = Synthesis(request, listener) { finished -> active.remove(finished) }
        active.add(synthesis)
        try {
            executor.execute(synthesis::run)
        } catch (_: RuntimeException) {
            active.remove(synthesis)
            synthesis.failBeforeStart(TtsProviderFailure(CODE_EXECUTOR_UNAVAILABLE, true))
        }
        return synthesis
    }

    override fun close() {
        val snapshot = synchronized(active) { active.toList() }
        snapshot.forEach(Synthesis::cancel)
        ownedExecutor?.shutdownNow()
    }

    private inner class Synthesis(
        private val request: TtsSynthesisRequest,
        private val listener: StreamingTtsProvider.Listener,
        private val onTerminal: (Synthesis) -> Unit,
    ) : StreamingTtsSynthesis {
        private val monitor = Object()

        @Volatile
        private var cancelled = false
        private var paused = false
        private var terminal = false

        @Volatile
        private var connection: HttpURLConnection? = null

        @Volatile
        private var input: InputStream? = null

        override fun pause() {
            synchronized(monitor) {
                if (!cancelled && !terminal) paused = true
            }
        }

        override fun resume() {
            synchronized(monitor) {
                if (!cancelled && !terminal && paused) {
                    paused = false
                    monitor.notifyAll()
                }
            }
        }

        override fun cancel() {
            val toClose: InputStream?
            val toDisconnect: HttpURLConnection?
            synchronized(monitor) {
                if (cancelled || terminal) return
                cancelled = true
                paused = false
                toClose = input
                toDisconnect = connection
                monitor.notifyAll()
            }
            closeQuietly(toClose)
            disconnectQuietly(toDisconnect)
            onTerminal(this)
        }

        fun failBeforeStart(failure: TtsProviderFailure) {
            emitFailure(failure)
        }

        fun run() {
            if (!awaitResumed()) {
                finishCancelled()
                return
            }

            val validated = try {
                OpenAiTtsRequest.validate(request, config.instructions)
            } catch (_: IllegalArgumentException) {
                emitFailure(TtsProviderFailure(CODE_INVALID_REQUEST, false))
                return
            }
            val body = try {
                OpenAiTtsRequest.encodeUtf8(validated)
            } catch (_: IllegalArgumentException) {
                emitFailure(TtsProviderFailure(CODE_INVALID_REQUEST, false))
                return
            }
            val token = try {
                tokenSource.loadBearerToken()
            } catch (_: Exception) {
                null
            }
            if (!isValidToken(token)) {
                emitFailure(TtsProviderFailure(CODE_CREDENTIAL_UNAVAILABLE, false))
                return
            }

            var openedConnection: HttpURLConnection? = null
            var openedInput: InputStream? = null
            try {
                val endpoint = URL(OpenAiTtsRequest.ENDPOINT)
                check(endpoint.protocol == "https" && endpoint.host == "api.openai.com")
                openedConnection = connectionFactory.open(endpoint)
                if (!registerConnection(openedConnection)) return
                configure(openedConnection, token!!, body.size)
                openedConnection.outputStream.use { output ->
                    output.write(body)
                    output.flush()
                }

                if (!awaitResumed()) return
                val status = openedConnection.responseCode
                if (status !in 200..299) {
                    val boundedError = openedConnection.errorStream?.use(
                        OpenAiApiFailureClassifier::readBoundedErrorBody,
                    )
                    emitFailure(classifyHttpStatus(status, boundedError))
                    return
                }

                openedInput = openedConnection.inputStream
                if (!registerInput(openedInput)) return
                if (!emitReady()) return
                streamPcm(openedInput)
            } catch (_: SocketTimeoutException) {
                emitFailureUnlessCancelled(TtsProviderFailure(CODE_NETWORK_TIMEOUT, true))
            } catch (_: UnknownHostException) {
                emitFailureUnlessCancelled(TtsProviderFailure(CODE_DNS_FAILURE, true))
            } catch (_: SSLException) {
                emitFailureUnlessCancelled(TtsProviderFailure(CODE_TLS_FAILURE, false))
            } catch (_: IOException) {
                emitFailureUnlessCancelled(TtsProviderFailure(CODE_NETWORK_IO, true))
            } catch (_: SecurityException) {
                emitFailureUnlessCancelled(TtsProviderFailure(CODE_NETWORK_FORBIDDEN, false))
            } catch (_: RuntimeException) {
                emitFailureUnlessCancelled(TtsProviderFailure(CODE_TRANSPORT_FAILURE, false))
            } finally {
                closeQuietly(openedInput)
                disconnectQuietly(openedConnection)
                synchronized(monitor) {
                    input = null
                    connection = null
                }
                if (cancelled) finishCancelled()
            }
        }

        private fun configure(
            target: HttpURLConnection,
            token: String,
            bodyBytes: Int,
        ) {
            target.requestMethod = "POST"
            target.instanceFollowRedirects = false
            target.doInput = true
            target.doOutput = true
            target.useCaches = false
            target.connectTimeout = config.connectTimeoutMs
            target.readTimeout = config.readTimeoutMs
            target.setRequestProperty("Authorization", "Bearer $token")
            target.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            target.setRequestProperty("Accept", OpenAiTtsRequest.PCM_MIME_TYPE)
            target.setFixedLengthStreamingMode(bodyBytes)
        }

        private fun streamPcm(source: InputStream) {
            val readBuffer = ByteArray(NETWORK_READ_BYTES)
            var carry: Byte? = null
            var sequence = 0L
            var consecutiveEmptyReads = 0

            while (true) {
                if (!awaitResumed()) return
                val count = try {
                    source.read(readBuffer)
                } catch (timeout: SocketTimeoutException) {
                    val pausedAtTimeout = synchronized(monitor) {
                        paused && !cancelled && !terminal
                    }
                    if (!pausedAtTimeout) throw timeout
                    if (!awaitResumed()) return
                    continue
                }
                if (count < 0) break
                if (count == 0) {
                    consecutiveEmptyReads += 1
                    if (consecutiveEmptyReads > MAX_EMPTY_READS) {
                        emitFailure(TtsProviderFailure(CODE_EMPTY_READ_LOOP, true))
                        return
                    }
                    continue
                }
                consecutiveEmptyReads = 0

                val available = count + if (carry == null) 0 else 1
                val evenCount = available and -2
                if (evenCount > 0) {
                    val chunkBytes = ByteArray(evenCount)
                    var destination = 0
                    carry?.let {
                        chunkBytes[0] = it
                        destination = 1
                        carry = null
                    }
                    val sourceCount = evenCount - destination
                    readBuffer.copyInto(chunkBytes, destination, 0, sourceCount)
                    if (sourceCount < count) carry = readBuffer[sourceCount]

                    if (!awaitResumed()) return
                    if (!emitChunk(TtsAudioChunk.create(sequence++, chunkBytes))) return
                } else {
                    carry = readBuffer[0]
                }
            }

            if (carry != null) {
                emitFailure(TtsProviderFailure(CODE_MALFORMED_PCM, false))
            } else {
                emitCompleted()
            }
        }

        private fun registerConnection(value: HttpURLConnection): Boolean =
            synchronized(monitor) {
                if (cancelled || terminal) {
                    disconnectQuietly(value)
                    false
                } else {
                    connection = value
                    true
                }
            }

        private fun registerInput(value: InputStream): Boolean =
            synchronized(monitor) {
                if (cancelled || terminal) {
                    closeQuietly(value)
                    false
                } else {
                    input = value
                    true
                }
            }

        private fun awaitResumed(): Boolean {
            synchronized(monitor) {
                while (paused && !cancelled && !terminal) {
                    try {
                        monitor.wait()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        cancelled = true
                    }
                }
                return !cancelled && !terminal
            }
        }

        private fun emitReady(): Boolean = emitNonTerminal {
            listener.onStreamReady(
                TtsAudioStreamFormat(
                    mimeType = OpenAiTtsRequest.PCM_MIME_TYPE,
                    sampleRateHz = OpenAiTtsRequest.SAMPLE_RATE_HZ,
                    channelCount = OpenAiTtsRequest.CHANNEL_COUNT,
                ),
            )
        }

        private fun emitChunk(chunk: TtsAudioChunk): Boolean = emitNonTerminal {
            listener.onAudioChunk(chunk)
        }

        private fun emitNonTerminal(callback: () -> Unit): Boolean {
            // Never hold the synthesis monitor while crossing into the
            // coordinator. Its acknowledged streaming handoff may wait for a
            // blocking AudioTrack write, while a prioritized stop/pause must
            // remain able to acquire this monitor and cancel that same stream.
            synchronized(monitor) {
                if (cancelled || terminal) return false
            }
            return try {
                callback()
                synchronized(monitor) { !cancelled && !terminal }
            } catch (_: RuntimeException) {
                synchronized(monitor) {
                    cancelled = true
                    paused = false
                    monitor.notifyAll()
                }
                false
            }
        }

        private fun emitCompleted() {
            if (!markTerminal()) return
            try {
                listener.onCompleted()
            } catch (_: RuntimeException) {
                // A consumer failure cannot be safely reported back to that consumer.
            } finally {
                onTerminal(this)
            }
        }

        private fun emitFailureUnlessCancelled(failure: TtsProviderFailure) {
            if (!cancelled) emitFailure(failure)
        }

        private fun emitFailure(failure: TtsProviderFailure) {
            if (!markTerminal()) return
            try {
                listener.onFailure(failure)
            } catch (_: RuntimeException) {
                // A consumer failure cannot be safely reported back to that consumer.
            } finally {
                onTerminal(this)
            }
        }

        private fun markTerminal(): Boolean = synchronized(monitor) {
            if (cancelled || terminal) {
                false
            } else {
                terminal = true
                paused = false
                monitor.notifyAll()
                true
            }
        }

        private fun finishCancelled() {
            onTerminal(this)
        }
    }

    private companion object {
        const val NETWORK_READ_BYTES = 8 * 1_024
        const val MAX_EMPTY_READS = 8
        const val MAX_TOKEN_CHARACTERS = 8 * 1_024

        const val CODE_INVALID_REQUEST = "invalid_request"
        const val CODE_CREDENTIAL_UNAVAILABLE = "credential_unavailable"
        const val CODE_EXECUTOR_UNAVAILABLE = "executor_unavailable"
        const val CODE_AUTHENTICATION_FAILED = "authentication_failed"
        const val CODE_PERMISSION_DENIED = "permission_denied"
        const val CODE_QUOTA_EXHAUSTED = "quota_exhausted"
        const val CODE_RATE_LIMITED = "rate_limited"
        const val CODE_HTTP_TIMEOUT = "http_timeout"
        const val CODE_HTTP_SERVER_ERROR = "http_server_error"
        const val CODE_HTTP_CLIENT_ERROR = "http_client_error"
        const val CODE_UNEXPECTED_REDIRECT = "unexpected_redirect"
        const val CODE_NETWORK_TIMEOUT = "network_timeout"
        const val CODE_DNS_FAILURE = "dns_failure"
        const val CODE_TLS_FAILURE = "tls_failure"
        const val CODE_NETWORK_IO = "network_io"
        const val CODE_NETWORK_FORBIDDEN = "network_forbidden"
        const val CODE_TRANSPORT_FAILURE = "transport_failure"
        const val CODE_EMPTY_READ_LOOP = "empty_read_loop"
        const val CODE_MALFORMED_PCM = "malformed_pcm"

        fun isValidToken(token: String?): Boolean =
            token != null &&
                token.isNotBlank() &&
                token.length <= MAX_TOKEN_CHARACTERS &&
                token.none(Char::isWhitespace)

        fun classifyHttpStatus(status: Int, boundedErrorBody: String?): TtsProviderFailure =
            OpenAiApiFailureClassifier.classifyHttp(status, boundedErrorBody).let {
                TtsProviderFailure(it.code, it.retryable)
            }

        fun closeQuietly(input: InputStream?) {
            try {
                input?.close()
            } catch (_: Exception) {
                // Best effort only; never surface a provider/credential-bearing exception.
            }
        }

        fun disconnectQuietly(connection: HttpURLConnection?) {
            try {
                connection?.disconnect()
            } catch (_: RuntimeException) {
                // Best effort only; never surface a provider/credential-bearing exception.
            }
        }
    }
}
