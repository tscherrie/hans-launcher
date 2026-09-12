package ai.hans.standard.runtime.python

import ai.hans.standard.BuildConfig
import ai.hans.standard.plugins.PluginRuntimePreparationCancellation
import ai.hans.standard.plugins.PythonPluginEntrypointBinding
import ai.hans.standard.plugins.PythonRuntimePluginEntrypointProber
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two-invocation shipping-path proof for a real, same-signer application update.
 *
 * The host gate runs [seedLowerVersionEnvironment] against the lower publisher build, updates that
 * exact package with `adb install -r`, and then runs [verifyHigherVersionEnvironment]. The seed is
 * intentionally stored below the target application's no-backup directory. The verification phase
 * has no wheel/source construction or install call: it can only reopen the pre-update store and
 * execute the finalized entrypoint that survived package replacement.
 */
@RunWith(AndroidJUnit4::class)
class PythonUpdateContinuityInstrumentedTest {
    @Test
    fun seedLowerVersionEnvironment() {
        val fixture = fixture(PHASE_SEED)
        assertFalse("Update-continuity state already exists", fixture.stateFile.exists())
        assertFalse("Update-continuity environment already exists", fixture.environmentRoot.exists())
        assertFalse("Update-continuity registry already exists", fixture.registryFile.exists())

        val scratch = File(fixture.root, "seed-input").apply {
            assertTrue("Cannot create update-continuity seed input", mkdirs())
        }
        val wheel = createWheel(scratch)
        val source = File(scratch, "plugin-source").apply {
            assertTrue(mkdirs())
            File(this, ENTRYPOINT_PATH).writeText(
                """
                from hans_update_continuity import stable_value

                def run(value):
                    return {
                        "answer": stable_value(value),
                        "fixture": "$RESULT_FIXTURE",
                    }
                """.trimIndent(),
                StandardCharsets.UTF_8,
            )
        }
        val sourceSha256 = PythonEnvironmentSourceDigest.digest(source)
        val pin = PythonWheelPin(
            packageName = PACKAGE_NAME,
            version = PACKAGE_VERSION,
            fileName = wheel.name,
            sha256 = PythonEnvironmentContract.sha256(wheel.readBytes()),
            sizeBytes = wheel.length(),
            sourceUri = "file://${wheel.name}",
            requiresPython = ">=3.14",
        )
        val lock = PythonEnvironmentLock(
            schemaVersion = PythonEnvironmentContract.LOCK_SCHEMA_VERSION,
            pluginId = fixture.pluginId,
            target = PythonEnvironmentTarget(
                pythonVersion = BuildConfig.PYTHON_RUNTIME_VERSION,
                interpreterTag = "cp314",
                androidAbi = BuildConfig.PYTHON_RUNTIME_ABI,
                minimumAndroidApi = 31,
            ),
            wheels = listOf(pin),
            sourceSha256 = sourceSha256,
        )

        val runtimeOwner = runtime(fixture)
        val environments = runtimeOwner.environments
        val registry = PythonPluginEntrypointRegistry(fixture.registryFile)
        var environmentReceipt: PythonEnvironmentInstallReceipt? = null
        var entrypointReceipt: PythonPluginEntrypointActivationReceipt? = null
        var finalized = false
        try {
            environmentReceipt = environments.prepareInstall(
                PythonEnvironmentInstallRequest(
                    lock = lock,
                    offlineWheels = PythonOfflineWheelSet.of(mapOf(pin.sha256 to wheel)),
                    pluginSourceDirectory = source,
                ),
            )
            val proven = PythonRuntimePluginEntrypointProber(
                runtime = runtimeOwner.runtime,
                timeoutMillis = EXECUTION_TIMEOUT_MILLIS,
            ).prove(
                pluginId = fixture.pluginId,
                environmentDigest = environmentReceipt.environmentDigest,
                bindings = listOf(
                    PythonPluginEntrypointBinding(
                        requirementId = ENTRYPOINT_ID,
                        relativePath = ENTRYPOINT_PATH,
                        callableName = ENTRYPOINT_FUNCTION,
                    ),
                ),
                cancellation = NEVER_CANCELLED,
            )
            assertEquals(setOf(ENTRYPOINT_ID), proven)

            val activation = PythonPluginEntrypointActivation(
                pluginId = fixture.pluginId,
                environmentDigest = environmentReceipt.environmentDigest,
                sourceSha256 = sourceSha256,
                declarations = listOf(
                    PythonPluginEntrypointDeclaration(
                        entrypointId = ENTRYPOINT_ID,
                        relativePath = ENTRYPOINT_PATH,
                        function = ENTRYPOINT_FUNCTION,
                        sourceSha256 = sourceSha256,
                    ),
                ),
                provenEntrypointIds = proven,
            )
            entrypointReceipt = registry.prepareActivation(activation)
            environments.commitActivation(environmentReceipt)
            registry.commitActivation(
                entrypointReceipt,
                activeEnvironmentDigest = environmentReceipt.environmentDigest,
                activeSourceSha256 = sourceSha256,
            )
            registry.finalizeActivation(entrypointReceipt)
            environments.finalizeActivation(environmentReceipt)
            finalized = true

            assertEquals(environmentReceipt.environmentDigest, environments.digestFor(fixture.pluginId))
            assertResolved(registry, fixture.pluginId, environmentReceipt.environmentDigest, sourceSha256)
            assertExecution(runtimeOwner.runtime, environmentReceipt.environmentDigest, fixture.pluginId)

            assertTrue("Seed input was not removed", scratch.deleteRecursively())
            val state = JSONObject()
                .put("schema", STATE_SCHEMA)
                .put("runId", fixture.runId)
                .put("packageName", fixture.context.packageName)
                .put("seedVersionCode", packageVersionCode(fixture.context))
                .put("signerSha256", packageSignerSha256(fixture.context))
                .put("pluginId", fixture.pluginId)
                .put("environmentDigest", environmentReceipt.environmentDigest)
                .put("sourceSha256", sourceSha256)
                .put("entrypointId", ENTRYPOINT_ID)
                .put("entrypointMetadataDigest", activation.metadataDigest)
                .put("expectedAnswer", EXPECTED_ANSWER)
                .put("expectedFixture", RESULT_FIXTURE)
            writeExclusiveState(fixture.stateFile, state)
            assertTrue(fixture.stateFile.isFile)
        } finally {
            if (!finalized) {
                entrypointReceipt?.let { runCatching { registry.rollbackActivation(it) } }
                environmentReceipt?.let { runCatching { environments.rollbackActivation(it) } }
            }
            runtimeOwner.close()
        }
    }

    @Test
    fun verifyHigherVersionEnvironment() {
        val fixture = fixture(PHASE_VERIFY)
        assertTrue("Pre-update expectation is missing", fixture.stateFile.isFile)
        assertFalse("Seed input unexpectedly survived the first phase", File(fixture.root, "seed-input").exists())
        val rawState = fixture.stateFile.readBytes()
        assertTrue(rawState.size in 1..MAX_STATE_BYTES)
        val state = JSONObject(rawState.toString(StandardCharsets.UTF_8))
        assertEquals(STATE_SCHEMA, state.getString("schema"))
        assertEquals(fixture.runId, state.getString("runId"))
        assertEquals(fixture.context.packageName, state.getString("packageName"))
        assertEquals(packageSignerSha256(fixture.context), state.getString("signerSha256"))
        assertTrue(
            "Product version did not increase across the same-signer update",
            packageVersionCode(fixture.context) > state.getLong("seedVersionCode"),
        )

        val pluginId = state.getString("pluginId")
        val environmentDigest = state.getString("environmentDigest")
        val sourceSha256 = state.getString("sourceSha256")
        assertTrue(PythonEnvironmentContract.isPluginId(pluginId))
        assertTrue(PythonEnvironmentContract.isSha256(environmentDigest))
        assertTrue(PythonEnvironmentContract.isSha256(sourceSha256))
        assertEquals(ENTRYPOINT_ID, state.getString("entrypointId"))
        assertEquals(EXPECTED_ANSWER, state.getInt("expectedAnswer"))
        assertEquals(RESULT_FIXTURE, state.getString("expectedFixture"))

        // These constructors open the pre-existing production-format state. This phase deliberately
        // contains no prepare/install/download/source-generation route.
        assertTrue(fixture.environmentRoot.isDirectory)
        assertTrue(fixture.registryFile.isFile)
        val runtimeOwner = runtime(fixture)
        try {
            val environments = runtimeOwner.environments
            val registry = PythonPluginEntrypointRegistry(fixture.registryFile)
            assertEquals(environmentDigest, environments.digestFor(pluginId))
            assertResolved(registry, pluginId, environmentDigest, sourceSha256)
            assertEquals(
                PythonPluginEntrypointFinalizedProof.EXACT,
                registry.proveFinalizedActivationAcrossVariant(
                    pluginId,
                    environmentDigest,
                    state.getString("entrypointMetadataDigest"),
                ),
            )
            val archive = environments.open(environmentDigest)
            assertEquals(environmentDigest, archive.sha256)
            assertTrue(archive.archive.isFile)
            assertExecution(runtimeOwner.runtime, environmentDigest, pluginId)
        } finally {
            runtimeOwner.close()
        }
    }

    private fun fixture(expectedPhase: String): Fixture {
        val arguments = InstrumentationRegistry.getArguments()
        assertEquals(expectedPhase, arguments.getString(ARG_PHASE))
        val runId = requireNotNull(arguments.getString(ARG_RUN_ID))
        assertEquals(runId, UUID.fromString(runId).toString())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.noBackupFilesDir, "python/update-continuity/$runId")
        assertTrue(root.canonicalFile.toPath().startsWith(context.noBackupFilesDir.canonicalFile.toPath()))
        if (expectedPhase == PHASE_SEED) assertTrue(root.mkdirs()) else assertTrue(root.isDirectory)
        return Fixture(
            context = context,
            runId = runId,
            pluginId = "update-continuity-$runId",
            root = root,
            environmentRoot = File(root, "environments"),
            registryFile = File(root, "state/plugin-entrypoints-v1.json"),
            stateFile = File(root, "state/update-expectation-v1.json"),
        )
    }

    private fun runtime(fixture: Fixture): RuntimeOwner {
        lateinit var environments: PythonEnvironmentStore
        val runtime = PythonRuntimeSupervisor(
            context = fixture.context,
            capabilityGateway = PythonCapabilityGateway.DENY_ALL,
            callbackExecutor = DIRECT_EXECUTOR,
            descriptorBroker = AndroidPythonRuntimeDescriptorBroker(
                context = fixture.context,
                environmentArchiveProvider = PythonEnvironmentArchiveProvider { digest ->
                    environments.open(digest)
                },
            ),
        )
        environments = environmentStore(fixture, runtime)
        return RuntimeOwner(runtime, environments)
    }

    private fun environmentStore(
        fixture: Fixture,
        runtime: PythonRuntimeSupervisor,
    ): PythonEnvironmentStore = PythonEnvironmentStore(
        rootDirectory = fixture.environmentRoot,
        wheelValidator = PythonWheelArchiveValidator(
            deviceAndroidApi = Build.VERSION.SDK_INT,
            supportedAndroidAbis = Build.SUPPORTED_ABIS.toSet(),
        ),
        importSelfTester = PythonEnvironmentImportSelfTester { prepared ->
            PythonEnvironmentRuntimeSelfTester(runtime).test(prepared)
        },
    )

    private fun assertResolved(
        registry: PythonPluginEntrypointRegistry,
        pluginId: String,
        environmentDigest: String,
        sourceSha256: String,
    ) {
        val resolved = registry.resolve(pluginId, ENTRYPOINT_ID, environmentDigest)
        assertTrue(resolved is PythonPluginEntrypointResolution.Resolved)
        resolved as PythonPluginEntrypointResolution.Resolved
        // This instrumentation is intentionally installed against both a release APK and its
        // same-publisher update. Kotlin mangles internal accessors with the build-variant module
        // name (for example `$app_standardDebug` versus `$app_standardRelease`), so directly
        // invoking those accessors makes a valid release product fail with NoSuchMethodError.
        // Read the stable backing fields instead; the production values remain encapsulated and
        // the cross-variant update gate can inspect the exact persisted resolution.
        assertEquals(sourceSha256, resolved.privateStringField("sourceSha256"))
        assertEquals(ENTRYPOINT_PATH, resolved.privateStringField("relativePath"))
        assertEquals(ENTRYPOINT_FUNCTION, resolved.privateStringField("function"))
    }

    private fun Any.privateStringField(name: String): String {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this) as String
    }

    private fun PythonPluginEntrypointRegistry.proveFinalizedActivationAcrossVariant(
        pluginId: String,
        environmentDigest: String,
        metadataDigest: String,
    ): PythonPluginEntrypointFinalizedProof {
        // Internal Kotlin methods carry the producer module name in their JVM symbol. The same
        // instrumented gate intentionally targets both debug-compiled test code and release APKs,
        // so discover the one stable method by its source name and exact argument contract.
        val method = javaClass.declaredMethods.single {
            it.name.startsWith("proveFinalizedActivation$") && it.parameterCount == 3
        }
        method.isAccessible = true
        return method.invoke(this, pluginId, environmentDigest, metadataDigest)
            as PythonPluginEntrypointFinalizedProof
    }

    private fun assertExecution(
        runtime: PythonRuntimeSupervisor,
        environmentDigest: String,
        pluginId: String,
    ) {
        val completed = CountDownLatch(1)
        var result: PythonExecutionResult? = null
        runtime.execute(
            request = PythonExecutionRequest(
                requestId = "update-continuity-${UUID.randomUUID()}",
                idempotencyKey = "update-continuity-${UUID.randomUUID()}",
                environmentDigest = environmentDigest,
                entrypoint = PythonEntrypoint(
                    kind = PythonEntrypointKind.PLUGIN,
                    pluginId = pluginId,
                    relativePath = ENTRYPOINT_PATH,
                    function = ENTRYPOINT_FUNCTION,
                ),
                argumentsJson = JSONObject().put("value", 40).toString(),
                limits = PythonResourceLimits(
                    deadlineElapsedRealtimeMillis =
                        SystemClock.elapsedRealtime() + EXECUTION_TIMEOUT_MILLIS,
                ),
            ),
            streamListener = PythonStreamListener { true },
        ) {
            result = it
            completed.countDown()
        }
        assertTrue(
            "Update-continuity entrypoint execution timed out",
            completed.await(EXECUTION_TIMEOUT_MILLIS + 10_000, TimeUnit.MILLISECONDS),
        )
        assertEquals(PythonExecutionStatus.SUCCEEDED, result?.status)
        val value = JSONObject(requireNotNull(result?.valueJson))
        assertEquals(EXPECTED_ANSWER, value.getInt("answer"))
        assertEquals(RESULT_FIXTURE, value.getString("fixture"))
    }

    private fun createWheel(directory: File): File {
        val file = File(directory, "hans_update_continuity-$PACKAGE_VERSION-py3-none-any.whl")
        val distInfo = "hans_update_continuity-$PACKAGE_VERSION.dist-info"
        val entries = linkedMapOf(
            "$distInfo/METADATA" to """
                Metadata-Version: 2.1
                Name: $PACKAGE_NAME
                Version: $PACKAGE_VERSION
                Requires-Python: >=3.14

            """.trimIndent(),
            "$distInfo/WHEEL" to """
                Wheel-Version: 1.0
                Generator: Hans update-continuity instrumentation
                Root-Is-Purelib: true
                Tag: py3-none-any

            """.trimIndent(),
            "$distInfo/top_level.txt" to "hans_update_continuity\n",
            "hans_update_continuity/__init__.py" to
                "def stable_value(value):\n    return value + 2\n",
        )
        FileOutputStream(file).use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (path, content) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(content.toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
        return file
    }

    private fun packageVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    private fun packageSignerSha256(context: Context): String {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        val signing = requireNotNull(info.signingInfo)
        require(!signing.hasMultipleSigners()) { "Update fixture requires one APK signer" }
        val signers = signing.apkContentsSigners
        require(signers.size == 1) { "Update fixture requires exactly one current signer" }
        return MessageDigest.getInstance("SHA-256")
            .digest(signers.single().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun writeExclusiveState(target: File, value: JSONObject) {
        assertTrue(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
        val bytes = (value.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
        assertTrue(bytes.size in 1..MAX_STATE_BYTES)
        assertTrue("Update-continuity state must be created once", target.createNewFile())
        FileOutputStream(target, false).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private data class Fixture(
        val context: Context,
        val runId: String,
        val pluginId: String,
        val root: File,
        val environmentRoot: File,
        val registryFile: File,
        val stateFile: File,
    )

    private class RuntimeOwner(
        val runtime: PythonRuntimeSupervisor,
        val environments: PythonEnvironmentStore,
    ) : Closeable {
        override fun close() = runtime.close()
    }

    private companion object {
        const val ARG_PHASE = "hansPythonUpdatePhase"
        const val ARG_RUN_ID = "hansPythonUpdateRunId"
        const val PHASE_SEED = "seed"
        const val PHASE_VERIFY = "verify"
        const val STATE_SCHEMA = "hans.python.update-continuity.v1"
        const val ENTRYPOINT_ID = "run"
        const val ENTRYPOINT_PATH = "entry.py"
        const val ENTRYPOINT_FUNCTION = "run"
        const val PACKAGE_NAME = "hans-update-continuity"
        const val PACKAGE_VERSION = "1.0.0"
        const val EXPECTED_ANSWER = 42
        const val RESULT_FIXTURE = "same-signer-update"
        const val MAX_STATE_BYTES = 16 * 1024
        const val EXECUTION_TIMEOUT_MILLIS = 30_000L
        val DIRECT_EXECUTOR = Executor(Runnable::run)
        val NEVER_CANCELLED = object : PluginRuntimePreparationCancellation {
            override fun isCancellationRequested() = false
            override fun onCancel(action: () -> Unit) = Closeable { }
        }
    }
}
