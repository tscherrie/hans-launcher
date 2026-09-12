package ai.hans.standard.plugins.runtime

import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.plugins.PluginDetailSnapshot
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

private const val SURFACE_STORE_VERSION = 1
private const val MAX_SURFACE_RECORD_BYTES = 512 * 1024
private const val MAX_SURFACE_RECEIPTS = 16
private const val MAX_ACTIVE_SURFACES = 256
private const val MAX_SOURCE_PATH_CHARS = 4_096
private val SURFACE_RECEIPT_ID = Regex("[0-9a-f]{32}")
private val SURFACE_PLUGIN_ID = Regex("[a-z][a-z0-9._-]{0,127}")
private val SURFACE_SHA256 = Regex("[0-9a-f]{64}")

internal data class PluginSurfaceEvidenceStageReceipt(
    val receiptId: String,
    val pluginId: String,
    val stateSha256: String,
) {
    init {
        require(SURFACE_RECEIPT_ID.matches(receiptId)) { "Invalid surface evidence receipt" }
        require(SURFACE_PLUGIN_ID.matches(pluginId)) { "Invalid surface evidence plugin" }
        require(SURFACE_SHA256.matches(stateSha256)) { "Invalid surface evidence identity" }
    }

    override fun toString(): String =
        "PluginSurfaceEvidenceStageReceipt(pluginId=$pluginId, receiptId=[opaque])"
}

internal sealed interface PluginSurfaceEvidenceStageResult {
    data object NotDeclared : PluginSurfaceEvidenceStageResult
    data class Staged(val receipt: PluginSurfaceEvidenceStageReceipt) :
        PluginSurfaceEvidenceStageResult
    data class Rejected(val reason: String) : PluginSurfaceEvidenceStageResult
}

internal data class PluginSurfaceDeclarationReceipt internal constructor(
    val pluginId: String,
    internal val canonicalSourceRoot: String,
    internal val manifest: PluginSurfaceManifest,
) {
    init {
        require(SURFACE_PLUGIN_ID.matches(pluginId) && manifest.pluginId == pluginId)
        require(File(canonicalSourceRoot).isAbsolute && canonicalSourceRoot.length <= MAX_SOURCE_PATH_CHARS)
    }

    override fun toString(): String =
        "PluginSurfaceDeclarationReceipt(pluginId=$pluginId, source=[private])"
}

internal sealed interface PluginSurfaceDeclarationProbe {
    data object NotDeclared : PluginSurfaceDeclarationProbe
    data class Declared(val receipt: PluginSurfaceDeclarationReceipt) :
        PluginSurfaceDeclarationProbe
    data class Rejected(val reason: String) : PluginSurfaceDeclarationProbe
}

/**
 * Explicit controller-facing boundary for fresh plugin/read evidence.
 *
 * The generic dependency-preparer API cannot carry App Server detail. The controller therefore
 * stages one private, opaque receipt immediately before starting the wider install transaction.
 * The surface preparer accepts only the unique receipt correlated to the same plugin and canonical
 * source root; no ThreadLocal or ambient "latest detail" state is used.
 */
internal class PluginSurfaceEvidenceStager(
    private val preflight: PluginSurfacePreflight,
    private val store: PluginSurfaceActivationStore,
) {
    /**
     * Side-effect-free declaration probe used before plugin/read. A malformed surface fails closed
     * before any App Server mutation; ordinary and Python-only plugins avoid the extra read.
     */
    fun requiresFreshEvidence(
        pluginId: String,
        sourceRoot: File,
    ): PluginSurfaceDeclarationProbe = when (
        val declaration = preflight.declaration(pluginId, sourceRoot)
    ) {
        PluginSurfaceManifestLoadResult.NotDeclared -> runCatching { store.active(pluginId) }
            .fold(
                onSuccess = { active ->
                    if (active == null) PluginSurfaceDeclarationProbe.NotDeclared
                    else PluginSurfaceDeclarationProbe.Rejected("surface_deactivation_required")
                },
                onFailure = {
                    PluginSurfaceDeclarationProbe.Rejected("surface_store_unavailable")
                },
            )
        is PluginSurfaceManifestLoadResult.Rejected ->
            PluginSurfaceDeclarationProbe.Rejected(declaration.reason)
        is PluginSurfaceManifestLoadResult.Declared -> PluginSurfaceDeclarationProbe.Declared(
            PluginSurfaceDeclarationReceipt(
                pluginId = pluginId,
                canonicalSourceRoot = sourceRoot.canonicalFile.path,
                manifest = declaration.manifest,
            ),
        )
    }

    fun stage(
        pluginId: String,
        sourceRoot: File,
        freshPluginDetail: PluginDetailSnapshot,
    ): PluginSurfaceEvidenceStageResult = when (
        val declaration = requiresFreshEvidence(pluginId, sourceRoot)
    ) {
        PluginSurfaceDeclarationProbe.NotDeclared -> PluginSurfaceEvidenceStageResult.NotDeclared
        is PluginSurfaceDeclarationProbe.Rejected ->
            PluginSurfaceEvidenceStageResult.Rejected(declaration.reason)
        is PluginSurfaceDeclarationProbe.Declared -> stage(
            declaration = declaration.receipt,
            freshPluginDetail = freshPluginDetail,
        )
    }

    /** Consumes the exact parsed declaration returned by [requiresFreshEvidence]. */
    fun stage(
        declaration: PluginSurfaceDeclarationReceipt,
        freshPluginDetail: PluginDetailSnapshot,
    ): PluginSurfaceEvidenceStageResult = when (
        val result = preflight.evaluateDeclared(
            manifest = declaration.manifest,
            nativeEvidence = freshPluginDetail.toCodexNativeSurfaceEvidence(),
        )
    ) {
        PluginSurfacePreflightResult.NotDeclared ->
            PluginSurfaceEvidenceStageResult.Rejected("surface_declaration_lost")
        is PluginSurfacePreflightResult.Rejected ->
            PluginSurfaceEvidenceStageResult.Rejected(result.reason)
        is PluginSurfacePreflightResult.Ready -> runCatching {
            PluginSurfaceEvidenceStageResult.Staged(
                store.stage(File(declaration.canonicalSourceRoot), result).toPublicReceipt(),
            )
        }.getOrElse {
            PluginSurfaceEvidenceStageResult.Rejected("surface_evidence_stage_failed")
        }
    }

    /** Removes only an unclaimed receipt returned by this stager. */
    fun discard(receipt: PluginSurfaceEvidenceStageReceipt): Boolean = store.discard(receipt)
}

internal data class PublishedPluginSurface(
    val pluginId: String,
    val stateSha256: String,
    val inventory: PluginSurfaceInventorySnapshot,
    val dynamicToolExecutors: List<DynamicToolExecutor>,
)

/**
 * Read-only publication facade. A route does not exist until one exact active record is present.
 * Every read revalidates source and signed-APK entrypoint evidence before returning executors.
 */
internal class PublishedPluginSurfaceRegistry(
    private val preflight: PluginSurfacePreflight,
    private val store: PluginSurfaceActivationStore,
) {
    fun snapshot(pluginId: String): PublishedPluginSurface? = runCatching {
        val active = store.active(pluginId) ?: return null
        val ready = preflight.revalidate(active) ?: return null
        if (ready.stateSha256 != active.stateSha256) return null
        PublishedPluginSurface(
            pluginId = pluginId,
            stateSha256 = active.stateSha256,
            inventory = ready.inventory,
            dynamicToolExecutors = ready.dynamicToolExecutors,
        )
    }.getOrNull()

    fun snapshots(): List<PublishedPluginSurface> = runCatching {
        store.activePluginIds().mapNotNull(::snapshot)
            .sortedBy(PublishedPluginSurface::pluginId)
    }.getOrDefault(emptyList())
}

internal enum class PluginSurfacePrivatePhase { STAGED, PREPARED, COMMITTED, ACTIVE }

internal data class PluginSurfacePrivateRecord(
    val receiptId: String,
    val pluginId: String,
    val canonicalSourceRoot: String,
    val stateSha256: String,
    val phase: PluginSurfacePrivatePhase,
    val manifest: PluginSurfaceManifest,
    val nativeEvidence: CodexNativeSurfaceEvidence,
    /** Exact active surface this transaction is allowed to replace, if any. */
    val previousReceiptId: String?,
    val previousStateSha256: String?,
) {
    init {
        require(SURFACE_RECEIPT_ID.matches(receiptId))
        require(SURFACE_PLUGIN_ID.matches(pluginId) && manifest.pluginId == pluginId)
        require(File(canonicalSourceRoot).isAbsolute && canonicalSourceRoot.length <= MAX_SOURCE_PATH_CHARS)
        require('\u0000' !in canonicalSourceRoot)
        require(SURFACE_SHA256.matches(stateSha256))
        require((previousReceiptId == null) == (previousStateSha256 == null))
        previousReceiptId?.let { require(SURFACE_RECEIPT_ID.matches(it)) }
        previousStateSha256?.let { require(SURFACE_SHA256.matches(it)) }
    }

    fun toPublicReceipt() = PluginSurfaceEvidenceStageReceipt(receiptId, pluginId, stateSha256)
}

/** App-private atomic receipt and active-pointer store. */
internal class PluginSurfaceActivationStore(
    rootDirectory: File,
) {
    private val requestedRoot = rootDirectory.absoluteFile
    private val root = rootDirectory.canonicalFile
    private val receipts = File(root, "receipts")
    private val active = File(root, "active")
    private val lock = Any()
    @Volatile private var unavailable = false

    init {
        runCatching {
            require(!Files.isSymbolicLink(requestedRoot.toPath())) {
                "Plugin surface store path is a symlink"
            }
            require(root.mkdirs() || root.isDirectory) { "Cannot create plugin surface store" }
            require(!Files.isSymbolicLink(root.toPath())) { "Plugin surface store is a symlink" }
            listOf(receipts, active).forEach { directory ->
                require(directory.mkdir() || directory.isDirectory) {
                    "Cannot create plugin surface store directory"
                }
                require(!Files.isSymbolicLink(directory.toPath())) {
                    "Plugin surface store directory is a symlink"
                }
                directory.listFiles().orEmpty()
                    .filter { it.name.startsWith(".tmp-") }
                    .forEach { Files.deleteIfExists(it.toPath()) }
            }
            require(receiptFiles().size <= MAX_SURFACE_RECEIPTS) {
                "Too many plugin surface receipts"
            }
        }.onFailure { unavailable = true }
    }

    fun stage(
        sourceRoot: File,
        ready: PluginSurfacePreflightResult.Ready,
    ): PluginSurfacePrivateRecord = synchronized(lock) {
        checkAvailable()
        require(!Files.isSymbolicLink(sourceRoot.toPath())) {
            "Plugin surface source is a symlink"
        }
        val source = sourceRoot.canonicalFile
        require(source.isDirectory && !Files.isSymbolicLink(source.toPath())) {
            "Plugin surface source is unavailable"
        }
        val all = readReceipts()
        require(all.size < MAX_SURFACE_RECEIPTS) { "Too many plugin surface receipts" }
        val activeIds = activePluginIdsLocked()
        require(activeIds.size < MAX_ACTIVE_SURFACES || ready.manifest.pluginId in activeIds) {
            "Too many active plugin surfaces"
        }
        require(all.none { it.pluginId == ready.manifest.pluginId }) {
            "Plugin surface already has staged evidence"
        }
        val previous = readActive(ready.manifest.pluginId)
        val record = PluginSurfacePrivateRecord(
            receiptId = UUID.randomUUID().toString().replace("-", ""),
            pluginId = ready.manifest.pluginId,
            canonicalSourceRoot = source.path,
            stateSha256 = ready.stateSha256,
            phase = PluginSurfacePrivatePhase.STAGED,
            manifest = ready.manifest,
            nativeEvidence = ready.nativeEvidence,
            previousReceiptId = previous?.receiptId,
            previousStateSha256 = previous?.stateSha256,
        )
        writeRecord(receiptFile(record.receiptId), record, replace = false)
        record
    }

    /** Validates and claims exactly one controller-staged receipt as one dependency transaction. */
    fun claimUnique(
        pluginId: String,
        sourceRoot: File,
        validate: (PluginSurfacePrivateRecord) -> Unit,
    ): PluginSurfacePrivateRecord = synchronized(lock) {
        checkAvailable()
        val source = sourceRoot.canonicalFile.path
        val candidates = readReceipts().filter { it.pluginId == pluginId }
        require(candidates.size == 1) { "Plugin surface requires one exact staged receipt" }
        val current = candidates.single()
        require(current.phase == PluginSurfacePrivatePhase.STAGED) {
            "Plugin surface evidence was already claimed"
        }
        require(current.canonicalSourceRoot == source) { "Plugin surface source correlation changed" }
        validate(current)
        val prepared = current.copy(phase = PluginSurfacePrivatePhase.PREPARED)
        replaceExact(current, prepared)
        prepared
    }

    fun markCommitted(expected: PluginSurfacePrivateRecord): PluginSurfacePrivateRecord =
        synchronized(lock) {
            checkAvailable()
            val current = exactReceipt(expected)
            when (current.phase) {
                PluginSurfacePrivatePhase.COMMITTED -> current
                PluginSurfacePrivatePhase.PREPARED -> current.copy(
                    phase = PluginSurfacePrivatePhase.COMMITTED,
                ).also { replaceExact(current, it) }
                else -> error("Plugin surface receipt is not prepared")
            }
        }

    /** The active record is the atomic publication point. */
    fun finalizeActivation(expected: PluginSurfacePrivateRecord): PluginSurfacePrivateRecord =
        synchronized(lock) {
            checkAvailable()
            val existingActive = readActive(expected.pluginId)
            if (existingActive != null && existingActive.sameIdentity(expected)) {
                deleteReceiptIfExact(expected)
                return@synchronized existingActive
            }
            val current = exactReceipt(expected)
            require(current.phase == PluginSurfacePrivatePhase.COMMITTED) {
                "Plugin surface receipt is not committed"
            }
            val published = current.copy(phase = PluginSurfacePrivatePhase.ACTIVE)
            writeRecord(activeFile(current.pluginId), published, replace = true)
            deleteReceiptIfExact(current)
            published
        }

    fun rollback(expected: PluginSurfacePrivateRecord): Boolean = synchronized(lock) {
        checkAvailable()
        val published = readActive(expected.pluginId)
        require(
            when {
                published == null -> expected.previousReceiptId == null
                published.sameIdentity(expected) -> false
                else -> published.receiptId == expected.previousReceiptId &&
                    published.stateSha256 == expected.previousStateSha256
            },
        ) {
            "Published plugin surface changed before rollback"
        }
        deleteReceiptIfExact(expected)
    }

    fun discard(receipt: PluginSurfaceEvidenceStageReceipt): Boolean = synchronized(lock) {
        checkAvailable()
        val file = receiptFile(receipt.receiptId)
        if (!file.exists()) return@synchronized false
        val current = readRecord(file)
        require(current.phase == PluginSurfacePrivatePhase.STAGED) {
            "Claimed plugin surface evidence cannot be discarded"
        }
        require(current.toPublicReceipt() == receipt) { "Plugin surface receipt changed" }
        Files.deleteIfExists(file.toPath())
    }

    /** Exact post-uninstall deactivation; a digest-only or plugin-id-only delete is forbidden. */
    fun deactivate(
        pluginId: String,
        receiptId: String,
        stateSha256: String,
    ): Boolean = synchronized(lock) {
        checkAvailable()
        val current = readActive(pluginId) ?: return@synchronized false
        require(current.receiptId == receiptId && current.stateSha256 == stateSha256) {
            "Published plugin surface identity changed"
        }
        val file = activeFile(pluginId)
        val tombstone = File(active, ".tmp-deactivate-${UUID.randomUUID()}")
        atomicMove(file, tombstone, replace = false)
        Files.deleteIfExists(tombstone.toPath())
    }

    fun recordsFor(pluginId: String): List<PluginSurfacePrivateRecord> = synchronized(lock) {
        checkAvailable()
        readReceipts().filter { it.pluginId == pluginId }
    }

    fun active(pluginId: String): PluginSurfacePrivateRecord? = synchronized(lock) {
        checkAvailable()
        readActive(pluginId)
    }

    fun activePluginIds(): List<String> = synchronized(lock) {
        checkAvailable()
        activePluginIdsLocked()
    }

    fun isAvailable(): Boolean = !unavailable

    private fun exactReceipt(expected: PluginSurfacePrivateRecord): PluginSurfacePrivateRecord {
        val current = readRecord(receiptFile(expected.receiptId))
        require(current.sameIdentity(expected) && current.pluginId == expected.pluginId) {
            "Plugin surface receipt changed"
        }
        return current
    }

    private fun replaceExact(
        expected: PluginSurfacePrivateRecord,
        replacement: PluginSurfacePrivateRecord,
    ) {
        val current = exactReceipt(expected)
        require(current == expected) { "Plugin surface receipt phase changed" }
        writeRecord(receiptFile(expected.receiptId), replacement, replace = true)
    }

    private fun deleteReceiptIfExact(expected: PluginSurfacePrivateRecord): Boolean {
        val file = receiptFile(expected.receiptId)
        if (!file.exists()) return false
        val current = readRecord(file)
        require(current.sameIdentity(expected) && current.pluginId == expected.pluginId) {
            "Plugin surface receipt changed before removal"
        }
        return Files.deleteIfExists(file.toPath())
    }

    private fun PluginSurfacePrivateRecord.sameIdentity(other: PluginSurfacePrivateRecord): Boolean =
        receiptId == other.receiptId && pluginId == other.pluginId &&
            stateSha256 == other.stateSha256 && canonicalSourceRoot == other.canonicalSourceRoot &&
            manifest == other.manifest && nativeEvidence == other.nativeEvidence &&
            previousReceiptId == other.previousReceiptId &&
            previousStateSha256 == other.previousStateSha256

    private fun readReceipts(): List<PluginSurfacePrivateRecord> = receiptFiles().map(::readRecord)

    private fun receiptFiles(): List<File> = receipts.listFiles().orEmpty().toList().also { files ->
        require(files.size <= MAX_SURFACE_RECEIPTS)
        require(files.all {
            it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.endsWith(".json") &&
                SURFACE_RECEIPT_ID.matches(it.name.removeSuffix(".json"))
        }) {
            "Unexpected plugin surface receipt"
        }
    }

    private fun readActive(pluginId: String): PluginSurfacePrivateRecord? {
        require(SURFACE_PLUGIN_ID.matches(pluginId))
        val file = activeFile(pluginId)
        if (!file.exists()) return null
        return readRecord(file).also {
            require(it.pluginId == pluginId && it.phase == PluginSurfacePrivatePhase.ACTIVE)
        }
    }

    private fun activePluginIdsLocked(): List<String> = active.listFiles().orEmpty()
        .also { files ->
            require(files.size <= MAX_ACTIVE_SURFACES)
            require(files.all {
                it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.endsWith(".json")
            }) { "Unexpected active plugin surface record" }
        }.map {
            it.name.removeSuffix(".json").also { id -> require(SURFACE_PLUGIN_ID.matches(id)) }
        }

    private fun receiptFile(receiptId: String): File {
        require(SURFACE_RECEIPT_ID.matches(receiptId))
        return File(receipts, "$receiptId.json")
    }

    private fun activeFile(pluginId: String): File {
        require(SURFACE_PLUGIN_ID.matches(pluginId))
        return File(active, "$pluginId.json")
    }

    private fun writeRecord(file: File, record: PluginSurfacePrivateRecord, replace: Boolean) {
        val bytes = PluginSurfacePrivateRecordCodec.encode(record)
        require(bytes.size <= MAX_SURFACE_RECORD_BYTES)
        require(!file.exists() || replace) { "Plugin surface record already exists" }
        require(!Files.isSymbolicLink(file.toPath())) { "Plugin surface record is a symlink" }
        val temporary = File(file.parentFile, ".tmp-${UUID.randomUUID()}")
        require(temporary.createNewFile()) { "Cannot create plugin surface temporary" }
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            atomicMove(temporary, file, replace)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun readRecord(file: File): PluginSurfacePrivateRecord {
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "Plugin surface record is not regular"
        }
        require(file.length() in 1..MAX_SURFACE_RECORD_BYTES.toLong()) {
            "Plugin surface record size is invalid"
        }
        return PluginSurfacePrivateRecordCodec.decode(file.readBytes())
    }

    private fun checkAvailable() = check(!unavailable) { "Plugin surface store is unavailable" }
}

internal fun PluginSurfacePreflight.revalidate(
    record: PluginSurfacePrivateRecord,
): PluginSurfacePreflightResult.Ready? {
    val result = evaluate(
        pluginId = record.pluginId,
        sourceRoot = File(record.canonicalSourceRoot),
        nativeEvidence = record.nativeEvidence,
    )
    return (result as? PluginSurfacePreflightResult.Ready)?.takeIf {
        it.manifest == record.manifest && it.stateSha256 == record.stateSha256
    }
}

private object PluginSurfacePrivateRecordCodec {
    fun encode(record: PluginSurfacePrivateRecord): ByteArray = JSONObject()
        .put("version", SURFACE_STORE_VERSION)
        .put("receiptId", record.receiptId)
        .put("pluginId", record.pluginId)
        .put("canonicalSourceRoot", record.canonicalSourceRoot)
        .put("stateSha256", record.stateSha256)
        .put("phase", record.phase.name)
        .put("previousReceiptId", record.previousReceiptId ?: JSONObject.NULL)
        .put("previousStateSha256", record.previousStateSha256 ?: JSONObject.NULL)
        .put(
            "manifestBase64",
            Base64.getEncoder().encodeToString(PluginSurfaceManifestCodec.encode(record.manifest)),
        )
        .put("nativeComplete", record.nativeEvidence.complete)
        .put("nativeSkills", JSONArray(record.nativeEvidence.enabledSkillNames.sorted()))
        .put(
            "nativeHooks",
            JSONArray(
                record.nativeEvidence.enabledHooks
                    .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
                    .map { JSONArray(listOf(it.first, it.second)) },
            ),
        )
        .toString().toByteArray(StandardCharsets.UTF_8).also {
            require(it.size <= MAX_SURFACE_RECORD_BYTES)
        }

    fun decode(bytes: ByteArray): PluginSurfacePrivateRecord {
        require(bytes.size in 1..MAX_SURFACE_RECORD_BYTES)
        val text = bytes.toString(StandardCharsets.UTF_8)
        require(text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes))
        val root = JSONObject(text)
        require(
            root.keys().asSequence().toSet() == setOf(
                "version", "receiptId", "pluginId", "canonicalSourceRoot", "stateSha256",
                "phase", "previousReceiptId", "previousStateSha256", "manifestBase64",
                "nativeComplete", "nativeSkills", "nativeHooks",
            ),
        ) { "Unexpected plugin surface record fields" }
        require(root.getInt("version") == SURFACE_STORE_VERSION)
        val manifestBytes = Base64.getDecoder().decode(root.getString("manifestBase64")).also {
            require(it.size in 1..PluginSurfaceManifestCodec.MAX_BYTES)
        }
        val skills = root.getJSONArray("nativeSkills").strings(MAX_NATIVE_EVIDENCE)
        val hooks = root.getJSONArray("nativeHooks").let { array ->
            require(array.length() <= MAX_NATIVE_EVIDENCE)
            (0 until array.length()).map { index ->
                val pair = array.getJSONArray(index)
                require(pair.length() == 2)
                pair.getString(0) to pair.getString(1)
            }.toSet()
        }
        return PluginSurfacePrivateRecord(
            receiptId = root.getString("receiptId"),
            pluginId = root.getString("pluginId"),
            canonicalSourceRoot = root.getString("canonicalSourceRoot"),
            stateSha256 = root.getString("stateSha256"),
            phase = PluginSurfacePrivatePhase.valueOf(root.getString("phase")),
            manifest = PluginSurfaceManifestCodec.decode(manifestBytes),
            nativeEvidence = CodexNativeSurfaceEvidence(
                enabledSkillNames = skills.toSet(),
                enabledHooks = hooks,
                complete = root.getBoolean("nativeComplete"),
            ),
            previousReceiptId = root.optString("previousReceiptId").takeIf {
                !root.isNull("previousReceiptId")
            },
            previousStateSha256 = root.optString("previousStateSha256").takeIf {
                !root.isNull("previousStateSha256")
            },
        )
    }

    private const val MAX_NATIVE_EVIDENCE = 128

    private fun JSONArray.strings(max: Int): List<String> {
        require(length() <= max)
        return (0 until length()).map { getString(it) }
    }
}

private fun atomicMove(source: File, target: File, replace: Boolean) {
    val options = buildList {
        add(StandardCopyOption.ATOMIC_MOVE)
        if (replace) add(StandardCopyOption.REPLACE_EXISTING)
    }.toTypedArray()
    try {
        Files.move(source.toPath(), target.toPath(), *options)
    } catch (_: AtomicMoveNotSupportedException) {
        throw IllegalStateException("Atomic plugin surface storage is unavailable")
    }
}
