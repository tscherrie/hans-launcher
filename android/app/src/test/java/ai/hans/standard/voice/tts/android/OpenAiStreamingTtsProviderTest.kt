package ai.hans.standard.voice.tts.android

import ai.hans.standard.voice.realtime.OpenAiLiveProtocol
import ai.hans.standard.voice.tts.StreamingTtsProvider
import ai.hans.standard.voice.tts.TtsAudioChunk
import ai.hans.standard.voice.tts.TtsAudioStreamFormat
import ai.hans.standard.voice.tts.TtsMessageId
import ai.hans.standard.voice.tts.TtsProviderFailure
import ai.hans.standard.voice.tts.TtsSegmentId
import ai.hans.standard.voice.tts.TtsSynthesisRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiStreamingTtsProviderTest {
    @Test
    fun postsBoundedSpeechRequestAndEmitsAlignedPcmCallbacksInStrictOrder() {
        val connection = FakeConnection(
            status = 200,
            response = FragmentedInputStream(
                byteArrayOf(0, 1, 2, 3, 4, 5),
                intArrayOf(3, 1, 2),
            ),
        )
        val listener = RecordingListener()
        provider(connection).use { value -> value.start(request(), listener) }

        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals(1_234, connection.connectTimeout)
        assertEquals(5_678, connection.readTimeout)
        assertEquals("Bearer local-test-token", connection.capturedHeaders["Authorization"])
        assertEquals("application/json; charset=utf-8", connection.capturedHeaders["Content-Type"])
        assertEquals("audio/pcm", connection.capturedHeaders["Accept"])
        assertEquals(connection.fixedLength, connection.requestBody.size())

        val payload = JSONObject(connection.requestBody.toString(StandardCharsets.UTF_8.name()))
        assertEquals("gpt-4o-mini-tts", payload.getString("model"))
        assertEquals("fable", payload.getString("voice"))
        assertEquals("pcm", payload.getString("response_format"))
        assertEquals(1.25, payload.getDouble("speed"), 0.0)
        assertTrue(payload.getString("instructions").isNotBlank())

        assertEquals(listOf("ready", "chunk:0", "chunk:1", "chunk:2", "completed"), listener.events)
        assertEquals(24_000, listener.format?.sampleRateHz)
        assertEquals(1, listener.format?.channelCount)
        assertArrayEquals(byteArrayOf(0, 1), listener.chunks[0].copyBytes())
        assertArrayEquals(byteArrayOf(2, 3), listener.chunks[1].copyBytes())
        assertArrayEquals(byteArrayOf(4, 5), listener.chunks[2].copyBytes())
        assertNull(listener.failure)
        assertTrue(connection.disconnected)
    }

    @Test
    fun firstPcmChunkArrivesBeforeTheHttpResponseFinishes() {
        val response = TwoStageInputStream(byteArrayOf(1, 2), byteArrayOf(3, 4))
        val connection = FakeConnection(200, response)
        val listener = RecordingListener()
        val provider = provider(connection, executor = null)
        try {
            provider.start(request(), listener)

            assertTrue(listener.firstChunk.await(2, TimeUnit.SECONDS))
            assertFalse(listener.terminal.await(100, TimeUnit.MILLISECONDS))
            response.releaseRemainder()
            assertTrue(listener.terminal.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("ready", "chunk:0", "chunk:1", "completed"), listener.events)
        } finally {
            provider.close()
        }
    }

    @Test
    fun pauseStopsDeliveryWithOnlyTheSingleReadBufferInFlightThenResumeContinues() {
        val response = GatedFirstReadInputStream(byteArrayOf(7, 8))
        val connection = FakeConnection(200, response)
        val listener = RecordingListener()
        val provider = provider(connection, executor = null)
        try {
            val handle = provider.start(request(), listener)
            assertTrue(response.readEntered.await(2, TimeUnit.SECONDS))

            handle.pause()
            response.releaseRead()
            assertFalse(listener.firstChunk.await(150, TimeUnit.MILLISECONDS))

            handle.resume()
            assertTrue(listener.terminal.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("ready", "chunk:0", "completed"), listener.events)
        } finally {
            provider.close()
        }
    }

    @Test
    fun cancellingBeforeQueuedWorkStartsOpensNoConnectionAndEmitsNothing() {
        val queued = QueuedExecutor()
        var opens = 0
        val listener = RecordingListener()
        val provider = OpenAiStreamingTtsProvider(
            tokenSource = BearerTokenSource { "local-test-token" },
            connectionFactory = TtsHttpConnectionFactory {
                opens += 1
                FakeConnection(200, ByteArrayInputStream(byteArrayOf(1, 2)))
            },
            executor = queued,
        )

        val handle = provider.start(request(), listener)
        handle.cancel()
        handle.cancel()
        queued.runAll()

        assertEquals(0, opens)
        assertTrue(listener.events.isEmpty())
        provider.close()
    }

    @Test
    fun cancellingAnActiveReadDisconnectsWithoutACompletionOrFailureCallback() {
        val response = CloseBlockingInputStream()
        val connection = FakeConnection(200, response)
        val listener = RecordingListener()
        val provider = provider(connection, executor = null)
        try {
            val handle = provider.start(request(), listener)
            assertTrue(response.readEntered.await(2, TimeUnit.SECONDS))
            handle.cancel()

            assertTrue(response.closed.await(2, TimeUnit.SECONDS))
            assertTrue(connection.disconnected)
            assertEquals(listOf("ready"), listener.events)
        } finally {
            provider.close()
        }
    }

    @Test
    fun httpFailuresReadOnlyBoundedMetadataAndNeverLeakTheErrorBody() {
        val cases = listOf(
            Triple(401, "authentication_failed", false),
            Triple(403, "permission_denied", false),
            Triple(408, "http_timeout", true),
            Triple(429, "rate_limited", true),
            Triple(503, "http_server_error", true),
            Triple(307, "unexpected_redirect", false),
            Triple(422, "http_client_error", false),
        )

        cases.forEach { (status, code, retryable) ->
            val secretError = CloseTrackingInputStream(
                """{"error":{"code":"temporary","message":"server-secret"}}""".toByteArray(),
            )
            val connection = FakeConnection(status, ByteArrayInputStream(ByteArray(0)), secretError)
            val listener = RecordingListener()
            provider(connection).use { value -> value.start(request(), listener) }

            assertEquals(listOf("failure:$code"), listener.events)
            assertEquals(retryable, listener.failure?.retryable)
            assertTrue(secretError.closed)
            assertTrue(secretError.bytesRead > 0)
            assertFalse(listener.events.joinToString().contains("server-secret"))
        }
    }

    @Test
    fun quota429IsNotMisreportedAsTemporaryRateLimit() {
        val body = """{"error":{"code":"credit_balance_exhausted","type":"insufficient_quota"}}"""
        val connection = FakeConnection(
            429,
            ByteArrayInputStream(ByteArray(0)),
            ByteArrayInputStream(body.toByteArray()),
        )
        val listener = RecordingListener()

        provider(connection).use { it.start(request(), listener) }

        assertEquals(TtsProviderFailure("quota_exhausted", false), listener.failure)
    }

    @Test
    fun missingThrowingOrHeaderInjectionTokenFailsBeforeNetwork() {
        listOf<BearerTokenSource>(
            BearerTokenSource { null },
            BearerTokenSource { throw IllegalStateException("credential-secret") },
            BearerTokenSource { "token\r\nInjected: yes" },
            BearerTokenSource { "token with space" },
        ).forEach { source ->
            var opens = 0
            val listener = RecordingListener()
            val provider = OpenAiStreamingTtsProvider(
                tokenSource = source,
                connectionFactory = TtsHttpConnectionFactory {
                    opens += 1
                    FakeConnection(200, ByteArrayInputStream(byteArrayOf(1, 2)))
                },
                executor = DIRECT_EXECUTOR,
            )

            provider.use { it.start(request(), listener) }

            assertEquals(0, opens)
            assertEquals("credential_unavailable", listener.failure?.code)
            assertFalse(listener.events.joinToString().contains("credential-secret"))
        }
    }

    @Test
    fun networkTimeoutHasOnlyStableRetryableFailureCode() {
        val connection = FakeConnection(
            status = 200,
            response = object : InputStream() {
                override fun read(): Int = throw SocketTimeoutException("contains-private-host")
                override fun read(b: ByteArray, off: Int, len: Int): Int = read()
            },
        )
        val listener = RecordingListener()
        provider(connection).use { it.start(request(), listener) }

        assertEquals(TtsProviderFailure("network_timeout", true), listener.failure)
        assertFalse(listener.events.joinToString().contains("private-host"))
    }

    @Test
    fun oddLengthPcmFailsAfterReadyAndNeverCompletes() {
        val listener = RecordingListener()
        provider(FakeConnection(200, ByteArrayInputStream(byteArrayOf(1, 2, 3)))).use {
            it.start(request(), listener)
        }

        assertEquals(listOf("ready", "chunk:0", "failure:malformed_pcm"), listener.events)
        assertArrayEquals(byteArrayOf(1, 2), listener.chunks.single().copyBytes())
    }

    @Test
    fun invalidRequestNeverLoadsCredentialOrOpensConnection() {
        var tokenReads = 0
        var opens = 0
        val listener = RecordingListener()
        val provider = OpenAiStreamingTtsProvider(
            tokenSource = BearerTokenSource {
                tokenReads += 1
                "local-test-token"
            },
            connectionFactory = TtsHttpConnectionFactory {
                opens += 1
                FakeConnection(200, ByteArrayInputStream(byteArrayOf(1, 2)))
            },
            executor = DIRECT_EXECUTOR,
        )

        provider.use { it.start(request(voice = "unsupported"), listener) }

        assertEquals(0, tokenReads)
        assertEquals(0, opens)
        assertEquals("invalid_request", listener.failure?.code)
    }

    @Test
    fun liveOnlyVoicesAreRejectedBeforeAnySpeechCredentialOrHttpAccess() {
        val liveOnlyVoices = OpenAiLiveProtocol.supportedVoices - OpenAiTtsRequest.supportedVoices
        assertTrue("Ripple must never be accepted by the speech endpoint", "ripple" in liveOnlyVoices)
        var tokenReads = 0
        var opens = 0
        OpenAiStreamingTtsProvider(
            tokenSource = BearerTokenSource {
                tokenReads += 1
                "local-test-token"
            },
            connectionFactory = TtsHttpConnectionFactory {
                opens += 1
                FakeConnection(200, ByteArrayInputStream(byteArrayOf(1, 2)))
            },
            executor = DIRECT_EXECUTOR,
        ).use { provider ->
            liveOnlyVoices.forEach { voice ->
                val listener = RecordingListener()
                provider.start(request(voice = voice), listener)
                assertEquals(voice, "invalid_request", listener.failure?.code)
                assertNull(listener.format)
                assertTrue(listener.chunks.isEmpty())
            }
        }

        assertEquals(0, tokenReads)
        assertEquals(0, opens)
    }

    private fun provider(
        connection: FakeConnection,
        executor: Executor? = DIRECT_EXECUTOR,
    ) = OpenAiStreamingTtsProvider(
        tokenSource = BearerTokenSource { "local-test-token" },
        config = OpenAiTtsTransportConfig(
            connectTimeoutMs = 1_234,
            readTimeoutMs = 5_678,
        ),
        connectionFactory = TtsHttpConnectionFactory { url ->
            assertEquals(OpenAiTtsRequest.ENDPOINT, url.toString())
            connection
        },
        executor = executor,
    )

    private fun request(
        voice: String = "fable",
    ) = TtsSynthesisRequest(
        segmentId = TtsSegmentId(TtsMessageId("answer"), 0),
        text = "Hallo Welt.",
        voice = voice,
        speed = 1.25,
    )

    private class RecordingListener : StreamingTtsProvider.Listener {
        val events = CollectionsSynchronizedList<String>()
        val chunks = CollectionsSynchronizedList<TtsAudioChunk>()
        val firstChunk = CountDownLatch(1)
        val terminal = CountDownLatch(1)

        @Volatile
        var format: TtsAudioStreamFormat? = null

        @Volatile
        var failure: TtsProviderFailure? = null

        override fun onStreamReady(format: TtsAudioStreamFormat) {
            this.format = format
            events += "ready"
        }

        override fun onAudioChunk(chunk: TtsAudioChunk) {
            chunks += chunk
            events += "chunk:${chunk.sequence}"
            firstChunk.countDown()
        }

        override fun onCompleted() {
            events += "completed"
            terminal.countDown()
        }

        override fun onFailure(failure: TtsProviderFailure) {
            this.failure = failure
            events += "failure:${failure.code}"
            terminal.countDown()
        }
    }

    private class CollectionsSynchronizedList<T> : AbstractMutableList<T>() {
        private val delegate = java.util.Collections.synchronizedList(mutableListOf<T>())
        override val size: Int get() = delegate.size
        override fun get(index: Int): T = delegate[index]
        override fun add(index: Int, element: T) = delegate.add(index, element)
        override fun removeAt(index: Int): T = delegate.removeAt(index)
        override fun set(index: Int, element: T): T = delegate.set(index, element)
    }

    private class FakeConnection(
        private val status: Int,
        private val response: InputStream,
        private val errors: InputStream? = null,
    ) : HttpURLConnection(URL("https://api.openai.com/v1/audio/speech")) {
        val requestBody = ByteArrayOutputStream()
        val capturedHeaders = linkedMapOf<String, String>()
        var fixedLength = -1
        var disconnected = false

        override fun connect() = Unit
        override fun usingProxy(): Boolean = false

        override fun disconnect() {
            disconnected = true
            try {
                response.close()
            } catch (_: IOException) {
                // Test double cleanup.
            }
        }

        override fun setRequestProperty(key: String, value: String) {
            capturedHeaders[key] = value
        }

        override fun getRequestProperty(key: String?): String? = capturedHeaders[key]
        override fun getOutputStream() = requestBody
        override fun getInputStream(): InputStream = response
        override fun getErrorStream(): InputStream? = errors
        override fun getResponseCode(): Int = status

        override fun setFixedLengthStreamingMode(contentLength: Int) {
            fixedLength = contentLength
        }
    }

    private class FragmentedInputStream(
        private val bytes: ByteArray,
        private val fragments: IntArray,
    ) : InputStream() {
        private var offset = 0
        private var fragment = 0
        override fun read(): Int {
            if (offset >= bytes.size) return -1
            return bytes[offset++].toInt() and 0xff
        }

        override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
            if (offset >= bytes.size) return -1
            val requested = fragments.getOrElse(fragment++) { length }
            val count = minOf(requested, length, bytes.size - offset)
            bytes.copyInto(target, targetOffset, offset, offset + count)
            offset += count
            return count
        }
    }

    private class TwoStageInputStream(
        private val first: ByteArray,
        private val second: ByteArray,
    ) : InputStream() {
        private val release = CountDownLatch(1)
        private var stage = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(target: ByteArray, offset: Int, length: Int): Int = when (stage++) {
            0 -> first.copyTo(target, offset, length)
            1 -> {
                release.await(2, TimeUnit.SECONDS)
                second.copyTo(target, offset, length)
            }
            else -> -1
        }

        fun releaseRemainder() = release.countDown()
    }

    private class GatedFirstReadInputStream(
        private val bytes: ByteArray,
    ) : InputStream() {
        val readEntered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private var delivered = false
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (delivered) return -1
            readEntered.countDown()
            release.await(2, TimeUnit.SECONDS)
            delivered = true
            return bytes.copyTo(target, offset, length)
        }

        fun releaseRead() = release.countDown()
    }

    private class CloseBlockingInputStream : InputStream() {
        val readEntered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val isClosed = AtomicBoolean(false)
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            readEntered.countDown()
            closed.await(2, TimeUnit.SECONDS)
            if (isClosed.get()) throw IOException("closed")
            return -1
        }

        override fun close() {
            isClosed.set(true)
            closed.countDown()
        }
    }

    private class CloseTrackingInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var bytesRead = 0
        var closed = false
        override fun read(): Int {
            bytesRead += 1
            return delegate.read()
        }

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private companion object {
        val DIRECT_EXECUTOR = Executor { command -> command.run() }

        fun ByteArray.copyTo(target: ByteArray, offset: Int, maximum: Int): Int {
            val count = minOf(size, maximum)
            copyInto(target, offset, 0, count)
            return count
        }
    }
}
