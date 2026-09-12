package ai.hans.standard.media

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Root-free Android 12+ import and preprocessing entry point.
 *
 * Call [import] while the transient picker grant is alive. The method performs
 * no network operation and returns only paths below the app-private files
 * directory. Run it off the main thread because video extraction is bounded but
 * intentionally synchronous.
 */
class AndroidMediaImportPipeline(
    private val context: Context,
    private val store: AtomicPrivateMediaStore = AtomicPrivateMediaStore(context),
    private val limits: MediaImportLimits = MediaImportLimits(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val imagePreprocessor = AndroidImagePreprocessor(limits)
    private val videoPreprocessor = AndroidVideoPreprocessor(limits)

    fun import(uri: Uri, expectedKind: MediaKind? = null): MediaImportResult {
        cleanupAbandonedImports()
        val selection = AndroidContentSelection(context.contentResolver, uri)
        val metadata = selection.metadata()
        val kind = resolveKind(metadata.declaredMimeType, expectedKind)
        val byteLimit = when (kind) {
            MediaKind.IMAGE -> limits.maxImageInputBytes
            MediaKind.VIDEO -> limits.maxVideoInputBytes
        }
        MediaPolicy.requireReportedSizeWithinLimit(metadata.reportedSize, byteLimit)
        val label = MediaPolicy.safeDisplayLabel(
            metadata.displayName,
            fallback = if (kind == MediaKind.IMAGE) "Foto" else "Video",
        )

        store.begin().use { transaction ->
            val rawSource = transaction.stagingFile(RAW_SOURCE_FILE)
            val sourceByteCount = selection.copyOnceTo(rawSource, byteLimit)
            if (sourceByteCount == 0L) {
                throw MediaImportException(
                    MediaImportFailureCode.CONTENT_UNAVAILABLE,
                    "The selected media is empty.",
                )
            }
            return when (kind) {
                MediaKind.IMAGE -> importImage(
                    transaction = transaction,
                    rawSource = rawSource,
                    sourceByteCount = sourceByteCount,
                    label = label,
                )
                MediaKind.VIDEO -> importVideo(
                    transaction = transaction,
                    rawSource = rawSource,
                    declaredMimeType = metadata.declaredMimeType,
                    sourceByteCount = sourceByteCount,
                    label = label,
                )
            }
        }
    }

    fun delete(importId: String): Boolean = store.deleteImport(importId)

    /** Terminal cleanup succeeds only when the app-private import is confirmed absent. */
    fun deleteAndConfirmAbsent(importId: String): Boolean {
        store.deleteImport(importId)
        return !store.importExists(importId)
    }

    fun cleanupAbandonedImports(
        olderThanEpochMillis: Long = nowMillis() - ABANDONED_TRANSACTION_MILLIS,
    ): Int = store.cleanupIncompleteImports(olderThanEpochMillis)

    private fun importImage(
        transaction: AtomicPrivateMediaStore.Transaction,
        rawSource: File,
        sourceByteCount: Long,
        label: String,
    ): MediaImportResult.Image {
        val processed = imagePreprocessor.process(rawSource, sourceByteCount, transaction)
        if (rawSource.exists() && !rawSource.delete()) {
            throw MediaImportException(
                MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                "Could not remove the normalized image source.",
            )
        }
        writeManifest(
            transaction,
            JSONObject()
                .put("schemaVersion", MANIFEST_SCHEMA_VERSION)
                .put("importId", transaction.importId)
                .put("kind", "image")
                .put("label", label)
                .put("createdAtEpochMillis", nowMillis())
                .put("sourceMimeType", processed.metadata.sourceMimeType)
                .put("sourceByteCount", processed.metadata.sourceByteCount)
                .put("width", processed.metadata.width)
                .put("height", processed.metadata.height)
                .put("image", processed.fileName),
        )
        transaction.commit()
        val imageFile = transaction.finalFile(processed.fileName)
        return MediaImportResult.Image(
            importId = transaction.importId,
            displayLabel = label,
            image = ImportedMediaFile(
                absolutePath = imageFile.absolutePath,
                mimeType = NORMALIZED_IMAGE_MIME,
                byteCount = processed.byteCount,
            ),
            metadata = processed.metadata,
            manifestPath = transaction.finalFile(MANIFEST_FILE).absolutePath,
        )
    }

    private fun importVideo(
        transaction: AtomicPrivateMediaStore.Transaction,
        rawSource: File,
        declaredMimeType: String,
        sourceByteCount: Long,
        label: String,
    ): MediaImportResult.Video {
        val processed = videoPreprocessor.process(
            rawSource = rawSource,
            declaredMimeType = declaredMimeType,
            sourceByteCount = sourceByteCount,
            transaction = transaction,
        )
        writeManifest(transaction, videoManifest(transaction.importId, label, processed))
        transaction.commit()

        val sourceFile = transaction.finalFile(processed.sourceFileName)
        val source = ImportedMediaFile(
            absolutePath = sourceFile.absolutePath,
            mimeType = processed.sourceMimeType,
            byteCount = processed.sourceByteCount,
        )
        val frames = processed.frames.map { frame ->
            VideoFrame(
                timeMillis = frame.timeMillis,
                image = ImportedMediaFile(
                    absolutePath = transaction.finalFile(frame.fileName).absolutePath,
                    mimeType = NORMALIZED_IMAGE_MIME,
                    byteCount = frame.byteCount,
                ),
            )
        }
        val audio = when (val processedAudio = processed.audio) {
            ProcessedVideoAudio.None -> VideoAudioHandoff.NoAudio
            is ProcessedVideoAudio.Extracted -> VideoAudioHandoff.ExtractedAudio(
                audio = ImportedMediaFile(
                    absolutePath = transaction.finalFile(processedAudio.fileName).absolutePath,
                    mimeType = EXTRACTED_AUDIO_MIME,
                    byteCount = processedAudio.byteCount,
                ),
                track = processedAudio.track,
            )
            is ProcessedVideoAudio.SourceTrack -> VideoAudioHandoff.SourceTrack(
                sourceVideo = source,
                track = processedAudio.track,
                reason = processedAudio.reason,
            )
        }
        return MediaImportResult.Video(
            importId = transaction.importId,
            displayLabel = label,
            sourceVideo = source,
            metadata = processed.metadata,
            representativeFrames = frames,
            audioHandoff = audio,
            manifestPath = transaction.finalFile(MANIFEST_FILE).absolutePath,
        )
    }

    private fun resolveKind(declaredMimeType: String, expectedKind: MediaKind?): MediaKind {
        if (declaredMimeType.isBlank()) {
            return expectedKind ?: throw MediaImportException(
                MediaImportFailureCode.UNSUPPORTED_DECLARED_TYPE,
                "The content provider did not identify the selected media type.",
            )
        }
        val declaredKind = MediaPolicy.kindForDeclaredMimeType(declaredMimeType)
        if (expectedKind != null && expectedKind != declaredKind) {
            throw MediaImportException(
                MediaImportFailureCode.UNSUPPORTED_DECLARED_TYPE,
                "The selected item does not match the requested media type.",
            )
        }
        return declaredKind
    }

    private fun writeManifest(
        transaction: AtomicPrivateMediaStore.Transaction,
        manifest: JSONObject,
    ) {
        val bytes = manifest.toString().toByteArray(StandardCharsets.UTF_8)
        transaction.writeAtomically(MANIFEST_FILE, MAX_MANIFEST_BYTES) { output ->
            output.write(bytes)
        }
    }

    private fun videoManifest(
        importId: String,
        label: String,
        video: ProcessedVideo,
    ): JSONObject {
        val frames = JSONArray()
        video.frames.forEach { frame ->
            frames.put(
                JSONObject()
                    .put("timeMillis", frame.timeMillis)
                    .put("file", frame.fileName)
                    .put("byteCount", frame.byteCount),
            )
        }
        val audio = when (val value = video.audio) {
            ProcessedVideoAudio.None -> JSONObject().put("kind", "none")
            is ProcessedVideoAudio.Extracted -> audioManifest(value.track)
                .put("kind", "extracted")
                .put("file", value.fileName)
                .put("byteCount", value.byteCount)
            is ProcessedVideoAudio.SourceTrack -> audioManifest(value.track)
                .put("kind", "sourceTrack")
                .put("reason", value.reason)
        }
        return JSONObject()
            .put("schemaVersion", MANIFEST_SCHEMA_VERSION)
            .put("importId", importId)
            .put("kind", "video")
            .put("label", label)
            .put("createdAtEpochMillis", nowMillis())
            .put("sourceFile", video.sourceFileName)
            .put("sourceMimeType", video.sourceMimeType)
            .put("sourceByteCount", video.sourceByteCount)
            .put("durationMillis", video.metadata.durationMillis)
            .put("width", video.metadata.width)
            .put("height", video.metadata.height)
            .put("rotationDegrees", video.metadata.rotationDegrees)
            .put("framesPerSecond", video.metadata.framesPerSecond ?: JSONObject.NULL)
            .put("representativeFrames", frames)
            .put("audio", audio)
    }

    private fun audioManifest(track: AudioTrackMetadata): JSONObject = JSONObject()
        .put("trackIndex", track.trackIndex)
        .put("mimeType", track.mimeType)
        .put("durationMillis", track.durationMillis)
        .put("sampleRateHz", track.sampleRateHz ?: JSONObject.NULL)
        .put("channelCount", track.channelCount ?: JSONObject.NULL)

    companion object {
        private const val RAW_SOURCE_FILE = "selection.bin"
        private const val MANIFEST_FILE = "manifest.json"
        private const val MANIFEST_SCHEMA_VERSION = 1
        private const val MAX_MANIFEST_BYTES = 64L * 1_024L
        private const val ABANDONED_TRANSACTION_MILLIS = 24L * 60L * 60L * 1_000L
        private const val NORMALIZED_IMAGE_MIME = "image/jpeg"
        private const val EXTRACTED_AUDIO_MIME = "audio/mp4"

        fun forWorkspace(
            context: Context,
            workspacePath: String,
            limits: MediaImportLimits = MediaImportLimits(),
        ): AndroidMediaImportPipeline = AndroidMediaImportPipeline(
            context = context,
            store = AtomicPrivateMediaStore(
                context,
                File(File(workspacePath), AtomicPrivateMediaStore.DEFAULT_DIRECTORY),
            ),
            limits = limits,
        )
    }
}
