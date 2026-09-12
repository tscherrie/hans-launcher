package ai.hans.standard.media

import java.io.File
import java.util.Locale

/**
 * Product limits for media selected from another Android app.
 *
 * Reported provider metadata is never trusted to enforce these limits. The
 * importer also counts the bytes it actually reads and validates the decoded
 * media before committing an import.
 */
data class MediaImportLimits(
    val maxImageInputBytes: Long = 20L * 1024L * 1024L,
    val maxVideoInputBytes: Long = 128L * 1024L * 1024L,
    val maxVideoDurationMillis: Long = 15L * 60L * 1_000L,
    val maxImagePixels: Long = 64L * 1_024L * 1_024L,
    val maxVideoPixels: Long = 40L * 1_024L * 1_024L,
    val maxDecodedImageEdge: Int = 4_096,
    val maxFrameEdge: Int = 1_280,
    val representativeFrameCount: Int = 5,
    val maxDerivedImageBytes: Long = 12L * 1024L * 1024L,
    val maxExtractedAudioBytes: Long = 48L * 1024L * 1024L,
) {
    init {
        require(maxImageInputBytes in 1..MAX_REASONABLE_INPUT_BYTES)
        require(maxVideoInputBytes in 1..MAX_REASONABLE_INPUT_BYTES)
        require(maxVideoDurationMillis in 1..MAX_REASONABLE_DURATION_MILLIS)
        require(maxImagePixels > 0)
        require(maxVideoPixels > 0)
        require(maxDecodedImageEdge in 1..MAX_REASONABLE_EDGE)
        require(maxFrameEdge in 1..MAX_REASONABLE_EDGE)
        require(representativeFrameCount in 1..MAX_REPRESENTATIVE_FRAMES)
        require(maxDerivedImageBytes in 1..MAX_REASONABLE_INPUT_BYTES)
        require(maxExtractedAudioBytes in 1..MAX_REASONABLE_INPUT_BYTES)
    }

    companion object {
        private const val MAX_REASONABLE_INPUT_BYTES = 2L * 1_024L * 1_024L * 1_024L
        private const val MAX_REASONABLE_DURATION_MILLIS = 24L * 60L * 60L * 1_000L
        private const val MAX_REASONABLE_EDGE = 16_384
        private const val MAX_REPRESENTATIVE_FRAMES = 12
    }
}

enum class MediaKind {
    IMAGE,
    VIDEO,
}

data class ImportedMediaFile(
    val absolutePath: String,
    val mimeType: String,
    val byteCount: Long,
) {
    init {
        require(File(absolutePath).isAbsolute)
        require(mimeType.isNotBlank())
        require(byteCount >= 0)
    }
}

data class ImageMetadata(
    val width: Int,
    val height: Int,
    val sourceMimeType: String,
    val sourceByteCount: Long,
) {
    init {
        require(width > 0)
        require(height > 0)
        require(sourceMimeType.startsWith("image/"))
        require(sourceByteCount >= 0)
    }
}

data class VideoMetadata(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val durationMillis: Long,
    val sourceMimeType: String,
    val sourceByteCount: Long,
    val framesPerSecond: Float?,
) {
    init {
        require(width > 0)
        require(height > 0)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(durationMillis >= 0)
        require(sourceMimeType.startsWith("video/"))
        require(sourceByteCount >= 0)
        require(framesPerSecond == null || framesPerSecond > 0f)
    }
}

data class VideoFrame(
    val timeMillis: Long,
    val image: ImportedMediaFile,
) {
    init {
        require(timeMillis >= 0)
        require(image.mimeType.startsWith("image/"))
    }
}

data class AudioTrackMetadata(
    val trackIndex: Int,
    val mimeType: String,
    val durationMillis: Long,
    val sampleRateHz: Int?,
    val channelCount: Int?,
) {
    init {
        require(trackIndex >= 0)
        require(mimeType.startsWith("audio/"))
        require(durationMillis >= 0)
        require(sampleRateHz == null || sampleRateHz > 0)
        require(channelCount == null || channelCount > 0)
    }
}

/**
 * A stable hand-off boundary between local preprocessing and speech-to-text.
 *
 * [ExtractedAudio] is preferred. [SourceTrack] deliberately keeps the private
 * source video and exact track index available when Android cannot remux the
 * codec into M4A without transcoding. A later STT adapter can either accept the
 * source container or decode this track with MediaExtractor/MediaCodec.
 */
sealed interface VideoAudioHandoff {
    data object NoAudio : VideoAudioHandoff

    data class ExtractedAudio(
        val audio: ImportedMediaFile,
        val track: AudioTrackMetadata,
    ) : VideoAudioHandoff

    data class SourceTrack(
        val sourceVideo: ImportedMediaFile,
        val track: AudioTrackMetadata,
        val reason: String,
    ) : VideoAudioHandoff {
        init {
            require(reason.isNotBlank())
        }
    }
}

sealed interface MediaImportResult {
    val importId: String
    val displayLabel: String

    data class Image(
        override val importId: String,
        override val displayLabel: String,
        val image: ImportedMediaFile,
        val metadata: ImageMetadata,
        val manifestPath: String,
    ) : MediaImportResult {
        init {
            require(File(manifestPath).isAbsolute)
        }
    }

    data class Video(
        override val importId: String,
        override val displayLabel: String,
        val sourceVideo: ImportedMediaFile,
        val metadata: VideoMetadata,
        val representativeFrames: List<VideoFrame>,
        val audioHandoff: VideoAudioHandoff,
        val manifestPath: String,
    ) : MediaImportResult {
        init {
            require(representativeFrames.isNotEmpty())
            require(File(manifestPath).isAbsolute)
        }
    }
}

data class VideoAudioTranscriptionRequest(
    val importId: String,
    val source: ImportedMediaFile,
    val track: AudioTrackMetadata,
    val sourceContainsOnlyAudio: Boolean,
)

/** The media module does not own an API key or network client. */
fun interface VideoAudioTranscriptionGateway {
    fun submit(
        request: VideoAudioTranscriptionRequest,
        callback: VideoAudioTranscriptionCallback,
    ): VideoAudioTranscriptionCancellation
}

fun interface VideoAudioTranscriptionCancellation {
    fun cancel()
}

interface VideoAudioTranscriptionCallback {
    fun onCompleted(transcript: String)
    fun onFailed(retryable: Boolean)
}

fun MediaImportResult.Video.toTranscriptionRequest(): VideoAudioTranscriptionRequest? =
    when (val handoff = audioHandoff) {
        VideoAudioHandoff.NoAudio -> null
        is VideoAudioHandoff.ExtractedAudio -> VideoAudioTranscriptionRequest(
            importId = importId,
            source = handoff.audio,
            track = handoff.track,
            sourceContainsOnlyAudio = true,
        )
        is VideoAudioHandoff.SourceTrack -> VideoAudioTranscriptionRequest(
            importId = importId,
            source = handoff.sourceVideo,
            track = handoff.track,
            sourceContainsOnlyAudio = false,
        )
    }

enum class MediaImportFailureCode {
    UNSUPPORTED_DECLARED_TYPE,
    DECLARED_SIZE_EXCEEDED,
    READ_LIMIT_EXCEEDED,
    CONTENT_UNAVAILABLE,
    INVALID_IMAGE,
    INVALID_VIDEO,
    PIXEL_LIMIT_EXCEEDED,
    DURATION_LIMIT_EXCEEDED,
    PRIVATE_STORAGE_FAILURE,
}

class MediaImportException(
    val code: MediaImportFailureCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal object MediaPolicy {
    private val labelWhitespace = Regex("\\s+")
    private val unsafeLabelCharacters = Regex("[\\p{Cc}\\p{Cf}]")

    fun kindForDeclaredMimeType(value: String?): MediaKind {
        val mimeType = normalizeMimeType(value)
        return when {
            mimeType.startsWith("image/") -> MediaKind.IMAGE
            mimeType.startsWith("video/") -> MediaKind.VIDEO
            else -> throw MediaImportException(
                MediaImportFailureCode.UNSUPPORTED_DECLARED_TYPE,
                "The selected item is not a supported image or video.",
            )
        }
    }

    fun normalizeMimeType(value: String?): String = value
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        .orEmpty()

    fun requireReportedSizeWithinLimit(reportedSize: Long?, maxBytes: Long) {
        if (reportedSize != null && reportedSize >= 0 && reportedSize > maxBytes) {
            throw MediaImportException(
                MediaImportFailureCode.DECLARED_SIZE_EXCEEDED,
                "The selected item exceeds the configured size limit.",
            )
        }
    }

    fun requirePixelCount(width: Int, height: Int, maxPixels: Long) {
        if (width <= 0 || height <= 0) {
            throw MediaImportException(
                MediaImportFailureCode.INVALID_IMAGE,
                "The selected item has invalid dimensions.",
            )
        }
        if (width.toLong() * height.toLong() > maxPixels) {
            throw MediaImportException(
                MediaImportFailureCode.PIXEL_LIMIT_EXCEEDED,
                "The selected item exceeds the configured pixel limit.",
            )
        }
    }

    fun requireVideoDuration(durationMillis: Long, maxDurationMillis: Long) {
        if (durationMillis < 0) {
            throw MediaImportException(
                MediaImportFailureCode.INVALID_VIDEO,
                "The selected video has no valid duration.",
            )
        }
        if (durationMillis > maxDurationMillis) {
            throw MediaImportException(
                MediaImportFailureCode.DURATION_LIMIT_EXCEEDED,
                "The selected video exceeds the configured duration limit.",
            )
        }
    }

    fun representativeFrameTimes(durationMillis: Long, requestedCount: Int): List<Long> {
        require(durationMillis >= 0)
        require(requestedCount > 0)
        if (durationMillis == 0L) return listOf(0L)

        val lastUsableMillis = (durationMillis - 1L).coerceAtLeast(0L)
        return (0 until requestedCount)
            .map { index ->
                val numerator = (index * 2L + 1L) * lastUsableMillis
                val denominator = requestedCount * 2L
                numerator / denominator
            }
            .distinct()
            .ifEmpty { listOf(0L) }
    }

    fun safeDisplayLabel(value: String?, fallback: String, maxLength: Int = 80): String {
        val sanitized = value
            .orEmpty()
            .replace(unsafeLabelCharacters, " ")
            .replace(labelWhitespace, " ")
            .trim()
            .take(maxLength)
        return sanitized.ifBlank { fallback }
    }

    fun normalizedRotation(value: Int): Int {
        val normalized = ((value % 360) + 360) % 360
        return when (normalized) {
            in 45..134 -> 90
            in 135..224 -> 180
            in 225..314 -> 270
            else -> 0
        }
    }
}
