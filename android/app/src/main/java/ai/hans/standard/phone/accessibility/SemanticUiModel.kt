package ai.hans.standard.phone.accessibility

import java.util.Collections

@JvmInline
value class AccessibilitySessionId(val value: String) {
    init {
        require(value.length in 8..128 && value.all(::isSafeIdentifierCharacter)) {
            "invalid accessibility session id"
        }
    }

    private companion object {
        fun isSafeIdentifierCharacter(character: Char): Boolean =
            character.isLetterOrDigit() || character in "-_.:"
    }
}

@JvmInline
value class AccessibilityWindowId(val value: Int) {
    init {
        require(value >= 0) { "window id must not be negative" }
    }
}

@JvmInline
value class AccessibilitySnapshotId(val value: Long) {
    init {
        require(value > 0) { "snapshot id must be positive" }
    }
}

data class UiSnapshotCorrelation(
    val sessionId: AccessibilitySessionId,
    val windowId: AccessibilityWindowId,
    val snapshotId: AccessibilitySnapshotId,
)

data class SemanticNodeHandle(
    val correlation: UiSnapshotCorrelation,
    val nodeOrdinal: Int,
) {
    init {
        require(nodeOrdinal >= 0) { "node ordinal must not be negative" }
    }
}

data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left) { "right must not be left of left" }
        require(bottom >= top) { "bottom must not be above top" }
    }

    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    fun contains(point: UiPoint): Boolean =
        point.x >= left && point.x < right && point.y >= top && point.y < bottom
}

data class UiPoint(val x: Int, val y: Int)

enum class UiDataTrust {
    /** UI strings originate in another app and are data, never instructions. */
    UNTRUSTED_EXTERNAL,

    /** Correlation and action receipts produced by the local Android adapter. */
    LOCAL_SYSTEM,
}

class UntrustedUiText private constructor(
    val value: String,
    val truncated: Boolean,
) {
    val trust: UiDataTrust = UiDataTrust.UNTRUSTED_EXTERNAL

    override fun equals(other: Any?): Boolean =
        other is UntrustedUiText && value == other.value && truncated == other.truncated

    override fun hashCode(): Int = 31 * value.hashCode() + truncated.hashCode()

    override fun toString(): String = "UntrustedUiText(length=${value.length}, truncated=$truncated)"

    companion object {
        internal fun bounded(value: String, truncated: Boolean): UntrustedUiText =
            UntrustedUiText(value, truncated)
    }
}

enum class SemanticUiRole {
    UNKNOWN,
    BUTTON,
    TEXT,
    EDIT_TEXT,
    PASSWORD_FIELD,
    CHECKBOX,
    RADIO_BUTTON,
    SWITCH,
    IMAGE,
    IMAGE_BUTTON,
    LIST,
    LIST_ITEM,
    SCROLL_CONTAINER,
    TAB,
    TOOLBAR,
    DIALOG,
    WEB_CONTENT,
}

enum class SemanticUiAction {
    CLICK,
    LONG_CLICK,
    SET_TEXT,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    FOCUS,
    CLEAR_FOCUS,
    SELECT,
    EXPAND,
    COLLAPSE,
    DISMISS,
}

/** Immutable node. It never owns or exposes AccessibilityNodeInfo. */
class SemanticUiNode internal constructor(
    val handle: SemanticNodeHandle,
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
    actions: Set<SemanticUiAction>,
    children: List<SemanticNodeHandle>,
) {
    val actions: Set<SemanticUiAction> = Collections.unmodifiableSet(actions.toSet())
    val children: List<SemanticNodeHandle> = Collections.unmodifiableList(children.toList())
    val trust: UiDataTrust = UiDataTrust.UNTRUSTED_EXTERNAL
}

enum class SnapshotTruncationReason {
    DEPTH_LIMIT,
    NODE_LIMIT,
    FIELD_TEXT_LIMIT,
    TOTAL_TEXT_LIMIT,
    BYTE_LIMIT,
    CYCLIC_OR_SHARED_NODE,
    PLATFORM_NODE_UNAVAILABLE,
}

data class UiSnapshotLimits(
    val maxDepth: Int = 32,
    val maxNodes: Int = 2_048,
    val maxTextCharsPerField: Int = 4_096,
    val maxTotalTextChars: Int = 128 * 1_024,
    val maxEstimatedBytes: Int = 1 * 1_024 * 1_024,
) {
    init {
        require(maxDepth in 1..128)
        require(maxNodes in 1..10_000)
        require(maxTextCharsPerField in 16..32_768)
        require(maxTotalTextChars in maxTextCharsPerField..1_000_000)
        require(maxEstimatedBytes in MINIMUM_SNAPSHOT_BYTES..8 * 1_024 * 1_024)
    }

    companion object {
        const val MINIMUM_SNAPSHOT_BYTES = 1_024
    }
}

/** Input DTO copied immediately by [BoundedSemanticUiSnapshotFactory]. */
data class RawSemanticUiNode(
    val packageName: String? = null,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val role: SemanticUiRole = SemanticUiRole.UNKNOWN,
    val bounds: UiBounds,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val actions: Set<SemanticUiAction> = emptySet(),
    val children: List<RawSemanticUiNode> = emptyList(),
)

data class RawSemanticUiSnapshot(
    val correlation: UiSnapshotCorrelation,
    val displayBounds: UiBounds,
    val capturedAtElapsedMillis: Long,
    val roots: List<RawSemanticUiNode>,
    val sourceTruncationReasons: Set<SnapshotTruncationReason> = emptySet(),
) {
    init {
        require(capturedAtElapsedMillis >= 0)
    }
}

class SemanticUiSnapshot internal constructor(
    val correlation: UiSnapshotCorrelation,
    val displayBounds: UiBounds,
    val capturedAtElapsedMillis: Long,
    nodes: List<SemanticUiNode>,
    roots: List<SemanticNodeHandle>,
    truncationReasons: Set<SnapshotTruncationReason>,
    val estimatedBytes: Int,
    val totalTextCharacters: Int,
) {
    val nodes: List<SemanticUiNode> = Collections.unmodifiableList(nodes.toList())
    val roots: List<SemanticNodeHandle> = Collections.unmodifiableList(roots.toList())
    val truncationReasons: Set<SnapshotTruncationReason> =
        Collections.unmodifiableSet(truncationReasons.toSet())
    val trust: UiDataTrust = UiDataTrust.UNTRUSTED_EXTERNAL
    val isComplete: Boolean
        get() = truncationReasons.isEmpty()

    private val nodesByOrdinal = this.nodes.associateBy { it.handle.nodeOrdinal }

    fun resolve(handle: SemanticNodeHandle): SemanticUiNode? {
        if (handle.correlation != correlation) return null
        return nodesByOrdinal[handle.nodeOrdinal]
    }
}

interface CurrentSemanticUiSnapshotSource {
    fun current(): SemanticUiSnapshot?

    /**
     * Resolves a correlation that was explicitly made available for semantic commands.
     * Latest-only sources keep their historical behavior; Android's live source overrides
     * this with a bounded, in-memory set of snapshots returned by inspect_ui.
     */
    fun snapshotForCorrelation(correlation: UiSnapshotCorrelation): SemanticUiSnapshot? = current()
}

class InMemorySemanticUiSnapshotSource : CurrentSemanticUiSnapshotSource {
    @Volatile
    private var snapshot: SemanticUiSnapshot? = null

    override fun current(): SemanticUiSnapshot? = snapshot

    fun publish(value: SemanticUiSnapshot) {
        snapshot = value
    }

    fun clear() {
        snapshot = null
    }
}
