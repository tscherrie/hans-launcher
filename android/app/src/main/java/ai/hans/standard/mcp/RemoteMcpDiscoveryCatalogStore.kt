package ai.hans.standard.mcp

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class RemoteMcpCatalogRouteState {
    READY,
    CONNECTION_REQUIRED,
    UNAVAILABLE,
}

internal enum class RemoteMcpCatalogPhase {
    PREPARED,
    COMMITTED,
    FINALIZED,
}

/**
 * Exact, bounded discovery result selected during plugin preflight.
 *
 * Only allowlisted tools with a trusted policy reach this object. The schemas stay in Hans'
 * private no-backup store; the outer plugin journal receives only [metadataDigest].
 */
internal class RemoteMcpDiscoveryCatalogDefinition(
    val transactionId: String,
    val identity: RemoteMcpActivationIdentity,
    val routeState: RemoteMcpCatalogRouteState,
    tools: List<RemoteMcpTool>,
    val activationTransactionId: String?,
    val activationMetadataDigest: String?,
) {
    val tools: List<RemoteMcpTool> = canonicalTools(tools)
    val catalogDigest: String = digestCatalog(this.tools)
    val metadataDigest: String = digestMetadata(
        identity = identity,
        routeState = routeState,
        catalogDigest = catalogDigest,
        activationTransactionId = activationTransactionId,
        activationMetadataDigest = activationMetadataDigest,
    )

    init {
        require(transactionId.matches(OPAQUE_TRANSACTION_ID)) {
            "Invalid Remote MCP catalog transaction id"
        }
        require((activationTransactionId == null) == (activationMetadataDigest == null)) {
            "Remote MCP activation recovery identity is incomplete"
        }
        activationTransactionId?.let {
            require(it.matches(OPAQUE_TRANSACTION_ID)) {
                "Invalid Remote MCP activation transaction id"
            }
        }
        activationMetadataDigest?.let {
            require(it.matches(SHA_256)) { "Invalid Remote MCP activation metadata digest" }
        }
        when (routeState) {
            RemoteMcpCatalogRouteState.READY -> {
                require(this.tools.isNotEmpty()) { "A routable Remote MCP catalog is empty" }
                require(activationTransactionId != null) {
                    "A routable Remote MCP catalog has no activation receipt"
                }
            }
            RemoteMcpCatalogRouteState.CONNECTION_REQUIRED,
            RemoteMcpCatalogRouteState.UNAVAILABLE,
            -> {
                require(this.tools.isEmpty()) { "A non-routable Remote MCP catalog has tools" }
                require(activationTransactionId == null) {
                    "A non-routable Remote MCP catalog has an activation receipt"
                }
            }
        }
        require(encodedCatalog(this.tools).toByteArray(StandardCharsets.UTF_8).size <=
            MAX_CATALOG_BYTES) {
            "Remote MCP catalog is too large"
        }
    }

    internal companion object {
        fun ready(
            activation: RemoteMcpActivationReceipt,
            identity: RemoteMcpActivationIdentity,
            discovery: RemoteMcpDiscoveryReceipt,
        ): RemoteMcpDiscoveryCatalogDefinition {
            require(activation.pluginId == identity.pluginId && activation.serverId == identity.serverId) {
                "Remote MCP activation/catalog identity changed"
            }
            val byName = discovery.tools.associateBy(RemoteMcpTool::name)
            require(byName.size == discovery.tools.size) { "Remote MCP discovery contains duplicates" }
            val selected = discovery.allowedToolNames.sorted().map { name ->
                requireNotNull(byName[name]) { "Remote MCP allowed tool has no catalog entry" }
            }
            return RemoteMcpDiscoveryCatalogDefinition(
                transactionId = activation.transactionId,
                identity = identity,
                routeState = RemoteMcpCatalogRouteState.READY,
                tools = selected,
                activationTransactionId = activation.transactionId,
                activationMetadataDigest = activation.metadataDigest,
            )
        }

        fun withoutRoute(
            identity: RemoteMcpActivationIdentity,
            routeState: RemoteMcpCatalogRouteState,
        ): RemoteMcpDiscoveryCatalogDefinition {
            require(routeState != RemoteMcpCatalogRouteState.READY)
            return RemoteMcpDiscoveryCatalogDefinition(
                transactionId = UUID.randomUUID().toString().replace("-", ""),
                identity = identity,
                routeState = routeState,
                tools = emptyList(),
                activationTransactionId = null,
                activationMetadataDigest = null,
            )
        }
    }
}

internal class RemoteMcpDiscoveryCatalogReceipt internal constructor(
    internal val transactionId: String,
    val pluginId: String,
    val serverId: String,
    internal val metadataDigest: String,
) {
    override fun toString(): String =
        "RemoteMcpDiscoveryCatalogReceipt(pluginId=$pluginId, serverId=$serverId)"
}

internal data class RemoteMcpDiscoveryCatalogRecoveryDescriptor(
    val transactionId: String,
    val identity: RemoteMcpActivationIdentity,
    val routeState: RemoteMcpCatalogRouteState,
    val catalogDigest: String,
    val metadataDigest: String,
    val activationTransactionId: String?,
    val activationMetadataDigest: String?,
    val phase: RemoteMcpCatalogPhase,
)

internal data class RemoteMcpFinalizedDiscoveryCatalog(
    val identity: RemoteMcpActivationIdentity,
    val routeState: RemoteMcpCatalogRouteState,
    val catalogDigest: String,
    val metadataDigest: String,
    val discovery: RemoteMcpDiscoveryReceipt,
) {
    val routeEligible: Boolean
        get() = routeState == RemoteMcpCatalogRouteState.READY &&
            discovery.allowedToolNames.isNotEmpty()
}

internal data class RemoteMcpDiscoveryCatalogRecoverySnapshot(
    val available: Boolean,
    val pending: List<RemoteMcpDiscoveryCatalogRecoveryDescriptor>,
    val finalized: List<RemoteMcpDiscoveryCatalogRecoveryDescriptor>,
)

internal enum class RemoteMcpCatalogDeactivationResult {
    DEACTIVATED,
    MISSING,
    STALE_IDENTITY,
    STALE_METADATA,
    TRANSACTION_PENDING,
    STORE_UNAVAILABLE,
}

/**
 * Private durable catalog store paired with [RemoteMcpSessionRegistry].
 *
 * PREPARED and COMMITTED records are recovery receipts only. Offline tool reconstruction and
 * routing metadata are exposed exclusively from an exact FINALIZED record.
 */
internal class RemoteMcpDiscoveryCatalogStore internal constructor(directory: File) {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME),
    )

    private val stateFile = RemoteMcpPrivateStateFile(directory, FILE_NAME, MAX_DOCUMENT_BYTES)
    private val lock = Any()
    private var loadFailed = false
    private var state = CatalogStoreState.EMPTY

    init {
        runCatching(::readState)
            .onSuccess { state = it }
            .onFailure { loadFailed = true }
    }

    fun prepare(
        definition: RemoteMcpDiscoveryCatalogDefinition,
    ): RemoteMcpDiscoveryCatalogReceipt = synchronized(lock) {
        ensureAvailable()
        require(state.transactions.size < MAX_PENDING_TRANSACTIONS) {
            "Too many pending Remote MCP catalogs"
        }
        require(state.finalized.size < MAX_FINALIZED_CATALOGS ||
            state.finalized.any { it.definition.identity.key == definition.identity.key }) {
            "Too many finalized Remote MCP catalogs"
        }
        require(state.transactions.none { it.definition.identity.key == definition.identity.key }) {
            "Remote MCP catalog transaction already pending"
        }
        require(state.transactions.none { it.definition.transactionId == definition.transactionId } &&
            state.finalized.none { it.definition.transactionId == definition.transactionId }) {
            "Remote MCP catalog transaction id already exists"
        }
        val transaction = StoredCatalogTransaction(RemoteMcpCatalogPhase.PREPARED, definition)
        val replacement = state.copy(transactions = state.transactions + transaction)
        persist(replacement)
        state = replacement
        transaction.receipt
    }

    fun commit(receipt: RemoteMcpDiscoveryCatalogReceipt) = synchronized(lock) {
        ensureAvailable()
        val index = requireTransaction(receipt, RemoteMcpCatalogPhase.PREPARED)
        val replacement = state.copy(
            transactions = state.transactions.toMutableList().also {
                it[index] = it[index].copy(phase = RemoteMcpCatalogPhase.COMMITTED)
            },
        )
        persist(replacement)
        state = replacement
    }

    fun finalize(receipt: RemoteMcpDiscoveryCatalogReceipt) = synchronized(lock) {
        ensureAvailable()
        val index = requireTransaction(receipt, RemoteMcpCatalogPhase.COMMITTED)
        val transaction = state.transactions[index]
        val key = transaction.definition.identity.key
        val replacement = CatalogStoreState(
            finalized = state.finalized.filterNot { it.definition.identity.key == key } +
                StoredFinalizedCatalog(transaction.definition),
            transactions = state.transactions.filterIndexed { candidate, _ -> candidate != index },
        )
        persist(replacement)
        state = replacement
    }

    fun rollback(receipt: RemoteMcpDiscoveryCatalogReceipt): Boolean = synchronized(lock) {
        ensureAvailable()
        val index = state.transactions.indexOfFirst {
            it.definition.transactionId == receipt.transactionId
        }
        if (index < 0) return@synchronized false
        requireReceipt(state.transactions[index], receipt)
        val replacement = state.copy(
            transactions = state.transactions.filterIndexed { candidate, _ -> candidate != index },
        )
        persist(replacement)
        state = replacement
        true
    }

    fun recoverySnapshot(pluginId: String): RemoteMcpDiscoveryCatalogRecoverySnapshot =
        synchronized(lock) {
            if (loadFailed) {
                return@synchronized RemoteMcpDiscoveryCatalogRecoverySnapshot(
                    available = false,
                    pending = emptyList(),
                    finalized = emptyList(),
                )
            }
            RemoteMcpDiscoveryCatalogRecoverySnapshot(
                available = true,
                pending = state.transactions
                    .filter { it.definition.identity.pluginId == pluginId }
                    .map(StoredCatalogTransaction::recovery),
                finalized = state.finalized
                    .filter { it.definition.identity.pluginId == pluginId }
                    .map { it.definition.recovery(RemoteMcpCatalogPhase.FINALIZED) },
            )
        }

    fun receiptForRecovery(
        descriptor: RemoteMcpDiscoveryCatalogRecoveryDescriptor,
    ): RemoteMcpDiscoveryCatalogReceipt = synchronized(lock) {
        ensureAvailable()
        val transaction = state.transactions.singleOrNull {
            it.definition.transactionId == descriptor.transactionId
        } ?: throw RemoteMcpFailure("mcp_catalog_recovery_missing")
        require(transaction.recovery == descriptor) { "Remote MCP catalog recovery identity changed" }
        transaction.receipt
    }

    fun receiptForFinalizedRecovery(
        descriptor: RemoteMcpDiscoveryCatalogRecoveryDescriptor,
    ): RemoteMcpDiscoveryCatalogReceipt = synchronized(lock) {
        ensureAvailable()
        require(descriptor.phase == RemoteMcpCatalogPhase.FINALIZED)
        val finalized = state.finalized.singleOrNull {
            it.definition.transactionId == descriptor.transactionId
        } ?: throw RemoteMcpFailure("mcp_catalog_recovery_missing")
        require(finalized.definition.recovery(RemoteMcpCatalogPhase.FINALIZED) == descriptor) {
            "Remote MCP finalized catalog identity changed"
        }
        finalized.definition.receipt
    }

    /** Full tool specs are reconstructed without opening a network session. */
    fun finalizedCatalog(
        identity: RemoteMcpActivationIdentity,
    ): RemoteMcpFinalizedDiscoveryCatalog = synchronized(lock) {
        ensureAvailable()
        val key = identity.key
        val finalized = state.finalized.singleOrNull { it.definition.identity.key == key }
            ?: throw RemoteMcpFailure("mcp_catalog_not_finalized")
        if (finalized.definition.identity != identity) {
            throw RemoteMcpFailure("mcp_catalog_stale_identity")
        }
        finalized.definition.finalizedCatalog
    }

    /**
     * Network-free inventory used to rebuild the revisioned App Server tool contract after a
     * process restart. PREPARED/COMMITTED catalogs are deliberately absent.
     */
    fun finalizedCatalogs(): List<RemoteMcpFinalizedDiscoveryCatalog> = synchronized(lock) {
        ensureAvailable()
        state.finalized
            .map { it.definition.finalizedCatalog }
            .sortedWith(
                compareBy(
                    { it.identity.pluginId },
                    { it.identity.serverId },
                    { it.identity.configurationDigest },
                ),
            )
    }

    fun deactivate(
        identity: RemoteMcpActivationIdentity,
        expectedMetadataDigest: String,
    ): RemoteMcpCatalogDeactivationResult = synchronized(lock) {
        if (loadFailed) return@synchronized RemoteMcpCatalogDeactivationResult.STORE_UNAVAILABLE
        require(expectedMetadataDigest.matches(SHA_256))
        val key = identity.key
        if (state.transactions.any { it.definition.identity.key == key }) {
            return@synchronized RemoteMcpCatalogDeactivationResult.TRANSACTION_PENDING
        }
        val active = state.finalized.singleOrNull { it.definition.identity.key == key }
            ?: return@synchronized RemoteMcpCatalogDeactivationResult.MISSING
        if (active.definition.identity != identity) {
            return@synchronized RemoteMcpCatalogDeactivationResult.STALE_IDENTITY
        }
        if (active.definition.metadataDigest != expectedMetadataDigest) {
            return@synchronized RemoteMcpCatalogDeactivationResult.STALE_METADATA
        }
        val replacement = state.copy(
            finalized = state.finalized.filterNot { it.definition.identity.key == key },
        )
        try {
            persist(replacement)
        } catch (_: Throwable) {
            return@synchronized RemoteMcpCatalogDeactivationResult.STORE_UNAVAILABLE
        }
        state = replacement
        RemoteMcpCatalogDeactivationResult.DEACTIVATED
    }

    private fun readState(): CatalogStoreState {
        val bytes = stateFile.readOrNull() ?: return CatalogStoreState.EMPTY
        return try {
            RemoteMcpDiscoveryCatalogStoreCodec.decode(bytes.toString(StandardCharsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }

    private fun persist(replacement: CatalogStoreState) {
        val encoded = RemoteMcpDiscoveryCatalogStoreCodec.encode(replacement)
            .toByteArray(StandardCharsets.UTF_8)
        try {
            stateFile.write(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    private fun requireTransaction(
        receipt: RemoteMcpDiscoveryCatalogReceipt,
        phase: RemoteMcpCatalogPhase,
    ): Int {
        val index = state.transactions.indexOfFirst {
            it.definition.transactionId == receipt.transactionId
        }
        if (index < 0) throw RemoteMcpFailure("mcp_catalog_transaction_missing")
        val transaction = state.transactions[index]
        requireReceipt(transaction, receipt)
        require(transaction.phase == phase) { "Remote MCP catalog phase changed" }
        return index
    }

    private fun requireReceipt(
        transaction: StoredCatalogTransaction,
        receipt: RemoteMcpDiscoveryCatalogReceipt,
    ) {
        require(transaction.receipt.matches(receipt)) { "Remote MCP catalog receipt changed" }
    }

    private fun ensureAvailable() {
        if (loadFailed) throw RemoteMcpFailure("mcp_catalog_store_unavailable")
    }

    private companion object {
        const val DIRECTORY_NAME = "remote-mcp"
        const val FILE_NAME = "discovery-catalog-v1.json"
        const val MAX_DOCUMENT_BYTES = 4 * 1024 * 1024
        const val MAX_FINALIZED_CATALOGS = 128
        const val MAX_PENDING_TRANSACTIONS = 32
    }
}

private data class RemoteMcpCatalogKey(val pluginId: String, val serverId: String)

private val RemoteMcpActivationIdentity.key: RemoteMcpCatalogKey
    get() = RemoteMcpCatalogKey(pluginId, serverId)

private data class StoredFinalizedCatalog(
    val definition: RemoteMcpDiscoveryCatalogDefinition,
)

private data class StoredCatalogTransaction(
    val phase: RemoteMcpCatalogPhase,
    val definition: RemoteMcpDiscoveryCatalogDefinition,
) {
    init {
        require(phase != RemoteMcpCatalogPhase.FINALIZED)
    }

    val receipt: RemoteMcpDiscoveryCatalogReceipt
        get() = definition.receipt

    val recovery: RemoteMcpDiscoveryCatalogRecoveryDescriptor
        get() = definition.recovery(phase)
}

private val RemoteMcpDiscoveryCatalogDefinition.receipt: RemoteMcpDiscoveryCatalogReceipt
    get() = RemoteMcpDiscoveryCatalogReceipt(
        transactionId = transactionId,
        pluginId = identity.pluginId,
        serverId = identity.serverId,
        metadataDigest = metadataDigest,
    )

private fun RemoteMcpDiscoveryCatalogDefinition.recovery(
    phase: RemoteMcpCatalogPhase,
) = RemoteMcpDiscoveryCatalogRecoveryDescriptor(
    transactionId = transactionId,
    identity = identity,
    routeState = routeState,
    catalogDigest = catalogDigest,
    metadataDigest = metadataDigest,
    activationTransactionId = activationTransactionId,
    activationMetadataDigest = activationMetadataDigest,
    phase = phase,
)

private val RemoteMcpDiscoveryCatalogDefinition.finalizedCatalog: RemoteMcpFinalizedDiscoveryCatalog
    get() = RemoteMcpFinalizedDiscoveryCatalog(
        identity = identity,
        routeState = routeState,
        catalogDigest = catalogDigest,
        metadataDigest = metadataDigest,
        discovery = RemoteMcpDiscoveryReceipt(
            tools = tools,
            allowedToolNames = tools.mapTo(linkedSetOf(), RemoteMcpTool::name),
        ),
    )

private fun RemoteMcpDiscoveryCatalogReceipt.matches(
    other: RemoteMcpDiscoveryCatalogReceipt,
): Boolean = transactionId == other.transactionId &&
    pluginId == other.pluginId &&
    serverId == other.serverId &&
    metadataDigest == other.metadataDigest

private data class CatalogStoreState(
    val finalized: List<StoredFinalizedCatalog>,
    val transactions: List<StoredCatalogTransaction>,
) {
    init {
        require(finalized.size <= 128 && transactions.size <= 32)
        require(finalized.map { it.definition.identity.key }.distinct().size == finalized.size)
        require(finalized.map { it.definition.transactionId }.distinct().size == finalized.size)
        require(transactions.map { it.definition.transactionId }.distinct().size == transactions.size)
        require(transactions.map { it.definition.identity.key }.distinct().size == transactions.size)
        require(
            finalized.mapTo(linkedSetOf()) { it.definition.transactionId }
                .intersect(transactions.mapTo(linkedSetOf()) { it.definition.transactionId })
                .isEmpty(),
        )
    }

    companion object {
        val EMPTY = CatalogStoreState(emptyList(), emptyList())
    }
}

private object RemoteMcpDiscoveryCatalogStoreCodec {
    private const val SCHEMA = "hans.remote-mcp-discovery-catalog"
    private const val VERSION = 2
    private const val LEGACY_VERSION = 1

    fun encode(state: CatalogStoreState): String = JSONObject()
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
            JSONArray(
                state.transactions.sortedBy { it.definition.transactionId }.map {
                    JSONObject()
                        .put("phase", it.phase.name)
                        .put("definition", encodeDefinition(it.definition))
                },
            ),
        )
        .toString()

    fun decode(raw: String): CatalogStoreState {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= 4 * 1024 * 1024)
        val root = JSONObject(raw)
        requireExactKeys(root, "schema", "version", "finalized", "transactions")
        require(root.getString("schema") == SCHEMA)
        val version = root.getInt("version")
        require(version in setOf(LEGACY_VERSION, VERSION))
        val finalized = root.getJSONArray("finalized")
        val transactions = root.getJSONArray("transactions")
        require(finalized.length() <= 128 && transactions.length() <= 32)
        val state = CatalogStoreState(
            finalized = (0 until finalized.length()).map {
                StoredFinalizedCatalog(decodeDefinition(finalized.getJSONObject(it), version))
            },
            transactions = (0 until transactions.length()).map { index ->
                val encoded = transactions.getJSONObject(index)
                requireExactKeys(encoded, "phase", "definition")
                val phase = RemoteMcpCatalogPhase.valueOf(encoded.getString("phase"))
                StoredCatalogTransaction(
                    phase,
                    decodeDefinition(encoded.getJSONObject("definition"), version),
                )
            },
        )
        val canonical = if (version == LEGACY_VERSION) encodeLegacy(state) else encode(state)
        require(canonical == raw) { "Remote MCP catalog store is not canonical" }
        return state
    }

    private fun encodeDefinition(value: RemoteMcpDiscoveryCatalogDefinition): JSONObject =
        JSONObject()
            .put("transactionId", value.transactionId)
            .put("pluginId", value.identity.pluginId)
            .put("serverId", value.identity.serverId)
            .put("configurationDigest", value.identity.configurationDigest)
            .put("routeState", value.routeState.name)
            .put("activationTransactionId", value.activationTransactionId ?: JSONObject.NULL)
            .put("activationMetadataDigest", value.activationMetadataDigest ?: JSONObject.NULL)
            .put("catalogDigest", value.catalogDigest)
            .put("metadataDigest", value.metadataDigest)
            .put("tools", encodeTools(value.tools))

    private fun decodeDefinition(
        value: JSONObject,
        version: Int,
    ): RemoteMcpDiscoveryCatalogDefinition {
        requireExactKeys(
            value,
            "transactionId",
            "pluginId",
            "serverId",
            "configurationDigest",
            "routeState",
            "activationTransactionId",
            "activationMetadataDigest",
            "catalogDigest",
            "metadataDigest",
            "tools",
        )
        val definition = RemoteMcpDiscoveryCatalogDefinition(
            transactionId = value.getString("transactionId"),
            identity = RemoteMcpActivationIdentity(
                pluginId = value.getString("pluginId"),
                serverId = value.getString("serverId"),
                configurationDigest = value.getString("configurationDigest"),
            ),
            routeState = RemoteMcpCatalogRouteState.valueOf(value.getString("routeState")),
            tools = decodeTools(value.getJSONArray("tools"), version),
            activationTransactionId = value.optionalString("activationTransactionId"),
            activationMetadataDigest = value.optionalString("activationMetadataDigest"),
        )
        require(value.getString("catalogDigest") == definition.catalogDigest &&
            value.getString("metadataDigest") == definition.metadataDigest) {
            "Remote MCP catalog digest changed"
        }
        return definition
    }

    private fun encodeLegacy(state: CatalogStoreState): String = JSONObject()
        .put("schema", SCHEMA)
        .put("version", LEGACY_VERSION)
        .put(
            "finalized",
            JSONArray(
                state.finalized.sortedWith(
                    compareBy(
                        { it.definition.identity.pluginId },
                        { it.definition.identity.serverId },
                    ),
                ).map { encodeLegacyDefinition(it.definition) },
            ),
        )
        .put(
            "transactions",
            JSONArray(
                state.transactions.sortedBy { it.definition.transactionId }.map {
                    JSONObject()
                        .put("phase", it.phase.name)
                        .put("definition", encodeLegacyDefinition(it.definition))
                },
            ),
        )
        .toString()

    private fun encodeLegacyDefinition(value: RemoteMcpDiscoveryCatalogDefinition): JSONObject =
        JSONObject()
            .put("transactionId", value.transactionId)
            .put("pluginId", value.identity.pluginId)
            .put("serverId", value.identity.serverId)
            .put("configurationDigest", value.identity.configurationDigest)
            .put("routeState", value.routeState.name)
            .put("activationTransactionId", value.activationTransactionId ?: JSONObject.NULL)
            .put("activationMetadataDigest", value.activationMetadataDigest ?: JSONObject.NULL)
            .put("catalogDigest", value.catalogDigest)
            .put("metadataDigest", value.metadataDigest)
            .put("tools", encodeLegacyTools(value.tools))
}

private fun canonicalTools(tools: List<RemoteMcpTool>): List<RemoteMcpTool> {
    require(tools.size <= MAX_CATALOG_TOOLS) { "Remote MCP catalog has too many tools" }
    require(tools.map(RemoteMcpTool::name).distinct().size == tools.size) {
        "Remote MCP catalog has duplicate tools"
    }
    return tools.sortedBy(RemoteMcpTool::name).map { tool ->
        RemoteMcpTool(
            name = tool.name,
            title = tool.title,
            description = tool.description,
            inputSchemaJson = canonicalJsonObject(tool.inputSchemaJson),
            outputSchemaJson = tool.outputSchemaJson?.let(::canonicalJsonObject),
            annotationsJson = tool.annotationsJson,
            iconsJson = tool.iconsJson,
            metaJson = tool.metaJson,
        )
    }
}

private fun encodeTools(tools: List<RemoteMcpTool>): JSONArray = JSONArray(
    tools.map { tool ->
        JSONObject()
            .put("name", tool.name)
            .put("title", tool.title ?: JSONObject.NULL)
            .put("description", tool.description ?: JSONObject.NULL)
            .put("inputSchemaJson", tool.inputSchemaJson)
            .put("outputSchemaJson", tool.outputSchemaJson ?: JSONObject.NULL)
            .put("annotationsJson", tool.annotationsJson ?: JSONObject.NULL)
            .put("iconsJson", tool.iconsJson ?: JSONObject.NULL)
            .put("metaJson", tool.metaJson ?: JSONObject.NULL)
    },
)

private fun decodeTools(values: JSONArray, version: Int): List<RemoteMcpTool> {
    require(values.length() <= MAX_CATALOG_TOOLS)
    return (0 until values.length()).map { index ->
        val value = values.getJSONObject(index)
        if (version == 1) {
            requireExactKeys(
                value,
                "name",
                "title",
                "description",
                "inputSchemaJson",
                "outputSchemaJson",
            )
        } else {
            requireExactKeys(
                value,
                "name",
                "title",
                "description",
                "inputSchemaJson",
                "outputSchemaJson",
                "annotationsJson",
                "iconsJson",
                "metaJson",
            )
        }
        RemoteMcpTool(
            name = value.getString("name"),
            title = value.optionalString("title"),
            description = value.optionalString("description"),
            inputSchemaJson = value.getString("inputSchemaJson"),
            outputSchemaJson = value.optionalString("outputSchemaJson"),
            annotationsJson = if (version == 1) null else value.optionalString("annotationsJson"),
            iconsJson = if (version == 1) null else value.optionalString("iconsJson"),
            metaJson = if (version == 1) null else value.optionalString("metaJson"),
        )
    }
}

private fun encodeLegacyTools(tools: List<RemoteMcpTool>): JSONArray = JSONArray(
    tools.map { tool ->
        JSONObject()
            .put("name", tool.name)
            .put("title", tool.title ?: JSONObject.NULL)
            .put("description", tool.description ?: JSONObject.NULL)
            .put("inputSchemaJson", tool.inputSchemaJson)
            .put("outputSchemaJson", tool.outputSchemaJson ?: JSONObject.NULL)
    },
)

private fun encodedCatalog(tools: List<RemoteMcpTool>): String = JSONObject()
    .put("tools", encodeDigestTools(tools))
    .toString()

/**
 * Preserve the v1 digest for tools that had no optional metadata. This lets an existing finalized
 * activation and its plugin journal survive the schema-2 migration, while any newly observed
 * annotation/icon/_meta becomes part of the exact digest.
 */
private fun encodeDigestTools(tools: List<RemoteMcpTool>): JSONArray = JSONArray(
    tools.map { tool ->
        JSONObject()
            .put("name", tool.name)
            .put("title", tool.title ?: JSONObject.NULL)
            .put("description", tool.description ?: JSONObject.NULL)
            .put("inputSchemaJson", tool.inputSchemaJson)
            .put("outputSchemaJson", tool.outputSchemaJson ?: JSONObject.NULL)
            .apply {
                tool.annotationsJson?.let { put("annotationsJson", it) }
                tool.iconsJson?.let { put("iconsJson", it) }
                tool.metaJson?.let { put("metaJson", it) }
            }
    },
)

private fun digestCatalog(tools: List<RemoteMcpTool>): String = sha256(
    encodedCatalog(tools).toByteArray(StandardCharsets.UTF_8),
)

private fun digestMetadata(
    identity: RemoteMcpActivationIdentity,
    routeState: RemoteMcpCatalogRouteState,
    catalogDigest: String,
    activationTransactionId: String?,
    activationMetadataDigest: String?,
): String = sha256(
    JSONObject()
        .put("pluginId", identity.pluginId)
        .put("serverId", identity.serverId)
        .put("configurationDigest", identity.configurationDigest)
        .put("routeState", routeState.name)
        .put("catalogDigest", catalogDigest)
        .put("activationTransactionId", activationTransactionId ?: JSONObject.NULL)
        .put("activationMetadataDigest", activationMetadataDigest ?: JSONObject.NULL)
        .toString()
        .toByteArray(StandardCharsets.UTF_8),
)

private fun canonicalJsonObject(raw: String): String = canonicalJsonValue(JSONObject(raw)).toString()

private fun canonicalJsonValue(value: Any?): Any = when (value) {
    null, JSONObject.NULL -> JSONObject.NULL
    is JSONObject -> JSONObject().also { target ->
        value.keys().asSequence().toList().sorted().forEach { key ->
            target.put(key, canonicalJsonValue(value.get(key)))
        }
    }
    is JSONArray -> JSONArray().also { target ->
        repeat(value.length()) { index -> target.put(canonicalJsonValue(value.get(index))) }
    }
    is String, is Boolean, is Number -> value
    else -> error("Remote MCP schema value is unsupported")
}

private fun JSONObject.optionalString(name: String): String? =
    if (isNull(name)) null else getString(name)

private const val MAX_CATALOG_TOOLS = 64
private const val MAX_CATALOG_BYTES = 512 * 1024
private val OPAQUE_TRANSACTION_ID = Regex("[A-Za-z0-9._:-]{1,128}")
