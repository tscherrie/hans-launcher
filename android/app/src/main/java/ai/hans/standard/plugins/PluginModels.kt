package ai.hans.standard.plugins

import androidx.annotation.StringRes

import ai.hans.standard.codex.ProtocolLimits

@JvmInline
value class PluginHandle(val value: String) {
    init {
        require(value.length == 64 && value.all { it in "0123456789abcdef" }) {
            "Plugin handle must be an opaque SHA-256 value"
        }
    }
}

enum class PluginAuthPolicy {
    ON_INSTALL,
    ON_USE,
}

enum class PluginAvailability {
    AVAILABLE,
    DISABLED_BY_ADMIN,
}

enum class PluginInstallPolicy {
    NOT_AVAILABLE,
    AVAILABLE,
    INSTALLED_BY_DEFAULT,
}

enum class PluginDisabledReason {
    DISABLED_BY_ADMIN,
    PLAN_NOT_ELIGIBLE,
    REQUIRED_APP_UNAVAILABLE,
    UNKNOWN,
}

enum class PluginSourceKind {
    LOCAL,
    GIT,
    NPM,
    REMOTE,
}

data class PluginCard(
    val handle: PluginHandle,
    val pluginId: String,
    val name: String,
    val displayName: String?,
    val shortDescription: String?,
    val marketplaceDisplayName: String,
    val installed: Boolean,
    val enabled: Boolean,
    val availability: PluginAvailability,
    val disabledReason: PluginDisabledReason?,
    val installPolicy: PluginInstallPolicy,
    val authPolicy: PluginAuthPolicy,
    val sourceKind: PluginSourceKind,
    val logoUrl: String?,
    val logoDarkUrl: String?,
    val capabilities: List<String>,
    val featured: Boolean,
) {
    val installable: Boolean
        get() = !installed && availability == PluginAvailability.AVAILABLE &&
            installPolicy != PluginInstallPolicy.NOT_AVAILABLE
}

data class PluginAppSummary(
    val id: String,
    val name: String,
    val description: String?,
    val installUrl: String?,
)

data class PluginSkillSummary(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val displayName: String?,
    val shortDescription: String?,
    val iconSmallUrl: String?,
)

data class PluginHookSummary(
    val key: String,
    val eventName: String,
) {
    init {
        requireBoundedToken(key, "Plugin hook key")
        requireBoundedToken(eventName, "Plugin hook event")
    }
}

data class PluginDetailSnapshot(
    val handle: PluginHandle,
    val description: String?,
    val apps: List<PluginAppSummary>,
    val skills: List<PluginSkillSummary>,
    /** Exact App Server-proven selectors; never paths or executable hook bodies. */
    val hooks: List<PluginHookSummary>,
    val mcpServerCount: Int,
    val scheduledTaskCount: Int,
    val shareUrl: String?,
) {
    val hookCount: Int
        get() = hooks.size
}

data class AppCard(
    val id: String,
    val name: String,
    val description: String?,
    val installUrl: String?,
    val logoUrl: String?,
    val logoDarkUrl: String?,
    val accessible: Boolean,
    val enabled: Boolean,
    val pluginDisplayNames: List<String>,
)

data class SkillCard(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val displayName: String?,
    val shortDescription: String?,
    val iconSmallUrl: String?,
    /** Exact App Server selector; never exposed as a user-editable path. */
    val path: String? = null,
)

enum class PluginCatalogPhase {
    EMPTY,
    LOADING,
    READY,
    STALE,
    FAILED,
}

enum class PluginOperationKind {
    REFRESH_PLUGINS,
    READ_PLUGIN,
    INSTALL_PLUGIN,
    UNINSTALL_PLUGIN,
    REFRESH_MARKETPLACE,
    REFRESH_APPS,
    REFRESH_SKILLS,
    CONFIGURE_SKILL,
}

enum class PluginOperationStatus {
    PENDING,
    /** The attempted mutation stopped before any remote side effect and needs explicit UI work. */
    ACTION_REQUIRED,
    SUCCESS,
    FAILURE,
}

enum class PluginOperationFailure {
    LOCAL_INCOMPATIBLE,
    REMOTE_REJECTED,
    TRANSPORT_AMBIGUOUS,
    MALFORMED_RESPONSE,
    TARGET_NOT_FOUND,
    BUSY,
}

enum class PluginConnectionActionKind {
    CONNECT_REMOTE_MCP,
    REVIEW_REMOTE_MCP_POLICY,
    RETRY_INSTALL,
}

enum class PluginRemoteMcpPolicyEffect {
    READ_ONLY,
    MUTATING,
}

/** Public wording is deliberately weaker than an independently observed Android postcondition. */
enum class PluginRemoteMcpMutationVerification {
    SERVER_CONFIRMED,
}

data class PluginRemoteMcpDeclaredHintsSnapshot(
    val readOnly: Boolean?,
    val destructive: Boolean?,
    val idempotent: Boolean?,
    val openWorld: Boolean?,
)

/** Bounded, secret-free tool row. Remote schemas and `_meta` never leave the MCP domain. */
data class PluginRemoteMcpPolicyToolSnapshot(
    val name: String,
    val title: String?,
    val declaredHints: PluginRemoteMcpDeclaredHintsSnapshot,
    val metadataDigest: String,
    val description: String? = null,
) {
    init {
        requireBoundedToken(name, "Remote MCP tool", 128)
        title?.let { require(it.length <= 2_048 && it.none(Char::isISOControl)) }
        description?.let { require(it.length <= 1_024 && it.none(Char::isISOControl)) }
        require(metadataDigest.matches(OPAQUE_SHA_256))
    }

    override fun toString(): String = "PluginRemoteMcpPolicyToolSnapshot(metadata=redacted)"
}

/**
 * Exact one-shot review binding. Digests are correlation identities, not endpoint or credential
 * material. A mutating approval means only that the authenticated server later confirms success.
 */
data class PluginRemoteMcpPolicyReviewSnapshot(
    val configurationDigest: String,
    val sourceDigest: String,
    val catalogDigest: String,
    val policyStoreRevision: Long,
    val tools: List<PluginRemoteMcpPolicyToolSnapshot>,
    val mutatingVerification: PluginRemoteMcpMutationVerification =
        PluginRemoteMcpMutationVerification.SERVER_CONFIRMED,
) {
    init {
        require(configurationDigest.matches(OPAQUE_SHA_256))
        require(sourceDigest.matches(OPAQUE_SHA_256))
        require(catalogDigest.matches(OPAQUE_SHA_256))
        require(policyStoreRevision >= 0L)
        require(tools.isNotEmpty() && tools.size <= 64)
        require(tools == tools.sortedBy(PluginRemoteMcpPolicyToolSnapshot::name))
        require(tools.map(PluginRemoteMcpPolicyToolSnapshot::name).distinct().size == tools.size)
    }

    override fun toString(): String =
        "PluginRemoteMcpPolicyReviewSnapshot(policyStoreRevision=$policyStoreRevision, " +
            "toolCount=${tools.size}, metadata=redacted)"
}

data class PluginRemoteMcpPolicyDecisionSubmission(
    val toolName: String,
    val toolMetadataDigest: String,
    val effect: PluginRemoteMcpPolicyEffect,
) {
    init {
        requireBoundedToken(toolName, "Remote MCP tool", 128)
        require(toolMetadataDigest.matches(OPAQUE_SHA_256))
    }
}

data class PluginRemoteMcpPolicyReviewSubmission(
    val pluginId: String,
    val serverId: String,
    val review: PluginRemoteMcpPolicyReviewSnapshot,
    val decisions: List<PluginRemoteMcpPolicyDecisionSubmission>,
) {
    init {
        requireBoundedToken(pluginId, "Remote MCP plugin")
        requireBoundedToken(serverId, "Remote MCP server")
        require(decisions.size == review.tools.size)
    }

    override fun toString(): String =
        "PluginRemoteMcpPolicyReviewSubmission(pluginId=$pluginId, serverId=$serverId, " +
            "decisionCount=${decisions.size}, metadata=redacted)"
}

enum class PluginRemoteMcpPolicyReviewResult {
    APPROVED_RETRY_REQUIRED,
    STALE_REVIEW,
    INVALID_DECISIONS,
    UNAVAILABLE,
}

/**
 * Deliberately minimal user-facing action. Security identities, source handles, endpoints,
 * configuration digests, OAuth state and credentials remain inside the controller/runtime.
 */
data class PluginConnectionActionSnapshot(
    val pluginId: String,
    val serverId: String,
    val kind: PluginConnectionActionKind,
    @StringRes val titleResource: Int,
    @StringRes val messageResource: Int,
    @StringRes val actionLabelResource: Int,
    val policyReview: PluginRemoteMcpPolicyReviewSnapshot? = null,
)

data class PluginOperationSnapshot(
    val operationId: String,
    val kind: PluginOperationKind,
    val target: PluginHandle?,
    val status: PluginOperationStatus,
    val retryable: Boolean,
    val failure: PluginOperationFailure?,
)

data class PluginDomainSnapshot(
    val phase: PluginCatalogPhase,
    val revision: Long,
    val plugins: List<PluginCard>,
    val apps: List<AppCard>,
    val skills: List<SkillCard>,
    val selectedPlugin: PluginDetailSnapshot?,
    val operations: List<PluginOperationSnapshot>,
    val marketplaceLoadIssueCount: Int,
    val marketplaceUpgradeIssueCount: Int,
    val connectionAction: PluginConnectionActionSnapshot? = null,
) {
    companion object {
        val EMPTY = PluginDomainSnapshot(
            phase = PluginCatalogPhase.EMPTY,
            revision = 0,
            plugins = emptyList(),
            apps = emptyList(),
            skills = emptyList(),
            selectedPlugin = null,
            operations = emptyList(),
            marketplaceLoadIssueCount = 0,
            marketplaceUpgradeIssueCount = 0,
            connectionAction = null,
        )
    }
}

internal data class PluginLocator(
    val pluginId: String,
    val pluginName: String,
    val marketplaceName: String,
    val marketplacePath: String?,
) {
    init {
        requireBoundedToken(pluginId, "Plugin id")
        requireBoundedToken(pluginName, "Plugin name", 512)
        requireBoundedToken(marketplaceName, "Marketplace name", 512)
        marketplacePath?.let {
            require(it.startsWith('/') && it.length <= ProtocolLimits.MAX_PATH_CHARS) {
                "Marketplace path must be absolute and bounded"
            }
            require('\u0000' !in it) { "Marketplace path contains NUL" }
        }
    }
}

internal fun requireBoundedToken(value: String, label: String, maxChars: Int = 256) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.length <= maxChars) { "$label is too long" }
    require(value.none(Char::isISOControl)) { "$label contains control characters" }
}

private val OPAQUE_SHA_256 = Regex("[0-9a-f]{64}")
