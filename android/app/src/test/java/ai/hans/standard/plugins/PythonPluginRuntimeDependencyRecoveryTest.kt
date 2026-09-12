package ai.hans.standard.plugins

import ai.hans.standard.plugins.install.PluginDependencyRecoveryDescriptor
import ai.hans.standard.plugins.install.PluginInstallJournalPhase
import ai.hans.standard.plugins.install.PluginInstallLocalRecoverability
import ai.hans.standard.runtime.python.PythonEnvironmentImportSelfTester
import ai.hans.standard.runtime.python.PythonEnvironmentLock
import ai.hans.standard.runtime.python.PythonEnvironmentStore
import ai.hans.standard.runtime.python.PythonEnvironmentTarget
import ai.hans.standard.runtime.python.PythonImportSelfTestResult
import ai.hans.standard.runtime.python.PythonPluginEntrypointActivation
import ai.hans.standard.runtime.python.PythonPluginEntrypointDeclaration
import ai.hans.standard.runtime.python.PythonPluginEntrypointRegistry
import ai.hans.standard.runtime.python.PythonPluginEntrypointResolution
import ai.hans.standard.runtime.python.PythonRuntimeContract
import ai.hans.standard.runtime.python.PythonWheelArchiveValidator
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonPluginRuntimeDependencyRecoveryTest {
    @Test
    fun preparedEnvironmentAndEntrypointRecoverAsPreparedAndRollbackExactReceipts() {
        val fixture = fixture()
        val original = fixture.runtime()
        val transaction = original.prepare(withEntrypoint = true)
        val descriptor = checkNotNull(transaction.recoveryDescriptor)

        val recovered = fixture.runtime()
        val result = recovered.preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.LOCAL_PREPARED,
        )

        assertEquals(PluginInstallLocalRecoverability.PREPARED, result.recoverability)
        assertNotNull(result.transaction)
        result.transaction!!.rollback()
        assertTrue(recovered.store.recoveryDescriptors().isEmpty())
        assertTrue(recovered.registry.recoveryDescriptors().isEmpty())
        assertEquals(PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST, recovered.store.digestFor(PLUGIN_ID))
    }

    @Test
    fun committedEnvironmentAndPreparedEntrypointRecoverAsPreparedAndCommitConverges() {
        val fixture = fixture()
        val original = fixture.runtime()
        val transaction = original.prepare(withEntrypoint = true)
        val descriptor = checkNotNull(transaction.recoveryDescriptor)
        original.store.commitActivation(original.store.recoveryDescriptors().single().receipt)

        val recovered = fixture.runtime()
        val result = recovered.preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.LOCAL_COMMIT_INTENT,
        )

        assertEquals(PluginInstallLocalRecoverability.PREPARED, result.recoverability)
        result.transaction!!.commit()
        result.transaction.commit()
        result.transaction.finalizeCommit()
        result.transaction.finalizeCommit()
        assertEquals(descriptor.environmentDigest, recovered.store.digestFor(PLUGIN_ID))
        assertTrue(
            recovered.registry.resolve(PLUGIN_ID, ENTRYPOINT_ID, descriptor.environmentDigest) is
                PythonPluginEntrypointResolution.Resolved,
        )
        assertTrue(recovered.store.recoveryDescriptors().isEmpty())
        assertTrue(recovered.registry.recoveryDescriptors().isEmpty())
    }

    @Test
    fun committedEnvironmentAndEntrypointRecoverAsCommitted() {
        val fixture = fixture()
        val original = fixture.runtime()
        val transaction = original.prepare(withEntrypoint = true)
        val descriptor = checkNotNull(transaction.recoveryDescriptor)
        val environment = original.store.recoveryDescriptors().single()
        val entrypoint = original.registry.recoveryDescriptors().single()
        original.store.commitActivation(environment.receipt)
        original.registry.commitActivation(
            receipt = entrypoint.receipt,
            activeEnvironmentDigest = descriptor.environmentDigest,
            activeSourceSha256 = entrypoint.activation.sourceSha256,
        )

        val result = fixture.runtime().preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.LOCAL_COMMITTED,
        )

        assertEquals(PluginInstallLocalRecoverability.COMMITTED, result.recoverability)
        assertNotNull(result.transaction)
    }

    @Test
    fun preparedEnvironmentAndCommittedEntrypointAreChanged() {
        val fixture = fixture()
        val original = fixture.runtime()
        val transaction = original.prepare(withEntrypoint = true)
        val descriptor = checkNotNull(transaction.recoveryDescriptor)
        val entrypoint = original.registry.recoveryDescriptors().single()
        original.registry.commitActivation(
            receipt = entrypoint.receipt,
            activeEnvironmentDigest = descriptor.environmentDigest,
            activeSourceSha256 = entrypoint.activation.sourceSha256,
        )

        val result = fixture.runtime().preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.LOCAL_COMMIT_INTENT,
        )

        assertEquals(PluginInstallLocalRecoverability.CHANGED, result.recoverability)
        assertNull(result.transaction)
    }

    @Test
    fun missingExpectedReceiptAtAlreadyActiveDigestIsChanged() {
        val fixture = fixture()
        val original = fixture.runtime()
        val transaction = original.prepare(withEntrypoint = true)
        val descriptor = checkNotNull(transaction.recoveryDescriptor)
        transaction.commit()
        transaction.finalizeCommit()

        val result = fixture.runtime().preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.LOCAL_COMMITTED,
        )

        assertEquals(PluginInstallLocalRecoverability.CHANGED, result.recoverability)
        assertNull(result.transaction)
    }

    @Test
    fun finalizedPhaseWithExactActiveEnvironmentAndEntrypointsRecoversAsCommittedNoOp() {
        val fixture = fixture()
        val original = fixture.runtime()
        val transaction = original.prepare(withEntrypoint = true)
        val descriptor = checkNotNull(transaction.recoveryDescriptor)
        transaction.commit()
        transaction.finalizeCommit()

        val result = fixture.runtime().preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.FINALIZED,
        )

        assertEquals(PluginInstallLocalRecoverability.COMMITTED, result.recoverability)
        assertNotNull(result.transaction)
        result.transaction!!.commit()
        result.transaction.finalizeCommit()
        assertEquals(descriptor.environmentDigest, fixture.runtime().store.digestFor(PLUGIN_ID))
    }

    @Test
    fun finalizedPhaseWithoutEntrypointsProvesTheirAbsenceAndRecoversAsCommitted() {
        val fixture = fixture()
        val original = fixture.runtime()
        val transaction = original.prepare(withEntrypoint = false)
        val descriptor = checkNotNull(transaction.recoveryDescriptor)
        transaction.commit()
        transaction.finalizeCommit()

        val result = fixture.runtime().preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.FINALIZED,
        )

        assertEquals(PluginInstallLocalRecoverability.COMMITTED, result.recoverability)
        assertNotNull(result.transaction)
    }

    @Test
    fun finalizedPhaseNeverGuessesExpectedEntrypointsFromEnvironmentAlone() {
        val fixture = fixture()
        val original = fixture.runtime()
        val transaction = original.prepare(withEntrypoint = true)
        val descriptor = checkNotNull(transaction.recoveryDescriptor)
        val environment = original.store.recoveryDescriptors().single()
        val entrypoint = original.registry.recoveryDescriptors().single()
        original.store.commitActivation(environment.receipt)
        original.registry.commitActivation(
            receipt = entrypoint.receipt,
            activeEnvironmentDigest = descriptor.environmentDigest,
            activeSourceSha256 = entrypoint.activation.sourceSha256,
        )
        original.registry.rollbackActivation(entrypoint.receipt)
        original.store.finalizeActivation(environment.receipt)

        val result = fixture.runtime().preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.FINALIZED,
        )

        assertEquals(PluginInstallLocalRecoverability.CHANGED, result.recoverability)
        assertNull(result.transaction)
    }

    @Test
    fun missingExpectedEntrypointReceiptWhileEnvironmentReceiptRemainsIsChanged() {
        val fixture = fixture()
        val original = fixture.runtime()
        val transaction = original.prepare(withEntrypoint = true)
        val descriptor = checkNotNull(transaction.recoveryDescriptor)
        original.registry.rollbackActivation(original.registry.recoveryDescriptors().single().receipt)

        val result = fixture.runtime().preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.LOCAL_PREPARED,
        )

        assertEquals(PluginInstallLocalRecoverability.CHANGED, result.recoverability)
        assertNull(result.transaction)
    }

    @Test
    fun entrypointReceiptWithoutMainDescriptorEntrypointIsChanged() {
        val fixture = fixture()
        val original = fixture.runtime()
        val transaction = original.prepare(withEntrypoint = false)
        val descriptor = checkNotNull(transaction.recoveryDescriptor)
        val sourceDigest = "a".repeat(64)
        original.registry.prepareActivation(
            PythonPluginEntrypointActivation(
                pluginId = PLUGIN_ID,
                environmentDigest = descriptor.environmentDigest,
                sourceSha256 = sourceDigest,
                declarations = listOf(
                    PythonPluginEntrypointDeclaration(
                        entrypointId = ENTRYPOINT_ID,
                        relativePath = "main.py",
                        function = "run",
                        sourceSha256 = sourceDigest,
                    ),
                ),
                provenEntrypointIds = setOf(ENTRYPOINT_ID),
            ),
        )

        val result = fixture.runtime().preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.LOCAL_PREPARED,
        )

        assertEquals(PluginInstallLocalRecoverability.CHANGED, result.recoverability)
        assertNull(result.transaction)
    }

    @Test
    fun completedRollbackWithNoActiveDigestIsAbsent() {
        val fixture = fixture()
        val original = fixture.runtime()
        val transaction = original.prepare(withEntrypoint = true)
        val descriptor = checkNotNull(transaction.recoveryDescriptor)
        transaction.rollback()

        val result = fixture.runtime().preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.ROLLBACK_INTENT,
        )

        assertEquals(PluginInstallLocalRecoverability.ABSENT, result.recoverability)
        assertNull(result.transaction)
    }

    @Test
    fun unavailableEntrypointStoreReturnsUnavailableWithoutTransaction() {
        val fixture = fixture()
        val original = fixture.runtime()
        val transaction = original.prepare(withEntrypoint = true)
        val descriptor = checkNotNull(transaction.recoveryDescriptor)
        fixture.registryFile.writeText("{not-json")

        val result = fixture.runtime().preparer.recover(
            PLUGIN_ID,
            descriptor,
            PluginInstallJournalPhase.LOCAL_PREPARED,
        )

        assertEquals(PluginInstallLocalRecoverability.UNAVAILABLE, result.recoverability)
        assertNull(result.transaction)
    }

    @Test
    fun crossWiredEnvironmentIdentityIsChanged() {
        val fixture = fixture()
        val original = fixture.runtime()
        val descriptor = checkNotNull(original.prepare(withEntrypoint = true).recoveryDescriptor)
        val changed = PluginDependencyRecoveryDescriptor(
            kind = descriptor.kind,
            environmentTransactionId = descriptor.environmentTransactionId,
            environmentDigest = "f".repeat(64),
            entrypointTransactionId = descriptor.entrypointTransactionId,
            entrypointMetadataDigest = descriptor.entrypointMetadataDigest,
        )

        val result = fixture.runtime().preparer.recover(
            PLUGIN_ID,
            changed,
            PluginInstallJournalPhase.LOCAL_PREPARED,
        )

        assertEquals(PluginInstallLocalRecoverability.CHANGED, result.recoverability)
        assertNull(result.transaction)
    }

    @Test
    fun missingMainDescriptorWithNoLocalStateIsAbsent() {
        val fixture = fixture()

        val result = fixture.runtime().preparer.recover(
            PLUGIN_ID,
            null,
            PluginInstallJournalPhase.LOCAL_PREPARING,
        )

        assertEquals(PluginInstallLocalRecoverability.ABSENT, result.recoverability)
        assertNull(result.transaction)
    }

    @Test
    fun missingMainDescriptorWithPreparedEnvironmentReceiptIsChanged() {
        val fixture = fixture()
        fixture.runtime().prepare(withEntrypoint = false)

        val result = fixture.runtime().preparer.recover(
            PLUGIN_ID,
            null,
            PluginInstallJournalPhase.LOCAL_PREPARING,
        )

        assertEquals(PluginInstallLocalRecoverability.CHANGED, result.recoverability)
        assertNull(result.transaction)
    }

    @Test
    fun missingMainDescriptorWithActiveEnvironmentIsChanged() {
        val fixture = fixture()
        val original = fixture.runtime()
        val transaction = original.prepare(withEntrypoint = false)
        transaction.commit()
        transaction.finalizeCommit()

        val result = fixture.runtime().preparer.recover(
            PLUGIN_ID,
            null,
            PluginInstallJournalPhase.LOCAL_PREPARING,
        )

        assertEquals(PluginInstallLocalRecoverability.CHANGED, result.recoverability)
        assertNull(result.transaction)
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("hans-python-plugin-recovery").toFile()
        val plugin = File(root, "plugins/$PLUGIN_ID").apply { mkdirs() }
        val source = File(plugin, "python").apply { mkdir() }
        File(source, "main.py").writeText("def run():\n    return 42\n")
        return Fixture(
            root = root,
            plugin = plugin,
            registryFile = File(root, "entrypoints/registry.json"),
        )
    }

    private data class Fixture(
        val root: File,
        val plugin: File,
        val registryFile: File,
    ) {
        fun runtime(): RuntimeFixture {
            val store = PythonEnvironmentStore(
                rootDirectory = File(root, "environments"),
                wheelValidator = PythonWheelArchiveValidator(36, setOf("arm64-v8a")),
                importSelfTester = PythonEnvironmentImportSelfTester {
                    PythonImportSelfTestResult(true, it.importNames)
                },
            )
            val registry = PythonPluginEntrypointRegistry(registryFile)
            val preparer = PythonPluginRuntimeDependencyPreparer(
                stagingRoot = File(root, "downloads"),
                target = TARGET,
                resolver = object :
                    ai.hans.standard.runtime.python.resolver.CancellablePythonEnvironmentResolver {
                    override fun resolve(
                        request: ai.hans.standard.runtime.python.PythonEnvironmentResolutionRequest,
                        cancellation: ai.hans.standard.runtime.python.resolver.PythonResolutionCancellation,
                    ): PythonEnvironmentLock = error("Empty requirements must not resolve")
                },
                downloader = CancellablePythonWheelDownloader { _, _, _ ->
                    error("Empty requirements must not download")
                },
                environments = store,
                entrypoints = PythonPluginEntrypointProber { _, _, bindings, _ ->
                    bindings.mapTo(linkedSetOf(), PythonPluginEntrypointBinding::requirementId)
                },
                entrypointRegistry = registry,
            )
            return RuntimeFixture(store, registry, preparer, plugin)
        }
    }

    private data class RuntimeFixture(
        val store: PythonEnvironmentStore,
        val registry: PythonPluginEntrypointRegistry,
        val preparer: PythonPluginRuntimeDependencyPreparer,
        val plugin: File,
    ) {
        fun prepare(withEntrypoint: Boolean): PluginRuntimeDependencyTransaction =
            preparer.prepare(requirements(withEntrypoint), plugin, NeverCancelled)
    }

    private data object NeverCancelled : PluginRuntimePreparationCancellation {
        override fun isCancellationRequested() = false
        override fun onCancel(action: () -> Unit) = Closeable {}
    }

    companion object {
        private const val PLUGIN_ID = "sample"
        private const val ENTRYPOINT_ID = "main"
        private val TARGET = PythonEnvironmentTarget("3.14.7", "cp314", "arm64-v8a", 31)

        private fun requirements(withEntrypoint: Boolean) = PluginRuntimeRequirements(
            pluginId = PLUGIN_ID,
            runtimes = listOf(
                PluginRuntimeRequirement(
                    id = "python",
                    kind = PluginRuntimeKind.EMBEDDED_PYTHON,
                    placement = PluginRuntimePlacement.LOCAL,
                ),
            ),
            entrypoints = if (withEntrypoint) {
                listOf(
                    PluginEntrypointRequirement(
                        id = ENTRYPOINT_ID,
                        runtimeRequirementId = "python",
                        kind = PluginEntrypointKind.PYTHON_CALLABLE,
                        target = "main:run",
                    ),
                )
            } else {
                emptyList()
            },
            capabilities = emptyList(),
        )
    }
}
