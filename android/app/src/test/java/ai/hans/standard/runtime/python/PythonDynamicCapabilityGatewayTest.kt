package ai.hans.standard.runtime.python

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonDynamicCapabilityGatewayTest {
    @Test
    fun routesOnlyExactInstalledCapabilityAndPreservesStructuredValue() {
        val executor = RecordingExecutor()
        val gateway = PythonDynamicCapabilityGateway(listOf(executor))
        var response: String? = null

        gateway.execute(request("phone.read", JSONObject().put("mode", "current"))) {
            response = it
        }

        assertEquals(setOf("phone.read"), gateway.supportedCapabilities)
        assertEquals("phone", executor.lastCall?.namespace)
        assertEquals("read", executor.lastCall?.tool)
        assertEquals("current", JSONObject(executor.lastCall!!.argumentsJson).getString("mode"))
        val decoded = JSONObject(response!!)
        assertEquals("succeeded", decoded.getString("status"))
        assertEquals("ok", decoded.getJSONObject("value").getString("status"))
    }

    @Test
    fun rejectsUnknownCapabilityWithoutEnteringExecutor() {
        val executor = RecordingExecutor()
        val gateway = PythonDynamicCapabilityGateway(listOf(executor))
        var response: String? = null

        gateway.execute(request("phone.delete_everything", JSONObject())) { response = it }

        assertEquals(null, executor.lastCall)
        assertEquals("capability_unavailable", JSONObject(response!!).getString("errorCode"))
    }

    @Test
    fun rejectsMismatchedEnvelopeBeforeEnteringExecutor() {
        val executor = RecordingExecutor()
        val gateway = PythonDynamicCapabilityGateway(listOf(executor))
        val mismatched = request("phone.read", JSONObject()).copy(
            capabilityJson = requestJson("different-request", 7, "phone.read", JSONObject()),
        )
        var response: String? = null

        gateway.execute(mismatched) { response = it }

        assertEquals(null, executor.lastCall)
        assertEquals("invalid_capability_request", JSONObject(response!!).getString("errorCode"))
    }

    @Test
    fun cancellationPropagatesAndSuppressesLateCompletion() {
        val executor = RecordingExecutor(deferCompletion = true)
        val gateway = PythonDynamicCapabilityGateway(listOf(executor))
        var completed = false

        val handle = gateway.execute(request("phone.read", JSONObject())) { completed = true }
        handle.cancel()
        executor.completeDeferred()

        assertTrue(executor.cancelled.get())
        assertFalse(completed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateRoutesAreRejectedAtConstruction() {
        PythonDynamicCapabilityGateway(listOf(RecordingExecutor(), RecordingExecutor()))
    }

    private fun request(name: String, arguments: JSONObject) = PythonCapabilityRequest(
        requestId = "py-request",
        sequence = 7,
        capabilityJson = requestJson("py-request", 7, name, arguments),
    )

    private fun requestJson(
        requestId: String,
        sequence: Long,
        name: String,
        arguments: JSONObject,
    ): String = JSONObject()
        .put("protocolVersion", PythonRuntimeContract.PROTOCOL_VERSION)
        .put("type", "capability_request")
        .put("requestId", requestId)
        .put("sequence", sequence)
        .put(
            "capability",
            JSONObject().put("name", name).put("arguments", arguments),
        )
        .toString()

    private class RecordingExecutor(
        private val deferCompletion: Boolean = false,
    ) : DynamicToolExecutor {
        override val specs = listOf(
            DynamicToolNamespaceSpec(
                name = "phone",
                description = "Test phone namespace",
                tools = listOf(
                    DynamicToolFunctionSpec(
                        name = "read",
                        description = "Read a test value",
                        inputSchemaJson = """{"type":"object"}""",
                    ),
                ),
            ),
        )
        var lastCall: DynamicToolCallParams? = null
        val cancelled = AtomicBoolean(false)
        private var deferredCompletion: ((DynamicToolExecutionResult) -> Unit)? = null

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) = error("cancellable path expected")

        override fun executeCancellable(
            call: DynamicToolCallParams,
            cancellation: DynamicToolCancellation,
            completion: (DynamicToolExecutionResult) -> Unit,
        ): DynamicToolExecutionHandle {
            lastCall = call
            if (deferCompletion) {
                deferredCompletion = completion
            } else {
                completion(DynamicToolExecutionResult("""{"status":"ok"}""", true))
            }
            return object : DynamicToolExecutionHandle {
                override fun cancel(): DynamicToolCancellationDisposition {
                    cancelled.set(true)
                    return DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED
                }
            }
        }

        fun completeDeferred() {
            deferredCompletion?.invoke(DynamicToolExecutionResult("""{"status":"ok"}""", true))
        }

        override fun failureResult(
            call: DynamicToolCallParams,
            code: String,
        ) = DynamicToolExecutionResult("""{"status":"failed","errorCode":"$code"}""", false)
    }
}
