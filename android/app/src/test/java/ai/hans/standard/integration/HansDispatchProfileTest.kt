package ai.hans.standard.integration

import ai.hans.standard.codex.AppServerRequests
import ai.hans.standard.codex.ApprovalPolicy
import ai.hans.standard.codex.CodexInput
import ai.hans.standard.codex.DispatchSandbox
import ai.hans.standard.codex.Personality
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.codex.ReasoningSummary
import ai.hans.standard.codex.RequestId
import ai.hans.standard.settings.HansSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class HansDispatchProfileTest {
    @Test
    fun everySelectionCarriesApprovedStyleAndYoloWithoutChangingModelEffortOrFast() {
        val selections = listOf(
            DispatchSelection("gpt-5.6-luna", ReasoningEffort.MAX),
            DispatchSelection("gpt-5.6-sol", ReasoningEffort.ULTRA, HansSettings.FAST_SERVICE_TIER),
            DispatchSelection("gpt-5.6-terra", ReasoningEffort.HIGH),
        )
        selections.forEach { selection ->
            val options = selection.toOptions("/private/work")
            assertEquals(selection.model, options.model)
            assertEquals(selection.effort, options.effort)
            assertEquals(selection.serviceTier, options.serviceTier)
            assertEquals(Personality.FRIENDLY, options.personality)
            assertEquals(ReasoningSummary.CONCISE, options.reasoningSummary)
            assertEquals(ApprovalPolicy.NEVER, options.approvalPolicy)
            assertEquals(DispatchSandbox.DANGER_FULL_ACCESS, options.sandbox)
        }
    }

    @Test
    fun threadCreationSendsNonDefaultEffortThroughTheSupportedConfigOverride() {
        listOf(
            DispatchSelection("gpt-5.6-sol", ReasoningEffort.ULTRA),
            DispatchSelection("gpt-5.6-luna", ReasoningEffort.LOW),
        ).forEach { selection ->
            val request = AppServerRequests.threadStart(
                RequestId.Number(1), selection.toOptions("/private/work"),
            )
            val params = JSONObject(request.json).getJSONObject("params")
            assertEquals(selection.model, params.getString("model"))
            assertEquals(
                selection.effort.wireValue,
                params.getJSONObject("config").getString("model_reasoning_effort"),
            )
            assertEquals("friendly", params.getString("personality"))
        }
    }

    @Test
    fun nextTurnOnARetainedThreadExplicitlyAppliesStyleAndSelectedDispatch() {
        val request = AppServerRequests.turnStart(
            id = RequestId.Number(1),
            threadId = "retained-thread",
            input = listOf(CodexInput.Text("Wie geht es dir?")),
            options = DispatchSelection(
                "gpt-5.6-sol", ReasoningEffort.ULTRA, HansSettings.FAST_SERVICE_TIER,
            ).toOptions("/private/work"),
        )
        val params = JSONObject(request.json).getJSONObject("params")
        assertEquals("gpt-5.6-sol", params.getString("model"))
        assertEquals("ultra", params.getString("effort"))
        assertEquals("priority", params.getString("serviceTier"))
        assertEquals("concise", params.getString("summary"))
        assertEquals("friendly", params.getString("personality"))
        assertEquals("never", params.getString("approvalPolicy"))
        assertEquals("dangerFullAccess", params.getJSONObject("sandboxPolicy").getString("type"))
    }
}
