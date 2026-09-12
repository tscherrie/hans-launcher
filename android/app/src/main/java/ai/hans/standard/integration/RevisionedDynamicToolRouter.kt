package ai.hans.standard.integration

import ai.hans.standard.codex.CompositeDynamicToolExecutor
import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.codex.DynamicToolLimits
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

internal enum class DynamicToolPlacement {
    BACKGROUND_ALLOWED,
    INTERACTIVE_ONLY,
}

internal class DynamicToolContributor(
    val interactiveExecutor: DynamicToolExecutor,
    val backgroundExecutor: DynamicToolExecutor?,
    revisionToken: String = "",
) {
    private val revisionTokenBytes = revisionToken.toByteArray(StandardCharsets.UTF_8).also { bytes ->
        require(bytes.size <= MAX_DYNAMIC_TOOL_REVISION_TOKEN_BYTES) {
            "Dynamic tool contributor revision token is too large"
        }
    }

    constructor(
        executor: DynamicToolExecutor,
        placement: DynamicToolPlacement = DynamicToolPlacement.INTERACTIVE_ONLY,
        revisionToken: String = "",
    ) : this(
        interactiveExecutor = executor,
        backgroundExecutor = executor.takeIf {
            placement == DynamicToolPlacement.BACKGROUND_ALLOWED
        },
        revisionToken = revisionToken,
    )

    /** Adds the private identity token without ever projecting it into advertised tool specs. */
    internal fun updateRevisionDigest(digest: MessageDigest, ordinal: Int) {
        digest.update("contributor:$ordinal:".toByteArray(StandardCharsets.US_ASCII))
        digest.update(revisionTokenBytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(revisionTokenBytes)
        digest.update('\n'.code.toByte())
    }
}

/**
 * Immutable App Server tool contract. A client leases exactly one snapshot for its whole
 * generation; a later plugin activation can therefore never change routing below a resumed
 * thread or an in-flight call.
 */
internal class RevisionedDynamicToolSnapshot private constructor(
    val revision: String,
    val interactive: DynamicToolExecutor,
    val background: DynamicToolExecutor,
    val interactiveSpecs: List<DynamicToolNamespaceSpec>,
    val backgroundSpecs: List<DynamicToolNamespaceSpec>,
) {
    companion object {
        fun create(contributors: List<DynamicToolContributor>): RevisionedDynamicToolSnapshot {
            require(contributors.isNotEmpty()) { "Dynamic tool contract is empty" }
            require(contributors.size <= MAX_DYNAMIC_TOOL_CONTRIBUTORS) {
                "Too many dynamic tool contributors"
            }
            val frozenContributors = contributors.map { contributor ->
                DynamicToolContributor(
                    interactiveExecutor = FrozenDynamicToolExecutor(
                        contributor.interactiveExecutor,
                    ),
                    backgroundExecutor = contributor.backgroundExecutor?.let {
                        FrozenDynamicToolExecutor(it)
                    },
                )
            }
            frozenContributors.forEach(::requireBackgroundContractSubset)
            val allSpecs = freezeSpecs(
                frozenContributors.flatMap { it.interactiveExecutor.specs },
            )
            require(allSpecs.size in 1..DynamicToolLimits.MAX_NAMESPACES) {
                "Invalid dynamic tool namespace count"
            }
            require(allSpecs.map(DynamicToolNamespaceSpec::name).distinct().size == allSpecs.size) {
                "Duplicate dynamic tool namespace"
            }
            require(allSpecs.sumOf { it.tools.size } <= DynamicToolLimits.MAX_FUNCTIONS) {
                "Too many dynamic tool functions"
            }
            val backgroundExecutors = frozenContributors.mapNotNull { it.backgroundExecutor }
            require(backgroundExecutors.isNotEmpty()) {
                "At least one background-safe dynamic tool contributor is required"
            }
            val backgroundSpecs = freezeSpecs(backgroundExecutors.flatMap { it.specs })
            require(backgroundSpecs.isNotEmpty()) {
                "Background-safe dynamic tool contract is empty"
            }
            val interactiveExecutor = FrozenDynamicToolExecutor(
                CompositeDynamicToolExecutor(
                    frozenContributors.map { it.interactiveExecutor },
                ),
            )
            val backgroundExecutor = FrozenDynamicToolExecutor(
                CompositeDynamicToolExecutor(backgroundExecutors),
            )
            return RevisionedDynamicToolSnapshot(
                revision = revisionFor(allSpecs, backgroundSpecs, contributors),
                interactive = interactiveExecutor,
                background = backgroundExecutor,
                interactiveSpecs = interactiveExecutor.specs,
                backgroundSpecs = backgroundExecutor.specs,
            )
        }

        private fun revisionFor(
            specs: List<DynamicToolNamespaceSpec>,
            backgroundSpecs: List<DynamicToolNamespaceSpec>,
            contributors: List<DynamicToolContributor>,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("hans-revisioned-dynamic-tools-v2\n".toByteArray(StandardCharsets.US_ASCII))
            digest.update(DynamicToolContractFingerprint.compute(specs).toByteArray(StandardCharsets.US_ASCII))
            digest.update(0)
            digest.update(
                DynamicToolContractFingerprint.compute(backgroundSpecs)
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            digest.update(0)
            contributors.forEachIndexed { index, contributor ->
                contributor.updateRevisionDigest(digest, index)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun requireBackgroundContractSubset(contributor: DynamicToolContributor) {
            val background = contributor.backgroundExecutor ?: return
            val interactiveByNamespace = contributor.interactiveExecutor.specs.associateBy { it.name }
            require(interactiveByNamespace.size == contributor.interactiveExecutor.specs.size) {
                "Duplicate interactive dynamic tool namespace"
            }
            background.specs.forEach { backgroundNamespace ->
                val interactiveNamespace = requireNotNull(
                    interactiveByNamespace[backgroundNamespace.name],
                ) { "Background dynamic tool namespace is not interactive" }
                require(backgroundNamespace.description == interactiveNamespace.description) {
                    "Background dynamic tool namespace description differs"
                }
                val interactiveTools = interactiveNamespace.tools.associateBy { it.name }
                require(interactiveTools.size == interactiveNamespace.tools.size) {
                    "Duplicate interactive dynamic tool name"
                }
                backgroundNamespace.tools.forEach { backgroundTool ->
                    require(interactiveTools[backgroundTool.name] == backgroundTool) {
                        "Background dynamic tool contract is not an exact interactive subset"
                    }
                }
            }
        }

        private fun freezeSpecs(
            specs: List<DynamicToolNamespaceSpec>,
        ): List<DynamicToolNamespaceSpec> = Collections.unmodifiableList(
            specs.map { namespace ->
                namespace.copy(
                    tools = Collections.unmodifiableList(namespace.tools.map { it.copy() }),
                )
            },
        )
    }
}

/** Captures the advertised catalog once while retaining the contributor's execution boundary. */
private class FrozenDynamicToolExecutor(
    private val delegate: DynamicToolExecutor,
) : DynamicToolExecutor {
    override val specs: List<DynamicToolNamespaceSpec> = freeze(delegate.specs)
    private val toolsByNamespace: Map<String, Set<String>> = specs.associate { namespace ->
        namespace.name to namespace.tools.mapTo(linkedSetOf()) { it.name }
    }

    override fun execute(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        val rejection = rejectionCode(call)
        if (rejection != null) {
            completion(delegate.failureResult(call, rejection))
        } else {
            delegate.execute(call, completion)
        }
    }

    override fun executeCancellable(
        call: DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle {
        val rejection = rejectionCode(call)
        if (rejection == null) {
            return delegate.executeCancellable(call, cancellation, completion)
        }
        completion(delegate.failureResult(call, rejection))
        return NoEffectDynamicToolExecutionHandle
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = delegate.failureResult(call, code)

    private fun rejectionCode(call: DynamicToolCallParams): String? {
        val tools = call.namespace?.let(toolsByNamespace::get)
            ?: return "unknown_dynamic_tool_namespace"
        return if (call.tool in tools) null else "unknown_dynamic_tool"
    }

    private companion object {
        fun freeze(specs: List<DynamicToolNamespaceSpec>): List<DynamicToolNamespaceSpec> =
            Collections.unmodifiableList(
                specs.map { namespace ->
                    namespace.copy(
                        tools = Collections.unmodifiableList(namespace.tools.map { it.copy() }),
                    )
                },
            )
    }
}

private object NoEffectDynamicToolExecutionHandle : DynamicToolExecutionHandle {
    override fun cancel() =
        DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT
}

/**
 * Process-owned snapshot selector. Publication is atomic and performs no network or polling.
 * Superseded snapshots remain executable only through leases already issued to an older client.
 */
internal class RevisionedDynamicToolRouter(initial: RevisionedDynamicToolSnapshot) {
    private val lock = Any()
    private var selected = initial
    private val snapshots = linkedMapOf(initial.revision to SnapshotState(initial))
    private val knownRevisions = linkedSetOf(initial.revision)

    fun currentRevision(): String = synchronized(lock) { selected.revision }

    fun currentSnapshot(): RevisionedDynamicToolSnapshot = synchronized(lock) { selected }

    fun publish(candidate: RevisionedDynamicToolSnapshot): DynamicToolSnapshotPublication =
        synchronized(lock) {
            if (candidate.revision == selected.revision) {
                return@synchronized DynamicToolSnapshotPublication.Unchanged(selected.revision)
            }
            require(candidate.revision !in knownRevisions) { "Stale dynamic tool revision" }
            require(knownRevisions.size < MAX_DYNAMIC_TOOL_REVISIONS) {
                "Dynamic tool revision capacity exhausted"
            }
            knownRevisions += candidate.revision
            snapshots[candidate.revision] = SnapshotState(candidate)
            val previous = selected
            selected = candidate
            prune(previous.revision)
            DynamicToolSnapshotPublication.Changed(previous.revision, candidate.revision)
        }

    /** Discards a published candidate which never became an installed client generation. */
    fun abortUnactivated(publication: DynamicToolSnapshotPublication.Changed) = synchronized(lock) {
        require(selected.revision == publication.revision) {
            "Dynamic tool publication is no longer the selected candidate"
        }
        val candidate = requireNotNull(snapshots[publication.revision])
        require(candidate.leases == 0) { "Activated dynamic tool revision cannot be aborted" }
        val previous = requireNotNull(snapshots[publication.previousRevision]) {
            "Previous dynamic tool revision is no longer retained"
        }
        selected = previous.snapshot
        snapshots.remove(publication.revision)
        check(knownRevisions.remove(publication.revision)) {
            "Dynamic tool candidate revision was not recorded"
        }
    }

    fun acquire(
        revision: String = currentRevision(),
        onQuiescent: () -> Unit = {},
    ): RevisionedDynamicToolLease =
        synchronized(lock) {
            val state = requireNotNull(snapshots[revision]) { "Unknown dynamic tool revision" }
            state.leases += 1
            RevisionedDynamicToolLease(
                snapshot = state.snapshot,
                release = { release(revision) },
                onQuiescent = onQuiescent,
            )
        }

    private fun release(revision: String) = synchronized(lock) {
        val state = requireNotNull(snapshots[revision]) { "Unknown dynamic tool revision" }
        check(state.leases > 0) { "Dynamic tool lease underflow" }
        state.leases -= 1
        prune(revision)
    }

    private fun prune(revision: String) {
        val state = snapshots[revision] ?: return
        if (revision != selected.revision && state.leases == 0) snapshots.remove(revision)
    }

    internal fun retainedRevisions(): Set<String> = synchronized(lock) { snapshots.keys.toSet() }

    internal fun hasSeenRevision(revision: String): Boolean = synchronized(lock) {
        revision in knownRevisions
    }

    internal fun canAcceptNewRevision(): Boolean = synchronized(lock) {
        knownRevisions.size < MAX_DYNAMIC_TOOL_REVISIONS
    }

    private data class SnapshotState(
        val snapshot: RevisionedDynamicToolSnapshot,
        var leases: Int = 0,
    )
}

internal sealed interface DynamicToolSnapshotPublication {
    data class Unchanged(val revision: String) : DynamicToolSnapshotPublication
    data class Changed(val previousRevision: String, val revision: String) :
        DynamicToolSnapshotPublication
}

/** A generation-bound executor. After close, every late App Server call fails closed. */
internal class RevisionedDynamicToolLease internal constructor(
    val snapshot: RevisionedDynamicToolSnapshot,
    private val release: () -> Unit,
    private val onQuiescent: () -> Unit,
) : DynamicToolExecutor, Closeable {
    private val lock = Any()
    private var closeRequested = false
    private var inFlight = 0
    private var released = false

    override val specs: List<DynamicToolNamespaceSpec> = snapshot.interactiveSpecs

    fun backgroundExecutor(): DynamicToolExecutor = GuardedExecutor(
        snapshot.background,
        ::beginCall,
        ::finishCall,
        "stale_dynamic_tool_revision",
    )

    override fun execute(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) = guarded().execute(call, completion)

    override fun executeCancellable(
        call: DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle = guarded().executeCancellable(call, cancellation, completion)

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = snapshot.interactive.failureResult(call, code)

    internal fun hasInFlightCalls(): Boolean = synchronized(lock) { inFlight > 0 }

    override fun close() {
        val shouldRelease = synchronized(lock) {
            if (closeRequested) return
            closeRequested = true
            claimReleaseLocked()
        }
        if (shouldRelease) release()
    }

    private fun guarded(): DynamicToolExecutor = GuardedExecutor(
        snapshot.interactive,
        ::beginCall,
        ::finishCall,
        "stale_dynamic_tool_revision",
    )

    private fun beginCall(): Boolean = synchronized(lock) {
        if (closeRequested) return@synchronized false
        inFlight += 1
        true
    }

    private fun finishCall() {
        val (shouldRelease, becameQuiescent) = synchronized(lock) {
            check(inFlight > 0) { "Dynamic tool lease call underflow" }
            inFlight -= 1
            claimReleaseLocked() to (inFlight == 0)
        }
        if (shouldRelease) release()
        if (becameQuiescent) runCatching(onQuiescent)
    }

    private fun claimReleaseLocked(): Boolean {
        if (!closeRequested || inFlight != 0 || released) return false
        released = true
        return true
    }
}

private class GuardedExecutor(
    private val delegate: DynamicToolExecutor,
    private val beginCall: () -> Boolean,
    private val finishCall: () -> Unit,
    private val staleCode: String,
) : DynamicToolExecutor {
    override val specs: List<DynamicToolNamespaceSpec> = delegate.specs

    override fun execute(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        if (!beginCall()) {
            completion(delegate.failureResult(call, staleCode))
            return
        }
        val finished = AtomicBoolean(false)
        fun completeOnce(result: DynamicToolExecutionResult) {
            if (!finished.compareAndSet(false, true)) return
            try {
                completion(result)
            } finally {
                finishCall()
            }
        }
        try {
            delegate.execute(call, ::completeOnce)
        } catch (failure: Throwable) {
            if (finished.compareAndSet(false, true)) finishCall()
            throw failure
        }
    }

    override fun executeCancellable(
        call: DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle {
        if (!beginCall()) {
            completion(delegate.failureResult(call, staleCode))
            return object : DynamicToolExecutionHandle {
                override fun cancel() =
                    DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT
            }
        }
        val finished = AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) finishCall()
        }
        val delegateHandle = try {
            delegate.executeCancellable(call, cancellation) { result ->
                if (finished.compareAndSet(false, true)) {
                    try {
                        completion(result)
                    } finally {
                        finishCall()
                    }
                }
            }
        } catch (failure: Throwable) {
            finishOnce()
            throw failure
        }
        return object : DynamicToolExecutionHandle {
            override fun cancel(): DynamicToolCancellationDisposition {
                return try {
                    delegateHandle.cancel()
                } finally {
                    // Cancellation is the logical end of this generation's call even when an
                    // external effect may already be completing independently. The delegate's
                    // task and callback retain their executor; the router lease must not leak
                    // merely because a cancellation-aware delegate suppresses completion.
                    finishOnce()
                }
            }
        }
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = delegate.failureResult(call, code)
}

private const val MAX_DYNAMIC_TOOL_CONTRIBUTORS = 256
private const val MAX_DYNAMIC_TOOL_REVISION_TOKEN_BYTES = 512
private const val MAX_DYNAMIC_TOOL_REVISIONS = 1_024
