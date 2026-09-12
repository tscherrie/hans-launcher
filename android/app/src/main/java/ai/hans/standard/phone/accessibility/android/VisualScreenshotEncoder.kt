package ai.hans.standard.phone.accessibility.android

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** One-shot, in-memory projection for App Server image content; no pixels are persisted. */
internal object VisualScreenshotEncoder {
    private const val MAX_EDGE_PIXELS = 1_280
    private const val JPEG_QUALITY = 68
    private const val MAX_JPEG_BYTES = 1_200_000

    fun encode(source: Bitmap): EncodedVisualScreenshot? {
        if (source.width <= 0 || source.height <= 0) return null
        val longest = max(source.width, source.height)
        val scale = if (longest > MAX_EDGE_PIXELS) {
            MAX_EDGE_PIXELS.toDouble() / longest.toDouble()
        } else {
            1.0
        }
        val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = if (targetWidth == source.width && targetHeight == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        }
        return try {
            val bytes = ByteArrayOutputStream().use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) return null
                output.toByteArray()
            }
            if (bytes.isEmpty() || bytes.size > MAX_JPEG_BYTES) return null
            EncodedVisualScreenshot(
                dataUrl = "data:image/jpeg;base64," +
                    Base64.encodeToString(bytes, Base64.NO_WRAP),
                width = scaled.width,
                height = scaled.height,
                sourceWidth = source.width,
                sourceHeight = source.height,
            )
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }
}

internal data class EncodedVisualScreenshot(
    val dataUrl: String,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
)
