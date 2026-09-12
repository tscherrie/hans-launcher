package ai.hans.standard.mcp

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteMcpPolicyReviewPartialCommitRecoveryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun durableCasFollowedByRefreshFailureIsReconciledWithoutSecondWrite() {
        val store = RemoteMcpToolPolicyStore(
            temporaryFolder.newFolder("partial-commit"),
            RemoteMcpBuiltInVerifiers.registry,
        )
        val identity = RemoteMcpActivationIdentity(
            pluginId = "mail-plugin",
            serverId = "mail",
            configurationDigest = "a".repeat(64),
        )
        val tool = RemoteMcpTool(
            name = "mail/send",
            title = "Send mail",
            description = null,
            inputSchemaJson = """{"type":"object","properties":{}}""",
            outputSchemaJson = null,
            annotationsJson = null,
        )
        val catalog = RemoteMcpPolicyReviewCatalog.fromDiscovery(
            activationIdentity = identity,
            allowedToolNames = setOf(tool.name),
            discovery = RemoteMcpDiscoveryReceipt(listOf(tool), emptySet()),
        )
        val request = catalog.request(0)
        val decisions = listOf(
            RemoteMcpPolicyReviewDecision(
                toolName = tool.name,
                toolMetadataDigest = remoteMcpToolMetadataDigest(tool),
                effect = RemoteMcpToolEffect.MUTATING,
                signedVerifierId = RemoteMcpBuiltInVerifiers.SERVER_ACKNOWLEDGED_V1,
            ),
        )
        val failFirstClose = AtomicBoolean(true)
        val reloads = AtomicInteger()
        val closes = AtomicInteger()
        val refreshes = AtomicInteger()
        val coordinator = RemoteMcpPolicyReviewCoordinator(
            store = store,
            reloadPolicyOwner = RemoteMcpPolicyOwnerReloadCallback { reloads.incrementAndGet() },
            closeExactSession = RemoteMcpExactSessionCloseCallback {
                closes.incrementAndGet()
                if (failFirstClose.getAndSet(false)) error("transient close failure")
            },
            refreshContract = RemoteMcpContractRefreshCallback { refreshes.incrementAndGet() },
        )

        val firstFailure = runCatching {
            coordinator.approve(request, catalog, decisions)
        }.exceptionOrNull()

        assertTrue(firstFailure is RemoteMcpFailure)
        assertEquals("mcp_policy_review_runtime_refresh_failed",
            (firstFailure as RemoteMcpFailure).code)
        assertEquals(1L, store.snapshot().revision)
        assertEquals(1, store.snapshot().policies.size)

        val recovered = coordinator.reconcileCommittedApproval(request, catalog, decisions)

        assertEquals(1L, recovered.revision)
        assertEquals(1L, store.snapshot().revision)
        assertEquals(2, reloads.get())
        assertEquals(2, closes.get())
        assertEquals(2, refreshes.get())
    }

    @Test
    fun reconciliationRejectsDifferentDecisionsWithoutChangingDurablePolicy() {
        val store = RemoteMcpToolPolicyStore(
            temporaryFolder.newFolder("decision-drift"),
            RemoteMcpBuiltInVerifiers.registry,
        )
        val identity = RemoteMcpActivationIdentity(
            pluginId = "tasks-plugin",
            serverId = "tasks",
            configurationDigest = "b".repeat(64),
        )
        val tool = RemoteMcpTool(
            name = "tasks/list",
            title = null,
            description = null,
            inputSchemaJson = """{"type":"object"}""",
            outputSchemaJson = null,
            annotationsJson = null,
        )
        val catalog = RemoteMcpPolicyReviewCatalog.fromDiscovery(
            identity,
            setOf(tool.name),
            RemoteMcpDiscoveryReceipt(listOf(tool), emptySet()),
        )
        val request = catalog.request(0)
        val readOnly = RemoteMcpPolicyReviewDecision(
            tool.name,
            remoteMcpToolMetadataDigest(tool),
            RemoteMcpToolEffect.READ_ONLY,
        )
        val coordinator = RemoteMcpPolicyReviewCoordinator(
            store,
            RemoteMcpPolicyOwnerReloadCallback {},
            RemoteMcpExactSessionCloseCallback {},
            RemoteMcpContractRefreshCallback {},
        )
        coordinator.approve(request, catalog, listOf(readOnly))
        val mutating = readOnly.copy(
            effect = RemoteMcpToolEffect.MUTATING,
            signedVerifierId = RemoteMcpBuiltInVerifiers.SERVER_ACKNOWLEDGED_V1,
        )

        val failure = runCatching {
            coordinator.reconcileCommittedApproval(request, catalog, listOf(mutating))
        }.exceptionOrNull()

        assertTrue(failure is RemoteMcpFailure)
        assertEquals("mcp_policy_review_commit_not_recoverable",
            (failure as RemoteMcpFailure).code)
        assertEquals(RemoteMcpToolEffect.READ_ONLY, store.snapshot().policies.single().effect)
        assertEquals(1L, store.snapshot().revision)
    }
}
