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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/** Loads a user-owned standard API key without adding it to domain state. */
fun interface LiveVoiceStandardKeySource {
    fun loadStandardKey(): String?
}

/**
 * Prototype-friendly client-secret minter. For a distributed product, inject a
 * backend-backed [LiveVoiceCredentialProvider] so a standard API key never
 * resides on the phone. Either route gives the media transport only the
 * short-lived client secret returned by `/v1/realtime/client_secrets`.
 */
class OpenAiRealtimeClientSecretProvider(
    private val keySource: LiveVoiceStandardKeySource,
    client: OkHttpClient? = null,
) : LiveVoiceCredentialProvider {
    private val client = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun request(
        session: LiveVoiceSessionConfig,
        callback: LiveVoiceCredentialProvider.Callback,
    ): LiveVoiceCancellation {
        val key = try {
            keySource.loadStandardKey()?.trim()
        } catch (_: Exception) {
            null
        }
        if (!validKey(key)) {
            callback.onFailure(LiveVoiceFailure("realtime_standard_key_unavailable", false))
            return LiveVoiceCancellation.NONE
        }
        val request = Request.Builder()
            .url(CLIENT_SECRET_ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .post(
                OpenAiRealtimeProtocol.clientSecretRequest(session)
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()
        val call = client.newCall(request)
        val terminal = AtomicBoolean(false)
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (terminal.compareAndSet(false, true) && !call.isCanceled()) {
                        callback.onFailure(LiveVoiceFailure("realtime_credential_network", true))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!terminal.compareAndSet(false, true)) return
                        if (!response.isSuccessful) {
                            val boundedError = OpenAiApiFailureClassifier.readBoundedErrorBody(
                                response.body?.byteStream(),
                            )
                            callback.onFailure(classifyStatus(response.code, boundedError))
                            return
                        }
                        val body = try {
                            readBounded(response, MAX_RESPONSE_BYTES)
                        } catch (_: IOException) {
                            callback.onFailure(
                                LiveVoiceFailure("realtime_credential_response_invalid", true),
                            )
                            return
                        }
                        val parsed = parseCredential(body)
                        if (parsed == null) {
                            callback.onFailure(
                                LiveVoiceFailure("realtime_credential_response_invalid", false),
                            )
                        } else {
                            callback.onCredential(parsed)
                        }
                    }
                }
            },
        )
        return LiveVoiceCancellation {
            if (terminal.compareAndSet(false, true)) call.cancel()
        }
    }

    private fun parseCredential(raw: String): RealtimeEphemeralCredential? {
        return try {
            val root = JSONObject(raw)
            val value = root.optString("value").trim()
            if (!validEphemeral(value)) return null
            val expiresAt = root.optLong("expires_at", -1).takeIf { it > 0 }
            RealtimeEphemeralCredential.of(value, expiresAt)
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun validKey(value: String?): Boolean =
        value != null &&
            value.length in 16..RealtimeEphemeralCredential.MAX_CREDENTIAL_CHARACTERS &&
            value.none(Char::isWhitespace)

    private fun validEphemeral(value: String): Boolean =
        value.length in 16..RealtimeEphemeralCredential.MAX_CREDENTIAL_CHARACTERS &&
            value.none(Char::isWhitespace)

    private fun classifyStatus(status: Int, boundedErrorBody: String?): LiveVoiceFailure {
        if (status == 409) {
            return LiveVoiceFailure("realtime_credential_conflict", retryable = true)
        }
        val failure = OpenAiApiFailureClassifier.classifyHttp(status, boundedErrorBody)
        return LiveVoiceFailure("realtime_${failure.code}", failure.retryable)
    }

    private fun readBounded(response: Response, maximumBytes: Int): String {
        val body = response.body ?: throw IOException("missing_response_body")
        val length = body.contentLength()
        if (length > maximumBytes) throw IOException("response_too_large")
        val input = body.byteStream()
        val output = ByteArrayOutputStream(minOf(maximumBytes, 8_192))
        val buffer = ByteArray(4_096)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maximumBytes) throw IOException("response_too_large")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val CLIENT_SECRET_ENDPOINT =
            "https://api.openai.com/v1/realtime/client_secrets"
        const val MAX_RESPONSE_BYTES = 64 * 1_024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
