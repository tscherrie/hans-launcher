package ai.hans.standard.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DynamicToolNamespaceDescriptionTest {
    private val tool = DynamicToolFunctionSpec("probe", "Probe", """{"type":"object"}""")
    private fun namespace(description: String) = DynamicToolNamespaceSpec("probe", description, listOf(tool))

    @Test fun acceptsNativeBoundaryOf1024AsciiCharacters() {
        assertEquals(1024, namespace("a".repeat(1024)).description.length)
    }

    @Test fun rejects1025AsciiCharactersBeforeThreadStart() {
        assertThrows(IllegalArgumentException::class.java) { namespace("a".repeat(1025)) }
    }

    @Test fun rejectsThePreviouslyShipped1461CharacterRegression() {
        assertThrows(IllegalArgumentException::class.java) { namespace("a".repeat(1461)) }
    }

    @Test fun nativeBoundaryCountsCodePointsRatherThanUtf16UnitsOrUtf8Bytes() {
        val description = "\uD83D\uDE42".repeat(1024)
        assertEquals(2048, namespace(description).description.length)
        assertEquals(4096, description.toByteArray(Charsets.UTF_8).size)
        assertThrows(IllegalArgumentException::class.java) { namespace("é".repeat(1025)) }
    }

    @Test fun namespaceLimitDoesNotUnnecessarilyRestrictFunctionDescriptions() {
        assertEquals(4096, tool.copy(description = "a".repeat(4096)).description.length)
        assertThrows(IllegalArgumentException::class.java) { tool.copy(description = "a".repeat(4097)) }
    }
}
