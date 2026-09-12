package ai.hans.standard.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraCaptureSelectionPolicyTest {
    @Test
    fun oneCameraActionOffersPhotoThenVideo() {
        assertEquals(
            listOf(CameraCaptureKind.PHOTO, CameraCaptureKind.VIDEO),
            CameraCaptureSelectionPolicy.choices.map(CameraCaptureChoice::kind),
        )
        assertEquals(
            listOf("Foto aufnehmen", "Video aufnehmen"),
            CameraCaptureSelectionPolicy.choices.map(CameraCaptureChoice::label),
        )
    }

    @Test
    fun launcherRoutesTheSingleCameraActionToChooserAndImportsVideoAsVideo() {
        val source = File("src/main/java/ai/hans/standard/LauncherActivity.kt").readText()

        assertTrue(source.contains("onChooseMedia = ::showCameraCaptureChoice"))
        assertTrue(source.contains("SoftwareHoldGestureEffect.CapturePhoto -> showCameraCaptureChoice()"))
        assertTrue(source.contains("CameraCaptureKind.VIDEO -> launchVideoCapture()"))
        assertTrue(source.contains("cameraCaptureCoordinator.prepareVideoCapture()"))
        assertTrue(source.contains("cameraCaptureCoordinator.acceptVideoResult(captured)"))
        assertTrue(source.contains("expectedKind = ai.hans.standard.media.MediaKind.VIDEO"))
        val setupCapturePath = source.substringAfter("private fun beginDirectSetupCameraCapture(")
            .substringBefore("private fun showNextCameraSetupAction")
        assertTrue(setupCapturePath.contains("cameraCaptureCoordinator.prepareCapture()"))
        assertFalse(setupCapturePath.contains("showCameraCaptureChoice"))
        assertFalse(setupCapturePath.contains("prepareVideoCapture"))
        val videoLaunchPath = source.substringAfter("private fun launchVideoCapture()")
            .substringBefore("private fun importCapturedPhoto")
        assertEquals(3, Regex.fromLiteral("cameraCaptureCoordinator.abandon(preparation)")
            .findAll(videoLaunchPath).count())
        assertFalse(source.contains("ActivityResultContracts.PickVisualMedia"))
        assertFalse(source.contains("ActivityResultContracts.GetContent"))
        assertFalse(source.contains("MediaStore.ACTION_PICK_IMAGES"))
    }
}
