package ai.hans.standard.integration

import ai.hans.standard.codex.CodexModel
import ai.hans.standard.codex.ModelServiceTier
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.settings.HansSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DispatchSelectionResolverTest {
    @Test
    fun currentLunaAndSolAreUsableOnlyWhenActuallyAdvertised() {
        for (id in listOf("gpt-6-luna", "gpt-6.1-sol", "gpt-6-sol")) {
            val current = model(id, ReasoningEffort.HIGH, setOf(ReasoningEffort.HIGH, ReasoningEffort.MAX))
            assertEquals(DispatchSelection(id, ReasoningEffort.MAX), resolveDispatchSelection(models + current, id, ReasoningEffort.MAX))
            assertEquals(DispatchSelection("gpt-5.6-luna", ReasoningEffort.MEDIUM),
                resolveDispatchSelection(models, id, ReasoningEffort.MEDIUM))
            assertEquals(DispatchSelection("gpt-5.6-luna", ReasoningEffort.MEDIUM),
                resolveDispatchSelection(models + current.copy(hidden = true), id, ReasoningEffort.MEDIUM))
        }
    }

    private val models = listOf(
        model(
            id = "gpt-5.6-luna",
            default = ReasoningEffort.MEDIUM,
            efforts = setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM),
            isDefault = true,
            fast = true,
        ),
        model(
            id = "gpt-5.6-terra",
            default = ReasoningEffort.HIGH,
            efforts = setOf(ReasoningEffort.HIGH, ReasoningEffort.MAX),
        ),
        model(
            id = "gpt-5.6-sol",
            default = ReasoningEffort.ULTRA,
            efforts = setOf(ReasoningEffort.MAX, ReasoningEffort.ULTRA),
        ),
    )

    @Test
    fun astraUsesOnlyItsAdvertisedEffortsAndServiceTier() {
        val astra = model(
            id = "gpt-6-astra",
            default = ReasoningEffort.HIGH,
            efforts = setOf(ReasoningEffort.HIGH, ReasoningEffort.ULTRA),
            fast = true,
        )
        assertEquals(
            DispatchSelection("gpt-6-astra", ReasoningEffort.ULTRA, HansSettings.FAST_SERVICE_TIER),
            resolveDispatchSelection(
                models + astra, "gpt-6-astra", ReasoningEffort.ULTRA, HansSettings.FAST_SERVICE_TIER,
            ),
        )
        assertEquals(
            DispatchSelection("gpt-6-astra", ReasoningEffort.HIGH),
            resolveDispatchSelection(models + astra, "gpt-6-astra", ReasoningEffort.MEDIUM),
        )
        for (catalog in listOf(models, models + astra.copy(hidden = true))) {
            assertEquals(
                DispatchSelection("gpt-5.6-luna", ReasoningEffort.MEDIUM),
                resolveDispatchSelection(catalog, "gpt-6-astra", ReasoningEffort.MEDIUM),
            )
        }
    }

    @Test
    fun lunaMaxFallsBackToAdvertisedDefaultWhenMaxIsUnavailable() {
        assertEquals(
            DispatchSelection("gpt-5.6-luna", ReasoningEffort.MEDIUM),
            resolveDispatchSelection(models, "gpt-5.6-luna", ReasoningEffort.MAX),
        )
    }

    @Test
    fun switchingModelsRetainsCurrentEffortOnlyWhenTargetSupportsIt() {
        assertEquals(
            DispatchSelection("gpt-5.6-terra", ReasoningEffort.HIGH),
            resolveDispatchSelection(models, "gpt-5.6-terra", ReasoningEffort.MEDIUM),
        )
        assertEquals(
            DispatchSelection("gpt-5.6-terra", ReasoningEffort.MAX),
            resolveDispatchSelection(models, "gpt-5.6-terra", ReasoningEffort.MAX),
        )
        assertEquals(
            DispatchSelection("gpt-5.6-sol", ReasoningEffort.ULTRA),
            resolveDispatchSelection(models, "gpt-5.6-sol", ReasoningEffort.HIGH),
        )
    }

    @Test
    fun missingModelUsesAdvertisedDefaultAndNeverInventsAnEffort() {
        assertEquals(
            DispatchSelection("gpt-5.6-luna", ReasoningEffort.LOW),
            resolveDispatchSelection(models, "gpt-5.6-retired", ReasoningEffort.LOW),
        )
        assertNull(
            resolveDispatchSelection(
                listOf(
                    model(
                        id = "gpt-5.6-luna",
                        default = ReasoningEffort.MINIMAL,
                        efforts = setOf(ReasoningEffort.MINIMAL),
                        isDefault = true,
                    ),
                ),
                "gpt-5.6-luna",
                ReasoningEffort.MAX,
            ),
        )
    }

    @Test
    fun effortChoicesFollowStableHansOrderButOnlyContainAdvertisedValues() {
        assertEquals(
            listOf(ReasoningEffort.HIGH, ReasoningEffort.MAX),
            supportedReasoningEfforts(models, "gpt-5.6-terra"),
        )
        assertEquals(
            listOf(ReasoningEffort.MAX, ReasoningEffort.ULTRA),
            supportedReasoningEfforts(models, "gpt-5.6-sol"),
        )
        assertEquals(emptyList<ReasoningEffort>(), supportedReasoningEfforts(models, "unknown"))
    }

    @Test
    fun fastModeIsRetainedOnlyWhenTheSelectedModelAdvertisesPriority() {
        assertEquals(
            DispatchSelection(
                "gpt-5.6-luna",
                ReasoningEffort.MEDIUM,
                HansSettings.FAST_SERVICE_TIER,
            ),
            resolveDispatchSelection(
                models,
                "gpt-5.6-luna",
                ReasoningEffort.MEDIUM,
                HansSettings.FAST_SERVICE_TIER,
            ),
        )
        assertEquals(
            DispatchSelection("gpt-5.6-terra", ReasoningEffort.HIGH),
            resolveDispatchSelection(
                models,
                "gpt-5.6-terra",
                ReasoningEffort.HIGH,
                HansSettings.FAST_SERVICE_TIER,
            ),
        )
    }

    @Test
    fun standardIsExplicitEvenThoughModelListDoesNotAdvertiseTheDefaultSentinel() {
        assertEquals(
            DispatchSelection(
                "gpt-5.6-terra",
                ReasoningEffort.HIGH,
                HansSettings.DEFAULT_SERVICE_TIER,
            ),
            resolveDispatchSelection(
                models,
                "gpt-5.6-terra",
                ReasoningEffort.HIGH,
                HansSettings.DEFAULT_SERVICE_TIER,
            ),
        )
        assertEquals(
            HansSettings.DEFAULT_SERVICE_TIER,
            resolveDispatchSelection(
                models,
                "gpt-5.6-luna",
                ReasoningEffort.MEDIUM,
                requestedServiceTier = null,
            )?.serviceTier,
        )
    }

    private fun model(
        id: String,
        default: ReasoningEffort,
        efforts: Set<ReasoningEffort>,
        isDefault: Boolean = false,
        fast: Boolean = false,
    ) = CodexModel(
        catalogId = id,
        wireModel = id,
        displayName = id,
        description = "fixture",
        hidden = false,
        isDefault = isDefault,
        defaultEffort = default,
        supportedEfforts = efforts,
        defaultServiceTier = null,
        serviceTiers = if (fast) {
            listOf(ModelServiceTier(HansSettings.FAST_SERVICE_TIER, "Fast", "1.5x speed"))
        } else {
            emptyList()
        },
    )
}
