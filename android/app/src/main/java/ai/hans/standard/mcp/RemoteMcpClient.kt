package ai.hans.standard.mcp

import ai.hans.standard.plugins.runtime.RemoteMcpPassiveStatus
import ai.hans.standard.plugins.runtime.RemoteMcpPassiveStatusSource
import ai.hans.standard.plugins.runtime.RemoteMcpRequirement
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal fun interface RemoteMcpCancellation {
    fun isCancelled(): Boolean

    companion object {
        val NONE = RemoteMcpCancellation { false }
    }
}

internal enum class RemoteMcpToolEffect {
    READ_ONLY,
    MUTATING,
}

internal enum class RemoteMcpPostcondition {
    VERIFIED,
    FAILED,
    AMBIGUOUS,
}

internal data class RemoteMcpVerificationContext(
    val pluginId: String,
    val serverId: String,
    val configurationDigest: String,
    val toolName: String,
    val argumentsJson: String,
    val resultJson: String,
)

internal fun interface RemoteMcpPostconditionVerifier {
    fun verify(context: RemoteMcpVerificationContext): RemoteMcpPostcondition
}

internal data class RemoteMcpResolvedToolPolicy(
    val effect: RemoteMcpToolEffect,
    val verifier: RemoteMcpPostconditionVerifier?,
    val generation: Long,
)

/**
 * Exact, live policy boundary used immediately before a Remote MCP effect.
 *
 * A session deliberately keeps this interface rather than copying a policy map. The durable
 * policy owner can therefore revoke or rotate an approval without trusting an already-open
 * session or a leased dynamic-tool generation.
 */
internal fun interface RemoteMcpToolPolicyResolver {
    fun resolve(
        activationIdentity: RemoteMcpActivationIdentity,
        tool: RemoteMcpTool,
    ): RemoteMcpResolvedToolPolicy?
}

/** Immutable exact-metadata snapshot. Policy changes require constructing/publishing a new one. */
internal class RemoteMcpToolPolicyRegistry(
    policies: List<RemoteMcpToolPolicy>,
    private val signedVerifiers: RemoteMcpSignedVerifierRegistry =
        RemoteMcpSignedVerifierRegistry.EMPTY,
) : RemoteMcpToolPolicyResolver {
    private val policies = policies.toList().associateBy {
        Triple(it.activationIdentity, it.toolName, it.toolMetadataDigest)
    }

    init {
        require(policies.size <= 1024)
        require(this.policies.size == policies.size) { "Duplicate remote MCP tool policy" }
        require(policies.all { policy ->
            policy.effect == RemoteMcpToolEffect.READ_ONLY ||
                signedVerifiers.resolve(requireNotNull(policy.verifierId)) != null
        }) { "Mutating MCP policy has no signed in-app verifier" }
    }

    override fun resolve(
        activationIdentity: RemoteMcpActivationIdentity,
        tool: RemoteMcpTool,
    ): RemoteMcpResolvedToolPolicy? {
        val policy = policies[
            Triple(activationIdentity, tool.name, remoteMcpToolMetadataDigest(tool))
        ] ?: return null
        val verifier = policy.verifierId?.let(signedVerifiers::resolve)
        if (policy.effect == RemoteMcpToolEffect.MUTATING && verifier == null) return null
        return RemoteMcpResolvedToolPolicy(policy.effect, verifier, policy.generation)
    }

    companion object {
        fun fromStoreSnapshot(
            snapshot: RemoteMcpToolPolicyStoreSnapshot,
            signedVerifiers: RemoteMcpSignedVerifierRegistry,
        ): RemoteMcpToolPolicyRegistry = RemoteMcpToolPolicyRegistry(
            policies = if (snapshot.available) snapshot.policies else emptyList(),
            signedVerifiers = signedVerifiers,
        )
    }
}

internal data class RemoteMcpDiscoveryReceipt(
    val tools: List<RemoteMcpTool>,
    val allowedToolNames: Set<String>,
)

internal data class RemoteMcpInvocationReceipt(
    val success: Boolean,
    val postcondition: RemoteMcpPostcondition,
    val resultJson: String?,
    val errorCode: String?,
)

internal class RemoteMcpFailure(val code: String) : IllegalStateException(code) {
    init {
        require(code.matches(Regex("[a-z0-9_.:-]{1,96}")))
    }
}

internal data class RemoteMcpActivationPassiveStatus(
    val activationIdentity: RemoteMcpActivationIdentity,
    val configured: Boolean,
    val authenticated: Boolean,
    val discoveryProven: Boolean,
    val discoveredToolNames: Set<String>,
) {
    init {
        require(!authenticated || configured)
        require(!discoveryProven || configured)
        require(discoveredToolNames.size <= 256)
    }

    fun publicProjection(): RemoteMcpPassiveStatus = RemoteMcpPassiveStatus(
        pluginId = activationIdentity.pluginId,
        serverId = activationIdentity.serverId,
        configured = configured,
        authenticated = authenticated,
        discoveryProven = discoveryProven,
        discoveredToolNames = discoveredToolNames,
    )

    override fun toString(): String =
        "RemoteMcpActivationPassiveStatus(" +
            "pluginId=${activationIdentity.pluginId}, " +
            "serverId=${activationIdentity.serverId}, " +
            "configured=$configured, authenticated=$authenticated, " +
            "discoveryProven=$discoveryProven, " +
            "discoveredToolNames=$discoveredToolNames)"
}

/** Thread-safe passive cache keyed by the complete activation identity, never only by server id. */
internal class RemoteMcpPassiveStatusRegistry {
    private val lock = Any()
    private var statuses = linkedMapOf<RemoteMcpActivationIdentity, RemoteMcpActivationPassiveStatus>()

    fun snapshot(): List<RemoteMcpActivationPassiveStatus> = synchronized(lock) {
        statuses.values.sortedWith(
            compareBy(
                { it.activationIdentity.pluginId },
                { it.activationIdentity.serverId },
                { it.activationIdentity.configurationDigest },
            ),
        )
    }

    fun sourceFor(identity: RemoteMcpActivationIdentity): RemoteMcpPassiveStatusSource =
        RemoteMcpPassiveStatusSource {
            synchronized(lock) {
                statuses[identity]?.let { listOf(it.publicProjection()) } ?: emptyList()
            }
        }

    fun publish(status: RemoteMcpActivationPassiveStatus) = synchronized(lock) {
        statuses = LinkedHashMap(statuses).also { it[status.activationIdentity] = status }
    }

    fun clear(identity: RemoteMcpActivationIdentity) = synchronized(lock) {
        statuses = LinkedHashMap(statuses).also { it.remove(identity) }
    }
}

/** Exact per-invocation cancellation lease; cancelling one lease can never affect another call. */
internal class RemoteMcpInvocation internal constructor(
    private val owner: Any,
) : RemoteMcpCancellation {
    private val cancelled = AtomicBoolean(false)
    private val activeCall = AtomicReference<RemoteMcpHttpCall?>(null)

    override fun isCancelled(): Boolean = cancelled.get()

    fun cancel(): Boolean {
        val changed = cancelled.compareAndSet(false, true)
        activeCall.get()?.let { runCatching(it::cancel) }
        return changed
    }

    internal fun attach(call: RemoteMcpHttpCall) {
        check(activeCall.compareAndSet(null, call)) { "mcp_invocation_already_active" }
        if (cancelled.get()) call.cancel()
    }

    internal fun detach(call: RemoteMcpHttpCall) {
        activeCall.compareAndSet(call, null)
    }

    internal fun ownedBy(candidate: Any): Boolean = owner === candidate
}

/**
 * One bounded dual-era MCP Streamable-HTTP client. It prefers the stateless 2026-07-28 wire and
 * pins the server era after a correlated discovery result. The legacy initialize/session wire is
 * selected only after the exact HTTP-400 legacy signal defined by the 2026 transport; recognized
 * modern protocol errors and transport/auth/other-HTTP/parse failures can never silently
 * downgrade. No raw token, URL or remote exception text is exposed.
 */
internal class RemoteMcpSession(
    private val definition: RemoteMcpActivationDefinition,
    private val http: RemoteMcpHttpCallFactory,
    private val oauth: RemoteMcpOAuthVault,
    private val toolPolicies: RemoteMcpToolPolicyResolver,
    private val passiveStatuses: RemoteMcpPassiveStatusRegistry,
) : Closeable {
    private val identity = definition.identity
    private val requirement = definition.requirement
    private val oauthIdentity = requirement.oauthHandle?.let { handle ->
        RemoteMcpOAuthCredentialIdentity(
            pluginId = identity.pluginId,
            serverId = identity.serverId,
            configurationDigest = identity.configurationDigest,
            handle = handle,
        )
    }
    private val nextId = AtomicLong(1L)
    private val closed = AtomicBoolean(false)
    private val invocationOwner = Any()
    private val stateLock = Any()
    private val discoveryLock = Any()
    private val activeInvocations = ConcurrentHashMap.newKeySet<RemoteMcpInvocation>()
    private var sessionId: String? = null
    private var protocolEra: RemoteMcpProtocolEra? = null
    private var protocolFailureCode: String? = null
    private var initialized = false
    private var discovered = emptyMap<String, RemoteMcpTool>()

    internal val activationIdentity: RemoteMcpActivationIdentity
        get() = identity

    fun discover(
        cancellation: RemoteMcpCancellation = RemoteMcpCancellation.NONE,
    ): RemoteMcpDiscoveryReceipt = discover(beginInvocation(), cancellation)

    /** Uses the caller-owned cancellation lease across the complete discovery transaction. */
    fun discover(
        invocation: RemoteMcpInvocation,
        cancellation: RemoteMcpCancellation = RemoteMcpCancellation.NONE,
    ): RemoteMcpDiscoveryReceipt = synchronized(discoveryLock) {
        require(invocation.ownedBy(invocationOwner)) { "mcp_invocation_identity_mismatch" }
        try {
            discoverInternal(cancellation, invocation)
        } catch (failure: Throwable) {
            passiveStatuses.publish(
                RemoteMcpActivationPassiveStatus(
                    activationIdentity = identity,
                    configured = true,
                    authenticated = credentialAuthenticated(),
                    discoveryProven = false,
                    discoveredToolNames = emptySet(),
                ),
            )
            throw if (failure is RemoteMcpFailure) {
                failure
            } else {
                RemoteMcpFailure(failure.safeMcpCode())
            }
        }
    }

    private fun discoverInternal(
        cancellation: RemoteMcpCancellation,
        invocation: RemoteMcpInvocation,
    ): RemoteMcpDiscoveryReceipt {
        ensureOpen()
        requireCredentialAvailable()
        val era = ensureProtocolReady(cancellation, invocation)
        val accumulated = linkedMapOf<String, RemoteMcpTool>()
        val seenCursors = linkedSetOf<String>()
        var cursor: String? = null
        repeat(MAX_TOOL_PAGES) {
            ensureNotCancelled(cancellation, invocation)
            val id = nextRequestId()
            val envelope = exchangeRequest(
                RemoteMcpProtocol.toolsListRequest(id, cursor, era),
                id,
                era,
                cancellation,
                invocation,
            )
            val result = envelope.successOrThrow()
            val page = RemoteMcpProtocol.parseToolsPage(result, era)
            page.tools.forEach { tool ->
                require(accumulated.putIfAbsent(tool.name, tool) == null) {
                    "mcp_duplicate_tool_across_pages"
                }
                require(accumulated.size <= MAX_DISCOVERED_TOOLS) { "mcp_tool_catalog_too_large" }
            }
            cursor = page.nextCursor
            if (cursor == null) {
                val allowed = requirement.allowedTools.filterTo(linkedSetOf()) { name ->
                    accumulated[name]?.let { toolPolicies.resolve(identity, it) } != null
                }
                synchronized(stateLock) { discovered = accumulated.toMap() }
                passiveStatuses.publish(
                    RemoteMcpActivationPassiveStatus(
                        activationIdentity = identity,
                        configured = true,
                        authenticated = credentialAuthenticated(),
                        discoveryProven = true,
                        discoveredToolNames = accumulated.keys,
                    ),
                )
                return RemoteMcpDiscoveryReceipt(accumulated.values.toList(), allowed)
            }
            require(seenCursors.add(cursor!!)) { "mcp_cursor_cycle" }
        }
        throw RemoteMcpFailure("mcp_tool_page_limit")
    }

    fun call(
        toolName: String,
        argumentsJson: String,
        cancellation: RemoteMcpCancellation = RemoteMcpCancellation.NONE,
    ): RemoteMcpInvocationReceipt = call(beginInvocation(), toolName, argumentsJson, cancellation)

    fun call(
        invocation: RemoteMcpInvocation,
        toolName: String,
        argumentsJson: String,
        cancellation: RemoteMcpCancellation = RemoteMcpCancellation.NONE,
    ): RemoteMcpInvocationReceipt {
        ensureOpen()
        require(invocation.ownedBy(invocationOwner)) { "mcp_invocation_identity_mismatch" }
        val discoveredSnapshot = synchronized(stateLock) {
            if (!initialized) null else discovered
        }
        if (discoveredSnapshot.isNullOrEmpty()) {
            return failure("mcp_discovery_required", RemoteMcpPostcondition.FAILED)
        }
        if (toolName !in requirement.allowedTools || toolName !in discoveredSnapshot) {
            return failure("mcp_tool_not_allowed", RemoteMcpPostcondition.FAILED)
        }
        val policy = toolPolicies.resolve(identity, requireNotNull(discoveredSnapshot[toolName]))
            ?: return failure("mcp_tool_policy_missing", RemoteMcpPostcondition.FAILED)
        val id = nextRequestId()
        return try {
            val era = requireNotNull(currentProtocolEra())
            val result = RemoteMcpProtocol.parseToolResult(
                exchangeRequest(
                    RemoteMcpProtocol.toolsCallRequest(
                        id,
                        requireNotNull(discoveredSnapshot[toolName]),
                        argumentsJson,
                        era,
                    ),
                    id,
                    era,
                    cancellation,
                    invocation,
                ).successOrThrow(),
            )
            if (result.isError) return failure("mcp_tool_reported_error", RemoteMcpPostcondition.FAILED)
            val postcondition = when (policy.effect) {
                RemoteMcpToolEffect.READ_ONLY -> RemoteMcpPostcondition.VERIFIED
                RemoteMcpToolEffect.MUTATING -> runCatching {
                    requireNotNull(policy.verifier).verify(
                        RemoteMcpVerificationContext(
                            identity.pluginId,
                            requirement.id,
                            identity.configurationDigest,
                            toolName,
                            argumentsJson,
                            result.resultJson,
                        ),
                    )
                }.getOrDefault(RemoteMcpPostcondition.AMBIGUOUS)
            }
            if (postcondition != RemoteMcpPostcondition.VERIFIED) {
                failure("mcp_postcondition_${postcondition.name.lowercase()}", postcondition)
            } else {
                RemoteMcpInvocationReceipt(true, postcondition, result.resultJson, null)
            }
        } catch (failure: Exception) {
            val cancelled = cancellation.isCancelled() || invocation.isCancelled()
            failure(
                if (cancelled) "mcp_call_cancelled" else failure.safeMcpCode(),
                if (cancelled) RemoteMcpPostcondition.AMBIGUOUS else RemoteMcpPostcondition.AMBIGUOUS,
            )
        }
    }

    fun beginInvocation(): RemoteMcpInvocation {
        ensureOpen()
        return RemoteMcpInvocation(invocationOwner)
    }

    fun cancel(invocation: RemoteMcpInvocation): Boolean {
        require(invocation.ownedBy(invocationOwner)) { "mcp_invocation_identity_mismatch" }
        return invocation.cancel()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            activeInvocations.toList().forEach(RemoteMcpInvocation::cancel)
        }
    }

    private fun ensureProtocolReady(
        cancellation: RemoteMcpCancellation,
        invocation: RemoteMcpInvocation,
    ): RemoteMcpProtocolEra {
        currentProtocolEra()?.let { era ->
            if (era == RemoteMcpProtocolEra.LEGACY_2025 && !isInitialized()) {
                initializeLegacy(cancellation, invocation)
            }
            if (era == RemoteMcpProtocolEra.MODERN_2026 && !isInitialized()) {
                throw RemoteMcpFailure(
                    synchronized(stateLock) {
                        protocolFailureCode ?: "mcp_modern_protocol_unavailable"
                    },
                )
            }
            return era
        }
        val id = nextRequestId()
        val discovery = exchangeRequest(
            RemoteMcpProtocol.modernDiscoverRequest(id),
            id,
            RemoteMcpProtocolEra.MODERN_2026,
            cancellation,
            invocation,
        )
        return when (discovery) {
            is RemoteMcpResponseEnvelope.Success -> {
                val result = RemoteMcpProtocol.parseDiscover(discovery.result)
                require(result.toolsCapability) { "mcp_tools_capability_missing" }
                synchronized(stateLock) {
                    require(protocolEra == null || protocolEra == RemoteMcpProtocolEra.MODERN_2026) {
                        "mcp_protocol_era_change_forbidden"
                    }
                    protocolEra = RemoteMcpProtocolEra.MODERN_2026
                    protocolFailureCode = null
                    initialized = true
                }
                RemoteMcpProtocolEra.MODERN_2026
            }
            is RemoteMcpResponseEnvelope.UnsupportedProtocolVersion -> {
                synchronized(stateLock) {
                    require(protocolEra == null || protocolEra == RemoteMcpProtocolEra.MODERN_2026) {
                        "mcp_protocol_era_change_forbidden"
                    }
                    protocolEra = RemoteMcpProtocolEra.MODERN_2026
                    protocolFailureCode = "mcp_no_supported_protocol_version"
                }
                throw RemoteMcpFailure("mcp_no_supported_protocol_version")
            }
            is RemoteMcpResponseEnvelope.ModernProtocolError -> {
                synchronized(stateLock) {
                    require(protocolEra == null || protocolEra == RemoteMcpProtocolEra.MODERN_2026) {
                        "mcp_protocol_era_change_forbidden"
                    }
                    protocolEra = RemoteMcpProtocolEra.MODERN_2026
                    protocolFailureCode = "mcp_modern_protocol_error"
                }
                throw RemoteMcpFailure("mcp_modern_protocol_error")
            }
            RemoteMcpResponseEnvelope.LegacyEraRequired -> {
                synchronized(stateLock) {
                    require(protocolEra == null || protocolEra == RemoteMcpProtocolEra.LEGACY_2025) {
                        "mcp_protocol_era_change_forbidden"
                    }
                    protocolEra = RemoteMcpProtocolEra.LEGACY_2025
                    protocolFailureCode = null
                }
                initializeLegacy(cancellation, invocation)
                RemoteMcpProtocolEra.LEGACY_2025
            }
            is RemoteMcpResponseEnvelope.Error -> throw RemoteMcpFailure("mcp_remote_error")
        }
    }

    private fun initializeLegacy(
        cancellation: RemoteMcpCancellation,
        invocation: RemoteMcpInvocation,
    ) {
        require(currentProtocolEra() == RemoteMcpProtocolEra.LEGACY_2025) {
            "mcp_protocol_era_change_forbidden"
        }
        val id = nextRequestId()
        val initialization = RemoteMcpProtocol.parseInitialize(
            exchangeRequest(
                RemoteMcpProtocol.initializeRequest(id),
                id,
                RemoteMcpProtocolEra.LEGACY_2025,
                cancellation,
                invocation,
            ).successOrThrow(),
        )
        require(initialization.toolsCapability) { "mcp_tools_capability_missing" }
        synchronized(stateLock) { initialized = true }
        try {
            sendNotification(RemoteMcpProtocol.initializedNotification(), cancellation, invocation)
        } catch (failure: Throwable) {
            synchronized(stateLock) { initialized = false }
            throw failure
        }
    }

    private fun exchangeRequest(
        message: RemoteMcpProtocolMessage,
        expectedId: Long,
        era: RemoteMcpProtocolEra,
        cancellation: RemoteMcpCancellation,
        invocation: RemoteMcpInvocation,
    ): RemoteMcpResponseEnvelope {
        val response = executeHttp(message, era, cancellation, invocation)
        return RemoteMcpProtocol.decodeEnvelope(
            response,
            expectedId,
            requirement.maxResponseBytes,
            era,
        )
    }

    private fun sendNotification(
        message: RemoteMcpProtocolMessage,
        cancellation: RemoteMcpCancellation,
        invocation: RemoteMcpInvocation,
    ) {
        val response = executeHttp(
            message,
            RemoteMcpProtocolEra.LEGACY_2025,
            cancellation,
            invocation,
        )
        require(response.statusCode in 200..299) { "mcp_notification_rejected" }
        require(response.body.isEmpty()) { "mcp_notification_response_unexpected" }
    }

    private fun executeHttp(
        message: RemoteMcpProtocolMessage,
        era: RemoteMcpProtocolEra,
        cancellation: RemoteMcpCancellation,
        invocation: RemoteMcpInvocation,
    ): RemoteMcpHttpResponse {
        ensureOpen()
        ensureNotCancelled(cancellation, invocation)
        val correlation = synchronized(stateLock) { sessionId to initialized }
        val modern = era == RemoteMcpProtocolEra.MODERN_2026
        val request = RemoteMcpHttpRequest(
            endpoint = requirement.endpoint,
            body = message.body,
            timeoutMillis = requirement.requestTimeoutMillis,
            maxResponseBytes = requirement.maxResponseBytes,
            sessionId = if (modern) null else correlation.first,
            protocolVersion = when {
                modern -> RemoteMcpProtocol.MODERN_PROTOCOL_VERSION
                correlation.second -> RemoteMcpProtocol.LEGACY_PROTOCOL_VERSION
                else -> null
            },
            method = if (modern) message.method else null,
            name = if (modern) message.name else null,
            parameterHeaders = if (modern) message.parameterHeaders else emptyMap(),
        )
        val execute: (String?) -> RemoteMcpHttpResponse = { token ->
            val call = http.create(request, token)
            activeInvocations.add(invocation)
            var attached = false
            try {
                invocation.attach(call)
                attached = true
                if (closed.get()) call.cancel()
                if (cancellation.isCancelled() || invocation.isCancelled()) call.cancel()
                val response = call.execute()
                if (closed.get() || cancellation.isCancelled() || invocation.isCancelled()) {
                    throw RemoteMcpFailure("mcp_call_cancelled")
                }
                if (modern) {
                    require(response.sessionId == null) { "mcp_modern_session_header_unexpected" }
                } else {
                    response.sessionId?.let { received ->
                        synchronized(stateLock) {
                            val existing = sessionId
                            require(existing == null || existing == received) {
                                "mcp_session_correlation_mismatch"
                            }
                            sessionId = received
                        }
                    }
                }
                response
            } finally {
                if (attached) invocation.detach(call)
                activeInvocations.remove(invocation)
                runCatching(call::close)
            }
        }
        return oauthIdentity?.let { credentialIdentity ->
            oauth.withBearerToken(credentialIdentity) { token -> execute(token) }
        } ?: execute(null)
    }

    private fun requireCredentialAvailable() {
        val credentialIdentity = oauthIdentity ?: return
        val state = runCatching { oauth.state(credentialIdentity) }
            .getOrDefault(OAuthCredentialState.UNAVAILABLE)
        if (state != OAuthCredentialState.AVAILABLE) {
            passiveStatuses.publish(
                RemoteMcpActivationPassiveStatus(identity, true, false, false, emptySet()),
            )
            throw RemoteMcpFailure("mcp_oauth_${state.name.lowercase()}")
        }
    }

    private fun credentialAuthenticated(): Boolean = oauthIdentity?.let {
        runCatching { oauth.state(it) }.getOrDefault(OAuthCredentialState.UNAVAILABLE) ==
            OAuthCredentialState.AVAILABLE
    } ?: true

    private fun isInitialized(): Boolean = synchronized(stateLock) { initialized }

    private fun currentProtocolEra(): RemoteMcpProtocolEra? =
        synchronized(stateLock) { protocolEra }

    private fun ensureOpen() {
        if (closed.get()) throw RemoteMcpFailure("mcp_session_closed")
    }

    private fun ensureNotCancelled(
        cancellation: RemoteMcpCancellation,
        invocation: RemoteMcpInvocation,
    ) {
        if (cancellation.isCancelled() || invocation.isCancelled()) {
            throw RemoteMcpFailure("mcp_call_cancelled")
        }
    }

    private fun nextRequestId(): Long = nextId.getAndIncrement().also {
        if (it <= 0L) throw RemoteMcpFailure("mcp_request_id_exhausted")
    }

    private fun RemoteMcpResponseEnvelope.successOrThrow() = when (this) {
        is RemoteMcpResponseEnvelope.Success -> result
        is RemoteMcpResponseEnvelope.Error -> throw RemoteMcpFailure("mcp_remote_error")
        is RemoteMcpResponseEnvelope.UnsupportedProtocolVersion ->
            throw RemoteMcpFailure("mcp_protocol_version_unsupported")
        is RemoteMcpResponseEnvelope.ModernProtocolError ->
            throw RemoteMcpFailure("mcp_modern_protocol_error")
        RemoteMcpResponseEnvelope.LegacyEraRequired ->
            throw RemoteMcpFailure("mcp_protocol_era_unexpected")
    }

    private fun failure(code: String, postcondition: RemoteMcpPostcondition) =
        RemoteMcpInvocationReceipt(false, postcondition, null, code)

    private fun Throwable.safeMcpCode(): String = when (this) {
        is RemoteMcpFailure -> code
        else -> message?.takeIf { it.matches(Regex("mcp_[a-z0-9_]{1,80}")) } ?: "mcp_call_failed"
    }

    private companion object {
        const val MAX_TOOL_PAGES = 16
        const val MAX_DISCOVERED_TOOLS = 1_024
    }
}

private val SAFE_TOOL = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
