package ai.hans.standard.diagnostics.memory

import java.io.File

/** Trusted caller supplies an already resolved effective path, never a user/model path or guessed TOML value.
 * Pinned Codex precedence: effective config.sqlite_home > CODEX_SQLITE_HOME > CODEX_HOME.
 * A relative environment override is resolved by Codex against its resolved working directory.
 */
data class NativeMemoryHealthRequest(
    val appOwnedRoot: File,
    val effectiveSqliteHome: File?,
    val runtimeVersion: String,
    val runtimeArtifactSha256: String,
)

enum class NativeMemoryUnavailableReason {
    CONFIGURATION_UNRESOLVED, MAIN_THREAD, MISSING, UNSAFE_PATH, TOO_LARGE, BUSY, CORRUPT, IO,
}
enum class NativeMemoryUnsupportedReason { RUNTIME, SCHEMA, VALUE }
enum class NativeMemoryJobKind { EXTRACTION, CONSOLIDATION }
enum class NativeMemoryJobStatus { PENDING, RUNNING, DONE, ERROR }
data class NativeMemoryJobAggregate(
    val kind: NativeMemoryJobKind,
    val status: NativeMemoryJobStatus,
    val count: Long,
    val lastStartedAtSeconds: Long?,
    val lastFinishedAtSeconds: Long?,
    val latestLeaseUntilSeconds: Long?,
    val latestRetryAtSeconds: Long?,
)
data class NativeMemoryHealthSnapshot(
    val stage1Count: Long,
    val selectedCount: Long,
    val totalUsageCount: Long,
    val lastGeneratedAtSeconds: Long?,
    val lastUsedAtSeconds: Long?,
    val jobs: List<NativeMemoryJobAggregate>,
) {
    init {
        require(stage1Count >= 0 && selectedCount in 0..stage1Count && totalUsageCount >= 0)
        require(lastGeneratedAtSeconds == null || lastGeneratedAtSeconds >= 0)
        require(lastUsedAtSeconds == null || lastUsedAtSeconds >= 0)
        require(jobs.size <= 8 && jobs.map { it.kind to it.status }.distinct().size == jobs.size)
        require(jobs.all { job -> job.count > 0 && listOf(job.lastStartedAtSeconds,
            job.lastFinishedAtSeconds, job.latestLeaseUntilSeconds, job.latestRetryAtSeconds)
            .all { it == null || it >= 0 } })
    }
}

sealed interface NativeMemoryHealthResult {
    data class Available(val snapshot: NativeMemoryHealthSnapshot) : NativeMemoryHealthResult
    data class Unavailable(val reason: NativeMemoryUnavailableReason) : NativeMemoryHealthResult
    data class Unsupported(val reason: NativeMemoryUnsupportedReason) : NativeMemoryHealthResult
}

internal object NativeMemoryHealthContract {
    // Historical 0.151.0 identity remains valid for its unchanged schema.
    const val VERSION = "0.151.0"
    const val COMMIT = "78c290807ce710180111df227df3b7a4fe845452"
    const val ARTIFACT_SHA256 = "aca90558b36446220378affaeeb1e95eff85177f5fec663ae372cd1f6108a184"
    // Exact 0.154.0 official App Server payload. Its sole memory migration is byte-identical:
    // release/third-party/runtime-0.154.0/source-compatibility.json. No version-range trust.
    const val VERSION_0_154 = "0.154.0"
    const val COMMIT_0_154 = "6b9826e3aa83b1a5947db50f4332cb9c65f1b340"
    const val ARTIFACT_SHA256_0_154 = "0c2495cedd0e01fd6ba1e9d949b637f55ac283e6019b998024c010788da8c508"
    // 0.155 adds exactly migration 2; its additive table does not alter either aggregate.
    // Retained source bytes: runtime/evidence/0.155.0/source/000{1,2}_*.sql.
    const val VERSION_0_155 = "0.155.0"
    const val COMMIT_0_155 = "f0a1b8f0849d90960bc406b848f32e5a129b0457"
    const val ARTIFACT_SHA256_0_155 = "a18a82fbfcecec13f320545f4b8e0c542247e16918fdbc61f3f8f7e4d121306d"
    // Source-pinned 0.160.1 retains exactly the same two migrations; no version-range trust.
    const val VERSION_0_160_1 = "0.160.1"
    const val COMMIT_0_160_1 = "d27764b82f7118f674371e6d6e76271d9d606edb"
    const val ARTIFACT_SHA256_0_160_1 = "a949b9fd5a00d4c0be2243d04073d666293c3633355b51e687365839fa528170"
    const val FILE_NAME = "memories_1.sqlite"
    const val MIGRATION_SHA384 = "a1af50da50775a70f98da680006b7df4501956753ae98511ab727aa295915d9ae6f4190b4eaa5abc4fd7891642f7d21d"
    const val CONSOLIDATION_MIGRATION_SHA384 = "18f0a8dd7fe9a847b30d719029066d7a78e0bc64310dc66e4f7708bad6f1a0a594c0dbd14fec1ccb410cd7ae75dd7b10"
    const val MAX_FILE_BYTES = 128L * 1024 * 1024
    const val MAX_ROWS = 100_000L
    fun supports(request: NativeMemoryHealthRequest): Boolean = when (request.runtimeVersion) {
        VERSION -> request.runtimeArtifactSha256 == ARTIFACT_SHA256
        VERSION_0_154 -> request.runtimeArtifactSha256 == ARTIFACT_SHA256_0_154
        VERSION_0_155 -> request.runtimeArtifactSha256 == ARTIFACT_SHA256_0_155
        VERSION_0_160_1 -> request.runtimeArtifactSha256 == ARTIFACT_SHA256_0_160_1
        else -> false
    }
    fun migrationChecksums(request: NativeMemoryHealthRequest): List<String> {
        require(supports(request))
        return if (request.runtimeVersion in setOf(VERSION_0_155, VERSION_0_160_1)) {
            listOf(MIGRATION_SHA384, CONSOLIDATION_MIGRATION_SHA384)
        } else listOf(MIGRATION_SHA384)
    }
    fun kind(value: String): NativeMemoryJobKind = when (value) {
        "memory_stage1" -> NativeMemoryJobKind.EXTRACTION
        "memory_consolidate_global" -> NativeMemoryJobKind.CONSOLIDATION
        else -> throw IllegalArgumentException("unsupported kind")
    }
    fun status(value: String): NativeMemoryJobStatus = when (value) {
        "pending" -> NativeMemoryJobStatus.PENDING
        "running" -> NativeMemoryJobStatus.RUNNING
        "done" -> NativeMemoryJobStatus.DONE
        "error" -> NativeMemoryJobStatus.ERROR
        else -> throw IllegalArgumentException("unsupported status")
    }
}
