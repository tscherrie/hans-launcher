package ai.hans.standard.mcp

import java.util.concurrent.atomic.AtomicReference

/** Secret-free process-local status for the live Remote MCP policy boundary. */
internal data class RemoteMcpToolPolicyOwnerSnapshot(
    val storeAvailable: Boolean,
    val current: Boolean,
    val revision: Long,
    val approvedPolicyCount: Int,
) {
    init {
        require(revision >= 0L)
        require(approvedPolicyCount >= 0)
        require(storeAvailable || (!current && revision == 0L && approvedPolicyCount == 0))
        require(current || approvedPolicyCount == 0)
    }

    override fun toString(): String =
        "RemoteMcpToolPolicyOwnerSnapshot(" +
            "storeAvailable=$storeAvailable, current=$current, revision=$revision, " +
            "approvedPolicyCount=$approvedPolicyCount)"
}

/**
 * Live fail-closed owner for the immutable policy registry used by already-open MCP sessions.
 *
 * Reads validate the durable revision before consulting the selected registry. A different store
 * revision is never adopted implicitly: it clears effective policy until the caller explicitly
 * invokes [reloadFromStore] after its successful CAS. Store corruption or unavailability clears
 * effective policy immediately.
 */
internal class RemoteMcpToolPolicyOwner(
    private val store: RemoteMcpToolPolicyStore,
    private val signedVerifiers: RemoteMcpSignedVerifierRegistry,
) : RemoteMcpToolPolicyResolver {
    private val selected = AtomicReference(loadCurrentState())

    override fun resolve(
        activationIdentity: RemoteMcpActivationIdentity,
        tool: RemoteMcpTool,
    ): RemoteMcpResolvedToolPolicy? {
        val state = validateDurableRevision()
        if (!state.passive.storeAvailable || !state.passive.current) return null
        return state.registry.resolve(activationIdentity, tool)
    }

    /** Passive and secret-free; does not read files or expose identities, tools or verifier ids. */
    fun passiveSnapshot(): RemoteMcpToolPolicyOwnerSnapshot = selected.get().passive

    /** Supplier for revisioned dynamic-tool publication. Never returns a stale revision. */
    fun currentRevisionOrThrow(): Long {
        val state = validateDurableRevision()
        if (!state.passive.storeAvailable) {
            throw RemoteMcpFailure("mcp_tool_policy_store_unavailable")
        }
        if (!state.passive.current) {
            throw RemoteMcpFailure("mcp_tool_policy_revision_changed")
        }
        return state.passive.revision
    }

    /**
     * Explicitly adopts the store's complete current revision after an external successful CAS.
     * The immutable registry and its passive projection become visible in one atomic swap.
     */
    fun reloadFromStore(): RemoteMcpToolPolicyOwnerSnapshot {
        val replacement = loadCurrentState()
        while (true) {
            val current = selected.get()
            // A delayed reload must never replace a newer revision already adopted by another
            // thread. Store revisions are monotonic whole-document CAS generations.
            if (replacement.passive.storeAvailable &&
                current.passive.storeAvailable &&
                current.passive.revision > replacement.passive.revision
            ) {
                return current.passive
            }
            if (selected.compareAndSet(current, replacement)) return replacement.passive
        }
    }

    override fun toString(): String = "RemoteMcpToolPolicyOwner(${passiveSnapshot()})"

    private fun validateDurableRevision(): OwnerState {
        var durable = runCatching(store::snapshot).getOrElse {
            return selectUnavailable()
        }
        if (!durable.available) return selectUnavailable()
        while (true) {
            val current = selected.get()
            if (!current.passive.storeAvailable) return current
            if (current.passive.revision > durable.revision) {
                // Distinguish a read which raced a newer successful reload from durable revision
                // rollback. A second post-observation read must see at least the adopted revision.
                durable = runCatching(store::snapshot).getOrElse {
                    return selectUnavailable()
                }
                if (!durable.available || durable.revision < current.passive.revision) {
                    return selectUnavailable()
                }
                continue
            }
            if (current.passive.revision == durable.revision) return current
            val stale = OwnerState.stale(durable.revision)
            if (selected.compareAndSet(current, stale)) return stale
        }
    }

    private fun selectUnavailable(): OwnerState {
        while (true) {
            val current = selected.get()
            if (!current.passive.storeAvailable) return current
            if (selected.compareAndSet(current, OwnerState.UNAVAILABLE)) {
                return OwnerState.UNAVAILABLE
            }
        }
    }

    private fun loadCurrentState(): OwnerState {
        val snapshot = runCatching(store::snapshot).getOrNull()
            ?: return OwnerState.UNAVAILABLE
        if (!snapshot.available) return OwnerState.UNAVAILABLE
        return runCatching {
            OwnerState(
                passive = RemoteMcpToolPolicyOwnerSnapshot(
                    storeAvailable = true,
                    current = true,
                    revision = snapshot.revision,
                    approvedPolicyCount = snapshot.policies.size,
                ),
                registry = RemoteMcpToolPolicyRegistry.fromStoreSnapshot(
                    snapshot,
                    signedVerifiers,
                ),
            )
        }.getOrDefault(OwnerState.UNAVAILABLE)
    }

    private data class OwnerState(
        val passive: RemoteMcpToolPolicyOwnerSnapshot,
        val registry: RemoteMcpToolPolicyRegistry,
    ) {
        companion object {
            val UNAVAILABLE = OwnerState(
                RemoteMcpToolPolicyOwnerSnapshot(
                    storeAvailable = false,
                    current = false,
                    revision = 0L,
                    approvedPolicyCount = 0,
                ),
                RemoteMcpToolPolicyRegistry(emptyList()),
            )

            fun stale(revision: Long) = OwnerState(
                RemoteMcpToolPolicyOwnerSnapshot(
                    storeAvailable = true,
                    current = false,
                    revision = revision,
                    approvedPolicyCount = 0,
                ),
                RemoteMcpToolPolicyRegistry(emptyList()),
            )
        }
    }
}
