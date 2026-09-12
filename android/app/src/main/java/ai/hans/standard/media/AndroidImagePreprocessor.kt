package ai.hans.standard.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

internal data class ProcessedImage(
    val fileName: String,
    val byteCount: Long,
    val metadata: ImageMetadata,
)

internal class AndroidImagePreprocessor(
    private val limits: MediaImportLimits,
) {
    fun process(
        source: File,
        sourceByteCount: Long,
        transaction: AtomicPrivateMediaStore.Transaction,
        outputName: String = OUTPUT_FILE,
        maxEdge: Int = limits.maxDecodedImageEdge,
    ): ProcessedImage {
        var sourceWidth = 0
        var sourceHeight = 0
        var actualMimeType = ""
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                sourceWidth = info.size.width
                sourceHeight = info.size.height
                actualMimeType = MediaPolicy.normalizeMimeType(info.mimeType)
                if (!actualMimeType.startsWith("image/")) {
                    throw MediaImportException(
                        MediaImportFailureCode.INVALID_IMAGE,
                        "The selected item is not a decodable image.",
                    )
                }
                MediaPolicy.requirePixelCount(sourceWidth, sourceHeight, limits.maxImagePixels)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                decoder.setOnPartialImageListener { false }
                val scale = min(1.0, maxEdge.toDouble() / maxOf(sourceWidth, sourceHeight))
                if (scale < 1.0) {
                    decoder.setTargetSize(
                        (sourceWidth * scale).roundToInt().coerceAtLeast(1),
                        (sourceHeight * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } catch (error: MediaImportException) {
            throw error
        } catch (error: Throwable) {
            if (error is ThreadDeath || error is VirtualMachineError && error !is OutOfMemoryError) {
                throw error
            }
            throw MediaImportException(
                MediaImportFailureCode.INVALID_IMAGE,
                "The selected image could not be decoded safely.",
                error,
            )
        }

        val flattened = flattenTransparency(bitmap)
        try {
            val bytes = transaction.writeAtomically(
                name = outputName,
                maxBytes = limits.maxDerivedImageBytes,
            ) { output ->
                if (!flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    throw MediaImportException(
                        MediaImportFailureCode.INVALID_IMAGE,
                        "The selected image could not be normalized.",
                    )
                }
            }
            return ProcessedImage(
                fileName = outputName,
                byteCount = bytes,
                metadata = ImageMetadata(
                    width = sourceWidth,
                    height = sourceHeight,
                    sourceMimeType = actualMimeType,
                    sourceByteCount = sourceByteCount,
                ),
            )
        } finally {
            if (flattened !== bitmap) flattened.recycle()
            bitmap.recycle()
        }
    }

    private fun flattenTransparency(source: Bitmap): Bitmap {
        if (!source.hasAlpha()) return source
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, 0f, 0f, null)
        }
        return output
    }

    companion object {
        const val OUTPUT_FILE = "image.jpg"
        private const val JPEG_QUALITY = 88
    }
}
