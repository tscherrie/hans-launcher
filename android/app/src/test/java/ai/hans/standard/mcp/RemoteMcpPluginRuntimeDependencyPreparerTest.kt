package ai.hans.standard.mcp

import ai.hans.standard.plugins.MutablePluginRuntimePreparationCancellation
import ai.hans.standard.plugins.PluginRuntimeRequirements
import ai.hans.standard.plugins.install.PluginInstallJournalPhase
import ai.hans.standard.plugins.install.PluginInstallLocalRecoverability
import ai.hans.standard.plugins.runtime.OAuthCredentialHandle
import ai.hans.standard.plugins.runtime.RemoteMcpRequirement
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteMcpPluginRuntimeDependencyPreparerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun requiredAuthenticationFailsClosedWithSafeConnectionRequest() {
        val harness = harness(
            requirement(required = true, handle = "a".repeat(64)),
            credentialState = OAuthCredentialState.MISSING,
        )

        val failure = assertThrows(RemoteMcpConnectionRequiredException::class.java) {
            harness.preparer.prepare(runtimeRequirements(), temporaryFolder.root, cancellation())
        }

        assertEquals("tasks-plugin", failure.request.pluginId)
        assertEquals("tasks", failure.request.serverId)
        assertEquals(RemoteMcpConnectionReason.MISSING, failure.request.reason)
        assertFalse(failure.toString().contains("https://"))
        assertFalse(failure.toString().contains("a".repeat(64)))
        assertTrue(harness.catalogs.recoverySnapshot("tasks-plugin").pending.isEmpty())
        assertTrue(harness.activations.pendingRecovery().isEmpty())
        assertEquals(0, harness.preflightCalls.get())
    }

    @Test
    fun optionalMissingAuthenticationFinalizesConnectionStateWithoutRoute() {
        val harness = harness(
            requirement(required = false, handle = "b".repeat(64)),
            credentialState = OAuthCredentialState.EXPIRED,
        )
        val transaction = harness.preparer.prepare(
            runtimeRequirements(),
            temporaryFolder.root,
            cancellation(),
        )
        val component = transaction.recoveryDescriptor!!.asComponents().single()
        val journalProjection = component.toString()
        assertFalse(journalProjection.contains("https://"))
        assertFalse(journalProjection.contains("properties"))
        val identity = harness.identity

        assertNotRoutable(harness, identity)
        transaction.commit()
        transaction.commit()
        assertNotRoutable(harness, identity)
        transaction.finalizeCommit()
        transaction.finalizeCommit()

        val catalog = harness.catalogs.finalizedCatalog(identity)
        assertEquals(RemoteMcpCatalogRouteState.CONNECTION_REQUIRED, catalog.routeState)
        assertFalse(catalog.routeEligible)
        assertTrue(catalog.discovery.tools.isEmpty())
        assertEquals(component.stateDigest, catalog.metadataDigest)
        assertEquals(0, harness.activations.status().finalizedActivationCount)
        assertEquals(0, harness.preflightCalls.get())
    }

    @Test
    fun firstInstallUsesFullPolicyIndependentDiscoveryAndRequiresSafeExplicitReview() {
        val schemaMarker = "private-schema-marker"
        val metaMarker = "private-meta-marker"
        val discovered = RemoteMcpTool(
            name = "tasks/list",
            title = "Tasks",
            description = "Private remote description",
            inputSchemaJson =
                """{"type":"object","description":"$schemaMarker","properties":{}}""",
            outputSchemaJson = null,
            annotationsJson =
                """{"readOnlyHint":true,"destructiveHint":false,"openWorldHint":true}""",
            metaJson = """{"tenant":"$metaMarker"}""",
        )
        val harness = harness(
            requirement = requirement(required = true, handle = null),
            approvePolicy = false,
            discoveredTool = discovered,
            reportedAllowedNames = emptySet(),
        )

        val failure = assertThrows(RemoteMcpPolicyReviewRequiredException::class.java) {
            harness.preparer.prepare(runtimeRequirements(), temporaryFolder.root, cancellation())
        }

        assertEquals(harness.identity, failure.request.activationIdentity)
        assertEquals(0L, failure.request.policyStoreRevision)
        val summary = failure.request.tools.single()
        assertEquals("tasks/list", summary.name)
        assertEquals("Tasks", summary.title)
        assertEquals(true, summary.declaredHints.readOnly)
        assertEquals(false, summary.declaredHints.destructive)
        assertEquals(true, summary.declaredHints.openWorld)
        assertEquals(remoteMcpToolMetadataDigest(discovered), summary.metadataDigest)
        assertTrue(failure.request.catalogDigest.matches(SHA_256))
        val publicText = listOf(failure, failure.request, summary).joinToString("|")
        assertFalse(publicText.contains("https://"))
        assertFalse(publicText.contains(schemaMarker))
        assertFalse(publicText.contains(metaMarker))
        assertFalse(publicText.contains("properties"))
        assertTrue(harness.catalogs.recoverySnapshot("tasks-plugin").pending.isEmpty())
        assertTrue(harness.activations.pendingRecovery().isEmpty())
        assertEquals(0L, harness.toolPolicies.snapshot().revision)
        assertEquals(1, harness.preflightCalls.get())
    }

    @Test
    fun preparedAndCommittedCatalogsNeverRouteThenFinalizePublishesExactOfflineSpecs() {
        val harness = harness(requirement(required = true, handle = null))
        val transaction = harness.preparer.prepare(
            runtimeRequirements(),
            temporaryFolder.root,
            cancellation(),
        )
        val component = transaction.recoveryDescriptor!!.asComponents().single()

        assertNotRoutable(harness, harness.identity)
        assertEquals(1, harness.preflightCalls.get())
        assertEquals(0, harness.registryFactoryCalls.get())
        transaction.commit()
        assertNotRoutable(harness, harness.identity)
        transaction.finalizeCommit()

        val restartedCatalogs = RemoteMcpDiscoveryCatalogStore(temporaryFolder.root)
        val offline = restartedCatalogs.finalizedCatalog(harness.identity)
        assertTrue(offline.routeEligible)
        assertEquals(setOf("tasks/list"), offline.discovery.allowedToolNames)
        assertEquals("tasks/list", offline.discovery.tools.single().name)
        assertEquals(component.stateDigest, offline.metadataDigest)
        assertEquals(1, harness.preflightCalls.get())
        assertEquals(0, harness.registryFactoryCalls.get())
        assertEquals(
            harness.identity,
            RemoteMcpSessionRegistry(temporaryFolder.root, noRouteSessionFactory())
                .finalizedDefinition(harness.identity)
                .identity,
        )
    }

    @Test
    fun committedColdRecoveryUsesExactReceiptsWithoutNetworkAndFinishesFinalizeWindow() {
        val first = harness(requirement(required = true, handle = null))
        val transaction = first.preparer.prepare(
            runtimeRequirements(),
            temporaryFolder.root,
            cancellation(),
        )
        val component = transaction.recoveryDescriptor!!.asComponents().single()
        transaction.commit()
        assertEquals(1, first.preflightCalls.get())

        val recoveredCalls = AtomicInteger(0)
        val recovered = harness(
            requirement(required = true, handle = null),
            root = temporaryFolder.root,
            preflightCalls = recoveredCalls,
        )
        val result = recovered.preparer.recover(
            pluginId = "tasks-plugin",
            descriptor = component,
            journalPhase = PluginInstallJournalPhase.LOCAL_COMMITTED,
        )
        assertEquals(PluginInstallLocalRecoverability.COMMITTED, result.recoverability)
        assertEquals(0, recoveredCalls.get())
        val recoveredTransaction = checkNotNull(result.transaction)
        recoveredTransaction.commit()
        recoveredTransaction.finalizeCommit()

        val secondColdStart = RemoteMcpDiscoveryCatalogStore(temporaryFolder.root)
            .finalizedCatalog(recovered.identity)
        assertTrue(secondColdStart.routeEligible)
        assertEquals(setOf("tasks/list"), secondColdStart.discovery.allowedToolNames)
        assertEquals(0, recovered.registryFactoryCalls.get())
    }

    @Test
    fun coldRecoveryCompletesCatalogFirstFinalizeCrashWindow() {
        val first = harness(requirement(required = true, handle = null))
        val transaction = first.preparer.prepare(
            runtimeRequirements(),
            temporaryFolder.root,
            cancellation(),
        )
        val component = transaction.recoveryDescriptor!!.asComponents().single()
        transaction.commit()
        val committedCatalog = first.catalogs.recoverySnapshot("tasks-plugin").pending.single()
        first.catalogs.finalize(first.catalogs.receiptForRecovery(committedCatalog))
        assertTrue(first.catalogs.finalizedCatalog(first.identity).routeEligible)
        assertThrows(RemoteMcpFailure::class.java) {
            first.activations.finalizedDefinition(first.identity)
        }

        val recovered = harness(
            requirement(required = true, handle = null),
            root = temporaryFolder.root,
        )
        val result = recovered.preparer.recover(
            "tasks-plugin",
            component,
            PluginInstallJournalPhase.LOCAL_COMMITTED,
        )
        assertEquals(PluginInstallLocalRecoverability.COMMITTED, result.recoverability)
        checkNotNull(result.transaction).finalizeCommit()
        assertEquals(recovered.identity, recovered.activations.finalizedDefinition(recovered.identity).identity)
    }

    @Test
    fun recoveryRejectsStaleDigestAndFailsUnavailableOnCorruptPrivateCatalog() {
        val harness = harness(requirement(required = true, handle = null))
        val transaction = harness.preparer.prepare(
            runtimeRequirements(),
            temporaryFolder.root,
            cancellation(),
        )
        val component = transaction.recoveryDescriptor!!.asComponents().single()
        val stale = component.copy(stateDigest = "f".repeat(64))
        assertEquals(
            PluginInstallLocalRecoverability.CHANGED,
            harness.preparer.recover(
                "tasks-plugin",
                stale,
                PluginInstallJournalPhase.LOCAL_PREPARED,
            ).recoverability,
        )

        transaction.commit()
        File(temporaryFolder.root, "discovery-catalog-v1.json").writeText("{corrupt")
        val corrupted = harness(
            requirement(required = true, handle = null),
            root = temporaryFolder.root,
        )
        assertEquals(
            PluginInstallLocalRecoverability.UNAVAILABLE,
            corrupted.preparer.recover(
                "tasks-plugin",
                component,
                PluginInstallJournalPhase.LOCAL_COMMITTED,
            ).recoverability,
        )
    }

    @Test
    fun exactDeactivationAndRollbackAreIdempotent() {
        val harness = harness(requirement(required = true, handle = null))
        val rolledBack = harness.preparer.prepare(
            runtimeRequirements(),
            temporaryFolder.root,
            cancellation(),
        )
        rolledBack.commit()
        rolledBack.rollback()
        rolledBack.rollback()
        assertTrue(harness.catalogs.recoverySnapshot("tasks-plugin").pending.isEmpty())
        assertTrue(harness.activations.pendingRecovery().isEmpty())

        val finalized = harness.preparer.prepare(
            runtimeRequirements(),
            temporaryFolder.root,
            cancellation(),
        )
        val component = finalized.recoveryDescriptor!!.asComponents().single()
        finalized.commit()
        finalized.finalizeCommit()
        assertEquals(
            RemoteMcpPluginDeactivationResult.DEACTIVATED,
            harness.preparer.deactivateFinalized(harness.identity, component.stateDigest),
        )
        assertEquals(
            RemoteMcpPluginDeactivationResult.ALREADY_ABSENT,
            harness.preparer.deactivateFinalized(harness.identity, component.stateDigest),
        )
        assertThrows(RemoteMcpFailure::class.java) {
            harness.catalogs.finalizedCatalog(harness.identity)
        }
        assertEquals(0, harness.activations.status().finalizedActivationCount)
    }

    private fun assertNotRoutable(harness: Harness, identity: RemoteMcpActivationIdentity) {
        assertThrows(RemoteMcpFailure::class.java) { harness.catalogs.finalizedCatalog(identity) }
        assertThrows(RemoteMcpFailure::class.java) {
            harness.activations.finalizedDefinition(identity)
        }
    }

    private fun harness(
        requirement: RemoteMcpRequirement,
        credentialState: OAuthCredentialState = OAuthCredentialState.AVAILABLE,
        root: File = temporaryFolder.root,
        preflightCalls: AtomicInteger = AtomicInteger(0),
        approvePolicy: Boolean = true,
        discoveredTool: RemoteMcpTool = defaultTool(),
        reportedAllowedNames: Set<String> = setOf("tasks/list"),
    ): Harness {
        val registryFactoryCalls = AtomicInteger(0)
        val registry = RemoteMcpSessionRegistry(root) {
            registryFactoryCalls.incrementAndGet()
            error("A preflight/finalized-definition test must not open a routed session")
        }
        val catalogs = RemoteMcpDiscoveryCatalogStore(root)
        val identity = RemoteMcpActivationIdentity(
            pluginId = "tasks-plugin",
            serverId = requirement.id,
            configurationDigest = RemoteMcpConfigurationIdentity.digest(requirement),
        )
        val toolPolicies = RemoteMcpToolPolicyStore(
            root,
            RemoteMcpSignedVerifierRegistry.EMPTY,
        )
        if (approvePolicy) {
            val snapshot = toolPolicies.snapshot()
            val digest = remoteMcpToolMetadataDigest(discoveredTool)
            val exactPolicyPresent = snapshot.policies.any { policy ->
                policy.activationIdentity == identity &&
                    policy.toolName == discoveredTool.name &&
                    policy.toolMetadataDigest == digest
            }
            if (!exactPolicyPresent) {
                toolPolicies.compareAndSetForActivation(
                    expectedRevision = snapshot.revision,
                    identity = identity,
                    approvals = listOf(
                        RemoteMcpToolPolicyApproval(
                            activationIdentity = identity,
                            tool = discoveredTool,
                            effect = RemoteMcpToolEffect.READ_ONLY,
                        ),
                    ),
                )
            }
        }
        val oauth = object : RemoteMcpOAuthVault {
            override fun state(identity: RemoteMcpOAuthCredentialIdentity) = credentialState
            override fun <T> withBearerToken(
                identity: RemoteMcpOAuthCredentialIdentity,
                block: (String) -> T,
            ): T = error("Preflight is stubbed")
        }
        val preflight = RemoteMcpPreflightDiscoverer { definition, cancellation ->
            cancellation.throwIfCancellationRequested()
            preflightCalls.incrementAndGet()
            assertEquals(identity, definition.identity)
            RemoteMcpDiscoveryReceipt(
                tools = listOf(discoveredTool),
                allowedToolNames = reportedAllowedNames,
            )
        }
        return Harness(
            preparer = RemoteMcpPluginRuntimeDependencyPreparer(
                requirements = RemoteMcpPluginRequirementSource { pluginId, _ ->
                    assertEquals("tasks-plugin", pluginId)
                    listOf(requirement)
                },
                oauth = oauth,
                activations = registry,
                catalogs = catalogs,
                preflight = preflight,
                toolPolicies = toolPolicies,
            ),
            activations = registry,
            catalogs = catalogs,
            identity = identity,
            toolPolicies = toolPolicies,
            preflightCalls = preflightCalls,
            registryFactoryCalls = registryFactoryCalls,
        )
    }

    private fun runtimeRequirements() = PluginRuntimeRequirements(
        pluginId = "tasks-plugin",
        runtimes = emptyList(),
        entrypoints = emptyList(),
        capabilities = emptyList(),
    )

    private fun defaultTool() = RemoteMcpTool(
        name = "tasks/list",
        title = "Tasks",
        description = "List tasks",
        inputSchemaJson = """{"type":"object","properties":{}}""",
        outputSchemaJson = null,
    )

    private fun requirement(
        required: Boolean,
        handle: String?,
    ) = RemoteMcpRequirement(
        id = "tasks",
        endpoint = "https://mcp.example.com/v1",
        oauthHandle = handle?.let(::OAuthCredentialHandle),
        allowedTools = setOf("tasks/list"),
        required = required,
        requestTimeoutMillis = 10_000,
        maxResponseBytes = 128 * 1024,
    )

    private fun cancellation() = MutablePluginRuntimePreparationCancellation()

    private fun noRouteSessionFactory() = RemoteMcpSessionFactory {
        error("Offline catalog reconstruction must not open a session")
    }

    private data class Harness(
        val preparer: RemoteMcpPluginRuntimeDependencyPreparer,
        val activations: RemoteMcpSessionRegistry,
        val catalogs: RemoteMcpDiscoveryCatalogStore,
        val identity: RemoteMcpActivationIdentity,
        val toolPolicies: RemoteMcpToolPolicyStore,
        val preflightCalls: AtomicInteger,
        val registryFactoryCalls: AtomicInteger,
    )
}
