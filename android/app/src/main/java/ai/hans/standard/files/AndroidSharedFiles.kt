package ai.hans.standard.files

import ai.hans.standard.R

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

internal object AndroidSharedFiles {
    fun store(context: Context) = SharedFileStore(
        roots = { publicVolumes(context) },
        accessGranted = Environment::isExternalStorageManager,
        aliases = { mapOf("/sdcard" to Environment.getExternalStorageDirectory(),
            "/storage/self/primary" to Environment.getExternalStorageDirectory()) },
        changed = { file -> MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null) },
        recoveryRoots = { recoveryRoots(context) },
        // Public NDK unlink: cannot remove a raced-in directory or follow the final symlink.
        unlink = AndroidFileUnlink::unlink,
    )

    /** Same-volume app-specific directories are isolated by Android, unlike random public trash names. */
    internal fun recoveryRoots(context: Context): List<SharedFileRecoveryRoot> {
        val volumes = publicVolumes(context)
        return context.getExternalFilesDirs(null).filterNotNull().mapNotNull { privateFiles ->
            val path = privateFiles.canonicalFile.toPath()
            val volume = volumes.filter { path.startsWith(it.canonicalFile.toPath()) }
                .maxByOrNull { it.canonicalFile.toPath().nameCount } ?: return@mapNotNull null
            SharedFileRecoveryRoot(volume.canonicalFile, File(privateFiles, "hans-file-recovery"))
        }.distinctBy { it.privateDirectory.canonicalPath }
    }

    private fun publicVolumes(context: Context): List<File> =
        (listOf(Environment.getExternalStorageDirectory()) + context.getSystemService(StorageManager::class.java)
            ?.storageVolumes.orEmpty().mapNotNull { it.directory }).distinctBy { it.canonicalPath }

    fun downloads(): String = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path

    fun mime(path: String): String = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(File(path).extension.lowercase()) ?: "application/octet-stream"
}

/** Only individually issued, bounded file tokens can be opened/shared. No exported root provider. */
internal class AndroidFileLinks(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun issue(info: SharedFileInfo): Pair<String, String> = synchronized(LOCK) {
        val token = UUID.randomUUID().toString().replace("-", "")
        val values = preferences.all
        val editor = preferences.edit()
        // A bounded, device-local receipt cache; old links fail closed instead of exposing paths.
        values.keys.filter { it.startsWith("p_") }
            .sortedBy { preferences.getLong("t_" + it.removePrefix("p_"), 0L) }
            .take((values.keys.count { it.startsWith("p_") } - MAX_LINKS + 1).coerceAtLeast(0))
            .forEach { key -> editor.remove(key).remove("t_" + key.removePrefix("p_")) }
        check(editor.putString("p_$token", info.path).putLong("t_$token", System.currentTimeMillis()).commit())
        "hansfile://open/$token" to "hansfile://share/$token"
    }

    fun resolve(token: String): File {
        if (!TOKEN.matches(token)) throw FileNotFoundException("invalid_file_receipt")
        val path = preferences.getString("p_$token", null) ?: throw FileNotFoundException("file_receipt_expired")
        return AndroidSharedFiles.store(context).resolve(path).also {
            if (!it.isFile) throw FileNotFoundException("file_not_found")
        }
    }

    fun openLink(link: String) {
        val uri = Uri.parse(link)
        if (!isFileLink(link)) throw IllegalArgumentException("invalid_file_link")
        val token = uri.lastPathSegment!!
        val file = resolve(token)
        val content = Uri.Builder().scheme("content").authority(context.packageName + ".files").appendPath(token).build()
        val intent = if (uri.host == "share") {
            Intent(Intent.ACTION_SEND).setType(AndroidSharedFiles.mime(file.path)).putExtra(Intent.EXTRA_STREAM, content)
        } else Intent(Intent.ACTION_VIEW).setDataAndType(content, AndroidSharedFiles.mime(file.path))
        intent.clipData = ClipData.newRawUri(file.name, content)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, if (uri.host == "share") context.getString(R.string.integration_share_file) else context.getString(R.string.integration_open_file))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val PREFERENCES = "hans_file_links_v1"
        private const val MAX_LINKS = 200
        private val LOCK = Any()
        private val TOKEN = Regex("[0-9a-f]{32}")
        fun isFileLink(raw: String): Boolean = Regex("hansfile://(?:open|share)/[0-9a-f]{32}").matches(raw)
    }
}

/** Android grants a receiving app only the exact read URI selected by the user. */
class HansSharedFileProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val app = checkNotNull(context)
        if (uri.authority != app.packageName + ".files" || uri.pathSegments.size != 1 || uri.query != null || uri.fragment != null) {
            throw FileNotFoundException("invalid_file_receipt")
        }
        return try { AndroidFileLinks(app).resolve(uri.pathSegments.single()) }
        catch (_: Exception) { throw FileNotFoundException("file_unavailable_or_permission_revoked") }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read_only_file_grant")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri): String = AndroidSharedFiles.mime(file(uri).path)
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = projection?.filter { it in setOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE) }
            ?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.length() }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read_only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read_only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read_only")
}
