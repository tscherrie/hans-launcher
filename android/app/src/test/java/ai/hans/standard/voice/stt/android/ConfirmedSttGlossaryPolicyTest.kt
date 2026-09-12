package ai.hans.standard.voice.stt.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmedSttGlossaryPolicyTest {
    @Test
    fun editorInputIsSanitizedDeduplicatedAndBoundedToEffectivePromptLimits() {
        val raw = buildString {
            appendLine("  Grenzebach  ")
            appendLine("GRENZEBACH")
            appendLine("Skill\tMe\u0000Now")
            appendLine("X".repeat(ConfirmedSttGlossaryPolicy.MAX_TERM_CHARACTERS + 20))
            repeat(ConfirmedSttGlossaryPolicy.MAX_TERMS + 20) { index ->
                appendLine("Eigenname-$index")
            }
        }

        val terms = ConfirmedSttGlossaryPolicy.parseEditorText(raw)

        assertEquals(ConfirmedSttGlossaryPolicy.MAX_TERMS, terms.size)
        assertEquals("Grenzebach", terms.first())
        assertEquals(1, terms.count { it.equals("Grenzebach", ignoreCase = true) })
        assertTrue(terms.contains("Skill Me Now"))
        assertTrue(terms.all { it.length <= ConfirmedSttGlossaryPolicy.MAX_TERM_CHARACTERS })
        assertFalse(terms.any { term -> term.any(Char::isISOControl) })
    }

    @Test
    fun emptyEditorClearsTheConfirmedSet() {
        assertTrue(ConfirmedSttGlossaryPolicy.parseEditorText(" \n\t\n").isEmpty())
    }
}
