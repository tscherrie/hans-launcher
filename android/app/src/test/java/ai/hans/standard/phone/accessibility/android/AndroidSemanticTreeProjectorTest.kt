package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.BoundedSemanticUiSnapshotFactory
import ai.hans.standard.phone.accessibility.SemanticUiAction
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.SnapshotTruncationReason
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiSnapshotLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSemanticTreeProjectorTest {
    @Test
    fun projectCopiesBreadthFirstTreeAndClosesEveryLiveNodeExactlyOnce() {
        val grandchild = node("grandchild")
        val first = node("first", children = mutableListOf(grandchild))
        val second = node("second")
        val root = node("root", children = mutableListOf(first, second))

        val outcome = AndroidSemanticTreeProjector().project(
            root = root,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 12,
        )

        assertFalse(outcome.readFailed)
        assertFalse(outcome.projectionTruncated)
        val snapshot = BoundedSemanticUiSnapshotFactory().build(checkNotNull(outcome.rawSnapshot))
        assertEquals(listOf("root", "first", "second", "grandchild"), snapshot.nodes.map { it.text?.value })
        assertEquals(listOf(1, 2), snapshot.nodes[0].children.map { it.nodeOrdinal })
        assertEquals(listOf(3), snapshot.nodes[1].children.map { it.nodeOrdinal })
        listOf(root, first, second, grandchild).forEach { current ->
            assertTrue(current.isClosed)
            assertEquals(1, current.closeInvocations)
        }
    }

    @Test
    fun projectCopiesMutableCharSequencesAndActionDataBeforeRecycling() {
        val mutableText = StringBuilder("before")
        val root = FakeAndroidAccessibilityNode(
            nodeText = mutableText,
            nodeRole = SemanticUiRole.BUTTON,
            nodeClickable = true,
            nodeActions = setOf(SemanticUiAction.CLICK, SemanticUiAction.FOCUS),
        )

        val outcome = AndroidSemanticTreeProjector().project(
            root = root,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        )
        mutableText.replace(0, mutableText.length, "after")

        val copied = checkNotNull(outcome.rawSnapshot).roots.single()
        assertEquals("before", copied.text)
        assertEquals(SemanticUiRole.BUTTON, copied.role)
        assertEquals(setOf(SemanticUiAction.CLICK, SemanticUiAction.FOCUS), copied.actions)
        assertTrue(root.isClosed)
    }

    @Test
    fun passwordTextAndDescriptionAreNeverCopiedIntoSnapshotData() {
        val root = FakeAndroidAccessibilityNode(
            nodeText = "super-secret-value",
            nodeContentDescription = "PIN 1234",
            nodeRole = SemanticUiRole.PASSWORD_FIELD,
            nodeEditable = true,
            nodeActions = setOf(SemanticUiAction.SET_TEXT),
        )

        val outcome = AndroidSemanticTreeProjector().project(
            root = root,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        )
        val raw = checkNotNull(outcome.rawSnapshot).roots.single()

        assertNull(raw.text)
        assertNull(raw.contentDescription)
        assertEquals(SemanticUiRole.PASSWORD_FIELD, raw.role)
        assertTrue(root.isClosed)
    }

    @Test
    fun semanticFactoryRemainsAuthoritativeForNodeLimit() {
        val deep = node("deep")
        val child = node("child", children = mutableListOf(deep))
        val sibling = node("sibling")
        val root = node("root", children = mutableListOf(child, sibling))
        val limits = UiSnapshotLimits(
            maxDepth = 1,
            maxNodes = 2,
            maxTextCharsPerField = 32,
            maxTotalTextChars = 128,
            maxEstimatedBytes = 4_096,
        )

        val outcome = AndroidSemanticTreeProjector(limits).project(
            root = root,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        )
        val bounded = BoundedSemanticUiSnapshotFactory(limits)
            .build(checkNotNull(outcome.rawSnapshot))

        assertEquals(2, bounded.nodes.size)
        assertTrue(SnapshotTruncationReason.NODE_LIMIT in bounded.truncationReasons)
        listOf(root, child, sibling).forEach { assertEquals(1, it.closeInvocations) }
        // The bounded traversal never called childAt for this beyond-budget node,
        // so it never acquired ownership of a live wrapper to close.
        assertEquals(0, deep.closeInvocations)
    }

    @Test
    fun semanticFactoryRemainsAuthoritativeForDepthLimit() {
        val deep = node("deep")
        val child = node("child", children = mutableListOf(deep))
        val sibling = node("sibling")
        val root = node("root", children = mutableListOf(child, sibling))
        val limits = UiSnapshotLimits(
            maxDepth = 1,
            maxNodes = 4,
            maxTextCharsPerField = 32,
            maxTotalTextChars = 128,
            maxEstimatedBytes = 4_096,
        )

        val outcome = AndroidSemanticTreeProjector(limits).project(
            root = root,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        )
        val bounded = BoundedSemanticUiSnapshotFactory(limits)
            .build(checkNotNull(outcome.rawSnapshot))

        assertTrue(SnapshotTruncationReason.DEPTH_LIMIT in bounded.truncationReasons)
        assertFalse(SnapshotTruncationReason.NODE_LIMIT in bounded.truncationReasons)
        listOf(root, child, sibling, deep).forEach { assertEquals(1, it.closeInvocations) }
    }

    @Test
    fun textProjectionAndSemanticBoundNeverSplitASurrogatePair() {
        val limits = UiSnapshotLimits(
            maxDepth = 2,
            maxNodes = 4,
            maxTextCharsPerField = 16,
            maxTotalTextChars = 32,
            maxEstimatedBytes = 2_048,
        )
        val root = FakeAndroidAccessibilityNode(
            nodePackageName = null,
            nodeClassName = null,
            nodeText = "123456789012345\uD83D\uDE03tail",
        )

        val outcome = AndroidSemanticTreeProjector(limits).project(
            root = root,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        )
        val bounded = BoundedSemanticUiSnapshotFactory(limits)
            .build(checkNotNull(outcome.rawSnapshot))

        assertEquals("123456789012345", bounded.nodes.single().text?.value)
        assertTrue(bounded.nodes.single().text?.truncated == true)
        assertTrue(SnapshotTruncationReason.FIELD_TEXT_LIMIT in bounded.truncationReasons)
        assertFalse(bounded.nodes.single().text!!.value.last().isHighSurrogate())
    }

    @Test
    fun utf8ProjectionIsBoundedBeforeBuildingTheSemanticSnapshot() {
        val limits = UiSnapshotLimits(
            maxDepth = 2,
            maxNodes = 4,
            maxTextCharsPerField = 1_000,
            maxTotalTextChars = 1_000,
            maxEstimatedBytes = 1_024,
        )
        val root = FakeAndroidAccessibilityNode(
            nodePackageName = null,
            nodeClassName = null,
            nodeText = "\uD83D\uDE03".repeat(600),
        )

        val outcome = AndroidSemanticTreeProjector(limits).project(
            root = root,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        )
        val rawText = checkNotNull(outcome.rawSnapshot).roots.single().text.orEmpty()
        val bounded = BoundedSemanticUiSnapshotFactory(limits).build(outcome.rawSnapshot)

        assertTrue(outcome.projectionTruncated)
        assertTrue(rawText.toByteArray(Charsets.UTF_8).size <= 1_028)
        assertTrue(SnapshotTruncationReason.BYTE_LIMIT in bounded.truncationReasons)
        assertTrue(bounded.estimatedBytes <= limits.maxEstimatedBytes)
    }

    @Test
    fun targetExistsOnlyInsideCallbackAndIsAlwaysClosedAfterSuccess() {
        val expected = targetLocator()
        val target = identifiedNode("target", "target-id")
        val sibling = node("sibling")
        val root = node("root", children = mutableListOf(target, sibling))
        var targetWasOpen = false

        val result = AndroidSemanticTreeProjector().withNodeMatchingLocator(
            root = root,
            expectedLocator = expected,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        ) { live, liveLocator, raw, locators ->
            targetWasOpen = !(live as FakeAndroidAccessibilityNode).isClosed
            assertEquals("target-id", liveLocator.uniqueId)
            assertEquals(3, locators.size)
            assertEquals("target", raw.roots.single().children.first().text)
            live.perform(AndroidNodeAction.Click)
        }

        assertTrue(result.targetFound)
        assertFalse(result.readFailed)
        assertTrue(targetWasOpen)
        assertEquals(AndroidNodeAction.Click, target.performedAction)
        listOf(root, target, sibling).forEach { assertEquals(1, it.closeInvocations) }
    }

    @Test
    fun targetIsClosedWhenCallbackThrowsAndExceptionDoesNotEscape() {
        val expected = targetLocator()
        val target = identifiedNode("target", "target-id")
        val root = node("root", children = mutableListOf(target))

        val result = AndroidSemanticTreeProjector().withNodeMatchingLocator<Unit>(
            root = root,
            expectedLocator = expected,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        ) { _, _, _, _ -> error("synthetic callback failure") }

        assertTrue(result.targetFound)
        assertTrue(result.readFailed)
        assertNull(result.value)
        assertEquals(1, root.closeInvocations)
        assertEquals(1, target.closeInvocations)
    }

    @Test
    fun missingLocatorReturnsNoTargetAndStillClosesWholeTree() {
        val child = node("child")
        val root = node("root", children = mutableListOf(child))
        val expected = targetLocator().copy(uniqueId = "missing-id")

        val result = AndroidSemanticTreeProjector().withNodeMatchingLocator(
            root = root,
            expectedLocator = expected,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        ) { _, _, _, _ -> "must-not-run" }

        assertFalse(result.targetFound)
        assertFalse(result.targetAmbiguous)
        assertFalse(result.readFailed)
        assertNull(result.value)
        assertEquals(1, root.closeInvocations)
        assertEquals(1, child.closeInvocations)
    }

    @Test
    fun uniqueLocatorMovementFailsClosedBeforeAction() {
        val expected = targetLocator()
        val sibling = node("sibling")
        val target = identifiedNode("target", "target-id")
        val root = node("root", children = mutableListOf(sibling, target))

        val result = AndroidSemanticTreeProjector().withNodeMatchingLocator(
            root = root,
            expectedLocator = expected,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        ) { _, liveLocator, _, _ -> liveLocator.nodeOrdinal }

        assertFalse(result.targetFound)
        assertTrue(result.targetAmbiguous)
        assertNull(result.value)
        assertNull(target.performedAction)
        listOf(root, sibling, target).forEach { assertEquals(1, it.closeInvocations) }
    }

    @Test
    fun duplicateStrongLocatorMatchesFailClosedAndRecycleBothCandidates() {
        val expected = targetLocator()
        val first = identifiedNode("target", "target-id")
        val second = identifiedNode("target", "target-id")
        val root = node("root", children = mutableListOf(first, second))

        val result = AndroidSemanticTreeProjector().withNodeMatchingLocator(
            root = root,
            expectedLocator = expected,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        ) { _, _, _, _ -> "must-not-run" }

        assertFalse(result.targetFound)
        assertTrue(result.targetAmbiguous)
        assertNull(result.value)
        assertNull(first.performedAction)
        assertNull(second.performedAction)
        listOf(root, first, second).forEach { assertEquals(1, it.closeInvocations) }
    }

    @Test
    fun inaccessibleCountedChildKeepsReadableSnapshotAndOriginalChildPaths() {
        val readable = node("readable")
        val root = node("root", children = mutableListOf(null, readable))

        val outcome = AndroidSemanticTreeProjector().project(
            root = root,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        )

        assertFalse(outcome.readFailed)
        assertTrue(outcome.projectionTruncated)
        val snapshot = BoundedSemanticUiSnapshotFactory().build(checkNotNull(outcome.rawSnapshot))
        assertEquals(listOf("root", "readable"), snapshot.nodes.map { it.text?.value })
        assertEquals(listOf(1), outcome.locators[1].structuralPath)
        assertFalse(snapshot.isComplete)
        assertTrue(
            SnapshotTruncationReason.PLATFORM_NODE_UNAVAILABLE in snapshot.truncationReasons,
        )
        assertEquals(1, root.closeInvocations)
        assertEquals(1, readable.closeInvocations)
    }

    @Test
    fun inaccessibleViewIdTargetDoesNotRetargetIdenticalSibling() {
        val originalSibling = node("sibling")
        val originalTarget = viewIdentifiedNode("target", "test:id/reused")
        val expected = AndroidSemanticTreeProjector().project(
            root = node("root", children = mutableListOf(originalSibling, originalTarget)),
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        ).locators[2]
        val wrongSibling = viewIdentifiedNode("target", "test:id/reused")
        val liveRoot = node("root", children = mutableListOf(wrongSibling, null))

        val result = AndroidSemanticTreeProjector().withNodeMatchingLocator(
            root = liveRoot,
            expectedLocator = expected,
            correlation = testCorrelation(snapshot = 2),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 2,
        ) { _, _, _, _ -> "must-not-run" }

        assertFalse(result.targetFound)
        assertFalse(result.targetAmbiguous)
        assertFalse(result.readFailed)
        assertTrue(result.projectionTruncated)
        assertNull(result.value)
        assertNull(wrongSibling.performedAction)
        assertEquals(1, liveRoot.closeInvocations)
        assertEquals(1, wrongSibling.closeInvocations)
    }

    @Test
    fun thrownChildReadSkipsOnlyThatBranchAndClosesAcquiredNodes() {
        val queued = node("queued")
        val unacquired = node("unacquired")
        val root = FakeAndroidAccessibilityNode(
            nodeText = "root",
            children = mutableListOf(queued, unacquired),
            failOnChildIndex = 1,
        )

        val outcome = AndroidSemanticTreeProjector().project(
            root = root,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        )

        assertFalse(outcome.readFailed)
        assertTrue(outcome.projectionTruncated)
        val snapshot = BoundedSemanticUiSnapshotFactory().build(checkNotNull(outcome.rawSnapshot))
        assertEquals(listOf("root", "queued"), snapshot.nodes.map { it.text?.value })
        assertEquals(listOf(0), outcome.locators[1].structuralPath)
        assertTrue(
            SnapshotTruncationReason.PLATFORM_NODE_UNAVAILABLE in snapshot.truncationReasons,
        )
        assertEquals(1, root.closeInvocations)
        assertEquals(1, queued.closeInvocations)
        assertEquals(0, unacquired.closeInvocations)
    }

    @Test
    fun unreadableNonRootNodeSkipsOnlyThatBranchAndKeepsSiblings() {
        val unreadable = FakeAndroidAccessibilityNode(failOnRead = true)
        val readable = node("readable")
        val root = node("root", children = mutableListOf(unreadable, readable))

        val outcome = AndroidSemanticTreeProjector().project(
            root = root,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        )

        assertFalse(outcome.readFailed)
        assertTrue(outcome.projectionTruncated)
        val snapshot = BoundedSemanticUiSnapshotFactory().build(checkNotNull(outcome.rawSnapshot))
        assertEquals(listOf("root", "readable"), snapshot.nodes.map { it.text?.value })
        assertEquals(listOf(1), outcome.locators[1].structuralPath)
        assertTrue(
            SnapshotTruncationReason.PLATFORM_NODE_UNAVAILABLE in snapshot.truncationReasons,
        )
        listOf(root, unreadable, readable).forEach { assertEquals(1, it.closeInvocations) }
    }

    @Test
    fun failedNodePropertyReadInvalidatesProjectionAndClosesRoot() {
        val root = FakeAndroidAccessibilityNode(failOnRead = true)

        val outcome = AndroidSemanticTreeProjector().project(
            root = root,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        )

        assertTrue(outcome.readFailed)
        assertFalse(outcome.projectionTruncated)
        assertNull(outcome.rawSnapshot)
        assertEquals(1, root.closeInvocations)
    }

    private fun node(
        text: String,
        children: MutableList<FakeAndroidAccessibilityNode?> = mutableListOf(),
    ): FakeAndroidAccessibilityNode = FakeAndroidAccessibilityNode(
        nodeText = text,
        children = children,
    )

    private fun identifiedNode(text: String, uniqueId: String) = FakeAndroidAccessibilityNode(
        nodeUniqueId = uniqueId,
        nodeText = text,
    )

    private fun viewIdentifiedNode(text: String, viewId: String) = FakeAndroidAccessibilityNode(
        nodeViewIdResourceName = viewId,
        nodeText = text,
    )

    private fun targetLocator(): AndroidNodeLocator {
        val target = identifiedNode("target", "target-id")
        val root = node("root", children = mutableListOf(target))
        return AndroidSemanticTreeProjector().project(
            root = root,
            correlation = testCorrelation(),
            displayId = 0,
            displayBounds = TEST_DISPLAY_BOUNDS,
            capturedAtElapsedMillis = 1,
        ).locators[1]
    }
}
