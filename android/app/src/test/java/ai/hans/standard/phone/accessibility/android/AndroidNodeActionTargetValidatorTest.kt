package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.BoundedSemanticUiSnapshotFactory
import ai.hans.standard.phone.accessibility.SemanticUiAction
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNodeActionTargetValidatorTest {
    @Test
    fun unrelatedDynamicTreeChurnKeepsTheExactTargetActionable() {
        val expected = capture(
            root = dynamicNestedRoot(
                target = targetWithUniqueId(),
                status = FakeAndroidAccessibilityNode(nodeText = "Traffic: light"),
            ),
            correlation = testCorrelation(),
        )
        val live = capture(
            root = dynamicNestedRoot(
                target = targetWithUniqueId(),
                status = FakeAndroidAccessibilityNode(
                    nodeText = "Traffic updated: moderate",
                    children = mutableListOf(
                        FakeAndroidAccessibilityNode(nodeText = "ETA 18 min"),
                    ),
                ),
            ),
            correlation = testCorrelation(snapshot = 2),
        )

        assertTrue(expected.targetLocator.nodeOrdinal != live.targetLocator.nodeOrdinal)
        assertTrue(
            AndroidNodeActionTargetValidator.matches(
                expectedSnapshot = expected.snapshot,
                expectedLocator = expected.targetLocator,
                liveSnapshot = live.snapshot,
                liveLocator = live.targetLocator,
            ),
        )
    }

    @Test
    fun changedRootWindowOrDisplayFailsClosed() {
        val expected = capture(dynamicRoot(targetWithUniqueId()), testCorrelation())
        val wrongRoot = capture(
            root = dynamicRoot(
                target = targetWithUniqueId(),
                packageName = "com.example.lookalike",
            ),
            correlation = testCorrelation(snapshot = 2),
        )
        val wrongWindow = capture(
            root = dynamicRoot(
                target = targetWithUniqueId(nodeWindowId = 8),
                status = FakeAndroidAccessibilityNode(nodeWindowId = 8, nodeText = "status"),
                nodeWindowId = 8,
            ),
            correlation = testCorrelation(snapshot = 2, window = 8),
        )
        val wrongDisplay = capture(
            root = dynamicRoot(targetWithUniqueId()),
            correlation = testCorrelation(snapshot = 2),
            displayId = 1,
        )

        listOf(wrongRoot, wrongWindow, wrongDisplay).forEach { live ->
            assertFalse(
                AndroidNodeActionTargetValidator.matches(
                    expectedSnapshot = expected.snapshot,
                    expectedLocator = expected.targetLocator,
                    liveSnapshot = live.snapshot,
                    liveLocator = live.targetLocator,
                ),
            )
        }
    }

    @Test
    fun movedOrReusedTargetFailsClosed() {
        val expectedUnique = capture(dynamicRoot(targetWithUniqueId()), testCorrelation())
        val movedUnique = capture(
            root = dynamicRoot(
                target = targetWithUniqueId(),
                leadingSibling = FakeAndroidAccessibilityNode(nodeText = "new banner"),
            ),
            correlation = testCorrelation(snapshot = 2),
        )
        assertFalse(
            AndroidNodeActionTargetValidator.matches(
                expectedUnique.snapshot,
                expectedUnique.targetLocator,
                movedUnique.snapshot,
                movedUnique.targetLocator,
            ),
        )

        val expectedView = capture(dynamicRoot(targetWithViewId()), testCorrelation())
        val reusedView = capture(
            root = dynamicRoot(
                target = targetWithViewId(bounds = UiBounds(40, 300, 1_000, 450)),
            ),
            correlation = testCorrelation(snapshot = 2),
        )
        assertFalse(
            AndroidNodeActionTargetValidator.matches(
                expectedView.snapshot,
                expectedView.targetLocator,
                reusedView.snapshot,
                reusedView.targetLocator,
            ),
        )
    }

    @Test
    fun changedTargetTextOrDescriptionFailsClosedWhileSiblingTextMayChange() {
        val expected = capture(
            dynamicRoot(
                targetWithUniqueId(text = "Start", contentDescription = "Start route"),
            ),
            testCorrelation(),
        )
        val changedText = capture(
            dynamicRoot(
                targetWithUniqueId(text = "Delete", contentDescription = "Start route"),
            ),
            testCorrelation(snapshot = 2),
        )
        val changedDescription = capture(
            dynamicRoot(
                targetWithUniqueId(text = "Start", contentDescription = "Delete route"),
            ),
            testCorrelation(snapshot = 3),
        )

        listOf(changedText, changedDescription).forEach { live ->
            assertFalse(
                AndroidNodeActionTargetValidator.matches(
                    expected.snapshot,
                    expected.targetLocator,
                    live.snapshot,
                    live.targetLocator,
                ),
            )
        }
    }

    private fun capture(
        root: FakeAndroidAccessibilityNode,
        correlation: UiSnapshotCorrelation,
        displayId: Int = 0,
    ): CapturedTree {
        val outcome = AndroidSemanticTreeProjector().project(
            root = root,
            correlation = correlation,
            displayId = displayId,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = correlation.snapshotId.value,
        )
        return CapturedTree(
            snapshot = BoundedSemanticUiSnapshotFactory().build(checkNotNull(outcome.rawSnapshot)),
            targetLocator = outcome.locators.single { it.uniqueId == TARGET_UNIQUE_ID ||
                it.viewIdResourceName == TARGET_VIEW_ID },
        )
    }

    private fun dynamicRoot(
        target: FakeAndroidAccessibilityNode,
        status: FakeAndroidAccessibilityNode = FakeAndroidAccessibilityNode(nodeText = "status"),
        leadingSibling: FakeAndroidAccessibilityNode? = null,
        packageName: String = DYNAMIC_APP_PACKAGE,
        nodeWindowId: Int = 7,
    ): FakeAndroidAccessibilityNode {
        val children = mutableListOf<FakeAndroidAccessibilityNode?>()
        if (leadingSibling != null) children += leadingSibling
        children += target
        children += status
        return FakeAndroidAccessibilityNode(
            nodeWindowId = nodeWindowId,
            nodePackageName = packageName,
            nodeClassName = "android.widget.FrameLayout",
            nodeText = "Dynamic app",
            children = children,
        )
    }

    private fun dynamicNestedRoot(
        target: FakeAndroidAccessibilityNode,
        status: FakeAndroidAccessibilityNode,
    ): FakeAndroidAccessibilityNode {
        val targetContainer = FakeAndroidAccessibilityNode(
            nodePackageName = DYNAMIC_APP_PACKAGE,
            nodeClassName = "android.widget.LinearLayout",
            children = mutableListOf(target),
        )
        return FakeAndroidAccessibilityNode(
            nodePackageName = DYNAMIC_APP_PACKAGE,
            nodeClassName = "android.widget.FrameLayout",
            nodeText = "Dynamic app",
            children = mutableListOf(status, targetContainer),
        )
    }

    private fun targetWithUniqueId(
        text: String = "Start",
        contentDescription: String = "Start route",
        nodeWindowId: Int = 7,
    ) = FakeAndroidAccessibilityNode(
        nodeWindowId = nodeWindowId,
        nodeUniqueId = TARGET_UNIQUE_ID,
        nodePackageName = DYNAMIC_APP_PACKAGE,
        nodeClassName = "android.widget.Button",
        nodeText = text,
        nodeContentDescription = contentDescription,
        nodeRole = SemanticUiRole.BUTTON,
        nodeBounds = UiBounds(40, 1_900, 1_000, 2_080),
        nodeClickable = true,
        nodeActions = setOf(SemanticUiAction.CLICK),
    )

    private fun targetWithViewId(
        bounds: UiBounds = UiBounds(40, 1_900, 1_000, 2_080),
    ) = FakeAndroidAccessibilityNode(
        nodeViewIdResourceName = TARGET_VIEW_ID,
        nodePackageName = DYNAMIC_APP_PACKAGE,
        nodeClassName = "android.widget.Button",
        nodeText = "Start",
        nodeRole = SemanticUiRole.BUTTON,
        nodeBounds = bounds,
        nodeClickable = true,
        nodeActions = setOf(SemanticUiAction.CLICK),
    )

    private data class CapturedTree(
        val snapshot: SemanticUiSnapshot,
        val targetLocator: AndroidNodeLocator,
    )

    private companion object {
        const val DYNAMIC_APP_PACKAGE = "org.example.dynamic"
        const val TARGET_UNIQUE_ID = "dynamic-primary-action"
        const val TARGET_VIEW_ID = "org.example.dynamic:id/primary_action"
    }
}
