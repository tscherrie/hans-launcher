package ai.hans.standard.media

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class AndroidMediaImportPipelineTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var testRoot: File
    private lateinit var sourceRoot: File

    @Before
    fun setUp() {
        testRoot = File(context.filesDir, "media-test-${UUID.randomUUID()}")
        sourceRoot = File(context.cacheDir, "media-source-${UUID.randomUUID()}")
        check(testRoot.mkdir())
        check(sourceRoot.mkdir())
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
        sourceRoot.deleteRecursively()
    }

    @Test
    fun importsTransientImageIntoCommittedPrivateNormalizedArtifact() {
        val source = File(sourceRoot, "picked.png")
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
        FileOutputStream(source).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.fd.sync()
        }
        bitmap.recycle()

        val pipeline = AndroidMediaImportPipeline(
            context = context,
            store = AtomicPrivateMediaStore(context, testRoot),
            nowMillis = { 123L },
        )
        val result = pipeline.import(Uri.fromFile(source), MediaKind.IMAGE)
            as MediaImportResult.Image

        assertEquals(32, result.metadata.width)
        assertEquals(24, result.metadata.height)
        assertEquals("image/png", result.metadata.sourceMimeType)
        assertEquals("image/jpeg", result.image.mimeType)
        assertTrue(File(result.image.absolutePath).isFile)
        assertTrue(File(result.manifestPath).readText().contains("\"createdAtEpochMillis\":123"))
        assertTrue(File(result.image.absolutePath).canonicalPath.startsWith(context.filesDir.canonicalPath))
        assertFalse(testRoot.listFiles().orEmpty().any { it.name.startsWith(".import-") })

        assertTrue(pipeline.deleteAndConfirmAbsent(result.importId))
        assertFalse(File(result.image.absolutePath).exists())
        assertTrue(pipeline.deleteAndConfirmAbsent(result.importId))
    }

    @Test
    fun invalidImageLeavesNoCommittedOrStagingArtifact() {
        val source = File(sourceRoot, "not-an-image.jpg").apply {
            writeText("This is not an image")
        }
        val pipeline = AndroidMediaImportPipeline(
            context = context,
            store = AtomicPrivateMediaStore(context, testRoot),
        )

        val failure = assertThrows(MediaImportException::class.java) {
            pipeline.import(Uri.fromFile(source), MediaKind.IMAGE)
        }

        assertEquals(MediaImportFailureCode.INVALID_IMAGE, failure.code)
        assertTrue(testRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun reportedOrReadBytesCannotBypassConfiguredLimit() {
        val source = File(sourceRoot, "oversized.bin").apply {
            writeBytes(ByteArray(64) { it.toByte() })
        }
        val pipeline = AndroidMediaImportPipeline(
            context = context,
            store = AtomicPrivateMediaStore(context, testRoot),
            limits = MediaImportLimits(maxImageInputBytes = 32),
        )

        val failure = assertThrows(MediaImportException::class.java) {
            pipeline.import(Uri.fromFile(source), MediaKind.IMAGE)
        }

        assertTrue(
            failure.code == MediaImportFailureCode.DECLARED_SIZE_EXCEEDED ||
                failure.code == MediaImportFailureCode.READ_LIMIT_EXCEEDED,
        )
        assertTrue(testRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun refusesStorageOutsideFilesDirectory() {
        assertThrows(IllegalArgumentException::class.java) {
            AtomicPrivateMediaStore(context, context.cacheDir)
        }
    }
}
