package ai.hans.standard.plugins

import ai.hans.standard.runtime.python.PythonEnvironmentContract
import ai.hans.standard.runtime.python.PythonEnvironmentImportSelfTester
import ai.hans.standard.runtime.python.PythonEnvironmentLock
import ai.hans.standard.runtime.python.PythonEnvironmentLockCodec
import ai.hans.standard.runtime.python.PythonEnvironmentState
import ai.hans.standard.runtime.python.PythonEnvironmentStore
import ai.hans.standard.runtime.python.PythonEnvironmentTarget
import ai.hans.standard.runtime.python.PythonImportSelfTestResult
import ai.hans.standard.runtime.python.PythonPluginEntrypointRegistry
import ai.hans.standard.runtime.python.PythonPluginEntrypointResolution
import ai.hans.standard.runtime.python.PythonRuntimeContract
import ai.hans.standard.runtime.python.PythonWheelArchiveValidator
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonPluginRuntimeDependencyPreparerTest {
    @Test
    fun stdlibOnlyPluginIsProvenBeforeItsEnvironmentCanBecomeActive() {
        val fixture = fixture()
        val source = File(fixture.plugin, "python").apply { mkdir() }
        File(source, "main.py").writeText("def run():\n    return 42\n")
        var observedBindings = emptyList<PythonPluginEntrypointBinding>()
        val preparer = fixture.preparer(
            prober = PythonPluginEntrypointProber { pluginId, digest, bindings, _ ->
                assertEquals("sample", pluginId)
                assertTrue(PythonEnvironmentContract.isSha256(digest))
                observedBindings = bindings
                bindings.mapTo(linkedSetOf(), PythonPluginEntrypointBinding::requirementId)
            },
        )

        val transaction = preparer.prepare(
            pythonRequirements(setOf("phone.read", "phone.write")),
            fixture.plugin,
            NeverCancelled,
        )

        assertEquals(setOf("main"), transaction.resolvedEntrypointIds)
        assertEquals("main.py", observedBindings.single().relativePath)
        assertEquals("run", observedBindings.single().callableName)
        assertEquals(
            PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST,
            fixture.store.digestFor("sample"),
        )
        assertEquals(1, fixture.registry.status().pendingActivationCount)
        assertEquals(0, fixture.registry.status().committedEntrypointCount)

        transaction.commit()
        val committedDigest = checkNotNull(transaction.recoveryDescriptor).environmentDigest
        assertEquals(
            PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST,
            fixture.store.digestFor("sample"),
        )
        assertEquals(
            PythonPluginEntrypointRegistry.ERROR_UNCOMMITTED,
            (fixture.registry.resolve("sample", "main", committedDigest) as
                PythonPluginEntrypointResolution.Rejected).errorCode,
        )
        transaction.finalizeCommit()
        val activeDigest = fixture.store.digestFor("sample")
        assertEquals(committedDigest, activeDigest)
        val resolved = fixture.registry.resolve("sample", "main", activeDigest)
            as PythonPluginEntrypointResolution.Resolved
        assertEquals(setOf("phone.read", "phone.write"), resolved.declaredCapabilities)
        assertEquals(
            PythonEnvironmentState.ACTIVE,
            fixture.store.snapshot().environments.single().state,
        )
        assertTrue(fixture.preparer().removeCommitted("sample", activeDigest))
        assertEquals(
            PythonPluginEntrypointRegistry.ERROR_MISSING,
            (fixture.registry.resolve("sample", "main", activeDigest) as
                PythonPluginEntrypointResolution.Rejected).errorCode,
        )
    }

    @Test
    fun authorLockMustMatchFreshResolutionAndTheDedicatedSourceDigest() {
        val fixture = fixture()
        val source = File(fixture.plugin, "python").apply { mkdir() }
        File(source, "main.py").writeText("def run():\n    return 42\n")
        val wrong = PythonEnvironmentLock(
            schemaVersion = PythonEnvironmentContract.LOCK_SCHEMA_VERSION,
            pluginId = "sample",
            target = TARGET,
            wheels = emptyList(),
            sourceSha256 = "a".repeat(64),
        )
        File(fixture.plugin, PluginPythonRequirementsCodec.LOCK_FILE_NAME)
            .writeText(PythonEnvironmentLockCodec.encode(wrong))

        val error = assertThrows(IllegalArgumentException::class.java) {
            fixture.preparer().prepare(pythonRequirements(), fixture.plugin, NeverCancelled)
        }

        assertTrue(error.message.orEmpty().contains("author lock"))
        assertEquals(
            PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST,
            fixture.store.digestFor("sample"),
        )
    }

    @Test
    fun cancellationIsObservedBeforeResolutionOrEnvironmentMutation() {
        val fixture = fixture()
        val source = File(fixture.plugin, "python").apply { mkdir() }
        File(source, "main.py").writeText("def run():\n    return 42\n")
        val cancellation = MutableTestCancellation().apply { cancel() }

        assertThrows(PluginRuntimePreparationCancelledException::class.java) {
            fixture.preparer().prepare(pythonRequirements(), fixture.plugin, cancellation)
        }
        assertEquals(
            PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST,
            fixture.store.digestFor("sample"),
        )
    }

    @Test
    fun rollbackRemovesBothPreparedEnvironmentAndUncommittedEntrypoints() {
        val fixture = fixture()
        val source = File(fixture.plugin, "python").apply { mkdir() }
        File(source, "main.py").writeText("def run():\n    return 42\n")
        val transaction = fixture.preparer().prepare(
            pythonRequirements(),
            fixture.plugin,
            NeverCancelled,
        )

        assertEquals(1, fixture.registry.status().pendingActivationCount)
        transaction.rollback()

        assertEquals(0, fixture.registry.status().pendingActivationCount)
        assertEquals(0, fixture.registry.status().committedEntrypointCount)
        assertEquals(
            PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST,
            fixture.store.digestFor("sample"),
        )
        assertEquals(
            PythonPluginEntrypointRegistry.ERROR_MISSING,
            (fixture.registry.resolve(
                "sample",
                "main",
                PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST,
            ) as PythonPluginEntrypointResolution.Rejected).errorCode,
        )
    }

    @Test
    fun sourceOutsideTheDedicatedPythonDirectoryCannotBeClaimedAsAnEntrypoint() {
        val fixture = fixture()
        File(fixture.plugin, "main.py").writeText("def run():\n    return 42\n")

        val error = assertThrows(IllegalArgumentException::class.java) {
            fixture.preparer().prepare(pythonRequirements(), fixture.plugin, NeverCancelled)
        }

        assertTrue(error.message.orEmpty().contains("source directory"))
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("hans-python-plugin-preparer").toFile()
        val plugin = File(root, "plugins/sample").apply { mkdirs() }
        val store = PythonEnvironmentStore(
            rootDirectory = File(root, "environments"),
            wheelValidator = PythonWheelArchiveValidator(36, setOf("arm64-v8a")),
            importSelfTester = PythonEnvironmentImportSelfTester {
                PythonImportSelfTestResult(true, it.importNames)
            },
        )
        return Fixture(
            root,
            plugin,
            store,
            PythonPluginEntrypointRegistry(File(root, "entrypoints/registry.json")),
        )
    }

    private fun pythonRequirements(capabilities: Set<String> = emptySet()) = PluginRuntimeRequirements(
        pluginId = "sample",
        runtimes = listOf(
            PluginRuntimeRequirement(
                id = "python",
                kind = PluginRuntimeKind.EMBEDDED_PYTHON,
                placement = PluginRuntimePlacement.LOCAL,
            ),
        ),
        entrypoints = listOf(
            PluginEntrypointRequirement(
                id = "main",
                runtimeRequirementId = "python",
                kind = PluginEntrypointKind.PYTHON_CALLABLE,
                target = "main:run",
            ),
        ),
        capabilities = capabilities.sorted().map { PluginCapabilityRequirement(it) },
    )

    private data class Fixture(
        val root: File,
        val plugin: File,
        val store: PythonEnvironmentStore,
        val registry: PythonPluginEntrypointRegistry,
    ) {
        fun preparer(
            prober: PythonPluginEntrypointProber = PythonPluginEntrypointProber { _, _, bindings, _ ->
                bindings.mapTo(linkedSetOf(), PythonPluginEntrypointBinding::requirementId)
            },
        ) = PythonPluginRuntimeDependencyPreparer(
            stagingRoot = File(root, "downloads"),
            target = TARGET,
            resolver = object : ai.hans.standard.runtime.python.resolver.CancellablePythonEnvironmentResolver {
                override fun resolve(
                    request: ai.hans.standard.runtime.python.PythonEnvironmentResolutionRequest,
                    cancellation: ai.hans.standard.runtime.python.resolver.PythonResolutionCancellation,
                ): PythonEnvironmentLock = error("Empty requirements must not start the resolver")
            },
            downloader = CancellablePythonWheelDownloader { _, _, _ ->
                error("Empty requirements must not download a wheel")
            },
            environments = store,
            entrypoints = prober,
            entrypointRegistry = registry,
        )
    }

    private data object NeverCancelled : PluginRuntimePreparationCancellation {
        override fun isCancellationRequested() = false
        override fun onCancel(action: () -> Unit) = Closeable {}
    }

    private class MutableTestCancellation : PluginRuntimePreparationCancellation {
        private val cancelled = AtomicBoolean(false)
        override fun isCancellationRequested() = cancelled.get()
        override fun onCancel(action: () -> Unit): Closeable {
            if (cancelled.get()) action()
            return Closeable {}
        }
        fun cancel() = cancelled.set(true)
    }

    private companion object {
        val TARGET = PythonEnvironmentTarget("3.14.7", "cp314", "arm64-v8a", 31)
    }
}
