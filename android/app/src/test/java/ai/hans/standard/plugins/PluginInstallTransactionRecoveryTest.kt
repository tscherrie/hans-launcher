package ai.hans.standard.plugins

import ai.hans.standard.plugins.install.PluginDependencyRecoveryDescriptor
import ai.hans.standard.plugins.install.PluginDependencyRecoveryKind
import ai.hans.standard.plugins.install.PluginInstallAttempt
import ai.hans.standard.plugins.install.PluginInstallIdentity
import ai.hans.standard.plugins.install.PluginInstallJournal
import ai.hans.standard.plugins.install.PluginInstallJournalCorruptException
import ai.hans.standard.plugins.install.PluginInstallJournalEntry
import ai.hans.standard.plugins.install.PluginInstallJournalMutationResult
import ai.hans.standard.plugins.install.PluginInstallJournalPhase
import ai.hans.standard.plugins.install.PluginInstallJournalTimestamps
import ai.hans.standard.plugins.install.PluginInstallRecoveryExecution
import ai.hans.standard.plugins.install.PluginInstallRemoteProof
import ai.hans.standard.plugins.install.PluginInstallRemoteProver
import ai.hans.standard.plugins.install.PluginInstallLocalRecoverability
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginInstallTransactionRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val compatibilityCounter = AtomicInteger()

    @Test
    fun claimRotatesLeaseRevisionAndProcessEpochExactlyOncePerCoordinator() {
        val journal = journalWithEntry(phase = PluginInstallJournalPhase.REMOTE_ACCEPTED)
        val coordinator = coordinator(
            journal = journal,
            remoteProver = FixedRemoteProver(PluginInstallRemoteProof.UNAVAILABLE),
            localRecovery = fixedLocal(PluginInstallLocalRecoverability.UNAVAILABLE),
            processEpoch = "process-recovery",
            leaseId = "lease-recovery",
        )

        val firstClaims = coordinator.claimRecoveryBatch()
        val leased = checkNotNull(journal.read(OPERATION_ID))

        assertEquals(1, firstClaims.size)
        assertEquals(OPERATION_ID, firstClaims.single().operationId)
        assertEquals(PLUGIN_ID, firstClaims.single().pluginId)
        assertEquals("lease-recovery", leased.leaseId)
        assertEquals("process-recovery", leased.ownerProcessEpoch)
        assertEquals(2L, leased.revision)
        assertEquals(2_000L, leased.timestamps.updatedAtWallEpochMillis)
        assertEquals(200L, leased.timestamps.updatedAtElapsedRealtimeMillis)
        assertEquals(firstClaims, coordinator.claimRecoveryBatch())
        assertEquals(leased, journal.read(OPERATION_ID))
        assertEquals(1, coordinator.recoveryPendingCount())
    }

    @Test
    fun exactRemoteAndPreparedLocalCommitFinalizeAndRemoveJournal() {
        val journal = journalWithEntry(phase = PluginInstallJournalPhase.REMOTE_ACCEPTED)
        val transaction = FakeRecoveryTransaction()
        val coordinator = coordinator(
            journal = journal,
            remoteProver = FixedRemoteProver(PluginInstallRemoteProof.EXACT),
            localRecovery = fixedLocal(
                PluginInstallLocalRecoverability.PREPARED,
                transaction,
            ),
        )
        coordinator.claimRecoveryBatch()

        assertEquals(
            PluginInstallRecoveryExecution.Finalized,
            coordinator.recoverClaim(OPERATION_ID, listing("installed-proof")),
        )
        assertEquals(1, transaction.commits)
        assertEquals(1, transaction.finalizes)
        assertEquals(0, transaction.rollbacks)
        assertTrue(journal.readAll().isEmpty())
        assertEquals(0, coordinator.recoveryPendingCount())
    }

    @Test
    fun exactRemoteWithAbsentLocalRequiresAcceptedCompensationAndFreshAbsenceProof() {
        val journal = journalWithEntry(phase = PluginInstallJournalPhase.REMOTE_ACCEPTED)
        val remote = SequencedRemoteProver(
            PluginInstallRemoteProof.EXACT,
            PluginInstallRemoteProof.ABSENT,
        )
        val coordinator = coordinator(
            journal = journal,
            remoteProver = remote,
            localRecovery = fixedLocal(PluginInstallLocalRecoverability.ABSENT),
        )
        coordinator.claimRecoveryBatch()

        assertEquals(
            PluginInstallRecoveryExecution.CompensationRequired(OPERATION_ID, PLUGIN_ID),
            coordinator.recoverClaim(OPERATION_ID, listing("present-proof")),
        )
        assertEquals(
            PluginInstallJournalPhase.COMPENSATION_UNINSTALL_INTENT,
            journal.read(OPERATION_ID)?.phase,
        )
        coordinator.markRecoveryCompensationAccepted(OPERATION_ID)
        coordinator.markRecoveryCompensationAccepted(OPERATION_ID)
        assertEquals(
            PluginInstallJournalPhase.COMPENSATION_ACCEPTED,
            journal.read(OPERATION_ID)?.phase,
        )

        assertEquals(
            PluginInstallRecoveryExecution.Finalized,
            coordinator.recoverClaim(OPERATION_ID, listing("fresh-absence-proof")),
        )
        assertEquals(listOf("present-proof", "fresh-absence-proof"), remote.observedProofMarkers)
        assertTrue(journal.readAll().isEmpty())
        assertEquals(0, coordinator.recoveryPendingCount())
    }

    @Test
    fun changedRemoteProofQuarantinesDurablyWithoutTouchingLocalTransaction() {
        val journal = journalWithEntry(phase = PluginInstallJournalPhase.REMOTE_ACCEPTED)
        val transaction = FakeRecoveryTransaction()
        val coordinator = coordinator(
            journal = journal,
            remoteProver = FixedRemoteProver(PluginInstallRemoteProof.CHANGED),
            localRecovery = fixedLocal(
                PluginInstallLocalRecoverability.PREPARED,
                transaction,
            ),
        )
        coordinator.claimRecoveryBatch()

        val result = coordinator.recoverClaim(OPERATION_ID, listing("changed"))
        val quarantined = checkNotNull(journal.read(OPERATION_ID))

        assertTrue(result is PluginInstallRecoveryExecution.Quarantined)
        assertEquals(PluginInstallJournalPhase.QUARANTINED, quarantined.phase)
        assertEquals(0, transaction.commits)
        assertEquals(0, transaction.finalizes)
        assertEquals(0, transaction.rollbacks)
        assertEquals(1, coordinator.recoveryPendingCount())

        assertTrue(
            coordinator.recoverClaim(OPERATION_ID, listing("changed-again")) is
                PluginInstallRecoveryExecution.Quarantined,
        )
        assertEquals(quarantined, journal.read(OPERATION_ID))
    }

    @Test
    fun unavailableProofDefersWithoutChangingOrRemovingDurableJournal() {
        val journal = journalWithEntry(phase = PluginInstallJournalPhase.REMOTE_ACCEPTED)
        val transaction = FakeRecoveryTransaction()
        val coordinator = coordinator(
            journal = journal,
            remoteProver = FixedRemoteProver(PluginInstallRemoteProof.UNAVAILABLE),
            localRecovery = fixedLocal(
                PluginInstallLocalRecoverability.PREPARED,
                transaction,
            ),
        )
        coordinator.claimRecoveryBatch()
        val leased = checkNotNull(journal.read(OPERATION_ID))

        val first = coordinator.recoverClaim(OPERATION_ID, listing("unavailable"))
        val second = coordinator.recoverClaim(OPERATION_ID, listing("still-unavailable"))

        assertTrue(first is PluginInstallRecoveryExecution.Deferred)
        assertTrue(second is PluginInstallRecoveryExecution.Deferred)
        assertEquals(leased, journal.read(OPERATION_ID))
        assertEquals(0, transaction.commits)
        assertEquals(0, transaction.finalizes)
        assertEquals(0, transaction.rollbacks)
        assertEquals(1, coordinator.recoveryPendingCount())
    }

    @Test
    fun finalizedJournalWithCommittedLocalReceiptCleansUpWithoutRecommitting() {
        val journal = journalWithEntry(phase = PluginInstallJournalPhase.FINALIZED)
        val transaction = FakeRecoveryTransaction()
        val coordinator = coordinator(
            journal = journal,
            remoteProver = FixedRemoteProver(PluginInstallRemoteProof.EXACT),
            localRecovery = fixedLocal(
                PluginInstallLocalRecoverability.COMMITTED,
                transaction,
            ),
        )
        coordinator.claimRecoveryBatch()

        assertEquals(
            PluginInstallRecoveryExecution.Finalized,
            coordinator.recoverClaim(OPERATION_ID, listing("finalized")),
        )
        assertEquals(0, transaction.commits)
        assertEquals(1, transaction.finalizes)
        assertEquals(0, transaction.rollbacks)
        assertTrue(journal.readAll().isEmpty())
        assertEquals(0, coordinator.recoveryPendingCount())
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.recoverClaim(OPERATION_ID, listing("already-clean"))
        }
    }

    @Test
    fun staleRecoveryLeaseCannotCommitAfterAnotherProcessTakesOver() {
        val journal = journalWithEntry(phase = PluginInstallJournalPhase.REMOTE_ACCEPTED)
        val staleTransaction = FakeRecoveryTransaction()
        val stale = coordinator(
            journal = journal,
            remoteProver = FixedRemoteProver(PluginInstallRemoteProof.EXACT),
            localRecovery = fixedLocal(
                PluginInstallLocalRecoverability.PREPARED,
                staleTransaction,
            ),
            processEpoch = "process-stale",
            leaseId = "lease-stale",
        )
        stale.claimRecoveryBatch()
        val current = coordinator(
            journal = journal,
            remoteProver = FixedRemoteProver(PluginInstallRemoteProof.UNAVAILABLE),
            localRecovery = fixedLocal(PluginInstallLocalRecoverability.UNAVAILABLE),
            processEpoch = "process-current",
            leaseId = "lease-current",
        )
        current.claimRecoveryBatch()

        val result = stale.recoverClaim(OPERATION_ID, listing("stale-proof"))
        val durable = checkNotNull(journal.read(OPERATION_ID))

        assertTrue(result is PluginInstallRecoveryExecution.Deferred)
        assertEquals("process-current", durable.ownerProcessEpoch)
        assertEquals("lease-current", durable.leaseId)
        assertEquals(3L, durable.revision)
        assertEquals(0, staleTransaction.commits)
        assertEquals(0, staleTransaction.finalizes)
        assertEquals(0, staleTransaction.rollbacks)
        assertEquals(1, stale.recoveryPendingCount())
        assertEquals(1, current.recoveryPendingCount())
    }

    @Test
    fun corruptJournalFailsClosedBeforeAnyRecoveryClaimExists() {
        val directory = temporaryFolder.newFolder("corrupt-recovery-journal")
        File(directory, "plugin-install-journal-v1.json").writeText("{not-json")
        val journal = PluginInstallJournal(directory)
        val coordinator = coordinator(
            journal = journal,
            remoteProver = FixedRemoteProver(PluginInstallRemoteProof.UNAVAILABLE),
            localRecovery = fixedLocal(PluginInstallLocalRecoverability.UNAVAILABLE),
        )

        assertThrows(PluginInstallJournalCorruptException::class.java) {
            coordinator.claimRecoveryBatch()
        }
        assertEquals(0, coordinator.recoveryPendingCount())
        assertThrows(PluginInstallJournalCorruptException::class.java) { journal.readAll() }
    }

    private fun journalWithEntry(phase: PluginInstallJournalPhase): PluginInstallJournal {
        val directory = temporaryFolder.newFolder("recovery-journal-${compatibilityCounter.get()}")
        val journal = PluginInstallJournal(directory)
        assertEquals(
            PluginInstallJournalMutationResult.APPLIED,
            journal.create(entry(phase)),
        )
        return journal
    }

    private fun entry(phase: PluginInstallJournalPhase) = PluginInstallJournalEntry(
        operationId = OPERATION_ID,
        leaseId = "lease-original",
        revision = 1L,
        ownerProcessEpoch = "process-original",
        phase = phase,
        identity = PluginInstallIdentity(
            pluginId = PLUGIN_ID,
            pluginHandleSha256 = "a".repeat(64),
            pluginName = "sample",
            marketplaceName = "local",
            marketplacePath = "/private/marketplace.json",
            expectedInstalledVersion = "1.0.0",
            canonicalSourceRoot = "/private/plugins/sample",
            sourceSha256 = "b".repeat(64),
        ),
        installAttempt = PluginInstallAttempt("attempt-recovery", 1),
        dependencyRecovery = PluginDependencyRecoveryDescriptor(
            kind = PluginDependencyRecoveryKind.PYTHON_ENVIRONMENT_V1,
            environmentTransactionId = "environment-recovery",
            environmentDigest = "c".repeat(64),
            entrypointTransactionId = "entrypoint-recovery",
            entrypointMetadataDigest = "d".repeat(64),
        ),
        timestamps = PluginInstallJournalTimestamps(
            createdAtWallEpochMillis = 1_000L,
            updatedAtWallEpochMillis = 1_000L,
            createdAtElapsedRealtimeMillis = 100L,
            updatedAtElapsedRealtimeMillis = 100L,
        ),
    )

    private fun coordinator(
        journal: PluginInstallJournal,
        remoteProver: PluginInstallRemoteProver,
        localRecovery: PluginRuntimeDependencyRecoveryProvider,
        processEpoch: String = "process-recovery",
        leaseId: String = "lease-recovery",
    ): PluginInstallTransactionCoordinator {
        val pluginRoot = temporaryFolder.newFolder(
            "compatibility-${compatibilityCounter.incrementAndGet()}",
        )
        val compatibility = PluginRuntimeCompatibilityService(
            PluginRuntimeManifestLoader(listOf(pluginRoot)),
            PluginRuntimeProbeRegistry(emptyList()),
        )
        return PluginInstallTransactionCoordinator(
            compatibility = compatibility,
            dependencies = PluginRuntimeDependencyPreparer { _, _, _ ->
                error("Live dependency preparation is forbidden during recovery tests")
            },
            availableCapabilities = { emptySet() },
            dependencyRecovery = localRecovery,
            remoteRecoveryProver = remoteProver,
            installJournal = journal,
            ownerProcessEpoch = processEpoch,
            leaseIdFactory = { leaseId },
            wallClockMillis = { 2_000L },
            elapsedRealtimeMillis = { 200L },
        )
    }

    private fun fixedLocal(
        recoverability: PluginInstallLocalRecoverability,
        transaction: PluginRuntimeDependencyTransaction? = null,
    ) = PluginRuntimeDependencyRecoveryProvider { _, _, _ ->
        if (transaction == null) {
            PluginRuntimeDependencyRecoveryResult.withoutTransaction(recoverability)
        } else {
            PluginRuntimeDependencyRecoveryResult.recoverable(recoverability, transaction)
        }
    }

    private fun listing(proofMarker: String) = PluginListWireResult(
        records = emptyList(),
        featuredPluginIds = setOf(proofMarker),
        marketplaceLoadIssueCount = 0,
    )

    private class FixedRemoteProver(
        private val proof: PluginInstallRemoteProof,
    ) : PluginInstallRemoteProver {
        override fun prove(
            identity: PluginInstallIdentity,
            listed: PluginListWireResult,
        ): PluginInstallRemoteProof = proof
    }

    private class SequencedRemoteProver(
        vararg proofs: PluginInstallRemoteProof,
    ) : PluginInstallRemoteProver {
        private val remaining = ArrayDeque(proofs.toList())
        val observedProofMarkers = mutableListOf<String>()

        override fun prove(
            identity: PluginInstallIdentity,
            listed: PluginListWireResult,
        ): PluginInstallRemoteProof {
            observedProofMarkers += listed.featuredPluginIds.single()
            return remaining.removeFirst()
        }
    }

    private class FakeRecoveryTransaction : PluginRuntimeDependencyTransaction {
        override val resolvedEntrypointIds = setOf("main")
        override val availableCapabilityIds = emptySet<String>()
        var commits = 0
        var rollbacks = 0
        var finalizes = 0

        override fun commit() {
            commits += 1
        }

        override fun rollback() {
            rollbacks += 1
        }

        override fun finalizeCommit() {
            finalizes += 1
        }
    }

    private companion object {
        const val OPERATION_ID = "plugin-recovery-op"
        const val PLUGIN_ID = "sample-plugin"
    }
}
