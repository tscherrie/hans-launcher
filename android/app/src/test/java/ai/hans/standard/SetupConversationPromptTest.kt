package ai.hans.standard

import ai.hans.standard.codex.CodexInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupConversationPromptTest {
    @Test
    fun visibleSetupPromptIsNaturalAndRequestsExactlyOneNextAction() {
        listOf(false, true).forEach { complete ->
            val prompt = SetupConversationPrompt.forState(complete)

            assertTrue(prompt.visibleText.contains("gemeinsam"))
            assertTrue(prompt.visibleText.length < 90)
            assertFalse(prompt.visibleText.contains("hans_setup"))
            assertFalse(prompt.visibleText.contains("setup-hans-device"))
            assertFalse(prompt.visibleText.contains('$'))
            assertTrue(prompt.internalRoutingContext.contains("hans_setup.get_setup_state"))
            assertTrue(prompt.internalRoutingContext.contains("Möchtest du die Einrichtung"))
            assertTrue(prompt.internalRoutingContext.contains("Chat oder per Sprache"))
            assertTrue(prompt.internalRoutingContext.contains("choice:\"begin\""))
        }
    }

    @Test
    fun freshInstallSetupTurnCarriesTheProvenSkillInsteadOfFallingBackToGenericChat() {
        val skill = CodexInput.Skill(
            name = "hans-setup:setup-hans-device",
            absolutePath = "/data/user/0/ai.hans.standard/files/hans-setup/SKILL.md",
        )

        val input = SetupConversationPrompt.forState(complete = false).toCodexInput(skill)

        assertEquals(3, input.size)
        assertTrue(input[0] is CodexInput.Text)
        assertEquals(skill, input[1])
        assertTrue(input[2] is CodexInput.UntrustedContext)
    }
}
