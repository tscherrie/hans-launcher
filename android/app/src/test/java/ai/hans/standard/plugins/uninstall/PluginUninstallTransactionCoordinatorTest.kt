package ai.hans.standard.plugins.uninstall

import ai.hans.standard.plugins.PluginAuthPolicy
import ai.hans.standard.plugins.PluginAvailability
import ai.hans.standard.plugins.PluginCard
import ai.hans.standard.plugins.PluginDisabledReason
import ai.hans.standard.plugins.PluginHandle
import ai.hans.standard.plugins.PluginInstallPolicy
import ai.hans.standard.plugins.PluginListWireResult
import ai.hans.standard.plugins.PluginLocator
import ai.hans.standard.plugins.PluginSourceKind
import ai.hans.standard.plugins.PluginWireRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginUninstallTransactionCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptanceIsNotAbsenceProofAndKeepsRuntimePublished() {
        val fixture = fixture()
        prepareAccepted(fixture, "uninstall:acceptance")

        assertEquals(0, fixture.runtime.deactivationCalls)
        assertEquals(PluginUninstallJournalPhase.REMOTE_ACCEPTED, fixture.journal.readAll().single().phase)
    }

    @Test
    fun freshCompleteAbsenceProofDeactivatesExactRuntimeAndPublishesRevision() {
        val fixture = fixture()
        prepareAccepted(fixture, "uninstall:exact")

        assertEquals(
            PluginUninstallExecution.Removed,
            fixture.coordinator.proveAndDeactivate("uninstall:exact", listed()),
        )
        assertEquals(1, fixture.runtime.deactivationCalls)
        assertFalse(fixture.runtime.allowAlreadyAbsent.single())
        assertEquals(1, fixture.publicationChanges)
        assertTrue(fixture.journal.readAll().isEmpty())
    }

    @Test
    fun changedInstalledVersionQuarantinesWithoutCleanup() {
        val fixture = fixture()
        prepareAccepted(fixture, "uninstall:version")
        val changed = fixture.record.copy(localVersion = "2.0")

        val outcome = fixture.coordinator.proveAndDeactivate(
            "uninstall:version",
            listed(changed),
        )

        assertTrue(outcome is PluginUninstallExecution.Quarantined)
        assertEquals(0, fixture.runtime.deactivationCalls)
        assertEquals(PluginUninstallJournalPhase.QUARANTINED, fixture.journal.readAll().single().phase)
    }

    @Test
    fun changedSourceQuarantinesWithoutCleanup() {
        val fixture = fixture()
        prepareAccepted(fixture, "uninstall:source")
        File(checkNotNull(fixture.record.localSourcePath), "plugin.txt").writeText("changed")

        val outcome = fixture.coordinator.proveAndDeactivate(
            "uninstall:source",
            listed(fixture.record),
        )

        assertTrue(outcome is PluginUninstallExecution.Quarantined)
        assertEquals(0, fixture.runtime.deactivationCalls)
        assertEquals(0, fixture.publicationChanges)
    }

    @Test
    fun incompleteMarketplaceListNeverProvesAbsence() {
        val fixture = fixture()
        prepareAccepted(fixture, "uninstall:load-errors")

        val outcome = fixture.coordinator.proveAndDeactivate(
            "uninstall:load-errors",
            listed(loadIssues = 1),
        )

        assertTrue(outcome is PluginUninstallExecution.Quarantined)
        assertEquals(0, fixture.runtime.deactivationCalls)
    }

    @Test
    fun exactTargetStillPresentIsRetryableAndDoesNotCleanup() {
        val fixture = fixture()
        prepareAccepted(fixture, "uninstall:present")

        assertEquals(
            PluginUninstallExecution.Retry,
            fixture.coordinator.proveAndDeactivate(
                "uninstall:present",
                listed(fixture.record),
            ),
        )
        assertEquals(0, fixture.runtime.deactivationCalls)
        assertTrue(fixture.journal.readAll().isEmpty())
    }

    @Test
    fun crashAfterRemoteIntentRecoversOnlyAfterFreshAbsenceProof() {
        val fixture = fixture()
        fixture.coordinator.prepare("uninstall:crash-intent", fixture.record)
        fixture.coordinator.markRemoteUninstallIntent("uninstall:crash-intent")
        fixture.coordinator.abortAll()
        assertEquals(0, fixture.runtime.deactivationCalls)

        val recovered = fixture.newCoordinator(process = "process-2", lease = "lease-2")
        assertEquals(1, recovered.claimRecoveryBatch().size)
        assertEquals(
            PluginUninstallExecution.Removed,
            recovered.recoverClaim("uninstall:crash-intent", listed()),
        )
        assertEquals(1, fixture.runtime.deactivationCalls)
        assertFalse(fixture.runtime.allowAlreadyAbsent.single())
        assertTrue(fixture.journal.readAll().isEmpty())
    }

    @Test
    fun abortedRecoveryLeaseCanBeReclaimedByNextAppServerGeneration() {
        val fixture = fixture()
        fixture.coordinator.prepare("uninstall:recovery-generation", fixture.record)
        fixture.coordinator.markRemoteUninstallIntent("uninstall:recovery-generation")
        fixture.coordinator.abortAll()

        val recovered = fixture.newCoordinator(process = "process-2", lease = "lease-2")
        assertEquals(1, recovered.claimRecoveryBatch().size)

        recovered.abortAll()

        assertEquals(1, recovered.claimRecoveryBatch().size)
        assertEquals(
            PluginUninstallExecution.Removed,
            recovered.recoverClaim("uninstall:recovery-generation", listed()),
        )
        assertEquals(1, fixture.runtime.deactivationCalls)
        assertTrue(fixture.journal.readAll().isEmpty())
    }

    @Test
    fun crashWindowDuringPartialCleanupResumesWithAlreadyAbsentAllowance() {
        val fixture = fixture(
            deactivationResults = ArrayDeque(
                listOf(
                    PluginRuntimeUninstallDeactivationResult.PARTIAL,
                    PluginRuntimeUninstallDeactivationResult.EXACT,
                ),
            ),
        )
        prepareAccepted(fixture, "uninstall:partial")
        assertTrue(
            fixture.coordinator.proveAndDeactivate("uninstall:partial", listed()) is
                PluginUninstallExecution.Deferred,
        )
        assertEquals(PluginUninstallJournalPhase.LOCAL_DEACTIVATION_INTENT, fixture.journal.readAll().single().phase)

        val recovered = fixture.newCoordinator(process = "process-2", lease = "lease-2")
        recovered.claimRecoveryBatch()
        assertEquals(
            PluginUninstallExecution.Removed,
            recovered.recoverClaim("uninstall:partial", listed()),
        )
        assertEquals(listOf(false, true), fixture.runtime.allowAlreadyAbsent)
        assertTrue(fixture.journal.readAll().isEmpty())
    }

    @Test
    fun publicationFailureStaysDurableAndRecoveryDoesNotDeactivateTwice() {
        val fixture = fixture(
            publicationFailures = ArrayDeque(listOf(true, false)),
        )
        prepareAccepted(fixture, "uninstall:publication")

        assertTrue(
            fixture.coordinator.proveAndDeactivate("uninstall:publication", listed()) is
                PluginUninstallExecution.Deferred,
        )
        assertEquals(1, fixture.runtime.deactivationCalls)
        assertEquals(1, fixture.publicationChanges)
        assertEquals(
            PluginUninstallJournalPhase.LOCAL_DEACTIVATED,
            fixture.journal.readAll().single().phase,
        )

        val recovered = fixture.newCoordinator(process = "process-2", lease = "lease-2")
        recovered.claimRecoveryBatch()
        assertEquals(
            PluginUninstallExecution.Removed,
            recovered.recoverClaim("uninstall:publication", listed()),
        )
        assertEquals(1, fixture.runtime.deactivationCalls)
        assertEquals(2, fixture.publicationChanges)
        assertTrue(fixture.journal.readAll().isEmpty())
    }

    @Test
    fun staleRuntimeSnapshotRejectsBeforeRemoteIntent() {
        val fixture = fixture(snapshot = PluginRuntimeUninstallSnapshot.Changed)

        val result = fixture.coordinator.prepare("uninstall:stale-local", fixture.record)

        assertTrue(result is PluginUninstallPreparation.Rejected)
        assertTrue(fixture.journal.readAll().isEmpty())
        assertEquals(0, fixture.runtime.deactivationCalls)
    }

    private fun prepareAccepted(fixture: Fixture, operationId: String) {
        assertTrue(
            fixture.coordinator.prepare(operationId, fixture.record) is
                PluginUninstallPreparation.Prepared,
        )
        fixture.coordinator.markRemoteUninstallIntent(operationId)
        fixture.coordinator.markRemoteAccepted(operationId)
    }

    private fun fixture(
        snapshot: PluginRuntimeUninstallSnapshot? = null,
        deactivationResults: ArrayDeque<PluginRuntimeUninstallDeactivationResult> =
            ArrayDeque(listOf(PluginRuntimeUninstallDeactivationResult.EXACT)),
        publicationFailures: ArrayDeque<Boolean> = ArrayDeque(),
    ): Fixture {
        val source = temporaryFolder.newFolder("source-${System.nanoTime()}")
        File(source, "plugin.txt").writeText("original")
        val descriptor = PluginRuntimeUninstallDescriptor.canonical(
            pluginId = "sample",
            components = listOf(
                PluginRuntimeUninstallComponent(
                    kind = PluginRuntimeUninstallComponentKind.PLUGIN_SURFACE_V1,
                    componentId = PluginRuntimeUninstallComponent.SURFACE_COMPONENT_ID,
                    identity = "1".repeat(32),
                    stateSha256 = "2".repeat(64),
                ),
            ),
        )
        val runtime = FakeRuntimeManager(
            snapshot ?: PluginRuntimeUninstallSnapshot.Exact(descriptor),
            deactivationResults,
        )
        val journal = PluginUninstallJournal(temporaryFolder.newFolder("journal-${System.nanoTime()}"))
        val record = record(source)
        var publicationChanges = 0
        val publication = {
            publicationChanges += 1
            if (publicationFailures.isNotEmpty() && publicationFailures.removeFirst()) {
                error("synthetic publication failure")
            }
        }
        val coordinator = coordinator(
            runtime = runtime,
            journal = journal,
            process = "process-1",
            lease = "lease-1",
            onPublication = publication,
        )
        return Fixture(
            coordinator,
            journal,
            runtime,
            record,
            publicationChangeCount = { publicationChanges },
            onPublication = publication,
        )
    }

    private fun coordinator(
        runtime: FakeRuntimeManager,
        journal: PluginUninstallJournal,
        process: String,
        lease: String,
        onPublication: () -> Unit,
    ) = PluginUninstallTransactionCoordinator(
        runtime = runtime,
        journal = journal,
        onRuntimePublicationChanged = onPublication,
        ownerProcessEpoch = process,
        leaseIdFactory = { lease },
        wallClockMillis = { 1_000L },
        elapsedRealtimeMillis = { 100L },
    )

    private fun record(source: File): PluginWireRecord = PluginWireRecord(
        locator = PluginLocator("sample", "sample", "local", "/private/marketplace.json"),
        card = PluginCard(
            handle = PluginHandle("3".repeat(64)),
            pluginId = "sample",
            name = "sample",
            displayName = "Sample",
            shortDescription = null,
            marketplaceDisplayName = "Local",
            installed = true,
            enabled = true,
            availability = PluginAvailability.AVAILABLE,
            disabledReason = null as PluginDisabledReason?,
            installPolicy = PluginInstallPolicy.AVAILABLE,
            authPolicy = PluginAuthPolicy.ON_USE,
            sourceKind = PluginSourceKind.LOCAL,
            logoUrl = null,
            logoDarkUrl = null,
            capabilities = emptyList(),
            featured = false,
        ),
        availableVersion = "1.0",
        localVersion = "1.0",
        localSourcePath = source.absolutePath,
    )

    private fun listed(
        vararg records: PluginWireRecord,
        loadIssues: Int = 0,
    ) = PluginListWireResult(records.toList(), emptySet(), loadIssues)

    private class FakeRuntimeManager(
        private val snapshotResult: PluginRuntimeUninstallSnapshot,
        private val results: ArrayDeque<PluginRuntimeUninstallDeactivationResult>,
    ) : PluginRuntimeUninstallManager {
        var deactivationCalls = 0
        val allowAlreadyAbsent = mutableListOf<Boolean>()

        override fun snapshot(pluginId: String): PluginRuntimeUninstallSnapshot = snapshotResult

        override fun deactivateExact(
            descriptor: PluginRuntimeUninstallDescriptor,
            allowAlreadyAbsent: Boolean,
        ): PluginRuntimeUninstallDeactivationResult {
            deactivationCalls += 1
            this.allowAlreadyAbsent += allowAlreadyAbsent
            return results.removeFirst()
        }
    }

    private data class Fixture(
        val coordinator: PluginUninstallTransactionCoordinator,
        val journal: PluginUninstallJournal,
        val runtime: FakeRuntimeManager,
        val record: PluginWireRecord,
        val publicationChangeCount: () -> Int,
        val onPublication: () -> Unit,
    ) {
        val publicationChanges: Int
            get() = publicationChangeCount()

        fun newCoordinator(process: String, lease: String) = PluginUninstallTransactionCoordinator(
            runtime = runtime,
            journal = journal,
            onRuntimePublicationChanged = onPublication,
            ownerProcessEpoch = process,
            leaseIdFactory = { lease },
            wallClockMillis = { 2_000L },
            elapsedRealtimeMillis = { 200L },
        )
    }
}
