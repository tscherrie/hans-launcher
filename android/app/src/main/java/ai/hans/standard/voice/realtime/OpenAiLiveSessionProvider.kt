package ai.hans.standard.voice.realtime

import ai.hans.standard.voice.openai.OpenAiApiFailureClassifier
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * Creates one GPT-Live WebRTC session using the owner's existing protected standard-key source.
 * No key is persisted or handed to the media transport. Creation is never automatically retried:
 * an interrupted HTTP result can leave an already-created session with an unknown outcome.
 */
class OpenAiLiveSessionProvider(
    private val keySource: LiveVoiceStandardKeySource,
    client: OkHttpClient? = null,
) : LiveSessionProvider {
    private val client = (client?.newBuilder() ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS))
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun create(
        setup: LiveSessionSetup,
        offerSdp: String,
        callback: LiveSessionProvider.Callback,
    ): LiveVoiceCancellation {
        val payload = try {
            OpenAiLiveProtocol.sessionCreate(setup, offerSdp).toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            notifyFailure(callback, "live_session_request_invalid")
            return LiveVoiceCancellation.NONE
        }
        val key = runCatching { keySource.loadStandardKey()?.trim() }.getOrNull()
        if (key == null || key.length !in 16..4_096 || key.any(Char::isWhitespace)) {
            notifyFailure(callback, "live_standard_key_unavailable")
            return LiveVoiceCancellation.NONE
        }
        val request = try {
            Request.Builder()
                .url(SESSION_ENDPOINT)
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .post(object : RequestBody() {
                    override fun contentType() = JSON_MEDIA_TYPE
                    override fun contentLength(): Long = payload.size.toLong()
                    override fun writeTo(sink: BufferedSink) { sink.write(payload) }
                    // Also prevents follow-up retries such as a 503 with Retry-After: 0.
                    override fun isOneShot(): Boolean = true
                })
                .build()
        } catch (_: Exception) {
            notifyFailure(callback, "live_session_request_invalid")
            return LiveVoiceCancellation.NONE
        }
        val terminal = AtomicBoolean(false)
        val call = client.newCall(request)
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (terminal.compareAndSet(false, true) && !call.isCanceled()) {
                        // The request may already have created a session. Do not auto-reconnect.
                        notifyFailure(callback, "live_session_creation_unconfirmed")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (terminal.get() || call.isCanceled()) return
                        if (response.code != 201) {
                            val body = OpenAiApiFailureClassifier.readBoundedErrorBody(
                                response.body?.byteStream(),
                            )
                            val classified = OpenAiApiFailureClassifier.classifyHttp(response.code, body)
                            if (terminal.compareAndSet(false, true)) {
                                // Even normally transient failures require deliberate new creation.
                                notifyFailure(callback, "live_${classified.code}")
                            }
                            return
                        }
                        val answer = runCatching {
                            OpenAiLiveProtocol.parseSessionAnswer(readBounded(response))
                        }.getOrNull()
                        if (terminal.compareAndSet(false, true)) {
                            if (answer == null) {
                                notifyFailure(callback, "live_session_creation_unconfirmed")
                            } else {
                                runCatching { callback.onCreated(answer) }
                            }
                        }
                    }
                }
            })
        } catch (_: Exception) {
            if (terminal.compareAndSet(false, true)) {
                notifyFailure(callback, "live_session_creation_unconfirmed")
            }
        }
        return LiveVoiceCancellation {
            if (terminal.compareAndSet(false, true)) call.cancel()
        }
    }

    private fun notifyFailure(callback: LiveSessionProvider.Callback, code: String) {
        runCatching { callback.onFailure(LiveVoiceFailure(code, retryable = false)) }
    }

    private fun readBounded(response: Response): String {
        val body = response.body ?: throw IOException("live_body_missing")
        if (body.contentLength() > OpenAiLiveProtocol.MAX_EVENT_BYTES) {
            throw IOException("live_body_too_large")
        }
        val output = ByteArrayOutputStream(8_192)
        val buffer = ByteArray(4_096)
        val input = body.byteStream()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > OpenAiLiveProtocol.MAX_EVENT_BYTES) {
                throw IOException("live_body_too_large")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val SESSION_ENDPOINT = "https://api.openai.com/v1/live/sessions"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
