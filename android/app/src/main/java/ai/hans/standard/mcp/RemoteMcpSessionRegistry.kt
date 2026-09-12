package ai.hans.standard.mcp

import android.content.Context
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal fun interface RemoteMcpSessionFactory {
    fun open(definition: RemoteMcpActivationDefinition): RemoteMcpSession
}

internal data class RemoteMcpFinalizedActivationDescriptor(
    val identity: RemoteMcpActivationIdentity,
    val metadataDigest: String,
)

internal data class RemoteMcpSessionRegistryRecoverySnapshot(
    val available: Boolean,
    val pending: List<RemoteMcpActivationRecoveryDescriptor>,
    val finalized: List<RemoteMcpFinalizedActivationDescriptor>,
)

/**
 * Process-safe activation registry for Remote MCP sessions.
 *
 * PREPARED and COMMITTED definitions are durable recovery evidence only. Session construction,
 * discovery and calls are possible exclusively for a FINALIZED exact identity. This keeps plugin
 * installation atomic even when Android kills the process between any two transitions.
 */
internal class RemoteMcpSessionRegistry internal constructor(
    directory: File,
    private val sessionFactory: RemoteMcpSessionFactory,
) : RemoteMcpActivationTransaction, Closeable {
    constructor(
        context: Context,
        sessionFactory: RemoteMcpSessionFactory,
    ) : this(
        File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME),
        sessionFactory,
    )

    private val stateFile = RemoteMcpPrivateStateFile(directory, FILE_NAME, MAX_DOCUMENT_BYTES)
    private val lock = Any()
    private var loadFailed = false
    private var closed = false
    private var state = RegistryState.EMPTY
    private val sessions = linkedMapOf<ActivationKey, SessionHolder>()

    init {
        val loaded = runCatching { readState() }
        loaded.onSuccess { state = it }
        loaded.onFailure { loadFailed = true }
    }

    override fun prepare(
        definition: RemoteMcpActivationDefinition,
    ): RemoteMcpActivationReceipt = synchronized(lock) {
        ensureMutable()
        require(state.transactions.size < MAX_PENDING_TRANSACTIONS) {
            "Too many pending Remote MCP activations"
        }
        val key = definition.identity.key
        require(state.finalized.size < MAX_FINALIZED_ACTIVATIONS ||
            state.finalized.any { it.definition.identity.key == key }) {
            "Too many finalized Remote MCP activations"
        }
        require(state.transactions.none { it.definition.identity.key == key }) {
            "Remote MCP activation already pending"
        }
        val transactionId = UUID.randomUUID().toString().replace("-", "")
        val transaction = StoredActivationTransaction(
            transactionId = transactionId,
            phase = RemoteMcpActivationPhase.PREPARED,
            definition = definition,
            metadataDigest = definition.metadataDigest,
        )
        val replacement = state.copy(transactions = state.transactions + transaction)
        persist(replacement)
        state = replacement
        transaction.receipt
    }

    override fun commit(receipt: RemoteMcpActivationReceipt) = synchronized(lock) {
        ensureMutable()
        val index = requireTransaction(receipt, RemoteMcpActivationPhase.PREPARED)
        val committed = state.transactions[index].copy(phase = RemoteMcpActivationPhase.COMMITTED)
        val replacement = state.copy(
            transactions = state.transactions.toMutableList().also { it[index] = committed },
        )
        persist(replacement)
        state = replacement
    }

    override fun finalize(receipt: RemoteMcpActivationReceipt) {
        val replacedSession = synchronized(lock) {
            ensureMutable()
            val index = requireTransaction(receipt, RemoteMcpActivationPhase.COMMITTED)
            val transaction = state.transactions[index]
            val key = transaction.definition.identity.key
            val active = StoredFinalizedActivation(transaction.definition)
            val replacement = RegistryState(
                finalized = state.finalized.filterNot { it.definition.identity.key == key } + active,
                transactions = state.transactions.filterIndexed { candidate, _ -> candidate != index },
            )
            persist(replacement)
            state = replacement
            sessions.remove(key)
        }
        // Closing can cancel an old network call, so it stays outside the registry monitor.
        replacedSession?.session?.close()
    }

    override fun rollback(receipt: RemoteMcpActivationReceipt): Boolean = synchronized(lock) {
        ensureMutable()
        val index = state.transactions.indexOfFirst { it.transactionId == receipt.transactionId }
        if (index < 0) return@synchronized false
        requireReceipt(state.transactions[index], receipt)
        val replacement = state.copy(
            transactions = state.transactions.filterIndexed { candidate, _ -> candidate != index },
        )
        persist(replacement)
        state = replacement
        true
    }

    override fun pendingRecovery(): List<RemoteMcpActivationRecoveryDescriptor> = synchronized(lock) {
        if (loadFailed) return@synchronized emptyList()
        state.transactions.sortedBy(StoredActivationTransaction::transactionId).map {
            RemoteMcpActivationRecoveryDescriptor(
                transactionId = it.transactionId,
                identity = it.definition.identity,
                metadataDigest = it.metadataDigest,
                phase = it.phase,
            )
        }
    }

    /** Complete network-free activation inventory for one plugin uninstall identity proof. */
    fun recoverySnapshot(pluginId: String): RemoteMcpSessionRegistryRecoverySnapshot =
        synchronized(lock) {
            if (loadFailed || closed) {
                return@synchronized RemoteMcpSessionRegistryRecoverySnapshot(
                    available = false,
                    pending = emptyList(),
                    finalized = emptyList(),
                )
            }
            RemoteMcpSessionRegistryRecoverySnapshot(
                available = true,
                pending = state.transactions
                    .filter { it.definition.identity.pluginId == pluginId }
                    .sortedBy(StoredActivationTransaction::transactionId)
                    .map {
                        RemoteMcpActivationRecoveryDescriptor(
                            transactionId = it.transactionId,
                            identity = it.definition.identity,
                            metadataDigest = it.metadataDigest,
                            phase = it.phase,
                        )
                    },
                finalized = state.finalized
                    .filter { it.definition.identity.pluginId == pluginId }
                    .map {
                        RemoteMcpFinalizedActivationDescriptor(
                            identity = it.definition.identity,
                            metadataDigest = it.definition.metadataDigest,
                        )
                    }
                    .sortedWith(compareBy(
                        { it.identity.serverId },
                        { it.identity.configurationDigest },
                    )),
            )
        }

    /** Reconstructs an opaque cursor only when the durable descriptor still matches exactly. */
    fun receiptForRecovery(
        descriptor: RemoteMcpActivationRecoveryDescriptor,
    ): RemoteMcpActivationReceipt = synchronized(lock) {
        ensureAvailable()
        val transaction = state.transactions.singleOrNull {
            it.transactionId == descriptor.transactionId
        } ?: throw RemoteMcpFailure("mcp_activation_recovery_missing")
        require(transaction.definition.identity == descriptor.identity &&
            transaction.metadataDigest == descriptor.metadataDigest &&
            transaction.phase == descriptor.phase) {
            "Remote MCP activation recovery identity changed"
        }
        transaction.receipt
    }

    /** Network-free proof used by the plugin install journal after a finalize crash window. */
    fun proveFinalized(
        identity: RemoteMcpActivationIdentity,
        metadataDigest: String,
    ): RemoteMcpFinalizedActivationProof = synchronized(lock) {
        if (loadFailed || closed) return@synchronized RemoteMcpFinalizedActivationProof.UNAVAILABLE
        require(metadataDigest.matches(SHA_256))
        val active = state.finalized.singleOrNull {
            it.definition.identity.key == identity.key
        } ?: return@synchronized RemoteMcpFinalizedActivationProof.MISSING
        if (active.definition.identity == identity &&
            active.definition.metadataDigest == metadataDigest
        ) {
            RemoteMcpFinalizedActivationProof.EXACT
        } else {
            RemoteMcpFinalizedActivationProof.CHANGED
        }
    }

    /** Exact private definition for a finalized router; it never opens the network session. */
    fun finalizedDefinition(
        identity: RemoteMcpActivationIdentity,
    ): RemoteMcpActivationDefinition = synchronized(lock) {
        ensureUsable()
        requireFinalized(identity).definition
    }

    /** Discovery cannot be reached for PREPARED/COMMITTED state. */
    fun discover(
        identity: RemoteMcpActivationIdentity,
        cancellation: RemoteMcpCancellation = RemoteMcpCancellation.NONE,
    ): RemoteMcpDiscoveryReceipt = finalizedSession(identity).discover(cancellation)

    /** Internal bridge for the existing dynamic-tool surface; only exact finalized state resolves. */
    fun finalizedSession(identity: RemoteMcpActivationIdentity): RemoteMcpSession {
        return synchronized(lock) {
            ensureUsable()
            val active = requireFinalized(identity)
            val key = identity.key
            sessions[key]?.let { existing ->
                require(existing.identity == identity) { "Remote MCP session identity changed" }
                return@synchronized existing.session
            }
            val session = try {
                sessionFactory.open(active.definition)
            } catch (_: Throwable) {
                throw RemoteMcpFailure("mcp_session_open_failed")
            }
            if (session.activationIdentity != identity) {
                runCatching(session::close)
                throw RemoteMcpFailure("mcp_session_identity_mismatch")
            }
            sessions[key] = SessionHolder(identity, session)
            session
        }
    }

    fun cancelInvocation(
        identity: RemoteMcpActivationIdentity,
        invocation: RemoteMcpInvocation,
    ): Boolean {
        val session = synchronized(lock) {
            ensureUsable()
            requireFinalized(identity)
            sessions[identity.key]?.also { holder ->
                require(holder.identity == identity) { "Remote MCP session identity changed" }
            }?.session
        } ?: return false
        return session.cancel(invocation)
    }

    /** Closes the process-local session but preserves finalized durable activation for lazy reopen. */
    fun closeSession(identity: RemoteMcpActivationIdentity): Boolean {
        val session = synchronized(lock) {
            ensureUsable()
            requireFinalized(identity)
            sessions.remove(identity.key)?.session
        } ?: return false
        session.close()
        return true
    }

    /**
     * Policy changes invalidate only an already-open exact process session. Unlike the public
     * finalized-session boundary, this operation is also valid before first installation and is a
     * no-op when no process session exists.
     */
    internal fun closeSessionIfPresent(identity: RemoteMcpActivationIdentity): Boolean {
        val session = synchronized(lock) {
            ensureUsable()
            sessions[identity.key]?.takeIf { it.identity == identity }?.let {
                sessions.remove(identity.key)
                it.session
            }
        } ?: return false
        session.close()
        return true
    }

    fun deactivate(identity: RemoteMcpActivationIdentity): RemoteMcpDeactivationResult =
        deactivateMatching(identity, expectedMetadataDigest = null)

    /** Exact uninstall boundary: both route identity and finalized metadata must still match. */
    fun deactivate(
        identity: RemoteMcpActivationIdentity,
        expectedMetadataDigest: String,
    ): RemoteMcpDeactivationResult {
        require(expectedMetadataDigest.matches(SHA_256))
        return deactivateMatching(identity, expectedMetadataDigest)
    }

    private fun deactivateMatching(
        identity: RemoteMcpActivationIdentity,
        expectedMetadataDigest: String?,
    ): RemoteMcpDeactivationResult {
        val removedSession: RemoteMcpSession?
        synchronized(lock) {
            if (loadFailed || closed) return RemoteMcpDeactivationResult.REGISTRY_UNAVAILABLE
            val key = identity.key
            if (state.transactions.any { it.definition.identity.key == key }) {
                return RemoteMcpDeactivationResult.TRANSACTION_PENDING
            }
            val active = state.finalized.singleOrNull { it.definition.identity.key == key }
                ?: return RemoteMcpDeactivationResult.MISSING
            if (active.definition.identity != identity) {
                return RemoteMcpDeactivationResult.STALE_IDENTITY
            }
            if (expectedMetadataDigest != null &&
                active.definition.metadataDigest != expectedMetadataDigest
            ) {
                return RemoteMcpDeactivationResult.STALE_IDENTITY
            }
            val replacement = state.copy(
                finalized = state.finalized.filterNot { it.definition.identity.key == key },
            )
            try {
                persist(replacement)
            } catch (_: Throwable) {
                return RemoteMcpDeactivationResult.REGISTRY_UNAVAILABLE
            }
            state = replacement
            removedSession = sessions.remove(key)?.session
        }
        removedSession?.close()
        return RemoteMcpDeactivationResult.DEACTIVATED
    }

    /**
     * Removes the previous configuration for one key after an exact finalized catalog has made
     * that key explicitly non-routable. The caller must prove that catalog before invoking this.
     */
    internal fun deactivateSupersededByFinalizedNoRoute(
        replacementIdentity: RemoteMcpActivationIdentity,
    ): RemoteMcpDeactivationResult {
        val removedSession: RemoteMcpSession?
        synchronized(lock) {
            if (loadFailed || closed) return RemoteMcpDeactivationResult.REGISTRY_UNAVAILABLE
            val key = replacementIdentity.key
            if (state.transactions.any { it.definition.identity.key == key }) {
                return RemoteMcpDeactivationResult.TRANSACTION_PENDING
            }
            if (state.finalized.none { it.definition.identity.key == key }) {
                return RemoteMcpDeactivationResult.MISSING
            }
            val replacement = state.copy(
                finalized = state.finalized.filterNot { it.definition.identity.key == key },
            )
            try {
                persist(replacement)
            } catch (_: Throwable) {
                return RemoteMcpDeactivationResult.REGISTRY_UNAVAILABLE
            }
            state = replacement
            removedSession = sessions.remove(key)?.session
        }
        removedSession?.close()
        return RemoteMcpDeactivationResult.DEACTIVATED
    }

    fun status(): RemoteMcpSessionRegistryStatus = synchronized(lock) {
        if (loadFailed) {
            return@synchronized RemoteMcpSessionRegistryStatus(
                available = false,
                finalizedActivationCount = 0,
                preparedTransactionCount = 0,
                committedTransactionCount = 0,
                activations = emptyList(),
            )
        }
        val finalized = state.finalized.map {
            RemoteMcpActivationStatus(
                pluginId = it.definition.identity.pluginId,
                serverId = it.definition.identity.serverId,
                phase = RemoteMcpActivationPhase.FINALIZED,
                sessionOpen = it.definition.identity.key in sessions,
            )
        }
        val pending = state.transactions.map {
            RemoteMcpActivationStatus(
                pluginId = it.definition.identity.pluginId,
                serverId = it.definition.identity.serverId,
                phase = it.phase,
                sessionOpen = false,
            )
        }
        RemoteMcpSessionRegistryStatus(
            available = !closed,
            finalizedActivationCount = finalized.size,
            preparedTransactionCount = pending.count { it.phase == RemoteMcpActivationPhase.PREPARED },
            committedTransactionCount = pending.count { it.phase == RemoteMcpActivationPhase.COMMITTED },
            activations = (finalized + pending).sortedWith(
                compareBy(
                    RemoteMcpActivationStatus::pluginId,
                    RemoteMcpActivationStatus::serverId,
                    RemoteMcpActivationStatus::phase,
                ),
            ),
        )
    }

    override fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            sessions.values.map(SessionHolder::session).also { sessions.clear() }
        }
        toClose.forEach { runCatching(it::close) }
    }

    private fun requireTransaction(
        receipt: RemoteMcpActivationReceipt,
        expectedPhase: RemoteMcpActivationPhase,
    ): Int {
        val index = state.transactions.indexOfFirst { it.transactionId == receipt.transactionId }
        if (index < 0) throw RemoteMcpFailure("mcp_activation_transaction_missing")
        val transaction = state.transactions[index]
        requireReceipt(transaction, receipt)
        require(transaction.phase == expectedPhase) { "Remote MCP activation phase changed" }
        return index
    }

    private fun requireReceipt(
        transaction: StoredActivationTransaction,
        receipt: RemoteMcpActivationReceipt,
    ) {
        require(transaction.receipt.matches(receipt)) { "Remote MCP activation receipt changed" }
    }

    private fun requireFinalized(identity: RemoteMcpActivationIdentity): StoredFinalizedActivation {
        val key = identity.key
        val active = state.finalized.singleOrNull { it.definition.identity.key == key }
            ?: throw RemoteMcpFailure("mcp_activation_not_finalized")
        if (active.definition.identity != identity) {
            throw RemoteMcpFailure("mcp_activation_stale_identity")
        }
        return active
    }

    private fun readState(): RegistryState {
        val bytes = stateFile.readOrNull() ?: return RegistryState.EMPTY
        return try {
            RemoteMcpSessionRegistryCodec.decode(bytes.toString(StandardCharsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }

    private fun persist(replacement: RegistryState) {
        val bytes = RemoteMcpSessionRegistryCodec.encode(replacement)
            .toByteArray(StandardCharsets.UTF_8)
        try {
            stateFile.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun ensureAvailable() {
        if (loadFailed) throw RemoteMcpFailure("mcp_activation_registry_unavailable")
    }

    private fun ensureMutable() {
        ensureAvailable()
        if (closed) throw RemoteMcpFailure("mcp_activation_registry_closed")
    }

    private fun ensureUsable() = ensureMutable()

    private companion object {
        const val DIRECTORY_NAME = "remote-mcp"
        const val FILE_NAME = "session-registry-v1.json"
        const val MAX_DOCUMENT_BYTES = 1024 * 1024
        const val MAX_FINALIZED_ACTIVATIONS = 128
        const val MAX_PENDING_TRANSACTIONS = 32
    }
}

private data class ActivationKey(val pluginId: String, val serverId: String)

private val RemoteMcpActivationIdentity.key: ActivationKey
    get() = ActivationKey(pluginId, serverId)

private data class SessionHolder(
    val identity: RemoteMcpActivationIdentity,
    val session: RemoteMcpSession,
)

private data class StoredFinalizedActivation(
    val definition: RemoteMcpActivationDefinition,
)

private data class StoredActivationTransaction(
    val transactionId: String,
    val phase: RemoteMcpActivationPhase,
    val definition: RemoteMcpActivationDefinition,
    val metadataDigest: String,
) {
    init {
        require(transactionId.matches(Regex("[0-9a-f]{32}")))
        require(phase != RemoteMcpActivationPhase.FINALIZED)
        require(metadataDigest == definition.metadataDigest)
    }

    val receipt: RemoteMcpActivationReceipt
        get() = RemoteMcpActivationReceipt(
            transactionId,
            definition.identity.pluginId,
            definition.identity.serverId,
            metadataDigest,
        )
}

private fun RemoteMcpActivationReceipt.matches(other: RemoteMcpActivationReceipt): Boolean =
    transactionId == other.transactionId &&
        pluginId == other.pluginId &&
        serverId == other.serverId &&
        metadataDigest == other.metadataDigest

private data class RegistryState(
    val finalized: List<StoredFinalizedActivation>,
    val transactions: List<StoredActivationTransaction>,
) {
    init {
        require(finalized.size <= 128 && transactions.size <= 32)
        require(finalized.map { it.definition.identity.key }.distinct().size == finalized.size)
        require(transactions.map(StoredActivationTransaction::transactionId).distinct().size ==
            transactions.size)
        require(transactions.map { it.definition.identity.key }.distinct().size == transactions.size)
    }

    companion object {
        val EMPTY = RegistryState(emptyList(), emptyList())
    }
}

private object RemoteMcpSessionRegistryCodec {
    private const val SCHEMA = "hans.remote-mcp-session-registry"
    private const val VERSION = 1

    fun encode(state: RegistryState): String = JSONObject()
        .put("schema", SCHEMA)
        .put("version", VERSION)
        .put(
            "finalized",
            JSONArray(
                state.finalized.sortedWith(
                    compareBy(
                        { it.definition.identity.pluginId },
                        { it.definition.identity.serverId },
                    ),
                ).map { encodeDefinition(it.definition) },
            ),
        )
        .put(
            "transactions",
            JSONArray(state.transactions.sortedBy { it.transactionId }.map(::encodeTransaction)),
        )
        .toString()

    fun decode(raw: String): RegistryState {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= 1024 * 1024)
        val root = JSONObject(raw)
        requireExactKeys(root, "schema", "version", "finalized", "transactions")
        require(root.getString("schema") == SCHEMA && root.getInt("version") == VERSION)
        val finalizedArray = root.getJSONArray("finalized")
        val transactionArray = root.getJSONArray("transactions")
        require(finalizedArray.length() <= 128 && transactionArray.length() <= 32)
        val finalized = (0 until finalizedArray.length()).map {
            StoredFinalizedActivation(decodeDefinition(finalizedArray.getJSONObject(it)))
        }
        val transactions = (0 until transactionArray.length()).map {
            decodeTransaction(transactionArray.getJSONObject(it))
        }
        val state = RegistryState(finalized, transactions)
        require(encode(state) == raw) { "Remote MCP registry is not canonical" }
        return state
    }

    private fun encodeTransaction(value: StoredActivationTransaction) = JSONObject()
        .put("transactionId", value.transactionId)
        .put("phase", value.phase.name)
        .put("metadataDigest", value.metadataDigest)
        .put("definition", encodeDefinition(value.definition))

    private fun decodeTransaction(value: JSONObject): StoredActivationTransaction {
        requireExactKeys(value, "transactionId", "phase", "metadataDigest", "definition")
        val phase = RemoteMcpActivationPhase.valueOf(value.getString("phase"))
        return StoredActivationTransaction(
            transactionId = value.getString("transactionId"),
            phase = phase,
            definition = decodeDefinition(value.getJSONObject("definition")),
            metadataDigest = value.getString("metadataDigest"),
        )
    }

    private fun encodeDefinition(value: RemoteMcpActivationDefinition) = JSONObject()
        .put("pluginId", value.identity.pluginId)
        .put("configurationDigest", value.identity.configurationDigest)
        .put("requirement", RemoteMcpConfigurationIdentity.canonicalJson(value.requirement))

    private fun decodeDefinition(value: JSONObject): RemoteMcpActivationDefinition {
        requireExactKeys(value, "pluginId", "configurationDigest", "requirement")
        val requirement = RemoteMcpConfigurationIdentity.decodeRequirement(
            value.getJSONObject("requirement"),
        )
        return RemoteMcpActivationDefinition(
            identity = RemoteMcpActivationIdentity(
                pluginId = value.getString("pluginId"),
                serverId = requirement.id,
                configurationDigest = value.getString("configurationDigest"),
            ),
            requirement = requirement,
        )
    }
}
