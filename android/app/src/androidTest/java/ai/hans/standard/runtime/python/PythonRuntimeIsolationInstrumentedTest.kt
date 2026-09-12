package ai.hans.standard.runtime.python

import ai.hans.standard.plugins.PluginRuntimePreparationCancellation
import ai.hans.standard.plugins.PythonPluginEntrypointBinding
import ai.hans.standard.plugins.PythonRuntimePluginEntrypointProber
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonRuntimeIsolationInstrumentedTest {
    @Test
    fun signedMsgpackCompanionUsesItsApkNativeExtensionOnDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pureEnvironment = createProbeEnvironment(context)
        val target = PythonEnvironmentTarget("3.14.7", "cp314", "arm64-v8a", 31)
        val entry = AndroidPythonNativePackageCatalog.load(context)
            .candidates("msgpack", target)
            .single()
        val directory = File(context.noBackupFilesDir, "python/instrumentation/msgpack").apply {
            assertTrue(mkdirs() || isDirectory)
        }
        val archive = File(directory, "environment.pyz").apply {
            setWritable(true, true)
            entry.companionWheel.source.open().use { input ->
                outputStream().use { output -> input.copyTo(output) }
            }
            setReadable(true, true)
            setWritable(false, false)
            setExecutable(false, false)
        }
        val receipt = PythonEnvironmentArchiveReceipt(
            archive = archive,
            sha256 = PythonEnvironmentContract.sha256(archive.readBytes()),
            sizeBytes = archive.length(),
            allowedNativeModules = entry.allowedNativeModules(),
        )
        val binding = bind(context)
        val client = PythonRuntimeClient(
            runtime = binding.service,
            sessionNonce = PythonRuntimeFdContract.newSessionNonce(),
            descriptorBroker = AndroidPythonRuntimeDescriptorBroker(
                context = context,
                environmentArchiveProvider = PythonEnvironmentArchiveProvider { requested ->
                    when (requested) {
                        receipt.sha256 -> receipt
                        pureEnvironment.sha256 -> pureEnvironment
                        else -> error("Unexpected Python environment: $requested")
                    }
                },
            ),
            capabilityGateway = PythonCapabilityGateway.DENY_ALL,
            callbackExecutor = DIRECT_EXECUTOR,
        )
        try {
            val initialized = CountDownLatch(1)
            var readiness: Result<PythonRuntimeSnapshot>? = null
            client.initialize {
                readiness = it
                initialized.countDown()
            }
            assertTrue("CPython readiness timed out", initialized.await(30, TimeUnit.SECONDS))
            assertTrue(
                "CPython readiness failed: ${readiness?.exceptionOrNull()?.stackTraceToString()}",
                readiness?.isSuccess == true,
            )

            val attack = executeJsonCode(
                client = client,
                environmentDigest = pureEnvironment.sha256,
                requestId = "native-audit-bypass-probe",
                source = """
                    import _imp
                    import builtins
                    import hans_native_importer
                    import importlib
                    import importlib.machinery
                    import importlib.util
                    import json
                    import os
                    import sys
                    import types

                    standard = hans_native_importer._installed_standard_finder()
                    native_path = os.path.join(
                        standard._directory,
                        "libhans_py_msgpack___cmsgpack.so",
                    )

                    def denied(action):
                        try:
                            action()
                        except PermissionError:
                            return True
                        except BaseException:
                            return False
                        return False

                    direct_spec = importlib.util.spec_from_file_location(
                        "msgpack._cmsgpack",
                        native_path,
                        loader=importlib.machinery.ExtensionFileLoader(
                            "msgpack._cmsgpack",
                            native_path,
                        ),
                    )
                    alias_spec = importlib.util.spec_from_file_location(
                        "hans_unsigned_alias",
                        native_path,
                        loader=importlib.machinery.ExtensionFileLoader(
                            "hans_unsigned_alias",
                            native_path,
                        ),
                    )
                    arbitrary_spec = importlib.util.spec_from_file_location(
                        "hans_arbitrary_shared_object",
                        os.path.join(standard._directory, "libhans_python_jni.so"),
                        loader=importlib.machinery.ExtensionFileLoader(
                            "hans_arbitrary_shared_object",
                            os.path.join(standard._directory, "libhans_python_jni.so"),
                        ),
                    )
                    abi_suffix_path = os.path.join(
                        standard._directory,
                        "hans_unlisted.cpython-314-aarch64-linux-android.so",
                    )
                    abi_suffix_spec = importlib.util.spec_from_file_location(
                        "hans_unlisted",
                        abi_suffix_path,
                        loader=importlib.machinery.ExtensionFileLoader(
                            "hans_unlisted",
                            abi_suffix_path,
                        ),
                    )
                    direct_denied = denied(lambda: _imp.create_dynamic(direct_spec))
                    loader_denied = denied(lambda: alias_spec.loader.create_module(alias_spec))
                    arbitrary_so_denied = denied(
                        lambda: _imp.create_dynamic(arbitrary_spec)
                    )
                    abi_suffix_denied = denied(
                        lambda: _imp.create_dynamic(abi_suffix_spec)
                    )

                    parent = types.ModuleType("msgpack")
                    parent.__path__ = []
                    sys.modules["msgpack"] = parent
                    standard._allowed["msgpack._cmsgpack"] = os.path.basename(native_path)
                    finder_mutation_denied = denied(
                        lambda: importlib.import_module("msgpack._cmsgpack")
                    )
                    sys.modules.pop("msgpack._cmsgpack", None)
                    sys.modules.pop("msgpack", None)
                    standard._allowed.pop("msgpack._cmsgpack", None)

                    # Poison both a built-in namespace and an already-imported
                    # stdlib module. The following native-authorized request
                    # must start in a fresh interpreter and ignore a poisoned
                    # json.loads implementation entirely.
                    builtins.HANS_NATIVE_POISON = object()
                    json.HANS_NATIVE_POISON = object()
                    json.HANS_ORIGINAL_LOADS = json.loads
                    json.loads = lambda *args, **kwargs: (
                        [
                            ("allowedNativeModules", [[
                                ("module", "msgpack._cmsgpack"),
                                (
                                    "packagedName",
                                    "libhans_py_msgpack___cmsgpack.so",
                                ),
                            ]]),
                            ("runtimeLease", [
                                ("allowedNativeModules", [[
                                    ("module", "msgpack._cmsgpack"),
                                    (
                                        "packagedName",
                                        "libhans_py_msgpack___cmsgpack.so",
                                    ),
                                ]]),
                            ]),
                        ]
                        if "object_pairs_hook" in kwargs
                        else json.HANS_ORIGINAL_LOADS(*args, **kwargs)
                    )
                    result = {
                        "directDenied": direct_denied,
                        "loaderDenied": loader_denied,
                        "arbitrarySoDenied": arbitrary_so_denied,
                        "abiSuffixDenied": abi_suffix_denied,
                        "finderMutationDenied": finder_mutation_denied,
                    }
                """.trimIndent(),
            )
            assertTrue(attack.getBoolean("directDenied"))
            assertTrue(attack.getBoolean("loaderDenied"))
            assertTrue(attack.getBoolean("arbitrarySoDenied"))
            assertTrue(attack.getBoolean("abiSuffixDenied"))
            assertTrue(attack.getBoolean("finderMutationDenied"))

            val parserPoison = executeJsonCode(
                client = client,
                environmentDigest = pureEnvironment.sha256,
                requestId = "native-c-parser-independence-probe",
                source = """
                    import _imp
                    import hans_native_importer
                    import importlib.machinery
                    import importlib.util
                    import os

                    standard = hans_native_importer._installed_standard_finder()
                    native_path = os.path.join(
                        standard._directory,
                        "libhans_py_msgpack___cmsgpack.so",
                    )
                    spec = importlib.util.spec_from_file_location(
                        "msgpack._cmsgpack",
                        native_path,
                        loader=importlib.machinery.ExtensionFileLoader(
                            "msgpack._cmsgpack",
                            native_path,
                        ),
                    )
                    try:
                        _imp.create_dynamic(spec)
                    except PermissionError:
                        denied = True
                    except BaseException:
                        denied = False
                    else:
                        denied = False
                    result = {"parserPoisonDenied": denied}
                """.trimIndent(),
            )
            assertTrue(parserPoison.getBoolean("parserPoisonDenied"))

            val output = ByteArrayOutputStream()
            val completed = CountDownLatch(1)
            var result: PythonExecutionResult? = null
            client.execute(
                PythonExecutionRequest(
                    requestId = "msgpack-native-smoke",
                    idempotencyKey = "msgpack-native-smoke",
                    environmentDigest = receipt.sha256,
                    entrypoint = PythonEntrypoint(
                        PythonEntrypointKind.CODE,
                        """
                            import json
                            import builtins
                            import msgpack
                            packed = msgpack.packb({"answer": 42})
                            clean_before_native = (
                                not hasattr(builtins, "HANS_NATIVE_POISON")
                                and not hasattr(json, "HANS_NATIVE_POISON")
                            )
                            builtins.HANS_NATIVE_LEAK = msgpack._cmsgpack
                            json.HANS_NATIVE_LEAK = msgpack._cmsgpack
                            print(json.dumps({
                                "answer": msgpack.unpackb(packed, raw=False)["answer"],
                                "backend": msgpack.Packer.__module__,
                                "cleanBeforeNative": clean_before_native,
                            }, separators=(",", ":")))
                        """.trimIndent(),
                    ),
                    argumentsJson = "{}",
                    limits = PythonResourceLimits(SystemClock.elapsedRealtime() + 20_000),
                ),
                PythonStreamListener { chunk ->
                    if (chunk.kind == PythonStreamKind.STDOUT) output.write(chunk.payload)
                    true
                },
            ) {
                result = it
                completed.countDown()
            }
            assertTrue(completed.await(30, TimeUnit.SECONDS))
            assertEquals(PythonExecutionStatus.SUCCEEDED, result?.status)
            val json = JSONObject(output.toString(Charsets.UTF_8.name()).trim())
            assertEquals(42, json.getInt("answer"))
            assertEquals("msgpack._cmsgpack", json.getString("backend"))
            assertTrue(json.getBoolean("cleanBeforeNative"))

            val afterNative = executeJsonCode(
                client = client,
                environmentDigest = pureEnvironment.sha256,
                requestId = "native-audit-cleanup-probe",
                source = """
                    import _imp
                    import builtins
                    import hans_native_importer
                    import importlib.machinery
                    import importlib.util
                    import json
                    import os
                    import sys

                    standard = hans_native_importer._installed_standard_finder()
                    native_path = os.path.join(
                        standard._directory,
                        "libhans_py_msgpack___cmsgpack.so",
                    )
                    spec = importlib.util.spec_from_file_location(
                        "msgpack._cmsgpack",
                        native_path,
                        loader=importlib.machinery.ExtensionFileLoader(
                            "msgpack._cmsgpack",
                            native_path,
                        ),
                    )
                    try:
                        _imp.create_dynamic(spec)
                    except PermissionError:
                        audit_restored = True
                    except BaseException:
                        audit_restored = False
                    else:
                        audit_restored = False
                    result = {
                        "builtinsClean": not hasattr(builtins, "HANS_NATIVE_LEAK"),
                        "stdlibClean": not hasattr(json, "HANS_NATIVE_LEAK"),
                        "modulesClean": not any(
                            name == "msgpack" or name.startswith("msgpack.")
                            for name in sys.modules
                        ),
                        "auditRestored": audit_restored,
                    }
                """.trimIndent(),
            )
            assertTrue(afterNative.getBoolean("builtinsClean"))
            assertTrue(afterNative.getBoolean("stdlibClean"))
            assertTrue(afterNative.getBoolean("modulesClean"))
            assertTrue(afterNative.getBoolean("auditRestored"))
        } finally {
            context.unbindService(binding.connection)
        }
    }

    @Test
    fun descriptorBackedRuntimeExecutesAndRemainsIsolatedWithoutLeakingPerRunFds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val environment = createProbeEnvironment(context)
        val binding = bind(context)
        val nonce = PythonRuntimeFdContract.newSessionNonce()
        val client = PythonRuntimeClient(
            runtime = binding.service,
            sessionNonce = nonce,
            descriptorBroker = AndroidPythonRuntimeDescriptorBroker(
                context = context,
                environmentArchiveProvider = PythonEnvironmentArchiveProvider { requestedDigest ->
                    require(requestedDigest == environment.sha256)
                    environment
                },
            ),
            capabilityGateway = PythonCapabilityGateway.DENY_ALL,
            callbackExecutor = DIRECT_EXECUTOR,
        )
        try {
            val initialized = CountDownLatch(1)
            var readiness: Result<PythonRuntimeSnapshot>? = null
            client.initialize {
                readiness = it
                initialized.countDown()
            }
            assertTrue("CPython readiness timed out", initialized.await(30, TimeUnit.SECONDS))
            assertTrue(
                "CPython readiness failed: ${readiness?.exceptionOrNull()?.stackTraceToString()}",
                readiness?.isSuccess == true,
            )
            assertEquals(PythonRuntimePhase.READY, readiness?.getOrThrow()?.phase)

            val first = executeProbe(client, context.packageName, environment.sha256, "isolation-1")
            val second = executeProbe(client, context.packageName, environment.sha256, "isolation-2")

            assertEquals(PythonExecutionStatus.SUCCEEDED, first.result.status)
            assertEquals("42", first.result.valueJson)
            assertEquals(42, first.probe.getInt("answer"))
            assertTrue(first.probe.getBoolean("json"))
            assertTrue(first.probe.getBoolean("sqlite3"))
            assertTrue(first.probe.getBoolean("ssl"))
            assertTrue(first.probe.getString("stdlibOrigin").startsWith("hans-fd://stdlib/"))
            assertEquals(42, first.probe.getInt("environmentImport"))
            assertTrue(first.probe.getString("environmentOrigin").startsWith("hans-fd://environment/"))
            assertEquals(0, first.probe.getInt("unsafePythonPathCount"))
            assertTrue(first.probe.getBoolean("networkDenied"))
            assertTrue(first.probe.getBoolean("appDataDenied"))
            assertNotEquals(context.applicationInfo.uid, first.probe.getInt("uid"))
            assertEquals(first.probe.getInt("pid"), second.probe.getInt("pid"))
            assertEquals(
                "A per-execution environment descriptor leaked in the isolated worker",
                first.probe.getInt("fdCount"),
                second.probe.getInt("fdCount"),
            )

            val plugin = executePluginProbe(client, environment.sha256)
            assertEquals(PythonExecutionStatus.SUCCEEDED, plugin.status)
            val pluginValue = JSONObject(requireNotNull(plugin.valueJson))
            assertEquals(42, pluginValue.getInt("answer"))
            assertEquals("sibling", pluginValue.getString("sibling"))
            assertEquals("sibling-nested", pluginValue.getString("nested"))
            assertEquals("aab", pluginValue.getString("cycle"))
            assertEquals("package-init", pluginValue.getString("packageInit"))
            assertTrue(pluginValue.getString("origin").startsWith(
                "hans-fd://environment/__hans_plugin_source__/",
            ))
            assertTrue(pluginValue.getString("package").startsWith("_hans_plugin_d"))
            assertEquals(0, pluginValue.getInt("unsafePythonPathCount"))
            assertEquals(0, executePluginCleanupProbe(client, environment.sha256))

            val processBoundary = executeProcessBoundaryProbe(client, environment.sha256)
            assertTrue(processBoundary.toString(), processBoundary.getBoolean("osSystemDenied"))
            assertTrue(processBoundary.toString(), processBoundary.getBoolean("subprocessDenied"))
            assertTrue(processBoundary.toString(), processBoundary.getBoolean("posixSpawnDenied"))
            assertTrue(processBoundary.toString(), processBoundary.getBoolean("directNativeDenied"))
            assertTrue(processBoundary.toString(), processBoundary.getBoolean("capturedNativeDenied"))

            val proven = PythonRuntimePluginEntrypointProber(
                runtime = client,
                timeoutMillis = 20_000,
            ).prove(
                pluginId = "instrumented-plugin",
                environmentDigest = environment.sha256,
                bindings = listOf(
                    PythonPluginEntrypointBinding(
                        requirementId = "proof-only",
                        relativePath = "entry.py",
                        callableName = "proof_only",
                    ),
                ),
                cancellation = NEVER_CANCELLED,
            )
            assertEquals(setOf("proof-only"), proven)
            assertEquals(
                "Entrypoint proof leaked its request-scoped importer or module namespace",
                0,
                executePluginCleanupProbe(client, environment.sha256),
            )

            val cancelled = executeCancellationProbe(client, environment.sha256)
            assertEquals(PythonExecutionStatus.CANCELLED, cancelled.status)
            val afterCancellation = executeProbe(
                client,
                context.packageName,
                environment.sha256,
                "isolation-after-cancel",
            )
            assertEquals(PythonExecutionStatus.SUCCEEDED, afterCancellation.result.status)
            assertTrue(
                "Cancellation increased the isolated worker's descriptor count",
                afterCancellation.probe.getInt("fdCount") <= second.probe.getInt("fdCount"),
            )
            val stabilizedAfterCancellation = executeProbe(
                client,
                context.packageName,
                environment.sha256,
                "isolation-after-cancel-stabilized",
            )
            assertEquals(PythonExecutionStatus.SUCCEEDED, stabilizedAfterCancellation.result.status)
            assertTrue(
                "A repeated post-cancellation run increased the isolated worker's descriptor count",
                stabilizedAfterCancellation.probe.getInt("fdCount") <=
                    afterCancellation.probe.getInt("fdCount"),
            )

            val stopped = CountDownLatch(1)
            client.stop { snapshot ->
                assertEquals(PythonRuntimePhase.STOPPED, snapshot.phase)
                stopped.countDown()
            }
            assertTrue("Native shutdown timed out", stopped.await(30, TimeUnit.SECONDS))
        } finally {
            context.unbindService(binding.connection)
        }

        val manager = context.getSystemService(ActivityManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline &&
            manager.runningAppProcesses.orEmpty().any { it.processName == "${context.packageName}:python" }
        ) {
            Thread.sleep(100)
        }
        assertFalse(
            "Isolated Python process survived after its final binding and descriptor session closed",
            manager.runningAppProcesses.orEmpty().any {
                it.processName == "${context.packageName}:python"
            },
        )
    }

    @Test
    fun uncooperativeExecutionIsKilledAndSupervisorRebindsToAFreshIsolatedWorker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val environment = createProbeEnvironment(context)
        val descriptorBroker = AndroidPythonRuntimeDescriptorBroker(
            context = context,
            environmentArchiveProvider = PythonEnvironmentArchiveProvider { requestedDigest ->
                require(requestedDigest == environment.sha256)
                environment
            },
        )
        val supervisor = PythonRuntimeSupervisor(
            context = context,
            capabilityGateway = PythonCapabilityGateway.DENY_ALL,
            callbackExecutor = DIRECT_EXECUTOR,
            descriptorBroker = descriptorBroker,
        )
        try {
            val workerStarted = CountDownLatch(1)
            val processDied = CountDownLatch(1)
            var terminal: PythonExecutionResult? = null
            supervisor.execute(
                PythonExecutionRequest(
                    requestId = "hard-abort-probe",
                    idempotencyKey = "idem-hard-abort-probe",
                    environmentDigest = environment.sha256,
                    entrypoint = PythonEntrypoint(
                        kind = PythonEntrypointKind.CODE,
                        source = """
                            print("hard-abort-ready", flush=True)
                            while True:
                                try:
                                    while True:
                                        pass
                                except KeyboardInterrupt:
                                    pass
                        """.trimIndent(),
                    ),
                    argumentsJson = "{}",
                    limits = PythonResourceLimits(
                        // Leave enough room for a cold isolated-process bind and CPython bootstrap.
                        // The engine still has to enforce this deadline and hard-abort two seconds later.
                        deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + 3_000,
                    ),
                ),
                PythonStreamListener { chunk ->
                    if (chunk.kind == PythonStreamKind.STDOUT &&
                        chunk.payload.toString(Charsets.UTF_8).contains("hard-abort-ready")
                    ) {
                        workerStarted.countDown()
                    }
                    true
                },
            ) {
                terminal = it
                processDied.countDown()
            }
            assertTrue(
                "Uncooperative Python execution never started; terminal=$terminal",
                workerStarted.await(20, TimeUnit.SECONDS),
            )
            assertTrue("Isolated worker death was not observed", processDied.await(15, TimeUnit.SECONDS))
            assertEquals(PythonExecutionStatus.PROCESS_DIED, terminal?.status)

            val recovered = executeProbe(
                supervisor,
                context.packageName,
                environment.sha256,
                "isolation-after-process-death",
            )
            assertEquals(PythonExecutionStatus.SUCCEEDED, recovered.result.status)
            assertEquals("42", recovered.result.valueJson)
        } finally {
            supervisor.close()
        }
    }

    private fun executeJsonCode(
        client: PythonRuntimeGateway,
        environmentDigest: String,
        requestId: String,
        source: String,
    ): JSONObject {
        val completed = CountDownLatch(1)
        var result: PythonExecutionResult? = null
        client.execute(
            PythonExecutionRequest(
                requestId = requestId,
                idempotencyKey = "idem-$requestId",
                environmentDigest = environmentDigest,
                entrypoint = PythonEntrypoint(PythonEntrypointKind.CODE, source),
                argumentsJson = "{}",
                limits = PythonResourceLimits(SystemClock.elapsedRealtime() + 20_000),
            ),
            PythonStreamListener { true },
        ) {
            result = it
            completed.countDown()
        }
        assertTrue("Python JSON probe timed out", completed.await(30, TimeUnit.SECONDS))
        val terminal = requireNotNull(result)
        assertEquals(
            "Python JSON probe failed: ${terminal.errorCode}: ${terminal.errorMessage}",
            PythonExecutionStatus.SUCCEEDED,
            terminal.status,
        )
        return JSONObject(requireNotNull(terminal.valueJson))
    }

    private fun executeProbe(
        client: PythonRuntimeGateway,
        packageName: String,
        environmentDigest: String,
        requestId: String,
    ): ProbeRun {
        val output = ByteArrayOutputStream()
        val completed = CountDownLatch(1)
        var result: PythonExecutionResult? = null
        val request = PythonExecutionRequest(
            requestId = requestId,
            idempotencyKey = "idem-$requestId",
            environmentDigest = environmentDigest,
            entrypoint = PythonEntrypoint(
                kind = PythonEntrypointKind.CODE,
                source = probeSource(packageName),
            ),
            argumentsJson = "{}",
            limits = PythonResourceLimits(
                deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + 20_000,
            ),
        )
        client.execute(
            request,
            PythonStreamListener { chunk ->
                if (chunk.kind == PythonStreamKind.STDOUT) synchronized(output) {
                    output.write(chunk.payload)
                }
                true
            },
        ) {
            result = it
            completed.countDown()
        }
        assertTrue("Python execution timed out", completed.await(30, TimeUnit.SECONDS))
        val line = synchronized(output) {
            output.toString(Charsets.UTF_8.name()).lineSequence().filter(String::isNotBlank).last()
        }
        return ProbeRun(requireNotNull(result), JSONObject(line))
    }

    private fun executeCancellationProbe(
        client: PythonRuntimeClient,
        environmentDigest: String,
    ): PythonExecutionResult {
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var result: PythonExecutionResult? = null
        val handle = client.execute(
            PythonExecutionRequest(
                requestId = "cancellation-probe",
                idempotencyKey = "idem-cancellation-probe",
                environmentDigest = environmentDigest,
                entrypoint = PythonEntrypoint(
                    kind = PythonEntrypointKind.CODE,
                    source = """
                        print("cancel-ready", flush=True)
                        while True:
                            pass
                    """.trimIndent(),
                ),
                argumentsJson = "{}",
                limits = PythonResourceLimits(
                    deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + 20_000,
                ),
            ),
            PythonStreamListener { chunk ->
                if (chunk.kind == PythonStreamKind.STDOUT &&
                    chunk.payload.toString(Charsets.UTF_8).contains("cancel-ready")
                ) {
                    started.countDown()
                }
                true
            },
        ) {
            result = it
            completed.countDown()
        }
        assertTrue("Cancellable Python execution never started", started.await(20, TimeUnit.SECONDS))
        assertTrue("Native cancellation was not accepted", handle.cancel())
        assertTrue("Cancelled Python execution did not finish", completed.await(10, TimeUnit.SECONDS))
        return requireNotNull(result)
    }

    private fun executePluginProbe(
        client: PythonRuntimeGateway,
        environmentDigest: String,
    ): PythonExecutionResult {
        val completed = CountDownLatch(1)
        var result: PythonExecutionResult? = null
        client.execute(
            PythonExecutionRequest(
                requestId = "plugin-package-probe",
                idempotencyKey = "idem-plugin-package-probe",
                environmentDigest = environmentDigest,
                entrypoint = PythonEntrypoint(
                    kind = PythonEntrypointKind.PLUGIN,
                    pluginId = "instrumented-plugin",
                    relativePath = "entry.py",
                    function = "run",
                ),
                argumentsJson = JSONObject().put("value", 21).toString(),
                limits = PythonResourceLimits(
                    deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + 20_000,
                ),
            ),
            PythonStreamListener { true },
        ) {
            result = it
            completed.countDown()
        }
        assertTrue("Multi-file plugin execution timed out", completed.await(30, TimeUnit.SECONDS))
        return requireNotNull(result)
    }

    private fun executePluginCleanupProbe(
        client: PythonRuntimeGateway,
        environmentDigest: String,
    ): Int {
        val completed = CountDownLatch(1)
        var result: PythonExecutionResult? = null
        client.execute(
            PythonExecutionRequest(
                requestId = "plugin-cleanup-${SystemClock.elapsedRealtime()}",
                idempotencyKey = "idem-plugin-cleanup-${SystemClock.elapsedRealtime()}",
                environmentDigest = environmentDigest,
                entrypoint = PythonEntrypoint(
                    kind = PythonEntrypointKind.CODE,
                    source = """
                        import sys
                        result = sum(1 for name in sys.modules if name.startswith("_hans_plugin_d")) + sum(
                            1 for finder in sys.meta_path
                            if type(finder).__name__ == "HansPluginSourceFinder"
                        )
                    """.trimIndent(),
                ),
                argumentsJson = "{}",
                limits = PythonResourceLimits(
                    deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + 20_000,
                ),
            ),
            PythonStreamListener { true },
        ) {
            result = it
            completed.countDown()
        }
        assertTrue("Plugin cleanup probe timed out", completed.await(30, TimeUnit.SECONDS))
        val terminal = requireNotNull(result)
        assertEquals(PythonExecutionStatus.SUCCEEDED, terminal.status)
        return requireNotNull(terminal.valueJson).toInt()
    }

    private fun executeProcessBoundaryProbe(
        client: PythonRuntimeGateway,
        environmentDigest: String,
    ): JSONObject {
        val completed = CountDownLatch(1)
        var result: PythonExecutionResult? = null
        client.execute(
            PythonExecutionRequest(
                requestId = "process-boundary-probe",
                idempotencyKey = "idem-process-boundary-probe",
                environmentDigest = environmentDigest,
                entrypoint = PythonEntrypoint(
                    kind = PythonEntrypointKind.CODE,
                    source = """
                        import _posixsubprocess
                        import os
                        import subprocess

                        def denied(action):
                            try:
                                action()
                            except PermissionError:
                                return True
                            except BaseException:
                                return False
                            return False

                        if not hasattr(os, "posix_spawn"):
                            # CPython's Android build omits this primitive,
                            # which is an even narrower boundary than denial.
                            posix_spawn_denied = True
                            posix_spawn_error = "absent"
                        else:
                            try:
                                os.posix_spawn("/system/bin/true", ["true"], {})
                            except BaseException as error:
                                posix_spawn_denied = isinstance(error, PermissionError)
                                posix_spawn_error = type(error).__name__ + ":" + str(error)
                            else:
                                posix_spawn_denied = False
                                posix_spawn_error = "none"

                        result = {
                            "osSystemDenied": denied(lambda: os.system("/system/bin/true")),
                            "subprocessDenied": denied(
                                lambda: subprocess.run(["/system/bin/true"], check=True)
                            ),
                            "posixSpawnDenied": posix_spawn_denied,
                            "posixSpawnError": posix_spawn_error,
                            "directNativeDenied": denied(
                                lambda: _posixsubprocess.fork_exec()
                            ),
                            "capturedNativeDenied": denied(
                                lambda: subprocess._fork_exec()
                            ),
                        }
                    """.trimIndent(),
                ),
                argumentsJson = "{}",
                limits = PythonResourceLimits(
                    deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + 20_000,
                ),
            ),
            PythonStreamListener { true },
        ) {
            result = it
            completed.countDown()
        }
        assertTrue("Process boundary probe timed out", completed.await(30, TimeUnit.SECONDS))
        val terminal = requireNotNull(result)
        assertEquals(PythonExecutionStatus.SUCCEEDED, terminal.status)
        return JSONObject(requireNotNull(terminal.valueJson))
    }

    private fun probeSource(packageName: String): String = """
        import json
        import hans_environment_probe
        import os
        import socket
        import sqlite3
        import ssl
        import sys

        network_denied = False
        sock = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(0.2)
            sock.connect(("127.0.0.1", 9))
        except PermissionError:
            network_denied = True
        except OSError as error:
            network_denied = getattr(error, "errno", None) in (1, 13)
        finally:
            if sock is not None:
                sock.close()

        app_data_denied = False
        try:
            os.listdir("/data/user/0/$packageName")
        except (PermissionError, FileNotFoundError):
            app_data_denied = True

        result = 21 * 2
        print(json.dumps({
            "answer": result,
            "uid": os.getuid(),
            "pid": os.getpid(),
            "fdCount": len(os.listdir("/proc/self/fd")),
            "environmentImport": hans_environment_probe.VALUE,
            "environmentOrigin": hans_environment_probe.__file__,
            "stdlibOrigin": json.__file__,
            "unsafePythonPathCount": sum(1 for path in sys.path if path.startswith("/proc/self/fd/") or path.startswith("/data/user/")),
            "networkDenied": network_denied,
            "appDataDenied": app_data_denied,
            "json": json is not None,
            "sqlite3": sqlite3 is not None,
            "ssl": ssl is not None
        }, separators=(",", ":")))
    """.trimIndent()

    private fun createProbeEnvironment(context: Context): PythonEnvironmentArchiveReceipt {
        val directory = File(context.noBackupFilesDir, "python/instrumentation")
        assertTrue(directory.mkdirs() || directory.isDirectory)
        val archive = File(directory, "environment.pyz")
        archive.setWritable(true, true)
        val sources = sortedMapOf(
            "hans_environment_probe.py" to "VALUE = 42\n",
            "__hans_plugin_source__/__init__.py" to "ROOT_INITIALIZED = True\n",
            "__hans_plugin_source__/sibling.py" to "VALUE = 'sibling'\n",
            "__hans_plugin_source__/nested/__init__.py" to
                "from .worker import nested_value\n",
            "__hans_plugin_source__/nested/worker.py" to
                "from .. import sibling\n\ndef nested_value():\n    return sibling.VALUE + '-nested'\n",
            "__hans_plugin_source__/cycle_a.py" to
                "TOKEN = 'a'\nfrom . import cycle_b\n\ndef cycle_value():\n    return TOKEN + cycle_b.TOKEN\n",
            "__hans_plugin_source__/cycle_b.py" to
                "from . import cycle_a\nTOKEN = cycle_a.TOKEN + 'b'\n",
            "__hans_plugin_source__/package/__init__.py" to
                "PACKAGE_VALUE = 'package-init'\n",
            "__hans_plugin_source__/entry.py" to """
                from . import sibling
                from .cycle_a import cycle_value
                from .nested.worker import nested_value
                from .package import PACKAGE_VALUE
                import sys

                def run(value):
                    return {
                        "answer": value * 2,
                        "sibling": sibling.VALUE,
                        "nested": nested_value(),
                        "cycle": cycle_value(),
                        "packageInit": PACKAGE_VALUE,
                        "origin": __file__,
                        "package": __package__,
                        "unsafePythonPathCount": sum(
                            1 for path in sys.path
                            if path.startswith("/proc/self/fd/")
                            or path.startswith("/data/user/")
                            or "__hans_plugin_source__" in path
                        ),
                    }

                def proof_only():
                    raise AssertionError("entrypoint prober invoked the callable")
            """.trimIndent() + "\n",
        )
        val file = FileOutputStream(archive)
        val zip = ZipOutputStream(file)
        try {
            sources.forEach { (name, text) ->
                val source = text.toByteArray()
                val crc = CRC32().apply { update(source) }
                zip.putNextEntry(
                    ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    time = 315_532_800_000L
                    size = source.size.toLong()
                    compressedSize = source.size.toLong()
                    this.crc = crc.value
                    },
                )
                zip.write(source)
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
            file.fd.sync()
        } finally {
            zip.close()
        }
        archive.setReadable(true, true)
        archive.setWritable(false, false)
        archive.setExecutable(false, false)
        return PythonEnvironmentArchiveReceipt(
            archive = archive,
            sha256 = PythonEnvironmentContract.sha256(archive.readBytes()),
            sizeBytes = archive.length(),
        )
    }

    private fun bind(context: Context): Binding {
        val connected = CountDownLatch(1)
        var service: IPythonRuntimeService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IPythonRuntimeService.Stub.asInterface(binder)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(
            context.bindService(
                Intent(context, PythonRuntimeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )
        assertTrue("Isolated Python service did not bind", connected.await(15, TimeUnit.SECONDS))
        return Binding(requireNotNull(service), connection)
    }

    private data class Binding(
        val service: IPythonRuntimeService,
        val connection: ServiceConnection,
    )

    private data class ProbeRun(
        val result: PythonExecutionResult,
        val probe: JSONObject,
    )

    private companion object {
        val DIRECT_EXECUTOR = Executor(Runnable::run)
        val NEVER_CANCELLED = object : PluginRuntimePreparationCancellation {
            override fun isCancellationRequested() = false
            override fun onCancel(action: () -> Unit) = Closeable {}
        }
    }
}
