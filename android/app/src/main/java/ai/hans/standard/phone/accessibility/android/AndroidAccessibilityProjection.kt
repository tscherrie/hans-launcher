package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.RawSemanticUiNode
import ai.hans.standard.phone.accessibility.RawSemanticUiSnapshot
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.SemanticUiAction
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.SnapshotTruncationReason
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiScrollDirection
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import ai.hans.standard.phone.accessibility.UiSnapshotLimits
import java.util.ArrayDeque

/** A short-lived wrapper around one live AccessibilityNodeInfo. */
internal interface AndroidAccessibilityNode : AutoCloseable {
    val windowId: Int
    val uniqueId: String?
    val viewIdResourceName: String?
    val packageName: CharSequence?
    val className: CharSequence?
    val text: CharSequence?
    val contentDescription: CharSequence?
    val role: SemanticUiRole
    val bounds: UiBounds
    val visible: Boolean
    val enabled: Boolean
    val clickable: Boolean
    val editable: Boolean
    val scrollable: Boolean
    val actions: Set<SemanticUiAction>
    val childCount: Int

    fun childAt(index: Int): AndroidAccessibilityNode?

    fun perform(action: AndroidNodeAction): Boolean
}

internal sealed interface AndroidNodeAction {
    data object Click : AndroidNodeAction

    data class SetText(val value: String) : AndroidNodeAction

    data class Scroll(val direction: UiScrollDirection) : AndroidNodeAction
}

internal enum class AndroidLocatorStrength {
    UNIQUE_ID,
    VIEW_ID,
    STRUCTURAL_PATH,
}

/**
 * App-private locator copied from a snapshot. It never owns a framework node
 * and intentionally excludes external text. The ordinal is only an index into
 * the immutable snapshot; live actions resolve [uniqueId], [viewIdResourceName]
 * or [structuralPath] and then revalidate the full semantic fingerprint.
 */
internal data class AndroidNodeLocator(
    val nodeOrdinal: Int,
    val displayId: Int,
    val windowId: Int,
    val uniqueId: String?,
    val viewIdResourceName: String?,
    val structuralPath: List<Int>,
    val fingerprint: AndroidNodeFingerprint,
) {
    init {
        require(nodeOrdinal >= 0)
        require(displayId >= 0)
        // Detached AccessibilityNodeInfo test doubles use -1. The real service
        // rejects an undefined root window before publishing a snapshot.
        require(windowId >= -1)
        require(structuralPath.all { it >= 0 })
    }

    val strength: AndroidLocatorStrength
        get() = when {
            uniqueId != null -> AndroidLocatorStrength.UNIQUE_ID
            viewIdResourceName != null -> AndroidLocatorStrength.VIEW_ID
            else -> AndroidLocatorStrength.STRUCTURAL_PATH
        }

    fun isSameActionTarget(candidate: AndroidNodeLocator): Boolean =
        structuralPath == candidate.structuralPath &&
            isSameIdentity(candidate) &&
            fingerprint == candidate.fingerprint

    fun conflictsWithMovedUniqueIdentity(candidate: AndroidNodeLocator): Boolean =
        uniqueId != null &&
            uniqueId == candidate.uniqueId &&
            displayId == candidate.displayId &&
            windowId == candidate.windowId &&
            structuralPath != candidate.structuralPath

    fun isSameIdentity(candidate: AndroidNodeLocator): Boolean {
        if (displayId != candidate.displayId || windowId != candidate.windowId) return false
        val locatorMatches = when {
            uniqueId != null -> uniqueId == candidate.uniqueId
            // A resource ID is commonly reused by list rows and other repeated
            // layouts. Keep it strong only while the structural position also
            // matches; otherwise an inaccessible row could make a sibling look
            // like the original action target.
            viewIdResourceName != null ->
                viewIdResourceName == candidate.viewIdResourceName &&
                    structuralPath == candidate.structuralPath
            else -> structuralPath == candidate.structuralPath
        }
        return locatorMatches &&
            fingerprint.packageName == candidate.fingerprint.packageName &&
            fingerprint.className == candidate.fingerprint.className &&
            fingerprint.role == candidate.fingerprint.role
    }
}

internal data class AndroidNodeFingerprint(
    val packageName: String?,
    val className: String?,
    val role: SemanticUiRole,
    val bounds: UiBounds,
    val visible: Boolean,
    val enabled: Boolean,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val actions: Set<SemanticUiAction>,
)

/** Stable preconditions for acting on a node from a previously published snapshot. */
internal object AndroidNodeActionTargetValidator {
    fun matches(
        expectedSnapshot: SemanticUiSnapshot,
        expectedLocator: AndroidNodeLocator,
        liveSnapshot: SemanticUiSnapshot,
        liveLocator: AndroidNodeLocator,
    ): Boolean {
        if (expectedSnapshot.displayBounds != liveSnapshot.displayBounds) return false
        if (expectedSnapshot.correlation.sessionId != liveSnapshot.correlation.sessionId) {
            return false
        }
        if (expectedSnapshot.correlation.windowId != liveSnapshot.correlation.windowId) return false
        if (expectedSnapshot.correlation.windowId.value != expectedLocator.windowId) return false
        if (liveSnapshot.correlation.windowId.value != liveLocator.windowId) return false
        if (!expectedLocator.isSameActionTarget(liveLocator)) return false

        val expectedRootHandle = expectedSnapshot.roots.singleOrNull() ?: return false
        val liveRootHandle = liveSnapshot.roots.singleOrNull() ?: return false
        val expectedRoot = expectedSnapshot.resolve(expectedRootHandle) ?: return false
        val liveRoot = liveSnapshot.resolve(liveRootHandle) ?: return false
        if (
            expectedRoot.packageName != liveRoot.packageName ||
            expectedRoot.className != liveRoot.className
        ) {
            return false
        }

        val expectedTarget = expectedSnapshot.nodes.firstOrNull {
            it.handle.nodeOrdinal == expectedLocator.nodeOrdinal
        } ?: return false
        val liveTarget = liveSnapshot.nodes.firstOrNull {
            it.handle.nodeOrdinal == liveLocator.nodeOrdinal
        } ?: return false
        return expectedTarget.text == liveTarget.text &&
            expectedTarget.contentDescription == liveTarget.contentDescription
    }
}

internal data class AndroidProjectionOutcome(
    val rawSnapshot: RawSemanticUiSnapshot?,
    val locators: List<AndroidNodeLocator>,
    val projectionTruncated: Boolean,
    val readFailed: Boolean,
)

internal data class AndroidTargetProjectionResult<T>(
    val value: T?,
    val targetFound: Boolean,
    val targetAmbiguous: Boolean,
    val projectionTruncated: Boolean,
    val readFailed: Boolean,
)

/**
 * Copies a live node hierarchy into bounded, framework-free DTOs. Every node
 * obtained during traversal is closed before this class returns. When an
 * action target is requested, it remains live only for the callback and is
 * then closed in a finally block. Duplicate locator matches fail closed.
 */
internal class AndroidSemanticTreeProjector(
    private val semanticLimits: UiSnapshotLimits = UiSnapshotLimits(),
) {
    private val traversalMaxNodes = (semanticLimits.maxNodes + 1).coerceAtMost(10_001)
    private val traversalMaxDepth = (semanticLimits.maxDepth + 1).coerceAtMost(129)

    fun project(
        root: AndroidAccessibilityNode,
        correlation: UiSnapshotCorrelation,
        displayId: Int,
        displayBounds: UiBounds,
        capturedAtElapsedMillis: Long,
    ): AndroidProjectionOutcome {
        val traversal = traverse(root, displayId, retainedLocator = null)
        return AndroidProjectionOutcome(
            rawSnapshot = traversal.toRawSnapshot(
                correlation,
                displayBounds,
                capturedAtElapsedMillis,
            ),
            locators = traversal.nodes.map(FlatProjectedNode::locator),
            projectionTruncated = traversal.truncated,
            readFailed = traversal.readFailed,
        )
    }

    fun <T> withNodeMatchingLocator(
        root: AndroidAccessibilityNode,
        expectedLocator: AndroidNodeLocator,
        correlation: UiSnapshotCorrelation,
        displayId: Int,
        displayBounds: UiBounds,
        capturedAtElapsedMillis: Long,
        action: (
            AndroidAccessibilityNode,
            AndroidNodeLocator,
            RawSemanticUiSnapshot,
            List<AndroidNodeLocator>,
        ) -> T,
    ): AndroidTargetProjectionResult<T> {
        val traversal = traverse(root, displayId, retainedLocator = expectedLocator)
        val target = traversal.retainedNode
        return try {
            val raw = traversal.toRawSnapshot(
                correlation,
                displayBounds,
                capturedAtElapsedMillis,
            )
            if (
                target == null ||
                raw == null ||
                traversal.readFailed ||
                traversal.targetAmbiguous
            ) {
                AndroidTargetProjectionResult(
                    value = null,
                    targetFound = target != null,
                    targetAmbiguous = traversal.targetAmbiguous,
                    projectionTruncated = traversal.truncated,
                    readFailed = traversal.readFailed,
                )
            } else {
                val liveLocator = checkNotNull(traversal.retainedLocator)
                AndroidTargetProjectionResult(
                    value = action(
                        target,
                        liveLocator,
                        raw,
                        traversal.nodes.map(FlatProjectedNode::locator),
                    ),
                    targetFound = true,
                    targetAmbiguous = false,
                    projectionTruncated = traversal.truncated,
                    readFailed = false,
                )
            }
        } catch (_: Exception) {
            AndroidTargetProjectionResult(
                value = null,
                targetFound = target != null,
                targetAmbiguous = traversal.targetAmbiguous,
                projectionTruncated = traversal.truncated,
                readFailed = true,
            )
        } finally {
            target.closeQuietly()
        }
    }

    private fun traverse(
        root: AndroidAccessibilityNode,
        displayId: Int,
        retainedLocator: AndroidNodeLocator?,
    ): Traversal {
        require(displayId >= 0)
        val textBudget = ProjectionTextBudget(semanticLimits)
        val pending = ArrayDeque<PendingNode>()
        val projected = mutableListOf<FlatProjectedNode>()
        pending.addLast(PendingNode(root, depth = 0, parentOrdinal = null, path = emptyList()))
        var retainedNode: AndroidAccessibilityNode? = null
        var matchedLocator: AndroidNodeLocator? = null
        var targetAmbiguous = false
        var truncated = false
        var sourceIncomplete = false
        var readFailed = false

        try {
            while (pending.isNotEmpty()) {
                if (projected.size >= traversalMaxNodes) {
                    truncated = true
                    break
                }
                val pendingNode = pending.removeFirst()
                val node = pendingNode.node
                var retainCurrent = false
                var projectedCurrent = false
                try {
                    if (pendingNode.depth > traversalMaxDepth) {
                        truncated = true
                        continue
                    }
                    val ordinal = projected.size
                    val packageName = textBudget.copy(node.packageName)
                    val className = textBudget.copy(node.className)
                    val role = node.role
                    val bounds = node.bounds
                    val visible = node.visible
                    val enabled = node.enabled
                    val clickable = node.clickable
                    val editable = node.editable
                    val scrollable = node.scrollable
                    val actions = node.actions.toSet()
                    val locator = AndroidNodeLocator(
                        nodeOrdinal = ordinal,
                        displayId = displayId,
                        windowId = node.windowId,
                        uniqueId = node.uniqueId?.take(MAX_LOCATOR_ID_CHARACTERS),
                        viewIdResourceName = node.viewIdResourceName
                            ?.take(MAX_LOCATOR_ID_CHARACTERS),
                        structuralPath = pendingNode.path.toList(),
                        fingerprint = AndroidNodeFingerprint(
                            packageName = packageName,
                            className = className,
                            role = role,
                            bounds = bounds,
                            visible = visible,
                            enabled = enabled,
                            clickable = clickable,
                            editable = editable,
                            scrollable = scrollable,
                            actions = actions,
                        ),
                    )
                    val flat = FlatProjectedNode(
                        packageName = packageName,
                        className = className,
                        // Credential contents never enter the semantic snapshot,
                        // receipts, logs or Codex context.
                        text = if (role == SemanticUiRole.PASSWORD_FIELD) {
                            null
                        } else {
                            textBudget.copy(node.text)
                        },
                        contentDescription = if (role == SemanticUiRole.PASSWORD_FIELD) {
                            null
                        } else {
                            textBudget.copy(node.contentDescription)
                        },
                        role = role,
                        bounds = bounds,
                        visible = visible,
                        enabled = enabled,
                        clickable = clickable,
                        editable = editable,
                        scrollable = scrollable,
                        actions = actions,
                        locator = locator,
                    )
                    projected += flat
                    projectedCurrent = true
                    pendingNode.parentOrdinal?.let { parent ->
                        projected[parent].childOrdinals += ordinal
                    }
                    if (retainedLocator?.conflictsWithMovedUniqueIdentity(locator) == true) {
                        targetAmbiguous = true
                    } else if (retainedLocator?.isSameActionTarget(locator) == true) {
                        if (retainedNode == null) {
                            retainedNode = node
                            matchedLocator = locator
                            retainCurrent = true
                        } else {
                            targetAmbiguous = true
                        }
                    }

                    val childCount = node.childCount.coerceAtLeast(0)
                    if (childCount == 0) continue
                    if (pendingNode.depth >= traversalMaxDepth) {
                        truncated = true
                        continue
                    }
                    val remaining =
                        (traversalMaxNodes - projected.size - pending.size).coerceAtLeast(0)
                    val childrenToRead = minOf(childCount, remaining)
                    repeat(childrenToRead) { childIndex ->
                        val child = try {
                            node.childAt(childIndex)
                        } catch (_: Exception) {
                            sourceIncomplete = true
                            null
                        }
                        if (child == null) {
                            // AccessibilityNodeInfo documents child lookup as
                            // nullable. Complex or changing Android views can
                            // expose a counted child that is not currently
                            // accessible; retain the readable snapshot instead
                            // of conflating that with an actual read failure.
                            sourceIncomplete = true
                        } else {
                            pending.addLast(
                                PendingNode(
                                    node = child,
                                    depth = pendingNode.depth + 1,
                                    parentOrdinal = ordinal,
                                    path = pendingNode.path + childIndex,
                                ),
                            )
                        }
                    }
                    if (childCount > childrenToRead) truncated = true
                } catch (_: Exception) {
                    if (pendingNode.parentOrdinal == null && !projectedCurrent) {
                        readFailed = true
                        break
                    }
                    sourceIncomplete = true
                } finally {
                    if (!retainCurrent) node.closeQuietly()
                }
                if (readFailed) break
            }
        } finally {
            while (pending.isNotEmpty()) pending.removeFirst().node.closeQuietly()
            if (targetAmbiguous) {
                retainedNode.closeQuietly()
                retainedNode = null
                matchedLocator = null
            }
        }
        return Traversal(
            nodes = projected,
            retainedNode = retainedNode,
            retainedLocator = matchedLocator,
            targetAmbiguous = targetAmbiguous,
            truncated = truncated || sourceIncomplete || textBudget.truncated,
            sourceIncomplete = sourceIncomplete,
            readFailed = readFailed,
        )
    }

    private data class PendingNode(
        val node: AndroidAccessibilityNode,
        val depth: Int,
        val parentOrdinal: Int?,
        val path: List<Int>,
    )

    private data class FlatProjectedNode(
        val packageName: String?,
        val className: String?,
        val text: String?,
        val contentDescription: String?,
        val role: SemanticUiRole,
        val bounds: UiBounds,
        val visible: Boolean,
        val enabled: Boolean,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val actions: Set<SemanticUiAction>,
        val locator: AndroidNodeLocator,
        val childOrdinals: MutableList<Int> = mutableListOf(),
    )

    private data class Traversal(
        val nodes: List<FlatProjectedNode>,
        val retainedNode: AndroidAccessibilityNode?,
        val retainedLocator: AndroidNodeLocator?,
        val targetAmbiguous: Boolean,
        val truncated: Boolean,
        val sourceIncomplete: Boolean,
        val readFailed: Boolean,
    ) {
        fun toRawSnapshot(
            correlation: UiSnapshotCorrelation,
            displayBounds: UiBounds,
            capturedAtElapsedMillis: Long,
        ): RawSemanticUiSnapshot? {
            if (readFailed || nodes.isEmpty()) return null
            val built = arrayOfNulls<RawSemanticUiNode>(nodes.size)
            for (ordinal in nodes.indices.reversed()) {
                val node = nodes[ordinal]
                built[ordinal] = RawSemanticUiNode(
                    packageName = node.packageName,
                    className = node.className,
                    text = node.text,
                    contentDescription = node.contentDescription,
                    role = node.role,
                    bounds = node.bounds,
                    visible = node.visible,
                    enabled = node.enabled,
                    clickable = node.clickable,
                    editable = node.editable,
                    scrollable = node.scrollable,
                    actions = node.actions,
                    children = node.childOrdinals.map { child -> checkNotNull(built[child]) },
                )
            }
            return RawSemanticUiSnapshot(
                correlation = correlation,
                displayBounds = displayBounds,
                capturedAtElapsedMillis = capturedAtElapsedMillis,
                roots = listOf(checkNotNull(built[0])),
                sourceTruncationReasons = if (sourceIncomplete) {
                    setOf(SnapshotTruncationReason.PLATFORM_NODE_UNAVAILABLE)
                } else {
                    emptySet()
                },
            )
        }
    }

    private class ProjectionTextBudget(limits: UiSnapshotLimits) {
        private val maxFieldCharacters = (limits.maxTextCharsPerField + 2)
            .coerceAtMost(32_770)
        private val maxTotalCharacters = (limits.maxTotalTextChars + 2)
            .coerceAtMost(1_000_002)
        private val maxUtf8Bytes = (limits.maxEstimatedBytes + 4)
            .coerceAtMost(8 * 1_024 * 1_024 + 4)
        private var totalCharacters = 0
        private var utf8Bytes = 0
        var truncated: Boolean = false
            private set

        fun copy(value: CharSequence?): String? {
            if (value == null) return null
            if (value.isEmpty()) return ""
            val output = StringBuilder(minOf(value.length, maxFieldCharacters))
            var index = 0
            while (index < value.length) {
                val codePoint = Character.codePointAt(value, index)
                val characterCount = Character.charCount(codePoint)
                if (output.length + characterCount > maxFieldCharacters) break
                if (totalCharacters + characterCount > maxTotalCharacters) break
                val bytes = utf8Length(codePoint)
                if (utf8Bytes + bytes > maxUtf8Bytes) break
                output.appendCodePoint(codePoint)
                index += characterCount
                totalCharacters += characterCount
                utf8Bytes += bytes
            }
            if (index < value.length) truncated = true
            return output.toString().takeIf { it.isNotEmpty() || value.isEmpty() }
        }

        private fun utf8Length(codePoint: Int): Int = when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }
    }

    private companion object {
        const val MAX_LOCATOR_ID_CHARACTERS = 1_024

        fun AndroidAccessibilityNode?.closeQuietly() {
            if (this == null) return
            runCatching { close() }
        }
    }
}
