package ai.hans.standard.phone.accessibility

import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/** Converts adapter DTOs into an immutable, bounded snapshot without recursion. */
class BoundedSemanticUiSnapshotFactory(
    private val limits: UiSnapshotLimits = UiSnapshotLimits(),
) {
    fun build(raw: RawSemanticUiSnapshot): SemanticUiSnapshot {
        val truncation = linkedSetOf<SnapshotTruncationReason>().apply {
            addAll(raw.sourceTruncationReasons)
        }
        val budget = SnapshotBudget(limits, truncation)
        val pending = ArrayDeque<PendingNode>()
        val roots = mutableListOf<Int>()
        val mutableNodes = mutableListOf<MutableSemanticNode>()
        val seen = Collections.newSetFromMap(
            IdentityHashMap<RawSemanticUiNode, Boolean>(),
        )

        val initialRoots = raw.roots
        val rootCapacity = limits.maxNodes.coerceAtMost(initialRoots.size)
        for (index in 0 until rootCapacity) {
            pending.addLast(PendingNode(initialRoots[index], depth = 0, parentOrdinal = null))
        }
        if (initialRoots.size > rootCapacity) {
            truncation += SnapshotTruncationReason.NODE_LIMIT
        }

        while (pending.isNotEmpty()) {
            if (mutableNodes.size >= limits.maxNodes) {
                truncation += SnapshotTruncationReason.NODE_LIMIT
                break
            }
            val item = pending.removeFirst()
            if (item.depth > limits.maxDepth) {
                truncation += SnapshotTruncationReason.DEPTH_LIMIT
                continue
            }
            if (!seen.add(item.raw)) {
                truncation += SnapshotTruncationReason.CYCLIC_OR_SHARED_NODE
                continue
            }
            val fixedBytes = NODE_BASE_BYTES +
                item.raw.actions.size.coerceAtMost(SemanticUiAction.entries.size) * ACTION_BYTES +
                if (item.parentOrdinal == null) 0 else EDGE_BYTES
            if (!budget.reserveFixedBytes(fixedBytes)) {
                truncation += SnapshotTruncationReason.BYTE_LIMIT
                break
            }

            val ordinal = mutableNodes.size
            val node = MutableSemanticNode(
                ordinal = ordinal,
                packageName = budget.copyText(item.raw.packageName),
                className = budget.copyText(item.raw.className),
                text = budget.copyText(item.raw.text),
                contentDescription = budget.copyText(item.raw.contentDescription),
                role = item.raw.role,
                bounds = item.raw.bounds,
                visible = item.raw.visible,
                enabled = item.raw.enabled,
                clickable = item.raw.clickable,
                editable = item.raw.editable,
                scrollable = item.raw.scrollable,
                actions = item.raw.actions.toSet(),
            )
            mutableNodes += node
            if (item.parentOrdinal == null) {
                roots += ordinal
            } else {
                mutableNodes[item.parentOrdinal].childOrdinals += ordinal
            }

            val children = item.raw.children
            if (children.isEmpty()) continue
            if (item.depth >= limits.maxDepth) {
                truncation += SnapshotTruncationReason.DEPTH_LIMIT
                continue
            }
            val remainingNodeSlots =
                (limits.maxNodes - mutableNodes.size - pending.size).coerceAtLeast(0)
            val childCount = minOf(children.size, remainingNodeSlots)
            for (childIndex in 0 until childCount) {
                pending.addLast(
                    PendingNode(
                        raw = children[childIndex],
                        depth = item.depth + 1,
                        parentOrdinal = ordinal,
                    ),
                )
            }
            if (children.size > childCount) {
                truncation += SnapshotTruncationReason.NODE_LIMIT
            }
        }

        val correlation = raw.correlation
        val semanticNodes = mutableNodes.map { mutable ->
            SemanticUiNode(
                handle = SemanticNodeHandle(correlation, mutable.ordinal),
                packageName = mutable.packageName,
                className = mutable.className,
                text = mutable.text,
                contentDescription = mutable.contentDescription,
                role = mutable.role,
                bounds = mutable.bounds,
                visible = mutable.visible,
                enabled = mutable.enabled,
                clickable = mutable.clickable,
                editable = mutable.editable,
                scrollable = mutable.scrollable,
                actions = mutable.actions,
                children = mutable.childOrdinals.map { SemanticNodeHandle(correlation, it) },
            )
        }
        return SemanticUiSnapshot(
            correlation = correlation,
            displayBounds = raw.displayBounds,
            capturedAtElapsedMillis = raw.capturedAtElapsedMillis,
            nodes = semanticNodes,
            roots = roots.map { SemanticNodeHandle(correlation, it) },
            truncationReasons = truncation,
            estimatedBytes = budget.estimatedBytes,
            totalTextCharacters = budget.totalTextCharacters,
        )
    }

    private data class PendingNode(
        val raw: RawSemanticUiNode,
        val depth: Int,
        val parentOrdinal: Int?,
    )

    private data class MutableSemanticNode(
        val ordinal: Int,
        val packageName: UntrustedUiText?,
        val className: UntrustedUiText?,
        val text: UntrustedUiText?,
        val contentDescription: UntrustedUiText?,
        val role: SemanticUiRole,
        val bounds: UiBounds,
        val visible: Boolean,
        val enabled: Boolean,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val actions: Set<SemanticUiAction>,
        val childOrdinals: MutableList<Int> = mutableListOf(),
    )

    private class SnapshotBudget(
        private val limits: UiSnapshotLimits,
        private val truncation: MutableSet<SnapshotTruncationReason>,
    ) {
        var estimatedBytes: Int = SNAPSHOT_BASE_BYTES
            private set
        var totalTextCharacters: Int = 0
            private set

        fun reserveFixedBytes(bytes: Int): Boolean {
            if (estimatedBytes + bytes > limits.maxEstimatedBytes) return false
            estimatedBytes += bytes
            return true
        }

        fun copyText(input: String?): UntrustedUiText? {
            if (input == null) return null
            if (input.isEmpty()) return UntrustedUiText.bounded("", truncated = false)
            val builder = StringBuilder(minOf(input.length, limits.maxTextCharsPerField))
            var sourceIndex = 0
            while (sourceIndex < input.length) {
                val codePoint = input.codePointAt(sourceIndex)
                val characterCount = Character.charCount(codePoint)
                if (builder.length + characterCount > limits.maxTextCharsPerField) {
                    truncation += SnapshotTruncationReason.FIELD_TEXT_LIMIT
                    break
                }
                if (totalTextCharacters + characterCount > limits.maxTotalTextChars) {
                    truncation += SnapshotTruncationReason.TOTAL_TEXT_LIMIT
                    break
                }
                val encodedBytes = utf8Length(codePoint)
                if (estimatedBytes + encodedBytes > limits.maxEstimatedBytes) {
                    truncation += SnapshotTruncationReason.BYTE_LIMIT
                    break
                }
                builder.appendCodePoint(codePoint)
                sourceIndex += characterCount
                totalTextCharacters += characterCount
                estimatedBytes += encodedBytes
            }
            val wasTruncated = sourceIndex < input.length
            return if (builder.isEmpty() && wasTruncated) {
                null
            } else {
                UntrustedUiText.bounded(builder.toString(), wasTruncated)
            }
        }

        private fun utf8Length(codePoint: Int): Int = when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            // String.toByteArray(UTF_8) replaces an unpaired UTF-16 surrogate with '?'.
            // Paired surrogates were already combined by codePointAt into a value > 0xffff.
            codePoint in 0xd800..0xdfff -> 1
            codePoint <= 0xffff -> 3
            else -> 4
        }
    }

    private companion object {
        const val SNAPSHOT_BASE_BYTES = 256
        const val NODE_BASE_BYTES = 192
        const val EDGE_BYTES = 24
        const val ACTION_BYTES = 4
    }
}
