package ai.hans.standard

import ai.hans.standard.runtime.AppServerSessionContract
import ai.hans.standard.runtime.BinderFrameChunker
import ai.hans.standard.runtime.IAppServerSessionCallback
import ai.hans.standard.runtime.IRuntimeService
import ai.hans.standard.runtime.RuntimeService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device network gate for the root-free Android DNS/CA/CONNECT adapter.
 * It obtains, validates and immediately abandons a device-code login; neither
 * the code nor the remote error text is logged or included in assertion text.
 */
@RunWith(AndroidJUnit4::class)
class ChatGptDeviceCodeNetworkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun embeddedAppServerObtainsBoundedHttpsChatGptDeviceCode() {
        val bound = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val responseReceived = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val generation = AtomicLong(0)
        val failureCode = AtomicReference("")
        val response = AtomicReference<JSONObject>()
        val frames = LoginResponseFrames(responseReceived, response, failureCode)
        var runtime: IRuntimeService? = null

        val callback = object : IAppServerSessionCallback.Stub() {
            override fun onSessionState(
                operationId: Long,
                callbackGeneration: Long,
                eventSequence: Long,
                state: Int,
                runtimePid: Int,
                detail: String,
            ) {
                if (callbackGeneration > 0) generation.compareAndSet(0, callbackGeneration)
                when (state) {
                    AppServerSessionContract.STATE_READY -> ready.countDown()
                    AppServerSessionContract.STATE_STOPPED -> stopped.countDown()
                    AppServerSessionContract.STATE_FAILED -> {
                        failureCode.compareAndSet(
                            "",
                            runtimeFailureCode(callbackGeneration, eventSequence, detail),
                        )
                        ready.countDown()
                        responseReceived.countDown()
                        stopped.countDown()
                    }
                }
            }

            override fun onFrameChunk(
                callbackGeneration: Long,
                eventSequence: Long,
                chunkIndex: Int,
                chunkCount: Int,
                totalBytes: Int,
                payload: ByteArray,
            ) {
                if (callbackGeneration == generation.get()) {
                    frames.accept(eventSequence, chunkIndex, chunkCount, totalBytes, payload)
                }
            }

            override fun onTransportNotice(
                callbackGeneration: Long,
                eventSequence: Long,
                code: Int,
                relatedSequence: Long,
                detail: String,
            ) {
                failureCode.compareAndSet("", "transport_notice_$code")
                responseReceived.countDown()
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                runtime = IRuntimeService.Stub.asInterface(binder)
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        assertTrue(
            context.bindService(
                Intent(context, RuntimeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )
        try {
            assertTrue("runtime bind timed out", bound.await(10, TimeUnit.SECONDS))
            runtime!!.startAppServerSession(8_001, callback)
            assertTrue("runtime readiness timed out", ready.await(45, TimeUnit.SECONDS))
            assertEquals(
                "Device-code gate requires a quiescent RuntimeService and its own " +
                    "instrumentation phase. ${failureCode.get()}",
                "",
                failureCode.get(),
            )

            val request = JSONObject()
                .put("id", LOGIN_REQUEST_ID)
                .put("method", "account/login/start")
                .put("params", JSONObject().put("type", "chatgptDeviceCode"))
                .toString()
                .toByteArray(Charsets.UTF_8)
            BinderFrameChunker.chunks(request).forEach { chunk ->
                runtime!!.sendAppServerFrameChunk(
                    generation.get(),
                    1,
                    chunk.index,
                    chunk.count,
                    chunk.totalBytes,
                    chunk.payload,
                )
            }

            assertTrue("device-code response timed out", responseReceived.await(60, TimeUnit.SECONDS))
            assertEquals("", failureCode.get())
            val result = response.get()?.optJSONObject("result")
            assertNotNull("device-code result missing", result)
            assertEquals("chatgptDeviceCode", result!!.optString("type"))
            assertTrue(result.optString("loginId").length in 1..512)
            assertTrue(result.optString("userCode").length in 4..128)
            val verification = Uri.parse(result.optString("verificationUrl"))
            assertEquals("https", verification.scheme)
            assertTrue(!verification.host.isNullOrBlank())

            runtime!!.stopAppServerSession(8_002, generation.get())
            assertTrue("runtime stop timed out", stopped.await(20, TimeUnit.SECONDS))
        } finally {
            runCatching {
                if (generation.get() > 0) {
                    runtime?.stopAppServerSession(8_003, generation.get())
                }
            }
            context.unbindService(connection)
        }
    }

    private class LoginResponseFrames(
        private val responseReceived: CountDownLatch,
        private val response: AtomicReference<JSONObject>,
        private val failureCode: AtomicReference<String>,
    ) {
        private var sequence = Long.MIN_VALUE
        private var expectedChunks = 0
        private var expectedBytes = 0
        private var nextChunk = 0
        private var output = ByteArrayOutputStream()

        @Synchronized
        fun accept(
            eventSequence: Long,
            chunkIndex: Int,
            chunkCount: Int,
            totalBytes: Int,
            payload: ByteArray,
        ) {
            if (chunkIndex == 0) {
                sequence = eventSequence
                expectedChunks = chunkCount
                expectedBytes = totalBytes
                nextChunk = 0
                output = ByteArrayOutputStream(totalBytes)
            }
            if (
                eventSequence != sequence || chunkCount != expectedChunks ||
                totalBytes != expectedBytes || chunkIndex != nextChunk
            ) {
                failureCode.compareAndSet("", "invalid_frame_sequence")
                responseReceived.countDown()
                return
            }
            output.write(payload)
            nextChunk += 1
            if (nextChunk != expectedChunks) return
            val complete = output.toByteArray()
            if (complete.size != expectedBytes) {
                failureCode.compareAndSet("", "invalid_frame_size")
                responseReceived.countDown()
                return
            }
            val json = runCatching { JSONObject(complete.toString(Charsets.UTF_8)) }
                .getOrElse {
                    failureCode.compareAndSet("", "invalid_json_response")
                    responseReceived.countDown()
                    return
                }
            if (json.optLong("id", Long.MIN_VALUE) != LOGIN_REQUEST_ID) return
            if (json.has("error")) {
                failureCode.compareAndSet("", "remote_login_error")
            } else {
                response.set(json)
            }
            responseReceived.countDown()
        }
    }

    private companion object {
        const val LOGIN_REQUEST_ID = 8_041L

        fun runtimeFailureCode(generation: Long, sequence: Long, detail: String): String {
            val category = if (generation == 0L && sequence == 0L) {
                "runtime_not_quiescent"
            } else {
                "runtime_failed"
            }
            val boundedDetail = detail
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(160)
                .ifEmpty { "no detail" }
            return "$category: $boundedDetail"
        }
    }
}
