package ai.hans.standard.setup

import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityWindowId
import ai.hans.standard.phone.accessibility.BoundedSemanticUiSnapshotFactory
import ai.hans.standard.phone.accessibility.RawSemanticUiNode
import ai.hans.standard.phone.accessibility.RawSemanticUiSnapshot
import ai.hans.standard.phone.accessibility.SemanticUiAction
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySetupReceiptTrackerTest {
    private val session = AccessibilitySessionId("accessibility-session-setup")
    private val nonce = "setup_nonce_123456789"
    private val tracker = AccessibilitySetupReceiptTracker(OWN_PACKAGE)

    @Test
    fun unrelatedFreshSnapshotCannotVerifyAccessibilitySetup() {
        tracker.arm(nonce, session, snapshot(1, node(text = "Vorher")))

        assertFalse(tracker.observeTargetAction(nonce, session, snapshot(2, node(text = "Fremd"))))
        assertFalse(
            tracker.observePostcondition(
                nonce,
                session,
                snapshot(3, node(text = ACCESSIBILITY_SETUP_POSTCONDITION_TEXT)),
            ),
        )
        assertEquals(AccessibilitySetupReceiptState.ARMED, tracker.state(nonce))
    }

    @Test
    fun exactNonceBoundTargetActionAndFreshPostconditionVerify() {
        tracker.arm(nonce, session, snapshot(1, node(text = "Vorher")))
        val target = node(
            contentDescription = ACCESSIBILITY_SETUP_TARGET_DESCRIPTION,
            role = SemanticUiRole.BUTTON,
            clickable = true,
            actions = setOf(SemanticUiAction.CLICK),
        )

        assertTrue(tracker.observeTargetAction(nonce, session, snapshot(2, target)))
        assertFalse(
            tracker.observePostcondition(
                nonce,
                session,
                snapshot(2, node(text = ACCESSIBILITY_SETUP_POSTCONDITION_TEXT)),
            ),
        )
        assertTrue(
            tracker.observePostcondition(
                nonce,
                session,
                snapshot(3, node(text = ACCESSIBILITY_SETUP_POSTCONDITION_TEXT)),
            ),
        )
        assertEquals(AccessibilitySetupReceiptState.VERIFIED, tracker.state(nonce))
        assertEquals(AccessibilitySetupReceiptState.NOT_ARMED, tracker.state("different_nonce_123"))
    }

    @Test
    fun staleOrWrongPackageTargetCannotCountAsAction() {
        val baseline = snapshot(4, node(text = "Vorher"))
        tracker.arm(nonce, session, baseline)
        val correctShapeWrongPackage = node(
            packageName = "other.app",
            contentDescription = ACCESSIBILITY_SETUP_TARGET_DESCRIPTION,
            role = SemanticUiRole.BUTTON,
            clickable = true,
            actions = setOf(SemanticUiAction.CLICK),
        )
        val staleCorrectTarget = node(
            contentDescription = ACCESSIBILITY_SETUP_TARGET_DESCRIPTION,
            role = SemanticUiRole.BUTTON,
            clickable = true,
            actions = setOf(SemanticUiAction.CLICK),
        )

        assertFalse(tracker.observeTargetAction(nonce, session, snapshot(5, correctShapeWrongPackage)))
        assertFalse(tracker.observeTargetAction(nonce, session, snapshot(4, staleCorrectTarget)))
    }

    private fun snapshot(id: Long, root: RawSemanticUiNode) =
        BoundedSemanticUiSnapshotFactory().build(
            RawSemanticUiSnapshot(
                correlation = UiSnapshotCorrelation(
                    sessionId = session,
                    windowId = AccessibilityWindowId(7),
                    snapshotId = AccessibilitySnapshotId(id),
                ),
                displayBounds = UiBounds(0, 0, 1_080, 2_400),
                capturedAtElapsedMillis = id,
                roots = listOf(root),
            ),
        )

    private fun node(
        packageName: String = OWN_PACKAGE,
        text: String? = null,
        contentDescription: String? = null,
        role: SemanticUiRole = SemanticUiRole.TEXT,
        clickable: Boolean = false,
        actions: Set<SemanticUiAction> = emptySet(),
    ) = RawSemanticUiNode(
        packageName = packageName,
        className = "android.view.View",
        text = text,
        contentDescription = contentDescription,
        role = role,
        bounds = UiBounds(0, 0, 500, 100),
        clickable = clickable,
        actions = actions,
    )

    private companion object {
        const val OWN_PACKAGE = "ai.hans.standard"
    }
}
