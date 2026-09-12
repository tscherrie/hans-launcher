package ai.hans.standard

import ai.hans.standard.runtime.AppServerSessionContract
import ai.hans.standard.runtime.BinderFrameChunker
import ai.hans.standard.runtime.CodexAssistantProfile
import ai.hans.standard.runtime.IAppServerSessionCallback
import ai.hans.standard.runtime.IRuntimeService
import ai.hans.standard.runtime.RuntimeService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PersistentAppServerSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun persistentSessionReadsAccountAndStopsCleanlyWithoutLoginOrNetwork() {
        val serviceLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(1)
        val accountLatch = CountDownLatch(1)
        val stoppedLatch = CountDownLatch(1)
        val generation = AtomicLong(0L)
        val runtimePid = AtomicLong(0L)
        val readyObserved = AtomicBoolean(false)
        val failure = AtomicReference<String>("")

        fun failFromCallback(message: String) {
            failure.compareAndSet("", message)
            readyLatch.countDown()
            accountLatch.countDown()
            stoppedLatch.countDown()
        }

        val eventOrder = EventOrder(::failFromCallback)
        val responseFrames = ResponseFrames(accountLatch, ::failFromCallback)
        var runtime: IRuntimeService? = null

        val callback = object : IAppServerSessionCallback.Stub() {
            override fun onSessionState(
                operationId: Long,
                callbackGeneration: Long,
                eventSequence: Long,
                state: Int,
                callbackRuntimePid: Int,
                detail: String,
            ) {
                if (callbackGeneration == 0L && eventSequence == 0L) {
                    val category = if (state == AppServerSessionContract.STATE_FAILED) {
                        "RuntimeService was not quiescent before the isolated session test"
                    } else {
                        "Unexpected standalone RuntimeService callback"
                    }
                    failFromCallback(
                        "$category: ${AppServerSessionContract.boundedDetail(detail)}",
                    )
                    return
                }
                if (!eventOrder.accept(eventSequence)) return
                if (callbackGeneration > 0) generation.compareAndSet(0L, callbackGeneration)
                when (state) {
                    AppServerSessionContract.STATE_READY -> {
                        if (detail != "Ready; ${CodexAssistantProfile.ID} verified") {
                            failFromCallback("READY did not include a verified personal assistant profile")
                            return
                        }
                        runtimePid.set(callbackRuntimePid.toLong())
                        readyObserved.set(true)
                        readyLatch.countDown()
                    }
                    AppServerSessionContract.STATE_STOPPED -> stoppedLatch.countDown()
                    AppServerSessionContract.STATE_EXITED,
                    AppServerSessionContract.STATE_FAILED -> failFromCallback(
                        "App Server session failed: " +
                            AppServerSessionContract.boundedDetail(detail),
                    )
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
                if (!readyObserved.get()) {
                    failFromCallback("Server frame arrived before READY")
                    return
                }
                if (callbackGeneration != generation.get()) {
                    failFromCallback("Server frame used an unexpected session generation")
                    return
                }
                if (chunkIndex == 0 && !eventOrder.accept(eventSequence)) return
                responseFrames.accept(
                    eventSequence,
                    chunkIndex,
                    chunkCount,
                    totalBytes,
                    payload,
                )
            }

            override fun onTransportNotice(
                callbackGeneration: Long,
                eventSequence: Long,
                code: Int,
                relatedSequence: Long,
                detail: String,
            ) {
                if (!eventOrder.accept(eventSequence)) return
                failFromCallback(
                    "Unexpected transport notice $code: " +
                        AppServerSessionContract.boundedDetail(detail),
                )
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                runtime = IRuntimeService.Stub.asInterface(binder)
                serviceLatch.countDown()
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
            assertTrue("runtime did not bind", serviceLatch.await(10, TimeUnit.SECONDS))
            assertEquals(
                AppServerSessionContract.PROTOCOL_VERSION,
                runtime!!.sessionProtocolVersion,
            )
            runtime!!.startAppServerSession(100L, callback)
            assertTrue("session did not become ready", readyLatch.await(45, TimeUnit.SECONDS))
            assertEquals(
                "Persistent App Server test requires a quiescent RuntimeService and " +
                    "its own instrumentation phase. ${failure.get()}",
                "",
                failure.get(),
            )
            assertTrue(generation.get() > 0)
            assertTrue(runtimePid.get() > 0)
            assertNotEquals(android.os.Process.myPid().toLong(), runtimePid.get())

            val request = """{"id":7001,"method":"account/read","params":{}}"""
                .toByteArray()
            BinderFrameChunker.chunks(request).forEach { chunk ->
                runtime!!.sendAppServerFrameChunk(
                    generation.get(),
                    1L,
                    chunk.index,
                    chunk.count,
                    chunk.totalBytes,
                    chunk.payload,
                )
            }
            assertTrue("account/read did not answer", accountLatch.await(30, TimeUnit.SECONDS))
            assertEquals("App Server callback failed: ${failure.get()}", "", failure.get())

            runtime!!.stopAppServerSession(101L, generation.get())
            assertTrue("session did not stop", stoppedLatch.await(20, TimeUnit.SECONDS))
            assertEquals("App Server callback failed: ${failure.get()}", "", failure.get())
        } finally {
            runCatching {
                if (generation.get() > 0) {
                    runtime?.stopAppServerSession(102L, generation.get())
                }
            }
            context.unbindService(connection)
        }
    }

    private class EventOrder(private val onFailure: (String) -> Unit) {
        private var last = 0L

        @Synchronized
        fun accept(sequence: Long): Boolean {
            val expected = last + 1
            if (sequence != expected) {
                onFailure("Callback event sequence expected $expected but received $sequence")
                return false
            }
            last = sequence
            return true
        }
    }

    private class ResponseFrames(
        private val accountLatch: CountDownLatch,
        private val onFailure: (String) -> Unit,
    ) {
        private var sequence = Long.MIN_VALUE
        private var chunkCount = 0
        private var totalBytes = 0
        private var nextChunk = 0
        private var output = ByteArrayOutputStream()

        @Synchronized
        fun accept(
            eventSequence: Long,
            chunkIndex: Int,
            callbackChunkCount: Int,
            callbackTotalBytes: Int,
            payload: ByteArray,
        ) {
            if (chunkIndex == 0) {
                if (callbackChunkCount <= 0 || callbackTotalBytes <= 0) {
                    onFailure("Server frame declared invalid chunk metadata")
                    return
                }
                sequence = eventSequence
                chunkCount = callbackChunkCount
                totalBytes = callbackTotalBytes
                nextChunk = 0
                output = ByteArrayOutputStream()
            }
            if (
                sequence != eventSequence || chunkCount != callbackChunkCount ||
                totalBytes != callbackTotalBytes || nextChunk != chunkIndex
            ) {
                onFailure("Server frame chunks were delivered with inconsistent metadata")
                return
            }
            output.write(payload)
            nextChunk += 1
            if (nextChunk == chunkCount) {
                val bytes = output.toByteArray()
                if (totalBytes != bytes.size) {
                    onFailure("Server frame byte count did not match its declaration")
                    return
                }
                val json = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
                    .getOrElse {
                        onFailure("Server frame was not valid JSON")
                        return
                    }
                if (json.optString("id").startsWith("hans:assistant-profile:")) {
                    onFailure("Raw profile configuration leaked across Binder")
                    return
                }
                if (json.optLong("id", Long.MIN_VALUE) == 7001L) {
                    accountLatch.countDown()
                }
            }
        }
    }
}
