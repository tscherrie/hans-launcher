package ai.hans.standard.remotecontrol

import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.integration.BinderSessionRuntimeTransport
import ai.hans.standard.integration.RuntimeSessionListener
import ai.hans.standard.integration.TransportProtocolFailure
import ai.hans.standard.runtime.AppServerSessionContract
import ai.hans.standard.runtime.IRuntimeService
import ai.hans.standard.runtime.RuntimeService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real packaged native MCP discovery; no model turn, account, pairing or phone action. */
@RunWith(AndroidJUnit4::class)
class PhoneToolsNativeMcpAndroidTest {
    @Test
    fun nativeRuntimeDiscoversPhoneToolsWithoutModelOrAccount() {
        assertTrue("Native discovery is restricted to disposable Android emulators",
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val invocations = AtomicInteger()
        val specs = listOf(DynamicToolNamespaceSpec(
            "hans_native_probe",
            "Synthetic local discovery only",
            listOf(DynamicToolFunctionSpec(
                "inspect",
                "Harmless native MCP discovery contract",
                """{"type":"object","properties":{"label":{"type":"string"}},"additionalProperties":false}""",
            )),
        ))
        PhoneToolsMcpServer(specs = { specs }, execute = { _, _, completion ->
            invocations.incrementAndGet()
            completion(DynamicToolExecutionResult("""{"unexpectedExecution":true}""", false))
            object : DynamicToolExecutionHandle {
                override fun cancel() = DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT
            }
        }).use { server ->
            server.start()
            val bound = CountDownLatch(1)
            val ready = CountDownLatch(1)
            val response = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            val generation = AtomicLong()
            val failure = AtomicReference<String>()
            val status = AtomicReference<JSONObject>()
            val runtime = AtomicReference<IRuntimeService>()
            fun fail(message: String) {
                failure.compareAndSet(null, message)
                ready.countDown()
                response.countDown()
                stopped.countDown()
            }
            val listener = object : RuntimeSessionListener {
                override fun onSessionState(operationId: Long, callbackGeneration: Long, eventSequence: Long, state: Int) {
                    if (callbackGeneration > 0) generation.compareAndSet(0, callbackGeneration)
                    when (state) {
                        AppServerSessionContract.STATE_READY -> ready.countDown()
                        AppServerSessionContract.STATE_STOPPED -> stopped.countDown()
                        AppServerSessionContract.STATE_FAILED,
                        AppServerSessionContract.STATE_EXITED -> fail("Native runtime lifecycle failed ($state)")
                    }
                }

                override fun onServerFrame(generation: Long, eventSequence: Long, frame: ByteArray) {
                    val json = runCatching { JSONObject(frame.toString(Charsets.UTF_8)) }.getOrNull()
                    if (json == null) {
                        fail("Native runtime returned malformed JSON")
                    } else if (json.optString("id") == "hans-native-mcp-probe") {
                        status.set(json)
                        response.countDown()
                    }
                }

                override fun onTransportNotice(generation: Long, eventSequence: Long, code: Int, relatedSequence: Long) {
                    fail("Native transport rejected discovery ($code)")
                }

                override fun onTransportProtocolFailure(failure: TransportProtocolFailure) {
                    fail("Native transport sequence failed")
                }
            }
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    runtime.set(IRuntimeService.Stub.asInterface(service))
                    bound.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
            assertTrue(context.bindService(Intent(context, RuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
            var transport: BinderSessionRuntimeTransport? = null
            try {
                assertTrue("Native service did not bind", bound.await(10, TimeUnit.SECONDS))
                transport = BinderSessionRuntimeTransport(checkNotNull(runtime.get()), server.config)
                assertEquals(AppServerSessionContract.PROTOCOL_VERSION, transport.protocolVersion)
                transport.start(7000L, listener)
                assertTrue("Native runtime did not become ready", ready.await(45, TimeUnit.SECONDS))
                assertEquals("Native startup failed", null, failure.get())
                assertTrue(generation.get() > 0)
                transport.sendFrame(generation.get(), JSONObject()
                    .put("id", "hans-native-mcp-probe")
                    .put("method", "mcpServerStatus/list")
                    .put("params", JSONObject().put("detail", "toolsAndAuthOnly").put("limit", 100))
                    .toString().toByteArray(Charsets.UTF_8))
                assertTrue("Native MCP inventory did not answer", response.await(45, TimeUnit.SECONDS))
                assertEquals("Native inventory transport failed", null, failure.get())
                val envelope = checkNotNull(status.get())
                assertFalse("Native App Server rejected configured MCP discovery", envelope.has("error"))
                val result = envelope.getJSONObject("result")
                val servers = result.getJSONArray("data")
                val phone = (0 until servers.length()).map(servers::getJSONObject)
                    .single { it.getString("name") == "hans_phone" }
                assertEquals("bearerToken", phone.getString("authStatus"))
                assertTrue("Native MCP tools discovery failed", phone.isNull("toolsError"))
                val tools = phone.getJSONObject("tools")
                assertEquals("Native inventory must contain the exact advertised tool", 1, tools.length())
                val tool = tools.getJSONObject(tools.keys().asSequence().single())
                assertEquals("hans_native_probe__inspect", tool.getString("name"))
                assertEquals("Harmless native MCP discovery contract", tool.getString("description"))
                val schema = tool.getJSONObject("inputSchema")
                assertEquals("object", schema.getString("type"))
                assertFalse(schema.getBoolean("additionalProperties"))
                assertEquals("string", schema.getJSONObject("properties").getJSONObject("label").getString("type"))
                assertEquals("Discovery must never execute a phone tool", 0, invocations.get())
                transport.stop(7001L, generation.get())
                assertTrue("Native runtime did not stop", stopped.await(20, TimeUnit.SECONDS))
                assertEquals("Native runtime cleanup failed", null, failure.get())
            } finally {
                if (generation.get() > 0) {
                    runCatching { transport?.stop(7002L, generation.get()) }
                    stopped.await(20, TimeUnit.SECONDS)
                }
                runCatching { runtime.get()?.configurePhoneToolsBridge(0, "") }
                context.unbindService(connection)
            }
        }
        assertEquals("No executor action is permitted in native discovery", 0, invocations.get())
    }
}
