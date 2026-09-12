package ai.hans.standard.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalSentenceSegmenterTest {
    private val segmenter = NaturalSentenceSegmenter()

    @Test
    fun streamsOnlyCompleteSentencesAndFlushesFinalTail() {
        assertEquals(
            listOf("Hallo Welt."),
            texts(segmenter.segment("Hallo Welt. Noch nicht", isFinal = false)),
        )
        assertEquals(
            listOf("Hallo Welt.", "Noch nicht"),
            texts(segmenter.segment("Hallo Welt. Noch nicht", isFinal = true)),
        )
    }

    @Test
    fun punctuationAtNonFinalSnapshotEndIsImmediatelySpeakable() {
        assertEquals(
            listOf("Bist du da?"),
            texts(segmenter.segment("Bist du da?", isFinal = false)),
        )
        assertEquals(
            listOf("Bist du da?", "Ja."),
            texts(segmenter.segment("Bist du da? Ja.", isFinal = false)),
        )
    }

    @Test
    fun keepsClosingQuotesWithSentence() {
        assertEquals(
            listOf("Er sagte: \u201eHallo!\u201c", "Danach ging er."),
            texts(segmenter.segment("Er sagte: \u201eHallo!\u201c Danach ging er.", isFinal = true)),
        )
    }

    @Test
    fun avoidsCommonAbbreviationAndDecimalFalsePositives() {
        assertEquals(
            listOf("Dr. Meyer hat 3.14 Punkte.", "Das reicht."),
            texts(
                segmenter.segment(
                    "Dr. Meyer hat 3.14 Punkte. Das reicht.",
                    isFinal = true,
                ),
            ),
        )
    }

    @Test
    fun newlineFlushesVisibleStatusWithoutFinalFlag() {
        assertEquals(
            listOf("Ich schaue kurz nach", "Einen Moment."),
            texts(segmenter.segment("Ich schaue kurz nach\nEinen Moment. Weiter", isFinal = false)),
        )
    }

    @Test
    fun sourceOffsetsAllowSafeIncrementalRebuild() {
        val input = "  Eins.   Zwei. Drei"
        val firstPass = segmenter.segment(input, isFinal = false)
        assertEquals(listOf("Eins.", "Zwei."), texts(firstPass))
        val committedEnd = firstPass.first().sourceEndExclusive
        assertEquals(
            listOf("Zwei.", "Drei"),
            texts(segmenter.segment(input, fromIndex = committedEnd, isFinal = true)),
        )
    }

    @Test
    fun repeatedPunctuationFormsOneNaturalSegment() {
        assertEquals(
            listOf("Wirklich?!", "Ja\u2026"),
            texts(segmenter.segment("Wirklich?! Ja\u2026", isFinal = true)),
        )
    }

    private fun texts(spans: List<SentenceSpan>): List<String> = spans.map(SentenceSpan::text)
}
