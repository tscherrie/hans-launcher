package ai.hans.standard.workspace

import java.io.Closeable
import java.io.InputStream

/** A stable description of one externally selected file; content is copied immediately on open. */
class WorkspaceSourceFile(
    val relativePath: String,
    val declaredByteCount: Long?,
    val lastModifiedEpochMillis: Long?,
    private val opener: () -> InputStream,
) {
    init {
        WorkspacePaths.requireSafeRelativePath(relativePath)
        require(declaredByteCount == null || declaredByteCount >= 0L)
        require(lastModifiedEpochMillis == null || lastModifiedEpochMillis >= 0L)
    }

    fun open(): InputStream = opener()
}

/** Implementations must return a bounded, duplicate-free, path-sorted snapshot. */
interface WorkspaceExternalSource : Closeable {
    fun files(): List<WorkspaceSourceFile>

    override fun close() = Unit
}

class WorkspaceExternalImporter(
    private val store: PrivateWorkspaceStore,
    private val quotas: WorkspaceQuotas = WorkspaceQuotas(),
) {
    fun import(source: WorkspaceExternalSource): WorkspaceSnapshot {
        val files = source.files()
        require(files.size <= quotas.maxFiles) { "External workspace file-count limit exceeded" }
        require(files.map { it.relativePath }.distinct().size == files.size) {
            "External workspace contains duplicate paths"
        }
        require(files == files.sortedBy { it.relativePath }) {
            "External workspace source is not deterministic"
        }
        return store.begin().use { transaction ->
            files.forEach { sourceFile ->
                sourceFile.declaredByteCount?.let { declared ->
                    require(declared <= quotas.maxSingleFileBytes) {
                        "External workspace file-size limit exceeded"
                    }
                }
                sourceFile.open().use { input ->
                    transaction.copy(
                        relativePath = sourceFile.relativePath,
                        source = input,
                        maxBytes = quotas.maxSingleFileBytes,
                    )
                }
            }
            transaction.commit()
        }
    }
}
