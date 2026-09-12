package ai.hans.standard.plugins

import ai.hans.standard.plugins.install.PluginDependencyRecoveryDescriptor
import ai.hans.standard.plugins.install.PluginDependencyRecoveryKind
import ai.hans.standard.plugins.install.PluginInstallJournalPhase
import ai.hans.standard.plugins.install.PluginInstallLocalRecoverability
import ai.hans.standard.runtime.python.PythonEnvironmentContract
import ai.hans.standard.runtime.python.PythonEnvironmentInstallCancellation
import ai.hans.standard.runtime.python.PythonEnvironmentInstallReceipt
import ai.hans.standard.runtime.python.PythonEnvironmentInstallRequest
import ai.hans.standard.runtime.python.PythonEnvironmentLock
import ai.hans.standard.runtime.python.PythonEnvironmentLockCodec
import ai.hans.standard.runtime.python.PythonEnvironmentResolutionRequest
import ai.hans.standard.runtime.python.PythonEnvironmentRecoveryDescriptor
import ai.hans.standard.runtime.python.PythonEnvironmentRecoveryState
import ai.hans.standard.runtime.python.PythonEnvironmentSourceDigest
import ai.hans.standard.runtime.python.PythonEnvironmentStore
import ai.hans.standard.runtime.python.PythonEnvironmentTarget
import ai.hans.standard.runtime.python.PythonOfflineWheelSet
import ai.hans.standard.runtime.python.PythonPluginEntrypointActivation
import ai.hans.standard.runtime.python.PythonPluginEntrypointActivationReceipt
import ai.hans.standard.runtime.python.PythonPluginEntrypointDeclaration
import ai.hans.standard.runtime.python.PythonPluginEntrypointFinalizedProof
import ai.hans.standard.runtime.python.PythonPluginEntrypointRegistry
import ai.hans.standard.runtime.python.PythonPluginEntrypointRecoveryDescriptor
import ai.hans.standard.runtime.python.PythonPluginEntrypointRecoveryState
import ai.hans.standard.runtime.python.PythonRuntimeContract
import ai.hans.standard.runtime.python.PythonWheelDownloadReceipt
import ai.hans.standard.runtime.python.PythonWheelPin
import ai.hans.standard.runtime.python.resolver.CancellablePythonEnvironmentResolver
import ai.hans.standard.runtime.python.resolver.PythonResolutionCancellation
import java.io.Closeable
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal data class PythonPluginEntrypointBinding(
    val requirementId: String,
    val relativePath: String,
    val callableName: String?,
) {
    init {
        require(requirementId.matches(Regex("[a-z][a-z0-9._-]{0,63}")))
        require(relativePath.endsWith(".py") && !relativePath.startsWith('/') && '\\' !in relativePath)
        require(relativePath.split('/').none { it.isBlank() || it == "." || it == ".." })
        callableName?.let {
            require(it.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) {
                "Python plugin callables must be public"
            }
        }
    }
}

internal fun interface PythonPluginEntrypointProber {
    /** Executes in the isolated worker without capabilities and never invokes the callable. */
    fun prove(
        pluginId: String,
        environmentDigest: String,
        bindings: List<PythonPluginEntrypointBinding>,
        cancellation: PluginRuntimePreparationCancellation,
    ): Set<String>
}

internal fun interface CancellablePythonWheelDownloader {
    fun download(
        pin: PythonWheelPin,
        destination: File,
        cancellation: PythonResolutionCancellation,
    ): PythonWheelDownloadReceipt
}

/**
 * Prepares one immutable pure-Python plugin environment without pip, a shell, a build backend, or
 * writable executable code. Network resolution and downloads happen in the app process; semantic
 * resolution and import/entrypoint proofs happen in the isolated CPython worker.
 */
internal class PythonPluginRuntimeDependencyPreparer(
    stagingRoot: File,
    private val target: PythonEnvironmentTarget,
    private val resolver: CancellablePythonEnvironmentResolver,
    private val downloader: CancellablePythonWheelDownloader,
    private val environments: PythonEnvironmentStore,
    private val entrypoints: PythonPluginEntrypointProber,
    private val entrypointRegistry: PythonPluginEntrypointRegistry,
) : PluginRuntimeDependencyPreparer,
    PluginRuntimeDependencyRemoval,
    PluginRuntimeDependencyRecoveryProvider {
    private val staging = stagingRoot.canonicalFile

    init {
        require(!Files.isSymbolicLink(stagingRoot.toPath())) { "Python download staging is a symlink" }
        require(staging.mkdirs() || staging.isDirectory) { "Cannot create Python download staging" }
        require(!Files.isSymbolicLink(staging.toPath())) { "Python download staging is a symlink" }
    }

    override fun prepare(
        requirements: PluginRuntimeRequirements,
        sourceRoot: File,
        cancellation: PluginRuntimePreparationCancellation,
    ): PluginRuntimeDependencyTransaction {
        val pythonRuntimeIds = requirements.runtimes
            .filter { it.kind == PluginRuntimeKind.EMBEDDED_PYTHON }
            .mapTo(linkedSetOf(), PluginRuntimeRequirement::id)
        val requestedEntrypoints = requirements.entrypoints.filter {
            it.runtimeRequirementId in pythonRuntimeIds
        }
        if (pythonRuntimeIds.isEmpty()) return EmptyPluginRuntimeDependencyTransaction
        require(requestedEntrypoints.all { it.kind == PluginEntrypointKind.PYTHON_CALLABLE }) {
            "Embedded Python entrypoints must use python_callable"
        }

        val source = File(sourceRoot, PYTHON_SOURCE_DIRECTORY)
        val bindings = resolveBindings(source, requestedEntrypoints)
        val needsSource = requestedEntrypoints.isNotEmpty()
        if (needsSource) requireSecureDirectory(source, sourceRoot)
        val sourceDigest = if (needsSource) PythonEnvironmentSourceDigest.digest(source) else null
        val requested = loadRequirements(sourceRoot, requirements.pluginId)
        require(!requested.allowPrereleases) {
            "Global Python prerelease mode is unsupported; use an explicit PEP 440 requirement"
        }
        cancellation.throwIfCancellationRequested()
        val resolutionCancellation = ResolutionCancellationAdapter(cancellation)
        val resolved = if (requested.requirements.isEmpty()) {
            PythonEnvironmentLock(
                schemaVersion = PythonEnvironmentContract.LOCK_SCHEMA_VERSION,
                pluginId = requirements.pluginId,
                target = target,
                wheels = emptyList(),
                sourceSha256 = sourceDigest,
            )
        } else {
            resolver.resolve(
                PythonEnvironmentResolutionRequest(
                    pluginId = requirements.pluginId,
                    requirements = requested.requirements,
                    target = target,
                ),
                resolutionCancellation,
            ).withSourceDigest(sourceDigest)
        }
        require(resolved.pluginId == requirements.pluginId && resolved.target == target) {
            "Python resolver returned another plugin target"
        }
        verifyOptionalAuthorLock(sourceRoot, resolved)
        cancellation.throwIfCancellationRequested()

        val transactionDirectory = File(staging, ".prepare-${UUID.randomUUID()}")
        require(transactionDirectory.mkdir()) { "Cannot create Python download transaction" }
        var receipt: PythonEnvironmentInstallReceipt? = null
        try {
            val artifacts = linkedMapOf<String, File>()
            resolved.wheels.sortedBy(PythonWheelPin::normalizedName).forEachIndexed { index, pin ->
                cancellation.throwIfCancellationRequested()
                val destination = File(transactionDirectory, "${index.toString().padStart(3, '0')}-${pin.fileName}")
                val download = downloader.download(pin, destination, resolutionCancellation)
                require(
                    download.sha256 == pin.sha256 && download.sizeBytes == pin.sizeBytes &&
                        destination.isFile && destination.length() == pin.sizeBytes,
                ) { "Python wheel download receipt did not match its lock" }
                artifacts[pin.sha256] = destination
            }
            cancellation.throwIfCancellationRequested()
            receipt = environments.prepareInstall(
                PythonEnvironmentInstallRequest(
                    lock = resolved,
                    offlineWheels = PythonOfflineWheelSet.of(artifacts),
                    pluginSourceDirectory = source.takeIf { needsSource },
                ),
                InstallCancellationAdapter(cancellation),
            )
            val proven = entrypoints.prove(
                pluginId = requirements.pluginId,
                environmentDigest = receipt.environmentDigest,
                bindings = bindings,
                cancellation = cancellation,
            )
            require(proven.all { id -> bindings.any { it.requirementId == id } }) {
                "Python entrypoint proof returned an undeclared id"
            }
            val callableBindings = bindings.filter { binding ->
                binding.requirementId in proven && binding.callableName != null
            }
            val registryReceipt = if (callableBindings.isEmpty()) {
                null
            } else {
                val exactSourceDigest = checkNotNull(sourceDigest) {
                    "Python callable proof has no source identity"
                }
                entrypointRegistry.prepareActivation(
                    PythonPluginEntrypointActivation(
                        pluginId = requirements.pluginId,
                        environmentDigest = receipt.environmentDigest,
                        sourceSha256 = exactSourceDigest,
                        declarations = callableBindings.map { binding ->
                            PythonPluginEntrypointDeclaration(
                                entrypointId = binding.requirementId,
                                relativePath = binding.relativePath,
                                function = checkNotNull(binding.callableName),
                                sourceSha256 = exactSourceDigest,
                                declaredCapabilities = requirements.capabilities
                                    .mapTo(linkedSetOf(), PluginCapabilityRequirement::id),
                            )
                        },
                        provenEntrypointIds = callableBindings
                            .mapTo(linkedSetOf(), PythonPluginEntrypointBinding::requirementId),
                    ),
                )
            }
            return PythonEnvironmentPluginTransaction(
                environments = environments,
                environmentReceipt = receipt,
                entrypointRegistry = entrypointRegistry,
                registryReceipt = registryReceipt,
                activeSourceSha256 = sourceDigest,
                resolvedEntrypointIds = proven,
            )
        } catch (failure: Throwable) {
            receipt?.let { prepared ->
                runCatching { environments.rollbackActivation(prepared) }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        } finally {
            deleteTree(transactionDirectory)
        }
    }

    override fun removeCommitted(
        pluginId: String,
        expectedEnvironmentDigest: String,
    ): Boolean {
        require(environments.digestFor(pluginId) == expectedEnvironmentDigest) {
            "Python plugin environment changed before entrypoint removal"
        }
        return entrypointRegistry.removeCommitted(pluginId, expectedEnvironmentDigest)
    }

    /**
     * Reconstructs only an exact pair of environment/entrypoint receipts. A missing, additional,
     * or cross-wired receipt is never guessed from the currently active environment.
     */
    override fun recover(
        pluginId: String,
        descriptor: PluginDependencyRecoveryDescriptor?,
        journalPhase: PluginInstallJournalPhase,
    ): PluginRuntimeDependencyRecoveryResult {
        if (!PythonEnvironmentContract.isPluginId(pluginId)) {
            return recoveryWithoutTransaction(PluginInstallLocalRecoverability.CHANGED)
        }
        val snapshot = try {
            RecoverySnapshot(
                environments = environments.recoveryDescriptors(),
                entrypoints = entrypointRegistry.recoveryDescriptors(),
                activeEnvironmentDigest = environments.digestFor(pluginId),
            )
        } catch (_: Exception) {
            return recoveryWithoutTransaction(PluginInstallLocalRecoverability.UNAVAILABLE)
        }
        val environmentCandidates = snapshot.environments.filter {
            it.receipt.pluginId == pluginId
        }
        val entrypointCandidates = snapshot.entrypoints.filter {
            it.receipt.pluginId == pluginId
        }
        if (descriptor == null) {
            val recoverability = if (
                environmentCandidates.isEmpty() &&
                entrypointCandidates.isEmpty() &&
                snapshot.activeEnvironmentDigest == PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST
            ) {
                PluginInstallLocalRecoverability.ABSENT
            } else {
                PluginInstallLocalRecoverability.CHANGED
            }
            return recoveryWithoutTransaction(recoverability)
        }
        if (descriptor.kind != PluginDependencyRecoveryKind.PYTHON_ENVIRONMENT_V1) {
            return recoveryWithoutTransaction(PluginInstallLocalRecoverability.CHANGED)
        }
        if (environmentCandidates.isEmpty()) {
            if (entrypointCandidates.isNotEmpty()) {
                return recoveryWithoutTransaction(PluginInstallLocalRecoverability.CHANGED)
            }
            if (snapshot.activeEnvironmentDigest == descriptor.environmentDigest &&
                journalPhase == PluginInstallJournalPhase.FINALIZED
            ) {
                return when (
                    entrypointRegistry.proveFinalizedActivation(
                        pluginId = pluginId,
                        environmentDigest = descriptor.environmentDigest,
                        metadataDigest = descriptor.entrypointMetadataDigest,
                    )
                ) {
                    PythonPluginEntrypointFinalizedProof.EXACT ->
                        PluginRuntimeDependencyRecoveryResult.recoverable(
                            recoverability = PluginInstallLocalRecoverability.COMMITTED,
                            transaction = FinalizedPythonPluginTransaction(descriptor),
                        )
                    PythonPluginEntrypointFinalizedProof.CHANGED ->
                        recoveryWithoutTransaction(PluginInstallLocalRecoverability.CHANGED)
                    PythonPluginEntrypointFinalizedProof.UNAVAILABLE ->
                        recoveryWithoutTransaction(PluginInstallLocalRecoverability.UNAVAILABLE)
                }
            }
            val state = if (
                snapshot.activeEnvironmentDigest == PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST
            ) {
                PluginInstallLocalRecoverability.ABSENT
            } else {
                PluginInstallLocalRecoverability.CHANGED
            }
            return recoveryWithoutTransaction(state)
        }
        if (environmentCandidates.size != 1) {
            return recoveryWithoutTransaction(PluginInstallLocalRecoverability.CHANGED)
        }
        val environment = environmentCandidates.single()
        if (!environment.matches(pluginId, descriptor)) {
            return recoveryWithoutTransaction(PluginInstallLocalRecoverability.CHANGED)
        }

        val entrypoint = when (val expectedTransaction = descriptor.entrypointTransactionId) {
            null -> {
                if (entrypointCandidates.isNotEmpty()) {
                    return recoveryWithoutTransaction(PluginInstallLocalRecoverability.CHANGED)
                }
                null
            }
            else -> {
                if (entrypointCandidates.size != 1) {
                    return recoveryWithoutTransaction(PluginInstallLocalRecoverability.CHANGED)
                }
                entrypointCandidates.single().takeIf {
                    it.matches(
                        pluginId = pluginId,
                        environmentDigest = descriptor.environmentDigest,
                        transactionId = expectedTransaction,
                        metadataDigest = checkNotNull(descriptor.entrypointMetadataDigest),
                    )
                } ?: return recoveryWithoutTransaction(PluginInstallLocalRecoverability.CHANGED)
            }
        }

        val recoverability = when {
            environment.state == PythonEnvironmentRecoveryState.PREPARED &&
                entrypoint?.state == PythonPluginEntrypointRecoveryState.COMMITTED ->
                PluginInstallLocalRecoverability.CHANGED
            environment.state == PythonEnvironmentRecoveryState.COMMITTED &&
                (entrypoint == null ||
                    entrypoint.state == PythonPluginEntrypointRecoveryState.COMMITTED) ->
                PluginInstallLocalRecoverability.COMMITTED
            else -> PluginInstallLocalRecoverability.PREPARED
        }
        if (recoverability == PluginInstallLocalRecoverability.CHANGED) {
            return recoveryWithoutTransaction(recoverability)
        }
        return PluginRuntimeDependencyRecoveryResult.recoverable(
            recoverability = recoverability,
            transaction = PythonEnvironmentPluginTransaction(
                environments = environments,
                environmentReceipt = environment.receipt,
                entrypointRegistry = entrypointRegistry,
                registryReceipt = entrypoint?.receipt,
                activeSourceSha256 = entrypoint?.activation?.sourceSha256,
                resolvedEntrypointIds = emptySet(),
            ),
        )
    }

    private fun loadRequirements(sourceRoot: File, pluginId: String): PluginPythonRequirements {
        val file = File(sourceRoot, PluginPythonRequirementsCodec.FILE_NAME)
        if (!file.exists()) return PluginPythonRequirements(pluginId, emptyList())
        requireRegularDirectChild(file, sourceRoot, "Python requirements")
        val decoded = PluginPythonRequirementsCodec.decode(file.readBytes())
        require(decoded.pluginId == pluginId) { "Python requirements plugin id mismatch" }
        return decoded
    }

    private fun verifyOptionalAuthorLock(sourceRoot: File, resolved: PythonEnvironmentLock) {
        val file = File(sourceRoot, PluginPythonRequirementsCodec.LOCK_FILE_NAME)
        if (!file.exists()) return
        requireRegularDirectChild(file, sourceRoot, "Python author lock")
        require(file.length() in 1..PythonEnvironmentContract.MAX_LOCK_BYTES.toLong()) {
            "Python author lock has an invalid size"
        }
        val authored = PythonEnvironmentLockCodec.decode(file.readText(Charsets.UTF_8))
        require(PythonEnvironmentLockCodec.encode(authored) == PythonEnvironmentLockCodec.encode(resolved)) {
            "Python author lock does not match the independently resolved artifact closure"
        }
    }

    private fun resolveBindings(
        source: File,
        requirements: List<PluginEntrypointRequirement>,
    ): List<PythonPluginEntrypointBinding> = requirements.sortedBy(PluginEntrypointRequirement::id).map {
        val separator = it.target.indexOf(':')
        val module = if (separator < 0) it.target else it.target.substring(0, separator)
        val callable = if (separator < 0) null else it.target.substring(separator + 1)
        val modulePath = module.replace('.', '/')
        val moduleFile = File(source, "$modulePath.py")
        val packageFile = File(source, "$modulePath/__init__.py")
        val selected = listOf(moduleFile, packageFile).filter(File::isFile).singleOrNull()
        if (selected == null) {
            return@map PythonPluginEntrypointBinding(it.id, "$modulePath.py", callable)
        }
        requireSecureFile(selected, source)
        PythonPluginEntrypointBinding(
            requirementId = it.id,
            relativePath = source.canonicalFile.toPath().relativize(selected.canonicalFile.toPath())
                .joinToString("/") { part -> part.toString() },
            callableName = callable,
        )
    }

    private fun requireSecureDirectory(directory: File, sourceRoot: File) {
        val root = sourceRoot.canonicalFile
        val canonical = directory.canonicalFile
        require(canonical.parentFile == root && canonical.isDirectory) {
            "Python source directory is missing or escaped its plugin"
        }
        require(!Files.isSymbolicLink(directory.toPath())) { "Python source directory is a symlink" }
    }

    private fun requireSecureFile(file: File, source: File) {
        val root = source.canonicalFile.toPath()
        val canonical = file.canonicalFile.toPath()
        require(canonical.startsWith(root) && file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "Python entrypoint escaped its source directory"
        }
    }

    private fun requireRegularDirectChild(file: File, sourceRoot: File, label: String) {
        require(
            file.canonicalFile.parentFile == sourceRoot.canonicalFile && file.isFile &&
                !Files.isSymbolicLink(file.toPath()),
        ) { "$label is not a regular direct child" }
    }

    private fun PythonEnvironmentLock.withSourceDigest(sourceSha256: String?) = PythonEnvironmentLock(
        schemaVersion = schemaVersion,
        pluginId = pluginId,
        target = target,
        wheels = wheels,
        nativePackages = nativePackages,
        sourceSha256 = sourceSha256,
    )

    private fun PythonEnvironmentRecoveryDescriptor.matches(
        pluginId: String,
        expected: PluginDependencyRecoveryDescriptor,
    ): Boolean = receipt.transactionId == expected.environmentTransactionId &&
        receipt.pluginId == pluginId &&
        installed.pluginId == pluginId &&
        receipt.environmentDigest == expected.environmentDigest &&
        installed.environmentDigest == expected.environmentDigest

    private fun PythonPluginEntrypointRecoveryDescriptor.matches(
        pluginId: String,
        environmentDigest: String,
        transactionId: String,
        metadataDigest: String,
    ): Boolean = receipt.transactionId == transactionId &&
        receipt.pluginId == pluginId &&
        receipt.metadataDigest == metadataDigest &&
        activation.pluginId == pluginId &&
        activation.environmentDigest == environmentDigest &&
        activation.metadataDigest == metadataDigest

    private fun recoveryWithoutTransaction(
        recoverability: PluginInstallLocalRecoverability,
    ) = PluginRuntimeDependencyRecoveryResult.withoutTransaction(recoverability)

    private fun deleteTree(root: File) {
        if (!root.exists()) return
        Files.walkFileTree(
            root.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private class ResolutionCancellationAdapter(
        private val delegate: PluginRuntimePreparationCancellation,
    ) : PythonResolutionCancellation {
        override fun isCancelled(): Boolean = delegate.isCancellationRequested()
        override fun onCancel(action: () -> Unit): Closeable = delegate.onCancel(action)
    }

    private class InstallCancellationAdapter(
        private val delegate: PluginRuntimePreparationCancellation,
    ) : PythonEnvironmentInstallCancellation {
        override fun isCancellationRequested(): Boolean = delegate.isCancellationRequested()
    }

    private class PythonEnvironmentPluginTransaction(
        private val environments: PythonEnvironmentStore,
        private val environmentReceipt: PythonEnvironmentInstallReceipt,
        private val entrypointRegistry: PythonPluginEntrypointRegistry,
        private val registryReceipt: PythonPluginEntrypointActivationReceipt?,
        private val activeSourceSha256: String?,
        override val resolvedEntrypointIds: Set<String>,
    ) : PluginRuntimeDependencyTransaction {
        override val recoveryDescriptor = PluginDependencyRecoveryDescriptor(
            kind = PluginDependencyRecoveryKind.PYTHON_ENVIRONMENT_V1,
            environmentTransactionId = environmentReceipt.transactionId,
            environmentDigest = environmentReceipt.environmentDigest,
            entrypointTransactionId = registryReceipt?.transactionId,
            entrypointMetadataDigest = registryReceipt?.metadataDigest,
        )
        private var state = State.PREPARED
        private var environmentFinalized = false
        private var registryFinalized = false

        @Synchronized
        override fun commit() {
            if (state == State.COMMITTED || state == State.FINALIZED) return
            check(state == State.PREPARED) { "Python plugin transaction is not prepared" }
            environments.commitActivation(environmentReceipt)
            registryReceipt?.let { activation ->
                entrypointRegistry.commitActivation(
                    receipt = activation,
                    activeEnvironmentDigest = environmentReceipt.environmentDigest,
                    activeSourceSha256 = checkNotNull(activeSourceSha256),
                )
            }
            state = State.COMMITTED
        }

        @Synchronized
        override fun rollback() {
            if (state == State.ROLLED_BACK || state == State.FINALIZED) return
            var failure: Throwable? = null
            if (!registryFinalized) {
                registryReceipt?.let { activation ->
                    runCatching { entrypointRegistry.rollbackActivation(activation) }
                        .onFailure { failure = it }
                }
            }
            if (!environmentFinalized) {
                runCatching { environments.rollbackActivation(environmentReceipt) }
                    .onFailure { environmentFailure ->
                        if (failure == null) {
                            failure = environmentFailure
                        } else {
                            failure?.addSuppressed(environmentFailure)
                        }
                    }
            }
            if (failure == null) {
                state = State.ROLLED_BACK
            } else {
                throw checkNotNull(failure)
            }
        }

        @Synchronized
        override fun finalizeCommit() {
            if (state == State.FINALIZED) return
            check(state == State.COMMITTED) { "Python plugin transaction is not committed" }
            if (!registryFinalized) {
                registryReceipt?.let { activation ->
                    entrypointRegistry.finalizeActivation(activation)
                }
                registryFinalized = true
            }
            if (!environmentFinalized) {
                environments.finalizeActivation(environmentReceipt)
                environmentFinalized = true
            }
            state = State.FINALIZED
        }

        private enum class State { PREPARED, COMMITTED, ROLLED_BACK, FINALIZED }
    }

    private companion object {
        const val PYTHON_SOURCE_DIRECTORY = "python"
    }

    private data class RecoverySnapshot(
        val environments: List<PythonEnvironmentRecoveryDescriptor>,
        val entrypoints: List<PythonPluginEntrypointRecoveryDescriptor>,
        val activeEnvironmentDigest: String,
    )

    /** Local finalization already consumed both receipts; only outer journal closure remains. */
    private class FinalizedPythonPluginTransaction(
        override val recoveryDescriptor: PluginDependencyRecoveryDescriptor,
    ) : PluginRuntimeDependencyTransaction {
        override val resolvedEntrypointIds: Set<String> = emptySet()
        override fun commit() = Unit
        override fun finalizeCommit() = Unit
        override fun rollback(): Unit = error("A finalized Python dependency cannot be rolled back")
    }
}

internal data object EmptyPluginRuntimeDependencyTransaction : PluginRuntimeDependencyTransaction {
    override val resolvedEntrypointIds: Set<String> = emptySet()
    override fun commit() = Unit
    override fun rollback() = Unit
    override fun finalizeCommit() = Unit
}
