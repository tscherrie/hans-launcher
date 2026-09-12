package ai.hans.standard.runtime

import ai.hans.standard.codex.Personality
import ai.hans.standard.codex.ReasoningSummary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAssistantProfileTest {
    @Test
    fun historicalNativeProfileFixtureCannotProveTheNewOwnerDefaults() {
        val fixture = requireNotNull(
            javaClass.getResourceAsStream("/codex/assistant-profile-0.151.0.json"),
        ).bufferedReader().use { it.readText() }
        val config = JSONObject(fixture).getJSONObject("result").getJSONObject("config")
        assertEquals("gpt-5.6-luna", config.getString("model"))
        assertEquals("max", config.getString("model_reasoning_effort"))
        assertThrows(IllegalStateException::class.java) {
            CodexAssistantProfile.requireEffectiveConfig(fixture, "hans-profile-native-probe")
        }
    }

    @Test
    fun updatedDefaultsPassWithTheOtherwiseUnchangedNativeConfigShape() {
        // Derived test input, not a new live/native observation. Preserve the source receipt.
        val fixture = requireNotNull(
            javaClass.getResourceAsStream("/codex/assistant-profile-0.151.0.json"),
        ).bufferedReader().use { it.readText() }
        val response = JSONObject(fixture)
        response.getJSONObject("result").getJSONObject("config")
            .put("model", "gpt-6-astra")
            .put("model_reasoning_effort", "medium")
        CodexAssistantProfile.requireEffectiveConfig(response.toString(), "hans-profile-native-probe")
    }

    @Test
    fun approvedProfileUsesExactValuesAndNeverEmbedsCredentials() {
        val values = overrides()
        assertEquals("--strict-config", CodexAssistantProfile.strictArguments().first())
        assertEquals(28, values.size)
        assertEquals("\"gpt-6-astra\"", values["model"])
        assertEquals("\"medium\"", values["model_reasoning_effort"])
        assertEquals("\"concise\"", values["model_reasoning_summary"])
        assertEquals("\"low\"", values["model_verbosity"])
        assertEquals("\"friendly\"", values["personality"])
        assertEquals(Personality.FRIENDLY, CodexAssistantProfile.personality)
        assertEquals(ReasoningSummary.CONCISE, CodexAssistantProfile.reasoningSummary)
        assertEquals("\"never\"", values["approval_policy"])
        assertEquals("\"danger-full-access\"", values["sandbox_mode"])
        assertEquals("\"live\"", values["web_search"])
        listOf("apps", "multi_agent", "goals", "memories").forEach {
            assertEquals("true", values["features.$it"])
        }
        assertEquals("3", values["agents.max_concurrent_threads_per_session"])
        assertEquals("4", values["features.multi_agent_v2.max_concurrent_threads_per_session"])
        assertFalse(values.containsKey("features.multi_agent_v2.enabled"))
        assertEquals("\"approve\"", values["apps._default.default_tools_approval_mode"])
        listOf("enabled", "destructive_enabled", "open_world_enabled").forEach {
            assertEquals("true", values["apps._default.$it"])
        }
        assertEquals("false", values["analytics.enabled"])
        assertEquals("false", values["otel.log_user_prompt"])
        assertFalse(values.keys.any { "secret" in it || "api_key" in it || "auth" in it })
    }

    @Test
    fun memoryRetentionAndQuotaMatchOwnerChoiceRatherThanPreviousDefaults() {
        val values = overrides()
        listOf("generate_memories", "use_memories", "dedicated_tools").forEach {
            assertEquals("true", values["memories.$it"])
        }
        assertEquals("false", values["memories.disable_on_external_context"])
        assertEquals("1", values["memories.min_rollout_idle_hours"])
        assertEquals("365", values["memories.max_unused_days"])
        assertEquals("90", values["memories.max_rollout_age_days"])
        assertEquals("10", values["memories.min_rate_limit_remaining_percent"])
    }

    @Test
    fun fastAndUpstreamSizingOrExtractionModelChoicesRemainUntouched() {
        val values = overrides()
        listOf(
            "service_tier", "model_auto_compact_token_limit", "tool_output_token_limit",
            "memories.extract_model", "memories.consolidation_model",
        ).forEach { key -> assertFalse("Do not override $key", values.containsKey(key)) }
    }

    @Test
    fun readRequestIsScopedAndDoesNotRequestCredentialBearingLayers() {
        val request = JSONObject(CodexAssistantProfile.configReadRequest("profile:1", "/private/work"))
        assertEquals("profile:1", request.getString("id"))
        assertEquals("config/read", request.getString("method"))
        assertEquals("/private/work", request.getJSONObject("params").getString("cwd"))
        assertFalse(request.getJSONObject("params").getBoolean("includeLayers"))
    }

    @Test
    fun effectiveProfileRequiresEveryTypedSettingAndExactRequestCorrelation() {
        val response = effectiveResponse()
        CodexAssistantProfile.requireEffectiveConfig(response.toString(), "profile:1")
        assertThrows(IllegalStateException::class.java) {
            CodexAssistantProfile.requireEffectiveConfig(response.toString(), "profile:2")
        }
        response.put("error", JSONObject().put("code", -32000))
        assertThrows(IllegalStateException::class.java) {
            CodexAssistantProfile.requireEffectiveConfig(response.toString(), "profile:1")
        }
    }

    @Test
    fun retainedDisabledMemoryOrWrongQuotaCannotBeReportedReady() {
        listOf("generate_memories" to false, "min_rate_limit_remaining_percent" to 25).forEach { (key, value) ->
            val response = effectiveResponse()
            response.getJSONObject("result").getJSONObject("config")
                .getJSONObject("memories").put(key, value)
            assertThrows(IllegalStateException::class.java) {
                CodexAssistantProfile.requireEffectiveConfig(response.toString(), "profile:1")
            }
        }
    }

    @Test
    fun missingOrWrongTypedValuesFailWithoutRevealingUnrelatedConfig() {
        val response = effectiveResponse()
        val config = response.getJSONObject("result").getJSONObject("config")
        config.put("private_unrelated_field", "sensitive-fixture-not-for-output")
        config.getJSONObject("memories").put("use_memories", "true")
        val failure = assertThrows(IllegalStateException::class.java) {
            CodexAssistantProfile.requireEffectiveConfig(response.toString(), "profile:1")
        }
        assertFalse(failure.message.orEmpty().contains("sensitive-fixture-not-for-output"))
        config.getJSONObject("memories").put("use_memories", true)
        config.getJSONObject("features").remove("goals")
        assertThrows(IllegalStateException::class.java) {
            CodexAssistantProfile.requireEffectiveConfig(response.toString(), "profile:1")
        }
    }

    private fun overrides(): Map<String, String> {
        val arguments = CodexAssistantProfile.strictArguments().drop(1)
        assertTrue(arguments.size % 2 == 0)
        return arguments.chunked(2).associate { pair ->
            assertEquals("-c", pair[0])
            val parts = pair[1].split('=', limit = 2)
            parts[0] to parts[1]
        }
    }

    private fun effectiveResponse(): JSONObject {
        val config = JSONObject()
        overrides().forEach { (key, literal) ->
            val path = key.split('.')
            val parent = path.dropLast(1).fold(config) { current, part ->
                current.optJSONObject(part) ?: JSONObject().also { current.put(part, it) }
            }
            val value = JSONObject("{\"value\":$literal}").get("value")
            parent.put(path.last(), value)
        }
        return JSONObject().put("id", "profile:1")
            .put("result", JSONObject().put("config", config))
    }
}
