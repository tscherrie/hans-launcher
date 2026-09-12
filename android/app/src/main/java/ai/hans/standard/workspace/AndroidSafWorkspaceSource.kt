package ai.hans.standard.workspace

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.ArrayDeque

/**
 * Bounded, one-shot projection of a user-selected Storage Access Framework tree.
 * No filesystem path is inferred from a content URI and no provider is polled.
 */
class AndroidSafWorkspaceSource(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val maxFiles: Int = WorkspaceQuotas.DEFAULT_MAX_FILES,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) : WorkspaceExternalSource {
    init {
        require(maxFiles in 1..WorkspaceQuotas.ABSOLUTE_MAX_FILES)
        require(maxDepth in 1..ABSOLUTE_MAX_DEPTH)
        require(DocumentsContract.isTreeUri(treeUri)) { "Workspace selection is not a document tree" }
    }

    override fun files(): List<WorkspaceSourceFile> {
        require(hasPersistedReadAccess()) { "Workspace document-tree permission is unavailable" }
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = ArrayDeque<DirectoryToVisit>()
        queue += DirectoryToVisit(rootDocumentId, prefix = "", depth = 0)
        val result = mutableListOf<WorkspaceSourceFile>()
        val seenDocumentIds = mutableSetOf<String>()
        val seenPaths = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val directory = queue.removeFirst()
            require(directory.depth <= maxDepth) { "External workspace directory-depth limit exceeded" }
            val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directory.documentId)
            val children = queryChildren(childUri).sortedWith(compareBy({ it.displayName }, { it.documentId }))
            children.forEach { child ->
                require(seenDocumentIds.add(child.documentId)) { "External document tree contains a cycle" }
                val relativePath = if (directory.prefix.isEmpty()) {
                    child.displayName
                } else {
                    "${directory.prefix}/${child.displayName}"
                }
                WorkspacePaths.requireSafeRelativePath(relativePath)
                require(seenPaths.add(relativePath)) { "External workspace contains duplicate paths" }
                if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    require(directory.depth < maxDepth) { "External workspace directory-depth limit exceeded" }
                    queue += DirectoryToVisit(child.documentId, relativePath, directory.depth + 1)
                } else {
                    require(result.size < maxFiles) { "External workspace file-count limit exceeded" }
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId)
                    result += WorkspaceSourceFile(
                        relativePath = relativePath,
                        declaredByteCount = child.byteCount,
                        lastModifiedEpochMillis = child.lastModifiedEpochMillis,
                    ) {
                        resolver.openInputStream(documentUri)
                            ?: throw FileNotFoundException("Selected workspace file is unavailable")
                    }
                }
            }
        }
        return result.sortedBy { it.relativePath }
    }

    fun hasPersistedReadAccess(): Boolean = resolver.persistedUriPermissions.any { permission ->
        permission.uri == treeUri && permission.isReadPermission
    }

    fun persistReadAndWriteAccess(grantFlags: Int) {
        val supportedFlags = grantFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(supportedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            "The document-tree selection did not grant read access"
        }
        resolver.takePersistableUriPermission(treeUri, supportedFlags)
    }

    private fun queryChildren(childrenUri: Uri): List<DocumentRow> {
        val rows = mutableListOf<DocumentRow>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw FileNotFoundException("Selected document tree is unavailable")
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)
                require(!name.isNullOrBlank()) { "External document has no display name" }
                rows += DocumentRow(
                    documentId = it.getString(idIndex),
                    displayName = name,
                    mimeType = it.getString(mimeIndex).orEmpty(),
                    byteCount = sizeIndex.takeIf { index -> index >= 0 && !it.isNull(index) }
                        ?.let(it::getLong)
                        ?.takeIf { size -> size >= 0L },
                    lastModifiedEpochMillis = modifiedIndex.takeIf { index -> index >= 0 && !it.isNull(index) }
                        ?.let(it::getLong)
                        ?.takeIf { timestamp -> timestamp >= 0L },
                )
            }
        }
        return rows
    }

    private data class DirectoryToVisit(
        val documentId: String,
        val prefix: String,
        val depth: Int,
    )

    private data class DocumentRow(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val byteCount: Long?,
        val lastModifiedEpochMillis: Long?,
    )

    companion object {
        const val DEFAULT_MAX_DEPTH = 32
        const val ABSOLUTE_MAX_DEPTH = 128
    }
}
