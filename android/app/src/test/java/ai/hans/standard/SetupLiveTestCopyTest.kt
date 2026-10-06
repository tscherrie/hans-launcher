package ai.hans.standard

import ai.hans.standard.localization.TestResourceTextResolver

import ai.hans.standard.setup.HansSetupStepRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupLiveTestCopyTest {
    @Test
    fun cameraInstructionsAdvanceOneConcreteActionAtATime() {
        val camera = SetupLiveTestCopy.cameraAction(HansSetupStepRecord(), text = TestResourceTextResolver(java.util.Locale.GERMAN))
        assertTrue(camera.startsWith("Nimm jetzt"))
        assertFalse(camera.contains("Diktat", ignoreCase = true))
        assertFalse(camera.contains("Halte", ignoreCase = true))

        val hold = SetupLiveTestCopy.cameraAction(
            HansSetupStepRecord(
                detailCode = "camera_capture_receipt_observed",
                auxiliaryEvidenceObserved = true,
            ),
        text = TestResourceTextResolver(java.util.Locale.GERMAN))
        assertTrue(hold.startsWith("Halte jetzt"))
        assertFalse(hold.contains("Foto", ignoreCase = true))
        assertFalse(hold.contains("Sprich", ignoreCase = true))

        val speak = SetupLiveTestCopy.cameraAction(
            HansSetupStepRecord(
                detailCode = "camera_recording_listening_observed",
                liveStartObserved = true,
                auxiliaryEvidenceObserved = true,
            ),
        text = TestResourceTextResolver(java.util.Locale.GERMAN))
        assertTrue(speak.startsWith("Sprich jetzt"))
        assertFalse(speak.contains("Foto", ignoreCase = true))
        assertFalse(speak.contains("Halte", ignoreCase = true))
    }

    @Test
    fun voicePreviewAndDictationAreSeparateInstructions() {
        assertTrue(SetupLiveTestCopy.voicePreviewAction(TestResourceTextResolver(java.util.Locale.GERMAN)).startsWith("Hör jetzt"))
        assertFalse(SetupLiveTestCopy.voicePreviewAction(TestResourceTextResolver(java.util.Locale.GERMAN)).contains("Diktat", ignoreCase = true))
        assertFalse(SetupLiveTestCopy.voicePreviewAction(TestResourceTextResolver(java.util.Locale.GERMAN)).contains("Sprich", ignoreCase = true))
        assertTrue(SetupLiveTestCopy.voiceDictationAction(TestResourceTextResolver(java.util.Locale.GERMAN)).startsWith("Sprich jetzt"))
        assertFalse(SetupLiveTestCopy.voiceDictationAction(TestResourceTextResolver(java.util.Locale.GERMAN)).contains("Hör", ignoreCase = true))
    }
}
