package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.AccessibilityGlobalAction
import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityWindowId
import ai.hans.standard.phone.accessibility.BoundedSemanticUiSnapshotFactory
import ai.hans.standard.phone.accessibility.RawSemanticUiNode
import ai.hans.standard.phone.accessibility.RawSemanticUiSnapshot
import ai.hans.standard.phone.accessibility.SemanticNodeHandle
import ai.hans.standard.phone.accessibility.SemanticUiAction
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiCoordinateGesture
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation

internal val TEST_DISPLAY_BOUNDS = UiBounds(0, 0, 1_080, 2_400)

internal fun testCorrelation(
    snapshot: Long = 1,
    window: Int = 7,
    session: String = "accessibility-session-0001",
): UiSnapshotCorrelation = UiSnapshotCorrelation(
    sessionId = AccessibilitySessionId(session),
    windowId = AccessibilityWindowId(window),
    snapshotId = AccessibilitySnapshotId(snapshot),
)

internal fun rawNode(
    text: String? = null,
    contentDescription: String? = null,
    packageName: String? = "test.package",
    className: String? = "android.view.View",
    role: SemanticUiRole = SemanticUiRole.TEXT,
    bounds: UiBounds = UiBounds(0, 0, 100, 100),
    visible: Boolean = true,
    enabled: Boolean = true,
    clickable: Boolean = false,
    editable: Boolean = false,
    scrollable: Boolean = false,
    actions: Set<SemanticUiAction> = emptySet(),
    children: List<RawSemanticUiNode> = emptyList(),
): RawSemanticUiNode = RawSemanticUiNode(
    packageName = packageName,
    className = className,
    text = text,
    contentDescription = contentDescription,
    role = role,
    bounds = bounds,
    visible = visible,
    enabled = enabled,
    clickable = clickable,
    editable = editable,
    scrollable = scrollable,
    actions = actions,
    children = children,
)

internal fun semanticSnapshot(
    correlation: UiSnapshotCorrelation = testCorrelation(),
    roots: List<RawSemanticUiNode> = listOf(rawNode(text = "root")),
    displayBounds: UiBounds = TEST_DISPLAY_BOUNDS,
): SemanticUiSnapshot = BoundedSemanticUiSnapshotFactory().build(
    RawSemanticUiSnapshot(
        correlation = correlation,
        displayBounds = displayBounds,
        capturedAtElapsedMillis = correlation.snapshotId.value,
        roots = roots,
    ),
)

internal class FakeAndroidAccessibilityNode(
    private val nodeWindowId: Int = 7,
    private val nodeUniqueId: String? = null,
    private val nodeViewIdResourceName: String? = null,
    private val nodePackageName: CharSequence? = "test.package",
    private val nodeClassName: CharSequence? = "android.view.View",
    private val nodeText: CharSequence? = null,
    private val nodeContentDescription: CharSequence? = null,
    private val nodeRole: SemanticUiRole = SemanticUiRole.TEXT,
    private val nodeBounds: UiBounds = UiBounds(0, 0, 100, 100),
    private val nodeVisible: Boolean = true,
    private val nodeEnabled: Boolean = true,
    private val nodeClickable: Boolean = false,
    private val nodeEditable: Boolean = false,
    private val nodeScrollable: Boolean = false,
    private val nodeActions: Set<SemanticUiAction> = emptySet(),
    val children: MutableList<FakeAndroidAccessibilityNode?> = mutableListOf(),
    private val failOnRead: Boolean = false,
    private val failOnChildIndex: Int? = null,
    var performResult: Boolean = true,
) : AndroidAccessibilityNode {
    var closeInvocations: Int = 0
        private set
    var performedAction: AndroidNodeAction? = null
        private set
    var isClosed: Boolean = false
        private set

    override val windowId: Int
        get() = read(nodeWindowId)
    override val uniqueId: String?
        get() = read(nodeUniqueId)
    override val viewIdResourceName: String?
        get() = read(nodeViewIdResourceName)
    override val packageName: CharSequence?
        get() = read(nodePackageName)
    override val className: CharSequence?
        get() = read(nodeClassName)
    override val text: CharSequence?
        get() = read(nodeText)
    override val contentDescription: CharSequence?
        get() = read(nodeContentDescription)
    override val role: SemanticUiRole
        get() = read(nodeRole)
    override val bounds: UiBounds
        get() = read(nodeBounds)
    override val visible: Boolean
        get() = read(nodeVisible)
    override val enabled: Boolean
        get() = read(nodeEnabled)
    override val clickable: Boolean
        get() = read(nodeClickable)
    override val editable: Boolean
        get() = read(nodeEditable)
    override val scrollable: Boolean
        get() = read(nodeScrollable)
    override val actions: Set<SemanticUiAction>
        get() = read(nodeActions)
    override val childCount: Int
        get() = read(children.size)

    override fun childAt(index: Int): AndroidAccessibilityNode? {
        ensureOpen()
        if (failOnChildIndex == index) error("synthetic child read failure")
        return children[index]
    }

    override fun perform(action: AndroidNodeAction): Boolean {
        ensureOpen()
        performedAction = action
        return performResult
    }

    override fun close() {
        closeInvocations += 1
        check(!isClosed) { "fake node closed twice" }
        isClosed = true
    }

    private fun <T> read(value: T): T {
        ensureOpen()
        if (failOnRead) error("synthetic node read failure")
        return value
    }

    private fun ensureOpen() {
        check(!isClosed) { "fake node already closed" }
    }
}

internal class FakeRootFreeAccessibilityHost : RootFreeAccessibilityHost {
    var nodeStatus: AndroidHostActionStatus = AndroidHostActionStatus.ACCEPTED
    var globalStatus: AndroidHostActionStatus = AndroidHostActionStatus.ACCEPTED
    var gestureStatus: AndroidHostActionStatus = AndroidHostActionStatus.ACCEPTED
    var gesturesSupported: Boolean = true
    var snapshotAfter: SemanticUiSnapshot? = null
    var observationBaseline: AccessibilitySnapshotId? = AccessibilitySnapshotId(1)
    var onNodeAction: (() -> Unit)? = null
    var nodeLocator: AndroidNodeLocator? = null
    val nodeLocators = mutableMapOf<Int, AndroidNodeLocator>()
    var targetResolution: AndroidTargetResolution = AndroidTargetResolution.Missing
    val nodeCalls = mutableListOf<Pair<SemanticNodeHandle, AndroidNodeAction>>()
    val globalCalls = mutableListOf<AccessibilityGlobalAction>()
    val gestureCalls = mutableListOf<Pair<UiSnapshotCorrelation, UiCoordinateGesture>>()
    val snapshotWaitCalls = mutableListOf<UiSnapshotCorrelation>()
    val resolveCalls = mutableListOf<Pair<AndroidNodeLocator, UiSnapshotCorrelation>>()

    override fun performNodeAction(
        handle: SemanticNodeHandle,
        action: AndroidNodeAction,
    ): AndroidHostNodeActionResult {
        nodeCalls += handle to action
        onNodeAction?.invoke()
        return AndroidHostNodeActionResult(
            status = nodeStatus,
            locator = nodeLocators[handle.nodeOrdinal] ?: nodeLocator,
        )
    }

    override fun performGlobalAction(action: AccessibilityGlobalAction): AndroidHostActionStatus {
        globalCalls += action
        return globalStatus
    }

    override fun supportsCoordinateGestures(): Boolean = gesturesSupported

    override fun performCoordinateGesture(
        correlation: UiSnapshotCorrelation,
        gesture: UiCoordinateGesture,
    ): AndroidHostActionStatus {
        gestureCalls += correlation to gesture
        return gestureStatus
    }

    override fun actionObservationBaseline(): AccessibilitySnapshotId? = observationBaseline

    override fun snapshotAfterAction(
        before: UiSnapshotCorrelation,
        baseline: AccessibilitySnapshotId,
    ): SemanticUiSnapshot? {
        snapshotWaitCalls += before
        return snapshotAfter
    }

    override fun resolveTargetAfterAction(
        beforeLocator: AndroidNodeLocator,
        after: UiSnapshotCorrelation,
    ): AndroidTargetResolution {
        resolveCalls += beforeLocator to after
        return targetResolution
    }
}
