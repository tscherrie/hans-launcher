package ai.hans.standard.plugins.runtime

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.plugins.PluginDetailSnapshot
import ai.hans.standard.plugins.PluginHandle
import ai.hans.standard.plugins.PluginRuntimeDependencyRecoveryResult
import ai.hans.standard.plugins.PluginRuntimePreparationCancellation
import ai.hans.standard.plugins.PluginRuntimeReadiness
import ai.hans.standard.plugins.PluginRuntimeRequirements
import ai.hans.standard.plugins.PluginSkillSummary
import ai.hans.standard.plugins.install.PluginDependencyRecoveryComponent
import ai.hans.standard.plugins.install.PluginInstallJournalPhase
import ai.hans.standard.plugins.install.PluginInstallLocalRecoverability
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginSurfacePluginRuntimeDependencyPreparerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun declarationProbeSkipsOrdinaryPluginsAndRejectsMalformedSurfaceBeforeRead() {
        val fixture = fixture()
        assertTrue(
            fixture.stager.requiresFreshEvidence(PLUGIN_ID, fixture.pluginRoot) is
                PluginSurfaceDeclarationProbe.Declared,
        )

        File(fixture.pluginRoot, PluginSurfaceManifestLoader.MANIFEST_NAME).delete()
        assertEquals(
            PluginSurfaceDeclarationProbe.NotDeclared,
            fixture.stager.requiresFreshEvidence(PLUGIN_ID, fixture.pluginRoot),
        )

        File(fixture.pluginRoot, PluginSurfaceManifestLoader.MANIFEST_NAME).writeText("not-json")
        assertTrue(
            fixture.stager.requiresFreshEvidence(PLUGIN_ID, fixture.pluginRoot) is
                PluginSurfaceDeclarationProbe.Rejected,
        )
        assertTrue(fixture.store.recordsFor(PLUGIN_ID).isEmpty())
    }

    @Test
    fun stagedEvidenceIsUniqueAndNothingPublishesBeforeFinalize() {
        val fixture = fixture()
        assertTrue(fixture.stager.stage(PLUGIN_ID, fixture.pluginRoot, detail()) is
            PluginSurfaceEvidenceStageResult.Staged)
        assertTrue(fixture.stager.stage(PLUGIN_ID, fixture.pluginRoot, detail()) is
            PluginSurfaceEvidenceStageResult.Rejected)

        val transaction = fixture.preparer.prepare(requirements(), fixture.pluginRoot, cancellation())
        assertNull(fixture.published.snapshot(PLUGIN_ID))
        transaction.commit()
        assertNull(fixture.published.snapshot(PLUGIN_ID))
        transaction.finalizeCommit()

        val published = checkNotNull(fixture.published.snapshot(PLUGIN_ID))
        assertEquals(setOf("skill", "open"), published.inventory.resolvedEntrypointIds)
        assertEquals(1, published.dynamicToolExecutors.size)
        transaction.finalizeCommit()
        assertEquals(1, fixture.published.snapshots().size)
    }

    @Test
    fun rollbackOfPreparedOrCommittedReceiptIsExactIdempotentAndNeverRemovesActiveSurface() {
        val fixture = fixture()
        stage(fixture)
        val prepared = fixture.preparer.prepare(requirements(), fixture.pluginRoot, cancellation())
        val descriptor = component(prepared)
        prepared.rollback()
        prepared.rollback()
        assertState(
            PluginInstallLocalRecoverability.ABSENT,
            fixture.preparer.recover(PLUGIN_ID, descriptor, PluginInstallJournalPhase.LOCAL_PREPARED),
        )

        stage(fixture)
        val committed = fixture.preparer.prepare(requirements(), fixture.pluginRoot, cancellation())
        committed.commit()
        committed.rollback()
        assertNull(fixture.published.snapshot(PLUGIN_ID))

        stage(fixture)
        val finalized = fixture.preparer.prepare(requirements(), fixture.pluginRoot, cancellation())
        finalized.commit()
        finalized.finalizeCommit()
        assertThrows(IllegalStateException::class.java) { finalized.rollback() }
        assertNotNull(fixture.published.snapshot(PLUGIN_ID))
    }

    @Test
    fun recoveryReconstructsPreparedCommittedAndFinalizedCrashBoundaries() {
        val fixture = fixture()
        stage(fixture)
        val prepared = fixture.preparer.prepare(requirements(), fixture.pluginRoot, cancellation())
        val descriptor = component(prepared)
        val recoveredPrepared = fixture.preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.LOCAL_PREPARED,
        )
        assertEquals(PluginInstallLocalRecoverability.PREPARED, recoveredPrepared.recoverability)
        assertNotNull(recoveredPrepared.transaction)

        prepared.commit()
        val recoveredCommitted = fixture.preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.LOCAL_COMMITTED,
        )
        assertEquals(PluginInstallLocalRecoverability.COMMITTED, recoveredCommitted.recoverability)
        assertNull(fixture.published.snapshot(PLUGIN_ID))
        recoveredCommitted.transaction?.finalizeCommit()
        assertNotNull(fixture.published.snapshot(PLUGIN_ID))

        val finalizedWithoutReceipt = fixture.preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.LOCAL_COMMITTED,
        )
        assertEquals(
            PluginInstallLocalRecoverability.COMMITTED,
            finalizedWithoutReceipt.recoverability,
        )
        finalizedWithoutReceipt.transaction?.commit()
        finalizedWithoutReceipt.transaction?.finalizeCommit()
        assertThrows(IllegalStateException::class.java) {
            finalizedWithoutReceipt.transaction?.rollback()
        }
    }

    @Test
    fun crashAfterActiveSwapBeforeReceiptDeletionFinalizesIdempotently() {
        val fixture = fixture()
        stage(fixture)
        val transaction = fixture.preparer.prepare(requirements(), fixture.pluginRoot, cancellation())
        val descriptor = component(transaction)
        transaction.commit()
        val receiptFile = File(
            File(fixture.storeRoot, "receipts"),
            "${descriptor.transactionId}.json",
        )
        val committedBytes = receiptFile.readBytes()
        transaction.finalizeCommit()
        // Recreate the exact committed receipt to model a process death after the active rename.
        receiptFile.writeBytes(committedBytes)

        val recovered = fixture.preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.LOCAL_COMMITTED,
        )
        assertEquals(PluginInstallLocalRecoverability.COMMITTED, recovered.recoverability)
        recovered.transaction?.finalizeCommit()
        assertFalse(receiptFile.exists())
        assertNotNull(fixture.published.snapshot(PLUGIN_ID))
    }

    @Test
    fun changedManifestOrSignedEntrypointEvidenceQuarantinesRecoveryAndPublication() {
        val fixture = fixture()
        stage(fixture)
        val transaction = fixture.preparer.prepare(requirements(), fixture.pluginRoot, cancellation())
        val descriptor = component(transaction)
        writeManifest(
            fixture.pluginRoot,
            manifest().copy(nativeSkills = listOf(NativeSkillRequirement("skill", "Different", true))),
        )
        assertState(
            PluginInstallLocalRecoverability.CHANGED,
            fixture.preparer.recover(PLUGIN_ID, descriptor, PluginInstallJournalPhase.LOCAL_PREPARED),
        )

        val second = fixture(pluginRoot = newPluginRoot("entrypoint-change"))
        stage(second)
        val staged = second.preparer.prepare(requirements(), second.pluginRoot, cancellation())
        val stagedDescriptor = component(staged)
        val changedRuntime = fixture(
            pluginRoot = second.pluginRoot,
            storeRoot = second.storeRoot,
            executorDescription = "changed signed contract",
        )
        assertState(
            PluginInstallLocalRecoverability.CHANGED,
            changedRuntime.preparer.recover(
                PLUGIN_ID,
                stagedDescriptor,
                PluginInstallJournalPhase.LOCAL_PREPARED,
            ),
        )
        staged.commit()
        staged.finalizeCommit()
        assertNull(changedRuntime.published.snapshot(PLUGIN_ID))
    }

    @Test
    fun remoteMcpDeclarationHasNoNetworkOrAlreadyFinalizedPrecondition() {
        val remoteReads = AtomicInteger()
        val fixture = fixture(
            manifest = manifest().copy(
                remoteMcpServers = listOf(
                    RemoteMcpRequirement(
                        id = "tasks",
                        endpoint = "https://mcp.example.test/v1",
                        oauthHandle = OAuthCredentialHandle("a".repeat(64)),
                        allowedTools = setOf("tasks/list"),
                        required = true,
                        requestTimeoutMillis = 10_000,
                        maxResponseBytes = 64 * 1024,
                    ),
                ),
            ),
            remoteReads = remoteReads,
        )
        val staged = fixture.stager.stage(PLUGIN_ID, fixture.pluginRoot, detail())
        assertTrue(staged is PluginSurfaceEvidenceStageResult.Staged)
        assertEquals(0, remoteReads.get())
        val transaction = fixture.preparer.prepare(requirements(), fixture.pluginRoot, cancellation())
        transaction.commit()
        transaction.finalizeCommit()
        assertEquals(0, remoteReads.get())
        assertEquals(
            "mcp_declaration_validated",
            fixture.published.snapshot(PLUGIN_ID)?.inventory?.items
                ?.single { it.id == "tasks" }?.detailCode,
        )
    }

    @Test
    fun storeBoundsReceiptsAndDescriptorContainsOnlyOpaqueSurfaceIdentity() {
        val storeRoot = temporaryFolder.newFolder("bounded-store")
        repeat(16) { index ->
            val id = "plugin-$index"
            val root = newPluginRoot(id, manifest(id))
            val fixture = fixture(pluginId = id, pluginRoot = root, storeRoot = storeRoot)
            assertTrue(fixture.stager.stage(id, root, detail(id)) is
                PluginSurfaceEvidenceStageResult.Staged)
        }
        val overflowId = "plugin-overflow"
        val overflowRoot = newPluginRoot(overflowId, manifest(overflowId))
        val overflow = fixture(
            pluginId = overflowId,
            pluginRoot = overflowRoot,
            storeRoot = storeRoot,
        )
        assertTrue(overflow.stager.stage(overflowId, overflowRoot, detail(overflowId)) is
            PluginSurfaceEvidenceStageResult.Rejected)

        val isolated = fixture(pluginRoot = newPluginRoot("descriptor"))
        stage(isolated)
        val descriptor = component(
            isolated.preparer.prepare(requirements(), isolated.pluginRoot, cancellation()),
        )
        val text = descriptor.toString()
        assertFalse(text.contains(isolated.pluginRoot.path))
        assertFalse(text.contains("schema", ignoreCase = true))
        assertFalse(text.contains("https://"))
        assertEquals("surface", descriptor.componentId)
        assertTrue(descriptor.transactionId.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun controllerCanDiscardOnlyItsOwnUnclaimedReceipt() {
        val fixture = fixture()
        val staged = fixture.stager.stage(PLUGIN_ID, fixture.pluginRoot, detail())
            as PluginSurfaceEvidenceStageResult.Staged
        assertTrue(fixture.stager.discard(staged.receipt))
        assertFalse(fixture.stager.discard(staged.receipt))
        assertState(
            PluginInstallLocalRecoverability.ABSENT,
            fixture.preparer.recover(
                PLUGIN_ID,
                null,
                PluginInstallJournalPhase.LOCAL_PREPARING,
            ),
        )

        stage(fixture)
        fixture.preparer.prepare(requirements(), fixture.pluginRoot, cancellation())
        assertThrows(IllegalArgumentException::class.java) {
            fixture.stager.discard(
                (fixture.store.recordsFor(PLUGIN_ID).single().toPublicReceipt()),
            )
        }
    }

    @Test
    fun updateRollbackPreservesPreviousActiveReceiptAndDeactivateRequiresExactIdentity() {
        val fixture = fixture()
        stage(fixture)
        val first = fixture.preparer.prepare(requirements(), fixture.pluginRoot, cancellation())
        val firstDescriptor = component(first)
        first.commit()
        first.finalizeCommit()
        val firstPublication = checkNotNull(fixture.published.snapshot(PLUGIN_ID))

        writeManifest(
            fixture.pluginRoot,
            manifest().copy(
                nativeSkills = manifest().nativeSkills +
                    NativeSkillRequirement("optional", "Optional skill", false),
            ),
        )
        val stagedUpdate = fixture.stager.stage(PLUGIN_ID, fixture.pluginRoot, detail())
        assertTrue(stagedUpdate is PluginSurfaceEvidenceStageResult.Staged)
        val update = fixture.preparer.prepare(requirements(), fixture.pluginRoot, cancellation())
        val updateDescriptor = component(update)
        update.commit()
        assertEquals(firstPublication.stateSha256, fixture.store.active(PLUGIN_ID)?.stateSha256)
        assertEquals(
            PluginInstallLocalRecoverability.COMMITTED,
            fixture.preparer.recover(
                PLUGIN_ID,
                updateDescriptor,
                PluginInstallJournalPhase.LOCAL_COMMITTED,
            ).recoverability,
        )
        update.rollback()
        assertEquals(firstPublication.stateSha256, fixture.store.active(PLUGIN_ID)?.stateSha256)

        assertThrows(IllegalArgumentException::class.java) {
            fixture.preparer.deactivate(PLUGIN_ID, updateDescriptor)
        }
        assertTrue(fixture.preparer.deactivate(PLUGIN_ID, firstDescriptor))
        assertNull(fixture.published.snapshot(PLUGIN_ID))
        assertFalse(fixture.preparer.deactivate(PLUGIN_ID, firstDescriptor))
    }

    private fun fixture(
        pluginId: String = PLUGIN_ID,
        pluginRoot: File = newPluginRoot("plugin"),
        storeRoot: File = temporaryFolder.newFolder("store-${System.nanoTime()}"),
        manifest: PluginSurfaceManifest = manifest(pluginId),
        executorDescription: String = "trusted contract",
        remoteReads: AtomicInteger = AtomicInteger(),
    ): Fixture {
        writeManifest(pluginRoot, manifest)
        val android = AndroidDynamicToolEntrypointRegistry(
            listOf(
                AndroidDynamicToolRegistration(
                    bindingId = "phone.open",
                    capabilityId = "android.intent.launch",
                    namespace = "phone",
                    tool = "open",
                    executor = FakeExecutor(executorDescription),
                    capabilityProbe = PassiveCapabilityProbe { PluginRuntimeReadiness.READY },
                ),
            ),
        )
        val hooks = HansDeclarativeHookRegistry(emptyList())
        val inventory = PluginSurfaceInventoryProjector(android, hooks) {
            remoteReads.incrementAndGet()
            emptyList()
        }
        val privateRoot = pluginRoot.parentFile
        val preflight = PluginSurfacePreflight(
            PluginSurfaceManifestLoader(listOf(privateRoot)),
            android,
            hooks,
            inventory,
        )
        val store = PluginSurfaceActivationStore(storeRoot)
        return Fixture(
            pluginId,
            pluginRoot,
            storeRoot,
            store,
            PluginSurfaceEvidenceStager(preflight, store),
            PluginSurfacePluginRuntimeDependencyPreparer(preflight, store),
            PublishedPluginSurfaceRegistry(preflight, store),
        )
    }

    private fun newPluginRoot(
        name: String,
        manifest: PluginSurfaceManifest = manifest(),
    ): File = temporaryFolder.newFolder("private-$name").let { privateRoot ->
        File(privateRoot, "source").apply {
            mkdir()
            writeManifest(this, manifest)
        }
    }

    private fun writeManifest(root: File, manifest: PluginSurfaceManifest) {
        File(root, PluginSurfaceManifestLoader.MANIFEST_NAME)
            .writeBytes(PluginSurfaceManifestCodec.encode(manifest))
    }

    private fun manifest(pluginId: String = PLUGIN_ID) = PluginSurfaceManifest(
        pluginId = pluginId,
        nativeSkills = listOf(NativeSkillRequirement("skill", "Sample skill", true)),
        nativeHooks = emptyList(),
        androidTools = listOf(AndroidToolRequirement("open", "phone.open", true)),
        hooks = emptyList(),
        remoteMcpServers = emptyList(),
    )

    private fun requirements(pluginId: String = PLUGIN_ID) = PluginRuntimeRequirements(
        pluginId = pluginId,
        runtimes = emptyList(),
        entrypoints = emptyList(),
        capabilities = emptyList(),
    )

    private fun detail(pluginId: String = PLUGIN_ID) = PluginDetailSnapshot(
        handle = PluginHandle((pluginId.hashCode().toUInt().toString(16).padStart(8, '0')).repeat(8)),
        description = null,
        apps = emptyList(),
        skills = listOf(
            PluginSkillSummary("Sample skill", "Sample", true, null, null, null),
        ),
        hooks = emptyList(),
        mcpServerCount = 0,
        scheduledTaskCount = 0,
        shareUrl = null,
    )

    private fun cancellation() = object : PluginRuntimePreparationCancellation {
        override fun isCancellationRequested() = false
        override fun onCancel(action: () -> Unit) = Closeable { }
    }

    private fun stage(fixture: Fixture) {
        check(
            fixture.stager.stage(fixture.pluginId, fixture.pluginRoot, detail(fixture.pluginId)) is
                PluginSurfaceEvidenceStageResult.Staged,
        )
    }

    private fun component(transaction: ai.hans.standard.plugins.PluginRuntimeDependencyTransaction):
        PluginDependencyRecoveryComponent = checkNotNull(transaction.recoveryDescriptor)
        .components.single()

    private fun assertState(
        expected: PluginInstallLocalRecoverability,
        result: PluginRuntimeDependencyRecoveryResult,
    ) {
        assertEquals(expected, result.recoverability)
        assertNull(result.transaction)
    }

    private data class Fixture(
        val pluginId: String,
        val pluginRoot: File,
        val storeRoot: File,
        val store: PluginSurfaceActivationStore,
        val stager: PluginSurfaceEvidenceStager,
        val preparer: PluginSurfacePluginRuntimeDependencyPreparer,
        val published: PublishedPluginSurfaceRegistry,
    )

    private class FakeExecutor(description: String) : DynamicToolExecutor {
        override val specs = listOf(
            DynamicToolNamespaceSpec(
                "phone",
                "Phone",
                listOf(
                    DynamicToolFunctionSpec(
                        "open",
                        description,
                        """{"type":"object","properties":{},"additionalProperties":false}""",
                    ),
                ),
            ),
        )

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) = completion(DynamicToolExecutionResult("{}", true))

        override fun failureResult(call: DynamicToolCallParams, code: String) =
            DynamicToolExecutionResult("{}", false)
    }

    private companion object {
        const val PLUGIN_ID = "sample"
    }
}
