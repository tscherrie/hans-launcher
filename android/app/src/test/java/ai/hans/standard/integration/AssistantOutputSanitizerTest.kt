package ai.hans.standard.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantOutputSanitizerTest {
    @Test
    fun plainTextNumbersPathsAndFormattingAreUnchanged() {
        val source = "Hallo!\n  Version 1.25, 12,50 Euro; Bericht.pdf und README.md.\n" +
            "/tmp/www.example.txt, docs/www.example/report.md, ../www.test und " +
            "C:\\docs\\www.example.txt. [Datei](/tmp/Bericht.pdf) [Notiz](notes.md).\n" +
            "`sample.txt` **Wichtig**: Unicode äöü 中文 😀."
        assertEquals(source, AssistantOutputSanitizer.sanitize(source))
    }

    @Test
    fun completeOrdinaryProtocolNamesAreNotUrls() {
        for (source in listOf("h", "http", "https", "http:", "w", "ww", "www")) {
            assertEquals(source, AssistantOutputSanitizer.sanitize(source, complete = true))
        }
        assertEquals("HTTP ist ein Protokoll.", AssistantOutputSanitizer.sanitize("HTTP ist ein Protokoll."))
    }

    @Test
    fun markdownWebDestinationsBecomeLabels() {
        assertEquals(
            "Die Route und Fahrplan sind bereit.",
            AssistantOutputSanitizer.sanitize(
                "Die [Route](https://maps.example/route?a=1&b=2) und " +
                    "[Fahrplan](http://bahn.example) sind bereit.",
            ),
        )
        assertEquals("Quelle.", AssistantOutputSanitizer.sanitize("[Quelle](www.example.org/info)."))
    }

    @Test
    fun nestedUrlParenthesesRedirectsAndEscapesStayInsideTheDestination() {
        assertEquals(
            "Mehr Details!",
            AssistantOutputSanitizer.sanitize(
                "Mehr [Details](https://example.org/a_(b_(c))?next=https://other.example/a_(d))!",
            ),
        )
        assertEquals("Info.", AssistantOutputSanitizer.sanitize("[Info](https://example.org/a\\(b\\))."))
        assertEquals("Info.", AssistantOutputSanitizer.sanitize("[Info](http://[::1]:8080/a)."))
    }

    @Test
    fun markdownTitlesAndAutolinkDestinationsAreNotSpoken() {
        assertEquals(
            "Die Route ist bereit.",
            AssistantOutputSanitizer.sanitize("Die [Route](<https://example.org/a_(b)> \"eine (lange) Beschreibung\") ist bereit."),
        )
        assertEquals("Info.", AssistantOutputSanitizer.sanitize("[Info](https://example.org 'Titel mit \\'Zitat\\'')."))
    }

    @Test
    fun nestedLabelsRemainReadableWithoutRecursiveParsing() {
        assertEquals(
            "Außen innen Ende.",
            AssistantOutputSanitizer.sanitize("[Außen [innen](https://inner.example) Ende](https://outer.example)."),
        )
        assertEquals("**Route**.", AssistantOutputSanitizer.sanitize("[**Route**](https://example.org)."))
    }

    @Test
    fun imageWebDestinationKeepsAltText() {
        assertEquals("Bild: Ein Baum.", AssistantOutputSanitizer.sanitize("Bild: ![Ein Baum](https://example.org/baum.png)."))
        assertEquals("![Foto](/tmp/foto.png)", AssistantOutputSanitizer.sanitize("![Foto](/tmp/foto.png)"))
    }

    @Test
    fun bareWebUrlsAreRemovedWithoutStraySpacesOrSentenceFragments() {
        assertEquals(
            "Hier ist die Info. Mehr dazu, bitte.",
            AssistantOutputSanitizer.sanitize("Hier ist die Info. https://example.org/abc. Mehr dazu www.example.org, bitte."),
        )
        assertEquals("Siehe. Danach weiter.", AssistantOutputSanitizer.sanitize("Siehe https://example.org. Danach weiter."))
        assertEquals("A B", AssistantOutputSanitizer.sanitize("A https://example.org B"))
        assertEquals("", AssistantOutputSanitizer.sanitize("https://example.org."))
        assertEquals("", AssistantOutputSanitizer.sanitize("www.example.org, "))
    }

    @Test
    fun urlWrappersDisappearButOrdinaryParenthesesDoNot() {
        for (wrapped in listOf(
            "<https://example.org>", "(https://example.org)", "[https://example.org]",
            "`https://example.org`", "\"https://example.org\"", "'https://example.org'",
            "„https://example.org“", "«https://example.org»", "‹https://example.org›",
        )) {
            assertEquals(wrapped, "", AssistantOutputSanitizer.sanitize(wrapped))
            assertEquals(wrapped, "Mehr.", AssistantOutputSanitizer.sanitize("Mehr $wrapped."))
        }
        assertEquals("Mehr.", AssistantOutputSanitizer.sanitize("Mehr(https://example.org)."))
        assertEquals("Wert (12.5), `Datei.md`.", AssistantOutputSanitizer.sanitize("Wert (12.5), `Datei.md`."))
    }

    @Test
    fun upperCaseSchemesAndUnicodeHostNamesAreRemoved() {
        assertEquals("Fertig.", AssistantOutputSanitizer.sanitize("Fertig. HTTPS://bücher.example/überblick."))
        assertEquals("Grüße aus Sofia.", AssistantOutputSanitizer.sanitize("[Grüße aus Sofia](HTTPS://пример.example/😀)."))
    }

    @Test
    fun incompleteBarePrefixesCannotProduceDomainSentencePieces() {
        val fragments = listOf("h", "ht", "htt", "http", "http:", "http:/", "http://",
            "https", "https:", "https:/", "https://", "https://example.", "w", "ww", "www", "www.", "www.example.")
        for (fragment in fragments) {
            assertEquals(fragment, "Fertiger Satz.",
                AssistantOutputSanitizer.sanitize("Fertiger Satz. $fragment", complete = false).trimEnd())
        }
    }

    @Test
    fun aWholeSentenceAtTheEndOfAStreamSnapshotIsImmediatelyAvailable() {
        for (text in listOf("Fertig.", "Erledigt!", "Stimmt das?", "Ein Satz. Zweiter Satz.")) {
            assertEquals(text, AssistantOutputSanitizer.sanitize(text, complete = false))
        }
    }

    @Test
    fun markdownPrefixesAreHeldOnlyUntilTheLabelAndWebPrefixAreKnown() {
        for (tail in listOf("[", "[Die Route", "[Die Route]", "[Die Route](", "[Die Route](h", "[Die Route](https:/")) {
            assertEquals(tail, "Fertig. ", AssistantOutputSanitizer.sanitize("Fertig. $tail", complete = false))
        }
        assertEquals("Fertig. Die Route", AssistantOutputSanitizer.sanitize("Fertig. [Die Route](https://", complete = false))
        assertEquals("Fertig. Die Route", AssistantOutputSanitizer.sanitize("Fertig. [Die Route](https://example.org/", complete = false))
        assertEquals("Fertig. Die Route.", AssistantOutputSanitizer.sanitize("Fertig. [Die Route](https://example.org/).", complete = false))
    }

    @Test
    fun markupAndUrlAreSafeAtEveryUtf16ChunkBoundary() {
        val source = "Erster Satz. [Zweiter 😀 Satz](https://example.org/a_(b)?next=https://other.example \"Titel\"). Dritter Satz."
        val expected = "Erster Satz. Zweiter 😀 Satz. Dritter Satz."
        for (end in 0..source.length) {
            val projected = AssistantOutputSanitizer.sanitize(source.substring(0, end), complete = false)
            assertTrue("prefix $end: $projected", expected.startsWith(projected))
            assertFalse(projected.contains("example"))
            assertFalse(projected.contains("https"))
            assertFalse(projected.lastOrNull()?.isHighSurrogate() == true)
        }
        assertEquals(expected, AssistantOutputSanitizer.sanitize(source))
    }

    @Test
    fun unfinishedUrlDoesNotExposeItsTrailingDot() {
        assertEquals("Mehr", AssistantOutputSanitizer.sanitize("Mehr https://example.", complete = false))
        assertEquals("Mehr.", AssistantOutputSanitizer.sanitize("Mehr https://example.org.", complete = true))
        assertEquals("Mehr weiter.", AssistantOutputSanitizer.sanitize("Mehr https://example.org weiter.", complete = false))
    }

    @Test
    fun malformedMarkdownDoesNotConsumeTheFollowingRealSentences() {
        assertEquals(
            "Info Danach ein echter Satz. Noch einer.",
            AssistantOutputSanitizer.sanitize("[Info](https://example.org Danach ein echter Satz. Noch einer."),
        )
        assertEquals("[Keine URL und kein Ende", AssistantOutputSanitizer.sanitize("[Keine URL und kein Ende"))
        assertEquals("[Datei](notes.md", AssistantOutputSanitizer.sanitize("[Datei](notes.md"))
    }

    @Test
    fun escapedSquareBracketsAreOrdinaryTextDuringStreaming() {
        val source = "Wert \\[offen und weiter."
        assertEquals(source, AssistantOutputSanitizer.sanitize(source, complete = false))
    }

    @Test
    fun completedLocalMarkdownLinksNeverLoseTheirTargetPaths() {
        val paths = listOf("/tmp/Plan.md", "../notes/2026-08-28.md", "file:///tmp/Plan.md", "docs/www.example.md")
        for (path in paths) {
            val source = "[Mein Dokument]($path)"
            assertEquals(source, AssistantOutputSanitizer.sanitize(source))
            assertEquals(source, AssistantOutputSanitizer.sanitize(source, complete = false))
        }
    }

    @Test(timeout = 5_000)
    fun veryLongPlainTextAndDeepBracketsAreNotTruncatedOrRecursivelyParsed() {
        val plain = "Version 1.25 /tmp/www.example.txt 😀\n".repeat(20_000)
        assertEquals(plain, AssistantOutputSanitizer.sanitize(plain))
        val nested = "[".repeat(30_000) + "Text" + "]".repeat(30_000)
        assertEquals(nested, AssistantOutputSanitizer.sanitize(nested))
    }

    @Test(timeout = 5_000)
    fun veryLongUrlsAndMalformedTitlesHaveBoundedWorkWithoutDroppingOrdinaryTail() {
        val source = "Start. https://example.org/" + "x".repeat(300_000) + ". Ende."
        assertEquals("Start. Ende.", AssistantOutputSanitizer.sanitize(source))
        val malformed = "[Info](https://example.org \"".repeat(15_000) + " Ende."
        val projected = AssistantOutputSanitizer.sanitize(malformed)
        assertTrue(projected.endsWith(" Ende."))
        assertTrue(projected.length <= malformed.length)
        assertFalse(projected.contains("https://"))
    }

    @Test
    fun inputStringRemainsAvailableUnchangedForTheRawTranscript() {
        val source = "[Quelle](https://example.org/private?q=123) /tmp/notes.md"
        val originalChars = source.toCharArray()
        assertEquals("Quelle /tmp/notes.md", AssistantOutputSanitizer.sanitize(source))
        assertEquals(String(originalChars), source)
    }
}
