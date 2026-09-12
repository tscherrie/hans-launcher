package ai.hans.standard.voice.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SttTranscriptionContextTest {
    @Test
    fun emptyContextProducesNoPrompt() {
        assertNull(SttTranscriptionPromptBuilder.build(SttTranscriptionContext()))
        assertNull(
            SttTranscriptionPromptBuilder.build(
                SttTranscriptionContext(
                    confirmedProfileSummary = "  \n\t ",
                    confirmedGlossaryTerms = listOf("", "\u0000"),
                ),
            ),
        )
    }

    @Test
    fun promptIsBoundedAndKeepsConfirmedKeywordsAheadOfLargeProfile() {
        val prompt = checkNotNull(
            SttTranscriptionPromptBuilder.build(
                SttTranscriptionContext(
                    confirmedProfileSummary = "Profilwort ".repeat(2_000),
                    confirmedGlossaryTerms = listOf("Grenzebach", "Skill Me Now"),
                ),
            ),
        )

        assertTrue(prompt.length <= SttTranscriptionPromptBuilder.MAX_PROMPT_CHARACTERS)
        assertTrue(
            prompt.toByteArray(Charsets.UTF_8).size <=
                SttTranscriptionPromptBuilder.MAX_PROMPT_UTF8_BYTES,
        )
        assertTrue(prompt.contains("Confirmed keywords: Grenzebach, Skill Me Now"))
        assertTrue(prompt.contains("Confirmed user context: Profilwort"))
    }

    @Test
    fun controlAndFormatCharactersCannotReshapePrompt() {
        val prompt = checkNotNull(
            SttTranscriptionPromptBuilder.build(
                SttTranscriptionContext(
                    confirmedProfileSummary = "Jeremias\n\u001b[31m\u202Eexample",
                    confirmedGlossaryTerms = listOf("Gams\u0000bart", "Skill\tMe\rNow"),
                ),
            ),
        )

        assertTrue(prompt.contains("Gams bart"))
        assertTrue(prompt.contains("Skill Me Now"))
        assertTrue(prompt.contains("Jeremias [31m example"))
        assertFalse(prompt.any(Char::isISOControl))
        assertFalse(prompt.contains('\u202E'))
    }

    @Test
    fun keywordsAreCleanedDeduplicatedAndCountLimited() {
        val terms = buildList {
            add(" Grenzebach ")
            add("GRENZEBACH")
            add("Skill Me Now")
            repeat(100) { add("Eigenname-$it") }
        }
        val prompt = checkNotNull(
            SttTranscriptionPromptBuilder.build(
                SttTranscriptionContext(confirmedGlossaryTerms = terms),
            ),
        )
        val keywordSection = prompt.substringAfter("Confirmed keywords: ")
        val emitted = keywordSection.split(", ")

        assertTrue(emitted.size <= SttTranscriptionPromptBuilder.MAX_GLOSSARY_TERMS)
        assertEquals(1, emitted.count { it.equals("Grenzebach", ignoreCase = true) })
        assertTrue(emitted.contains("Skill Me Now"))
    }

    @Test
    fun multibyteConfirmedContextCannotExceedProviderSafeUtf8Budget() {
        val prompt = checkNotNull(
            SttTranscriptionPromptBuilder.build(
                SttTranscriptionContext(
                    confirmedProfileSummary = "äöü🦌".repeat(2_000),
                    confirmedGlossaryTerms = listOf("Gamsbart", "München", "🦌 Hans"),
                ),
            ),
        )

        assertTrue(prompt.contains("Gamsbart"))
        assertTrue(prompt.contains("München"))
        assertTrue(
            prompt.toByteArray(Charsets.UTF_8).size <=
                SttTranscriptionPromptBuilder.MAX_PROMPT_UTF8_BYTES,
        )
        assertFalse(prompt.last().isHighSurrogate())
    }
}
