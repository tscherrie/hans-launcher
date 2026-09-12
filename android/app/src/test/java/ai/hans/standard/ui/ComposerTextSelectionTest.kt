package ai.hans.standard.ui

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ComposerTextSelectionTest {
    @Test
    fun firstHardwareCharacterMovesCursorBehindItBeforeSecondCharacter() {
        val editor = ComposerEditorState("")

        editor.insertText("a")
        assertEquals("a", editor.text)
        assertEquals(TextRange(1), editor.textField.selection)

        editor.insertText("b")
        assertEquals("ab", editor.text)
        assertEquals(TextRange(2), editor.textField.selection)
    }

    @Test
    fun insertionReplacesSelectionAndPreservesNaturalCursorEditing() {
        val editor = ComposerEditorState("Hans phone")
        editor.textField.edit { selection = TextRange(10, 5) }

        editor.insertText("OS")

        assertEquals("Hans OS", editor.text)
        assertEquals(TextRange(7), editor.textField.selection)
    }

    @Test
    fun equalRuntimeEchoDoesNotReplaceBufferOrResetSelection() {
        val editor = ComposerEditorState("")
        editor.insertText("Hans")
        assertEquals("Hans", editor.takeDraftPublication())
        editor.textField.edit { selection = TextRange(1) }
        val buffer = editor.textField

        editor.reconcileExternalDraft("Hans")

        assertSame(buffer, editor.textField)
        assertEquals("Hans", editor.text)
        assertEquals(TextRange(1), editor.textField.selection)
        assertNull(editor.takeDraftPublication())
    }

    @Test
    fun externalDraftReplacementMovesCursorToNewEnd() {
        val editor = ComposerEditorState("old")
        editor.textField.edit { selection = TextRange(1) }

        editor.reconcileExternalDraft("new draft")

        assertEquals("new draft", editor.text)
        assertEquals(TextRange(9), editor.textField.selection)
        assertNull("A parent replacement must not echo back as a new edit", editor.takeDraftPublication())

        editor.reconcileExternalDraft("")
        assertEquals("", editor.text)
        assertEquals(TextRange.Zero, editor.textField.selection)
        assertNull(editor.takeDraftPublication())
    }

    @Test
    fun firstIdleKeyAndSubsequentNativeEditShareTheSameBuffer() {
        val editor = ComposerEditorState("")
        val androidField = editor.textField
        editor.insertText("t")

        // BasicTextField edits this same state immediately, without a parent render in between.
        androidField.edit {
            replace(selection.min, selection.max, "est")
            selection = TextRange(length)
        }

        assertSame(androidField, editor.textField)
        assertEquals("test", editor.text)
        assertEquals(TextRange(4), editor.textField.selection)
        assertEquals("test", editor.takeDraftPublication())
    }

    @Test
    fun delayedAcknowledgementDoesNotOverwriteNewerUnpublishedTextOrCaret() {
        val editor = ComposerEditorState("")
        editor.insertText("t")
        val parentEcho = editor.takeDraftPublication()
        editor.insertText("est")
        editor.textField.edit { selection = TextRange(2) }

        editor.reconcileExternalDraft(checkNotNull(parentEcho))

        assertEquals("test", editor.text)
        assertEquals(TextRange(2), editor.textField.selection)
        assertEquals("test", editor.takeDraftPublication())
        editor.reconcileExternalDraft("test")
        assertEquals(TextRange(2), editor.textField.selection)
        assertNull(editor.takeDraftPublication())
    }

    @Test
    fun immediateSubmissionCanBeClearedBeforeTheFirstParentEchoIsRendered() {
        val editor = ComposerEditorState("")
        editor.insertText("test")
        assertEquals("test", editor.takeDraftPublication())
        assertEquals("test", editor.prepareSubmission())

        // Parent received and sent the draft synchronously, then cleared it in the same frame.
        editor.reconcileDispatch(ComposerDraftSnapshot(text = "", dispatchPending = false))

        assertEquals("", editor.text)
        assertEquals(TextRange.Zero, editor.textField.selection)
        assertNull("A previously scheduled publication must not resurrect sent text", editor.takeDraftPublication())
        editor.insertText("new")
        assertEquals("new", editor.takeDraftPublication())
    }

    @Test
    fun rejectedSubmissionRetainsTheAcknowledgedDraft() {
        val editor = ComposerEditorState("")
        editor.insertText("still here")
        val parentDraft = checkNotNull(editor.takeDraftPublication())
        assertEquals(parentDraft, editor.prepareSubmission())

        // A rejected dispatch does not clear the parent's draft.
        editor.reconcileDispatch(ComposerDraftSnapshot(text = parentDraft, dispatchPending = false))

        assertEquals("still here", editor.text)
        assertEquals(TextRange(10), editor.textField.selection)
        assertNull(editor.takeDraftPublication())

        editor.insertText(" new")
        assertEquals("still here new", editor.takeDraftPublication())
    }

    @Test
    fun pendingDispatchKeepsTheOriginalDraftUntilAuthoritativeSentThenAcceptsNewInput() {
        val editor = ComposerEditorState("")
        editor.insertText("test")
        assertEquals("test", editor.takeDraftPublication())
        assertEquals("test", editor.prepareSubmission())

        editor.reconcileDispatch(ComposerDraftSnapshot(text = "test", dispatchPending = true))
        editor.insertText("must not change the in-flight draft")

        assertEquals(true, editor.dispatchPending)
        assertEquals("test", editor.text)
        assertNull(editor.takeDraftPublication())

        // This is the existing owner's SENT reconciliation, not an optimistic editor clear.
        editor.reconcileExternalDraft(external = "", pending = false)
        assertEquals(false, editor.dispatchPending)
        assertEquals("", editor.text)
        editor.insertText("new")
        assertEquals("new", editor.takeDraftPublication())
    }

    @Test
    fun asynchronouslyRejectedPendingDispatchRestoresEditingWithoutDiscardingDraft() {
        val editor = ComposerEditorState("")
        editor.insertText("keep me")
        assertEquals("keep me", editor.takeDraftPublication())
        editor.prepareSubmission()
        editor.reconcileDispatch(ComposerDraftSnapshot(text = "keep me", dispatchPending = true))

        editor.reconcileExternalDraft(external = "keep me", pending = false)
        editor.insertText(" please")

        assertEquals(false, editor.dispatchPending)
        assertEquals("keep me please", editor.text)
        assertEquals("keep me please", editor.takeDraftPublication())
    }

    @Test
    fun sendEligibilityUsesImmediateTextFieldValueInsteadOfDelayedRuntimeEcho() {
        assertEquals(true, composerCanSend(enabled = true, currentText = "a", hasAttachments = false))
        assertEquals(true, composerCanSend(enabled = true, currentText = "", hasAttachments = true))
        assertEquals(false, composerCanSend(enabled = true, currentText = "   ", hasAttachments = false))
        assertEquals(false, composerCanSend(enabled = false, currentText = "a", hasAttachments = true))
    }
}
