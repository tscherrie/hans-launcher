package ai.hans.standard.plugins.runtime

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.plugins.PluginRuntimeReadiness
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPluginEntrypointsTest {
    @Test
    fun registryResolvesOnlySignedReadyBindingsAndProjectsCapabilities() {
        val executor = FakeExecutor("hans_phone", "open_app")
        val registry = AndroidDynamicToolEntrypointRegistry(
            listOf(
                AndroidDynamicToolRegistration(
                    "phone.open",
                    "android.intent.launch",
                    "hans_phone",
                    "open_app",
                    executor,
                    PassiveCapabilityProbe { PluginRuntimeReadiness.READY },
                ),
            ),
        )
        val resolution = registry.resolve(
            listOf(
                AndroidToolRequirement("open", "phone.open", true),
                AndroidToolRequirement("missing", "phone.missing", false),
            ),
        )

        assertEquals(setOf("open"), resolution.resolvedRequirementIds)
        assertEquals(setOf("android.intent.launch"), resolution.availableCapabilityIds)
        assertTrue(resolution.missingRequiredIds.isEmpty())
        assertEquals(listOf(executor), registry.executorsFor(listOf(AndroidToolRequirement("open", "phone.open", true))))
    }

    @Test
    fun unavailableRequiredBindingIsNotOptimisticallyResolved() {
        val registry = AndroidDynamicToolEntrypointRegistry(emptyList())
        val resolution = registry.resolve(listOf(AndroidToolRequirement("send", "sms.send", true)))

        assertEquals(setOf("send"), resolution.missingRequiredIds)
        assertTrue(resolution.availableCapabilityIds.isEmpty())
    }

    @Test
    fun declarativeHookCannotSupplyArgumentsPathOrWrongEvent() {
        val invoked = AtomicInteger()
        val registry = HansDeclarativeHookRegistry(
            listOf(
                HansDeclarativeHookRegistration(
                    "network.refresh",
                    "android.network",
                    PassiveCapabilityProbe { PluginRuntimeReadiness.READY },
                ) {
                    invoked.incrementAndGet()
                    HansHookActionReceipt(true, true, "verified")
                },
            ),
        )
        val requirement = DeclarativeHookRequirement(
            "refresh",
            HansHookEvent.NETWORK_RESTORED,
            "network.refresh",
            true,
        )
        assertTrue(
            runCatching {
                registry.dispatch(
                    requirement,
                    HansHookInvocation(HansHookEvent.TURN_COMPLETED, "turn-1", "codex"),
                )
            }.isFailure,
        )
        assertEquals(0, invoked.get())

        val receipt = registry.dispatch(
            requirement,
            HansHookInvocation(HansHookEvent.NETWORK_RESTORED, "turn-1", "android"),
        )
        assertTrue(receipt.postconditionVerified)
        assertEquals(1, invoked.get())
        assertFalse(HansHookInvocation::class.java.declaredFields.any {
            it.name.contains("path", ignoreCase = true) ||
                it.name.contains("command", ignoreCase = true) ||
                it.name.contains("shell", ignoreCase = true)
        })
    }

    private class FakeExecutor(namespace: String, tool: String) : DynamicToolExecutor {
        override val specs = listOf(
            DynamicToolNamespaceSpec(
                namespace,
                "Fake trusted Android tool",
                listOf(
                    DynamicToolFunctionSpec(
                        tool,
                        "Fake tool",
                        """{"type":"object","properties":{},"additionalProperties":false}""",
                    ),
                ),
            ),
        )

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) = completion(failureResult(call, "not_called"))

        override fun failureResult(call: DynamicToolCallParams, code: String) =
            DynamicToolExecutionResult("""{"status":"failed"}""", false)
    }
}
