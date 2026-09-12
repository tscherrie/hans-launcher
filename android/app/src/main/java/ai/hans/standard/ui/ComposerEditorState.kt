package ai.hans.standard.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange

/**
 * One editing buffer for idle-first-key insertion, Android/IME edits, Alt and submission.
 * The parent owns the durable draft; its synchronous callback echoes are acknowledgements,
 * not replacements for a newer edit that arrived before the next composition.
 */
internal class ComposerEditorState(initialText: String) {
    val textField = TextFieldState(initialText, TextRange(initialText.length))
    private var lastExternalText = initialText
    private var lastPublishedText = initialText
    var dispatchPending by mutableStateOf(false)
        private set

    val text: String
        get() = textField.text.toString()

    fun insertText(inserted: String) {
        if (inserted.isEmpty() || dispatchPending) return
        textField.edit {
            val start = selection.min
            replace(start, selection.max, inserted)
            selection = TextRange(start + inserted.length)
        }
    }

    /** Called synchronously after a committed composition, never from a delayed coroutine. */
    fun reconcileExternalDraft(external: String, pending: Boolean = false) {
        dispatchPending = pending
        if (external == lastExternalText) return
        lastExternalText = external
        if (external == lastPublishedText) return
        lastPublishedText = external
        if (text != external) textField.setTextAndPlaceCursorAtEnd(external)
    }

    /** Read current buffer state, not an earlier snapshotFlow emission or rendered value. */
    fun takeDraftPublication(): String? {
        if (dispatchPending) return null
        val current = text
        if (current == lastPublishedText) return null
        lastPublishedText = current
        return current
    }

    /**
     * Call after publishing this exact draft and before onSend. The parent can acknowledge and
     * clear it in the same frame, so an empty draft must not be mistaken for unchanged startup
     * state. A rejected send leaves the same text intact.
     */
    fun prepareSubmission(): String = text.also { lastExternalText = it }

    /** Synchronous authoritative read after dispatch, before another key can be received. */
    fun reconcileDispatch(snapshot: ComposerDraftSnapshot) {
        reconcileExternalDraft(snapshot.text, snapshot.dispatchPending)
    }
}
