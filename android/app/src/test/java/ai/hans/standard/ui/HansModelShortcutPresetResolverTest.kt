package ai.hans.standard.ui

import ai.hans.standard.codex.CodexModel
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.phone.keys.HansModelPreset
import org.junit.Assert.*
import org.junit.Test

class HansModelShortcutPresetResolverTest {
    @Test fun lunaPrefersNewestActuallyAdvertisedVersionRegardlessOfCatalogOrder() {
        val legacy = model("gpt-5.6-luna", ReasoningEffort.MAX)
        val current = model("gpt-6-luna", ReasoningEffort.MAX)
        for (catalog in listOf(listOf(legacy, current), listOf(current, legacy))) {
            assertEquals(ModelShortcutPresetTarget("gpt-6-luna", "max"), resolveModelShortcutPreset(HansModelPreset.LUNA_MAX, catalog))
        }
    }

    @Test fun unavailableOrHiddenCurrentLunaRetainsAdvertisedLegacyFallback() {
        val legacy = model("gpt-5.6-luna", ReasoningEffort.MAX)
        for (catalog in listOf(listOf(legacy), listOf(model("gpt-6-luna", ReasoningEffort.MAX).copy(hidden = true), legacy))) {
            assertEquals(ModelShortcutPresetTarget("gpt-5.6-luna", "max"), resolveModelShortcutPreset(HansModelPreset.LUNA_MAX, catalog))
        }
    }

    @Test fun namedPresetNeverSilentlyDowngradesToAnAdvertisedDefaultEffort() {
        assertNull(resolveModelShortcutPreset(HansModelPreset.LUNA_MAX, listOf(model("gpt-6-luna", ReasoningEffort.MEDIUM))))
        assertNull(resolveModelShortcutPreset(HansModelPreset.ASTRA_ULTRA, listOf(model("gpt-6-astra", ReasoningEffort.HIGH))))
        assertEquals(ModelShortcutPresetTarget("gpt-5.6-luna", "max"), resolveModelShortcutPreset(HansModelPreset.LUNA_MAX,
            listOf(model("gpt-6-luna", ReasoningEffort.MEDIUM), model("gpt-5.6-luna", ReasoningEffort.MAX))))
    }

    @Test fun astraRequiresTheExactAdvertisedAstraUltraAndNeverSubstitutesSol() {
        assertNull(resolveModelShortcutPreset(HansModelPreset.ASTRA_ULTRA, listOf(model("gpt-6.1-sol", ReasoningEffort.ULTRA))))
        assertNull(resolveModelShortcutPreset(HansModelPreset.ASTRA_ULTRA, listOf(model("gpt-6-astra", ReasoningEffort.ULTRA).copy(hidden = true))))
        assertEquals(ModelShortcutPresetTarget("gpt-6-astra", "ultra"), resolveModelShortcutPreset(HansModelPreset.ASTRA_ULTRA,
            listOf(model("gpt-6-astra", ReasoningEffort.ULTRA))))
    }

    @Test fun emptyCatalogCannotInventAnAvailablePreset() {
        HansModelPreset.entries.forEach { assertNull(resolveModelShortcutPreset(it, emptyList())) }
    }

    private fun model(id: String, effort: ReasoningEffort) = CodexModel(id, id, id, "fixture", false, false,
        effort, setOf(effort), null, emptyList())
}
