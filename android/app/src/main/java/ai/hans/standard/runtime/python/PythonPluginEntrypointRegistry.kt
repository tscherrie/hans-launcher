package ai.hans.standard.runtime.python

import ai.hans.standard.codex.JsonContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** One callable whose source identity was declared before the plugin install was committed. */
class PythonPluginEntrypointDeclaration(
    val entrypointId: String,
    val relativePath: String,
    val function: String,
    val sourceSha256: String,
    declaredCapabilities: Set<String> = emptySet(),
) {
    /** Durable declaration evidence; live execution still requires an installed route. */
    val declaredCapabilities: Set<String> = declaredCapabilities.toSet()

    init {
        require(ENTRYPOINT_ID.matches(entrypointId)) { "Invalid Python plugin entrypoint id" }
        require(relativePath.toByteArray(StandardCharsets.UTF_8).size in 1..MAX_RELATIVE_PATH_BYTES &&
            relativePath.endsWith(".py") && relativePath.none(Char::isISOControl)) {
            "Invalid Python plugin entrypoint path"
        }
        require(!relativePath.startsWith('/') && '\\' !in relativePath)
        require(relativePath.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Python plugin entrypoint escapes its source"
        }
        require(PUBLIC_CALLABLE.matches(function)) { "Invalid Python plugin callable" }
        require(PythonEnvironmentContract.isSha256(sourceSha256)) {
            "Invalid Python plugin source identity"
        }
        require(this.declaredCapabilities.size <= MAX_DECLARED_CAPABILITIES) {
            "Too many declared Python plugin capabilities"
        }
        require(this.declaredCapabilities.all(DECLARED_CAPABILITY_ID::matches)) {
            "Invalid declared Python plugin capability"
        }
    }

    internal fun canonicalJson(): JSONObject = JSONObject()
        .put("entrypointId", entrypointId)
        .put("relativePath", relativePath)
        .put("function", function)
        .put("sourceSha256", sourceSha256)
        .put("declaredCapabilities", JSONArray(declaredCapabilities.sorted()))

    companion object {
        private const val MAX_RELATIVE_PATH_BYTES = 1_024
        private const val MAX_DECLARED_CAPABILITIES = 128
        private val ENTRYPOINT_ID = Regex("[a-z][a-z0-9._-]{0,63}")
        private val PUBLIC_CALLABLE = Regex("[A-Za-z][A-Za-z0-9_]{0,255}")
        private val DECLARED_CAPABILITY_ID = Regex("[a-z][a-z0-9._:-]{0,127}")
    }
}

/**
 * Immutable install evidence. Every declaration must have been resolved by the isolated worker,
 * and every declaration is bound to the exact source tree and content-addressed environment.
 */
class PythonPluginEntrypointActivation(
    val pluginId: String,
    val environmentDigest: String,
    val sourceSha256: String,
    declarations: List<PythonPluginEntrypointDeclaration>,
    provenEntrypointIds: Set<String>,
) {
    val declarations: List<PythonPluginEntrypointDeclaration> = declarations.toList()
    val provenEntrypointIds: Set<String> = provenEntrypointIds.toSet()

    init {
        require(PythonEnvironmentContract.isPluginId(pluginId)) { "Invalid Python plugin id" }
        require(PythonEnvironmentContract.isSha256(environmentDigest)) {
            "Invalid Python plugin environment identity"
        }
        require(PythonEnvironmentContract.isSha256(sourceSha256)) {
            "Invalid Python plugin source identity"
        }
        require(this.declarations.isNotEmpty() &&
            this.declarations.size <= MAX_ENTRYPOINTS_PER_PLUGIN) {
            "Invalid Python plugin entrypoint count"
        }
        require(this.declarations.all { it.sourceSha256 == sourceSha256 }) {
            "Python entrypoint source identity does not match its activation"
        }
        val ids = this.declarations.map(PythonPluginEntrypointDeclaration::entrypointId)
        require(ids.distinct().size == ids.size) { "Duplicate Python plugin entrypoint id" }
        require(this.provenEntrypointIds == ids.toSet()) {
            "Every declared Python plugin entrypoint must be proven before activation"
        }
    }

    val metadataDigest: String
        get() = sha256(canonicalJson().toString().toByteArray(StandardCharsets.UTF_8))

    internal fun declaration(entrypointId: String): PythonPluginEntrypointDeclaration? =
        declarations.singleOrNull { it.entrypointId == entrypointId }

    internal fun canonicalJson(): JSONObject = JSONObject()
        .put("pluginId", pluginId)
        .put("environmentDigest", environmentDigest)
        .put("sourceSha256", sourceSha256)
        .put(
            "declarations",
            JSONArray(
                declarations.sortedBy(PythonPluginEntrypointDeclaration::entrypointId)
                    .map(PythonPluginEntrypointDeclaration::canonicalJson),
            ),
        )
        .put("provenEntrypointIds", JSONArray(provenEntrypointIds.sorted()))

    companion object {
        internal const val MAX_ENTRYPOINTS_PER_PLUGIN = 64
    }
}

/** Opaque receipt. It intentionally contains no path or callable supplied to the worker. */
class PythonPluginEntrypointActivationReceipt internal constructor(
    internal val transactionId: String,
    val pluginId: String,
    internal val metadataDigest: String,
) {
    init {
        require(PythonPluginEntrypointActivationJournalRecord.TRANSACTION_ID.matches(transactionId)) {
            "Invalid Python plugin entrypoint transaction id"
        }
        require(PythonEnvironmentContract.isPluginId(pluginId)) {
            "Invalid Python plugin entrypoint receipt plugin id"
        }
        require(PythonEnvironmentContract.isSha256(metadataDigest)) {
            "Invalid Python plugin entrypoint receipt identity"
        }
    }
}

sealed interface PythonPluginEntrypointResolution {
    class Resolved internal constructor(
        internal val pluginId: String,
        internal val entrypointId: String,
        internal val environmentDigest: String,
        internal val sourceSha256: String,
        internal val relativePath: String,
        internal val function: String,
        declaredCapabilities: Set<String>,
    ) : PythonPluginEntrypointResolution {
        internal val declaredCapabilities: Set<String> = declaredCapabilities.toSet()

        override fun toString(): String =
            "PythonPluginEntrypointResolution.Resolved(pluginId=$pluginId, entrypointId=$entrypointId)"
    }

    data class Rejected(val errorCode: String) : PythonPluginEntrypointResolution
}

fun interface PythonPluginEntrypointResolver {
    /** [activeEnvironmentDigest] comes from the independently verified active environment pointer. */
    fun resolve(
        pluginId: String,
        entrypointId: String,
        activeEnvironmentDigest: String,
    ): PythonPluginEntrypointResolution

    companion object {
        val DENY_ALL = PythonPluginEntrypointResolver { _, _, _ ->
            PythonPluginEntrypointResolution.Rejected(
                PythonPluginEntrypointRegistry.ERROR_MISSING,
            )
        }
    }
}

/** Passive status that is safe for Workbench/UI projection; it never contains paths or functions. */
data class PythonPluginEntrypointRegistryStatus(
    val available: Boolean,
    val committedPluginCount: Int,
    val committedEntrypointCount: Int,
    val pendingActivationCount: Int,
)

internal sealed interface PythonPluginEntrypointFinalizedSnapshot {
    data class Present(
        val pluginId: String,
        val environmentDigest: String,
        val sourceSha256: String,
        val metadataDigest: String,
    ) : PythonPluginEntrypointFinalizedSnapshot

    data object Absent : PythonPluginEntrypointFinalizedSnapshot
    data object Changed : PythonPluginEntrypointFinalizedSnapshot
    data object Unavailable : PythonPluginEntrypointFinalizedSnapshot
}

internal enum class PythonPluginEntrypointDeactivationResult {
    DEACTIVATED,
    MISSING,
    CHANGED,
    UNAVAILABLE,
}

/**
 * Private, durable registry for callable plugin source.
 *
 * Pending install evidence is durably journaled but never executable. A committed activation stays
 * quarantined until finalize removes its receipt; at invocation the independently selected
 * environment digest must still match the finalized activation.
 */
class PythonPluginEntrypointRegistry(
    stateFile: File,
) : PythonPluginEntrypointResolver {
    private val file = stateFile.absoluteFile
    private val journalDirectory = File(
        requireNotNull(file.parentFile) { "Python entrypoint registry needs a parent" },
        ".${file.name}.activation-journal",
    )
    private val lock = Any()
    private val pending = linkedMapOf<String, PendingActivation>()
    private var loadFailed = false
    private var committed: Map<String, CommittedActivation> = emptyMap()

    init {
        val parent = requireNotNull(file.parentFile) { "Python entrypoint registry needs a parent" }
        require(parent.mkdirs() || parent.isDirectory) { "Cannot create Python entrypoint registry" }
        require(!Files.isSymbolicLink(parent.toPath())) {
            "Python entrypoint registry parent is a symlink"
        }
        require(journalDirectory.mkdirs() || journalDirectory.isDirectory) {
            "Cannot create Python entrypoint activation journal"
        }
        require(!Files.isSymbolicLink(journalDirectory.toPath())) {
            "Python entrypoint activation journal is a symlink"
        }
        runCatching {
            cleanupJournalTemporaries()
            committed = if (file.exists()) loadState() else emptyMap()
            recoverActivationJournals()
        }.onFailure {
            loadFailed = true
            committed = emptyMap()
            pending.clear()
        }
    }

    /** Stages exact worker proof. The receipt grants no execution authority. */
    fun prepareActivation(
        activation: PythonPluginEntrypointActivation,
    ): PythonPluginEntrypointActivationReceipt = synchronized(lock) {
        check(!loadFailed) { "Python plugin entrypoint registry is unavailable" }
        require(committed.size < MAX_PLUGINS || activation.pluginId in committed) {
            "Too many committed Python plugins"
        }
        require(pending.values.none { it.activation.activation.pluginId == activation.pluginId }) {
            "Python plugin already has a pending entrypoint activation"
        }
        require(activation.metadataDigest == metadataDigest(activation)) {
            "Python plugin activation identity changed"
        }
        val transactionId = UUID.randomUUID().toString().replace("-", "")
        val receipt = PythonPluginEntrypointActivationReceipt(
            transactionId = transactionId,
            pluginId = activation.pluginId,
            metadataDigest = activation.metadataDigest,
        )
        val staged = PendingActivation(
            receipt = receipt,
            activation = CommittedActivation(activation),
            previous = committed[activation.pluginId],
        )
        try {
            writeActivationJournal(staged)
            require(pending.put(transactionId, staged) == null) {
                "Duplicate Python plugin entrypoint transaction"
            }
        } catch (failure: Throwable) {
            runCatching {
                activationJournalFile(transactionId).takeIf(File::exists)?.let(::removeJournalFile)
            }.onFailure(failure::addSuppressed)
            throw failure
        }
        receipt
    }

    /**
     * Durably records exact environment and source postconditions. The entries remain quarantined
     * until [finalizeActivation] closes the wider plugin transaction.
     */
    fun commitActivation(
        receipt: PythonPluginEntrypointActivationReceipt,
        activeEnvironmentDigest: String,
        activeSourceSha256: String,
    ): Unit = synchronized(lock) {
        check(!loadFailed) { "Python plugin entrypoint registry is unavailable" }
        require(PythonEnvironmentContract.isSha256(activeEnvironmentDigest))
        require(PythonEnvironmentContract.isSha256(activeSourceSha256))
        val staged = requireNotNull(pending[receipt.transactionId]) {
            "Unknown Python plugin entrypoint activation"
        }
        require(staged.matches(receipt)) { "Python plugin entrypoint receipt identity changed" }
        require(staged.activation.activation.environmentDigest == activeEnvironmentDigest) {
            "Python plugin environment postcondition does not match"
        }
        require(staged.activation.activation.sourceSha256 == activeSourceSha256) {
            "Python plugin source postcondition does not match"
        }
        when (staged.state) {
            ActivationState.PREPARED -> {
                require(sameActivation(committed[receipt.pluginId], staged.previous)) {
                    "Python plugin entrypoint activation changed concurrently"
                }
                persistActivationState(staged, ActivationState.COMMITTED)
                staged.state = ActivationState.COMMITTED
            }
            ActivationState.COMMITTED -> require(
                sameActivation(committed[receipt.pluginId], staged.previous) ||
                    sameActivation(committed[receipt.pluginId], staged.activation),
            ) {
                "Committed Python plugin entrypoint activation changed concurrently"
            }
        }
        if (!sameActivation(committed[receipt.pluginId], staged.activation)) {
            val replacement = committed.toMutableMap().apply {
                put(receipt.pluginId, staged.activation)
            }.toMap()
            persist(replacement)
            committed = replacement
        }
        Unit
    }

    fun finalizeActivation(receipt: PythonPluginEntrypointActivationReceipt): Unit =
        synchronized(lock) {
            check(!loadFailed) { "Python plugin entrypoint registry is unavailable" }
            val staged = requireNotNull(pending[receipt.transactionId]) {
                "Unknown Python plugin entrypoint activation"
            }
            require(staged.matches(receipt)) { "Python plugin entrypoint receipt identity changed" }
            require(staged.state == ActivationState.COMMITTED) {
                "Python plugin entrypoint activation is not committed"
            }
            require(sameActivation(committed[receipt.pluginId], staged.activation)) {
                "Committed Python plugin entrypoint registry state is missing"
            }
            removeActivationJournal(staged)
            require(pending.remove(receipt.transactionId) === staged) {
                "Python plugin entrypoint activation changed during finalize"
            }
            Unit
        }

    fun rollbackActivation(receipt: PythonPluginEntrypointActivationReceipt): Boolean =
        synchronized(lock) {
            check(!loadFailed) { "Python plugin entrypoint registry is unavailable" }
            val staged = pending[receipt.transactionId] ?: return@synchronized false
            require(staged.matches(receipt)) { "Python plugin entrypoint receipt identity changed" }
            when (staged.state) {
                ActivationState.PREPARED -> require(
                    sameActivation(committed[receipt.pluginId], staged.previous),
                ) {
                    "Prepared Python plugin entrypoint activation changed before rollback"
                }
                ActivationState.COMMITTED -> {
                    require(
                        sameActivation(committed[receipt.pluginId], staged.activation) ||
                            sameActivation(committed[receipt.pluginId], staged.previous),
                    ) {
                        "Python plugin entrypoint activation changed before rollback"
                    }
                    if (!sameActivation(committed[receipt.pluginId], staged.previous)) {
                        val replacement = committed.toMutableMap().apply {
                            if (staged.previous == null) {
                                remove(receipt.pluginId)
                            } else {
                                put(receipt.pluginId, staged.previous)
                            }
                        }.toMap()
                        persist(replacement)
                        committed = replacement
                    }
                }
            }
            removeActivationJournal(staged)
            require(pending.remove(receipt.transactionId) === staged) {
                "Python plugin entrypoint activation changed during rollback"
            }
            true
        }

    fun removeCommitted(
        pluginId: String,
        expectedEnvironmentDigest: String,
    ): Boolean = synchronized(lock) {
        check(!loadFailed) { "Python plugin entrypoint registry is unavailable" }
        require(PythonEnvironmentContract.isPluginId(pluginId))
        require(PythonEnvironmentContract.isSha256(expectedEnvironmentDigest))
        val existing = committed[pluginId] ?: return@synchronized false
        require(existing.activation.environmentDigest == expectedEnvironmentDigest) {
            "Python plugin environment changed before registry removal"
        }
        require(pending.values.none { it.activation.activation.pluginId == pluginId }) {
            "Python plugin has a pending entrypoint activation"
        }
        val replacement = committed.toMutableMap().apply { remove(pluginId) }.toMap()
        persist(replacement)
        committed = replacement
        true
    }

    /**
     * Secret-free exact projection used by the ordinary plugin-uninstall journal. Pending
     * activation receipts deliberately make the result unusable rather than being ignored.
     */
    internal fun finalizedSnapshot(
        pluginId: String,
    ): PythonPluginEntrypointFinalizedSnapshot = synchronized(lock) {
        if (!PythonEnvironmentContract.isPluginId(pluginId)) {
            return@synchronized PythonPluginEntrypointFinalizedSnapshot.Changed
        }
        if (loadFailed) return@synchronized PythonPluginEntrypointFinalizedSnapshot.Unavailable
        if (pending.values.any { it.activation.activation.pluginId == pluginId }) {
            return@synchronized PythonPluginEntrypointFinalizedSnapshot.Changed
        }
        val existing = committed[pluginId]
            ?: return@synchronized PythonPluginEntrypointFinalizedSnapshot.Absent
        val activation = existing.activation
        if (existing.metadataDigest != activation.metadataDigest ||
            existing.metadataDigest != metadataDigest(activation)
        ) {
            return@synchronized PythonPluginEntrypointFinalizedSnapshot.Changed
        }
        PythonPluginEntrypointFinalizedSnapshot.Present(
            pluginId = pluginId,
            environmentDigest = activation.environmentDigest,
            sourceSha256 = activation.sourceSha256,
            metadataDigest = existing.metadataDigest,
        )
    }

    /** Removes one finalized projection only when every persisted identity field still matches. */
    internal fun deactivateFinalized(
        pluginId: String,
        expectedEnvironmentDigest: String,
        expectedSourceSha256: String,
        expectedMetadataDigest: String,
    ): PythonPluginEntrypointDeactivationResult = synchronized(lock) {
        if (loadFailed) return@synchronized PythonPluginEntrypointDeactivationResult.UNAVAILABLE
        if (!PythonEnvironmentContract.isPluginId(pluginId) ||
            !PythonEnvironmentContract.isSha256(expectedEnvironmentDigest) ||
            !PythonEnvironmentContract.isSha256(expectedSourceSha256) ||
            !PythonEnvironmentContract.isSha256(expectedMetadataDigest)
        ) {
            return@synchronized PythonPluginEntrypointDeactivationResult.CHANGED
        }
        if (pending.values.any { it.activation.activation.pluginId == pluginId }) {
            return@synchronized PythonPluginEntrypointDeactivationResult.CHANGED
        }
        val existing = committed[pluginId]
            ?: return@synchronized PythonPluginEntrypointDeactivationResult.MISSING
        val activation = existing.activation
        if (activation.environmentDigest != expectedEnvironmentDigest ||
            activation.sourceSha256 != expectedSourceSha256 ||
            existing.metadataDigest != expectedMetadataDigest ||
            existing.metadataDigest != activation.metadataDigest ||
            existing.metadataDigest != metadataDigest(activation)
        ) {
            return@synchronized PythonPluginEntrypointDeactivationResult.CHANGED
        }
        val replacement = committed.toMutableMap().apply { remove(pluginId) }.toMap()
        try {
            persist(replacement)
        } catch (_: Throwable) {
            return@synchronized PythonPluginEntrypointDeactivationResult.UNAVAILABLE
        }
        committed = replacement
        PythonPluginEntrypointDeactivationResult.DEACTIVATED
    }

    override fun resolve(
        pluginId: String,
        entrypointId: String,
        activeEnvironmentDigest: String,
    ): PythonPluginEntrypointResolution = synchronized(lock) {
        if (!PythonEnvironmentContract.isPluginId(pluginId) ||
            !PythonEnvironmentContract.isSha256(activeEnvironmentDigest)
        ) {
            return@synchronized PythonPluginEntrypointResolution.Rejected(ERROR_REGISTRY_INVALID)
        }
        if (loadFailed) {
            return@synchronized PythonPluginEntrypointResolution.Rejected(ERROR_REGISTRY_INVALID)
        }
        val existing = effectiveCommitted(pluginId)
        val declaration = existing?.activation?.declaration(entrypointId)
        if (declaration == null) {
            val isPending = pending.values.any {
                it.activation.activation.pluginId == pluginId &&
                    it.activation.activation.declaration(entrypointId) != null
            }
            return@synchronized PythonPluginEntrypointResolution.Rejected(
                if (isPending) ERROR_UNCOMMITTED else ERROR_MISSING,
            )
        }
        val activation = existing.activation
        if (existing.metadataDigest != metadataDigest(activation) ||
            declaration.sourceSha256 != activation.sourceSha256
        ) {
            return@synchronized PythonPluginEntrypointResolution.Rejected(ERROR_SOURCE_IDENTITY)
        }
        if (activation.environmentDigest != activeEnvironmentDigest) {
            return@synchronized PythonPluginEntrypointResolution.Rejected(ERROR_STALE_ENVIRONMENT)
        }
        PythonPluginEntrypointResolution.Resolved(
            pluginId = pluginId,
            entrypointId = entrypointId,
            environmentDigest = activation.environmentDigest,
            sourceSha256 = activation.sourceSha256,
            relativePath = declaration.relativePath,
            function = declaration.function,
            declaredCapabilities = declaration.declaredCapabilities,
        )
    }

    fun status(): PythonPluginEntrypointRegistryStatus = synchronized(lock) {
        val effective = effectiveCommittedActivations()
        PythonPluginEntrypointRegistryStatus(
            available = !loadFailed,
            committedPluginCount = effective.size,
            committedEntrypointCount = effective.values.sumOf { it.activation.declarations.size },
            pendingActivationCount = pending.size,
        )
    }

    /** Durable activation receipts available to the installer after process death. */
    fun recoveryDescriptors(): List<PythonPluginEntrypointRecoveryDescriptor> =
        synchronized(lock) {
            check(!loadFailed) { "Python plugin entrypoint registry is unavailable" }
            pending.values
                .sortedBy { it.receipt.transactionId }
                .map(PendingActivation::toRecoveryDescriptor)
        }

    /**
     * Proves the exact finalized projection without exposing callable paths or source contents.
     * A null [metadataDigest] means that the outer transaction expected no callable activation.
     */
    internal fun proveFinalizedActivation(
        pluginId: String,
        environmentDigest: String,
        metadataDigest: String?,
    ): PythonPluginEntrypointFinalizedProof = synchronized(lock) {
        if (!PythonEnvironmentContract.isPluginId(pluginId) ||
            !PythonEnvironmentContract.isSha256(environmentDigest) ||
            (metadataDigest != null && !PythonEnvironmentContract.isSha256(metadataDigest))
        ) {
            return@synchronized PythonPluginEntrypointFinalizedProof.CHANGED
        }
        if (loadFailed) return@synchronized PythonPluginEntrypointFinalizedProof.UNAVAILABLE
        if (pending.values.any { it.activation.activation.pluginId == pluginId }) {
            return@synchronized PythonPluginEntrypointFinalizedProof.CHANGED
        }
        val existing = committed[pluginId]
        val exact = if (metadataDigest == null) {
            existing == null
        } else {
            existing != null &&
                existing.activation.pluginId == pluginId &&
                existing.activation.environmentDigest == environmentDigest &&
                existing.metadataDigest == metadataDigest &&
                existing.metadataDigest == existing.activation.metadataDigest
        }
        if (exact) {
            PythonPluginEntrypointFinalizedProof.EXACT
        } else {
            PythonPluginEntrypointFinalizedProof.CHANGED
        }
    }

    private fun loadState(): Map<String, CommittedActivation> {
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "Python entrypoint registry is not a regular file"
        }
        require(file.length() in 1..MAX_STATE_BYTES.toLong()) {
            "Python entrypoint registry has an invalid size"
        }
        val root = JsonContract.parseObject(file.readText(Charsets.UTF_8), MAX_STATE_BYTES)
        JsonContract.requireOnlyKeys(root, setOf("schemaVersion", "activations"), "registry")
        require(JsonContract.requiredLong(root, "schemaVersion") == SCHEMA_VERSION.toLong())
        val values = JsonContract.requiredArray(root, "activations")
        require(values.length() <= MAX_PLUGINS) { "Too many Python plugin activations" }
        return buildMap {
            repeat(values.length()) { index ->
                val encoded = values.opt(index) as? JSONObject
                    ?: error("Python plugin activation must be an object")
                val activation = PythonPluginEntrypointActivationCodec.decode(encoded)
                require(put(activation.pluginId, CommittedActivation(activation)) == null) {
                    "Duplicate committed Python plugin activation"
                }
            }
        }.toMap()
    }

    private fun persist(values: Map<String, CommittedActivation>) {
        require(values.size <= MAX_PLUGINS)
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put(
                "activations",
                JSONArray(values.values.sortedBy { it.activation.pluginId }.map { committed ->
                    PythonPluginEntrypointActivationCodec.encode(committed.activation)
                }),
            )
        val encoded = JsonContract.encodeBounded(root, MAX_STATE_BYTES)
            .toByteArray(StandardCharsets.UTF_8)
        writeAtomic(file, encoded)
    }

    private fun cleanupJournalTemporaries() {
        journalDirectory.listFiles().orEmpty()
            .filter(::isJournalTemporary)
            .forEach { temporary ->
                require(temporary.isFile && !Files.isSymbolicLink(temporary.toPath())) {
                    "Python entrypoint activation journal temporary is unsafe"
                }
                require(temporary.delete() || !temporary.exists()) {
                    "Cannot remove Python entrypoint activation journal temporary"
                }
            }
    }

    private fun recoverActivationJournals() {
        val journals = journalDirectory.listFiles().orEmpty()
            .filterNot(::isJournalTemporary)
            .sortedBy(File::getName)
        require(journals.size <= MAX_PLUGINS) {
            "Too many Python entrypoint activation journals"
        }
        val plugins = linkedSetOf<String>()
        journals.forEach { journal ->
            require(journal.isFile && !Files.isSymbolicLink(journal.toPath())) {
                "Python entrypoint activation journal is not a regular file"
            }
            require(journal.name.endsWith(PythonPluginEntrypointActivationJournalCodec.FILE_SUFFIX)) {
                "Unexpected Python entrypoint activation journal file"
            }
            val record = PythonPluginEntrypointActivationJournalCodec.decode(
                readBoundedJournal(journal),
            )
            require(journal.name == record.transactionId +
                PythonPluginEntrypointActivationJournalCodec.FILE_SUFFIX) {
                "Python entrypoint activation journal name does not match"
            }
            require(plugins.add(record.activation.pluginId)) {
                "Plugin has multiple Python entrypoint activation journals"
            }
            val activation = CommittedActivation(record.activation)
            val previous = record.previous?.let(::CommittedActivation)
            val raw = committed[record.activation.pluginId]
            when (record.state) {
                PythonPluginEntrypointRecoveryState.PREPARED -> require(
                    sameActivation(raw, previous),
                ) {
                    "Prepared Python entrypoint journal has an unexpected registry state"
                }
                PythonPluginEntrypointRecoveryState.COMMITTED -> require(
                    sameActivation(raw, previous) || sameActivation(raw, activation),
                ) {
                    "Committed Python entrypoint journal has an unexpected registry state"
                }
            }
            val receipt = PythonPluginEntrypointActivationReceipt(
                transactionId = record.transactionId,
                pluginId = record.activation.pluginId,
                metadataDigest = record.activation.metadataDigest,
            )
            val staged = PendingActivation(
                receipt = receipt,
                activation = activation,
                previous = previous,
                state = record.state.toActivationState(),
            )
            require(pending.put(receipt.transactionId, staged) == null) {
                "Duplicate Python entrypoint activation transaction"
            }
        }
    }

    private fun writeActivationJournal(staged: PendingActivation) {
        val journal = activationJournalFile(staged.receipt.transactionId)
        require(!journal.exists() && !Files.isSymbolicLink(journal.toPath())) {
            "Python entrypoint activation journal already exists"
        }
        writeAtomic(
            journal,
            PythonPluginEntrypointActivationJournalCodec.encode(staged.toJournal()),
        )
        require(sameJournalRecord(
            PythonPluginEntrypointActivationJournalCodec.decode(readBoundedJournal(journal)),
            staged.toJournal(),
        )) { "Python entrypoint activation journal write changed identity" }
    }

    private fun persistActivationState(staged: PendingActivation, state: ActivationState) {
        val journal = activationJournalFile(staged.receipt.transactionId)
        require(journal.isFile && !Files.isSymbolicLink(journal.toPath())) {
            "Python entrypoint activation journal is missing"
        }
        val replacement = staged.copy(state = state)
        writeAtomic(
            journal,
            PythonPluginEntrypointActivationJournalCodec.encode(replacement.toJournal()),
        )
        require(sameJournalRecord(
            PythonPluginEntrypointActivationJournalCodec.decode(readBoundedJournal(journal)),
            replacement.toJournal(),
        )) { "Python entrypoint activation journal state did not verify" }
    }

    private fun removeActivationJournal(staged: PendingActivation) {
        val journal = activationJournalFile(staged.receipt.transactionId)
        require(journal.isFile && !Files.isSymbolicLink(journal.toPath())) {
            "Python entrypoint activation journal is missing"
        }
        require(sameJournalRecord(
            PythonPluginEntrypointActivationJournalCodec.decode(readBoundedJournal(journal)),
            staged.toJournal(),
        )) { "Python entrypoint activation journal changed before completion" }
        removeJournalFile(journal)
    }

    private fun removeJournalFile(journal: File) {
        require(journal.parentFile?.canonicalFile == journalDirectory.canonicalFile) {
            "Python entrypoint activation journal escaped its root"
        }
        val tombstone = File(
            journalDirectory,
            ".${journal.name}.${UUID.randomUUID()}.completed.tmp",
        )
        moveAtomically(journal, tombstone)
        require(tombstone.delete() || !tombstone.exists()) {
            "Cannot remove completed Python entrypoint activation journal"
        }
    }

    private fun activationJournalFile(transactionId: String): File {
        require(PythonPluginEntrypointActivationJournalRecord.TRANSACTION_ID.matches(transactionId)) {
            "Invalid Python entrypoint activation transaction id"
        }
        return File(
            journalDirectory,
            transactionId + PythonPluginEntrypointActivationJournalCodec.FILE_SUFFIX,
        )
    }

    private fun readBoundedJournal(journal: File): ByteArray {
        require(journal.isFile && !Files.isSymbolicLink(journal.toPath())) {
            "Python entrypoint activation journal is not a regular file"
        }
        require(journal.length() in 1..PythonPluginEntrypointActivationJournalCodec.MAX_BYTES.toLong()) {
            "Python entrypoint activation journal is outside its size limit"
        }
        return FileInputStream(journal).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total = Math.addExact(total, read)
                require(total <= PythonPluginEntrypointActivationJournalCodec.MAX_BYTES) {
                    "Python entrypoint activation journal is outside its size limit"
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun effectiveCommitted(pluginId: String): CommittedActivation? {
        val raw = committed[pluginId]
        val staged = pending.values.singleOrNull {
            it.activation.activation.pluginId == pluginId && it.state == ActivationState.COMMITTED
        } ?: return raw
        return when {
            sameActivation(raw, staged.activation) -> staged.previous
            sameActivation(raw, staged.previous) -> staged.previous
            else -> null
        }
    }

    private fun effectiveCommittedActivations(): Map<String, CommittedActivation> =
        buildMap {
            committed.keys.forEach { pluginId ->
                effectiveCommitted(pluginId)?.let { put(pluginId, it) }
            }
        }

    private fun isJournalTemporary(file: File): Boolean =
        file.name.startsWith(".") && file.name.endsWith(".tmp")

    private fun moveAtomically(source: File, target: File) {
        require(source.parentFile?.canonicalFile == journalDirectory.canonicalFile &&
            target.parentFile?.canonicalFile == journalDirectory.canonicalFile) {
            "Python entrypoint journal move escaped its root"
        }
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun sameActivation(
        first: CommittedActivation?,
        second: CommittedActivation?,
    ): Boolean = if (first == null || second == null) {
        first == null && second == null
    } else {
        first.identity == second.identity &&
            first.activation.canonicalJson().toString() == second.activation.canonicalJson().toString()
    }

    private fun sameJournalRecord(
        first: PythonPluginEntrypointActivationJournalRecord,
        second: PythonPluginEntrypointActivationJournalRecord,
    ): Boolean = first.transactionId == second.transactionId &&
        first.state == second.state &&
        sameActivation(CommittedActivation(first.activation), CommittedActivation(second.activation)) &&
        sameActivation(
            first.previous?.let(::CommittedActivation),
            second.previous?.let(::CommittedActivation),
        )

    private fun PythonPluginEntrypointRecoveryState.toActivationState(): ActivationState =
        when (this) {
            PythonPluginEntrypointRecoveryState.PREPARED -> ActivationState.PREPARED
            PythonPluginEntrypointRecoveryState.COMMITTED -> ActivationState.COMMITTED
        }

    private data class PendingActivation(
        val receipt: PythonPluginEntrypointActivationReceipt,
        val activation: CommittedActivation,
        val previous: CommittedActivation?,
        var state: ActivationState = ActivationState.PREPARED,
    ) {
        fun matches(other: PythonPluginEntrypointActivationReceipt): Boolean =
            receipt.transactionId == other.transactionId &&
                receipt.pluginId == other.pluginId &&
                receipt.metadataDigest == other.metadataDigest

        fun toJournal() = PythonPluginEntrypointActivationJournalRecord(
            transactionId = receipt.transactionId,
            state = when (state) {
                ActivationState.PREPARED -> PythonPluginEntrypointRecoveryState.PREPARED
                ActivationState.COMMITTED -> PythonPluginEntrypointRecoveryState.COMMITTED
            },
            activation = activation.activation,
            previous = previous?.activation,
        )

        fun toRecoveryDescriptor() = PythonPluginEntrypointRecoveryDescriptor(
            receipt = receipt,
            state = when (state) {
                ActivationState.PREPARED -> PythonPluginEntrypointRecoveryState.PREPARED
                ActivationState.COMMITTED -> PythonPluginEntrypointRecoveryState.COMMITTED
            },
            activation = activation.identity,
            previous = previous?.identity,
        )
    }

    private enum class ActivationState { PREPARED, COMMITTED }

    private class CommittedActivation(val activation: PythonPluginEntrypointActivation) {
        val metadataDigest = PythonPluginEntrypointRegistry.metadataDigest(activation)
        val identity = PythonPluginEntrypointActivationIdentity(
            pluginId = activation.pluginId,
            environmentDigest = activation.environmentDigest,
            sourceSha256 = activation.sourceSha256,
            metadataDigest = metadataDigest,
        )
    }

    companion object {
        const val ERROR_MISSING = "python_plugin_entrypoint_missing"
        const val ERROR_UNCOMMITTED = "python_plugin_entrypoint_uncommitted"
        const val ERROR_STALE_ENVIRONMENT = "python_plugin_entrypoint_stale_environment"
        const val ERROR_SOURCE_IDENTITY = "python_plugin_entrypoint_source_identity_invalid"
        const val ERROR_REGISTRY_INVALID = "python_plugin_entrypoint_registry_invalid"

        private const val SCHEMA_VERSION = 1
        private const val MAX_PLUGINS = 128
        private const val MAX_STATE_BYTES = 1024 * 1024

        private fun metadataDigest(activation: PythonPluginEntrypointActivation): String =
            sha256(activation.canonicalJson().toString().toByteArray(StandardCharsets.UTF_8))
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte ->
        String.format(Locale.US, "%02x", byte.toInt() and 0xff)
    }

private fun writeAtomic(target: File, bytes: ByteArray) {
    require(!target.exists() || !Files.isSymbolicLink(target.toPath())) {
        "Python entrypoint registry is a symlink"
    }
    val parent = requireNotNull(target.parentFile)
    val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        require(target.isFile && !Files.isSymbolicLink(target.toPath())) {
            "Python entrypoint registry write did not verify"
        }
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}
