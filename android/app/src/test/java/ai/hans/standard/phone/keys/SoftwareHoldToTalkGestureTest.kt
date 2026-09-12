package ai.hans.standard.phone.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareHoldToTalkGestureTest {
    private val controller = SoftwareHoldToTalkGestureController(longPressMillis = 500)

    @Test
    fun shortTapCapturesPhotoWithoutStartingDictation() {
        val id = requireNotNull(controller.onPointerDown(1_000))

        assertEquals(
            listOf(SoftwareHoldGestureEffect.CapturePhoto),
            controller.onPointerUp(id, 1_499),
        )
        assertFalse(controller.ownsRecording())
    }

    @Test
    fun longPressStartsAndReleaseStopsOnlyOwnedRecording() {
        val id = requireNotNull(controller.onPointerDown(1_000))

        assertEquals(
            listOf(SoftwareHoldGestureEffect.StartOwnedDictation(id)),
            controller.onLongPress(id, 1_500, false, true),
        )
        assertTrue(controller.ownsRecording())
        assertEquals(
            listOf(SoftwareHoldGestureEffect.StopOwnedDictation(id)),
            controller.onPointerUp(id, 1_700),
        )
    }

    @Test
    fun existingRecordingIsNeverTakenOverOrStopped() {
        val id = requireNotNull(controller.onPointerDown(1_000))

        assertTrue(controller.onLongPress(id, 1_500, true, true).isEmpty())
        assertFalse(controller.ownsRecording())
        assertTrue(controller.onPointerUp(id, 2_000).isEmpty())
        assertTrue(controller.onFocusLost().isEmpty())
    }

    @Test
    fun releasingBeforePermissionResultPreventsDelayedStart() {
        val id = requireNotNull(controller.onPointerDown(1_000))
        assertEquals(
            listOf(SoftwareHoldGestureEffect.RequestMicrophonePermission(id)),
            controller.onLongPress(id, 1_500, false, false),
        )

        assertTrue(controller.onPointerUp(id, 1_600).isEmpty())
        assertTrue(controller.onMicrophonePermissionResult(id, true, false).isEmpty())
        assertFalse(controller.ownsRecording())
    }

    @Test
    fun focusLossStopsOnlyGestureOwnedRecording() {
        val id = requireNotNull(controller.onPointerDown(1_000))
        controller.onLongPress(id, 1_500, false, true)

        assertEquals(
            listOf(SoftwareHoldGestureEffect.StopOwnedDictation(id)),
            controller.onFocusLost(),
        )
        assertTrue(controller.onPointerUp(id, 1_800).isEmpty())
    }

    @Test
    fun staleThresholdAndPermissionCallbacksCannotAffectNewGesture() {
        val first = requireNotNull(controller.onPointerDown(1_000))
        controller.onPointerCancel(first, 1_100)
        val second = requireNotNull(controller.onPointerDown(2_000))

        assertTrue(controller.onLongPress(first, 2_500, false, true).isEmpty())
        assertTrue(controller.onMicrophonePermissionResult(first, true, false).isEmpty())
        assertNotNull(second)
        assertEquals(
            listOf(SoftwareHoldGestureEffect.CapturePhoto),
            controller.onPointerUp(second, 2_100),
        )
    }

    @Test
    fun lateUpAfterMissedThresholdNeverAccidentallyOpensCamera() {
        val id = requireNotNull(controller.onPointerDown(1_000))

        assertTrue(controller.onPointerUp(id, 1_500).isEmpty())
    }

    @Test
    fun completedOrTimedOutOwnedRecordingCannotStopAReplacementRecording() {
        val id = requireNotNull(controller.onPointerDown(1_000))
        controller.onLongPress(id, 1_500, false, true)
        controller.onOwnedRecordingEnded()

        assertTrue(controller.onPointerUp(id, 2_000).isEmpty())
    }
}
