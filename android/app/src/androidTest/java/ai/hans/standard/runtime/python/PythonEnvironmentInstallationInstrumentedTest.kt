package ai.hans.standard.runtime.python

import ai.hans.standard.BuildConfig
import ai.hans.standard.plugins.PluginRuntimePreparationCancellation
import ai.hans.standard.plugins.PythonPluginEntrypointBinding
import ai.hans.standard.plugins.PythonRuntimePluginEntrypointProber
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the complete signed-runtime path from a pure wheel to a callable plugin on Android. */
@RunWith(AndroidJUnit4::class)
class PythonEnvironmentInstallationInstrumentedTest {
    @Test
    fun pureWheelAndPluginSourceAreProvenCommittedAndExecutedInTheIsolatedWorker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(
            context.noBackupFilesDir,
            "python/instrumentation/${UUID.randomUUID()}",
        )
        assertTrue(root.mkdirs())

        lateinit var environments: PythonEnvironmentStore
        val runtime = PythonRuntimeSupervisor(
            context = context,
            capabilityGateway = PythonCapabilityGateway.DENY_ALL,
            callbackExecutor = DIRECT_EXECUTOR,
            descriptorBroker = AndroidPythonRuntimeDescriptorBroker(
                context = context,
                environmentArchiveProvider = PythonEnvironmentArchiveProvider { digest ->
                    environments.open(digest)
                },
            ),
        )
        environments = PythonEnvironmentStore(
            rootDirectory = File(root, "environments"),
            wheelValidator = PythonWheelArchiveValidator(
                deviceAndroidApi = Build.VERSION.SDK_INT,
                supportedAndroidAbis = Build.SUPPORTED_ABIS.toSet(),
            ),
            importSelfTester = PythonEnvironmentImportSelfTester { prepared ->
                PythonEnvironmentRuntimeSelfTester(runtime).test(prepared)
            },
        )

        var receipt: PythonEnvironmentInstallReceipt? = null
        var finalized = false
        try {
            val wheel = createWheel(root)
            val source = File(root, "source").apply {
                assertTrue(mkdirs())
                File(this, "entry.py").writeText(
                    """
                    from hans_e2e import plus_one

                    def run(value):
                        return {"answer": plus_one(value)}
                    """.trimIndent(),
                    StandardCharsets.UTF_8,
                )
            }
            val pin = PythonWheelPin(
                packageName = PACKAGE_NAME,
                version = PACKAGE_VERSION,
                fileName = wheel.name,
                sha256 = PythonEnvironmentContract.sha256(wheel.readBytes()),
                sizeBytes = wheel.length(),
                sourceUri = "file://${wheel.name}",
                requiresPython = ">=3.14",
            )
            val target = PythonEnvironmentTarget(
                pythonVersion = BuildConfig.PYTHON_RUNTIME_VERSION,
                interpreterTag = "cp314",
                androidAbi = BuildConfig.PYTHON_RUNTIME_ABI,
                minimumAndroidApi = 31,
            )
            val lock = PythonEnvironmentLock(
                schemaVersion = PythonEnvironmentContract.LOCK_SCHEMA_VERSION,
                pluginId = PLUGIN_ID,
                target = target,
                wheels = listOf(pin),
                sourceSha256 = PythonEnvironmentSourceDigest.digest(source),
            )

            val preparedReceipt = environments.prepareInstall(
                PythonEnvironmentInstallRequest(
                    lock = lock,
                    offlineWheels = PythonOfflineWheelSet.of(mapOf(pin.sha256 to wheel)),
                    pluginSourceDirectory = source,
                ),
            )
            receipt = preparedReceipt
            assertEquals(
                PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST,
                environments.digestFor(PLUGIN_ID),
            )

            val proven = PythonRuntimePluginEntrypointProber(
                runtime = runtime,
                timeoutMillis = 30_000,
            ).prove(
                pluginId = PLUGIN_ID,
                environmentDigest = preparedReceipt.environmentDigest,
                bindings = listOf(
                    PythonPluginEntrypointBinding(
                        requirementId = ENTRYPOINT_ID,
                        relativePath = "entry.py",
                        callableName = "run",
                    ),
                ),
                cancellation = NEVER_CANCELLED,
            )
            assertEquals(setOf(ENTRYPOINT_ID), proven)

            environments.commitActivation(preparedReceipt)
            environments.finalizeActivation(preparedReceipt)
            finalized = true
            assertEquals(preparedReceipt.environmentDigest, environments.digestFor(PLUGIN_ID))

            val completed = CountDownLatch(1)
            var result: PythonExecutionResult? = null
            runtime.execute(
                request = PythonExecutionRequest(
                    requestId = "environment-install-e2e",
                    idempotencyKey = "environment-install-e2e",
                    environmentDigest = preparedReceipt.environmentDigest,
                    entrypoint = PythonEntrypoint(
                        kind = PythonEntrypointKind.PLUGIN,
                        pluginId = PLUGIN_ID,
                        relativePath = "entry.py",
                        function = "run",
                    ),
                    argumentsJson = JSONObject().put("value", 41).toString(),
                    limits = PythonResourceLimits(
                        deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + 30_000,
                    ),
                ),
                streamListener = PythonStreamListener { true },
            ) {
                result = it
                completed.countDown()
            }
            assertTrue("Installed plugin execution timed out", completed.await(40, TimeUnit.SECONDS))
            assertEquals(PythonExecutionStatus.SUCCEEDED, result?.status)
            assertEquals(42, JSONObject(requireNotNull(result?.valueJson)).getInt("answer"))
        } finally {
            if (!finalized) receipt?.let { runCatching { environments.rollbackActivation(it) } }
            runtime.close()
            root.deleteRecursively()
        }
    }

    private fun createWheel(directory: File): File {
        val file = File(directory, "hans_e2e-$PACKAGE_VERSION-py3-none-any.whl")
        val distInfo = "hans_e2e-$PACKAGE_VERSION.dist-info"
        val entries = linkedMapOf(
            "$distInfo/METADATA" to """
                Metadata-Version: 2.1
                Name: $PACKAGE_NAME
                Version: $PACKAGE_VERSION
                Requires-Python: >=3.14

            """.trimIndent(),
            "$distInfo/WHEEL" to """
                Wheel-Version: 1.0
                Generator: Hans Android instrumentation
                Root-Is-Purelib: true
                Tag: py3-none-any

            """.trimIndent(),
            "$distInfo/top_level.txt" to "hans_e2e\n",
            "hans_e2e/__init__.py" to "def plus_one(value):\n    return value + 1\n",
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

    private companion object {
        const val PLUGIN_ID = "instrumented-environment"
        const val ENTRYPOINT_ID = "run"
        const val PACKAGE_NAME = "hans-e2e"
        const val PACKAGE_VERSION = "1.0.0"
        val DIRECT_EXECUTOR = Executor(Runnable::run)
        val NEVER_CANCELLED = object : PluginRuntimePreparationCancellation {
            override fun isCancellationRequested() = false
            override fun onCancel(action: () -> Unit) = Closeable { }
        }
    }
}
