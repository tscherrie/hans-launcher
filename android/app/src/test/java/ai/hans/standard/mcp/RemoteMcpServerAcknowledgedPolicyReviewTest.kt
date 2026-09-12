package ai.hans.standard.mcp

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteMcpServerAcknowledgedPolicyReviewTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun mutatingApprovalUsesSignedServerAcknowledgementAndRefreshesExactIdentity() {
        val store = RemoteMcpToolPolicyStore(
            temporaryFolder.newFolder("policy"),
            RemoteMcpBuiltInVerifiers.registry,
        )
        val identity = RemoteMcpActivationIdentity(
            pluginId = "tasks-plugin",
            serverId = "tasks",
            configurationDigest = "a".repeat(64),
        )
        val tool = RemoteMcpTool(
            name = "tasks/create",
            title = "Create task",
            description = "Creates one task",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            outputSchemaJson = null,
            annotationsJson = """{"readOnlyHint":false}""",
        )
        val catalog = RemoteMcpPolicyReviewCatalog.fromDiscovery(
            activationIdentity = identity,
            allowedToolNames = setOf(tool.name),
            discovery = RemoteMcpDiscoveryReceipt(listOf(tool), emptySet()),
        )
        val reloads = AtomicInteger()
        val closes = mutableListOf<RemoteMcpActivationIdentity>()
        val refreshes = mutableListOf<RemoteMcpActivationIdentity>()
        val coordinator = RemoteMcpPolicyReviewCoordinator(
            store = store,
            reloadPolicyOwner = RemoteMcpPolicyOwnerReloadCallback { reloads.incrementAndGet() },
            closeExactSession = RemoteMcpExactSessionCloseCallback { identity ->
                closes += identity
            },
            refreshContract = RemoteMcpContractRefreshCallback { identity ->
                refreshes += identity
            },
        )

        val updated = coordinator.approve(
            request = catalog.request(0),
            currentCatalog = catalog,
            decisions = listOf(
                RemoteMcpPolicyReviewDecision(
                    toolName = tool.name,
                    toolMetadataDigest = remoteMcpToolMetadataDigest(tool),
                    effect = RemoteMcpToolEffect.MUTATING,
                    signedVerifierId = RemoteMcpBuiltInVerifiers.SERVER_ACKNOWLEDGED_V1,
                ),
            ),
        )

        val policy = updated.policies.single()
        assertEquals(RemoteMcpToolEffect.MUTATING, policy.effect)
        assertEquals(RemoteMcpBuiltInVerifiers.SERVER_ACKNOWLEDGED_V1, policy.verifierId)
        assertEquals(1, reloads.get())
        assertEquals(listOf(identity), closes)
        assertEquals(listOf(identity), refreshes)
    }
}
