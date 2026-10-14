package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.BoundedSemanticUiSnapshotFactory
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.SnapshotTruncationReason
import ai.hans.standard.phone.accessibility.UiBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualFallbackFrameGuardTest {
    @Test
    fun identicalCompleteFrameSurvivesSnapshotIdChurnWithoutChangingIdentity() {
        assertTrue(VisualFallbackFrameGuard.matches(frame(1), frame(2)))
    }

    @Test
    fun sameWindowNeverSufficesWhenDisplayRootIdentityTextOrLayoutChanges() {
        val original = frame(1)
        val changes = listOf(
            frame(2, display = 2),
            frame(2, window = 8),
            frame(2, session = "another-session-0001"),
            frame(2, rootUniqueId = "different-root"),
            frame(2, text = "changed"),
            frame(2, packageName = "another.package"),
            frame(2, bounds = UiBounds(1, 0, 100, 100)),
            frame(2, displayBounds = UiBounds(0, 0, 900, 2_000)),
        )
        changes.forEach { assertFalse(VisualFallbackFrameGuard.matches(original, it)) }
    }

    @Test
    fun missingLocatorAndTruncatedSnapshotsNeverRenewProof() {
        val original = frame(1)
        val full = frame(2)
        val withoutLocator = AndroidAccessibilityFrame(full.snapshot, full.displayId, emptyList())
        val truncatedSnapshot = copyTruncated(full.snapshot)
        val truncated = AndroidAccessibilityFrame(truncatedSnapshot, full.displayId, full.locators)

        assertFalse(VisualFallbackFrameGuard.isComplete(withoutLocator))
        assertFalse(VisualFallbackFrameGuard.matches(original, withoutLocator))
        assertFalse(VisualFallbackFrameGuard.matches(original, truncated))
        assertFalse(VisualFallbackFrameGuard.matches(truncated, full))
    }

    @Test
    fun multipleRootsDoNotAuthorizeAFrameEvenWithIdenticalSemanticContent() {
        val snapshot = semanticSnapshot(roots = listOf(rawNode(), rawNode()))
        val frame = AndroidAccessibilityFrame(snapshot, 0, emptyList())
        assertFalse(VisualFallbackFrameGuard.matches(frame, frame))
    }

    @Test
    fun visualScreenshotGuardRejectsRootReplacementEvenWithIdenticalCopiedTree() {
        val before = frame(1)
        val after = frame(2, rootUniqueId = "replacement-root")
        assertTrue(SemanticSnapshotContent.same(before.snapshot, after.snapshot))
        assertFalse(VisualCaptureFrameCorrelationGuard.matches(before, after, 0))
        assertFalse(VisualCaptureFrameCorrelationGuard.matches(before, frame(2), 2))
    }

    @Test
    fun checkingAnIdenticalFrameDoesNotExtendOriginalCommandExpiry() {
        var elapsed = 0L
        val store = RetainedAccessibilityFrameStore({ elapsed }, commandTtlMillis = 1_000)
        val original = frame(1)
        store.publish(original)
        assertTrue(store.retainCurrentForCommand(original.correlation))
        elapsed = 999
        val current = frame(2)
        store.publish(current)
        assertTrue(VisualFallbackFrameGuard.matches(
            checkNotNull(store.commandFrame(original.correlation)), current,
        ))
        assertEquals(1, store.retainedCommandCount())
        elapsed = 1_000
        assertNull(store.commandFrame(original.correlation))
        assertEquals(0, store.retainedCommandCount())
    }

    @Test
    fun finalGestureBoundaryRejectsInterveningPublicationAndChangedLiveRoot() {
        val expected = frame(1)
        assertTrue(CoordinateGestureFrameGuard.matches(expected, expected))
        assertFalse(CoordinateGestureFrameGuard.matches(expected, null))
        // Fallback proof can survive unchanged ID churn, but a gesture already queued for the
        // old ID must be rejected at its final dispatch boundary, never silently redirected.
        assertTrue(VisualFallbackFrameGuard.matches(expected, frame(2)))
        assertFalse(CoordinateGestureFrameGuard.matches(expected, frame(2)))
        assertFalse(CoordinateGestureFrameGuard.matches(expected, frame(1, window = 8)))
        assertFalse(CoordinateGestureFrameGuard.matches(expected, frame(1, display = 2)))
        assertFalse(CoordinateGestureFrameGuard.matches(expected, frame(1, text = "changed")))
        assertFalse(CoordinateGestureFrameGuard.matches(expected, frame(1, rootUniqueId = "new-root")))
    }

    private fun frame(
        number: Long,
        window: Int = 7,
        display: Int = 0,
        session: String = "accessibility-session-0001",
        rootUniqueId: String = "stable-root",
        packageName: String = "test.package",
        text: String = "unchanged",
        bounds: UiBounds = UiBounds(0, 0, 100, 100),
        displayBounds: UiBounds = TEST_DISPLAY_BOUNDS,
    ): AndroidAccessibilityFrame {
        val projected = AndroidSemanticTreeProjector().project(
            root = FakeAndroidAccessibilityNode(
                nodeWindowId = window,
                nodeUniqueId = rootUniqueId,
                nodePackageName = packageName,
                nodeText = text,
                nodeBounds = bounds,
            ),
            correlation = testCorrelation(number, window, session),
            displayId = display,
            displayBounds = displayBounds,
            capturedAtElapsedMillis = number,
        )
        return AndroidAccessibilityFrame(
            BoundedSemanticUiSnapshotFactory().build(checkNotNull(projected.rawSnapshot)),
            display,
            projected.locators,
        )
    }

    private fun copyTruncated(snapshot: SemanticUiSnapshot) = SemanticUiSnapshot(
        correlation = snapshot.correlation,
        displayBounds = snapshot.displayBounds,
        capturedAtElapsedMillis = snapshot.capturedAtElapsedMillis,
        nodes = snapshot.nodes,
        roots = snapshot.roots,
        truncationReasons = setOf(SnapshotTruncationReason.PLATFORM_NODE_UNAVAILABLE),
        estimatedBytes = snapshot.estimatedBytes,
        totalTextCharacters = snapshot.totalTextCharacters,
    )
}
