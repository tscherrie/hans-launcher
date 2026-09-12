package ai.hans.standard.runtime.python

import ai.hans.standard.codex.JsonContract
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Atomic-file seam used to verify activation rollback without weakening production semantics. */
fun interface PythonEnvironmentAtomicFileWriter {
    fun write(target: File, bytes: ByteArray)
}

/**
 * Transactional app-private Python environment store.
 *
 * The installer never invokes pip, a shell, a build backend, or the network. It consumes only an
 * exact lock plus caller-provided offline wheel files, validates those files byte-for-byte, and
 * creates a deterministic read-only PYZ. Native packages are references to an allowlisted catalog
 * whose implementation is supplied from signed APK contents.
 */
class PythonEnvironmentStore(
    rootDirectory: File,
    private val wheelValidator: PythonWheelArchiveValidator,
    private val importSelfTester: PythonEnvironmentImportSelfTester,
    private val nativeCatalog: PythonNativePackageCatalog = PythonNativePackageCatalog.EMPTY,
    private val baselineProvider: PythonEnvironmentArchiveProvider =
        StoreBaselineEnvironmentArchiveProvider(rootDirectory),
    private val atomicWriter: PythonEnvironmentAtomicFileWriter =
        PythonEnvironmentAtomicFileWriter(::writeAtomicFile),
) : PythonEnvironmentArchiveProvider, PythonEnvironmentSelectionProvider {
    private val root = rootDirectory.canonicalFile
    private val versions = File(root, "versions")
    private val active = File(root, "active")
    private val staging = File(root, "staging")
    private val activationJournal = File(root, "activation-journal")
    private val mutationLock = Any()

    /**
     * A staged archive has to be opened by the descriptor broker while [prepareInstall] still
     * owns [mutationLock] and waits for the isolated-worker import proof. Keep that one immutable
     * lease in a concurrent index so the Binder callback never waits for the installer lock.
     * Publishing archive and native authority as one value also prevents a mixed-generation read.
     */
    private val pendingEnvironments =
        ConcurrentHashMap<String, PendingEnvironmentArchive>()
    private val archiveIndex = ConcurrentHashMap<String, File>()
    private val nativeModuleIndex = ConcurrentHashMap<String, List<PythonAllowedNativeModule>>()
    private val activationTransactions = ConcurrentHashMap<String, PendingActivation>()

    init {
        require(!Files.isSymbolicLink(rootDirectory.toPath())) { "Environment root is a symlink" }
        require(root.mkdirs() || root.isDirectory) { "Cannot create Python environment root" }
        require(versions.mkdirs() || versions.isDirectory) { "Cannot create environment versions" }
        require(active.mkdirs() || active.isDirectory) { "Cannot create environment activation store" }
        require(staging.mkdirs() || staging.isDirectory) { "Cannot create environment staging store" }
        require(activationJournal.mkdirs() || activationJournal.isDirectory) {
            "Cannot create environment activation journal"
        }
        listOf(root, versions, active, staging, activationJournal)
            .forEach(::requireDirectoryWithoutSymlink)
        recoverInterruptedTransactions()
        rebuildArchiveIndex()
        recoverDurableActivationTransactions()
        garbageCollectUnreferencedVersions()
        rebuildArchiveIndex()
    }

    /** Convenience wrapper for callers whose install proof and activation are one transaction. */
    fun installAndActivate(
        request: PythonEnvironmentInstallRequest,
        cancellation: PythonEnvironmentInstallCancellation = PythonEnvironmentInstallCancellation.NONE,
    ): PythonEnvironmentStatus {
        val receipt = prepareInstall(request, cancellation)
        return try {
            commitActivation(receipt)
            finalizeActivation(receipt)
            synchronized(mutationLock) {
                requireNotNull(readEffectiveActive(receipt.pluginId)) {
                    "Finalized Python environment is not active"
                }.toStatus(PythonEnvironmentState.ACTIVE)
            }
        } catch (failure: Throwable) {
            runCatching { rollbackActivation(receipt) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    /**
     * Builds an immutable, self-tested version but deliberately leaves the active pointer alone.
     * Plugin installation may now run and prove its App Server postcondition before committing.
     */
    fun prepareInstall(
        request: PythonEnvironmentInstallRequest,
        cancellation: PythonEnvironmentInstallCancellation = PythonEnvironmentInstallCancellation.NONE,
    ): PythonEnvironmentInstallReceipt = synchronized(mutationLock) {
        cancellation.throwIfCancellationRequested()
        validateOfflineSet(request)
        validateSourceContract(request)
        val lock = request.lock
        val previous = readActive(lock.pluginId)
        val finalDirectory = environmentDirectory(lock)
        readInstalled(finalDirectory)?.let { existing ->
            require(existing.lockDigest == lock.lockDigest) { "Installed environment lock changed" }
            return@synchronized registerPreparedActivation(existing, previous, newlyInstalled = false)
        }
        require(!finalDirectory.exists()) { "Existing environment version is corrupt" }
        enforcePreInstallQuota()

        val transaction = File(staging, ".staging-${UUID.randomUUID()}")
        require(transaction.mkdir()) { "Cannot create Python environment transaction" }
        val sitePackages = File(transaction, "site-packages")
        val pluginSource = File(transaction, "plugin-source")
        val archive = File(transaction, ARCHIVE_FILE)
        var archiveDigest: String? = null
        var pendingEnvironment: PendingEnvironmentArchive? = null
        try {
            require(sitePackages.mkdir()) { "Cannot create site-packages staging" }
            require(pluginSource.mkdir()) { "Cannot create plugin-source staging" }
            val claimedPaths = mutableSetOf<String>()
            val importNames = linkedSetOf<String>()
            var extractedBytes = 0L
            var extractedFiles = 0

            lock.wheels.sortedBy { it.normalizedName }.forEach { pin ->
                cancellation.throwIfCancellationRequested()
                val wheelFile = request.offlineWheels.fileFor(pin)
                val validated = wheelValidator.validate(pin, wheelFile, lock.target)
                extractedBytes = checkedEnvironmentBytes(extractedBytes, validated.extractedBytes)
                extractedFiles = checkedEnvironmentFiles(extractedFiles, validated.fileCount)
                wheelValidator.extract(validated, wheelFile, sitePackages, claimedPaths)
                importNames += validated.importNames
            }
            val nativeEntries = validateNativeCatalog(lock)
            val allowedNativeModules = nativeEntries
                .flatMap(PythonNativeCatalogEntry::allowedNativeModules)
                .sortedWith(
                    compareBy(PythonAllowedNativeModule::module, PythonAllowedNativeModule::packagedName),
                )
            require(allowedNativeModules.distinct() == allowedNativeModules) {
                "Native catalog packages claim the same module"
            }
            nativeEntries.forEach { entry ->
                cancellation.throwIfCancellationRequested()
                val companionFile = File(transaction, ".native-${entry.catalogId}.whl")
                try {
                    copySignedCompanionWheel(entry, companionFile, cancellation)
                    val companion = entry.companionWheel
                    val pin = PythonWheelPin(
                        packageName = entry.packageName,
                        version = entry.version,
                        fileName = companion.fileName,
                        sha256 = companion.sha256,
                        sizeBytes = companion.sizeBytes,
                        sourceUri = "file:///signed-apk/${companion.assetPath}",
                        requiresPython = entry.requiresPython,
                    )
                    val validated = wheelValidator.validate(pin, companionFile, lock.target)
                    require(validated.importNames.all { wheelImport ->
                        entry.importNames.any { declared ->
                            declared == wheelImport || declared.startsWith("$wheelImport.")
                        }
                    }) { "Native companion wheel exposes an undeclared import" }
                    extractedBytes = checkedEnvironmentBytes(extractedBytes, validated.extractedBytes)
                    extractedFiles = checkedEnvironmentFiles(extractedFiles, validated.fileCount)
                    wheelValidator.extract(validated, companionFile, sitePackages, claimedPaths)
                    importNames += validated.importNames
                    importNames += entry.importNames
                } finally {
                    if (companionFile.exists()) {
                        require(companionFile.delete()) { "Cannot remove staged native companion wheel" }
                    }
                }
            }
            cancellation.throwIfCancellationRequested()

            val copiedSource = copyVerifiedPluginSource(
                request.pluginSourceDirectory,
                pluginSource,
                lock.sourceSha256,
                cancellation,
            )
            extractedBytes = checkedEnvironmentBytes(extractedBytes, copiedSource.bytes)
            extractedFiles = checkedEnvironmentFiles(extractedFiles, copiedSource.files)
            cancellation.throwIfCancellationRequested()

            val internalManifest = internalArchiveManifest(lock, importNames)
            buildDeterministicArchive(
                archive = archive,
                sitePackages = sitePackages,
                pluginSource = pluginSource,
                internalManifest = internalManifest,
                cancellation = cancellation,
            )
            require(archive.length() in PythonRuntimeFdContract.MIN_ENVIRONMENT_BYTES..
                PythonRuntimeFdContract.MAX_ENVIRONMENT_BYTES) {
                "Python environment archive is outside its transport quota"
            }
            archiveDigest = sha256(archive)
            val prepared = PythonPreparedEnvironment(
                pluginId = lock.pluginId,
                lockDigest = lock.lockDigest,
                target = lock.target,
                stagingDirectory = transaction,
                sitePackagesDirectory = sitePackages,
                sourceDirectory = pluginSource,
                environmentArchive = archive,
                importNames = importNames.toSet(),
            )
            pendingEnvironment = PendingEnvironmentArchive(
                archive = archive,
                allowedNativeModules = allowedNativeModules.toList(),
            )
            require(pendingEnvironments.putIfAbsent(archiveDigest, pendingEnvironment) == null) {
                "Python environment self-test lease already exists"
            }
            val selfTest = try {
                cancellation.throwIfCancellationRequested()
                importSelfTester.test(prepared)
            } finally {
                pendingEnvironments.remove(archiveDigest, pendingEnvironment)
            }
            cancellation.throwIfCancellationRequested()
            require(selfTest.succeeded) {
                "Python import self-test failed: ${selfTest.errorCode ?: "unknown"}"
            }
            require(selfTest.importedNames.containsAll(importNames)) {
                "Python import self-test did not prove every locked import"
            }

            deleteTreeWithoutFollowingLinks(sitePackages)
            deleteTreeWithoutFollowingLinks(pluginSource)
            val manifest = InstalledManifest(
                pluginId = lock.pluginId,
                target = lock.target,
                lockDigest = lock.lockDigest,
                environmentDigest = archiveDigest,
                environmentBytes = archive.length(),
                sourceSha256 = lock.sourceSha256,
                wheelPackages = lock.wheels.map { "${it.normalizedName}==${it.version}" },
                nativePackages = lock.nativePackages.map { "${it.normalizedName}==${it.version}" },
                allowedNativeModules = allowedNativeModules,
                importNames = importNames,
                resolutionProof = RESOLUTION_PROOF_EXACT_ARTIFACTS,
            )
            writeDurableFile(File(transaction, LOCK_FILE), PythonEnvironmentLockCodec.encode(lock))
            writeDurableFile(File(transaction, MANIFEST_FILE), manifest.encode())
            cancellation.throwIfCancellationRequested()
            enforceFinalQuota(transaction)
            require(finalDirectory.parentFile?.let { it.mkdirs() || it.isDirectory } == true) {
                "Cannot create environment version parent"
            }
            moveAtomically(transaction, finalDirectory)
            makeTreeImmutable(finalDirectory)
            val installed = readInstalled(finalDirectory)
                ?: error("Activated Python environment failed post-write verification")
            archiveIndex[installed.environmentDigest] = installed.archive
            nativeModuleIndex[installed.environmentDigest] = installed.manifest.allowedNativeModules
            registerPreparedActivation(installed, previous, newlyInstalled = true)
        } finally {
            if (archiveDigest != null && pendingEnvironment != null) {
                pendingEnvironments.remove(archiveDigest, pendingEnvironment)
            }
            if (transaction.exists()) deleteTreeWithoutFollowingLinks(transaction)
        }
    }

    /** Commits only if no other transaction changed this plugin's active environment meanwhile. */
    fun commitActivation(receipt: PythonEnvironmentInstallReceipt): PythonEnvironmentStatus =
        synchronized(mutationLock) {
            val pending = pendingActivation(receipt)
            val transitionedFromPrepared = pending.state == ActivationTransactionState.PREPARED
            when (pending.state) {
                ActivationTransactionState.PREPARED -> {
                    require(sameIdentity(readActive(receipt.pluginId), pending.previous)) {
                        "Python environment activation changed concurrently"
                    }
                    persistActivationState(pending, ActivationTransactionState.COMMITTED)
                    pending.state = ActivationTransactionState.COMMITTED
                }
                ActivationTransactionState.COMMITTED -> {
                    val rawActive = readActive(receipt.pluginId)
                    require(
                        sameIdentity(rawActive, pending.previous) ||
                            sameIdentity(rawActive, pending.installed),
                    ) { "Committed Python environment pointer changed concurrently" }
                }
            }
            if (!sameIdentity(readActive(receipt.pluginId), pending.installed)) {
                try {
                    activate(pending.installed)
                } catch (failure: Throwable) {
                    val restored = runCatching { restorePreviousPointer(pending) }.isSuccess
                    if (transitionedFromPrepared && restored) {
                        runCatching {
                            persistActivationState(pending, ActivationTransactionState.PREPARED)
                            pending.state = ActivationTransactionState.PREPARED
                        }.onFailure(failure::addSuppressed)
                    }
                    throw failure
                }
            }
            pending.installed.toStatus(PythonEnvironmentState.INSTALLED)
        }

    /** Completes a proven external transaction and releases its rollback receipt. */
    fun finalizeActivation(receipt: PythonEnvironmentInstallReceipt) = synchronized(mutationLock) {
        val pending = pendingActivation(receipt)
        require(pending.state == ActivationTransactionState.COMMITTED) {
            "Python environment transaction was not committed"
        }
        require(sameIdentity(readActive(receipt.pluginId), pending.installed)) {
            "Committed Python environment pointer is not installed"
        }
        removeActivationJournal(pending)
        require(activationTransactions.remove(receipt.transactionId, pending)) {
            "Python environment transaction changed"
        }
        garbageCollectUnreferencedVersions()
        Unit
    }

    /** Restores the exact previous pointer; an uncommitted prepare leaves the pointer untouched. */
    fun rollbackActivation(receipt: PythonEnvironmentInstallReceipt) = synchronized(mutationLock) {
        val pending = pendingActivation(receipt)
        when (pending.state) {
            ActivationTransactionState.PREPARED -> {
                require(sameIdentity(readActive(receipt.pluginId), pending.previous)) {
                    "Python environment activation changed concurrently"
                }
            }
            ActivationTransactionState.COMMITTED -> {
                val rawActive = readActive(receipt.pluginId)
                require(
                    sameIdentity(rawActive, pending.installed) ||
                        sameIdentity(rawActive, pending.previous),
                ) { "Cannot roll back an environment pointer changed by another transaction" }
                if (!sameIdentity(rawActive, pending.previous)) {
                    restorePreviousPointer(pending)
                }
            }
        }
        removeActivationJournal(pending)
        require(activationTransactions.remove(receipt.transactionId, pending)) {
            "Python environment transaction changed"
        }
        discardPreparedIfExclusive(pending)
        garbageCollectUnreferencedVersions()
        Unit
    }

    /** Durable transactions available to the installer after process death. */
    fun recoveryDescriptors(): List<PythonEnvironmentRecoveryDescriptor> =
        synchronized(mutationLock) {
            activationTransactions.values
                .sortedBy { it.receipt.transactionId }
                .map(PendingActivation::toRecoveryDescriptor)
        }

    override fun digestFor(pluginId: String?): String = synchronized(mutationLock) {
        if (pluginId == null) return PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST
        if (!PythonEnvironmentContract.isPluginId(pluginId)) return PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST
        return readEffectiveActive(pluginId)?.environmentDigest
            ?: PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST
    }

    override fun snapshot(): PythonEnvironmentStatusProjection = synchronized(mutationLock) {
        val activeByPlugin = effectiveActivePointers().associateBy { it.pluginId }
        val statuses = installedDirectories()
            .take(PythonEnvironmentLimits.MAX_STATUS_ENVIRONMENTS)
            .mapNotNull { directory ->
                val installed = readInstalled(directory)
                if (installed != null) {
                    val isActive = activeByPlugin[installed.pluginId]?.let {
                        it.lockDigest == installed.lockDigest &&
                            it.environmentDigest == installed.environmentDigest
                    } == true
                    installed.toStatus(
                        if (isActive) PythonEnvironmentState.ACTIVE else PythonEnvironmentState.INSTALLED,
                    )
                } else {
                    corruptStatus(directory)
                }
            }
            .sortedWith(compareBy<PythonEnvironmentStatus> { it.pluginId }.thenBy { it.lockDigest })
        PythonEnvironmentStatusProjection(
            effectiveEnvironmentDigest = PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST,
            environments = statuses,
        )
    }

    override fun open(environmentDigest: String): PythonEnvironmentArchiveReceipt {
        require(PythonEnvironmentContract.isSha256(environmentDigest)) { "Invalid environment digest" }
        pendingEnvironments[environmentDigest]?.let { pending ->
            requireRegularArchive(pending.archive, environmentDigest)
            return PythonEnvironmentArchiveReceipt(
                archive = pending.archive.canonicalFile,
                sha256 = environmentDigest,
                sizeBytes = pending.archive.length(),
                allowedNativeModules = pending.allowedNativeModules,
            )
        }
        return synchronized(mutationLock) {
            if (environmentDigest == PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST) {
                return@synchronized baselineProvider.open(environmentDigest)
            }
            require(!isQuarantinedEnvironment(environmentDigest)) {
                "Python environment activation is committed but not finalized"
            }
            val candidate = archiveIndex[environmentDigest]
                ?: findAndIndexArchive(environmentDigest)
                ?: error("No installed Python environment matches the requested digest")
            requireRegularArchive(candidate, environmentDigest)
            val allowedNativeModules = nativeModuleIndex[environmentDigest]
                ?: error("Python environment native authority is unavailable")
            PythonEnvironmentArchiveReceipt(
                archive = candidate.canonicalFile,
                sha256 = environmentDigest,
                sizeBytes = candidate.length(),
                allowedNativeModules = allowedNativeModules,
            )
        }
    }

    private fun validateOfflineSet(request: PythonEnvironmentInstallRequest) {
        val expected = request.lock.wheels.map { it.sha256 }.toSet()
        require(request.offlineWheels.digests() == expected) {
            "Offline wheel set must exactly match the lock"
        }
    }

    private fun validateSourceContract(request: PythonEnvironmentInstallRequest) {
        val source = request.pluginSourceDirectory
        if (request.lock.sourceSha256 == null) {
            require(source == null) { "Unlocked plugin source is forbidden" }
        } else {
            require(source != null) { "Locked plugin source is missing" }
            require(source.isDirectory && !Files.isSymbolicLink(source.toPath())) {
                "Plugin source is not a regular directory"
            }
        }
    }

    private fun validateNativeCatalog(lock: PythonEnvironmentLock): List<PythonNativeCatalogEntry> = buildList {
        lock.nativePackages.sortedBy { it.normalizedName }.forEach { pin ->
            val entry = nativeCatalog.find(pin, lock.target)
                ?: error("Native package is absent from the signed APK catalog: ${pin.packageName}")
            require(entry.catalogId == pin.catalogId &&
                PythonEnvironmentContract.normalizePackageName(entry.packageName) == pin.normalizedName &&
                entry.version == pin.version &&
                entry.payloadSha256 == pin.payloadSha256 &&
                lock.target.directorySegment in entry.supportedTargets) {
                "Native package catalog pin does not match the signed APK payload"
            }
            add(entry)
        }
    }

    private fun copySignedCompanionWheel(
        entry: PythonNativeCatalogEntry,
        destination: File,
        cancellation: PythonEnvironmentInstallCancellation,
    ) {
        require(!destination.exists() && destination.parentFile?.canonicalFile?.toPath()
            ?.startsWith(staging.toPath()) == true) {
            "Native companion staging escaped the transaction store"
        }
        val expected = entry.companionWheel
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        expected.source.open().use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    cancellation.throwIfCancellationRequested()
                    val read = input.read(buffer)
                    if (read < 0) break
                    size = Math.addExact(size, read.toLong())
                    require(size <= expected.sizeBytes && size <= PythonEnvironmentLimits.MAX_WHEEL_BYTES) {
                        "Native companion wheel exceeds its signed byte limit"
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        require(size == expected.sizeBytes && digest.digest().joinToString("") {
            "%02x".format(Locale.US, it.toInt() and 0xff)
        } == expected.sha256) {
            "Native companion wheel does not match the signed APK catalog"
        }
        require(destination.isFile && !Files.isSymbolicLink(destination.toPath())) {
            "Native companion staging is not a regular file"
        }
    }

    private fun copyVerifiedPluginSource(
        source: File?,
        destination: File,
        expectedDigest: String?,
        cancellation: PythonEnvironmentInstallCancellation,
    ): SourceCopyReceipt {
        if (source == null) return SourceCopyReceipt(0, 0, PythonEnvironmentSourceDigest.EMPTY)
        val entries = scanSource(source, cancellation)
        val actualDigest = PythonEnvironmentSourceDigest.digest(source)
        require(actualDigest == expectedDigest) { "Plugin source digest does not match its lock" }
        var bytes = 0L
        entries.forEach { entry ->
            cancellation.throwIfCancellationRequested()
            val target = resolveBelow(destination, entry.relativePath)
            require(target.parentFile?.let { it.mkdirs() || it.isDirectory } == true) {
                "Cannot create plugin-source parent"
            }
            FileInputStream(entry.file).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        cancellation.throwIfCancellationRequested()
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        require(written <= entry.bytes) { "Plugin source changed during copy" }
                        output.write(buffer, 0, read)
                    }
                    require(written == entry.bytes) { "Plugin source changed during copy" }
                    output.fd.sync()
                }
            }
            require(target.setReadable(true, true)) { "Cannot make plugin source readable" }
            require(target.setWritable(false, false)) { "Cannot make plugin source immutable" }
            target.setExecutable(false, false)
            bytes += entry.bytes
        }
        val copied = scanSource(destination, cancellation)
        require(PythonEnvironmentSourceDigest.digest(destination) == actualDigest) {
            "Plugin source changed while staging"
        }
        return SourceCopyReceipt(entries.size, bytes, actualDigest)
    }

    private fun scanSource(
        source: File,
        cancellation: PythonEnvironmentInstallCancellation,
    ): List<SourceEntry> {
        require(source.isDirectory && !Files.isSymbolicLink(source.toPath())) {
            "Plugin source root is invalid"
        }
        val rootPath = source.canonicalFile.toPath()
        val folded = mutableSetOf<String>()
        val entries = mutableListOf<SourceEntry>()
        Files.walk(rootPath).use { paths ->
            paths.sorted().forEach { path ->
                cancellation.throwIfCancellationRequested()
                if (path == rootPath) return@forEach
                require(!Files.isSymbolicLink(path)) { "Plugin source symlinks are forbidden" }
                require(Files.isDirectory(path) || Files.isRegularFile(path)) {
                    "Plugin source contains a non-regular filesystem object"
                }
                if (Files.isDirectory(path)) return@forEach
                val relative = rootPath.relativize(path).joinToString("/") { it.toString() }
                validateRelativeArchivePath(relative)
                val lower = relative.lowercase(Locale.US)
                require(folded.add(lower)) { "Plugin source contains a case-colliding path" }
                require(!lower.endsWith(".pyc") && !lower.contains("/__pycache__/") &&
                    !lower.startsWith("__pycache__/") && DANGEROUS_SOURCE_SUFFIXES.none(lower::endsWith)) {
                    "Plugin source contains a forbidden executable payload"
                }
                val size = Files.size(path)
                require(size in 0..PythonEnvironmentLimits.MAX_SOURCE_FILE_BYTES) {
                    "Plugin source file is too large"
                }
                entries += SourceEntry(relative, path.toFile(), size)
                require(entries.size <= PythonEnvironmentLimits.MAX_SOURCE_FILES) {
                    "Plugin source has too many files"
                }
                require(entries.sumOf { it.bytes } <= PythonEnvironmentLimits.MAX_SOURCE_BYTES) {
                    "Plugin source is too large"
                }
            }
        }
        return entries.sortedBy { it.relativePath }
    }

    private fun buildDeterministicArchive(
        archive: File,
        sitePackages: File,
        pluginSource: File,
        internalManifest: ByteArray,
        cancellation: PythonEnvironmentInstallCancellation,
    ) {
        val sources = mutableListOf<ArchiveSource>()
        sources += ArchiveSource.Bytes(INTERNAL_MANIFEST, internalManifest)
        sources += archiveSources(sitePackages, "", cancellation)
        sources += archiveSources(pluginSource, "$PLUGIN_SOURCE_PREFIX/", cancellation)
        val exact = mutableSetOf<String>()
        val folded = mutableSetOf<String>()
        sources.forEach {
            validateRelativeArchivePath(it.path)
            require(exact.add(it.path) && folded.add(it.path.lowercase(Locale.US))) {
                "Environment archive contains a colliding path"
            }
        }
        require(sources.size <= PythonEnvironmentLimits.MAX_FILES_PER_ENVIRONMENT) {
            "Environment archive has too many files"
        }
        require(sources.sumOf { it.size } <= PythonEnvironmentLimits.MAX_EXTRACTED_BYTES_PER_ENVIRONMENT) {
            "Environment archive is too large"
        }
        FileOutputStream(archive).use { fileOutput ->
            ZipOutputStream(BufferedOutputStream(fileOutput)).use { zip ->
                zip.setLevel(0)
                sources.sortedBy { it.path }.forEach { source ->
                    cancellation.throwIfCancellationRequested()
                    val entry = ZipEntry(source.path)
                    entry.method = ZipEntry.STORED
                    entry.size = source.size
                    entry.compressedSize = source.size
                    entry.crc = source.crc32(cancellation)
                    entry.time = DETERMINISTIC_ZIP_TIME_MILLIS
                    zip.putNextEntry(entry)
                    source.copyTo(zip, cancellation)
                    zip.closeEntry()
                }
            }
        }
        RandomAccessFile(archive, "rw").use { it.fd.sync() }
    }

    private fun archiveSources(
        directory: File,
        prefix: String,
        cancellation: PythonEnvironmentInstallCancellation,
    ): List<ArchiveSource> {
        val rootPath = directory.canonicalFile.toPath()
        return buildList {
            Files.walk(rootPath).use { paths ->
                paths.sorted().forEach { path ->
                    cancellation.throwIfCancellationRequested()
                    if (path == rootPath) return@forEach
                    require(!Files.isSymbolicLink(path)) { "Staged environment contains a symlink" }
                    if (Files.isDirectory(path)) return@forEach
                    require(Files.isRegularFile(path)) { "Staged environment contains a special file" }
                    val relative = rootPath.relativize(path).joinToString("/") { it.toString() }
                    add(ArchiveSource.Disk(prefix + relative, path.toFile(), Files.size(path)))
                }
            }
        }
    }

    private fun internalArchiveManifest(lock: PythonEnvironmentLock, imports: Set<String>): ByteArray =
        JSONObject()
            .put("schemaVersion", MANIFEST_SCHEMA_VERSION)
            .put("pluginId", lock.pluginId)
            .put("lockDigest", lock.lockDigest)
            .put("target", PythonEnvironmentLockCodec.targetJson(lock.target))
            .put("sourceSha256", lock.sourceSha256 ?: JSONObject.NULL)
            .put("wheels", JSONArray(lock.wheels.map { "${it.normalizedName}==${it.version}" }.sorted()))
            .put(
                "nativePackages",
                JSONArray(lock.nativePackages.map { "${it.normalizedName}==${it.version}" }.sorted()),
            )
            .put("importNames", JSONArray(imports.sorted()))
            .put("resolutionProof", RESOLUTION_PROOF_EXACT_ARTIFACTS)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

    private fun activate(installed: InstalledEnvironment) {
        val pointer = ActivePointer(
            pluginId = installed.pluginId,
            target = installed.target,
            lockDigest = installed.lockDigest,
            environmentDigest = installed.environmentDigest,
        )
        atomicWriter.write(activePointerFile(installed.pluginId), pointer.encode())
        val verified = readActive(installed.pluginId)
        require(sameIdentity(verified, installed)) { "Python environment activation did not verify" }
    }

    private fun readActive(pluginId: String): InstalledEnvironment? {
        val pointerFile = activePointerFile(pluginId)
        if (!pointerFile.isFile || Files.isSymbolicLink(pointerFile.toPath())) return null
        val pointer = runCatching { ActivePointer.decode(readBounded(pointerFile)) }.getOrNull() ?: return null
        if (pointer.pluginId != pluginId) return null
        val directory = File(
            File(File(versions, pointer.pluginId), pointer.target.directorySegment),
            pointer.lockDigest,
        )
        val installed = readInstalled(directory) ?: return null
        return installed.takeIf {
            it.pluginId == pointer.pluginId && it.target == pointer.target &&
                it.lockDigest == pointer.lockDigest && it.environmentDigest == pointer.environmentDigest
        }
    }

    private fun activePointers(): List<InstalledEnvironment> = active.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(ACTIVE_SUFFIX) && !it.name.startsWith(TEMP_PREFIX) }
        .mapNotNull { file ->
            val pluginId = file.name.removeSuffix(ACTIVE_SUFFIX)
            if (PythonEnvironmentContract.isPluginId(pluginId)) readActive(pluginId) else null
        }

    private fun effectiveActivePointers(): List<InstalledEnvironment> = active.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(ACTIVE_SUFFIX) && !it.name.startsWith(TEMP_PREFIX) }
        .mapNotNull { file ->
            val pluginId = file.name.removeSuffix(ACTIVE_SUFFIX)
            if (PythonEnvironmentContract.isPluginId(pluginId)) readEffectiveActive(pluginId) else null
        }

    private fun readEffectiveActive(pluginId: String): InstalledEnvironment? {
        val rawActive = readActive(pluginId)
        val quarantined = activationTransactions.values.singleOrNull {
            it.receipt.pluginId == pluginId &&
                it.state == ActivationTransactionState.COMMITTED &&
                !sameIdentity(it.installed, it.previous)
        } ?: return rawActive
        return when {
            sameIdentity(rawActive, quarantined.installed) -> quarantined.previous
            sameIdentity(rawActive, quarantined.previous) -> quarantined.previous
            else -> null
        }
    }

    private fun isQuarantinedEnvironment(environmentDigest: String): Boolean =
        activationTransactions.values.any {
            it.state == ActivationTransactionState.COMMITTED &&
                !sameIdentity(it.installed, it.previous) &&
                it.installed.environmentDigest == environmentDigest
        }

    private fun readInstalled(directory: File): InstalledEnvironment? = runCatching {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        val lockFile = File(directory, LOCK_FILE)
        val manifestFile = File(directory, MANIFEST_FILE)
        val archive = File(directory, ARCHIVE_FILE)
        require(listOf(lockFile, manifestFile, archive).all {
            it.isFile && !Files.isSymbolicLink(it.toPath())
        })
        val lock = PythonEnvironmentLockCodec.decode(readBounded(lockFile, PythonEnvironmentContract.MAX_LOCK_BYTES))
        val manifest = InstalledManifest.decode(readBounded(manifestFile))
        require(manifest.pluginId == lock.pluginId && manifest.target == lock.target)
        require(manifest.lockDigest == lock.lockDigest && directory.name == lock.lockDigest)
        require(manifest.environmentBytes == archive.length())
        require(manifest.environmentDigest == sha256(archive))
        require(manifest.environmentBytes in PythonRuntimeFdContract.MIN_ENVIRONMENT_BYTES..
            PythonRuntimeFdContract.MAX_ENVIRONMENT_BYTES)
        require(manifest.sourceSha256 == lock.sourceSha256)
        require(manifest.wheelPackages == lock.wheels.map { "${it.normalizedName}==${it.version}" }.sorted())
        require(manifest.nativePackages ==
            lock.nativePackages.map { "${it.normalizedName}==${it.version}" }.sorted())
        val expectedNativeModules = validateNativeCatalog(lock)
            .flatMap(PythonNativeCatalogEntry::allowedNativeModules)
            .sortedWith(
                compareBy(PythonAllowedNativeModule::module, PythonAllowedNativeModule::packagedName),
            )
        require(expectedNativeModules.distinct() == expectedNativeModules)
        require(manifest.allowedNativeModules == expectedNativeModules)
        InstalledEnvironment(lock, manifest, directory.canonicalFile, archive.canonicalFile)
    }.getOrNull()

    private fun corruptStatus(directory: File): PythonEnvironmentStatus? {
        val lockFile = File(directory, LOCK_FILE)
        val lock = runCatching {
            PythonEnvironmentLockCodec.decode(readBounded(lockFile, PythonEnvironmentContract.MAX_LOCK_BYTES))
        }.getOrNull() ?: return null
        return PythonEnvironmentStatus(
            pluginId = lock.pluginId,
            target = lock.target,
            state = PythonEnvironmentState.CORRUPT,
            lockDigest = lock.lockDigest,
            environmentDirectory = directory,
            wheelPackages = lock.wheels.map { "${it.normalizedName}==${it.version}" },
            nativeCatalogPackages = lock.nativePackages.map { "${it.normalizedName}==${it.version}" },
            detail = "installed_environment_failed_verification",
        )
    }

    private fun installedDirectories(): List<File> = versions.listFiles()
        .orEmpty()
        .filter { it.isDirectory && PythonEnvironmentContract.isPluginId(it.name) }
        .flatMap { plugin -> plugin.listFiles().orEmpty().filter { it.isDirectory } }
        .flatMap { target -> target.listFiles().orEmpty().filter { it.isDirectory } }
        .filter { PythonEnvironmentContract.isSha256(it.name) }
        .sortedBy { it.path }

    private fun environmentDirectory(lock: PythonEnvironmentLock): File =
        File(File(File(versions, lock.pluginId), lock.target.directorySegment), lock.lockDigest)

    private fun activePointerFile(pluginId: String) = File(active, pluginId + ACTIVE_SUFFIX)

    private fun activationJournalFile(transactionId: String) =
        File(activationJournal, transactionId + PythonEnvironmentActivationJournalCodec.FILE_SUFFIX)

    private fun recoverInterruptedTransactions() {
        staging.listFiles().orEmpty().forEach(::deleteTreeWithoutFollowingLinks)
        active.listFiles().orEmpty().filter { it.name.startsWith(TEMP_PREFIX) }.forEach { temporary ->
            require(temporary.isFile && !Files.isSymbolicLink(temporary.toPath())) {
                "Environment activation temporary is unsafe"
            }
            require(temporary.delete() || !temporary.exists()) {
                "Cannot remove environment activation temporary"
            }
        }
        activationJournal.listFiles().orEmpty()
            .filter { it.name.startsWith(TEMP_PREFIX) }
            .forEach { temporary ->
                require(temporary.isFile && !Files.isSymbolicLink(temporary.toPath())) {
                    "Environment activation journal temporary is unsafe"
                }
                require(temporary.delete() || !temporary.exists()) {
                    "Cannot remove environment activation journal temporary"
                }
            }
    }

    private fun recoverDurableActivationTransactions() {
        val files = activationJournal.listFiles().orEmpty()
            .filterNot { it.name.startsWith(TEMP_PREFIX) }
            .sortedBy(File::getName)
        require(files.size <= PythonEnvironmentLimits.MAX_INSTALLED_ENVIRONMENTS) {
            "Too many durable Python environment transactions"
        }
        val plugins = linkedSetOf<String>()
        files.forEach { journal ->
            require(journal.isFile && !Files.isSymbolicLink(journal.toPath())) {
                "Python environment activation journal is not a regular file"
            }
            require(journal.name.endsWith(PythonEnvironmentActivationJournalCodec.FILE_SUFFIX)) {
                "Unexpected Python environment activation journal file"
            }
            require(journal.length() in 1..PythonEnvironmentActivationJournalCodec.MAX_BYTES.toLong()) {
                "Python environment activation journal is outside its size limit"
            }
            val record = PythonEnvironmentActivationJournalCodec.decode(journal.readBytes())
            require(journal.name == record.transactionId +
                PythonEnvironmentActivationJournalCodec.FILE_SUFFIX) {
                "Python environment activation journal name does not match"
            }
            require(plugins.add(record.installed.pluginId)) {
                "Plugin has multiple durable Python environment transactions"
            }
            val installed = resolveInstalledIdentity(record.installed)
            val previous = record.previous?.let(::resolveInstalledIdentity)
            val rawActive = readActive(installed.pluginId)
            when (record.state) {
                PythonEnvironmentRecoveryState.PREPARED -> require(sameIdentity(rawActive, previous)) {
                    "Prepared Python environment journal has an unexpected active pointer"
                }
                PythonEnvironmentRecoveryState.COMMITTED -> require(
                    sameIdentity(rawActive, installed) || sameIdentity(rawActive, previous),
                ) { "Committed Python environment journal has an unexpected active pointer" }
            }
            val receipt = PythonEnvironmentInstallReceipt(
                transactionId = record.transactionId,
                pluginId = installed.pluginId,
                target = installed.target,
                lockDigest = installed.lockDigest,
                environmentDigest = installed.environmentDigest,
            )
            val pending = PendingActivation(
                receipt = receipt,
                installed = installed,
                previous = previous,
                newlyInstalled = record.newlyInstalled,
                state = record.state.toTransactionState(),
            )
            require(activationTransactions.putIfAbsent(receipt.transactionId, pending) == null) {
                "Duplicate durable Python environment transaction"
            }
        }
    }

    private fun resolveInstalledIdentity(
        identity: PythonEnvironmentActivationIdentity,
    ): InstalledEnvironment {
        val directory = File(
            File(File(versions, identity.pluginId), identity.target.directorySegment),
            identity.lockDigest,
        )
        val installed = readInstalled(directory)
            ?: error("Durable Python environment transaction references a missing environment")
        require(installed.identity() == identity) {
            "Durable Python environment identity does not match installed content"
        }
        return installed
    }

    private fun garbageCollectUnreferencedVersions() {
        val referenced = buildSet {
            activePointers().forEach { add(it.identity()) }
            activationTransactions.values.forEach {
                add(it.installed.identity())
                it.previous?.let { previous -> add(previous.identity()) }
            }
        }
        installedDirectories().forEach { directory ->
            val installed = readInstalled(directory) ?: return@forEach
            if (installed.identity() !in referenced) {
                archiveIndex.remove(installed.environmentDigest, installed.archive)
                nativeModuleIndex.remove(installed.environmentDigest, installed.manifest.allowedNativeModules)
                deleteTreeWithoutFollowingLinks(installed.directory)
                installed.directory.parentFile
                    ?.takeIf { it.list().orEmpty().isEmpty() }
                    ?.let { emptyTarget ->
                        require(emptyTarget.delete() || !emptyTarget.exists()) {
                            "Cannot remove empty Python environment target directory"
                        }
                    }
                installed.directory.parentFile?.parentFile
                    ?.takeIf { it.list().orEmpty().isEmpty() }
                    ?.let { emptyPlugin ->
                        require(emptyPlugin.delete() || !emptyPlugin.exists()) {
                            "Cannot remove empty Python environment plugin directory"
                        }
                    }
            }
        }
    }

    private fun rebuildArchiveIndex() {
        archiveIndex.clear()
        nativeModuleIndex.clear()
        installedDirectories().forEach { directory ->
            readInstalled(directory)?.let {
                archiveIndex[it.environmentDigest] = it.archive
                nativeModuleIndex[it.environmentDigest] = it.manifest.allowedNativeModules
            }
        }
    }

    private fun findAndIndexArchive(digest: String): File? {
        installedDirectories().forEach { directory ->
            val installed = readInstalled(directory)
            if (installed != null) {
                archiveIndex.putIfAbsent(installed.environmentDigest, installed.archive)
                nativeModuleIndex.putIfAbsent(
                    installed.environmentDigest,
                    installed.manifest.allowedNativeModules,
                )
                if (installed.environmentDigest == digest) return installed.archive
            }
        }
        return null
    }

    private fun requireRegularArchive(file: File, digest: String) {
        val canonical = file.canonicalFile
        require(canonical.isFile && !Files.isSymbolicLink(canonical.toPath())) {
            "Environment archive is not a regular file"
        }
        require(canonical.toPath().startsWith(root.toPath())) { "Environment archive escaped app storage" }
        require(canonical.length() in PythonRuntimeFdContract.MIN_ENVIRONMENT_BYTES..
            PythonRuntimeFdContract.MAX_ENVIRONMENT_BYTES) { "Environment archive is too large" }
        require(sha256(canonical) == digest) { "Environment archive digest changed" }
    }

    private fun enforcePreInstallQuota() {
        garbageCollectUnreferencedVersions()
        require(installedDirectories().size < PythonEnvironmentLimits.MAX_INSTALLED_ENVIRONMENTS) {
            "Python environment count quota exceeded"
        }
    }

    private fun enforceFinalQuota(transaction: File) {
        val existingBytes = installedDirectories().sumOf(::regularTreeBytes)
        val transactionBytes = regularTreeBytes(transaction)
        require(existingBytes + transactionBytes <= PythonEnvironmentLimits.MAX_INSTALLED_ENVIRONMENT_BYTES) {
            "Python environment storage quota exceeded"
        }
    }

    private fun checkedEnvironmentBytes(current: Long, additional: Long): Long =
        Math.addExact(current, additional).also {
            require(it <= PythonEnvironmentLimits.MAX_EXTRACTED_BYTES_PER_ENVIRONMENT) {
                "Python environment expands beyond its quota"
            }
        }

    private fun checkedEnvironmentFiles(current: Int, additional: Int): Int =
        Math.addExact(current, additional).also {
            require(it <= PythonEnvironmentLimits.MAX_FILES_PER_ENVIRONMENT) {
                "Python environment has too many files"
            }
        }

    private fun registerPreparedActivation(
        installed: InstalledEnvironment,
        previous: InstalledEnvironment?,
        newlyInstalled: Boolean,
    ): PythonEnvironmentInstallReceipt {
        val transactionId = "pyenv-${UUID.randomUUID().toString().replace("-", "")}".take(128)
        val receipt = PythonEnvironmentInstallReceipt(
            transactionId = transactionId,
            pluginId = installed.pluginId,
            target = installed.target,
            lockDigest = installed.lockDigest,
            environmentDigest = installed.environmentDigest,
        )
        val pending = PendingActivation(receipt, installed, previous, newlyInstalled)
        require(activationTransactions.values.none { it.receipt.pluginId == installed.pluginId }) {
            "Plugin already has a pending Python environment transaction"
        }
        try {
            writeActivationJournal(pending)
            require(activationTransactions.putIfAbsent(transactionId, pending) == null) {
                "Duplicate Python environment transaction"
            }
        } catch (failure: Throwable) {
            runCatching {
                val journal = activationJournalFile(transactionId)
                if (journal.exists()) removeJournalFile(journal)
            }.onFailure(failure::addSuppressed)
            if (newlyInstalled && !sameIdentity(readActive(installed.pluginId), installed)) {
                archiveIndex.remove(installed.environmentDigest, installed.archive)
                nativeModuleIndex.remove(
                    installed.environmentDigest,
                    installed.manifest.allowedNativeModules,
                )
                runCatching { deleteTreeWithoutFollowingLinks(installed.directory) }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        }
        return receipt
    }

    private fun pendingActivation(receipt: PythonEnvironmentInstallReceipt): PendingActivation {
        val pending = activationTransactions[receipt.transactionId]
            ?: error("Unknown or completed Python environment transaction")
        require(pending.receipt.sameIdentity(receipt)) {
            "Python environment receipt does not match its transaction"
        }
        return pending
    }

    private fun writeActivationJournal(pending: PendingActivation) {
        val file = activationJournalFile(pending.receipt.transactionId)
        require(!file.exists() && !Files.isSymbolicLink(file.toPath())) {
            "Python environment activation journal already exists"
        }
        atomicWriterForJournal(file, PythonEnvironmentActivationJournalCodec.encode(pending.toJournal()))
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "Python environment activation journal write did not verify"
        }
        val verified = PythonEnvironmentActivationJournalCodec.decode(file.readBytes())
        require(verified == pending.toJournal()) {
            "Python environment activation journal write changed identity"
        }
    }

    private fun persistActivationState(
        pending: PendingActivation,
        state: ActivationTransactionState,
    ) {
        val file = activationJournalFile(pending.receipt.transactionId)
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "Python environment activation journal is missing"
        }
        val replacement = pending.copy(state = state)
        atomicWriterForJournal(file, PythonEnvironmentActivationJournalCodec.encode(replacement.toJournal()))
        val verified = PythonEnvironmentActivationJournalCodec.decode(file.readBytes())
        require(verified == replacement.toJournal()) {
            "Python environment activation journal state did not verify"
        }
    }

    private fun removeActivationJournal(pending: PendingActivation) {
        val file = activationJournalFile(pending.receipt.transactionId)
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "Python environment activation journal is missing"
        }
        val verified = PythonEnvironmentActivationJournalCodec.decode(file.readBytes())
        require(verified == pending.toJournal()) {
            "Python environment activation journal changed before completion"
        }
        removeJournalFile(file)
    }

    private fun removeJournalFile(file: File) {
        val tombstone = File(
            activationJournal,
            "$TEMP_PREFIX${file.name}-completed-${UUID.randomUUID()}",
        )
        moveAtomically(file, tombstone)
        require(tombstone.delete() || !tombstone.exists()) {
            "Cannot remove completed Python environment activation journal"
        }
    }

    private fun restorePreviousPointer(pending: PendingActivation) {
        if (pending.previous == null) {
            val pointer = activePointerFile(pending.receipt.pluginId)
            require(!Files.isSymbolicLink(pointer.toPath())) {
                "Python environment activation pointer is a symlink"
            }
            require(pointer.delete() || !pointer.exists()) {
                "Cannot remove Python environment activation"
            }
            require(readActive(pending.receipt.pluginId) == null) {
                "Python environment rollback did not verify"
            }
        } else {
            activate(pending.previous)
        }
    }

    private fun sameIdentity(first: InstalledEnvironment?, second: InstalledEnvironment?): Boolean =
        if (first == null || second == null) first == null && second == null else
            first.pluginId == second.pluginId &&
                first.target == second.target &&
                first.lockDigest == second.lockDigest &&
                first.environmentDigest == second.environmentDigest

    private fun PythonEnvironmentInstallReceipt.sameIdentity(
        other: PythonEnvironmentInstallReceipt,
    ): Boolean = transactionId == other.transactionId &&
        pluginId == other.pluginId &&
        target == other.target &&
        lockDigest == other.lockDigest &&
        environmentDigest == other.environmentDigest

    private fun atomicWriterForJournal(target: File, bytes: ByteArray) {
        require(target.parentFile?.canonicalFile == activationJournal.canonicalFile) {
            "Python environment activation journal escaped its root"
        }
        require(!Files.isSymbolicLink(target.toPath())) {
            "Python environment activation journal is a symlink"
        }
        writeAtomicFile(target, bytes)
    }

    private fun discardPreparedIfExclusive(pending: PendingActivation) {
        if (!pending.newlyInstalled) return
        val stillReferenced = activationTransactions.values.any {
            sameIdentity(it.installed, pending.installed)
        }
        val stillActive = sameIdentity(readActive(pending.installed.pluginId), pending.installed)
        if (stillReferenced || stillActive) return
        archiveIndex.remove(pending.installed.environmentDigest, pending.installed.archive)
        nativeModuleIndex.remove(
            pending.installed.environmentDigest,
            pending.installed.manifest.allowedNativeModules,
        )
        deleteTreeWithoutFollowingLinks(pending.installed.directory)
        pending.installed.directory.parentFile?.takeIf { it.list().orEmpty().isEmpty() }?.delete()
    }

    private enum class ActivationTransactionState { PREPARED, COMMITTED }

    private fun PythonEnvironmentRecoveryState.toTransactionState(): ActivationTransactionState =
        when (this) {
            PythonEnvironmentRecoveryState.PREPARED -> ActivationTransactionState.PREPARED
            PythonEnvironmentRecoveryState.COMMITTED -> ActivationTransactionState.COMMITTED
        }

    private data class PendingActivation(
        val receipt: PythonEnvironmentInstallReceipt,
        val installed: InstalledEnvironment,
        val previous: InstalledEnvironment?,
        val newlyInstalled: Boolean,
        var state: ActivationTransactionState = ActivationTransactionState.PREPARED,
    ) {
        fun toJournal() = PythonEnvironmentActivationJournalRecord(
            transactionId = receipt.transactionId,
            state = when (state) {
                ActivationTransactionState.PREPARED -> PythonEnvironmentRecoveryState.PREPARED
                ActivationTransactionState.COMMITTED -> PythonEnvironmentRecoveryState.COMMITTED
            },
            installed = installed.identity(),
            previous = previous?.identity(),
            newlyInstalled = newlyInstalled,
        )

        fun toRecoveryDescriptor() = PythonEnvironmentRecoveryDescriptor(
            receipt = receipt,
            state = when (state) {
                ActivationTransactionState.PREPARED -> PythonEnvironmentRecoveryState.PREPARED
                ActivationTransactionState.COMMITTED -> PythonEnvironmentRecoveryState.COMMITTED
            },
            installed = installed.identity(),
            previous = previous?.identity(),
            newlyInstalled = newlyInstalled,
        )
    }

    private data class InstalledEnvironment(
        val lock: PythonEnvironmentLock,
        val manifest: InstalledManifest,
        val directory: File,
        val archive: File,
    ) {
        val pluginId get() = lock.pluginId
        val target get() = lock.target
        val lockDigest get() = lock.lockDigest
        val environmentDigest get() = manifest.environmentDigest

        fun identity() = PythonEnvironmentActivationIdentity(
            pluginId = pluginId,
            target = target,
            lockDigest = lockDigest,
            environmentDigest = environmentDigest,
        )

        fun toStatus(state: PythonEnvironmentState) = PythonEnvironmentStatus(
            pluginId = pluginId,
            target = target,
            state = state,
            lockDigest = lockDigest,
            environmentDirectory = directory,
            wheelPackages = manifest.wheelPackages,
            nativeCatalogPackages = manifest.nativePackages,
            detail = manifest.resolutionProof,
        )
    }

    private data class InstalledManifest(
        val pluginId: String,
        val target: PythonEnvironmentTarget,
        val lockDigest: String,
        val environmentDigest: String,
        val environmentBytes: Long,
        val sourceSha256: String?,
        val wheelPackages: List<String>,
        val nativePackages: List<String>,
        val allowedNativeModules: List<PythonAllowedNativeModule>,
        val importNames: Set<String>,
        val resolutionProof: String,
    ) {
        fun encode(): String = JSONObject()
            .put("schemaVersion", MANIFEST_SCHEMA_VERSION)
            .put("pluginId", pluginId)
            .put("target", PythonEnvironmentLockCodec.targetJson(target))
            .put("lockDigest", lockDigest)
            .put("environmentDigest", environmentDigest)
            .put("environmentBytes", environmentBytes)
            .put("sourceSha256", sourceSha256 ?: JSONObject.NULL)
            .put("wheelPackages", JSONArray(wheelPackages.sorted()))
            .put("nativePackages", JSONArray(nativePackages.sorted()))
            .put(
                "allowedNativeModules",
                JSONArray(allowedNativeModules.map { module ->
                    JSONObject()
                        .put("module", module.module)
                        .put("packagedName", module.packagedName)
                }),
            )
            .put("importNames", JSONArray(importNames.sorted()))
            .put("resolutionProof", resolutionProof)
            .toString()

        companion object {
            fun decode(raw: String): InstalledManifest {
                val json = JsonContract.parseObject(raw, PythonEnvironmentLimits.MAX_MANIFEST_BYTES)
                JsonContract.requireOnlyKeys(
                    json,
                    setOf(
                        "schemaVersion", "pluginId", "target", "lockDigest", "environmentDigest",
                        "environmentBytes", "sourceSha256", "wheelPackages", "nativePackages",
                        "allowedNativeModules", "importNames", "resolutionProof",
                    ),
                    "Python environment manifest",
                )
                require(json.requiredInt("schemaVersion") == MANIFEST_SCHEMA_VERSION)
                val targetJson = JsonContract.requiredObject(json, "target")
                JsonContract.requireOnlyKeys(
                    targetJson,
                    setOf("pythonVersion", "interpreterTag", "androidAbi", "minimumAndroidApi"),
                    "Python environment target",
                )
                return InstalledManifest(
                    pluginId = JsonContract.requiredString(json, "pluginId", 128),
                    target = PythonEnvironmentTarget(
                        pythonVersion = JsonContract.requiredString(targetJson, "pythonVersion", 32),
                        interpreterTag = JsonContract.requiredString(targetJson, "interpreterTag", 16),
                        androidAbi = JsonContract.requiredString(targetJson, "androidAbi", 32),
                        minimumAndroidApi = targetJson.requiredInt("minimumAndroidApi"),
                    ),
                    lockDigest = JsonContract.requiredString(json, "lockDigest", 64),
                    environmentDigest = JsonContract.requiredString(json, "environmentDigest", 64),
                    environmentBytes = JsonContract.requiredLong(json, "environmentBytes"),
                    sourceSha256 = JsonContract.optionalString(json, "sourceSha256", 64),
                    wheelPackages = stringArray(json, "wheelPackages"),
                    nativePackages = stringArray(json, "nativePackages"),
                    allowedNativeModules = if (json.has("allowedNativeModules")) {
                        nativeModuleArray(json)
                    } else {
                        emptyList()
                    },
                    importNames = stringArray(json, "importNames").toSet(),
                    resolutionProof = JsonContract.requiredString(json, "resolutionProof", 96),
                ).also {
                    require(PythonEnvironmentContract.isPluginId(it.pluginId))
                    require(PythonEnvironmentContract.isSha256(it.lockDigest))
                    require(PythonEnvironmentContract.isSha256(it.environmentDigest))
                    require(it.sourceSha256 == null || PythonEnvironmentContract.isSha256(it.sourceSha256))
                    require(it.resolutionProof == RESOLUTION_PROOF_EXACT_ARTIFACTS)
                }
            }

            private fun stringArray(json: JSONObject, name: String): List<String> {
                val array = JsonContract.requiredArray(json, name)
                require(array.length() <= PythonEnvironmentLimits.MAX_FILES_PER_ENVIRONMENT)
                return List(array.length()) { index ->
                    (array.opt(index) as? String)?.takeIf { it.length <= 256 }
                        ?: error("Invalid $name entry")
                }
            }

            private fun nativeModuleArray(json: JSONObject): List<PythonAllowedNativeModule> {
                val array = JsonContract.requiredArray(json, "allowedNativeModules")
                require(array.length() <= PythonRuntimeFdContract.MAX_ALLOWED_NATIVE_MODULES)
                return List(array.length()) { index ->
                    val value = array.optJSONObject(index)
                        ?: error("Invalid allowed native module entry")
                    JsonContract.requireOnlyKeys(
                        value,
                        setOf("module", "packagedName"),
                        "Installed allowed native module",
                    )
                    PythonAllowedNativeModule(
                        module = JsonContract.requiredString(value, "module", 128),
                        packagedName = JsonContract.requiredString(value, "packagedName", 256),
                    )
                }.also { modules ->
                    require(modules.distinct() == modules)
                    require(modules == modules.sortedWith(
                        compareBy(PythonAllowedNativeModule::module, PythonAllowedNativeModule::packagedName),
                    ))
                }
            }
        }
    }

    private data class ActivePointer(
        val pluginId: String,
        val target: PythonEnvironmentTarget,
        val lockDigest: String,
        val environmentDigest: String,
    ) {
        fun encode(): ByteArray = JSONObject()
            .put("schemaVersion", ACTIVE_SCHEMA_VERSION)
            .put("pluginId", pluginId)
            .put("target", PythonEnvironmentLockCodec.targetJson(target))
            .put("lockDigest", lockDigest)
            .put("environmentDigest", environmentDigest)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        companion object {
            fun decode(raw: String): ActivePointer {
                val json = JsonContract.parseObject(raw, PythonEnvironmentLimits.MAX_MANIFEST_BYTES)
                JsonContract.requireOnlyKeys(
                    json,
                    setOf("schemaVersion", "pluginId", "target", "lockDigest", "environmentDigest"),
                    "Python environment activation",
                )
                require(json.requiredInt("schemaVersion") == ACTIVE_SCHEMA_VERSION)
                val target = JsonContract.requiredObject(json, "target")
                return ActivePointer(
                    pluginId = JsonContract.requiredString(json, "pluginId", 128),
                    target = PythonEnvironmentTarget(
                        pythonVersion = JsonContract.requiredString(target, "pythonVersion", 32),
                        interpreterTag = JsonContract.requiredString(target, "interpreterTag", 16),
                        androidAbi = JsonContract.requiredString(target, "androidAbi", 32),
                        minimumAndroidApi = target.requiredInt("minimumAndroidApi"),
                    ),
                    lockDigest = JsonContract.requiredString(json, "lockDigest", 64),
                    environmentDigest = JsonContract.requiredString(json, "environmentDigest", 64),
                ).also {
                    require(PythonEnvironmentContract.isPluginId(it.pluginId))
                    require(PythonEnvironmentContract.isSha256(it.lockDigest))
                    require(PythonEnvironmentContract.isSha256(it.environmentDigest))
                }
            }
        }
    }

    private sealed class ArchiveSource(val path: String, val size: Long) {
        abstract fun open(): java.io.InputStream

        fun crc32(cancellation: PythonEnvironmentInstallCancellation): Long {
            val crc = CRC32()
            open().use { input ->
                val buffer = ByteArray(64 * 1024)
                var readTotal = 0L
                while (true) {
                    cancellation.throwIfCancellationRequested()
                    val read = input.read(buffer)
                    if (read < 0) break
                    readTotal += read
                    require(readTotal <= size) { "Archive input changed while hashing" }
                    crc.update(buffer, 0, read)
                }
                require(readTotal == size) { "Archive input changed while hashing" }
            }
            return crc.value
        }

        fun copyTo(output: java.io.OutputStream, cancellation: PythonEnvironmentInstallCancellation) {
            open().use { input ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    cancellation.throwIfCancellationRequested()
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    require(copied <= size) { "Archive input changed while writing" }
                    output.write(buffer, 0, read)
                }
                require(copied == size) { "Archive input changed while writing" }
            }
        }

        class Disk(path: String, private val file: File, size: Long) : ArchiveSource(path, size) {
            override fun open() = BufferedInputStream(FileInputStream(file))
        }

        class Bytes(path: String, private val bytes: ByteArray) : ArchiveSource(path, bytes.size.toLong()) {
            override fun open() = ByteArrayInputStream(bytes)
        }
    }

    private data class SourceEntry(val relativePath: String, val file: File, val bytes: Long)
    private data class SourceCopyReceipt(val files: Int, val bytes: Long, val digest: String)
    private data class PendingEnvironmentArchive(
        val archive: File,
        val allowedNativeModules: List<PythonAllowedNativeModule>,
    )

    companion object {
        private const val MANIFEST_SCHEMA_VERSION = 1
        private const val ACTIVE_SCHEMA_VERSION = 1
        private const val LOCK_FILE = "environment.lock.json"
        private const val MANIFEST_FILE = "manifest.json"
        private const val ARCHIVE_FILE = "environment.pyz"
        private const val INTERNAL_MANIFEST = "__hans_environment__.json"
        private const val PLUGIN_SOURCE_PREFIX = "__hans_plugin_source__"
        private const val ACTIVE_SUFFIX = ".json"
        private const val TEMP_PREFIX = ".tmp-"
        private const val DETERMINISTIC_ZIP_TIME_MILLIS = 315_532_800_000L
        const val RESOLUTION_PROOF_EXACT_ARTIFACTS =
            "exact_artifacts_verified_dependency_closure_unverified"
        private val DANGEROUS_SOURCE_SUFFIXES = setOf(
            ".so", ".dex", ".jar", ".apk", ".class", ".dylib", ".dll", ".exe",
        )

        private fun writeAtomicFile(target: File, bytes: ByteArray) {
            require(target.parentFile?.let { it.mkdirs() || it.isDirectory } == true) {
                "Cannot create atomic-file parent"
            }
            val temporary = File(target.parentFile, "$TEMP_PREFIX${target.name}-${UUID.randomUUID()}")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                moveAtomically(temporary, target, replace = true)
            } finally {
                temporary.delete()
            }
        }

        private fun writeDurableFile(target: File, text: String) {
            require(!target.exists()) { "Durable environment file already exists" }
            FileOutputStream(target).use { output ->
                output.write(text.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
        }

        private fun moveAtomically(source: File, target: File, replace: Boolean = false) {
            val options = buildList {
                add(StandardCopyOption.ATOMIC_MOVE)
                if (replace) add(StandardCopyOption.REPLACE_EXISTING)
            }.toTypedArray()
            try {
                Files.move(source.toPath(), target.toPath(), *options)
            } catch (_: AtomicMoveNotSupportedException) {
                val fallback = if (replace) {
                    arrayOf(StandardCopyOption.REPLACE_EXISTING)
                } else {
                    emptyArray()
                }
                Files.move(source.toPath(), target.toPath(), *fallback)
            }
        }

        private fun makeTreeImmutable(root: File) {
            Files.walk(root.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    val file = path.toFile()
                    require(!Files.isSymbolicLink(path)) { "Immutable environment contains a symlink" }
                    require(file.setReadable(true, true)) { "Cannot make environment readable" }
                    require(file.setWritable(false, false)) { "Cannot make environment immutable" }
                    file.setExecutable(Files.isDirectory(path), true)
                }
            }
        }

        private fun deleteTreeWithoutFollowingLinks(root: File) {
            if (!root.exists() && !Files.isSymbolicLink(root.toPath())) return
            if (Files.isDirectory(root.toPath()) && !Files.isSymbolicLink(root.toPath())) {
                root.setWritable(true, true)
                root.listFiles().orEmpty().forEach(::deleteTreeWithoutFollowingLinks)
            }
            root.setWritable(true, true)
            require(root.delete() || !root.exists()) { "Cannot remove environment staging data" }
        }

        private fun requireDirectoryWithoutSymlink(directory: File) {
            require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
                "Environment storage contains a symlink"
            }
        }

        private fun resolveBelow(root: File, relativePath: String): File {
            validateRelativeArchivePath(relativePath)
            val target = File(root, relativePath).canonicalFile
            require(target.path.startsWith(root.canonicalPath + File.separator)) {
                "Environment path escapes its root"
            }
            return target
        }

        private fun validateRelativeArchivePath(path: String) {
            require(path.isNotBlank() && !path.startsWith('/') && !path.startsWith('\\')) {
                "Environment path is absolute"
            }
            require(path.toByteArray(StandardCharsets.UTF_8).size <=
                PythonEnvironmentLimits.MAX_ARCHIVE_PATH_BYTES) { "Environment path is too long" }
            require('\\' !in path && '\u0000' !in path && ':' !in path) { "Environment path is unsafe" }
            require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) {
                "Environment path traversal is forbidden"
            }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(FileInputStream(file)).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
        }

        private fun readBounded(file: File, maximumBytes: Int = PythonEnvironmentLimits.MAX_MANIFEST_BYTES): String {
            require(file.length() in 1..maximumBytes.toLong()) { "Environment metadata is too large" }
            return file.readText(StandardCharsets.UTF_8)
        }

        private fun regularTreeBytes(root: File): Long {
            if (!root.exists()) return 0
            var bytes = 0L
            Files.walk(root.toPath()).use { paths ->
                paths.forEach { path ->
                    require(!Files.isSymbolicLink(path)) { "Environment tree contains a symlink" }
                    if (Files.isRegularFile(path)) bytes = Math.addExact(bytes, Files.size(path))
                }
            }
            return bytes
        }

        private fun JSONObject.requiredInt(name: String): Int {
            require(has(name) && opt(name) is Number) { "Missing integer: $name" }
            val value = getLong(name)
            require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Integer out of bounds: $name" }
            return value.toInt()
        }
    }
}

/** Canonical source-tree digest used by plugin authors when creating an exact lock. */
object PythonEnvironmentSourceDigest {
    val EMPTY: String = PythonEnvironmentContract.sha256(ByteArray(0))

    fun digest(sourceDirectory: File): String {
        require(sourceDirectory.isDirectory && !Files.isSymbolicLink(sourceDirectory.toPath())) {
            "Plugin source root is invalid"
        }
        val root = sourceDirectory.canonicalFile.toPath()
        val entries = buildList {
            Files.walk(root).use { paths ->
                paths.sorted().forEach { path ->
                    if (path == root) return@forEach
                    require(!Files.isSymbolicLink(path)) { "Plugin source symlinks are forbidden" }
                    if (Files.isRegularFile(path)) {
                        val relative = root.relativize(path).joinToString("/") { it.toString() }
                        add(SourceDigestEntry(relative, path.toFile(), Files.size(path)))
                    } else {
                        require(Files.isDirectory(path)) { "Plugin source contains a special file" }
                    }
                }
            }
        }
        val views = entries.map { SourceDigestView(it.path, it.file, it.size) }
        val digest = MessageDigest.getInstance("SHA-256")
        views.sortedBy { it.path }.forEach { entry ->
            val path = entry.path.toByteArray(StandardCharsets.UTF_8)
            digest.update(path)
            digest.update(0.toByte())
            digest.update(entry.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(0.toByte())
            BufferedInputStream(FileInputStream(entry.file)).use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= entry.size) { "Plugin source changed while hashing" }
                    digest.update(buffer, 0, read)
                }
                require(total == entry.size) { "Plugin source changed while hashing" }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private data class SourceDigestEntry(val path: String, val file: File, val size: Long)
    private data class SourceDigestView(val path: String, val file: File, val size: Long)
}

/** Store-local deterministic empty archive; it has the runtime's pinned baseline digest. */
private class StoreBaselineEnvironmentArchiveProvider(rootDirectory: File) :
    PythonEnvironmentArchiveProvider {
    private val archive = File(rootDirectory, "baseline/environment.pyz")

    override fun open(environmentDigest: String): PythonEnvironmentArchiveReceipt {
        require(environmentDigest == PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST) {
            "Unknown baseline environment"
        }
        synchronized(this) {
            if (!archive.isFile || archive.length() != EMPTY_ZIP.size.toLong() ||
                PythonEnvironmentContract.sha256(archive.readBytes()) != environmentDigest
            ) {
                require(archive.parentFile?.let { it.mkdirs() || it.isDirectory } == true) {
                    "Cannot create baseline environment"
                }
                val staging = File(archive.parentFile, ".baseline-${UUID.randomUUID()}")
                try {
                    FileOutputStream(staging).use { output ->
                        output.write(EMPTY_ZIP)
                        output.fd.sync()
                    }
                    Files.move(
                        staging.toPath(),
                        archive.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    staging.delete()
                }
            }
        }
        return PythonEnvironmentArchiveReceipt(archive.canonicalFile, environmentDigest, archive.length())
    }

    private companion object {
        val EMPTY_ZIP = byteArrayOf(
            0x50, 0x4b, 0x05, 0x06,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
    }
}
