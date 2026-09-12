package ai.hans.standard.plugins.runtime

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.plugins.PluginRuntimeReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSurfaceInventoryTest {
    @Test
    fun passiveProjectionRequiresExactNativeAndRemoteEvidenceAndLeaksNoSecrets() {
        var remoteReads = 0
        val android = AndroidDynamicToolEntrypointRegistry(
            listOf(
                AndroidDynamicToolRegistration(
                    "phone.open",
                    "android.intent.launch",
                    "phone",
                    "open",
                    FakeExecutor(),
                    PassiveCapabilityProbe { PluginRuntimeReadiness.READY },
                ),
            ),
        )
        val hooks = HansDeclarativeHookRegistry(emptyList())
        val projector = PluginSurfaceInventoryProjector(android, hooks) {
            remoteReads += 1
            listOf(
                RemoteMcpPassiveStatus(
                    pluginId = "sample",
                    serverId = "tasks",
                    configured = true,
                    authenticated = true,
                    discoveryProven = true,
                    discoveredToolNames = setOf("tasks/list"),
                ),
            )
        }
        val handle = OAuthCredentialHandle("b".repeat(64))
        val manifest = PluginSurfaceManifest(
            "sample",
            nativeSkills = listOf(NativeSkillRequirement("skill", "Sample skill", true)),
            nativeHooks = emptyList(),
            androidTools = listOf(AndroidToolRequirement("open", "phone.open", true)),
            hooks = emptyList(),
            remoteMcpServers = listOf(
                RemoteMcpRequirement(
                    "tasks",
                    "https://example.com/mcp",
                    handle,
                    setOf("tasks/list"),
                    true,
                    10_000,
                    64 * 1024,
                ),
            ),
        )
        val snapshot = projector.project(
            manifest,
            CodexNativeSurfaceEvidence(setOf("Sample skill"), emptySet(), complete = true),
        )

        assertEquals(PluginSurfaceReadiness.READY, snapshot.readiness)
        assertEquals(1, remoteReads)
        assertTrue("open" in snapshot.resolvedEntrypointIds)
        assertTrue("tasks:tasks/list" in snapshot.resolvedEntrypointIds)
        val projected = snapshot.toString()
        assertFalse(projected.contains(handle.value))
        assertFalse(projected.contains("https://"))
    }

    @Test
    fun requiredMissingNativeSelectorIsIncompatibleNotInstructionFallback() {
        val projector = PluginSurfaceInventoryProjector(
            AndroidDynamicToolEntrypointRegistry(emptyList()),
            HansDeclarativeHookRegistry(emptyList()),
            RemoteMcpPassiveStatusSource { emptyList() },
        )
        val manifest = PluginSurfaceManifest(
            "sample",
            listOf(NativeSkillRequirement("skill", "Missing", true)),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )
        val snapshot = projector.project(
            manifest,
            CodexNativeSurfaceEvidence(emptySet(), emptySet(), complete = true),
        )
        assertEquals(PluginSurfaceReadiness.INCOMPATIBLE, snapshot.readiness)
        assertEquals("app_server_selector_missing", snapshot.items.single().detailCode)
    }

    @Test
    fun sameServerIdFromAnotherPluginCannotSatisfyRemoteEvidence() {
        val projector = PluginSurfaceInventoryProjector(
            AndroidDynamicToolEntrypointRegistry(emptyList()),
            HansDeclarativeHookRegistry(emptyList()),
            RemoteMcpPassiveStatusSource {
                listOf(
                    RemoteMcpPassiveStatus(
                        pluginId = "other",
                        serverId = "tasks",
                        configured = true,
                        authenticated = true,
                        discoveryProven = true,
                        discoveredToolNames = setOf("tasks/list"),
                    ),
                )
            },
        )
        val manifest = PluginSurfaceManifest(
            "sample",
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                RemoteMcpRequirement(
                    "tasks",
                    "https://example.com/mcp",
                    null,
                    setOf("tasks/list"),
                    true,
                    10_000,
                    64 * 1024,
                ),
            ),
        )

        val snapshot = projector.project(
            manifest,
            CodexNativeSurfaceEvidence(emptySet(), emptySet(), complete = true),
        )

        assertEquals(PluginSurfaceReadiness.INCOMPATIBLE, snapshot.readiness)
        assertEquals("mcp_not_configured", snapshot.items.single().detailCode)
    }

    private class FakeExecutor : DynamicToolExecutor {
        override val specs = listOf(
            DynamicToolNamespaceSpec(
                "phone",
                "Phone tools",
                listOf(
                    DynamicToolFunctionSpec(
                        "open",
                        "Open app",
                        """{"type":"object","properties":{},"additionalProperties":false}""",
                    ),
                ),
            ),
        )

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) = completion(DynamicToolExecutionResult("""{"status":"ok"}""", true))

        override fun failureResult(call: DynamicToolCallParams, code: String) =
            DynamicToolExecutionResult("""{"status":"failed"}""", false)
    }
}
