package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityWindowId
import ai.hans.standard.phone.accessibility.BoundedSemanticUiSnapshotFactory
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityRecoveryAndroidContractTest {
    @Test
    @Suppress("DEPRECATION")
    fun publicNodeWindowLookupFailureAndFallbackKeepNativeOwnershipBounded() {
        val native = AccessibilityNodeInfo.obtain()
        val wrapped = AccessibilityNodeInfoNode(native)
        // Android may return null or reject this unbound/unsealed test node. The resolver treats
        // either public lookup failure as unavailable, without using private sealing APIs.
        assertNull(runCatching(wrapped::window).getOrNull())
        var windowCloses = 0
        val owner = object : AndroidAccessibilityWindow {
            override val windowId = 41
            override val displayId = 7
            override val active = true
            override val focused = false
            override val kind = AndroidAccessibilityWindowKind.APPLICATION
            override fun root(): AndroidAccessibilityNode? = error("must not acquire another root")
            override fun close() { windowCloses += 1 }
        }
        val direct = object : AndroidAccessibilityNode by wrapped {
            override val windowId = 41
            override fun window() = owner
        }
        val resolved = AndroidActiveWindowRootResolver().resolve({ direct }) {
            error("synthetic transient public window enumeration failure")
        }
        assertSame(direct, resolved?.root)
        assertEquals(7, resolved?.displayId)
        assertEquals(1, windowCloses)
        checkNotNull(resolved).root.close()
        assertThrows(IllegalStateException::class.java) { wrapped.text }
    }

    @Test
    fun missingVisualBoundaryRootRecoversOnlyAfterEventWithinBoundedWait() {
        val current = AtomicReference<SemanticUiSnapshot?>(null)
        val began = CountDownLatch(1)
        var captures = 0
        val waiter = EventDrivenSemanticSnapshotWaiter(
            freshSnapshot = { captures += 1; began.countDown(); null },
            currentSnapshot = current::get,
            elapsedRealtimeMillis = SystemClock::elapsedRealtime,
        )
        val task = FutureTask { waiter.await(750, { false }, { true }) { true } }
        val worker = Thread(task).apply { isDaemon = true; start() }
        try {
            assertTrue(began.await(2, TimeUnit.SECONDS))
            val recovered = frame(2).snapshot
            current.set(recovered)
            waiter.wake()
            assertSame(recovered, task.get(2, TimeUnit.SECONDS))
            assertEquals(1, captures)
        } finally { worker.interrupt(); waiter.wake() }
    }

    @Test
    @android.annotation.SuppressLint("InlinedApi")
    fun frameworkDerivedFramesRequireUnchangedIdentityAndCannotExtendRetainedExpiry() {
        var now = 0L
        val store = RetainedAccessibilityFrameStore({ now }, commandTtlMillis = 1_000)
        val original = frame(1)
        store.publish(original)
        assertTrue(store.retainCurrentForCommand(original.correlation))
        val identical = frame(2)
        store.publish(identical)
        assertTrue(VisualFallbackFrameGuard.matches(original, identical))
        assertTrue(CoordinateGestureFrameGuard.matches(original, original))
        assertFalse(CoordinateGestureFrameGuard.matches(original, identical))
        assertFalse(CoordinateGestureFrameGuard.matches(original, frame(1, "different")))
        assertFalse(VisualFallbackFrameGuard.matches(original, frame(3, "different")))
        assertFalse(VisualCaptureFrameCorrelationGuard.matches(original, identical, 2))
        now = 1_000
        assertNull(store.commandFrame(original.correlation))
        assertEquals("visual_capture_secure_window", AccessibilityScreenshotFailure.code(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW,
        ))
    }

    @Suppress("DEPRECATION")
    private fun frame(number: Long, text: String = "same"): AndroidAccessibilityFrame {
        val native = AccessibilityNodeInfo.obtain().apply {
            packageName = "test.example"
            className = "android.view.View"
            this.text = text
            setBoundsInScreen(Rect(0, 0, 100, 100))
            isVisibleToUser = true
            isEnabled = true
        }
        val wrapped = AccessibilityNodeInfoNode(native)
        val root = object : AndroidAccessibilityNode by wrapped { override val windowId = 7 }
        val correlation = UiSnapshotCorrelation(
            AccessibilitySessionId("android-recovery-test-0001"),
            AccessibilityWindowId(7),
            AccessibilitySnapshotId(number),
        )
        val projected = AndroidSemanticTreeProjector().project(
            root, correlation, 0, UiBounds(0, 0, 1_080, 2_400), number,
        )
        return AndroidAccessibilityFrame(
            BoundedSemanticUiSnapshotFactory().build(checkNotNull(projected.rawSnapshot)),
            0,
            projected.locators,
        )
    }
}
