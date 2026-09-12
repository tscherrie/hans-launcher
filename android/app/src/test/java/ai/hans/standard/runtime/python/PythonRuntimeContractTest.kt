package ai.hans.standard.runtime.python

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonRuntimeContractTest {
    @Test
    fun requestRoundTripPreservesVersionedModuleExecutionAndLimits() {
        val now = 10_000L
        val request = PythonExecutionRequest(
            requestId = "request-1",
            idempotencyKey = "idem-1",
            environmentDigest = "a".repeat(64),
            entrypoint = PythonEntrypoint(
                PythonEntrypointKind.MODULE,
                module = "garage.control",
                function = "open_door",
            ),
            argumentsJson = "{\"door\":1}",
            workspaceHandle = "c".repeat(64),
            allowedCapabilities = setOf("network", "notifications"),
            limits = PythonResourceLimits(now + 60_000),
        )

        val encoded = PythonRuntimeContract.encodeRequest(request, now)
        val decoded = PythonRuntimeContract.decodeRequest(encoded, now)

        assertEquals(request, decoded)
        assertEquals(1, JSONObject(encoded).getInt("protocolVersion"))
    }

    @Test
    fun pluginEntrypointsCannotEscapeTheirEnvironment() {
        val request = validRequest().copy(
            entrypoint = PythonEntrypoint(
                PythonEntrypointKind.PLUGIN,
                pluginId = "garage",
                relativePath = "../other/secret.py",
                function = "run",
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            PythonRuntimeContract.encodeRequest(request, 1_000)
        }
    }

    @Test
    fun deadlineAndWritableNativeImportClaimsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            PythonRuntimeContract.encodeRequest(validRequest(), 70_000)
        }

        val readiness = JSONObject()
            .put("protocolVersion", 1)
            .put("ready", true)
            .put("pythonVersion", "3.14.2")
            .put("abi", "arm64-v8a")
            .put("stdlibDigest", "b".repeat(64))
            .put(
                "verifiedImports",
                org.json.JSONArray(listOf("json", "asyncio", "sqlite3", "_ssl", "_hans_android")),
            )
            .put("writableNativeImports", true)
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            PythonRuntimeContract.decodeReadiness(readiness)
        }
    }

    @Test
    fun resultAndCapabilityContractsKeepCorrelationExact() {
        val result = PythonExecutionResult(
            requestId = "request-1",
            status = PythonExecutionStatus.SUCCEEDED,
            valueJson = "{\"answer\":42}",
            metricsJson = "{\"durationMillis\":5}",
        )
        assertEquals(result, PythonRuntimeContract.decodeResult(PythonRuntimeContract.encodeResult(result)))

        val capability = PythonRuntimeContract.capabilityFailure("request-1", 7, "denied")
        assertEquals(
            capability,
            PythonRuntimeContract.validateCapabilityResult(capability, "request-1", 7),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PythonRuntimeContract.validateCapabilityResult(capability, "request-1", 8)
        }
    }

    private fun validRequest() = PythonExecutionRequest(
        requestId = "request-1",
        idempotencyKey = "idem-1",
        environmentDigest = "a".repeat(64),
        entrypoint = PythonEntrypoint(PythonEntrypointKind.CODE, source = "print('hi')"),
        argumentsJson = "{}",
        limits = PythonResourceLimits(61_000),
    )
}
