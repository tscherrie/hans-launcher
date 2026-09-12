package ai.hans.standard.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidCameraCaptureCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var captureDirectory: File
    private lateinit var importDirectory: File
    private lateinit var preferencesName: String

    @Before
    fun setUp() {
        val testId = UUID.randomUUID().toString()
        captureDirectory = File(
            context.cacheDir,
            "${AndroidCameraCaptureCoordinator.CAPTURE_DIRECTORY}/test-$testId",
        )
        importDirectory = File(context.filesDir, "camera-import-test-$testId")
        preferencesName = "camera-capture-test-$testId"
    }

    @After
    fun tearDown() {
        captureDirectory.deleteRecursively()
        importDirectory.deleteRecursively()
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    @Suppress("DEPRECATION")
    fun fullResolutionContractUsesImageCaptureOutputAndTransientGrants() {
        val output = Uri.parse("content://${context.packageName}.fileprovider/camera/photo.jpg")
        val contract = FullResolutionCameraContract()

        val intent = contract.createIntent(context, output)

        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
        assertEquals(output, intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT))
        assertEquals(output, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(contract.parseResult(Activity.RESULT_OK, null))
        assertFalse(contract.parseResult(Activity.RESULT_CANCELED, null))
    }

    @Test
    @Suppress("DEPRECATION")
    fun fullResolutionVideoContractUsesVideoCaptureOutputAndTransientGrants() {
        val output = Uri.parse("content://${context.packageName}.fileprovider/camera/video.mp4")
        val contract = FullResolutionVideoContract()

        val intent = contract.createIntent(context, output)

        assertEquals(MediaStore.ACTION_VIDEO_CAPTURE, intent.action)
        assertEquals(output, intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT))
        assertEquals(output, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(contract.parseResult(Activity.RESULT_OK, Intent().setData(Uri.EMPTY)))
        assertFalse(contract.parseResult(Activity.RESULT_CANCELED, null))
    }

    @Test
    fun successfulCaptureImportsFullImageThenDeletesTemporarySource() {
        val coordinator = coordinator(cameraAvailable = true)
        val preparation = coordinator.prepareCapture() as CameraCapturePreparation.Ready
        context.contentResolver.openOutputStream(preparation.outputUri, "w")!!.use { output ->
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            try {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            } finally {
                bitmap.recycle()
            }
        }

        val photo = coordinator.acceptResult(captured = true)

        assertNotNull(photo)
        val imported = AndroidMediaImportPipeline(
            context = context,
            store = AtomicPrivateMediaStore(context, importDirectory),
        ).import(photo!!.contentUri, expectedKind = MediaKind.IMAGE)
        assertTrue(imported is MediaImportResult.Image)
        assertTrue((imported as MediaImportResult.Image).image.byteCount > 0L)

        coordinator.completeImport(photo)

        assertTrue(captureDirectory.listFiles().orEmpty().isEmpty())
        assertNull(
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                .getString(AndroidCameraCaptureCoordinator.PENDING_FILE_KEY, null),
        )
    }

    @Test
    fun cancelledOrEmptyCaptureIsRejectedAndTemporarySourceIsDeleted() {
        val coordinator = coordinator(cameraAvailable = true)
        coordinator.prepareCapture() as CameraCapturePreparation.Ready

        assertNull(coordinator.acceptResult(captured = false))
        assertTrue(captureDirectory.listFiles().orEmpty().isEmpty())

        coordinator.prepareCapture() as CameraCapturePreparation.Ready
        assertNull(coordinator.acceptResult(captured = true))
        assertTrue(captureDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun successfulVideoResultAcceptsOnlyFreshPrivateVideoAndDeletesItAfterImport() {
        val coordinator = coordinator(cameraAvailable = true, videoAvailable = true)
        val preparation = coordinator.prepareVideoCapture() as CameraCapturePreparation.Ready
        assertEquals(CameraCaptureKind.VIDEO, preparation.kind)
        assertTrue(preparation.fileName.endsWith(AndroidCameraCaptureCoordinator.VIDEO_CAPTURE_SUFFIX))
        context.contentResolver.openOutputStream(preparation.outputUri, "w")!!.use { output ->
            output.write(byteArrayOf(0, 0, 0, 20, 'f'.code.toByte(), 't'.code.toByte()))
        }

        val video = coordinator.acceptVideoResult(captured = true)

        assertNotNull(video)
        assertEquals(preparation.fileName, video!!.fileName)
        assertTrue(captureDirectory.resolve(preparation.fileName).isFile)

        coordinator.completeImport(video)

        assertTrue(captureDirectory.listFiles().orEmpty().isEmpty())
        assertNull(
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                .getString(AndroidCameraCaptureCoordinator.PENDING_FILE_KEY, null),
        )
    }

    @Test
    fun cancelledEmptyOrWrongKindVideoResultDeletesOnlyPendingPrivateCapture() {
        val coordinator = coordinator(cameraAvailable = true, videoAvailable = true)
        val unrelated = File(captureDirectory, "unrelated.txt")

        coordinator.prepareVideoCapture() as CameraCapturePreparation.Ready
        assertNull(coordinator.acceptVideoResult(captured = false))
        assertTrue(captureDirectory.listFiles().orEmpty().isEmpty())

        coordinator.prepareVideoCapture() as CameraCapturePreparation.Ready
        assertNull(coordinator.acceptVideoResult(captured = true))
        assertTrue(captureDirectory.listFiles().orEmpty().isEmpty())

        val photo = coordinator.prepareCapture() as CameraCapturePreparation.Ready
        context.contentResolver.openOutputStream(photo.outputUri, "w")!!.use { output ->
            output.write(byteArrayOf(1, 2, 3))
        }
        assertNull(coordinator.acceptVideoResult(captured = true))
        assertTrue(captureDirectory.listFiles().orEmpty().isEmpty())

        assertTrue(captureDirectory.isDirectory || captureDirectory.mkdirs())
        assertTrue(unrelated.createNewFile())
        val abandoned = coordinator.prepareVideoCapture() as CameraCapturePreparation.Ready
        coordinator.abandon(abandoned)
        assertTrue(unrelated.isFile)
        assertFalse(captureDirectory.resolve(abandoned.fileName).exists())
    }

    @Test
    fun abandonedVideoCrashCleanupRemovesOldCaptureButPreservesFreshAndUnrelatedFiles() {
        var now = 200_000_000L
        val coordinator = AndroidCameraCaptureCoordinator(
            context = context,
            captureDirectory = captureDirectory,
            pendingStore = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
            cameraAvailable = { true },
            videoCameraAvailable = { true },
            nowMillis = { now },
        )
        val old = coordinator.prepareVideoCapture() as CameraCapturePreparation.Ready
        val oldFile = captureDirectory.resolve(old.fileName)
        context.contentResolver.openOutputStream(old.outputUri, "w")!!.use { it.write(byteArrayOf(1)) }
        assertTrue(oldFile.setLastModified(10L))
        val unrelated = captureDirectory.resolve("keep.txt").apply { writeText("keep") }

        assertEquals(1, coordinator.cleanupAbandonedCaptures(olderThanEpochMillis = 20L))
        assertFalse(oldFile.exists())
        assertTrue(unrelated.isFile)

        now += 1
        val fresh = coordinator.prepareVideoCapture() as CameraCapturePreparation.Ready
        val freshFile = captureDirectory.resolve(fresh.fileName)
        context.contentResolver.openOutputStream(fresh.outputUri, "w")!!.use { it.write(byteArrayOf(2)) }
        // API 31 emulator filesystems may quantize sub-second mtimes. Read the
        // persisted value back and keep the cleanup boundary comfortably below
        // it so this verifies "fresh is preserved", not timestamp precision.
        assertTrue(freshFile.setLastModified(10_000L))
        val persistedFreshModified = freshFile.lastModified()
        assertTrue(persistedFreshModified > 1L)

        assertEquals(
            0,
            coordinator.cleanupAbandonedCaptures(
                olderThanEpochMillis = persistedFreshModified - 1L,
            ),
        )
        assertTrue(freshFile.isFile)
        assertTrue(unrelated.isFile)
    }

    @Test
    fun aPreviousNonemptyCaptureCannotSatisfyANewSetupCameraTest() {
        val coordinator = coordinator(cameraAvailable = true)
        val previous = coordinator.prepareCapture() as CameraCapturePreparation.Ready
        context.contentResolver.openOutputStream(previous.outputUri, "w")!!.use { output ->
            output.write(byteArrayOf(1, 2, 3, 4))
        }

        // Every test gets a new private filename and discards the previous receipt first.
        val current = coordinator.prepareCapture() as CameraCapturePreparation.Ready

        assertFalse(previous.fileName == current.fileName)
        assertNull(coordinator.acceptResult(captured = true))
        assertTrue(captureDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun missingCameraHandlerFailsClosedWithoutCreatingOutput() {
        val coordinator = coordinator(cameraAvailable = false)

        val result = coordinator.prepareCapture()

        assertEquals(CameraCapturePreparation.CameraUnavailable, result)
        assertFalse(captureDirectory.exists())
    }

    private fun coordinator(
        cameraAvailable: Boolean,
        videoAvailable: Boolean = cameraAvailable,
    ): AndroidCameraCaptureCoordinator =
        AndroidCameraCaptureCoordinator(
            context = context,
            captureDirectory = captureDirectory,
            pendingStore = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
            cameraAvailable = { cameraAvailable },
            videoCameraAvailable = { videoAvailable },
        )
}
