package ai.hans.standard.diagnostics.memory

import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.os.Looper
import android.os.Process
import android.system.Os
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** On-demand metadata only. Never uses a helper, migrator, integrity repair, checkpoint or journal pragma.
 * Android's default corruption handler deletes files: explicitly replace it with a nondeleting handler.
 * The normal read-only SQLite WAL reader is used; WAL/SHM are neither copied nor removed.
 */
class AndroidNativeMemoryHealthReader {
    fun read(request: NativeMemoryHealthRequest): NativeMemoryHealthResult {
        if (!NativeMemoryHealthContract.supports(request)) return unsupported(NativeMemoryUnsupportedReason.RUNTIME)
        val home = request.effectiveSqliteHome ?: return unavailable(NativeMemoryUnavailableReason.CONFIGURATION_UNRESOLVED)
        if (Looper.myLooper() == Looper.getMainLooper()) return unavailable(NativeMemoryUnavailableReason.MAIN_THREAD)
        return try {
            val root = request.appOwnedRoot
            if (!root.isAbsolute || !home.isAbsolute || root.canonicalFile != root.absoluteFile ||
                home.canonicalFile != home.absoluteFile || !home.toPath().startsWith(root.toPath()) ||
                !root.isDirectory || Os.stat(root.path).st_uid != Process.myUid()) {
                return unavailable(NativeMemoryUnavailableReason.UNSAFE_PATH)
            }
            val file = File(home, NativeMemoryHealthContract.FILE_NAME)
            for (candidate in listOf(file, File(file.path + "-wal"), File(file.path + "-shm"))) {
                if (!candidate.exists()) continue
                if (candidate.canonicalFile != candidate.absoluteFile || !candidate.isFile ||
                    Os.stat(candidate.path).st_uid != Process.myUid()) return unavailable(NativeMemoryUnavailableReason.UNSAFE_PATH)
                if (candidate.length() > NativeMemoryHealthContract.MAX_FILE_BYTES) return unavailable(NativeMemoryUnavailableReason.TOO_LARGE)
            }
            if (!file.isFile) return unavailable(NativeMemoryUnavailableReason.MISSING)
            val corrupt = AtomicBoolean(false)
            SQLiteDatabase.openDatabase(file.path, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                DatabaseErrorHandler { corrupt.set(true) }).use { db ->
                check(db.isReadOnly)
                if (!schemaMatches(db)) return unsupported(NativeMemoryUnsupportedReason.SCHEMA)
                if (corrupt.get()) return unavailable(NativeMemoryUnavailableReason.CORRUPT)
                for (table in listOf("stage1_outputs", "jobs")) {
                    db.rawQuery("SELECT COUNT(*) FROM (SELECT 1 FROM $table LIMIT 100001)", null).use { cursor ->
                        check(cursor.moveToFirst())
                        if (integer(cursor, 0) > NativeMemoryHealthContract.MAX_ROWS) return unavailable(NativeMemoryUnavailableReason.TOO_LARGE)
                    }
                }
                // SQLite affinity is not a type constraint; do not let SUM coerce malformed values.
                db.rawQuery(INVALID_VALUES, null).use { cursor ->
                    if (cursor.moveToFirst()) return unsupported(NativeMemoryUnsupportedReason.VALUE)
                }
                // One SELECT supplies a single SQLite read snapshot across both aggregates.
                val jobs = mutableListOf<NativeMemoryJobAggregate>()
                var stageCount = 0L
                var selectedCount = 0L
                var usageCount = 0L
                var generated: Long? = null
                var used: Long? = null
                db.rawQuery(AGGREGATES, null).use { cursor ->
                    while (cursor.moveToNext()) {
                        when (integer(cursor, 0)) {
                            0L -> {
                                stageCount = integer(cursor, 3)
                                selectedCount = integer(cursor, 4)
                                usageCount = integer(cursor, 5)
                                generated = optionalInteger(cursor, 6)
                                used = optionalInteger(cursor, 7)
                            }
                            1L -> {
                                require(jobs.size < 8)
                                jobs += NativeMemoryJobAggregate(NativeMemoryHealthContract.kind(cursor.getString(1)),
                                    NativeMemoryHealthContract.status(cursor.getString(2)), integer(cursor, 3),
                                    optionalInteger(cursor, 4), optionalInteger(cursor, 5),
                                    optionalInteger(cursor, 6), optionalInteger(cursor, 7))
                            }
                            else -> error("unsupported aggregate")
                        }
                    }
                }
                if (corrupt.get()) unavailable(NativeMemoryUnavailableReason.CORRUPT)
                else NativeMemoryHealthResult.Available(NativeMemoryHealthSnapshot(stageCount, selectedCount,
                    usageCount, generated, used, jobs))
            }
        } catch (_: SQLiteDatabaseLockedException) {
            unavailable(NativeMemoryUnavailableReason.BUSY)
        } catch (_: SQLiteDatabaseCorruptException) {
            unavailable(NativeMemoryUnavailableReason.CORRUPT)
        } catch (_: IllegalArgumentException) {
            unsupported(NativeMemoryUnsupportedReason.VALUE)
        } catch (_: SQLiteException) {
            unavailable(NativeMemoryUnavailableReason.IO)
        } catch (_: Exception) {
            unavailable(NativeMemoryUnavailableReason.IO)
        }
    }

    private fun schemaMatches(db: SQLiteDatabase): Boolean {
        val required = mapOf(
            "stage1_outputs" to "thread_id:TEXT,source_updated_at:INTEGER,raw_memory:TEXT,rollout_summary:TEXT,rollout_slug:TEXT,generated_at:INTEGER,usage_count:INTEGER,last_usage:INTEGER,selected_for_phase2:INTEGER,selected_for_phase2_source_updated_at:INTEGER",
            "jobs" to "kind:TEXT,job_key:TEXT,status:TEXT,worker_id:TEXT,ownership_token:TEXT,started_at:INTEGER,finished_at:INTEGER,lease_until:INTEGER,retry_at:INTEGER,retry_remaining:INTEGER,last_error:TEXT,input_watermark:INTEGER,last_success_watermark:INTEGER",
            "_sqlx_migrations" to "version:BIGINT,description:TEXT,installed_on:TIMESTAMP,success:BOOLEAN,checksum:BLOB,execution_time:BIGINT",
        )
        for ((table, signature) in required) {
            db.rawQuery("SELECT type FROM sqlite_master WHERE name = ?", arrayOf(table)).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "table" || cursor.moveToNext()) return false
            }
            val actual = mutableListOf<String>()
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (actual.size >= 32) return false
                    actual += cursor.getString(1) + ":" + cursor.getString(2).uppercase(java.util.Locale.ROOT)
                }
            }
            if (actual.joinToString(",") != signature) return false
        }
        db.rawQuery("SELECT version, success, hex(checksum) FROM _sqlx_migrations LIMIT 2", null).use { cursor ->
            return cursor.moveToFirst() && integer(cursor, 0) == 1L && integer(cursor, 1) == 1L &&
                cursor.getString(2).lowercase(java.util.Locale.ROOT) == NativeMemoryHealthContract.MIGRATION_SHA384 && !cursor.moveToNext()
        }
    }

    private fun integer(cursor: Cursor, column: Int): Long {
        require(cursor.getType(column) == Cursor.FIELD_TYPE_INTEGER)
        return cursor.getLong(column).also { require(it >= 0) }
    }
    private fun optionalInteger(cursor: Cursor, column: Int): Long? =
        if (cursor.isNull(column)) null else integer(cursor, column)
    private fun unavailable(reason: NativeMemoryUnavailableReason) = NativeMemoryHealthResult.Unavailable(reason)
    private fun unsupported(reason: NativeMemoryUnsupportedReason) = NativeMemoryHealthResult.Unsupported(reason)

    private companion object {
        const val INVALID_VALUES = """
            SELECT 1 FROM stage1_outputs WHERE
              typeof(generated_at) <> 'integer' OR generated_at < 0 OR
              typeof(selected_for_phase2) <> 'integer' OR selected_for_phase2 NOT IN (0,1) OR
              (usage_count IS NOT NULL AND (typeof(usage_count) <> 'integer' OR usage_count < 0)) OR
              (last_usage IS NOT NULL AND (typeof(last_usage) <> 'integer' OR last_usage < 0))
            UNION ALL
            SELECT 1 FROM jobs WHERE
              (started_at IS NOT NULL AND (typeof(started_at) <> 'integer' OR started_at < 0)) OR
              (finished_at IS NOT NULL AND (typeof(finished_at) <> 'integer' OR finished_at < 0)) OR
              (lease_until IS NOT NULL AND (typeof(lease_until) <> 'integer' OR lease_until < 0)) OR
              (retry_at IS NOT NULL AND (typeof(retry_at) <> 'integer' OR retry_at < 0)) LIMIT 1
        """
        const val AGGREGATES = """
            SELECT 0 AS category, NULL AS kind, NULL AS status, COUNT(*) AS n,
                   COALESCE(SUM(selected_for_phase2),0), COALESCE(SUM(usage_count),0),
                   MAX(generated_at), MAX(last_usage) FROM stage1_outputs
            UNION ALL
            SELECT 1, kind, status, COUNT(*), MAX(started_at), MAX(finished_at), MAX(lease_until), MAX(retry_at)
            FROM jobs GROUP BY kind, status
        """
    }
}
