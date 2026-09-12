package ai.hans.standard.plugins.runtime

import java.net.URI

private const val MAX_PLUGIN_ID_CHARS = 128
private const val MAX_ID_CHARS = 96
private const val MAX_SKILLS = 128
private const val MAX_HOOKS = 128
private const val MAX_ANDROID_TOOLS = 128
private const val MAX_MCP_SERVERS = 16
private const val MAX_MCP_TOOLS = 64
private const val MAX_ENDPOINT_CHARS = 2_048

private val PLUGIN_ID = Regex("[a-z][a-z0-9._-]{0,127}")
private val LOGICAL_ID = Regex("[a-z][a-z0-9._:-]{0,95}")
private val SKILL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9 ._/-]{0,127}")
private val MCP_TOOL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")

/**
 * Optional root-free execution surface layered on top of an ordinary Codex plugin.
 *
 * Skills and native hooks remain owned by Codex App Server. Hans only correlates their exact
 * selectors with fresh App Server evidence; it never reads or executes instruction files itself.
 * Android actions and Hans hooks reference registrations compiled into the signed APK. A plugin
 * cannot place a class name, path, command line or executable in this contract.
 */
internal data class PluginSurfaceManifest(
    val pluginId: String,
    val nativeSkills: List<NativeSkillRequirement>,
    val nativeHooks: List<NativeHookRequirement>,
    val androidTools: List<AndroidToolRequirement>,
    val hooks: List<DeclarativeHookRequirement>,
    val remoteMcpServers: List<RemoteMcpRequirement>,
) {
    init {
        require(pluginId.length <= MAX_PLUGIN_ID_CHARS && PLUGIN_ID.matches(pluginId)) {
            "Invalid plugin id"
        }
        require(nativeSkills.size <= MAX_SKILLS) { "Too many native skills" }
        require(nativeHooks.size <= MAX_HOOKS) { "Too many native hooks" }
        require(androidTools.size <= MAX_ANDROID_TOOLS) { "Too many Android tools" }
        require(hooks.size <= MAX_HOOKS) { "Too many Hans hooks" }
        require(remoteMcpServers.size <= MAX_MCP_SERVERS) { "Too many remote MCP servers" }
        requireUnique(nativeSkills.map(NativeSkillRequirement::id), "native skill")
        requireUnique(nativeHooks.map(NativeHookRequirement::id), "native hook")
        requireUnique(androidTools.map(AndroidToolRequirement::id), "Android tool")
        requireUnique(hooks.map(DeclarativeHookRequirement::id), "Hans hook")
        requireUnique(remoteMcpServers.map(RemoteMcpRequirement::id), "remote MCP server")
        requireUnique(
            nativeSkills.map(NativeSkillRequirement::id) +
                nativeHooks.map(NativeHookRequirement::id) +
                androidTools.map(AndroidToolRequirement::id) +
                hooks.map(DeclarativeHookRequirement::id) +
                remoteMcpServers.map(RemoteMcpRequirement::id),
            "plugin surface requirement",
        )
    }
}

internal data class NativeSkillRequirement(
    val id: String,
    /** Exact App Server skill name, never a path supplied to Hans. */
    val name: String,
    val required: Boolean,
) {
    init {
        requireLogicalId(id, "Native skill id")
        require(name.length <= 128 && SKILL_NAME.matches(name) && ".." !in name) {
            "Invalid native skill name"
        }
    }
}

internal data class NativeHookRequirement(
    val id: String,
    /** Exact opaque key and event name proven by App Server. */
    val key: String,
    val eventName: String,
    val required: Boolean,
) {
    init {
        requireLogicalId(id, "Native hook id")
        requireLogicalId(key, "Native hook key")
        requireLogicalId(eventName, "Native hook event")
    }
}

internal data class AndroidToolRequirement(
    val id: String,
    /** Identifier of a trusted registration compiled into the signed Hans APK. */
    val bindingId: String,
    val required: Boolean,
) {
    init {
        requireLogicalId(id, "Android tool id")
        requireLogicalId(bindingId, "Android tool binding id")
    }
}

internal enum class HansHookEvent(val wireName: String) {
    TURN_STARTED("turn_started"),
    TURN_COMPLETED("turn_completed"),
    USER_SETUP_COMPLETED("user_setup_completed"),
    AUTOMATION_STARTED("automation_started"),
    AUTOMATION_COMPLETED("automation_completed"),
    NETWORK_RESTORED("network_restored"),
    NOTIFICATION_ACCEPTED("notification_accepted"),
}

internal data class DeclarativeHookRequirement(
    val id: String,
    val event: HansHookEvent,
    /** Identifier of a trusted action compiled into the signed Hans APK. */
    val actionId: String,
    val required: Boolean,
) {
    init {
        requireLogicalId(id, "Hans hook id")
        requireLogicalId(actionId, "Hans hook action id")
    }
}

@JvmInline
internal value class OAuthCredentialHandle(val value: String) {
    init {
        // Handles are deliberately opaque and non-reversible. Tokens, header fragments and URLs
        // cannot accidentally be accepted as a handle or projected to diagnostics.
        require(value.length == 64 && value.all { it in "0123456789abcdef" }) {
            "OAuth credential handle must be an opaque SHA-256 value"
        }
    }

    override fun toString(): String = "[oauth-handle]"
}

internal data class RemoteMcpRequirement(
    val id: String,
    val endpoint: String,
    val oauthHandle: OAuthCredentialHandle?,
    val allowedTools: Set<String>,
    val required: Boolean,
    val requestTimeoutMillis: Long,
    val maxResponseBytes: Int,
) {
    init {
        requireLogicalId(id, "Remote MCP id")
        require(endpoint.length in 1..MAX_ENDPOINT_CHARS) { "Remote MCP endpoint is too long" }
        val uri = URI(endpoint)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Remote MCP requires HTTPS" }
        require(uri.rawUserInfo == null && uri.fragment == null && !uri.host.isNullOrBlank()) {
            "Invalid remote MCP endpoint"
        }
        require(uri.port in -1..65535 && uri.port != 0) { "Invalid remote MCP port" }
        require(allowedTools.isNotEmpty() && allowedTools.size <= MAX_MCP_TOOLS) {
            "Remote MCP tool allowlist is invalid"
        }
        allowedTools.forEach {
            require(it.length <= 128 && MCP_TOOL_NAME.matches(it) && ".." !in it) {
                "Invalid remote MCP tool name"
            }
        }
        require(requestTimeoutMillis in 1_000L..120_000L) { "Remote MCP timeout is invalid" }
        require(maxResponseBytes in 1_024..(4 * 1024 * 1024)) {
            "Remote MCP response limit is invalid"
        }
    }
}

internal data class CodexNativeSurfaceEvidence(
    val enabledSkillNames: Set<String>,
    val enabledHooks: Set<Pair<String, String>>,
    val complete: Boolean,
) {
    init {
        require(enabledSkillNames.size <= MAX_SKILLS)
        require(enabledHooks.size <= MAX_HOOKS)
    }
}

private fun requireLogicalId(value: String, label: String) {
    require(value.length <= MAX_ID_CHARS && LOGICAL_ID.matches(value)) { "$label is invalid" }
}

private fun requireUnique(values: List<String>, label: String) {
    require(values.distinct().size == values.size) { "Duplicate $label id" }
}
