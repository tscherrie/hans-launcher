package ai.hans.standard.media

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

internal data class ProcessedVideo(
    val sourceFileName: String,
    val sourceByteCount: Long,
    val sourceMimeType: String,
    val metadata: VideoMetadata,
    val frames: List<ProcessedVideoFrame>,
    val audio: ProcessedVideoAudio,
)

internal data class ProcessedVideoFrame(
    val fileName: String,
    val byteCount: Long,
    val timeMillis: Long,
)

internal sealed interface ProcessedVideoAudio {
    data object None : ProcessedVideoAudio

    data class Extracted(
        val fileName: String,
        val byteCount: Long,
        val track: AudioTrackMetadata,
    ) : ProcessedVideoAudio

    data class SourceTrack(
        val track: AudioTrackMetadata,
        val reason: String,
    ) : ProcessedVideoAudio
}

internal class AndroidVideoPreprocessor(
    private val limits: MediaImportLimits,
) {
    fun process(
        rawSource: File,
        declaredMimeType: String,
        sourceByteCount: Long,
        transaction: AtomicPrivateMediaStore.Transaction,
    ): ProcessedVideo {
        val inspected = inspect(rawSource, declaredMimeType, sourceByteCount)
        val sourceName = "source.${extensionFor(inspected.sourceMimeType)}"
        val sourceFile = transaction.stagingFile(sourceName)
        if (!rawSource.renameTo(sourceFile)) {
            throw MediaImportException(
                MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                "Could not finalize the private video source.",
            )
        }

        val frames = extractFrames(
            source = sourceFile,
            metadata = inspected.metadata,
            transaction = transaction,
        )
        if (frames.isEmpty()) {
            throw MediaImportException(
                MediaImportFailureCode.INVALID_VIDEO,
                "The selected video did not contain a decodable frame.",
            )
        }

        val audio = inspected.audioTrack?.let { track ->
            extractAudioIfSupported(sourceFile, track, transaction)
        } ?: ProcessedVideoAudio.None

        return ProcessedVideo(
            sourceFileName = sourceName,
            sourceByteCount = sourceByteCount,
            sourceMimeType = inspected.sourceMimeType,
            metadata = inspected.metadata,
            frames = frames,
            audio = audio,
        )
    }

    private fun inspect(
        source: File,
        declaredMimeType: String,
        sourceByteCount: Long,
    ): InspectedVideo {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(source.absolutePath)
            retriever.setDataSource(source.absolutePath)

            var videoFormat: MediaFormat? = null
            var audioTrack: InspectedAudioTrack? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mimeType = format.stringOrNull(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mimeType.startsWith("video/") && videoFormat == null -> videoFormat = format
                    mimeType.startsWith("audio/") && audioTrack == null -> {
                        audioTrack = InspectedAudioTrack(index, format, mimeType)
                    }
                }
            }
            val requiredVideoFormat = videoFormat ?: throw MediaImportException(
                MediaImportFailureCode.INVALID_VIDEO,
                "The selected item contains no video track.",
            )

            val width = requiredVideoFormat.intOrNull(MediaFormat.KEY_WIDTH)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: 0
            val height = requiredVideoFormat.intOrNull(MediaFormat.KEY_HEIGHT)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: 0
            try {
                MediaPolicy.requirePixelCount(width, height, limits.maxVideoPixels)
            } catch (error: MediaImportException) {
                if (error.code == MediaImportFailureCode.INVALID_IMAGE) {
                    throw MediaImportException(
                        MediaImportFailureCode.INVALID_VIDEO,
                        "The selected video has invalid dimensions.",
                        error,
                    )
                }
                throw error
            }

            val durationMillis = sequenceOf(
                requiredVideoFormat.longOrNull(MediaFormat.KEY_DURATION)?.div(MICROS_PER_MILLI),
                audioTrack?.format?.longOrNull(MediaFormat.KEY_DURATION)?.div(MICROS_PER_MILLI),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            ).filterNotNull().maxOrNull() ?: -1L
            if (durationMillis <= 0L) {
                throw MediaImportException(
                    MediaImportFailureCode.INVALID_VIDEO,
                    "The selected video has no valid duration.",
                )
            }
            MediaPolicy.requireVideoDuration(durationMillis, limits.maxVideoDurationMillis)

            val rotation = requiredVideoFormat.intOrNull(MediaFormat.KEY_ROTATION)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()
                ?: 0
            val sourceMimeType = MediaPolicy.normalizeMimeType(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            ).takeIf { it.startsWith("video/") }
                ?: declaredMimeType.takeIf { it.startsWith("video/") }
                ?: "video/unknown"
            val frameRate = requiredVideoFormat.numberOrNull(MediaFormat.KEY_FRAME_RATE)?.toFloat()
                ?.takeIf { it.isFinite() && it > 0f }

            val metadata = VideoMetadata(
                width = width,
                height = height,
                rotationDegrees = MediaPolicy.normalizedRotation(rotation),
                durationMillis = durationMillis,
                sourceMimeType = sourceMimeType,
                sourceByteCount = sourceByteCount,
                framesPerSecond = frameRate,
            )
            val audioMetadata = audioTrack?.let { track ->
                AudioTrackMetadata(
                    trackIndex = track.index,
                    mimeType = track.mimeType,
                    durationMillis = track.format.longOrNull(MediaFormat.KEY_DURATION)
                        ?.div(MICROS_PER_MILLI)
                        ?.takeIf { it >= 0L }
                        ?: durationMillis,
                    sampleRateHz = track.format.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                    channelCount = track.format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                )
            }
            return InspectedVideo(sourceMimeType, metadata, audioTrack?.copy(metadata = audioMetadata))
        } catch (error: MediaImportException) {
            throw error
        } catch (error: Exception) {
            throw MediaImportException(
                MediaImportFailureCode.INVALID_VIDEO,
                "The selected video could not be inspected safely.",
                error,
            )
        } finally {
            extractor.release()
            retriever.release()
        }
    }

    private fun extractFrames(
        source: File,
        metadata: VideoMetadata,
        transaction: AtomicPrivateMediaStore.Transaction,
    ): List<ProcessedVideoFrame> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            val displayWidth = if (metadata.rotationDegrees in setOf(90, 270)) {
                metadata.height
            } else {
                metadata.width
            }
            val displayHeight = if (metadata.rotationDegrees in setOf(90, 270)) {
                metadata.width
            } else {
                metadata.height
            }
            val scale = min(
                1.0,
                limits.maxFrameEdge.toDouble() / maxOf(displayWidth, displayHeight),
            )
            val targetWidth = (displayWidth * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (displayHeight * scale).roundToInt().coerceAtLeast(1)
            MediaPolicy.representativeFrameTimes(
                metadata.durationMillis,
                limits.representativeFrameCount,
            ).mapIndexedNotNull { index, timeMillis ->
                val frame = retriever.getScaledFrameAtTime(
                    timeMillis * MICROS_PER_MILLI,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidth,
                    targetHeight,
                ) ?: retriever.getScaledFrameAtTime(
                    timeMillis * MICROS_PER_MILLI,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    targetWidth,
                    targetHeight,
                ) ?: return@mapIndexedNotNull null
                val normalized = scaleFrame(frame, limits.maxFrameEdge)
                try {
                    val fileName = "frame-${(index + 1).toString().padStart(2, '0')}.jpg"
                    val byteCount = transaction.writeAtomically(
                        name = fileName,
                        maxBytes = limits.maxDerivedImageBytes,
                    ) { output ->
                        if (!normalized.compress(Bitmap.CompressFormat.JPEG, FRAME_JPEG_QUALITY, output)) {
                            throw MediaImportException(
                                MediaImportFailureCode.INVALID_VIDEO,
                                "A representative video frame could not be normalized.",
                            )
                        }
                    }
                    ProcessedVideoFrame(fileName, byteCount, timeMillis)
                } finally {
                    if (normalized !== frame) normalized.recycle()
                    frame.recycle()
                }
            }
        } catch (error: MediaImportException) {
            throw error
        } catch (error: Exception) {
            throw MediaImportException(
                MediaImportFailureCode.INVALID_VIDEO,
                "Representative video frames could not be extracted.",
                error,
            )
        } finally {
            retriever.release()
        }
    }

    @SuppressLint("WrongConstant")
    private fun extractAudioIfSupported(
        source: File,
        inspected: InspectedAudioTrack,
        transaction: AtomicPrivateMediaStore.Transaction,
    ): ProcessedVideoAudio {
        val metadata = checkNotNull(inspected.metadata)
        if (inspected.mimeType !in MP4_REMUXABLE_AUDIO_MIME_TYPES) {
            return ProcessedVideoAudio.SourceTrack(metadata, "codec_requires_decoder")
        }
        val temporary = transaction.temporaryFileFor(AUDIO_FILE)
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        return try {
            extractor = MediaExtractor().apply {
                setDataSource(source.absolutePath)
                selectTrack(inspected.index)
            }
            muxer = MediaMuxer(
                temporary.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            val outputTrack = muxer.addTrack(inspected.format)
            muxer.start()
            muxerStarted = true

            val bufferSize = inspected.format.intOrNull(MediaFormat.KEY_MAX_INPUT_SIZE)
                ?.coerceIn(MIN_AUDIO_SAMPLE_BUFFER, MAX_AUDIO_SAMPLE_BUFFER)
                ?: DEFAULT_AUDIO_SAMPLE_BUFFER
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()
            var encodedBytes = 0L
            var sampleCount = 0
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                encodedBytes += sampleSize
                if (encodedBytes > limits.maxExtractedAudioBytes) {
                    throw MediaImportException(
                        MediaImportFailureCode.READ_LIMIT_EXCEEDED,
                        "The extracted audio exceeds its configured byte limit.",
                    )
                }
                info.set(
                    0,
                    sampleSize,
                    extractor.sampleTime.coerceAtLeast(0L),
                    muxerSampleFlags(extractor.sampleFlags),
                )
                buffer.position(0)
                buffer.limit(sampleSize)
                muxer.writeSampleData(outputTrack, buffer, info)
                sampleCount += 1
                if (!extractor.advance()) break
            }
            if (sampleCount == 0) {
                throw IllegalStateException("Audio track contains no samples")
            }
            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null
            FileOutputStream(temporary, true).use { it.fd.sync() }
            if (temporary.length() > limits.maxExtractedAudioBytes) {
                throw MediaImportException(
                    MediaImportFailureCode.READ_LIMIT_EXCEEDED,
                    "The extracted audio exceeds its configured byte limit.",
                )
            }
            val finalized = transaction.finalizeTemporary(temporary, AUDIO_FILE)
            ProcessedVideoAudio.Extracted(AUDIO_FILE, finalized.length(), metadata)
        } catch (error: MediaImportException) {
            throw error
        } catch (_: Exception) {
            ProcessedVideoAudio.SourceTrack(metadata, "lossless_remux_unavailable")
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun scaleFrame(source: Bitmap, maxEdge: Int): Bitmap {
        val scale = min(1.0, maxEdge.toDouble() / maxOf(source.width, source.height))
        if (scale >= 1.0) return source
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun muxerSampleFlags(extractorFlags: Int): Int {
        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
            throw IllegalArgumentException("Encrypted media cannot be remuxed")
        }
        var codecFlags = 0
        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return codecFlags
    }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "video/mp4", "video/mpeg" -> "mp4"
        "video/webm" -> "webm"
        "video/3gpp", "video/3gpp2" -> "3gp"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        else -> "video"
    }

    private data class InspectedVideo(
        val sourceMimeType: String,
        val metadata: VideoMetadata,
        val audioTrack: InspectedAudioTrack?,
    )

    private data class InspectedAudioTrack(
        val index: Int,
        val format: MediaFormat,
        val mimeType: String,
        val metadata: AudioTrackMetadata? = null,
    )

    companion object {
        const val AUDIO_FILE = "audio.m4a"
        private const val MICROS_PER_MILLI = 1_000L
        private const val FRAME_JPEG_QUALITY = 82
        private const val MIN_AUDIO_SAMPLE_BUFFER = 64 * 1_024
        private const val DEFAULT_AUDIO_SAMPLE_BUFFER = 512 * 1_024
        private const val MAX_AUDIO_SAMPLE_BUFFER = 4 * 1_024 * 1_024
        private val MP4_REMUXABLE_AUDIO_MIME_TYPES = setOf(
            "audio/mp4a-latm",
            "audio/3gpp",
            "audio/amr-wb",
        )
    }
}

private fun MediaFormat.stringOrNull(key: String): String? =
    if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun MediaFormat.longOrNull(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

private fun MediaFormat.numberOrNull(key: String): Number? =
    if (containsKey(key)) runCatching { getNumber(key) }.getOrNull() else null
