package ai.hans.standard.media

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Full-resolution camera contract. The camera receives only a transient grant
 * to an app-private [FileProvider] URI; no CAMERA or storage permission is
 * required by Hans.
 */
class FullResolutionCameraContract : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, input)
            clipData = ClipData.newRawUri(CLIP_LABEL, input)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == Activity.RESULT_OK

    private companion object {
        const val CLIP_LABEL = "Hans camera output"
    }
}

/**
 * Full-resolution video counterpart to [FullResolutionCameraContract]. The
 * returned intent payload is deliberately ignored: Hans accepts only bytes
 * written to the fresh app-private output URI it supplied.
 */
class FullResolutionVideoContract : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, input)
            clipData = ClipData.newRawUri(CLIP_LABEL, input)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == Activity.RESULT_OK

    private companion object {
        const val CLIP_LABEL = "Hans video output"
    }
}

enum class CameraCaptureKind {
    PHOTO,
    VIDEO,
}

data class CameraCaptureChoice(
    val kind: CameraCaptureKind,
    val label: String,
)

/** Pure, stable policy shared by the launcher dialog and regression tests. */
object CameraCaptureSelectionPolicy {
    val choices: List<CameraCaptureChoice> = listOf(
        CameraCaptureChoice(CameraCaptureKind.PHOTO, "Foto aufnehmen"),
        CameraCaptureChoice(CameraCaptureKind.VIDEO, "Video aufnehmen"),
    )
}

sealed interface CameraCapturePreparation {
    class Ready internal constructor(
        val outputUri: Uri,
        internal val fileName: String,
        internal val kind: CameraCaptureKind,
    ) : CameraCapturePreparation

    data object CameraUnavailable : CameraCapturePreparation
    data object StorageUnavailable : CameraCapturePreparation
}

class CapturedCameraPhoto internal constructor(
    val contentUri: Uri,
    internal val fileName: String,
)

class CapturedCameraVideo internal constructor(
    val contentUri: Uri,
    internal val fileName: String,
)

/**
 * Owns the short-lived source file used by the external camera application.
 * The normalized attachment remains owned by [AndroidMediaImportPipeline].
 */
class AndroidCameraCaptureCoordinator internal constructor(
    private val context: Context,
    private val captureDirectory: File = File(context.cacheDir, CAPTURE_DIRECTORY),
    private val pendingStore: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    ),
    private val cameraAvailable: () -> Boolean = { hasCameraHandler(context) },
    private val videoCameraAvailable: () -> Boolean = { hasVideoCameraHandler(context) },
    private val uriForFile: (File) -> Uri = { file ->
        FileProvider.getUriForFile(context, providerAuthority(context), file)
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Creates a fresh private output only after a camera handler is proven. */
    @Synchronized
    fun prepareCapture(): CameraCapturePreparation = prepareCapture(CameraCaptureKind.PHOTO)

    @Synchronized
    fun prepareVideoCapture(): CameraCapturePreparation = prepareCapture(CameraCaptureKind.VIDEO)

    private fun prepareCapture(kind: CameraCaptureKind): CameraCapturePreparation {
        discardPendingCapture()
        val handlerAvailable = when (kind) {
            CameraCaptureKind.PHOTO -> cameraAvailable()
            CameraCaptureKind.VIDEO -> videoCameraAvailable()
        }
        if (!handlerAvailable) return CameraCapturePreparation.CameraUnavailable

        return runCatching {
            ensureCaptureDirectory()
            val suffix = when (kind) {
                CameraCaptureKind.PHOTO -> PHOTO_CAPTURE_SUFFIX
                CameraCaptureKind.VIDEO -> VIDEO_CAPTURE_SUFFIX
            }
            val fileName = "$CAPTURE_PREFIX${UUID.randomUUID()}$suffix"
            val output = File(captureDirectory, fileName)
            if (!output.createNewFile()) error("Camera output already exists")
            if (!pendingStore.edit().putString(PENDING_FILE_KEY, fileName).commit()) {
                deletePrivateCapture(output)
                error("Camera state could not be persisted")
            }
            val uri = uriForFile(output)
            CameraCapturePreparation.Ready(uri, fileName, kind)
        }.getOrElse {
            discardPendingCapture()
            CameraCapturePreparation.StorageUnavailable
        }
    }

    /**
     * Validates the camera result without trusting RESULT_OK alone. The source
     * remains present until [completeImport] is called after the private import.
     */
    @Synchronized
    fun acceptResult(captured: Boolean): CapturedCameraPhoto? {
        val accepted = acceptResult(captured, CameraCaptureKind.PHOTO) ?: return null
        val (uri, file) = accepted
        return CapturedCameraPhoto(uri, file.name)
    }

    @Synchronized
    fun acceptVideoResult(captured: Boolean): CapturedCameraVideo? {
        val accepted = acceptResult(captured, CameraCaptureKind.VIDEO) ?: return null
        val (uri, file) = accepted
        return CapturedCameraVideo(uri, file.name)
    }

    /** Removes the exact capture that failed to launch, without touching a newer one. */
    @Synchronized
    fun abandon(preparation: CameraCapturePreparation.Ready) {
        if (pendingStore.getString(PENDING_FILE_KEY, null) == preparation.fileName) {
            discardPendingCapture()
        } else {
            validatedCaptureFile(preparation.fileName)?.let(::deletePrivateCapture)
        }
    }

    /** Called only after the import pipeline has finished reading the source. */
    @Synchronized
    fun completeImport(photo: CapturedCameraPhoto) {
        completeImport(photo.fileName)
    }

    @Synchronized
    fun completeImport(video: CapturedCameraVideo) {
        completeImport(video.fileName)
    }

    private fun completeImport(fileName: String) {
        validatedCaptureFile(fileName)?.let(::deletePrivateCapture)
        if (pendingStore.getString(PENDING_FILE_KEY, null) == fileName) {
            pendingStore.edit().remove(PENDING_FILE_KEY).commit()
        }
    }

    /** Best-effort crash recovery; never deletes a recent in-flight capture. */
    @Synchronized
    fun cleanupAbandonedCaptures(
        olderThanEpochMillis: Long = nowMillis() - ABANDONED_CAPTURE_MILLIS,
    ): Int {
        if (!captureDirectory.isDirectory) return 0
        val pendingName = pendingStore.getString(PENDING_FILE_KEY, null)
        var removed = 0
        captureDirectory.listFiles().orEmpty().forEach { file ->
            val captureFile = validatedCaptureFile(file.name) ?: return@forEach
            if (
                captureFile.isFile &&
                captureFile.lastModified() < olderThanEpochMillis &&
                deletePrivateCapture(captureFile)
            ) {
                removed += 1
                if (captureFile.name == pendingName) {
                    pendingStore.edit().remove(PENDING_FILE_KEY).commit()
                }
            }
        }
        return removed
    }

    private fun discardPendingCapture() {
        pendingStore.getString(PENDING_FILE_KEY, null)
            ?.let(::validatedCaptureFile)
            ?.let(::deletePrivateCapture)
        pendingStore.edit().remove(PENDING_FILE_KEY).commit()
    }

    private fun pendingCaptureFile(): File? = pendingStore
        .getString(PENDING_FILE_KEY, null)
        ?.let(::validatedCaptureFile)

    private fun validatedCaptureFile(fileName: String): File? {
        if (
            !fileName.startsWith(CAPTURE_PREFIX) ||
            !hasSupportedCaptureSuffix(fileName) ||
            fileName.any { it == '/' || it == '\\' }
        ) {
            return null
        }
        val directory = runCatching { captureDirectory.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(captureDirectory, fileName).canonicalFile }.getOrNull()
            ?: return null
        return candidate.takeIf { it.parentFile == directory }
    }

    private fun acceptResult(
        captured: Boolean,
        expectedKind: CameraCaptureKind,
    ): Pair<Uri, File>? {
        if (!captured) {
            discardPendingCapture()
            return null
        }
        val file = pendingCaptureFile() ?: run {
            discardPendingCapture()
            return null
        }
        if (!file.matches(expectedKind) || !file.isFile || file.length() <= 0L) {
            discardPendingCapture()
            return null
        }
        val uri = runCatching { uriForFile(file) }.getOrNull() ?: run {
            discardPendingCapture()
            return null
        }
        return uri to file
    }

    private fun File.matches(kind: CameraCaptureKind): Boolean = when (kind) {
        CameraCaptureKind.PHOTO -> name.endsWith(PHOTO_CAPTURE_SUFFIX)
        CameraCaptureKind.VIDEO -> name.endsWith(VIDEO_CAPTURE_SUFFIX)
    }

    private fun hasSupportedCaptureSuffix(fileName: String): Boolean =
        fileName.endsWith(PHOTO_CAPTURE_SUFFIX) || fileName.endsWith(VIDEO_CAPTURE_SUFFIX)

    private fun ensureCaptureDirectory() {
        if (!captureDirectory.isDirectory && !captureDirectory.mkdirs()) {
            error("Camera capture directory could not be created")
        }
        // Android may expose the same app-private directory through
        // /data/user/0 and its system-managed /data/data alias. Individual
        // capture paths are still canonicalized and required to stay directly
        // below this private directory by validatedCaptureFile().
    }

    private fun deletePrivateCapture(file: File): Boolean {
        if (!file.exists()) return true
        if (file.delete()) return true
        // If deletion is temporarily denied, remove the sensitive payload and
        // mark the empty shell for the next abandoned-capture cleanup pass.
        runCatching {
            FileOutputStream(file, false).use { output -> output.fd.sync() }
            file.setLastModified(0L)
        }
        return !file.exists() || file.delete()
    }

    companion object {
        internal const val CAPTURE_DIRECTORY = "hans-camera-captures"
        internal const val CAPTURE_PREFIX = "hans-camera-"
        internal const val PHOTO_CAPTURE_SUFFIX = ".jpg"
        internal const val VIDEO_CAPTURE_SUFFIX = ".mp4"
        internal const val PREFERENCES_NAME = "hans_camera_capture_v1"
        internal const val PENDING_FILE_KEY = "pending_file"
        private const val ABANDONED_CAPTURE_MILLIS = 24L * 60L * 60L * 1_000L

        fun providerAuthority(context: Context): String = "${context.packageName}.fileprovider"

        @Suppress("DEPRECATION")
        private fun hasCameraHandler(context: Context): Boolean =
            context.packageManager.queryIntentActivities(
                Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            ).isNotEmpty()

        @Suppress("DEPRECATION")
        private fun hasVideoCameraHandler(context: Context): Boolean =
            context.packageManager.queryIntentActivities(
                Intent(MediaStore.ACTION_VIDEO_CAPTURE),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            ).isNotEmpty()
    }
}
