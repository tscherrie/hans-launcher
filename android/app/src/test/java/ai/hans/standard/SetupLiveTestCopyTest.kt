package ai.hans.standard

import ai.hans.standard.setup.HansSetupStepRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupLiveTestCopyTest {
    @Test
    fun cameraInstructionsAdvanceOneConcreteActionAtATime() {
        val camera = SetupLiveTestCopy.cameraAction(HansSetupStepRecord())
        assertTrue(camera.startsWith("Nimm jetzt"))
        assertFalse(camera.contains("Diktat", ignoreCase = true))
        assertFalse(camera.contains("Halte", ignoreCase = true))

        val hold = SetupLiveTestCopy.cameraAction(
            HansSetupStepRecord(
                detailCode = "camera_capture_receipt_observed",
                auxiliaryEvidenceObserved = true,
            ),
        )
        assertTrue(hold.startsWith("Halte jetzt"))
        assertFalse(hold.contains("Foto", ignoreCase = true))
        assertFalse(hold.contains("Sprich", ignoreCase = true))

        val speak = SetupLiveTestCopy.cameraAction(
            HansSetupStepRecord(
                detailCode = "camera_recording_listening_observed",
                liveStartObserved = true,
                auxiliaryEvidenceObserved = true,
            ),
        )
        assertTrue(speak.startsWith("Sprich jetzt"))
        assertFalse(speak.contains("Foto", ignoreCase = true))
        assertFalse(speak.contains("Halte", ignoreCase = true))
    }

    @Test
    fun voicePreviewAndDictationAreSeparateInstructions() {
        assertTrue(SetupLiveTestCopy.VOICE_PREVIEW_ACTION.startsWith("Hör jetzt"))
        assertFalse(SetupLiveTestCopy.VOICE_PREVIEW_ACTION.contains("Diktat", ignoreCase = true))
        assertFalse(SetupLiveTestCopy.VOICE_PREVIEW_ACTION.contains("Sprich", ignoreCase = true))
        assertTrue(SetupLiveTestCopy.VOICE_DICTATION_ACTION.startsWith("Sprich jetzt"))
        assertFalse(SetupLiveTestCopy.VOICE_DICTATION_ACTION.contains("Hör", ignoreCase = true))
    }
}
