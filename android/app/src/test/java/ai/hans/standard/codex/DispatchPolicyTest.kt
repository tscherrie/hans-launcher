package ai.hans.standard.codex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class DispatchPolicyTest {
    private val catalog = ModelCatalog(
        listOf(
            model(
                id = "gpt-6-astra",
                efforts = setOf(ReasoningEffort.MEDIUM, ReasoningEffort.MAX),
                defaultEffort = ReasoningEffort.MEDIUM,
            ),
            model(
                id = "gpt-5.6-luna",
                efforts = setOf(ReasoningEffort.MEDIUM, ReasoningEffort.MAX),
                defaultEffort = ReasoningEffort.MAX,
            ),
            model(
                id = "gpt-5.6-sol",
                efforts = setOf(ReasoningEffort.MAX, ReasoningEffort.ULTRA),
                defaultEffort = ReasoningEffort.ULTRA,
            ),
        ),
    )

    @Test
    fun uiPreferenceChangeCannotSilentlySteerAnExistingSolTurn() {
        val activeSolTurn = ActiveTurn(
            threadId = "thread-1",
            turnId = "turn-sol",
            effectiveOptions = DispatchOptions.SOL_ULTRA,
        )

        val plan = DispatchPolicy.plan(
            id = RequestId.Number(1),
            threadId = "thread-1",
            activeTurn = activeSolTurn,
            input = listOf(CodexInput.Text("Benutze jetzt Astra")),
            desiredOptions = DispatchOptions.DEFAULT,
            catalog = catalog,
        )

        assertTrue(plan is DispatchPlan.RequiresNewTurn)
        val boundary = plan as DispatchPlan.RequiresNewTurn
        assertEquals(
            setOf(OptionDifference.MODEL, OptionDifference.EFFORT),
            boundary.differences,
        )
        assertEquals(DispatchOptions.DEFAULT, boundary.desiredOptions)
    }

    @Test
    fun identicalEffectiveOptionsMaySteer() {
        val active = ActiveTurn("thread-1", "turn-sol", DispatchOptions.SOL_ULTRA)

        val plan = DispatchPolicy.plan(
            id = RequestId.Number(2),
            threadId = "thread-1",
            activeTurn = active,
            input = listOf(CodexInput.Text("Noch etwas")),
            desiredOptions = DispatchOptions.SOL_ULTRA,
            catalog = catalog,
        )

        assertTrue(plan is DispatchPlan.Steer)
        val params = JSONObject((plan as DispatchPlan.Steer).request.json)
            .getJSONObject("params")
        assertEquals("turn-sol", params.getString("expectedTurnId"))
        assertFalse(params.has("model"))
        assertFalse(params.has("effort"))
    }

    @Test
    fun noActiveTurnStartsWithTheSelectedAstraMediumOptions() {
        val plan = DispatchPolicy.plan(
            id = RequestId.Number(3),
            threadId = "thread-1",
            activeTurn = null,
            input = listOf(CodexInput.Text("Wie geht es dir?")),
            desiredOptions = DispatchOptions.DEFAULT,
            catalog = catalog,
        )

        assertTrue(plan is DispatchPlan.StartTurn)
        val params = JSONObject((plan as DispatchPlan.StartTurn).request.json)
            .getJSONObject("params")
        assertEquals("gpt-6-astra", params.getString("model"))
        assertEquals("medium", params.getString("effort"))
        assertEquals("never", params.getString("approvalPolicy"))
        assertEquals(
            "dangerFullAccess",
            params.getJSONObject("sandboxPolicy").getString("type"),
        )
    }

    @Test
    fun everyNonSteerableOptionForcesATurnBoundary() {
        val base = DispatchOptions(
            model = "gpt-5.6-sol",
            effort = ReasoningEffort.ULTRA,
            serviceTier = CodexServiceTier.STANDARD,
            approvalPolicy = ApprovalPolicy.ON_REQUEST,
            permissionsProfile = "standard-granted",
            cwd = "/data/user/0/ai.hans.standard/files/workspace",
            personality = Personality.FRIENDLY,
            reasoningSummary = ReasoningSummary.CONCISE,
        )
        val variants = listOf(
            base.copy(effort = ReasoningEffort.MAX) to OptionDifference.EFFORT,
            base.copy(serviceTier = "priority") to OptionDifference.SERVICE_TIER,
            base.copy(approvalPolicy = ApprovalPolicy.NEVER) to OptionDifference.APPROVAL_POLICY,
            base.copy(permissionsProfile = "limited") to OptionDifference.PERMISSIONS_PROFILE,
            base.copy(cwd = "/data/user/0/ai.hans.standard/files/other") to
                OptionDifference.WORKING_DIRECTORY,
            base.copy(personality = Personality.PRAGMATIC) to OptionDifference.PERSONALITY,
            base.copy(reasoningSummary = ReasoningSummary.DETAILED) to
                OptionDifference.REASONING_SUMMARY,
        )

        variants.forEachIndexed { index, (desired, expectedDifference) ->
            val expandedCatalog = if (desired.serviceTier == "priority") {
                ModelCatalog(
                    catalog.models.map { model ->
                        if (model.wireModel == base.model) {
                            model.copy(
                                serviceTiers = listOf(
                                    ModelServiceTier("priority", "Priority", "Fast"),
                                ),
                            )
                        } else {
                            model
                        }
                    },
                )
            } else {
                catalog
            }
            val plan = DispatchPolicy.plan(
                id = RequestId.Number(10L + index),
                threadId = "thread-1",
                activeTurn = ActiveTurn("thread-1", "turn-sol", base),
                input = listOf(CodexInput.Text("Test $index")),
                desiredOptions = desired,
                catalog = expandedCatalog,
            )
            assertTrue(plan is DispatchPlan.RequiresNewTurn)
            assertTrue(
                (plan as DispatchPlan.RequiresNewTurn).differences.contains(expectedDifference),
            )
        }
    }

    @Test
    fun sandboxDifferenceCannotSteerAndPermissionsCannotBeCombinedWithSandbox() {
        val permissions = DispatchOptions(
            model = "gpt-5.6-sol",
            effort = ReasoningEffort.ULTRA,
            approvalPolicy = ApprovalPolicy.NEVER,
            permissionsProfile = "standard-granted",
        )
        val sandbox = permissions.copy(
            permissionsProfile = null,
            sandbox = DispatchSandbox.DANGER_FULL_ACCESS,
        )

        val plan = DispatchPolicy.plan(
            id = RequestId.Number(99),
            threadId = "thread-1",
            activeTurn = ActiveTurn("thread-1", "turn-sol", permissions),
            input = listOf(CodexInput.Text("YOLO")),
            desiredOptions = sandbox,
            catalog = catalog,
        )

        assertTrue(plan is DispatchPlan.RequiresNewTurn)
        assertEquals(
            setOf(OptionDifference.PERMISSIONS_PROFILE, OptionDifference.SANDBOX),
            (plan as DispatchPlan.RequiresNewTurn).differences,
        )
        assertThrows(IllegalArgumentException::class.java) {
            DispatchOptions(
                model = "gpt-5.6-luna",
                effort = ReasoningEffort.MAX,
                permissionsProfile = "standard-granted",
                sandbox = DispatchSandbox.DANGER_FULL_ACCESS,
            )
        }
    }

    private fun model(
        id: String,
        efforts: Set<ReasoningEffort>,
        defaultEffort: ReasoningEffort,
    ): CodexModel = CodexModel(
        catalogId = id,
        wireModel = id,
        displayName = id,
        description = "",
        hidden = false,
        isDefault = id.endsWith("luna"),
        defaultEffort = defaultEffort,
        supportedEfforts = efforts,
        defaultServiceTier = null,
        serviceTiers = emptyList(),
    )
}
