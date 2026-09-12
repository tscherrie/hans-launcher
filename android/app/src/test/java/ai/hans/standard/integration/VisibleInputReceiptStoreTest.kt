package ai.hans.standard.integration

import ai.hans.standard.codex.CodexInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisibleInputReceiptStoreTest {
    @Test
    fun receiptRecoversOnlyOrderedVisibleInputsAndSkipsInternalContext() {
        val receipt = checkNotNull(
            VisibleInputReceipt.fromInputs(
                threadId = "thread-1",
                clientId = "client-1",
                inputs = listOf(
                    CodexInput.UntrustedContext("SECRET BEFORE"),
                    CodexInput.Text("Visible first"),
                    CodexInput.UntrustedContext("SECRET BETWEEN"),
                    CodexInput.Text("Visible second"),
                ),
            ),
        )

        assertEquals(
            "Visible first\nVisible second",
            receipt.recoverDisplayText(
                listOf("SECRET BEFORE", "Visible first", "SECRET BETWEEN", "Visible second"),
            ),
        )
    }

    @Test
    fun duplicateDigestAlignmentFailsClosedInsteadOfGuessing() {
        val receipt = checkNotNull(
            VisibleInputReceipt.fromInputs(
                "thread-1",
                "client-1",
                listOf(CodexInput.Text("same")),
            ),
        )

        assertNull(receipt.recoverDisplayText(listOf("same", "internal", "same")))
    }

    @Test
    fun changedOrMissingVisibleTextCannotBeRecovered() {
        val receipt = checkNotNull(
            VisibleInputReceipt.fromInputs(
                "thread-1",
                "client-1",
                listOf(CodexInput.Text("expected")),
            ),
        )

        assertNull(receipt.recoverDisplayText(listOf("SECRET only")))
    }

    @Test
    fun attachmentOnlyInputUsesAContentFreePlaceholder() {
        val receipt = checkNotNull(
            VisibleInputReceipt.fromInputs(
                "thread-1",
                "client-1",
                listOf(CodexInput.LocalImage("/private/image.jpg")),
            ),
        )

        assertEquals("Attachment", receipt.recoverDisplayText(emptyList()))
    }
}
