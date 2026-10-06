package ai.hans.standard.diagnostics.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** UUID-isolated synthetic cache fixtures only, never real Codex databases. */
class AndroidNativeMemoryHealthReaderTest {
    private lateinit var directory: File
    private lateinit var contextDirectoryAlias: File
    private lateinit var request: NativeMemoryHealthRequest
    private val reader = AndroidNativeMemoryHealthReader()
    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        // Match RuntimeService's trusted Context-root canonicalization. Android may
        // expose /data/user/0 through an alias; the reader must still reject aliases
        // supplied as arbitrary effective config paths.
        directory = Files.createTempDirectory(app.cacheDir.canonicalFile.toPath(), "native-memory-health-").toFile()
        contextDirectoryAlias = File(app.cacheDir, directory.name)
        check(directory == directory.canonicalFile)
        request = NativeMemoryHealthRequest(directory, directory, NativeMemoryHealthContract.VERSION, NativeMemoryHealthContract.ARTIFACT_SHA256)
    }
    @After fun cleanup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(directory.parentFile == app.cacheDir.canonicalFile && directory.name.startsWith("native-memory-health-"))
        for (name in listOf("link", "trusted-root-alias")) {
            val path = File(directory, name).toPath()
            if (Files.isSymbolicLink(path)) Files.delete(path)
        }
        check(directory.deleteRecursively())
    }
    private fun file() = File(directory, "memories_1.sqlite")
    private fun database(): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file(), null).also { db ->
        db.execSQL("CREATE TABLE stage1_outputs(thread_id TEXT PRIMARY KEY,source_updated_at INTEGER NOT NULL,raw_memory TEXT NOT NULL,rollout_summary TEXT NOT NULL,rollout_slug TEXT,generated_at INTEGER NOT NULL,usage_count INTEGER,last_usage INTEGER,selected_for_phase2 INTEGER NOT NULL DEFAULT 0,selected_for_phase2_source_updated_at INTEGER)")
        db.execSQL("CREATE TABLE jobs(kind TEXT NOT NULL,job_key TEXT NOT NULL,status TEXT NOT NULL,worker_id TEXT,ownership_token TEXT,started_at INTEGER,finished_at INTEGER,lease_until INTEGER,retry_at INTEGER,retry_remaining INTEGER NOT NULL,last_error TEXT,input_watermark INTEGER,last_success_watermark INTEGER,PRIMARY KEY(kind,job_key))")
        db.execSQL("CREATE TABLE _sqlx_migrations(version BIGINT PRIMARY KEY,description TEXT NOT NULL,installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,success BOOLEAN NOT NULL,checksum BLOB NOT NULL,execution_time BIGINT NOT NULL)")
        db.execSQL("INSERT INTO _sqlx_migrations(version,description,success,checksum,execution_time) VALUES(1,'memories',1,X'${NativeMemoryHealthContract.MIGRATION_SHA384}',1)")
    }
    private fun insert(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO stage1_outputs VALUES('synthetic-id',1,'PRIVATE_MEMORY','PRIVATE_SUMMARY','PRIVATE_SLUG',100,2,200,1,1)")
        db.execSQL("INSERT INTO jobs VALUES('memory_stage1','synthetic-id','done','PRIVATE_WORKER','PRIVATE_TOKEN',80,100,NULL,NULL,3,'PRIVATE_ERROR',1,1)")
    }
    @Test fun metadataOnlyAndDatabaseBytesRemainUnchanged() {
        database().use(::insert)
        val before = file().readBytes()
        val result = reader.read(request) as NativeMemoryHealthResult.Available
        assertEquals(1L, result.snapshot.stage1Count)
        assertEquals(2L, result.snapshot.totalUsageCount)
        assertEquals(200L, result.snapshot.lastUsedAtSeconds)
        assertEquals(NativeMemoryJobStatus.DONE, result.snapshot.jobs.single().status)
        assertFalse(result.toString().contains("PRIVATE"))
        assertFalse(result.toString().contains("synthetic-id"))
        assertArrayEquals(before, file().readBytes())
    }
    @Test fun secondProvenRuntimeUsesTheSameMetadataOnlyReadWithoutChangingDatabase() {
        database().use(::insert)
        val before = file().readBytes()
        val current = request.copy(runtimeVersion = NativeMemoryHealthContract.VERSION_0_154,
            runtimeArtifactSha256 = NativeMemoryHealthContract.ARTIFACT_SHA256_0_154)
        assertEquals(reader.read(request), reader.read(current))
        assertTrue(reader.read(current) is NativeMemoryHealthResult.Available)
        assertArrayEquals(before, file().readBytes())
    }
    @Test fun secondProvenRuntimeStillRejectsUnprovedMigrationChecksum() {
        database().use { db -> db.execSQL("UPDATE _sqlx_migrations SET checksum=X'00'") }
        val before = file().readBytes()
        val current = request.copy(runtimeVersion = NativeMemoryHealthContract.VERSION_0_154,
            runtimeArtifactSha256 = NativeMemoryHealthContract.ARTIFACT_SHA256_0_154)
        assertEquals(NativeMemoryHealthResult.Unsupported(NativeMemoryUnsupportedReason.SCHEMA), reader.read(current))
        assertArrayEquals(before, file().readBytes())
    }
    private fun thirdRuntimeRequest() = request.copy(runtimeVersion = NativeMemoryHealthContract.VERSION_0_155,
        runtimeArtifactSha256 = NativeMemoryHealthContract.ARTIFACT_SHA256_0_155)
    @Test fun currentPublisherRuntimeUsesUnchangedReadOnlyMigrationsWithoutChangingDatabase() {
        thirdRuntimeDatabase().use(::insert)
        val before = file().readBytes()
        val current = request.copy(runtimeVersion = NativeMemoryHealthContract.VERSION_0_160_1,
            runtimeArtifactSha256 = NativeMemoryHealthContract.ARTIFACT_SHA256_0_160_1)
        assertEquals(reader.read(thirdRuntimeRequest()), reader.read(current))
        assertTrue(reader.read(current) is NativeMemoryHealthResult.Available)
        assertArrayEquals(before, file().readBytes())
    }
    private fun thirdRuntimeDatabase(): SQLiteDatabase = database().also { db ->
        db.execSQL("CREATE TABLE consolidation_progress(singleton INTEGER PRIMARY KEY CHECK(singleton=1),max_thread_count INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT INTO consolidation_progress(singleton) VALUES(1)")
        db.execSQL("INSERT INTO _sqlx_migrations(version,description,success,checksum,execution_time) VALUES(2,'consolidation progress',1,X'${NativeMemoryHealthContract.CONSOLIDATION_MIGRATION_SHA384}',1)")
    }
    @Test fun thirdRuntimeReadsMetadataAfterExactAdditiveMigrationWithoutChangingDatabase() {
        thirdRuntimeDatabase().use(::insert)
        val before = file().readBytes()
        val result = reader.read(thirdRuntimeRequest()) as NativeMemoryHealthResult.Available
        assertEquals(1L, result.snapshot.stage1Count)
        assertEquals(2L, result.snapshot.totalUsageCount)
        assertFalse(result.toString().contains("PRIVATE"))
        assertEquals(NativeMemoryHealthResult.Unsupported(NativeMemoryUnsupportedReason.SCHEMA), reader.read(request))
        assertArrayEquals(before, file().readBytes())
    }
    @Test fun thirdRuntimeRejectsMissingSecondMigration() {
        database().close()
        val before = file().readBytes()
        assertEquals(NativeMemoryHealthResult.Unsupported(NativeMemoryUnsupportedReason.SCHEMA), reader.read(thirdRuntimeRequest()))
        assertArrayEquals(before, file().readBytes())
    }
    @Test fun thirdRuntimeRejectsWrongSecondMigrationChecksum() {
        thirdRuntimeDatabase().use { it.execSQL("UPDATE _sqlx_migrations SET checksum=X'00' WHERE version=2") }
        val before = file().readBytes()
        assertEquals(NativeMemoryHealthResult.Unsupported(NativeMemoryUnsupportedReason.SCHEMA), reader.read(thirdRuntimeRequest()))
        assertArrayEquals(before, file().readBytes())
    }
    @Test fun thirdRuntimeRejectsUnknownThirdMigration() {
        thirdRuntimeDatabase().use { it.execSQL("INSERT INTO _sqlx_migrations(version,description,success,checksum,execution_time) VALUES(3,'unknown',1,X'00',1)") }
        val before = file().readBytes()
        assertEquals(NativeMemoryHealthResult.Unsupported(NativeMemoryUnsupportedReason.SCHEMA), reader.read(thirdRuntimeRequest()))
        assertArrayEquals(before, file().readBytes())
    }
    @Test fun committedWalIsReadWithoutCheckpointingOrChangingMainFile() {
        database().use { writer ->
            assertTrue(writer.enableWriteAheadLogging())
            writer.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
            val before = file().readBytes()
            insert(writer)
            assertTrue(File(file().path + "-wal").length() > 0)
            val walBefore = File(file().path + "-wal").readBytes()
            val result = reader.read(request) as NativeMemoryHealthResult.Available
            assertEquals(1L, result.snapshot.stage1Count)
            assertArrayEquals(before, file().readBytes())
            assertArrayEquals(walBefore, File(file().path + "-wal").readBytes())
        }
    }
    @Test fun corruptDatabaseIsNotDeletedOrRepaired() {
        val raw = "Not SQLite PRIVATE_CONTENT".toByteArray()
        file().writeBytes(raw)
        assertTrue(reader.read(request) is NativeMemoryHealthResult.Unavailable)
        assertArrayEquals(raw, file().readBytes())
    }
    @Test fun unknownSchemaAndMigrationAreUnsupported() {
        database().use { db -> db.execSQL("UPDATE _sqlx_migrations SET version=2") }
        val before = file().readBytes()
        assertEquals(NativeMemoryHealthResult.Unsupported(NativeMemoryUnsupportedReason.SCHEMA), reader.read(request))
        assertArrayEquals(before, file().readBytes())
    }
    @Test fun sqliteAffinityCannotCoerceMalformedUsageIntoHealthyEvidence() {
        database().use { db ->
            insert(db)
            db.execSQL("UPDATE stage1_outputs SET usage_count='not-a-count'")
        }
        val before = file().readBytes()
        assertEquals(NativeMemoryHealthResult.Unsupported(NativeMemoryUnsupportedReason.VALUE), reader.read(request))
        assertArrayEquals(before, file().readBytes())
    }
    @Test fun missingAndUnresolvedPathsNeverCreateDatabase() {
        assertEquals(NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.MISSING), reader.read(request))
        assertEquals(NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.CONFIGURATION_UNRESOLVED),
            reader.read(request.copy(effectiveSqliteHome = null)))
        assertFalse(file().exists())
    }
    @Test fun escapingAndSymlinkPathsAreRejected() {
        database().close()
        val link = File(directory, "link")
        Files.createSymbolicLink(link.toPath(), directory.toPath())
        assertEquals(NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.UNSAFE_PATH), reader.read(request.copy(effectiveSqliteHome = link)))
        assertEquals(NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.UNSAFE_PATH), reader.read(request.copy(effectiveSqliteHome = directory.parentFile)))
    }
    @Test fun trustedContextCanonicalRootMatchesProductionButUnresolvedAliasesRemainRejected() {
        assertEquals(directory, contextDirectoryAlias.canonicalFile)
        assertEquals(NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.MISSING), reader.read(request))
        if (contextDirectoryAlias != contextDirectoryAlias.canonicalFile) {
            assertEquals(NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.UNSAFE_PATH),
                reader.read(request.copy(appOwnedRoot = contextDirectoryAlias, effectiveSqliteHome = contextDirectoryAlias)))
        }
        // Deterministic alias regression also runs where Context happens to be canonical.
        val link = File(directory, "trusted-root-alias")
        Files.createSymbolicLink(link.toPath(), directory.toPath())
        assertEquals(NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.UNSAFE_PATH),
            reader.read(request.copy(appOwnedRoot = link, effectiveSqliteHome = link)))
    }
}
