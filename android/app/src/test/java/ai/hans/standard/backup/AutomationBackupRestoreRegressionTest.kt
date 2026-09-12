package ai.hans.standard.backup

import ai.hans.standard.automations.AutomationBootSessionId
import ai.hans.standard.automations.AutomationCompletion
import ai.hans.standard.automations.AutomationCompletionResult
import ai.hans.standard.automations.AutomationDispatchFenceMarkResult
import ai.hans.standard.automations.AutomationLeaseClaim
import ai.hans.standard.automations.AutomationLeaseToken
import ai.hans.standard.automations.AutomationManualEnqueueResult
import ai.hans.standard.automations.AutomationManualRequestId
import ai.hans.standard.automations.AutomationRunState
import ai.hans.standard.automations.AutomationSnapshotPersistence
import ai.hans.standard.automations.AutomationStorageSnapshot
import ai.hans.standard.automations.AutomationWorkerId
import ai.hans.standard.automations.PersistentAutomationStorage
import ai.hans.standard.automations.testDefinition
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The actual persistent restore path, not a gateway which pretends to be atomic. */
class AutomationBackupRestoreRegressionTest {
    private val now = Instant.parse("2026-08-27T09:00:00Z")

    @Test
    fun matchingSnapshotCannotEraseAnExecutorOwnedLease() {
        val storage = storage()
        claim(storage)
        val before = storage.snapshot()

        assertThrows(IllegalStateException::class.java) {
            storage.compareAndReplaceSnapshotForRestore(
                before,
                AutomationStorageSnapshot(definitions = listOf(testDefinition(id = "imported.task"))),
            )
        }

        assertEquals(before, storage.snapshot())
    }

    @Test
    fun importCannotEraseAnUnresolvedTerminalDispatchFence() {
        val storage = storage()
        val claim = claim(storage)
        assertEquals(
            AutomationDispatchFenceMarkResult.MARKED,
            storage.markDispatchFence(claim.run.key, claim.lease.token, now, Duration.ofMinutes(2)),
        )
        storage.recoverLeases(now.plusSeconds(130), AutomationBootSessionId("backup-boot"))
        val before = storage.snapshot()
        assertEquals(AutomationRunState.FAILED_TERMINAL, before.runs.single().state)

        assertThrows(IllegalStateException::class.java) {
            storage.replaceSnapshotForRestore(AutomationStorageSnapshot())
        }

        assertEquals(before, storage.snapshot())
    }

    @Test
    fun rollbackCannotResurrectACompletedLeaseFromItsBeforeImage() {
        val storage = storage()
        val claim = claim(storage)
        val before = storage.snapshot()
        assertEquals(
            AutomationCompletionResult.COMPLETED,
            storage.completeLease(claim.run.key, claim.lease.token, now, AutomationCompletion.Succeeded),
        )
        val completed = storage.snapshot()

        assertThrows(IllegalStateException::class.java) {
            storage.replaceSnapshotForRestore(before)
        }

        assertEquals(completed, storage.snapshot())
    }

    @Test
    fun definitionsImportCannotDropTerminalHistoryOrManualIdempotency() {
        val storage = storage()
        val claim = claim(storage)
        storage.completeLease(claim.run.key, claim.lease.token, now, AutomationCompletion.Succeeded)
        val completed = storage.snapshot()

        assertThrows(IllegalStateException::class.java) {
            storage.compareAndReplaceSnapshotForRestore(
                completed,
                AutomationStorageSnapshot(definitions = listOf(testDefinition(id = "imported.task"))),
            )
        }

        assertEquals(completed, storage.snapshot())
    }

    private fun claim(storage: PersistentAutomationStorage): AutomationLeaseClaim {
        val definition = testDefinition()
        storage.upsertDefinition(definition)
        check(
            storage.enqueueManualRun(
                definition.id,
                definition.revision,
                AutomationManualRequestId("b".repeat(64)),
                now,
            ) is AutomationManualEnqueueResult.Enqueued,
        )
        storage.materializeDueInbox(now)
        return checkNotNull(
            storage.acquireNextLease(
                AutomationWorkerId("backup-worker"),
                AutomationLeaseToken("backup-test-lease"),
                AutomationBootSessionId("backup-boot"),
                now,
                Duration.ofMinutes(2),
            ),
        )
    }

    private fun storage(): PersistentAutomationStorage = PersistentAutomationStorage(
        object : AutomationSnapshotPersistence {
            private var bytes: ByteArray? = null
            override fun read(): ByteArray? = bytes?.copyOf()
            override fun write(bytes: ByteArray) {
                this.bytes = bytes.copyOf()
            }
        },
    )
}
