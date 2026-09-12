package ai.hans.standard.runtime.python

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PythonDynamicToolsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun sourceToolBuildsABoundedRequestAndProjectsStreamsAndValue() {
        val captured = AtomicReference<PythonExecutionRequest>()
        val runtime = object : PythonRuntimeGateway {
            override fun snapshot() = PythonRuntimeSnapshot(PythonRuntimePhase.STOPPED, 0, 0)
            override fun execute(
                request: PythonExecutionRequest,
                streamListener: PythonStreamListener,
                callback: PythonResultCallback,
            ): PythonExecutionHandle {
                captured.set(request)
                streamListener.onChunk(
                    PythonStreamChunk(request.requestId, 1, PythonStreamKind.STDOUT, "hello".toByteArray()),
                )
                callback.onResult(
                    PythonExecutionResult(request.requestId, PythonExecutionStatus.SUCCEEDED, "42"),
                )
                return LocalPythonExecutionHandle { true }
            }
        }
        val workspaceHandle = "9".repeat(64)
        val executor = PythonDynamicToolExecutor(
            runtime,
            capabilityProvider = { setOf("phone.read", "phone.delete") },
            nowElapsedRealtimeMillis = { 1_000 },
        )
        var result: DynamicToolExecutionResult? = null

        executor.execute(
            call(
                "run",
                """{"code":"print('hello')","arguments":{},"workspaceHandle":"$workspaceHandle"}""",
            ),
        ) { result = it }

        assertTrue(result!!.success)
        assertEquals("hello", JSONObject(result!!.contentText).getString("stdout"))
        assertEquals(PythonEntrypointKind.CODE, captured.get().entrypoint.kind)
        assertTrue(captured.get().requestId.matches(Regex("py-[a-f0-9]{32}")))
        assertEquals(workspaceHandle, captured.get().workspaceHandle)
        assertTrue(captured.get().allowedCapabilities.isEmpty())
    }

    @Test
    fun statusDoesNotStartTheRuntimeAndBadArgumentsFailClosed() {
        var executed = false
        val runtime = object : PythonRuntimeGateway {
            override fun snapshot() = PythonRuntimeSnapshot(PythonRuntimePhase.STOPPED, 0, 0)
            override fun execute(
                request: PythonExecutionRequest,
                streamListener: PythonStreamListener,
                callback: PythonResultCallback,
            ): PythonExecutionHandle {
                executed = true
                return LocalPythonExecutionHandle { false }
            }
        }
        val executor = PythonDynamicToolExecutor(runtime, nowElapsedRealtimeMillis = { 1_000 })
        var status: DynamicToolExecutionResult? = null
        executor.execute(call("runtime_status", "{}")) { status = it }
        assertTrue(status!!.success)
        assertFalse(executed)

        var invalid: DynamicToolExecutionResult? = null
        executor.execute(call("run", "{\"code\":\"pass\",\"unexpected\":true}")) { invalid = it }
        assertFalse(invalid!!.success)
        assertEquals("invalid_arguments", JSONObject(invalid!!.contentText).getString("errorCode"))
    }

    @Test
    fun pluginExecutionUsesOnlyThatPluginsActiveDigestAndStatusIsPassiveAndBounded() {
        val pluginDigest = "b".repeat(64)
        val sourceDigest = "c".repeat(64)
        val captured = AtomicReference<PythonExecutionRequest>()
        var executed = false
        val runtime = object : PythonRuntimeGateway {
            override fun snapshot() = PythonRuntimeSnapshot(PythonRuntimePhase.STOPPED, 0, 0)
            override fun execute(
                request: PythonExecutionRequest,
                streamListener: PythonStreamListener,
                callback: PythonResultCallback,
            ): PythonExecutionHandle {
                executed = true
                captured.set(request)
                callback.onResult(
                    PythonExecutionResult(request.requestId, PythonExecutionStatus.SUCCEEDED, "null"),
                )
                return LocalPythonExecutionHandle { false }
            }
        }
        val provider = object : PythonEnvironmentSelectionProvider {
            override fun digestFor(pluginId: String?) =
                if (pluginId == "garage") pluginDigest else PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST

            override fun snapshot() = PythonEnvironmentStatusProjection(
                effectiveEnvironmentDigest = PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST,
                environments = listOf(
                    PythonEnvironmentStatus(
                        pluginId = "garage",
                        target = PythonEnvironmentTarget("3.14.7", "cp314", "arm64-v8a", 31),
                        state = PythonEnvironmentState.ACTIVE,
                        lockDigest = "a".repeat(64),
                        detail = PythonEnvironmentStore.RESOLUTION_PROOF_EXACT_ARTIFACTS,
                    ),
                ),
            )
        }
        val executor = PythonDynamicToolExecutor(
            runtime,
            environmentSelectionProvider = provider,
            pluginEntrypointResolver = committedRegistry(
                pluginId = "garage",
                environmentDigest = pluginDigest,
                sourceDigest = sourceDigest,
                declaredCapabilities = setOf("phone.read", "phone.missing"),
            ),
            capabilityProvider = { setOf("phone.read", "phone.undeclared") },
            nowElapsedRealtimeMillis = { 1_000 },
        )
        var status: DynamicToolExecutionResult? = null

        executor.execute(call("packages_status", "{}")) { status = it }
        assertFalse(executed)
        assertEquals(
            "exact_artifacts_verified_dependency_closure_unverified",
            JSONObject(status!!.contentText)
                .getJSONObject("packages")
                .getJSONArray("environments")
                .getJSONObject(0)
                .getString("detail"),
        )

        executor.execute(
            call(
                "run_plugin_entrypoint",
                """{"pluginId":"garage","entrypointId":"open-door"}""",
            ),
        ) { }
        assertTrue(executed)
        assertEquals(pluginDigest, captured.get().environmentDigest)
        assertEquals("garage", captured.get().entrypoint.pluginId)
        assertEquals("entry.py", captured.get().entrypoint.relativePath)
        assertEquals("run", captured.get().entrypoint.function)
        assertEquals(setOf("phone.read"), captured.get().allowedCapabilities)
    }

    @Test
    fun unknownPluginCannotFallThroughToTheBaselineOrSupplyItsOwnPathAndFunction() {
        val runtime = object : PythonRuntimeGateway {
            override fun snapshot() = PythonRuntimeSnapshot(PythonRuntimePhase.STOPPED, 0, 0)
            override fun execute(
                request: PythonExecutionRequest,
                streamListener: PythonStreamListener,
                callback: PythonResultCallback,
            ): PythonExecutionHandle = error("must not execute")
        }
        val executor = PythonDynamicToolExecutor(runtime, nowElapsedRealtimeMillis = { 1_000 })
        var result: DynamicToolExecutionResult? = null

        executor.execute(
            call(
                "run_plugin_entrypoint",
                """{"pluginId":"missing","entrypointId":"open-door"}""",
            ),
        ) { result = it }

        assertFalse(result!!.success)
        assertEquals(
            PythonPluginEntrypointRegistry.ERROR_MISSING,
            JSONObject(result!!.contentText).getString("errorCode"),
        )

        result = null
        executor.execute(
            call(
                "run_plugin_entrypoint",
                """{"pluginId":"missing","entrypointId":"open-door","relativePath":"evil.py","function":"run"}""",
            ),
        ) { result = it }
        assertFalse(result!!.success)
        assertEquals("invalid_arguments", JSONObject(result!!.contentText).getString("errorCode"))
    }

    @Test
    fun pendingAndStalePluginEntrypointsFailBeforeTheWorkerStarts() {
        val environmentDigest = "d".repeat(64)
        val sourceDigest = "e".repeat(64)
        var executed = false
        val runtime = object : PythonRuntimeGateway {
            override fun snapshot() = PythonRuntimeSnapshot(PythonRuntimePhase.STOPPED, 0, 0)
            override fun execute(
                request: PythonExecutionRequest,
                streamListener: PythonStreamListener,
                callback: PythonResultCallback,
            ): PythonExecutionHandle {
                executed = true
                return LocalPythonExecutionHandle { false }
            }
        }
        val registry = PythonPluginEntrypointRegistry(
            File(temporaryFolder.root, "pending-entrypoints.json"),
        )
        val activation = activation("garage", environmentDigest, sourceDigest)
        registry.prepareActivation(activation)
        val active = object : PythonEnvironmentSelectionProvider {
            override fun digestFor(pluginId: String?) = environmentDigest
            override fun snapshot() = PythonEnvironmentStatusProjection(
                effectiveEnvironmentDigest = PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST,
                environments = emptyList(),
            )
        }
        val executor = PythonDynamicToolExecutor(
            runtime = runtime,
            environmentSelectionProvider = active,
            pluginEntrypointResolver = registry,
            nowElapsedRealtimeMillis = { 1_000 },
        )
        var result: DynamicToolExecutionResult? = null

        executor.execute(
            call("run_plugin_entrypoint", """{"pluginId":"garage","entrypointId":"open-door"}"""),
        ) { result = it }

        assertFalse(executed)
        assertEquals(
            PythonPluginEntrypointRegistry.ERROR_UNCOMMITTED,
            JSONObject(result!!.contentText).getString("errorCode"),
        )

        val committed = committedRegistry("garage", environmentDigest, sourceDigest)
        val staleExecutor = PythonDynamicToolExecutor(
            runtime = runtime,
            environmentSelectionProvider = object : PythonEnvironmentSelectionProvider {
                override fun digestFor(pluginId: String?) = "f".repeat(64)
                override fun snapshot() = active.snapshot()
            },
            pluginEntrypointResolver = committed,
            nowElapsedRealtimeMillis = { 1_000 },
        )
        result = null
        staleExecutor.execute(
            call("run_plugin_entrypoint", """{"pluginId":"garage","entrypointId":"open-door"}"""),
        ) { result = it }
        assertFalse(executed)
        assertEquals(
            PythonPluginEntrypointRegistry.ERROR_STALE_ENVIRONMENT,
            JSONObject(result!!.contentText).getString("errorCode"),
        )
    }

    @Test
    fun rawModuleGetsNoCapabilitiesAndInvalidWorkspaceHandleFailsBeforeWorker() {
        val captured = AtomicReference<PythonExecutionRequest>()
        val runtime = object : PythonRuntimeGateway {
            override fun snapshot() = PythonRuntimeSnapshot(PythonRuntimePhase.STOPPED, 0, 0)
            override fun execute(
                request: PythonExecutionRequest,
                streamListener: PythonStreamListener,
                callback: PythonResultCallback,
            ): PythonExecutionHandle {
                captured.set(request)
                callback.onResult(
                    PythonExecutionResult(request.requestId, PythonExecutionStatus.SUCCEEDED, "null"),
                )
                return LocalPythonExecutionHandle { false }
            }
        }
        val executor = PythonDynamicToolExecutor(
            runtime = runtime,
            capabilityProvider = { setOf("phone.read") },
            nowElapsedRealtimeMillis = { 1_000 },
        )

        executor.execute(
            call("run_module", """{"module":"json","function":"loads"}"""),
        ) { }
        assertTrue(captured.get().allowedCapabilities.isEmpty())

        var invalid: DynamicToolExecutionResult? = null
        executor.execute(
            call(
                "run",
                """{"code":"pass","workspaceHandle":"${"a".repeat(63)}"}""",
            ),
        ) { invalid = it }
        assertFalse(invalid!!.success)
        assertEquals("invalid_arguments", JSONObject(invalid!!.contentText).getString("errorCode"))
    }

    @Test
    fun oversizedInstalledDeclarationIntersectionFailsClosedInsteadOfTruncatingAuthority() {
        val environmentDigest = "7".repeat(64)
        val sourceDigest = "8".repeat(64)
        val capabilities = (0 until PythonRuntimeContract.MAX_CAPABILITIES + 1)
            .mapTo(linkedSetOf()) { "phone.read$it" }
        var executed = false
        val runtime = object : PythonRuntimeGateway {
            override fun snapshot() = PythonRuntimeSnapshot(PythonRuntimePhase.STOPPED, 0, 0)
            override fun execute(
                request: PythonExecutionRequest,
                streamListener: PythonStreamListener,
                callback: PythonResultCallback,
            ): PythonExecutionHandle {
                executed = true
                return LocalPythonExecutionHandle { false }
            }
        }
        val executor = PythonDynamicToolExecutor(
            runtime = runtime,
            environmentSelectionProvider = object : PythonEnvironmentSelectionProvider {
                override fun digestFor(pluginId: String?) = environmentDigest
                override fun snapshot() = PythonEnvironmentStatusProjection(
                    effectiveEnvironmentDigest = PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST,
                    environments = emptyList(),
                )
            },
            pluginEntrypointResolver = committedRegistry(
                "garage",
                environmentDigest,
                sourceDigest,
                capabilities,
            ),
            capabilityProvider = { capabilities },
            nowElapsedRealtimeMillis = { 1_000 },
        )
        var result: DynamicToolExecutionResult? = null

        executor.execute(
            call("run_plugin_entrypoint", """{"pluginId":"garage","entrypointId":"open-door"}"""),
        ) { result = it }

        assertFalse(executed)
        assertFalse(result!!.success)
        assertEquals("invalid_arguments", JSONObject(result!!.contentText).getString("errorCode"))
    }

    private fun committedRegistry(
        pluginId: String,
        environmentDigest: String,
        sourceDigest: String,
        declaredCapabilities: Set<String> = emptySet(),
    ): PythonPluginEntrypointRegistry {
        val registry = PythonPluginEntrypointRegistry(
            File(temporaryFolder.root, "entrypoints-${System.nanoTime()}.json"),
        )
        val activation = activation(
            pluginId,
            environmentDigest,
            sourceDigest,
            declaredCapabilities,
        )
        val receipt = registry.prepareActivation(activation)
        registry.commitActivation(receipt, environmentDigest, sourceDigest)
        registry.finalizeActivation(receipt)
        return registry
    }

    private fun activation(
        pluginId: String,
        environmentDigest: String,
        sourceDigest: String,
        declaredCapabilities: Set<String> = emptySet(),
    ) = PythonPluginEntrypointActivation(
        pluginId = pluginId,
        environmentDigest = environmentDigest,
        sourceSha256 = sourceDigest,
        declarations = listOf(
            PythonPluginEntrypointDeclaration(
                entrypointId = "open-door",
                relativePath = "entry.py",
                function = "run",
                sourceSha256 = sourceDigest,
                declaredCapabilities = declaredCapabilities,
            ),
        ),
        provenEntrypointIds = setOf("open-door"),
    )

    private fun call(tool: String, arguments: String) = DynamicToolCallParams(
        threadId = "thread-1",
        turnId = "turn-1",
        callId = "call/with opaque characters",
        namespace = PythonDynamicToolCatalog.NAMESPACE,
        tool = tool,
        argumentsJson = arguments,
    )
}
