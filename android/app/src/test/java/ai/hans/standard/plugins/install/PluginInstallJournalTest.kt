package ai.hans.standard.plugins.install

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginInstallJournalTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun schemaV1RoundTripsEveryPhaseAndStrictNestedIdentity() {
        val entries = PluginInstallJournalPhase.entries.mapIndexed { index, phase ->
            entry(
                operationId = "operation-$index",
                leaseId = "lease-$index",
                phase = phase,
                dependency = if (index % 2 == 0) dependency() else null,
            )
        }

        val encoded = PluginInstallJournalCodec.encode(entries)
        assertEquals(entries.sortedBy { it.operationId }, PluginInstallJournalCodec.decode(encoded))
        assertTrue(encoded.length < PluginInstallJournalBounds.MAX_DOCUMENT_BYTES)

        val rootWithUnknownField = JSONObject(encoded).put("unexpected", true).toString()
        assertThrows(IllegalArgumentException::class.java) {
            PluginInstallJournalCodec.decode(rootWithUnknownField)
        }

        val wrongRevisionType = JSONObject(encoded).also { root ->
            root.getJSONArray("entries").getJSONObject(0).put("revision", "1")
        }.toString()
        assertThrows(IllegalArgumentException::class.java) {
            PluginInstallJournalCodec.decode(wrongRevisionType)
        }

        val changedIdentityShape = JSONObject(encoded).also { root ->
            root.getJSONArray("entries").getJSONObject(0)
                .getJSONObject("identity").put("displayName", "not authoritative")
        }.toString()
        assertThrows(IllegalArgumentException::class.java) {
            PluginInstallJournalCodec.decode(changedIdentityShape)
        }
    }

    @Test
    fun exactOperationLeaseRevisionCasAllowsLeaseTakeoverAndRejectsEveryStaleWriter() {
        val journal = PluginInstallJournal(temporaryFolder.root)
        val initial = entry()
        assertEquals(PluginInstallJournalMutationResult.APPLIED, journal.create(initial))
        assertEquals(
            PluginInstallJournalMutationResult.OPERATION_ALREADY_EXISTS,
            journal.create(initial),
        )

        val takeover = initial.copy(
            leaseId = "lease-recovery",
            revision = 2L,
            ownerProcessEpoch = "process-2",
            phase = PluginInstallJournalPhase.LOCAL_PREPARED,
            dependencyRecovery = dependency(),
            timestamps = initial.timestamps.copy(
                updatedAtWallEpochMillis = 2_000L,
                // Elapsed realtime can reset on a reboot; the new owner epoch makes that explicit.
                updatedAtElapsedRealtimeMillis = 10L,
            ),
        )
        assertEquals(
            PluginInstallJournalMutationResult.APPLIED,
            journal.compareAndSet(initial.cursor, takeover),
        )
        assertEquals(takeover, journal.read(initial.operationId))

        assertEquals(
            PluginInstallJournalMutationResult.CURSOR_MISMATCH,
            journal.compareAndSet(
                initial.cursor,
                takeover.copy(revision = 3L, phase = PluginInstallJournalPhase.REMOTE_INSTALL_INTENT),
            ),
        )
        assertEquals(
            PluginInstallJournalMutationResult.CURSOR_MISMATCH,
            journal.remove(initial.cursor),
        )
        assertEquals(PluginInstallJournalMutationResult.APPLIED, journal.remove(takeover.cursor))
        assertTrue(journal.readAll().isEmpty())
    }

    @Test
    fun casCannotChangeExactIdentityAttemptCreationTimeOrPreparedRecoveryReceipt() {
        val journal = PluginInstallJournal(temporaryFolder.root)
        val initial = entry(dependency = dependency())
        journal.create(initial)

        fun next() = initial.copy(
            revision = 2L,
            phase = PluginInstallJournalPhase.REMOTE_INSTALL_INTENT,
            timestamps = initial.timestamps.copy(updatedAtElapsedRealtimeMillis = 101L),
        )

        assertThrows(IllegalArgumentException::class.java) {
            journal.compareAndSet(
                initial.cursor,
                next().copy(identity = initial.identity.copy(sourceSha256 = "f".repeat(64))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            journal.compareAndSet(
                initial.cursor,
                next().copy(installAttempt = PluginInstallAttempt("attempt-2", 2)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            journal.compareAndSet(
                initial.cursor,
                next().copy(
                    timestamps = next().timestamps.copy(createdAtWallEpochMillis = 999L),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            journal.compareAndSet(
                initial.cursor,
                next().copy(
                    dependencyRecovery = dependency().copy(environmentDigest = "e".repeat(64)),
                ),
            )
        }
        assertEquals(initial, journal.read(initial.operationId))
    }

    @Test
    fun corruptOversizedAndSymlinkedJournalFailClosedInsteadOfBecomingEmpty() {
        val file = File(temporaryFolder.root, "plugin-install-journal-v1.json")
        file.writeText("{not-json")
        val corrupt = PluginInstallJournal(temporaryFolder.root)
        assertThrows(PluginInstallJournalCorruptException::class.java) { corrupt.readAll() }

        file.writeBytes(ByteArray(PluginInstallJournalBounds.MAX_DOCUMENT_BYTES + 1))
        assertThrows(PluginInstallJournalCorruptException::class.java) { corrupt.readAll() }

        assertTrue(file.delete())
        val outside = temporaryFolder.newFile("outside.json").apply {
            writeText(PluginInstallJournalCodec.encode(emptyList()))
        }
        Files.createSymbolicLink(file.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { corrupt.readAll() }
    }

    @Test
    fun symlinkedAtomicStagingFileCannotReplaceLastGoodRevision() {
        val journal = PluginInstallJournal(temporaryFolder.root)
        val initial = entry()
        journal.create(initial)
        val staging = File(temporaryFolder.root, "plugin-install-journal-v1.json.tmp")
        val outside = temporaryFolder.newFile("outside-staging.json")
        Files.createSymbolicLink(staging.toPath(), outside.toPath())
        val replacement = initial.copy(
            revision = 2L,
            phase = PluginInstallJournalPhase.LOCAL_PREPARED,
            timestamps = initial.timestamps.copy(updatedAtElapsedRealtimeMillis = 101L),
        )

        assertThrows(IllegalArgumentException::class.java) {
            journal.compareAndSet(initial.cursor, replacement)
        }
        assertEquals(initial, journal.read(initial.operationId))
        assertTrue(Files.isSymbolicLink(staging.toPath()))
    }

    @Test
    fun boundedJournalRejectsSeventeenthConcurrentOperationWithoutTouchingExistingReceipts() {
        val journal = PluginInstallJournal(temporaryFolder.root)
        repeat(PluginInstallJournalBounds.MAX_ENTRIES) { index ->
            assertEquals(
                PluginInstallJournalMutationResult.APPLIED,
                journal.create(entry(operationId = "operation-$index", leaseId = "lease-$index")),
            )
        }
        assertEquals(
            PluginInstallJournalMutationResult.JOURNAL_FULL,
            journal.create(entry(operationId = "operation-overflow", leaseId = "lease-overflow")),
        )
        assertEquals(PluginInstallJournalBounds.MAX_ENTRIES, journal.readAll().size)
    }

    @Test
    fun concurrentCasWritersCannotBothAcquireTheSameRevision() {
        val journal = PluginInstallJournal(temporaryFolder.root)
        val initial = entry()
        journal.create(initial)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(
                PluginInstallJournalPhase.LOCAL_PREPARED,
                PluginInstallJournalPhase.ROLLBACK_INTENT,
            ).mapIndexed { index, phase ->
                pool.submit<PluginInstallJournalMutationResult> {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    journal.compareAndSet(
                        initial.cursor,
                        initial.copy(
                            leaseId = "lease-writer-$index",
                            revision = 2L,
                            ownerProcessEpoch = "process-writer-$index",
                            phase = phase,
                            timestamps = initial.timestamps.copy(
                                updatedAtElapsedRealtimeMillis = 1L,
                            ),
                        ),
                    )
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val completed = results.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, completed.count { it == PluginInstallJournalMutationResult.APPLIED })
            assertEquals(
                1,
                completed.count { it == PluginInstallJournalMutationResult.CURSOR_MISMATCH },
            )
            assertEquals(2L, journal.read(initial.operationId)?.revision)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun entry(
        operationId: String = "operation-1",
        leaseId: String = "lease-1",
        phase: PluginInstallJournalPhase = PluginInstallJournalPhase.LOCAL_PREPARING,
        dependency: PluginDependencyRecoveryDescriptor? = null,
    ) = PluginInstallJournalEntry(
        operationId = operationId,
        leaseId = leaseId,
        revision = 1L,
        ownerProcessEpoch = "process-1",
        phase = phase,
        identity = PluginInstallIdentity(
            pluginId = "garage",
            pluginHandleSha256 = "a".repeat(64),
            pluginName = "garage-plugin",
            marketplaceName = "personal",
            marketplacePath = "/data/user/0/ai.hans.standard/no_backup/marketplace",
            expectedInstalledVersion = "1.2.3",
            canonicalSourceRoot = "/data/user/0/ai.hans.standard/no_backup/plugins/garage",
            sourceSha256 = "b".repeat(64),
        ),
        installAttempt = PluginInstallAttempt("attempt-1", 1),
        dependencyRecovery = dependency,
        timestamps = PluginInstallJournalTimestamps(
            createdAtWallEpochMillis = 1_000L,
            updatedAtWallEpochMillis = 1_000L,
            createdAtElapsedRealtimeMillis = 100L,
            updatedAtElapsedRealtimeMillis = 100L,
        ),
    )

    private fun dependency() = PluginDependencyRecoveryDescriptor(
        kind = PluginDependencyRecoveryKind.PYTHON_ENVIRONMENT_V1,
        environmentTransactionId = "pyenv-1",
        environmentDigest = "c".repeat(64),
        entrypointTransactionId = "entrypoint-1",
        entrypointMetadataDigest = "d".repeat(64),
    )
}
