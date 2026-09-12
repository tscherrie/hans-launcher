package ai.hans.standard.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream

/** One-shot Storage Access Framework I/O; no broad storage permission or retained URI grant. */
internal class AndroidHansBackupDocumentIo(
    private val contentResolver: ContentResolver,
) {
    fun read(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri)
            ?: throw HansBackupException("backup_document_open_failed")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > HansBackupLimits.MAX_DOCUMENT_BYTES) {
                    throw HansBackupException("backup_document_oversize")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    fun write(uri: Uri, bytes: ByteArray) {
        if (bytes.size > HansBackupLimits.MAX_DOCUMENT_BYTES) {
            throw HansBackupException("backup_document_oversize")
        }
        val output = contentResolver.openOutputStream(uri, "wt")
            ?: throw HansBackupException("backup_document_open_failed")
        output.use { stream ->
            stream.write(bytes)
            stream.flush()
        }
    }
}
