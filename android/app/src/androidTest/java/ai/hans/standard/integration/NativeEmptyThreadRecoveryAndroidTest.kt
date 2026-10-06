package ai.hans.standard.integration

import ai.hans.standard.codex.AppServerRequests
import ai.hans.standard.codex.DispatchOptions
import ai.hans.standard.codex.EncodedRequest
import ai.hans.standard.codex.RemoteError
import ai.hans.standard.codex.RequestId
import ai.hans.standard.codex.ResponseCorrelator
import ai.hans.standard.codex.CorrelatedResponse
import ai.hans.standard.codex.ThreadMaterializeResult
import ai.hans.standard.runtime.AppServerSessionContract
import ai.hans.standard.runtime.CodexRuntimeContract
import ai.hans.standard.runtime.IRuntimeService
import ai.hans.standard.runtime.RuntimeService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real packaged AppServer + production Binder/restart path, disposable emulators only. Run in its
 * own quiescent instrumentation phase with -e runNativeEmptyThreadRecovery true. Requires a fresh
 * signed-out app home; never run on MP01. For a hard no-external-network claim the runner must also
 * isolate the emulator's network: RuntimeService still has its normal network stack/prewarm code.
 *
 * No user/model turn, login, credentials, real tools, activity, microphone or speaker is used.
 * The only section write is null -> null on this test's newly minted, verified ungrouped thread.
 * No file or task is deleted. Disposable app-home artifacts remain for the runner to dispose of.
 * Restart means the production stop/restart path, NOT proof of graceful EOF-only termination.
 */
@RunWith(AndroidJUnit4::class)
class NativeEmptyThreadRecoveryAndroidTest {
    @Test
    fun newPaginatedThreadResumesAfterSectionMaterializationWithoutUserInput() {
        Fixture.open().use { fixture ->
            val thread = fixture.startThread()
            fixture.enableMemory(thread)
            fixture.materializeNewUngroupedThread(thread)
            fixture.restart()

            val resumed = fixture.requireSuccess(fixture.resume(thread))
            fixture.assertResumedEmptyThread(resumed, thread)
            fixture.enableMemory(thread)
            fixture.assertNoUserWork()
        }
    }

    @Test
    fun newPaginatedThreadResumesAfterCompleteReadWithoutUserInput() {
        Fixture.open().use { fixture ->
            val thread = fixture.startThread()
            fixture.enableMemory(thread)
            // Pinned native live paginated read(includeTurns=true) forces persistence first.
            fixture.assertCompleteReadIsEmpty(thread)
            fixture.restart()

            val resumed = fixture.requireSuccess(fixture.resume(thread))
            fixture.assertResumedEmptyThread(resumed, thread)
            fixture.enableMemory(thread)
            fixture.assertNoUserWork()
        }
    }

    /**
     * Diagnostic comparison, not a release acceptance gate. A future upstream fix is accepted;
     * an unknown error is not. The fixed category is logged without the native message or IDs.
     * Select this method explicitly when reproducing the pinned empty-paginated-history issue.
     */
    @Test
    fun defaultPaginatedWithoutMaterializationReportsBoundedResumeOutcome() {
        Fixture.open().use { fixture ->
            val thread = fixture.startThread()
            fixture.enableMemory(thread)
            fixture.restart()

            val reply = fixture.resume(thread)
            if (reply.has("error")) {
                val error = reply.getJSONObject("error")
                assertEquals(-32600L, error.getLong("code"))
                val category = ThreadBootstrapRemoteFailureClassifier.classify(
                    RemoteError(error.getLong("code"), error.getString("message"), null),
                    thread.id,
                )
                assertTrue("Unexpected default-paginated native rejection category", category in setOf(
                    ThreadBootstrapRemoteFailureReason.ROLLOUT_MISSING,
                    ThreadBootstrapRemoteFailureReason.ROLLOUT_PATH_INVALID,
                ))
                Log.i(TAG, "default_paginated_resume=" + category.diagnosticName)
            } else {
                fixture.assertResumedEmptyThread(fixture.requireSuccess(reply), thread)
                Log.i(TAG, "default_paginated_resume=succeeded")
            }
            fixture.assertNoUserWork()
        }
    }

    private data class CreatedThread(val id: String, val historyMode: String)

    private sealed interface Event {
        data class State(val generation: Long, val state: Int) : Event
        data class Frame(val generation: Long, val value: JSONObject) : Event
        data object Failure : Event
    }

    /**
     * Keep the Android service alive between test methods. Its onDestroy synchronously shuts down
     * the code-mode host and proxy; immediately rebinding while that teardown runs can time out.
     * Every fixture still stops its real native AppServer and proves STOPPED before releasing it.
     */
    private class ServiceBinding(private val context: Context) : AutoCloseable {
        private val connected = CountDownLatch(1)
        private val runtime = AtomicReference<IRuntimeService>()
        private var bindingOwned = false
        private val activeFailure = AtomicReference<(() -> Unit)?>()
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                runtime.set(IRuntimeService.Stub.asInterface(binder))
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                runtime.set(null)
                activeFailure.get()?.invoke()
            }

            override fun onBindingDied(name: ComponentName?) {
                runtime.set(null)
                connected.countDown()
                activeFailure.get()?.invoke()
            }

            override fun onNullBinding(name: ComponentName?) {
                connected.countDown()
            }
        }

        fun connect() {
            bindingOwned = context.bindService(Intent(context, RuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue("Runtime service did not bind", bindingOwned)
            assertTrue("Runtime service binding timed out", connected.await(30, TimeUnit.SECONDS))
            checkNotNull(runtime.get()) { "Runtime service binding unavailable" }
        }

        fun acquire(onFailure: () -> Unit): IRuntimeService {
            val connectedRuntime = checkNotNull(runtime.get()) { "Runtime service disconnected" }
            check(activeFailure.compareAndSet(null, onFailure)) { "Native fixtures must not overlap" }
            return connectedRuntime
        }

        fun release() {
            activeFailure.set(null)
        }

        override fun close() {
            check(activeFailure.get() == null) { "Native fixture still owns the service" }
            if (bindingOwned) {
                bindingOwned = false
                context.unbindService(connection)
            }
        }
    }

    private class Fixture private constructor(
        private val context: Context,
        private val binding: ServiceBinding,
    ) : AutoCloseable {
        private val events = LinkedBlockingQueue<Event>()
        private val latestGeneration = AtomicLong()
        private val unexpectedUserWork = AtomicInteger()
        private var bindingLeaseOwned = false
        private var readyGeneration = 0L
        private var nextOperation = 80_000L
        private var nextRequest = 90_000L
        private var lastRpcMethod = "none"
        private var lastRpcThreadId: String? = null
        private lateinit var transport: BinderSessionRuntimeTransport
        private lateinit var workspace: File
        private val createdThreads = mutableSetOf<String>()

        private val listener = object : RuntimeSessionListener {
            override fun onSessionState(operationId: Long, generation: Long, eventSequence: Long, state: Int) {
                if (generation > 0) latestGeneration.set(generation)
                events.offer(Event.State(generation, state))
            }

            override fun onServerFrame(generation: Long, eventSequence: Long, frame: ByteArray) {
                val json = runCatching { JSONObject(frame.toString(Charsets.UTF_8)) }.getOrNull()
                if (json == null) {
                    events.offer(Event.Failure)
                    return
                }
                val method = json.optString("method")
                if (method == "turn/started" || method.startsWith("thread/realtime/") ||
                    method == "account/login/completed" || method == "item/tool/call") {
                    unexpectedUserWork.incrementAndGet()
                    events.offer(Event.Failure)
                }
                events.offer(Event.Frame(generation, json))
            }

            override fun onTransportNotice(generation: Long, eventSequence: Long, code: Int, relatedSequence: Long) {
                events.offer(Event.Failure)
            }

            override fun onTransportProtocolFailure(failure: TransportProtocolFailure) {
                events.offer(Event.Failure)
            }
        }

        private fun connect() {
            workspace = Files.createTempDirectory(context.cacheDir.toPath(), "native-empty-thread-").toFile()
            val runtime = binding.acquire { events.offer(Event.Failure) }
            bindingLeaseOwned = true
            transport = BinderSessionRuntimeTransport(runtime)
            assertEquals(AppServerSessionContract.PROTOCOL_VERSION, transport.protocolVersion)
            transport.start(nextOperation++, listener)
            readyGeneration = awaitReadyAfter(0L)
            assertSignedOut()
        }

        fun startThread(): CreatedThread {
            // Use the production encoder and leave historyMode absent to exercise native default.
            val request = AppServerRequests.threadStart(
                id = RequestId.Number(nextRequest++),
                options = DispatchOptions.DEFAULT.copy(cwd = workspace.canonicalPath),
                ephemeral = false,
            )
            val params = JSONObject(request.json).getJSONObject("params")
            assertFalse("Default control must not pin a history mode", params.has("historyMode"))
            // The fixture never configures a phone MCP bridge. Setting only enabled=false would
            // create an invalid transport entry instead of disabling an existing one.
            assertFalse(params.getJSONObject("config").has("mcp_servers.hans_phone.enabled"))
            val result = requireSuccess(rpc(request))
            val thread = result.getJSONObject("thread")
            val id = thread.getString("id")
            assertTrue("Native start must identify a new task", id.isNotBlank())
            assertEquals("paginated", thread.getString("historyMode"))
            assertFalse(thread.getBoolean("ephemeral"))
            assertTrue("Only a new ungrouped task is eligible for materialization",
                thread.has("section") && thread.isNull("section"))
            // These are start-time observations, not proof of durable persisted emptiness.
            assertEquals(0, thread.getJSONArray("turns").length())
            assertEquals("", thread.getString("preview"))
            assertTrue(createdThreads.add(id))
            return CreatedThread(id, thread.getString("historyMode"))
        }

        fun enableMemory(thread: CreatedThread) {
            assertTrue(createdThreads.contains(thread.id))
            val result = requireSuccess(rpc(AppServerRequests.threadMemoryModeSetEnabled(
                id = RequestId.Number(nextRequest++), threadId = thread.id,
            )))
            assertEquals(0, result.length())
        }

        fun materializeNewUngroupedThread(thread: CreatedThread) {
            assertTrue("Never place an existing task", createdThreads.contains(thread.id))
            // Public pinned RPC: core/thread_manager.rs forces materialize + flush before SQL.
            val result = requireSuccess(rpc(JSONObject()
                .put("id", nextRequest++)
                .put("method", "thread/section/move")
                .put("params", JSONObject().put("threadId", thread.id).put("sectionId", JSONObject.NULL))))
            assertEquals(0, result.length())
        }

        fun restart() {
            val previous = readyGeneration
            transport.restart(nextOperation++, previous)
            var observedStopped = false
            val next = awaitEvent(45) { event ->
                if (event is Event.State && event.generation == previous && event.state == AppServerSessionContract.STATE_STOPPED) {
                    observedStopped = true
                }
                if (event is Event.State && event.generation > previous && event.state == AppServerSessionContract.STATE_READY) {
                    assertTrue("Replacement must follow the old process stop receipt", observedStopped)
                    event
                } else null
            }
            readyGeneration = next.generation
            assertSignedOut()
        }

        fun resume(thread: CreatedThread): JSONObject = rpc(AppServerRequests.threadResume(
            id = RequestId.Number(nextRequest++),
            threadId = thread.id,
            excludeTurns = true,
        ))

        fun assertResumedEmptyThread(result: JSONObject, expected: CreatedThread) {
            val thread = result.getJSONObject("thread")
            assertEquals(expected.id, thread.getString("id"))
            assertEquals(expected.historyMode, thread.getString("historyMode"))
            assertTrue(thread.isNull("section"))
            assertFalse(thread.getBoolean("ephemeral"))
            assertEquals(0, thread.getJSONArray("turns").length())
            assertEquals("", thread.getString("preview"))
            val page = result.getJSONObject("initialTurnsPage")
            assertEquals(0, page.getJSONArray("data").length())
            assertTrue(page.isNull("nextCursor"))
            // Explicit complete-history read, not only a metadata turns[] placeholder.
            assertCompleteReadIsEmpty(expected)
            assertNoUserWork()
        }

        fun assertCompleteReadIsEmpty(expected: CreatedThread) {
            assertTrue(createdThreads.contains(expected.id))
            val request = AppServerRequests.materializeFreshThread(RequestId.Number(nextRequest++), expected.id)
            val reply = rpc(request)
            val result = requireSuccess(reply)
            // Exercise the actual bounded production decoder, not only a permissive fixture read.
            val correlator = ResponseCorrelator()
            correlator.register(request)
            val receipt = correlator.accept(reply.toString()) as CorrelatedResponse.Success
            assertEquals(ThreadMaterializeResult(expected.id), receipt.result)
            val thread = result.getJSONObject("thread")
            assertEquals(expected.id, thread.getString("id"))
            assertEquals(expected.historyMode, thread.getString("historyMode"))
            assertTrue(thread.has("section") && thread.isNull("section"))
            assertEquals(0, thread.getJSONArray("turns").length())
            assertEquals("", thread.getString("preview"))
            assertNoUserWork()
        }

        fun assertNoUserWork() = assertEquals("No model/user/realtime/tool work is allowed", 0, unexpectedUserWork.get())

        fun requireSuccess(reply: JSONObject): JSONObject {
            if (reply.has("error")) {
                val error = reply.getJSONObject("error")
                val code = error.optLong("code")
                val message = error.optString("message")
                val category = ThreadBootstrapRemoteFailureClassifier.classify(
                    RemoteError(code, message, null), lastRpcThreadId,
                )
                val detail = if (category == ThreadBootstrapRemoteFailureReason.CONFIGURATION_LOAD &&
                    message.length <= 16_384 && message.contains("invalid transport") &&
                    message.contains("mcp_servers.hans_phone")) "phone_mcp_transport_incomplete" else "none"
                // Emit only fixed vocabulary and an allowlisted method, never native text/data.
                throw AssertionError("Native fixture RPC rejected (method=$lastRpcMethod code=$code " +
                    "category=${category.diagnosticName} detail=$detail)")
            }
            return reply.getJSONObject("result")
        }

        private fun assertSignedOut() {
            val reply = requireSuccess(rpc(JSONObject()
                .put("id", nextRequest++)
                .put("method", "account/read")
                .put("params", JSONObject())))
            assertTrue("Fixture requires a disposable signed-out app home", reply.isNull("account"))
        }

        private fun rpc(request: EncodedRequest): JSONObject = rpc(JSONObject(request.json))

        private fun rpc(request: JSONObject): JSONObject {
            val method = request.getString("method")
            check(method in ALLOWED_METHODS) { "Non-fixture RPC blocked" }
            lastRpcMethod = method
            lastRpcThreadId = request.optJSONObject("params")?.optString("threadId")?.takeIf { it in createdThreads }
            val id = request.getLong("id")
            val generation = readyGeneration
            transport.sendFrame(generation, request.toString().toByteArray(Charsets.UTF_8))
            return awaitEvent(30) { event ->
                if (event is Event.Frame && event.generation == generation && event.value.optLong("id", -1) == id) {
                    event.value
                } else null
            }
        }

        private fun awaitReadyAfter(previous: Long): Long = awaitEvent(45) { event ->
            if (event is Event.State && event.generation > previous && event.state == AppServerSessionContract.STATE_READY) {
                event.generation
            } else null
        }

        private fun <T : Any> awaitEvent(timeoutSeconds: Long, select: (Event) -> T?): T {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw AssertionError("Native fixture response timeout")
                val event = events.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("Native fixture response timeout")
                if (event is Event.Failure || (event is Event.State && event.state in setOf(
                        AppServerSessionContract.STATE_FAILED, AppServerSessionContract.STATE_EXITED))) {
                    throw AssertionError("Native fixture runtime or transport failed")
                }
                select(event)?.let { return it }
            }
        }

        override fun close() {
            try {
                if (::transport.isInitialized && latestGeneration.get() > 0) {
                    val generation = latestGeneration.get()
                    transport.stop(nextOperation++, generation)
                    awaitEvent(20) { event ->
                        if (event is Event.State && event.generation == generation &&
                            event.state == AppServerSessionContract.STATE_STOPPED) true else null
                    }
                }
            } finally {
                if (bindingLeaseOwned) {
                    bindingLeaseOwned = false
                    binding.release()
                }
            }
        }

        companion object {
            fun open(): Fixture {
                assertTrue("Native recovery fixture is restricted to disposable emulators", Build.HARDWARE in setOf("ranchu", "goldfish"))
                assertEquals("Explicit native recovery fixture opt-in required", "true",
                    InstrumentationRegistry.getArguments().getString("runNativeEmptyThreadRecovery"))
                val context = ApplicationProvider.getApplicationContext<Context>()
                val home = CodexRuntimeContract.directories(context.noBackupFilesDir, context.cacheDir).codexHomeDirectory
                assertFalse("Fixture must not read an existing account", File(home, "auth.json").exists())
                assertFalse("Fixture must not touch a selected user conversation", context
                    .getSharedPreferences("hans_codex_session_v1", Context.MODE_PRIVATE).contains("thread_id"))
                val fixture = Fixture(context, checkNotNull(serviceBinding))
                try {
                    fixture.connect()
                    return fixture
                } catch (failure: Throwable) {
                    runCatching { fixture.close() }.onFailure(failure::addSuppressed)
                    throw failure
                }
            }

            private val ALLOWED_METHODS = setOf(
                "account/read", "thread/start", "thread/memoryMode/set", "thread/section/move", "thread/resume", "thread/read",
            )
        }
    }

    companion object {
        private const val TAG = "HansNativeEmptyThread"
        private var serviceBinding: ServiceBinding? = null

        @JvmStatic
        @BeforeClass
        fun bindRuntimeForClass() {
            assertTrue("Native recovery fixture is restricted to disposable emulators", Build.HARDWARE in setOf("ranchu", "goldfish"))
            assertEquals("Explicit native recovery fixture opt-in required", "true",
                InstrumentationRegistry.getArguments().getString("runNativeEmptyThreadRecovery"))
            val context = ApplicationProvider.getApplicationContext<Context>()
            val home = CodexRuntimeContract.directories(context.noBackupFilesDir, context.cacheDir).codexHomeDirectory
            assertFalse("Fixture must not read an existing account", File(home, "auth.json").exists())
            assertFalse("Fixture must not touch a selected user conversation", context
                .getSharedPreferences("hans_codex_session_v1", Context.MODE_PRIVATE).contains("thread_id"))
            val binding = ServiceBinding(context)
            try {
                binding.connect()
                serviceBinding = binding
            } catch (failure: Throwable) {
                runCatching { binding.close() }.onFailure(failure::addSuppressed)
                throw failure
            }
        }

        @JvmStatic
        @AfterClass
        fun unbindRuntimeAfterClass() {
            val binding = serviceBinding
            serviceBinding = null
            binding?.close()
        }
    }
}
