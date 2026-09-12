package ai.hans.standard.mcp

import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** Only the four standard MCP hints are projected. They remain untrusted display hints. */
internal data class RemoteMcpDeclaredToolHints(
    val readOnly: Boolean?,
    val destructive: Boolean?,
    val idempotent: Boolean?,
    val openWorld: Boolean?,
)

/** Secret-free bounded review projection. Schemas, endpoint, OAuth state and `_meta` stay private. */
internal data class RemoteMcpPolicyReviewToolSummary(
    val name: String,
    val title: String?,
    val declaredHints: RemoteMcpDeclaredToolHints,
    val metadataDigest: String,
    val description: String? = null,
) {
    init {
        require(name.matches(REVIEW_TOOL_NAME))
        title?.let { require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_REVIEW_TITLE_BYTES) }
        description?.let {
            require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_REVIEW_DESCRIPTION_BYTES)
            require(it.none(Char::isISOControl))
        }
        require(metadataDigest.matches(SHA_256))
    }

    override fun toString(): String = "RemoteMcpPolicyReviewToolSummary(metadata=redacted)"
}

/** Exact one-shot challenge handed from isolated discovery to the approval surface. */
internal data class RemoteMcpPolicyReviewRequest(
    val activationIdentity: RemoteMcpActivationIdentity,
    val catalogDigest: String,
    val policyStoreRevision: Long,
    val tools: List<RemoteMcpPolicyReviewToolSummary>,
) {
    init {
        require(catalogDigest.matches(SHA_256))
        require(policyStoreRevision >= 0L)
        require(tools.isNotEmpty() && tools.size <= MAX_REVIEW_TOOLS)
        require(tools == tools.sortedBy(RemoteMcpPolicyReviewToolSummary::name))
        require(tools.map(RemoteMcpPolicyReviewToolSummary::name).distinct().size == tools.size)
    }

    override fun toString(): String =
        "RemoteMcpPolicyReviewRequest(pluginId=${activationIdentity.pluginId}, " +
            "serverId=${activationIdentity.serverId}, policyStoreRevision=$policyStoreRevision, " +
            "toolCount=${tools.size})"
}

internal class RemoteMcpPolicyReviewRequiredException(
    val request: RemoteMcpPolicyReviewRequest,
) : IllegalStateException("mcp_tool_policy_review_required")

/**
 * Internal exact catalog retained only for validation. Its public representation is redacted.
 * The digest covers every security-relevant tool field through each exact metadata digest.
 */
internal class RemoteMcpPolicyReviewCatalog private constructor(
    val activationIdentity: RemoteMcpActivationIdentity,
    tools: List<RemoteMcpTool>,
) {
    val tools: List<RemoteMcpTool> = tools.sortedBy(RemoteMcpTool::name).toList()
    val catalogDigest: String = policyReviewCatalogDigest(this.tools)
    val summaries: List<RemoteMcpPolicyReviewToolSummary> = this.tools.map { it.safeSummary() }

    init {
        require(this.tools.isNotEmpty() && this.tools.size <= MAX_REVIEW_TOOLS)
        require(this.tools.map(RemoteMcpTool::name).distinct().size == this.tools.size)
    }

    fun request(policyStoreRevision: Long): RemoteMcpPolicyReviewRequest =
        RemoteMcpPolicyReviewRequest(
            activationIdentity = activationIdentity,
            catalogDigest = catalogDigest,
            policyStoreRevision = policyStoreRevision,
            tools = summaries,
        )

    fun hasExactPolicies(snapshot: RemoteMcpToolPolicyStoreSnapshot): Boolean {
        if (!snapshot.available) return false
        val exact = snapshot.policies.associateBy {
            Triple(it.activationIdentity, it.toolName, it.toolMetadataDigest)
        }
        return tools.all { tool ->
            Triple(
                activationIdentity,
                tool.name,
                remoteMcpToolMetadataDigest(tool),
            ) in exact
        }
    }

    override fun toString(): String =
        "RemoteMcpPolicyReviewCatalog(pluginId=${activationIdentity.pluginId}, " +
            "serverId=${activationIdentity.serverId}, toolCount=${tools.size})"

    companion object {
        /** Selects the manifest allowlist from the full bounded isolated-discovery catalog. */
        fun fromDiscovery(
            activationIdentity: RemoteMcpActivationIdentity,
            allowedToolNames: Set<String>,
            discovery: RemoteMcpDiscoveryReceipt,
        ): RemoteMcpPolicyReviewCatalog {
            require(allowedToolNames.isNotEmpty() && allowedToolNames.size <= MAX_REVIEW_TOOLS)
            if (discovery.tools.size > MAX_DISCOVERY_REVIEW_TOOLS) {
                throw RemoteMcpFailure("mcp_tool_catalog_too_large")
            }
            val byName = discovery.tools.associateBy(RemoteMcpTool::name)
            if (byName.size != discovery.tools.size || !byName.keys.containsAll(allowedToolNames)) {
                throw RemoteMcpFailure("mcp_required_tool_catalog_incomplete")
            }
            return RemoteMcpPolicyReviewCatalog(
                activationIdentity = activationIdentity,
                tools = allowedToolNames.sorted().map { requireNotNull(byName[it]) },
            )
        }
    }
}

/** A human decision is mandatory; no annotation hint supplies a default effect. */
internal data class RemoteMcpPolicyReviewDecision(
    val toolName: String,
    val toolMetadataDigest: String,
    val effect: RemoteMcpToolEffect,
    val signedVerifierId: String? = null,
) {
    init {
        require(toolName.matches(REVIEW_TOOL_NAME))
        require(toolMetadataDigest.matches(SHA_256))
        when (effect) {
            RemoteMcpToolEffect.READ_ONLY -> require(signedVerifierId == null)
            RemoteMcpToolEffect.MUTATING -> require(!signedVerifierId.isNullOrBlank()) {
                "Mutating Remote MCP approval requires a signed in-app verifier"
            }
        }
    }

    override fun toString(): String = "RemoteMcpPolicyReviewDecision(effect=$effect)"
}

internal fun interface RemoteMcpPolicyOwnerReloadCallback {
    fun reload()
}

internal fun interface RemoteMcpExactSessionCloseCallback {
    fun close(identity: RemoteMcpActivationIdentity)
}

internal fun interface RemoteMcpContractRefreshCallback {
    fun refresh(identity: RemoteMcpActivationIdentity)
}

/**
 * Explicit approval boundary. It mutates only the exact policy set and refreshes runtime owners;
 * it deliberately has no install/retry dependency, so approval can never commit a plugin.
 */
internal class RemoteMcpPolicyReviewCoordinator(
    private val store: RemoteMcpToolPolicyStore,
    private val reloadPolicyOwner: RemoteMcpPolicyOwnerReloadCallback,
    private val closeExactSession: RemoteMcpExactSessionCloseCallback,
    private val refreshContract: RemoteMcpContractRefreshCallback,
) {
    fun approve(
        request: RemoteMcpPolicyReviewRequest,
        currentCatalog: RemoteMcpPolicyReviewCatalog,
        decisions: List<RemoteMcpPolicyReviewDecision>,
    ): RemoteMcpToolPolicyStoreSnapshot {
        validateCurrent(request, currentCatalog)
        val approvals = validateDecisions(currentCatalog, decisions)
        val updated = store.compareAndSetForActivation(
            expectedRevision = request.policyStoreRevision,
            identity = request.activationIdentity,
            approvals = approvals,
        )
        refreshRuntime(request.activationIdentity)
        return updated
    }

    /**
     * Repairs the only post-CAS crash window without writing policy a second time. This succeeds
     * solely when the durable exact activation already contains every submitted decision and no
     * additional policy. Installation remains outside this coordinator.
     */
    fun reconcileCommittedApproval(
        request: RemoteMcpPolicyReviewRequest,
        currentCatalog: RemoteMcpPolicyReviewCatalog,
        decisions: List<RemoteMcpPolicyReviewDecision>,
    ): RemoteMcpToolPolicyStoreSnapshot {
        validateCatalog(request, currentCatalog)
        val approvals = validateDecisions(currentCatalog, decisions)
        val snapshot = store.snapshot()
        if (!snapshot.available) throw RemoteMcpFailure("mcp_tool_policy_store_unavailable")
        if (snapshot.revision <= request.policyStoreRevision) {
            throw RemoteMcpFailure("mcp_policy_review_commit_not_recoverable")
        }
        val actual = snapshot.policies.filter {
            it.activationIdentity == request.activationIdentity
        }
        if (actual.size != approvals.size || approvals.any { approval ->
                actual.singleOrNull { policy ->
                    policy.toolName == approval.tool.name &&
                        policy.toolMetadataDigest == remoteMcpToolMetadataDigest(approval.tool) &&
                        policy.effect == approval.effect &&
                        policy.verifierId == approval.verifierId
                } == null
            }
        ) {
            throw RemoteMcpFailure("mcp_policy_review_commit_not_recoverable")
        }
        refreshRuntime(request.activationIdentity)
        return snapshot
    }

    private fun validateDecisions(
        currentCatalog: RemoteMcpPolicyReviewCatalog,
        decisions: List<RemoteMcpPolicyReviewDecision>,
    ): List<RemoteMcpToolPolicyApproval> {
        if (decisions.size != currentCatalog.tools.size) {
            throw RemoteMcpFailure("mcp_policy_review_decision_incomplete")
        }
        val decisionsByName = decisions.associateBy(RemoteMcpPolicyReviewDecision::toolName)
        if (decisionsByName.size != decisions.size ||
            decisionsByName.keys != currentCatalog.tools.mapTo(linkedSetOf(), RemoteMcpTool::name)
        ) {
            throw RemoteMcpFailure("mcp_policy_review_decision_incomplete")
        }
        return currentCatalog.tools.map { tool ->
            val decision = requireNotNull(decisionsByName[tool.name])
            if (decision.toolMetadataDigest != remoteMcpToolMetadataDigest(tool)) {
                throw RemoteMcpFailure("mcp_policy_review_catalog_changed")
            }
            RemoteMcpToolPolicyApproval(
                activationIdentity = currentCatalog.activationIdentity,
                tool = tool,
                effect = decision.effect,
                verifierId = decision.signedVerifierId,
            )
        }
    }

    fun revoke(
        request: RemoteMcpPolicyReviewRequest,
        currentCatalog: RemoteMcpPolicyReviewCatalog,
    ): RemoteMcpToolPolicyStoreSnapshot {
        validateCurrent(request, currentCatalog)
        val updated = store.compareAndSetForActivation(
            expectedRevision = request.policyStoreRevision,
            identity = request.activationIdentity,
            approvals = emptyList(),
        )
        refreshRuntime(request.activationIdentity)
        return updated
    }

    private fun validateCurrent(
        request: RemoteMcpPolicyReviewRequest,
        currentCatalog: RemoteMcpPolicyReviewCatalog,
    ) {
        validateCatalog(request, currentCatalog)
        val snapshot = store.snapshot()
        if (!snapshot.available) throw RemoteMcpFailure("mcp_tool_policy_store_unavailable")
        if (snapshot.revision != request.policyStoreRevision) {
            throw RemoteMcpFailure("mcp_tool_policy_revision_stale")
        }
    }

    private fun validateCatalog(
        request: RemoteMcpPolicyReviewRequest,
        currentCatalog: RemoteMcpPolicyReviewCatalog,
    ) {
        if (request.activationIdentity != currentCatalog.activationIdentity) {
            throw RemoteMcpFailure("mcp_policy_review_identity_changed")
        }
        if (request.catalogDigest != currentCatalog.catalogDigest ||
            request.tools != currentCatalog.summaries
        ) {
            throw RemoteMcpFailure("mcp_policy_review_catalog_changed")
        }
    }

    private fun refreshRuntime(identity: RemoteMcpActivationIdentity) {
        val failures = listOf<() -> Unit>(
            { reloadPolicyOwner.reload() },
            { closeExactSession.close(identity) },
            { refreshContract.refresh(identity) },
        ).mapNotNull { callback -> runCatching(callback).exceptionOrNull() }
        if (failures.isNotEmpty()) {
            val failure = RemoteMcpFailure("mcp_policy_review_runtime_refresh_failed")
            failures.forEach(failure::addSuppressed)
            throw failure
        }
    }
}

private fun RemoteMcpTool.safeSummary(): RemoteMcpPolicyReviewToolSummary =
    RemoteMcpPolicyReviewToolSummary(
        name = name,
        title = title,
        declaredHints = declaredHints(),
        metadataDigest = remoteMcpToolMetadataDigest(this),
        description = description
            ?.filterNot(Char::isISOControl)
            ?.trim()
            ?.take(MAX_REVIEW_DESCRIPTION_CHARS)
            ?.takeIf(String::isNotEmpty),
    )

private fun RemoteMcpTool.declaredHints(): RemoteMcpDeclaredToolHints {
    val annotations = annotationsJson?.let(::JSONObject)
    fun hint(name: String): Boolean? {
        if (annotations == null || !annotations.has(name) || annotations.isNull(name)) return null
        return (annotations.get(name) as? Boolean)
    }
    return RemoteMcpDeclaredToolHints(
        readOnly = hint("readOnlyHint"),
        destructive = hint("destructiveHint"),
        idempotent = hint("idempotentHint"),
        openWorld = hint("openWorldHint"),
    )
}

private fun policyReviewCatalogDigest(tools: List<RemoteMcpTool>): String = sha256(
    JSONObject()
        .put(
            "tools",
            JSONArray(
                tools.sortedBy(RemoteMcpTool::name).map { tool ->
                    JSONObject()
                        .put("name", tool.name)
                        .put("metadataDigest", remoteMcpToolMetadataDigest(tool))
                },
            ),
        )
        .toString()
        .toByteArray(StandardCharsets.UTF_8),
)

private const val MAX_REVIEW_TITLE_BYTES = 2 * 1024
private const val MAX_REVIEW_DESCRIPTION_BYTES = 4 * 1024
private const val MAX_REVIEW_DESCRIPTION_CHARS = 1_024
private const val MAX_REVIEW_TOOLS = 64
private const val MAX_DISCOVERY_REVIEW_TOOLS = 256
private val REVIEW_TOOL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
