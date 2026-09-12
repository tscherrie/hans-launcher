package ai.hans.standard.ui

import ai.hans.standard.text.AssistantMarkdown
import ai.hans.standard.text.AssistantMarkdownBlock
import ai.hans.standard.text.AssistantMarkdownBlockKind
import ai.hans.standard.text.AssistantMarkdownRun
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AssistantRichTextUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nativeTextUsesBoldItalicCodeAndNoVisibleMarkdownDelimiters() {
        compose.setContent {
            MaterialTheme { AssistantRichText("**Wichtig** und *freundlich*, `datei.md`.") }
        }
        val node = compose.onNodeWithTag("assistant_rich_block_0")
        node.assertTextEquals("Wichtig und freundlich, datei.md.")
        val text = node.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(text.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(text.spanStyles.any { it.item.fontFamily != null })
    }

    @Test
    fun tappingNamedNativeLinkCallsInjectedHandlerWithExactSafeUrl() {
        var opened: String? = null
        compose.setContent {
            MaterialTheme {
                AssistantRichText("[Quelle](https://example.org/details?a=1)", onOpenLink = { opened = it })
            }
        }
        compose.onNodeWithText("Quelle").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals("https://example.org/details?a=1", opened) }
    }

    @Test
    fun darkModeAndLargeFontKeepReadableDomainLinksAndFormattedLabelInOneRange() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.8f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    AssistantRichText("[**Gute** Quelle](https://example.org) und https://other.example/info.")
                }
            }
        }
        val node = compose.onNodeWithTag("assistant_rich_block_0")
        node.assertTextEquals("Gute Quelle und other.example.")
        val text = node.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(2, links.size)
        assertEquals("Gute Quelle", text.substring(links.first().start, links.first().end))
        assertEquals("https://example.org/", (links.first().item as LinkAnnotation.Url).url)
    }

    @Test
    fun streamingUpdateReplacesRawAddressWithLabelWithoutLosingFollowingSentence() {
        val raw = mutableStateOf("Fertig. [**Quelle**](https://example.org")
        val complete = mutableStateOf(false)
        compose.setContent {
            MaterialTheme { AssistantRichText(raw.value, complete = complete.value) }
        }
        compose.onNodeWithTag("assistant_rich_block_0").assertTextEquals("Fertig. Quelle")
        compose.runOnIdle {
            raw.value = "Fertig. [**Quelle**](https://example.org). Weiter."
            complete.value = true
        }
        compose.onNodeWithTag("assistant_rich_block_0").assertTextEquals("Fertig. Quelle. Weiter.")
    }

    @Test
    fun maliciousManualRunCannotSmuggleExecutableOrFileLinkIntoNativeText() {
        for (destination in listOf("javascript:alert(1)", "file:///data/private", "intent://open")) {
            val block = AssistantMarkdownBlock(AssistantMarkdownBlockKind.PARAGRAPH,
                listOf(AssistantMarkdownRun("Label", url = destination)))
            val text = assistantAnnotatedText(block, Color.Blue, Color.Gray) { error("Must not open") }
            assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
        }
        val block = AssistantMarkdown.parse("`https://example.org/private` ![Foto](https://image.example/private)").blocks.single()
        val text = assistantAnnotatedText(block, Color.Blue, Color.Gray) { error("Must not open") }
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
        assertEquals("https://example.org/private Foto", text.text)
    }
}
