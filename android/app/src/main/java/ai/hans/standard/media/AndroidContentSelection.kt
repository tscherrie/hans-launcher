package ai.hans.standard.media

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

internal data class ContentSelectionMetadata(
    val declaredMimeType: String,
    val displayName: String?,
    val reportedSize: Long?,
)

/** Consumes a transient content grant immediately; no persisted URI grant is assumed. */
internal class AndroidContentSelection(
    private val resolver: ContentResolver,
    private val uri: Uri,
) {
    fun metadata(): ContentSelectionMetadata {
        val declaredMimeType = MediaPolicy.normalizeMimeType(resolver.getType(uri))
        var displayName: String? = null
        var reportedSize: Long? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    displayName = cursor.optionalString(OpenableColumns.DISPLAY_NAME)
                    reportedSize = cursor.optionalLong(OpenableColumns.SIZE)
                }
            }
        }
        if (reportedSize == null) {
            runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.length.takeIf { it >= 0 }?.let { reportedSize = it }
                }
            }
        }
        return ContentSelectionMetadata(declaredMimeType, displayName, reportedSize)
    }

    fun copyOnceTo(target: File, maxBytes: Long): Long {
        require(maxBytes > 0)
        if (target.exists()) {
            throw MediaImportException(
                MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                "Private media staging target already exists.",
            )
        }
        try {
            val input = resolver.openInputStream(uri) ?: throw MediaImportException(
                MediaImportFailureCode.CONTENT_UNAVAILABLE,
                "The selected media can no longer be opened.",
            )
            input.use { source ->
                FileOutputStream(target).use { fileOutput ->
                    val bounded = BoundedOutputStream(fileOutput, maxBytes)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        bounded.write(buffer, 0, count)
                    }
                    bounded.flush()
                    fileOutput.fd.sync()
                    return bounded.byteCount
                }
            }
        } catch (error: MediaImportException) {
            throw error
        } catch (error: Exception) {
            throw MediaImportException(
                MediaImportFailureCode.CONTENT_UNAVAILABLE,
                "The selected media could not be copied.",
                error,
            )
        } finally {
            if (target.exists() && target.length() == 0L) target.delete()
        }
    }
}

private fun Cursor.optionalString(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun Cursor.optionalLong(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}
