package ai.hans.standard.automations

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class SQLiteAutomationStorageTest {
    private lateinit var context: Context
    private lateinit var fileName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fileName = "automation-test-${System.nanoTime()}.db"
    }

    @After
    fun tearDown() {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            context.noBackupFilesDir.resolve(fileName + suffix).delete()
        }
    }

    @Test
    fun tabularSnapshotPreservesInboxRunLeaseCursorAndRecoveryAcrossReopen() {
        val now = Instant.parse("2026-08-24T07:00:00Z")
        val later = now.plusSeconds(3_600)
        val definition = AutomationDefinition(
            id = AutomationId("automation.sqlite_device_test"),
            revision = 1,
            enabled = true,
            schedule = AutomationSchedule(
                LocalDateTime.of(2026, 8, 24, 9, 0),
                AutomationTimeZone.Fixed("Europe/Oslo"),
                "FREQ=DAILY",
            ),
            missedRunPolicy = MissedRunPolicy(),
            retryPolicy = AutomationRetryPolicy(),
            target = CodexAutomationTarget.Independent,
            instruction = "Create a private SQLite recovery test summary.",
            updatedAt = now,
        )
        val persistence = SQLiteAutomationSnapshotPersistence(context, fileName)
        val storage = PersistentAutomationStorage(persistence)
        storage.upsertDefinition(definition)
        storage.recordDiscovery(
            AutomationDiscoveryBatch(
                definition.id,
                definition.revision,
                later,
                listOf(
                    deviceItem(definition, now),
                    deviceItem(definition, later),
                ),
            ),
        )
        storage.materializeDueInbox(now)
        storage.acquireNextLease(
            AutomationWorkerId("worker-sqlite-device"),
            AutomationLeaseToken("lease-token-sqlite-device"),
            AutomationBootSessionId("boot-sqlite-device"),
            now,
            Duration.ofMinutes(2),
        )
        storage.storeRecoveryState(
            AutomationRecoveryState(
                AutomationBootSessionId("boot-sqlite-device"),
                "Europe/Oslo",
                now,
            ),
        )
        val databaseFile = context.noBackupFilesDir.resolve(fileName)
        val stableDefinitionRowId = databaseFile.singleRowId("automation_definitions")
        val stableRunRowId = databaseFile.singleRowId("automation_runs")
        assertEquals(
            AutomationHeartbeatResult.EXTENDED,
            storage.heartbeat(
                AutomationRunKey(definition.id, now),
                AutomationLeaseToken("lease-token-sqlite-device"),
                now.plusSeconds(10),
                Duration.ofMinutes(2),
            ),
        )
        assertEquals(stableDefinitionRowId, databaseFile.singleRowId("automation_definitions"))
        assertEquals(stableRunRowId, databaseFile.singleRowId("automation_runs"))
        val expected = storage.snapshot()
        persistence.close()

        val reopenedPersistence = SQLiteAutomationSnapshotPersistence(context, fileName)
        val reopened = PersistentAutomationStorage(reopenedPersistence)
        assertEquals(expected, reopened.snapshot())
        reopenedPersistence.close()

        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            val tableNames = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'automation_%'",
                null,
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            assertTrue("automation_definitions" in tableNames)
            assertTrue("automation_inbox" in tableNames)
            assertTrue("automation_runs" in tableNames)
            assertTrue("automation_leases" in tableNames)
            assertTrue("automation_recovery" in tableNames)
            assertEquals(1L, database.rowCount("automation_definitions"))
            assertEquals(1L, database.rowCount("automation_inbox"))
            assertEquals(1L, database.rowCount("automation_runs"))
            assertEquals(1L, database.rowCount("automation_leases"))
            assertEquals(1L, database.rowCount("automation_recovery"))
        }
    }

    @Test
    fun legacyVersionOneDatabaseMigratesRunWithoutFenceAndRetainsAllRows() {
        val now = Instant.parse("2026-08-24T07:00:00Z")
        val definition = AutomationDefinition(
            id = AutomationId("automation.sqlite.legacy.v1"),
            revision = 1,
            enabled = true,
            schedule = AutomationSchedule(
                LocalDateTime.of(2026, 8, 24, 9, 0),
                AutomationTimeZone.Fixed("Europe/Oslo"),
                "FREQ=DAILY",
            ),
            missedRunPolicy = MissedRunPolicy(),
            retryPolicy = AutomationRetryPolicy(),
            target = CodexAutomationTarget.Independent,
            instruction = "Retain this legacy automation during migration.",
            updatedAt = now,
        )
        val persistence = SQLiteAutomationSnapshotPersistence(context, fileName)
        val storage = PersistentAutomationStorage(persistence)
        storage.upsertDefinition(definition)
        storage.recordDiscovery(
            AutomationDiscoveryBatch(
                definition.id,
                definition.revision,
                now,
                listOf(deviceItem(definition, now)),
            ),
        )
        storage.materializeDueInbox(now)
        persistence.close()

        val databaseFile = context.noBackupFilesDir.resolve(fileName)
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            val legacyRun = database.rawQuery(
                "SELECT payload FROM automation_runs",
                null,
            ).use { cursor ->
                check(cursor.moveToFirst())
                JSONObject(cursor.getString(0)).apply { remove("dispatchFence") }
            }
            database.update(
                "automation_runs",
                ContentValues().apply { put("payload", legacyRun.toString()) },
                null,
                null,
            )
            database.execSQL(
                "UPDATE automation_metadata SET snapshot_version = 1 WHERE singleton_id = 1",
            )
        }

        val reopenedPersistence = SQLiteAutomationSnapshotPersistence(context, fileName)
        val reopened = PersistentAutomationStorage(reopenedPersistence)
        assertEquals(definition, reopened.definition(definition.id))
        assertEquals(AutomationRunState.PENDING, reopened.snapshot().runs.single().state)
        assertEquals(null, reopened.snapshot().runs.single().dispatchFence)
        reopened.storeRecoveryState(
            AutomationRecoveryState(
                AutomationBootSessionId("boot-sqlite-v2"),
                "UTC",
                now,
            ),
        )
        reopenedPersistence.close()

        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            assertEquals(
                3,
                database.rawQuery(
                    "SELECT snapshot_version FROM automation_metadata WHERE singleton_id = 1",
                    null,
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                },
            )
            assertEquals(1L, database.rowCount("automation_definitions"))
            assertEquals(1L, database.rowCount("automation_runs"))
        }
    }

    @Test
    fun versionTwoRetryWaitWithoutWaitKindRemainsBackoffAcrossSQLiteReopen() {
        val now = Instant.parse("2026-08-24T07:00:00Z")
        val retryAt = now.plus(Duration.ofMinutes(5))
        val definition = AutomationDefinition(
            id = AutomationId("automation.sqlite.legacy.v2.wait"),
            revision = 1,
            enabled = true,
            schedule = AutomationSchedule(
                LocalDateTime.of(2026, 8, 24, 9, 0),
                AutomationTimeZone.Fixed("Europe/Oslo"),
                "FREQ=DAILY",
            ),
            missedRunPolicy = MissedRunPolicy(),
            retryPolicy = AutomationRetryPolicy(),
            target = CodexAutomationTarget.Independent,
            instruction = "Preserve a legacy retry backoff across SQLite migration.",
            updatedAt = now,
        )
        val persistence = SQLiteAutomationSnapshotPersistence(context, fileName)
        val storage = PersistentAutomationStorage(persistence)
        storage.upsertDefinition(definition)
        storage.recordDiscovery(
            AutomationDiscoveryBatch(
                definition.id,
                definition.revision,
                now,
                listOf(deviceItem(definition, now)),
            ),
        )
        storage.materializeDueInbox(now)
        val key = AutomationRunKey(definition.id, now)
        val token = AutomationLeaseToken("lease-token-sqlite-v2-wait")
        storage.acquireNextLease(
            AutomationWorkerId("worker-sqlite-v2-wait"),
            token,
            AutomationBootSessionId("boot-sqlite-v2-wait"),
            now,
            Duration.ofMinutes(2),
        )
        assertEquals(
            AutomationCompletionResult.DEFERRED,
            storage.completeLease(
                key,
                token,
                now.plusSeconds(1),
                AutomationCompletion.Deferred("network_offline", retryAt),
            ),
        )
        persistence.close()

        val databaseFile = context.noBackupFilesDir.resolve(fileName)
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            val legacyRun = database.rawQuery(
                "SELECT payload FROM automation_runs",
                null,
            ).use { cursor ->
                check(cursor.moveToFirst())
                JSONObject(cursor.getString(0)).apply { remove("waitKind") }
            }
            database.update(
                "automation_runs",
                ContentValues().apply { put("payload", legacyRun.toString()) },
                null,
                null,
            )
            database.execSQL(
                "UPDATE automation_metadata SET snapshot_version = 2 WHERE singleton_id = 1",
            )
        }

        val reopenedPersistence = SQLiteAutomationSnapshotPersistence(context, fileName)
        val reopened = PersistentAutomationStorage(reopenedPersistence)
        val migrated = reopened.snapshot().runs.single()
        assertEquals(AutomationRunState.RETRY_WAIT, migrated.state)
        assertEquals(AutomationRunWaitKind.RETRY_BACKOFF, migrated.waitKind)
        assertEquals(retryAt, migrated.availableAt)
        assertEquals(
            0,
            reopened.releaseDeferredRuns(
                setOf(AutomationResolvedPrecondition.VALIDATED_NETWORK),
                now.plusSeconds(2),
            ),
        )
        assertEquals(retryAt, reopened.snapshot().runs.single().availableAt)
        reopenedPersistence.close()
    }

    @Test
    fun legacyVersionOneLeasedRunWithoutFenceIsTerminalAndNeverRedispatched() {
        val now = Instant.parse("2026-08-24T07:00:00Z")
        val definition = AutomationDefinition(
            id = AutomationId("automation.sqlite.legacy.leased"),
            revision = 1,
            enabled = true,
            schedule = AutomationSchedule(
                LocalDateTime.of(2026, 8, 24, 9, 0),
                AutomationTimeZone.Fixed("Europe/Oslo"),
                "FREQ=DAILY",
            ),
            missedRunPolicy = MissedRunPolicy(),
            retryPolicy = AutomationRetryPolicy(),
            target = CodexAutomationTarget.Independent,
            instruction = "Never redispatch this ambiguous legacy SQLite run.",
            updatedAt = now,
        )
        val persistence = SQLiteAutomationSnapshotPersistence(context, fileName)
        val storage = PersistentAutomationStorage(persistence)
        storage.upsertDefinition(definition)
        storage.recordDiscovery(
            AutomationDiscoveryBatch(
                definition.id,
                definition.revision,
                now,
                listOf(deviceItem(definition, now)),
            ),
        )
        storage.materializeDueInbox(now)
        storage.acquireNextLease(
            AutomationWorkerId("worker-sqlite-legacy-before"),
            AutomationLeaseToken("lease-token-sqlite-legacy-before"),
            AutomationBootSessionId("boot-sqlite-legacy-before"),
            now,
            Duration.ofMinutes(2),
        )
        persistence.close()

        val databaseFile = context.noBackupFilesDir.resolve(fileName)
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            val legacyRun = database.rawQuery(
                "SELECT payload FROM automation_runs",
                null,
            ).use { cursor ->
                check(cursor.moveToFirst())
                JSONObject(cursor.getString(0)).apply { remove("dispatchFence") }
            }
            database.update(
                "automation_runs",
                ContentValues().apply { put("payload", legacyRun.toString()) },
                null,
                null,
            )
            database.execSQL(
                "UPDATE automation_metadata SET snapshot_version = 1 WHERE singleton_id = 1",
            )
        }

        val reopenedPersistence = SQLiteAutomationSnapshotPersistence(context, fileName)
        val reopened = PersistentAutomationStorage(reopenedPersistence)
        val migrated = reopened.snapshot().runs.single()
        assertEquals(AutomationRunState.FAILED_TERMINAL, migrated.state)
        assertEquals("automation_outcome_ambiguous", migrated.lastFailureCode)
        assertEquals(null, migrated.dispatchFence)
        assertTrue(reopened.snapshot().leases.isEmpty())
        val recovery = reopened.recoverLeases(
            now.plus(Duration.ofDays(1)),
            AutomationBootSessionId("boot-sqlite-legacy-after"),
            forceSameBootRecovery = true,
        )
        assertEquals(0, recovery.retriesScheduled)
        assertEquals(0, recovery.terminalFailures)
        assertEquals(
            null,
            reopened.acquireNextLease(
                AutomationWorkerId("worker-sqlite-legacy-after"),
                AutomationLeaseToken("lease-token-sqlite-legacy-after"),
                AutomationBootSessionId("boot-sqlite-legacy-after"),
                now.plus(Duration.ofDays(1)),
                Duration.ofMinutes(2),
            ),
        )
        reopened.storeRecoveryState(
            AutomationRecoveryState(
                AutomationBootSessionId("boot-sqlite-legacy-after"),
                "UTC",
                now.plus(Duration.ofDays(1)),
            ),
        )
        reopenedPersistence.close()

        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            assertEquals(0L, database.rowCount("automation_leases"))
            assertEquals(
                AutomationRunState.FAILED_TERMINAL.name,
                database.rawQuery(
                    "SELECT state FROM automation_runs",
                    null,
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getString(0)
                },
            )
        }
    }

    private fun deviceItem(
        definition: AutomationDefinition,
        at: Instant,
    ) = AutomationInboxItem(
        AutomationRunKey(definition.id, at),
        definition.revision,
        at,
        at,
        AutomationDiscoverySource.TIMER,
    )

    private fun SQLiteDatabase.rowCount(table: String): Long =
        rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun java.io.File.singleRowId(table: String): Long = SQLiteDatabase.openDatabase(
        absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY,
    ).use { database ->
        database.rawQuery("SELECT rowid FROM $table", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }
}
