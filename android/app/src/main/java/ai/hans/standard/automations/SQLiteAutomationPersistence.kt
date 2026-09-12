package ai.hans.standard.automations

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transactional, tabular Android persistence without an external ORM dependency.
 *
 * Each durable automation entity has its own keyed table. The JSON payload is the versioned
 * projection already validated by [AutomationSnapshotJsonCodec]; indexed identity/state columns
 * enforce uniqueness and allow future incremental migrations without changing the storage
 * contract. A complete candidate snapshot is committed in one SQLite transaction, so leases,
 * run state and recovery checkpoints can never become partially visible after process death.
 */
class SQLiteAutomationSnapshotPersistence(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : AutomationSnapshotPersistence, Closeable {
    private val databaseFile: File
    private val helper: Helper

    init {
        require(fileName.matches(SAFE_FILE_NAME)) { "Invalid automation database file name" }
        databaseFile = File(context.applicationContext.noBackupFilesDir, fileName)
        helper = Helper(context.applicationContext, databaseFile.absolutePath)
        helper.setWriteAheadLoggingEnabled(true)
    }

    @Synchronized
    override fun read(): ByteArray? {
        return try {
            val database = helper.readableDatabase
            val snapshotVersion = database.committedSnapshotVersion()
            if (snapshotVersion == null) {
                null
            } else {
                val root = JSONObject().put("version", snapshotVersion)
                TABLES.forEach { table ->
                    root.put(table.arrayName, database.readPayloads(table))
                }
                root.put("recovery", database.readRecovery() ?: JSONObject.NULL)
                root.toString().toByteArray(Charsets.UTF_8).also { bytes ->
                    if (bytes.size > MAXIMUM_SNAPSHOT_BYTES) {
                        throw AutomationPersistenceException("automation_snapshot_size_invalid")
                    }
                }
            }
        } catch (error: AutomationPersistenceException) {
            throw error
        } catch (_: Exception) {
            throw AutomationPersistenceException("automation_storage_read_failed")
        }
    }

    @Synchronized
    override fun write(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAXIMUM_SNAPSHOT_BYTES) {
            throw AutomationPersistenceException("automation_snapshot_size_invalid")
        }
        val root = try {
            JSONObject(bytes.toString(Charsets.UTF_8)).also {
                require(it.getInt("version") == SNAPSHOT_VERSION)
                TABLES.forEach { table -> require(it.getJSONArray(table.arrayName).length() <= table.maximumRows) }
                require(it.has("recovery"))
            }
        } catch (_: Exception) {
            throw AutomationPersistenceException("automation_snapshot_corrupt")
        }
        val database = try {
            helper.writableDatabase
        } catch (_: Exception) {
            throw AutomationPersistenceException("automation_storage_write_failed")
        }
        try {
            database.beginTransaction()
            TABLES.forEach { table ->
                database.synchronizeRows(table, root.getJSONArray(table.arrayName))
            }
            if (!root.isNull("recovery")) {
                val recovery = root.getJSONObject("recovery")
                database.insertWithOnConflict(
                    TABLE_RECOVERY,
                    null,
                    ContentValues().apply {
                        put(COLUMN_SINGLETON_ID, SINGLETON_ID)
                        put("boot_session_id", recovery.getString("bootSessionId"))
                        put("system_zone_id", recovery.getString("systemZoneId"))
                        put("reconciled_epoch_second", instantSeconds(recovery, "reconciledAt"))
                        put("reconciled_nano", instantNanos(recovery, "reconciledAt"))
                        put(COLUMN_PAYLOAD, boundedPayload(recovery))
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            } else {
                database.delete(TABLE_RECOVERY, null, null)
            }
            database.insertWithOnConflict(
                TABLE_METADATA,
                null,
                ContentValues().apply {
                    put(COLUMN_SINGLETON_ID, SINGLETON_ID)
                    put("snapshot_version", SNAPSHOT_VERSION)
                    put("committed_epoch_millis", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            database.setTransactionSuccessful()
        } catch (error: AutomationPersistenceException) {
            throw error
        } catch (_: Exception) {
            throw AutomationPersistenceException("automation_storage_write_failed")
        } finally {
            if (database.inTransaction()) database.endTransaction()
        }
    }

    override fun close() = helper.close()

    private fun SQLiteDatabase.committedSnapshotVersion(): Int? = query(
        TABLE_METADATA,
        arrayOf("snapshot_version"),
        "$COLUMN_SINGLETON_ID = ?",
        arrayOf(SINGLETON_ID.toString()),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        cursor.getInt(0).takeIf { it in LEGACY_SNAPSHOT_VERSION..SNAPSHOT_VERSION }
    }

    private fun SQLiteDatabase.readPayloads(table: TableProjection): JSONArray = JSONArray().also { out ->
        query(
            table.tableName,
            arrayOf(COLUMN_PAYLOAD),
            null,
            null,
            null,
            null,
            table.orderBy,
        ).use { cursor ->
            var count = 0
            while (cursor.moveToNext()) {
                count += 1
                if (count > table.maximumRows) {
                    throw AutomationPersistenceException("automation_snapshot_capacity_exceeded")
                }
                out.put(JSONObject(cursor.getString(0)))
            }
        }
    }

    private fun SQLiteDatabase.readRecovery(): JSONObject? = query(
        TABLE_RECOVERY,
        arrayOf(COLUMN_PAYLOAD),
        "$COLUMN_SINGLETON_ID = ?",
        arrayOf(SINGLETON_ID.toString()),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) JSONObject(cursor.getString(0)) else null }

    /** Applies only changed rows; a lease heartbeat never rewrites bounded history tables. */
    private fun SQLiteDatabase.synchronizeRows(
        table: TableProjection,
        rows: JSONArray,
    ) {
        val existing = linkedMapOf<String, String>()
        query(
            table.tableName,
            arrayOf(COLUMN_PAYLOAD),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val payload = cursor.getString(0)
                val row = JSONObject(payload)
                val identity = table.identity(row)
                check(existing.put(identity.key, payload) == null)
            }
        }

        val retained = hashSetOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val identity = table.identity(row)
            require(retained.add(identity.key))
            val payload = boundedPayload(row)
            if (existing[identity.key] != payload) {
                val values = table.values(row)
                values.put(COLUMN_PAYLOAD, payload)
                if (
                    insertWithOnConflict(
                        table.tableName,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    ) == -1L
                ) {
                    throw SQLiteException("Automation row upsert failed")
                }
            }
        }
        existing.keys.filterNot(retained::contains).forEach { removedKey ->
            val identity = table.identity(JSONObject(existing.getValue(removedKey)))
            delete(table.tableName, identity.whereClause, identity.whereArguments)
        }
    }

    private class Helper(
        context: Context,
        absolutePath: String,
    ) : SQLiteOpenHelper(context, absolutePath, null, DATABASE_SCHEMA_VERSION) {
        override fun onConfigure(database: SQLiteDatabase) {
            super.onConfigure(database)
            database.setForeignKeyConstraintsEnabled(false)
        }

        override fun onCreate(database: SQLiteDatabase) {
            DDL.forEach(database::execSQL)
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException("Unsupported automation database upgrade $oldVersion->$newVersion")
        }

        override fun onDowngrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException("Unsupported automation database downgrade $oldVersion->$newVersion")
        }
    }

    private data class TableProjection(
        val tableName: String,
        val arrayName: String,
        val maximumRows: Int,
        val orderBy: String,
        val identity: (JSONObject) -> RowIdentity,
        val values: (JSONObject) -> ContentValues,
    )

    private data class RowIdentity(
        val key: String,
        val whereClause: String,
        val whereArguments: Array<String>,
    )

    companion object {
        const val DEFAULT_FILE_NAME = "hans_automations_v1.db"
        private const val DATABASE_SCHEMA_VERSION = 1
        private const val LEGACY_SNAPSHOT_VERSION = 1
        private const val SNAPSHOT_VERSION = 3
        private const val SINGLETON_ID = 1
        private const val MAXIMUM_SNAPSHOT_BYTES = 16 * 1024 * 1024
        private const val MAXIMUM_ROW_BYTES = 256 * 1024
        private const val COLUMN_SINGLETON_ID = "singleton_id"
        private const val COLUMN_PAYLOAD = "payload"
        private const val TABLE_METADATA = "automation_metadata"
        private const val TABLE_DEFINITIONS = "automation_definitions"
        private const val TABLE_INBOX = "automation_inbox"
        private const val TABLE_RUNS = "automation_runs"
        private const val TABLE_RECEIPTS = "automation_run_receipts"
        private const val TABLE_CONFIRMATIONS = "automation_confirmations"
        private const val TABLE_MANUAL = "automation_manual_invocations"
        private const val TABLE_LEASES = "automation_leases"
        private const val TABLE_CURSORS = "automation_cursors"
        private const val TABLE_RECOVERY = "automation_recovery"
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{2,95}")

        private val TABLES = listOf(
            TableProjection(
                TABLE_DEFINITIONS,
                "definitions",
                10_000,
                "automation_id ASC",
                ::definitionIdentity,
            ) { row ->
                ContentValues().apply {
                    put("automation_id", row.getString("id"))
                    put("revision", row.getLong("revision"))
                    put("enabled", if (row.getBoolean("enabled")) 1 else 0)
                }
            },
            runKeyTable(
                TABLE_INBOX,
                "inbox",
                100_000,
                "ready_epoch_second ASC, ready_nano ASC, automation_id ASC",
            ) { values, row ->
                values.put("definition_revision", row.getLong("definitionRevision"))
                values.put("ready_epoch_second", instantSeconds(row, "readyAt"))
                values.put("ready_nano", instantNanos(row, "readyAt"))
            },
            runKeyTable(
                TABLE_RUNS,
                "runs",
                100_000,
                "updated_epoch_second ASC, updated_nano ASC, automation_id ASC",
            ) { values, row ->
                values.put("definition_revision", row.getLong("definitionRevision"))
                values.put("state", row.getString("state"))
                values.put("available_epoch_second", instantSeconds(row, "availableAt"))
                values.put("available_nano", instantNanos(row, "availableAt"))
                values.put("updated_epoch_second", instantSeconds(row, "updatedAt"))
                values.put("updated_nano", instantNanos(row, "updatedAt"))
            },
            runKeyTable(
                TABLE_RECEIPTS,
                "receipts",
                500_000,
                "completed_epoch_second ASC, completed_nano ASC, automation_id ASC",
            ) { values, row ->
                values.put("terminal_state", row.getString("terminalState"))
                values.put("completed_epoch_second", instantSeconds(row, "completedAt"))
                values.put("completed_nano", instantNanos(row, "completedAt"))
            },
            runKeyTable(
                TABLE_CONFIRMATIONS,
                "confirmations",
                10_000,
                "expires_epoch_second ASC, expires_nano ASC, automation_id ASC",
            ) { values, row ->
                values.put("confirmation_id", row.getString("id"))
                values.put("definition_revision", row.getLong("definitionRevision"))
                values.put("expires_epoch_second", instantSeconds(row, "expiresAt"))
                values.put("expires_nano", instantNanos(row, "expiresAt"))
            },
            runKeyTable(
                TABLE_MANUAL,
                "manualInvocations",
                500_000,
                "requested_epoch_second ASC, requested_nano ASC, request_id ASC",
            ) { values, row ->
                values.put("request_id", row.getString("requestId"))
                values.put("definition_revision", row.getLong("definitionRevision"))
                values.put("requested_epoch_second", instantSeconds(row, "requestedAt"))
                values.put("requested_nano", instantNanos(row, "requestedAt"))
            },
            runKeyTable(
                TABLE_LEASES,
                "leases",
                10_000,
                "expires_epoch_second ASC, expires_nano ASC, automation_id ASC",
            ) { values, row ->
                values.put("lease_token", row.getString("token"))
                values.put("expires_epoch_second", instantSeconds(row, "expiresAt"))
                values.put("expires_nano", instantNanos(row, "expiresAt"))
            },
            TableProjection(
                TABLE_CURSORS,
                "cursors",
                10_000,
                "automation_id ASC",
                ::cursorIdentity,
            ) { row ->
                ContentValues().apply {
                    put("automation_id", row.getString("automationId"))
                    put("definition_revision", row.getLong("definitionRevision"))
                    put("evaluated_epoch_second", instantSeconds(row, "evaluatedThrough"))
                    put("evaluated_nano", instantNanos(row, "evaluatedThrough"))
                }
            },
        )

        private fun runKeyTable(
            tableName: String,
            arrayName: String,
            maximumRows: Int,
            orderBy: String,
            additionalValues: (ContentValues, JSONObject) -> Unit,
        ) = TableProjection(
            tableName,
            arrayName,
            maximumRows,
            orderBy,
            ::runIdentity,
        ) { row ->
            ContentValues().apply {
                put("automation_id", row.getString("automationId"))
                put("scheduled_epoch_second", instantSeconds(row, "scheduledAt"))
                put("scheduled_nano", instantNanos(row, "scheduledAt"))
                additionalValues(this, row)
            }
        }

        private fun instantSeconds(row: JSONObject, key: String): Long =
            Instant.parse(row.getString(key)).epochSecond

        private fun instantNanos(row: JSONObject, key: String): Int =
            Instant.parse(row.getString(key)).nano

        private fun definitionIdentity(row: JSONObject) = RowIdentity(
            key = row.getString("id"),
            whereClause = "automation_id = ?",
            whereArguments = arrayOf(row.getString("id")),
        )

        private fun cursorIdentity(row: JSONObject) = RowIdentity(
            key = row.getString("automationId"),
            whereClause = "automation_id = ?",
            whereArguments = arrayOf(row.getString("automationId")),
        )

        private fun runIdentity(row: JSONObject): RowIdentity {
            val automationId = row.getString("automationId")
            val seconds = instantSeconds(row, "scheduledAt").toString()
            val nanos = instantNanos(row, "scheduledAt").toString()
            return RowIdentity(
                key = "$automationId\u0000$seconds\u0000$nanos",
                whereClause =
                    "automation_id = ? AND scheduled_epoch_second = ? AND scheduled_nano = ?",
                whereArguments = arrayOf(automationId, seconds, nanos),
            )
        }

        private fun boundedPayload(row: JSONObject): String = row.toString().also { payload ->
            require(payload.toByteArray(Charsets.UTF_8).size <= MAXIMUM_ROW_BYTES)
        }

        private val DDL = listOf(
            """CREATE TABLE $TABLE_METADATA (
                $COLUMN_SINGLETON_ID INTEGER NOT NULL PRIMARY KEY CHECK ($COLUMN_SINGLETON_ID = 1),
                snapshot_version INTEGER NOT NULL,
                committed_epoch_millis INTEGER NOT NULL
            )""".trimIndent(),
            """CREATE TABLE $TABLE_DEFINITIONS (
                automation_id TEXT NOT NULL PRIMARY KEY,
                revision INTEGER NOT NULL CHECK (revision >= 1),
                enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
                $COLUMN_PAYLOAD TEXT NOT NULL
            )""".trimIndent(),
            runKeyDdl(
                TABLE_INBOX,
                "definition_revision INTEGER NOT NULL, ready_epoch_second INTEGER NOT NULL, ready_nano INTEGER NOT NULL",
            ),
            runKeyDdl(
                TABLE_RUNS,
                "definition_revision INTEGER NOT NULL, state TEXT NOT NULL, " +
                    "available_epoch_second INTEGER NOT NULL, available_nano INTEGER NOT NULL, " +
                    "updated_epoch_second INTEGER NOT NULL, updated_nano INTEGER NOT NULL",
            ),
            runKeyDdl(
                TABLE_RECEIPTS,
                "terminal_state TEXT NOT NULL, completed_epoch_second INTEGER NOT NULL, completed_nano INTEGER NOT NULL",
            ),
            runKeyDdl(
                TABLE_CONFIRMATIONS,
                "confirmation_id TEXT NOT NULL UNIQUE, definition_revision INTEGER NOT NULL, " +
                    "expires_epoch_second INTEGER NOT NULL, expires_nano INTEGER NOT NULL",
            ),
            runKeyDdl(
                TABLE_MANUAL,
                "request_id TEXT NOT NULL UNIQUE, definition_revision INTEGER NOT NULL, " +
                    "requested_epoch_second INTEGER NOT NULL, requested_nano INTEGER NOT NULL",
            ),
            runKeyDdl(
                TABLE_LEASES,
                "lease_token TEXT NOT NULL UNIQUE, expires_epoch_second INTEGER NOT NULL, expires_nano INTEGER NOT NULL",
            ),
            """CREATE TABLE $TABLE_CURSORS (
                automation_id TEXT NOT NULL PRIMARY KEY,
                definition_revision INTEGER NOT NULL,
                evaluated_epoch_second INTEGER NOT NULL,
                evaluated_nano INTEGER NOT NULL,
                $COLUMN_PAYLOAD TEXT NOT NULL
            )""".trimIndent(),
            """CREATE TABLE $TABLE_RECOVERY (
                $COLUMN_SINGLETON_ID INTEGER NOT NULL PRIMARY KEY CHECK ($COLUMN_SINGLETON_ID = 1),
                boot_session_id TEXT NOT NULL,
                system_zone_id TEXT NOT NULL,
                reconciled_epoch_second INTEGER NOT NULL,
                reconciled_nano INTEGER NOT NULL,
                $COLUMN_PAYLOAD TEXT NOT NULL
            )""".trimIndent(),
            "CREATE INDEX automation_inbox_ready ON $TABLE_INBOX (ready_epoch_second, ready_nano)",
            "CREATE INDEX automation_runs_available ON $TABLE_RUNS (state, available_epoch_second, available_nano)",
            "CREATE INDEX automation_runs_updated ON $TABLE_RUNS (updated_epoch_second, updated_nano)",
            "CREATE INDEX automation_leases_expiry ON $TABLE_LEASES (expires_epoch_second, expires_nano)",
        )

        private fun runKeyDdl(tableName: String, additionalColumns: String): String =
            """CREATE TABLE $tableName (
                automation_id TEXT NOT NULL,
                scheduled_epoch_second INTEGER NOT NULL,
                scheduled_nano INTEGER NOT NULL,
                $additionalColumns,
                $COLUMN_PAYLOAD TEXT NOT NULL,
                PRIMARY KEY (automation_id, scheduled_epoch_second, scheduled_nano)
            )""".trimIndent()
    }
}
