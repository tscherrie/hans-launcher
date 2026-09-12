package ai.hans.standard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AssistantMarkdownTest {
    @Test
    fun boldItalicAndInlineCodeUseStylesRatherThanPrintedDelimiters() {
        val document = AssistantMarkdown.parse("**Wichtig** und *freundlich*, `datei.md`, ***beides***.")
        assertEquals("Wichtig und freundlich, datei.md, beides.", document.plainText)
        assertEquals(document.plainText, document.spokenText)
        val runs = document.blocks.single().runs
        assertTrue(runs.single { it.text == "Wichtig" }.bold)
        assertTrue(runs.single { it.text == "freundlich" }.italic)
        assertTrue(runs.single { it.text == "datei.md" }.code)
        assertTrue(runs.single { it.text == "beides" }.let { it.bold && it.italic })
    }

    @Test
    fun nestedEmphasisAndApostrophesKeepTheirMeaning() {
        val document = AssistantMarkdown.parse("**Hans' *gute* Idee**; don't remove ‘quotes’ oder file_name.")
        assertEquals("Hans' gute Idee; don't remove ‘quotes’ oder file_name.", document.plainText)
        assertTrue(document.blocks.single().runs.single { it.text == "gute" }.let { it.bold && it.italic })
    }

    @Test
    fun nestedBoldAndItalicMayShareTheirThreeCharacterClosingRun() {
        for ((source, expected, inner) in listOf(
            Triple("**fett *kursiv***", "fett kursiv", "kursiv"),
            Triple("*kursiv **fett***", "kursiv fett", "fett"),
            Triple("__fett _kursiv___", "fett kursiv", "kursiv"),
        )) {
            val document = AssistantMarkdown.parse(source)
            assertEquals(expected, document.plainText)
            assertEquals(expected, document.spokenText)
            assertTrue(document.blocks.single().runs.single { it.text == inner }.let { it.bold && it.italic })
            val partial = AssistantMarkdown.parse(source, complete = false)
            assertEquals(expected, partial.plainText)
            assertEquals("", partial.spokenText)
            assertStablePrefixes("$source. Danach.")
        }
    }

    @Test
    fun namedLinksPreserveLabelFormattingAndCanonicalDestination() {
        val document = AssistantMarkdown.parse("Die [**Route** nach Hause](https://maps.example/weg?a=1&b=2) steht.")
        assertEquals("Die Route nach Hause steht.", document.plainText)
        assertEquals(document.plainText, document.spokenText)
        val label = document.blocks.single().runs.filter { it.url != null }
        assertEquals("Route nach Hause", label.joinToString("") { it.text })
        assertTrue(label.first().bold)
        assertTrue(label.all { it.url == "https://maps.example/weg?a=1&b=2" })
    }

    @Test
    fun bracketsInsideCodeDoNotCloseTheSurroundingLinkLabel() {
        for (code in listOf("a]b", "a[b", "a[b]c")) {
            val source = "[Code `$code`](https://example.org)"
            val document = AssistantMarkdown.parse(source)
            assertEquals("Code $code", document.plainText)
            assertEquals(document.plainText, document.spokenText)
            val runs = document.blocks.single().runs
            assertTrue(runs.all { it.url == "https://example.org/" })
            assertTrue(runs.single { it.text == code }.code)
            assertStablePrefixes("$source. Danach.")
        }
    }

    @Test
    fun rawWebAddressesBecomeHonestDomainLabelsWithoutInventingPageTitles() {
        val document = AssistantMarkdown.parse("Siehe https://example.org/very/long?q=private. Danach www.example.net/info!")
        assertEquals("Siehe example.org. Danach example.net!", document.plainText)
        assertEquals(document.plainText, document.spokenText)
        assertEquals(listOf("https://example.org/very/long?q=private", "https://www.example.net/info"),
            document.blocks.flatMap { it.runs }.mapNotNull { it.url })
    }

    @Test
    fun autolinksEmptyAndRawUrlLabelsUseTheHost() {
        val document = AssistantMarkdown.parse(
            "<https://example.org/a> [](https://empty.example) [https://label.example/x](https://target.example).",
        )
        assertEquals("example.org empty.example label.example.", document.plainText)
        assertEquals(document.plainText, document.spokenText)
    }

    @Test
    fun unicodeHostAndUnicodeLabelAreSupported() {
        val document = AssistantMarkdown.parse("[Bücher 😀](HTTPS://bücher.example/überblick) 中文 e\u0301.")
        assertEquals("Bücher 😀 中文 e\u0301.", document.plainText)
        assertTrue(document.blocks.single().runs.first { it.url != null }.url!!.startsWith("https://xn--bcher-kva.example/"))
    }

    @Test
    fun linkTitlesAndBalancedEscapedParenthesesAreNotSpoken() {
        for (source in listOf(
            "[Route](https://example.org/a_(b_(c)) \"ein (langer) Titel\")",
            "[Route](<https://example.org/a_(b)> 'ein Titel')",
            "[Route](https://example.org/a\\(b\\))",
        )) {
            val document = AssistantMarkdown.parse(source)
            assertEquals(source, "Route", document.plainText)
            assertEquals(source, "Route", document.spokenText)
            assertTrue(document.blocks.single().runs.all { it.url != null })
        }
    }

    @Test
    fun onlySafeHttpDestinationsAreClickable() {
        for (destination in listOf(
            "javascript:alert(1)", "file:///data/private", "intent://open#Intent;end", "data:text/html,hi",
            "ftp://example.org", "content://provider/item", "https://user:password@example.org/",
            "https://@example.org/", "https://example.org\\@other.example", "https://example.org/\nsecret",
        )) {
            assertNull(destination, AssistantMarkdown.safeWebUrl(destination))
            val document = AssistantMarkdown.parse("[Link]($destination)")
            assertTrue(destination, document.blocks.flatMap { it.runs }.all { it.url == null })
        }
        assertEquals("https://example.org/", AssistantMarkdown.safeWebUrl("HTTPS://EXAMPLE.ORG"))
    }

    @Test
    fun htmlIsInertLiteralTextAndImagesAreOnlyAltText() {
        val source = "<b>Hallo</b> <script>alert('x')</script> ![Ein **Baum**](https://example.org/private.png)."
        val document = AssistantMarkdown.parse(source)
        assertEquals("<b>Hallo</b> <script>alert('x')</script> Ein Baum.", document.plainText)
        assertTrue(document.blocks.flatMap { it.runs }.none { it.url != null })
    }

    @Test
    fun codeIsLiteralAndNeverTurnsIntoClickableMarkup() {
        val document = AssistantMarkdown.parse(
            "`**x** https://example.org`\n\n```kotlin\nval text = \"**not bold**\"\nhttps://code.example/x\n```",
        )
        assertEquals(AssistantMarkdownBlockKind.CODE, document.blocks.last().kind)
        assertEquals("kotlin", document.blocks.last().codeLanguage)
        assertTrue(document.blocks.flatMap { it.runs }.all { it.url == null })
        assertEquals("**x** example.org\n\nval text = \"**not bold**\"\ncode.example", document.spokenText)
        assertTrue(document.plainText.contains("https://code.example/x"))
    }

    @Test
    fun headingsListsQuotesAndParagraphsHaveNativeBlockMetadata() {
        val document = AssistantMarkdown.parse("## Hallo\n\n- **Erstens**\n2. Zweitens\n\n> Ein Zitat\n\nEin Absatz.")
        assertEquals(listOf(
            AssistantMarkdownBlockKind.HEADING, AssistantMarkdownBlockKind.LIST_ITEM,
            AssistantMarkdownBlockKind.LIST_ITEM, AssistantMarkdownBlockKind.QUOTE,
            AssistantMarkdownBlockKind.PARAGRAPH,
        ), document.blocks.map { it.kind })
        assertEquals(2, document.blocks.first().headingLevel)
        assertEquals("Hallo\n\n• Erstens\n\n2. Zweitens\n\nEin Zitat\n\nEin Absatz.", document.plainText)
        assertEquals("Hallo\n\nErstens\n\n2. Zweitens\n\nEin Zitat\n\nEin Absatz.", document.spokenText)
    }

    @Test
    fun escapesOnlyUnquoteMarkdownPunctuationNotNormalBackslashes() {
        val document = AssistantMarkdown.parse("\\*kein Kursiv\\* \\[kein Link] C:\\docs\\note.txt. Hans' Handy.")
        assertEquals("*kein Kursiv* [kein Link] C:\\docs\\note.txt. Hans' Handy.", document.plainText)
        assertTrue(document.blocks.single().runs.none { it.bold || it.italic || it.url != null })
    }

    @Test
    fun malformedCompletedMarkupRetainsOrdinaryContent() {
        for (source in listOf("**ohne Ende", "[kein Link", "`kein Ende", "[Datei](/tmp/note.md)", "<offen")) {
            assertEquals(source, AssistantMarkdown.parse(source).plainText)
        }
        val source = "[Info](https://example.org ohne Ende. Nächster Satz."
        assertEquals(source, AssistantMarkdown.parse(source).plainText)
    }

    @Test
    fun trailingRawUrlIsHiddenFromSpeechAndNotPrintedInFullWhileStreaming() {
        val partial = AssistantMarkdown.parse("Schon fertig. https://example.org/private?token=abc", complete = false)
        assertEquals("Schon fertig.", partial.spokenText)
        assertEquals("Schon fertig. example.org", partial.plainText)
        assertTrue(partial.blocks.flatMap { it.runs }.none { it.url != null })
        assertEquals("Schon fertig. example.org", AssistantMarkdown.parse("Schon fertig. https://example.org/private?token=abc").spokenText)
    }

    @Test
    fun underscoreEmphasisDoesNotHideWebAddressesFromTheSharedLinkProjection() {
        for (source in listOf("_https://example.org/a_b_", "__https://example.org/a_b__", "___https://example.org/a_b___")) {
            val document = AssistantMarkdown.parse(source)
            assertEquals(source, "example.org", document.plainText)
            assertEquals(source, "example.org", document.spokenText)
            assertTrue(document.blocks.single().runs.single().url != null)
            assertStablePrefixes(source + ". Danach.")
        }
        assertEquals("www.", AssistantMarkdown.parse("https://www.").plainText)
    }

    @Test
    fun realFinishedSentencesAreAvailableBeforeFinalMessage() {
        for (source in listOf("Hallo.", "Erster Satz. Zweiter Satz.", "Ja!", "Alles bereit?")) {
            assertEquals(source, AssistantMarkdown.parse(source, complete = false).spokenText)
        }
        assertEquals("Wichtig. Weiter.", AssistantMarkdown.parse("**Wichtig.** Weiter.", complete = false).spokenText)
    }

    @Test
    fun currentlyClosedTerminalMarkupIsVisibleBeforeItsSpeechIsStable() {
        for (source in listOf("**Erster Satz.**", "*Erster Satz.*", "__Erster Satz.__", "`Erster Satz.`")) {
            val partial = AssistantMarkdown.parse(source, complete = false)
            assertEquals(source, "Erster Satz.", partial.plainText)
            assertEquals(source, "", partial.spokenText)
            assertEquals(source, "Erster Satz. Weiter.",
                AssistantMarkdown.parse("$source Weiter.", complete = false).spokenText)
            assertStablePrefixes("$source Weiter.")
        }
    }

    @Test
    fun provisionalFenceHeaderDoesNotFinalizeAnEarlierUnclosedInlineMarker() {
        assertEquals("", AssistantMarkdown.parse("**Erster Satz.\n```c", complete = false).spokenText)
        assertStablePrefixes("**Erster Satz.\n```c```**")
        assertStablePrefixes("Fertig. **Noch ein Satz.\n```c```** Danach.")
        assertStablePrefixes("**Erster Satz.\n```kotlin\nval x = 1\n```\nWeiter.")
        assertStablePrefixes("[Erster Satz.\n```c```](https://example.org)")
        assertStablePrefixes("Erster `Satz.\n```c`")
    }

    @Test
    fun speechIsMonotonicAtEveryUtf16BoundaryIncludingFinalization() {
        val sources = listOf(
            "**Erster Satz.** Zweiter *freundlicher* Satz. Danach **noch einer**.",
            "**Hans' *wirklich gute* Idee.** Danach __klar__ und ***beides***.",
            "Ein `literal **Code** Satz.` und ``code ` drin``. Ende.",
            "Erster Satz. [**Zweiter 😀 Satz**](https://example.org/a_(b)?next=https://other.example \"Titel (lang)\"). Dritter Satz.",
            "[Bücher 😀](HTTPS://bücher.example/überblick) 中文 e\u0301.",
            "Info: https://example.org/a_(b_(c))?x=1. Mehr auf www.example.net!",
            "[Route](<https://example.org/a_(b)> 'Titel') und <https://second.example>.",
            "Erledigt. [](https://example.org) und [https://label.example/a](https://target.example).",
            "\\*wörtlich\\* \\[kein Link] und \\`kein Code\\`. Hans' Text.",
            "Hallo\n\n## Ein Titel\n\n- **Erstens**\n2. Zweitens\n\n> Ein Zitat\n\nDanach.",
            "Ein Absatz\nmit zweiter Zeile.\n\nNächster Absatz.",
            "Vorher.\n\n```kotlin\nval x = \"**literal**\"\nhttps://example.org/a\n```\n\nNachher.",
            "~~~text\nHallo.\n~~~\nDanach.",
            "<b>Hallo</b> ![ein **Baum**](https://image.example/photo.png).",
            "**nicht geschlossen", "[unfertig", "[Datei](/tmp/notes.md)",
            "[kein Link](javascript:alert(1)) und Text.", "__", "***", "#", "1.", "42",
        )
        for (source in sources) assertStablePrefixes(source)
    }

    @Test(timeout = 5_000)
    fun deterministicCombinationsAlsoKeepEveryCommittedSpeechPrefix() {
        val random = Random(20260829)
        val fragments = listOf(
            "Hallo.", "**wichtig**", "*kurz*", "`**Code**`", "__ruhig__", "Hans' Idee",
            "[Quelle](https://example.org/a_(b) \"Titel\")", "https://other.example/private?q=value.",
            "\\*literal\\*", "[offen", "**offen", "<b>Text</b>", "😀", "中文", "file_name", "42",
            "## Titel", "- Eintrag", "2. Eintrag", "> Zitat", "~~~text\nCode\n~~~", "**", "_", "\\",
        )
        val separators = listOf(" ", "\n", "\n\n", ". ")
        repeat(120) {
            val source = buildString {
                repeat(4) { index ->
                    if (index > 0) append(separators[random.nextInt(separators.size)])
                    append(fragments[random.nextInt(fragments.size)])
                }
            }
            assertStablePrefixes(source)
        }
    }

    @Test
    fun originalInputIsNotMutated() {
        val source = "**Text** [Quelle](https://example.org/secret?q=value)"
        val original = source.toCharArray()
        AssistantMarkdown.parse(source)
        assertEquals(String(original), source)
    }

    @Test(timeout = 5_000)
    fun adversarialTextDoesNotRecursivelyParseOrTruncate() {
        val plain = "Unverändert 😀 Dateiname_123.txt\n".repeat(8_000)
        assertEquals(plain.trimEnd(), AssistantMarkdown.parse(plain).spokenText)
        val brackets = "[".repeat(20_000) + "Inhalt" + "]".repeat(20_000)
        assertEquals(brackets, AssistantMarkdown.parse(brackets).plainText)
        val malformed = "[Text](\"".repeat(8_000) + " Inhalt bleibt erhalten."
        assertTrue(AssistantMarkdown.parse(malformed).plainText.endsWith(" Inhalt bleibt erhalten."))
        val angles = "<".repeat(30_000) + "Inhalt"
        assertEquals(angles, AssistantMarkdown.parse(angles).plainText)
    }

    private fun assertStablePrefixes(source: String) {
        val final = AssistantMarkdown.parse(source).spokenText
        var previous = ""
        for (end in 0..source.length) {
            val projected = AssistantMarkdown.parse(source.substring(0, end), complete = false).spokenText
            assertTrue("Final prefix mismatch at $end of <$source>: <$projected> vs <$final>", final.startsWith(projected))
            assertTrue("Committed prefix rewritten at $end of <$source>: <$previous> -> <$projected>", projected.startsWith(previous))
            assertFalse(projected.lastOrNull()?.isHighSurrogate() == true)
            previous = projected
        }
        assertTrue("Finalization rewrote speech for <$source>", final.startsWith(previous))
    }
}
