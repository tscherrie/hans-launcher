package ai.hans.standard.ui

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class IdleHeaderEvidenceTest {
    private fun header(vararg descriptions: String) = IdleHeaderEvidence(true, true, true, descriptions.toMutableList())

    @Test fun mergedParentMayExposeDescriptionOnItsOwnVirtualChild() {
        // Parent contentDescription=null; traversal collects its visible virtual child's label.
        requireIdleHeader(listOf(header("Hans. Anrufdialog öffnen", "Hans ist nicht bereit")), false)
        requireIdleHeader(listOf(header("Hans. Live Voice beenden", "Live Voice aktiv")), true)
    }

    @Test fun descriptionOnParentItselfAlsoMatches() {
        requireIdleHeader(listOf(header("Hans. Anrufdialog öffnen")), false)
    }

    @Test fun oppositePhaseAndPartialLabelsCannotSatisfyHeader() {
        for (descriptions in listOf(arrayOf("Hans. Live Voice beenden"), arrayOf("Anrufdialog öffnen"),
            arrayOf("Hans. Anrufdialog öffnen extra"), emptyArray())) {
            assertThrows(IllegalStateException::class.java) { requireIdleHeader(listOf(header(*descriptions)), false) }
        }
    }

    @Test fun missingDuplicateAndContradictoryHeadersFailClosed() {
        for (headers in listOf(emptyList(), listOf(header("Hans. Anrufdialog öffnen"), header("Hans. Anrufdialog öffnen")),
            listOf(header("Hans. Anrufdialog öffnen", "Hans. Live Voice beenden")),
            listOf(header("Hans. Anrufdialog öffnen", "foreign description")),
            listOf(header("Hans. Anrufdialog öffnen", "Hans. Anrufdialog öffnen")))) {
            assertThrows(IllegalStateException::class.java) { requireIdleHeader(headers, false) }
        }
    }

    @Test fun hiddenDisabledAndNonclickableTitlesFail() {
        val valid = header("Hans. Anrufdialog öffnen")
        for (invalid in listOf(valid.copy(visible = false), valid.copy(enabled = false), valid.copy(clickable = false))) {
            assertThrows(IllegalStateException::class.java) { requireIdleHeader(listOf(invalid), false) }
        }
    }

    @Test fun descriptionElsewhereInChatCannotReplaceMissingHeaderSubtreeLabel() {
        val unrelatedDescription = "Hans. Anrufdialog öffnen"
        check(unrelatedDescription.isNotEmpty())
        assertThrows(IllegalStateException::class.java) { requireIdleHeader(listOf(header()), false) }
    }

    @Test fun sixtyFifthDiagnosticNodeIsExplicitlyTruncated() {
        val value = JSONObject().put("nodes", JSONArray()).put("truncated", false)
        repeat(64) { retainIdleAccessibilityNode(value, JSONObject().put("index", it)) }
        assertFalse(value.getBoolean("truncated"))
        retainIdleAccessibilityNode(value, JSONObject().put("index", 64))
        assertTrue(value.getBoolean("truncated"))
        assertEquals(64, value.getJSONArray("nodes").length())
        assertEquals(63, value.getJSONArray("nodes").getJSONObject(63).getInt("index"))
    }
}
