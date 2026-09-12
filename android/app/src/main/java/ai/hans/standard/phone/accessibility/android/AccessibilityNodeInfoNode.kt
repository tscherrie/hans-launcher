package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.SemanticUiAction
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiScrollDirection
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Owns exactly one framework node and recycles it once the current operation ends. */
internal class AccessibilityNodeInfoNode(
    private val node: AccessibilityNodeInfo,
) : AndroidAccessibilityNode {
    private val closed = AtomicBoolean(false)

    override val windowId: Int
        get() = openNode().windowId

    override val uniqueId: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            openNode().uniqueId
        } else {
            null
        }

    override val viewIdResourceName: String?
        get() = openNode().viewIdResourceName

    override val packageName: CharSequence?
        get() = openNode().packageName

    override val className: CharSequence?
        get() = openNode().className

    override val text: CharSequence?
        get() = openNode().text

    override val contentDescription: CharSequence?
        get() = openNode().contentDescription

    override val role: SemanticUiRole
        get() {
            val current = openNode()
            if (current.isPassword) return SemanticUiRole.PASSWORD_FIELD
            val name = current.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
            return when {
                current.isEditable || "edittext" in name -> SemanticUiRole.EDIT_TEXT
                "imagebutton" in name -> SemanticUiRole.IMAGE_BUTTON
                "radiobutton" in name -> SemanticUiRole.RADIO_BUTTON
                "checkbox" in name -> SemanticUiRole.CHECKBOX
                "switch" in name || "togglebutton" in name -> SemanticUiRole.SWITCH
                "button" in name -> SemanticUiRole.BUTTON
                "imageview" in name -> SemanticUiRole.IMAGE
                "recyclerview" in name || "listview" in name -> SemanticUiRole.LIST
                current.isScrollable || "scrollview" in name -> SemanticUiRole.SCROLL_CONTAINER
                "tab" in name -> SemanticUiRole.TAB
                "toolbar" in name -> SemanticUiRole.TOOLBAR
                "dialog" in name -> SemanticUiRole.DIALOG
                "webview" in name -> SemanticUiRole.WEB_CONTENT
                current.text != null || current.contentDescription != null -> SemanticUiRole.TEXT
                else -> SemanticUiRole.UNKNOWN
            }
        }

    override val bounds: UiBounds
        get() {
            val rect = Rect()
            openNode().getBoundsInScreen(rect)
            return UiBounds(rect.left, rect.top, rect.right, rect.bottom)
        }

    override val visible: Boolean
        get() = openNode().isVisibleToUser

    override val enabled: Boolean
        get() = openNode().isEnabled

    override val clickable: Boolean
        get() = openNode().isClickable

    override val editable: Boolean
        get() = openNode().isEditable

    override val scrollable: Boolean
        get() = openNode().isScrollable

    override val actions: Set<SemanticUiAction>
        get() = openNode().actionList.mapNotNullTo(linkedSetOf()) { action ->
            when (action.id) {
                AccessibilityNodeInfo.ACTION_CLICK -> SemanticUiAction.CLICK
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> SemanticUiAction.LONG_CLICK
                AccessibilityNodeInfo.ACTION_SET_TEXT -> SemanticUiAction.SET_TEXT
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> SemanticUiAction.SCROLL_FORWARD
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> SemanticUiAction.SCROLL_BACKWARD
                AccessibilityNodeInfo.ACTION_FOCUS -> SemanticUiAction.FOCUS
                AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> SemanticUiAction.CLEAR_FOCUS
                AccessibilityNodeInfo.ACTION_SELECT -> SemanticUiAction.SELECT
                AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id -> SemanticUiAction.EXPAND
                AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.id -> SemanticUiAction.COLLAPSE
                AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id -> SemanticUiAction.DISMISS
                else -> null
            }
        }

    override val childCount: Int
        get() = openNode().childCount

    override fun childAt(index: Int): AndroidAccessibilityNode? =
        openNode().getChild(index)?.let(::AccessibilityNodeInfoNode)

    override fun perform(action: AndroidNodeAction): Boolean = when (action) {
        AndroidNodeAction.Click -> openNode().performAction(AccessibilityNodeInfo.ACTION_CLICK)
        is AndroidNodeAction.SetText -> openNode().performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    action.value,
                )
            },
        )
        is AndroidNodeAction.Scroll -> openNode().performAction(
            when (action.direction) {
                UiScrollDirection.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                UiScrollDirection.BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            },
        )
    }

    @Suppress("DEPRECATION")
    override fun close() {
        if (closed.compareAndSet(false, true)) node.recycle()
    }

    private fun openNode(): AccessibilityNodeInfo {
        check(!closed.get()) { "accessibility node already closed" }
        return node
    }
}
