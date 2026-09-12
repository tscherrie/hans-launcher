package ai.hans.standard.runtime.python

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonEnvironmentRuntimeSelfTesterTest {
    @Test
    fun executesImportsInTheRealRuntimeContractWithoutCapabilities() {
        val captured = AtomicReference<PythonExecutionRequest>()
        val runtime = runtime { request ->
            captured.set(request)
            PythonExecutionResult(
                request.requestId,
                PythonExecutionStatus.SUCCEEDED,
                JSONObject().put("imports", JSONArray().put("demo_pkg")).toString(),
            )
        }
        val prepared = prepared(setOf("demo_pkg"))

        val result = PythonEnvironmentRuntimeSelfTester(
            runtime,
            nowElapsedRealtimeMillis = { 1_000L },
            timeoutMillis = 5_000L,
        ).test(prepared)

        assertTrue(result.succeeded)
        assertEquals(setOf("demo_pkg"), result.importedNames)
        assertEquals(emptySet<String>(), captured.get().allowedCapabilities)
        assertEquals(PythonEntrypointKind.CODE, captured.get().entrypoint.kind)
        assertEquals(
            PythonEnvironmentContract.sha256(prepared.environmentArchive.readBytes()),
            captured.get().environmentDigest,
        )
    }

    @Test
    fun failsClosedOnMissingOrMalformedImportProof() {
        val prepared = prepared(setOf("demo_pkg"))
        val missing = PythonEnvironmentRuntimeSelfTester(
            runtime { request ->
                PythonExecutionResult(
                    request.requestId,
                    PythonExecutionStatus.SUCCEEDED,
                    JSONObject().put("imports", JSONArray()).toString(),
                )
            },
            nowElapsedRealtimeMillis = { 1_000L },
            timeoutMillis = 5_000L,
        ).test(prepared)
        assertFalse(missing.succeeded)
        assertEquals("imports_incomplete", missing.errorCode)

        val malformed = PythonEnvironmentRuntimeSelfTester(
            runtime { request ->
                PythonExecutionResult(request.requestId, PythonExecutionStatus.SUCCEEDED, "[]")
            },
            nowElapsedRealtimeMillis = { 1_000L },
            timeoutMillis = 5_000L,
        ).test(prepared)
        assertFalse(malformed.succeeded)
        assertEquals("self_test_invalid_result", malformed.errorCode)
    }

    private fun runtime(result: (PythonExecutionRequest) -> PythonExecutionResult) =
        object : PythonRuntimeGateway {
            override fun snapshot() = PythonRuntimeSnapshot(PythonRuntimePhase.READY, 1, 123)

            override fun execute(
                request: PythonExecutionRequest,
                streamListener: PythonStreamListener,
                callback: PythonResultCallback,
            ): PythonExecutionHandle {
                callback.onResult(result(request))
                return LocalPythonExecutionHandle { false }
            }
        }

    private fun prepared(imports: Set<String>): PythonPreparedEnvironment {
        val root = Files.createTempDirectory("python-self-test").toFile()
        val archive = root.resolve("environment.pyz").apply {
            writeBytes(byteArrayOf(
                0x50, 0x4b, 0x05, 0x06,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ))
        }
        return PythonPreparedEnvironment(
            pluginId = "demo",
            lockDigest = "a".repeat(64),
            target = PythonEnvironmentTarget("3.14.7", "cp314", "arm64-v8a", 31),
            stagingDirectory = root,
            sitePackagesDirectory = root.resolve("site").apply { mkdir() },
            sourceDirectory = root.resolve("source").apply { mkdir() },
            environmentArchive = archive,
            importNames = imports,
        )
    }
}
