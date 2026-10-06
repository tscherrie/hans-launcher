package ai.hans.standard.diagnostics.memory

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMemoryHealthTest {
    @Test fun onlyExactRuntimeIsSupported() {
        val request = NativeMemoryHealthRequest(File("/app"), File("/app/codex"),
            NativeMemoryHealthContract.VERSION, NativeMemoryHealthContract.ARTIFACT_SHA256)
        assertTrue(NativeMemoryHealthContract.supports(request))
        assertFalse(NativeMemoryHealthContract.supports(request.copy(runtimeVersion = "0.150.0")))
        assertFalse(NativeMemoryHealthContract.supports(request.copy(runtimeArtifactSha256 = "0".repeat(64))))
    }
    @Test fun secondProvenRuntimeDoesNotAuthorizeCrossedOrFutureIdentities() {
        val request = NativeMemoryHealthRequest(File("/app"), File("/app/codex"),
            "0.154.0", "0c2495cedd0e01fd6ba1e9d949b637f55ac283e6019b998024c010788da8c508")
        assertTrue(NativeMemoryHealthContract.supports(request))
        assertFalse(NativeMemoryHealthContract.supports(request.copy(runtimeVersion = "0.155.0")))
        assertFalse(NativeMemoryHealthContract.supports(request.copy(runtimeVersion = NativeMemoryHealthContract.VERSION)))
        assertFalse(NativeMemoryHealthContract.supports(request.copy(runtimeArtifactSha256 = NativeMemoryHealthContract.ARTIFACT_SHA256)))
        assertFalse(NativeMemoryHealthContract.supports(request.copy(runtimeArtifactSha256 = "0".repeat(64))))
    }
    @Test fun retainedSecondRuntimeMigrationMatchesTheUnchangedReadOnlySchemaContract() {
        val proof = JSONObject(sourceFile("release/third-party/runtime-0.154.0/source-compatibility.json").readText())
        assertEquals(NativeMemoryHealthContract.VERSION_0_154, proof.getString("runtimeVersion"))
        assertEquals(NativeMemoryHealthContract.COMMIT_0_154, proof.getString("runtimeCommit"))
        assertEquals(NativeMemoryHealthContract.ARTIFACT_SHA256_0_154, proof.getString("runtimeArtifactSha256"))
        assertEquals(NativeMemoryHealthContract.VERSION, proof.getString("previousRuntimeVersion"))
        assertEquals(NativeMemoryHealthContract.COMMIT, proof.getString("previousRuntimeCommit"))
        val migration = proof.getJSONObject("memoryMigration")
        val bytes = sourceFile(migration.getString("retainedFile")).readBytes()
        assertEquals(989, bytes.size)
        assertEquals(1, migration.getInt("directoryFileCount"))
        assertTrue(migration.getBoolean("identicalInPreviousRuntime"))
        assertEquals(NativeMemoryHealthContract.MIGRATION_SHA384, digest("SHA-384", bytes))
        assertEquals(NativeMemoryHealthContract.MIGRATION_SHA384, migration.getString("sha384"))
        assertEquals(migration.getString("gitBlobSha1"), gitBlob(bytes))
    }
    @Test fun thirdRuntimeRequiresItsExactIdentityAndBothRetainedMigrations() {
        val request = NativeMemoryHealthRequest(File("/app"), File("/app/codex"),
            NativeMemoryHealthContract.VERSION_0_155, NativeMemoryHealthContract.ARTIFACT_SHA256_0_155)
        assertTrue(NativeMemoryHealthContract.supports(request))
        assertFalse(NativeMemoryHealthContract.supports(request.copy(runtimeVersion = "0.156.0")))
        assertFalse(NativeMemoryHealthContract.supports(request.copy(runtimeArtifactSha256 = NativeMemoryHealthContract.ARTIFACT_SHA256_0_154)))
        assertEquals(listOf(NativeMemoryHealthContract.MIGRATION_SHA384,
            NativeMemoryHealthContract.CONSOLIDATION_MIGRATION_SHA384), NativeMemoryHealthContract.migrationChecksums(request))
        val first = sourceFile("runtime/evidence/0.155.0/source/0001_memories.sql").readBytes()
        val second = sourceFile("runtime/evidence/0.155.0/source/0002_consolidation_progress.sql").readBytes()
        assertEquals(NativeMemoryHealthContract.MIGRATION_SHA384, digest("SHA-384", first))
        assertEquals(NativeMemoryHealthContract.CONSOLIDATION_MIGRATION_SHA384, digest("SHA-384", second))
        assertEquals("4e5b3a1e9fd55d8682047afd0a3325536be9f39f", gitBlob(first))
        assertEquals("ec84e3a127e931b5b6f22fdadd80cc1632d0122f", gitBlob(second))
    }
    @Test fun currentRuntimeRequiresExactPublisherIdentityAndUnchangedRetainedMigrations() {
        val request = NativeMemoryHealthRequest(File("/app"), File("/app/codex"),
            NativeMemoryHealthContract.VERSION_0_160_1, NativeMemoryHealthContract.ARTIFACT_SHA256_0_160_1)
        assertTrue(NativeMemoryHealthContract.supports(request))
        assertFalse(NativeMemoryHealthContract.supports(request.copy(runtimeVersion = "0.160.2")))
        assertFalse(NativeMemoryHealthContract.supports(request.copy(runtimeArtifactSha256 = NativeMemoryHealthContract.ARTIFACT_SHA256_0_155)))
        val lock = JSONObject(sourceFile("runtime/runtime.lock.json").readText())
        assertEquals(NativeMemoryHealthContract.COMMIT_0_160_1, lock.getJSONObject("upstream").getString("commit"))
        assertEquals(request.runtimeArtifactSha256, lock.getJSONObject("runtime").getString("extractedSha256"))
        for (name in listOf("0001_memories.sql", "0002_consolidation_progress.sql")) {
            assertArrayEquals(sourceFile("runtime/evidence/0.155.0/source/$name").readBytes(),
                sourceFile("runtime/evidence/0.160.1/source/$name").readBytes())
        }
        assertEquals(listOf(NativeMemoryHealthContract.MIGRATION_SHA384,
            NativeMemoryHealthContract.CONSOLIDATION_MIGRATION_SHA384), NativeMemoryHealthContract.migrationChecksums(request))
    }
    @Test fun secondRuntimeTopLevelNoticesMatchExistingAssetsWithoutClaimingPublicClosure() {
        val proof = JSONObject(sourceFile("release/third-party/runtime-0.154.0/source-compatibility.json").readText())
        assertFalse(proof.getBoolean("publicReady"))
        assertTrue(proof.getString("scope").contains("not-transitive-license-or-public-release-clearance"))
        assertTrue(proof.getJSONArray("remainingScope").length() > 0)
        val notices = proof.getJSONArray("topLevelNotices")
        assertEquals(2, notices.length())
        for (index in 0 until notices.length()) {
            val notice = notices.getJSONObject(index)
            val bytes = sourceFile(notice.getString("existingApkAsset")).readBytes()
            assertEquals(notice.getInt("bytes"), bytes.size)
            assertEquals(notice.getString("sha256"), digest("SHA-256", bytes))
            assertEquals(notice.getString("gitBlobSha1"), gitBlob(bytes))
            assertTrue(notice.getBoolean("identicalInPreviousRuntime"))
        }
    }
    @Test fun emptySnapshotIsMetadataNotEvidenceOfUse() {
        assertEquals(0L, NativeMemoryHealthSnapshot(0, 0, 0, null, null, emptyList()).totalUsageCount)
    }
    @Test fun unsupportedStatusesAreNeverEchoed() {
        try { NativeMemoryHealthContract.status("PRIVATE_CONTENT"); fail() }
        catch (error: IllegalArgumentException) { assertFalse(error.message!!.contains("PRIVATE_CONTENT")) }
    }
    @Test fun invalidCountsAndTimesAreRejected() {
        for (action in listOf<() -> Unit>(
            { NativeMemoryHealthSnapshot(1, 2, 0, null, null, emptyList()) },
            { NativeMemoryHealthSnapshot(1, 0, -1, null, null, emptyList()) },
            { NativeMemoryHealthSnapshot(1, 0, 0, -1, null, emptyList()) })) {
            try { action(); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun jobKindsHaveFixedVocabulary() {
        assertEquals(NativeMemoryJobKind.EXTRACTION, NativeMemoryHealthContract.kind("memory_stage1"))
        assertEquals(NativeMemoryJobKind.CONSOLIDATION, NativeMemoryHealthContract.kind("memory_consolidate_global"))
    }

    private fun sourceFile(relative: String): File = requireNotNull(
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, relative) }.firstOrNull(File::isFile),
    ) { "Required runtime source evidence missing: $relative" }

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    private fun gitBlob(bytes: ByteArray): String =
        digest("SHA-1", "blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8) + bytes)
}
