package ai.hans.standard.media

import ai.hans.standard.voice.tts.android.BearerTokenSource
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import org.json.JSONObject

fun interface TranscriptionHttpConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

data class OpenAiVideoTranscriptionConfig(
    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 120_000,
    val model: String = "gpt-4o-transcribe",
) {
    init {
        require(connectTimeoutMillis in 1..60_000)
        require(readTimeoutMillis in 1_000..180_000)
        require(model.matches(Regex("[a-zA-Z0-9._-]{1,128}")))
    }
}

/**
 * Bounded multipart adapter for OpenAI's public audio transcription endpoint. The bearer token is
 * read from Android Keystore only on the worker and never enters a media result or log message.
 */
class OpenAiVideoAudioTranscriptionGateway(
    private val tokenSource: BearerTokenSource,
    private val config: OpenAiVideoTranscriptionConfig = OpenAiVideoTranscriptionConfig(),
    private val connectionFactory: TranscriptionHttpConnectionFactory =
        TranscriptionHttpConnectionFactory { url -> url.openConnection() as HttpURLConnection },
    executor: Executor? = null,
) : VideoAudioTranscriptionGateway, Closeable {
    private val ownedExecutor: ExecutorService? = if (executor == null) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hans-video-transcription").apply { isDaemon = true }
        }
    } else {
        null
    }
    private val executor = executor ?: ownedExecutor!!
    private val active = Collections.synchronizedSet(mutableSetOf<Transcription>())

    override fun submit(
        request: VideoAudioTranscriptionRequest,
        callback: VideoAudioTranscriptionCallback,
    ): VideoAudioTranscriptionCancellation {
        val work = Transcription(request, callback)
        active += work
        try {
            executor.execute(work::run)
        } catch (_: RuntimeException) {
            active -= work
            callback.onFailed(retryable = true)
        }
        return work
    }

    override fun close() {
        synchronized(active) { active.toList() }.forEach(Transcription::cancel)
        ownedExecutor?.shutdownNow()
    }

    private inner class Transcription(
        private val request: VideoAudioTranscriptionRequest,
        private val callback: VideoAudioTranscriptionCallback,
    ) : VideoAudioTranscriptionCancellation {
        private val cancelled = AtomicBoolean(false)
        private val terminal = AtomicBoolean(false)
        private val connection = AtomicReference<HttpURLConnection?>()

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            connection.getAndSet(null)?.disconnect()
            active -= this
        }

        fun run() {
            var opened: HttpURLConnection? = null
            try {
                val source = validateRequest(request)
                val token = tokenSource.loadBearerToken()
                requireValidBearerToken(token)
                if (cancelled.get()) return

                val endpoint = URL(ENDPOINT)
                check(endpoint.protocol == "https" && endpoint.host == "api.openai.com")
                opened = connectionFactory.open(endpoint)
                if (!connection.compareAndSet(null, opened) || cancelled.get()) return
                val boundary = "hans-${UUID.randomUUID()}"
                configure(opened, boundary, token!!)
                BufferedOutputStream(opened.outputStream, COPY_BUFFER_BYTES).use { output ->
                    OpenAiTranscriptionMultipart.write(
                        output = output,
                        boundary = boundary,
                        model = config.model,
                        source = source,
                        sourceContainsOnlyAudio = request.sourceContainsOnlyAudio,
                        cancelled = cancelled::get,
                    )
                    output.flush()
                }
                if (cancelled.get()) return
                val status = opened.responseCode
                if (status !in 200..299) {
                    drainBounded(opened.errorStream, MAX_ERROR_RESPONSE_BYTES)
                    fail(status == 408 || status == 409 || status == 429 || status >= 500)
                    return
                }
                val response = readBounded(opened.inputStream, MAX_SUCCESS_RESPONSE_BYTES)
                val transcript = JSONObject(response.toString(StandardCharsets.UTF_8))
                    .getString("text")
                    .trim()
                    .take(MAX_TRANSCRIPT_CHARACTERS)
                if (transcript.isBlank()) {
                    fail(retryable = false)
                } else {
                    complete(transcript)
                }
            } catch (_: SocketTimeoutException) {
                fail(retryable = true)
            } catch (_: UnknownHostException) {
                fail(retryable = true)
            } catch (_: SSLException) {
                fail(retryable = false)
            } catch (_: IOException) {
                fail(retryable = true)
            } catch (_: SecurityException) {
                fail(retryable = false)
            } catch (_: RuntimeException) {
                fail(retryable = false)
            } finally {
                connection.compareAndSet(opened, null)
                opened?.disconnect()
                active -= this
            }
        }

        private fun complete(transcript: String) {
            if (!cancelled.get() && terminal.compareAndSet(false, true)) {
                runCatching { callback.onCompleted(transcript) }
            }
        }

        private fun fail(retryable: Boolean) {
            if (!cancelled.get() && terminal.compareAndSet(false, true)) {
                runCatching { callback.onFailed(retryable) }
            }
        }
    }

    private fun configure(
        target: HttpURLConnection,
        boundary: String,
        token: String,
    ) {
        target.requestMethod = "POST"
        target.instanceFollowRedirects = false
        target.doInput = true
        target.doOutput = true
        target.useCaches = false
        target.connectTimeout = config.connectTimeoutMillis
        target.readTimeout = config.readTimeoutMillis
        target.setRequestProperty("Authorization", "Bearer $token")
        target.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        target.setRequestProperty("Accept", "application/json")
        target.setChunkedStreamingMode(COPY_BUFFER_BYTES)
    }

    private fun validateRequest(request: VideoAudioTranscriptionRequest): File {
        val source = File(request.source.absolutePath)
        require(source.isAbsolute && source.isFile && !java.nio.file.Files.isSymbolicLink(source.toPath()))
        require(source.length() == request.source.byteCount)
        require(source.length() in 1..MAX_UPLOAD_BYTES)
        require(request.track.trackIndex >= 0)
        return source
    }

    private fun requireValidBearerToken(token: String?) {
        require(token != null && token.length in 16..1_024)
        require(token.all { it.code in 0x21..0x7e })
    }

    private companion object {
        const val ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        const val COPY_BUFFER_BYTES = 64 * 1_024
        const val MAX_UPLOAD_BYTES = 128L * 1_024L * 1_024L
        const val MAX_SUCCESS_RESPONSE_BYTES = 512 * 1_024
        const val MAX_ERROR_RESPONSE_BYTES = 8 * 1_024
        const val MAX_TRANSCRIPT_CHARACTERS = 128 * 1_024
    }
}

internal object OpenAiTranscriptionMultipart {
    fun write(
        output: java.io.OutputStream,
        boundary: String,
        model: String,
        source: File,
        sourceContainsOnlyAudio: Boolean,
        cancelled: () -> Boolean = { false },
    ) {
        require(boundary.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(model.matches(Regex("[a-zA-Z0-9._-]{1,128}")))
        val extension = if (sourceContainsOnlyAudio) "m4a" else "mp4"
        val mimeType = if (sourceContainsOnlyAudio) "audio/mp4" else "video/mp4"
        writeAscii(
            output,
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"model\"\r\n\r\n" +
                "$model\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"media.$extension\"\r\n" +
                "Content-Type: $mimeType\r\n\r\n",
        )
        BufferedInputStream(source.inputStream(), 64 * 1_024).use { input ->
            val buffer = ByteArray(64 * 1_024)
            while (true) {
                if (cancelled()) throw IOException("cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
        writeAscii(output, "\r\n--$boundary--\r\n")
    }

    private fun writeAscii(output: java.io.OutputStream, value: String) {
        output.write(value.toByteArray(StandardCharsets.US_ASCII))
    }
}

private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
    input.use { source ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1_024)
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maxBytes)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

private fun drainBounded(input: InputStream?, maxBytes: Int) {
    if (input == null) return
    runCatching { readBounded(input, maxBytes) }
}
