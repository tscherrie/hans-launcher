package ai.hans.standard.runtime

import ai.hans.standard.codex.Personality
import ai.hans.standard.codex.ReasoningSummary
import ai.hans.standard.settings.HansSettings
import org.json.JSONObject

/**
 * Owner-selected defaults for personal Hans sessions, not for the untrusted notification
 * classifier. CLI overrides take precedence over retained user/project configuration without
 * replacing CODEX_HOME, credentials, plugin configuration or conversation history.
 *
 * Model/effort are startup defaults only: each personal turn still sends the user's confirmed
 * selection explicitly. Fast, memory extraction models, compaction and tool-output limits are
 * deliberately not overridden here.
 */
internal object CodexAssistantProfile {
    const val ID = "hans-personal-2026-08-27"
    val personality: Personality = Personality.FRIENDLY
    val reasoningSummary: ReasoningSummary = ReasoningSummary.CONCISE

    private val settings: Map<String, Any> = linkedMapOf(
        "model" to HansSettings.DEFAULT_MODEL,
        "model_reasoning_effort" to HansSettings.DEFAULT_REASONING_EFFORT,
        "model_reasoning_summary" to reasoningSummary.wireValue,
        "model_verbosity" to "low",
        "personality" to personality.wireValue,
        "approval_policy" to "never",
        "sandbox_mode" to "danger-full-access",
        "web_search" to "live",
        "features.apps" to true,
        "features.multi_agent" to true,
        "features.goals" to true,
        "agents.max_concurrent_threads_per_session" to 3,
        // V2 counts the primary too; its optional field does not itself enable V2. Set both
        // limits so a retained V2 setting cannot silently permit more than three children.
        "features.multi_agent_v2.max_concurrent_threads_per_session" to 4,
        "features.memories" to true,
        "memories.generate_memories" to true,
        "memories.use_memories" to true,
        "memories.dedicated_tools" to true,
        "memories.disable_on_external_context" to false,
        "memories.min_rollout_idle_hours" to 1,
        "memories.max_unused_days" to 365,
        "memories.max_rollout_age_days" to 90,
        "memories.min_rate_limit_remaining_percent" to 10,
        "apps._default.enabled" to true,
        "apps._default.destructive_enabled" to true,
        "apps._default.open_world_enabled" to true,
        "apps._default.default_tools_approval_mode" to "approve",
        "analytics.enabled" to false,
        "otel.log_user_prompt" to false,
    )

    fun strictArguments(): List<String> = buildList {
        add("--strict-config")
        settings.forEach { (key, value) ->
            add("-c")
            add("$key=${if (value is String) JSONObject.quote(value) else value}")
        }
    }

    fun configReadRequest(requestId: String, workspacePath: String): String {
        require(requestId.isNotBlank())
        require(workspacePath.startsWith('/'))
        return JSONObject()
            .put("id", requestId)
            .put("method", "config/read")
            .put("params", JSONObject().put("includeLayers", false).put("cwd", workspacePath))
            .toString()
    }

    /**
     * Check the server's effective merged configuration, not a file or our launch intentions.
     * No raw configuration, plugin environment, token or user content leaves this boundary.
     */
    fun requireEffectiveConfig(responseJson: String, expectedRequestId: String) {
        val response = JSONObject(responseJson)
        check(response.opt("id") == expectedRequestId && !response.has("error")) {
            "Hans profile response was not a correlated success"
        }
        val config = response.getJSONObject("result").getJSONObject("config")
        settings.forEach { (key, expected) ->
            val components = key.split('.')
            val parent = components.dropLast(1).fold(config) { current, component ->
                current.optJSONObject(component)
                    ?: error("Hans profile section was not confirmed: $key")
            }
            val actual = parent.opt(components.last())
            val matches = when (expected) {
                is Int -> actual is Number && actual.toDouble() == expected.toDouble()
                is Boolean -> actual is Boolean && actual == expected
                is String -> actual is String && actual == expected
                else -> false
            }
            check(matches) { "Hans profile setting was not confirmed: $key" }
        }
    }
}
