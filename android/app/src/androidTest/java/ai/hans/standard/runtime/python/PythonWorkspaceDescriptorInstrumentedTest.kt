package ai.hans.standard.runtime.python

import ai.hans.standard.workspace.PrivateWorkspaceStore
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonWorkspaceDescriptorInstrumentedTest {
    @Test
    fun selectedWorkspaceIsReadableAndImportableButNeverWritableInIsolatedPython() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.noBackupFilesDir, "python/workspace-test/${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        val store = PrivateWorkspaceStore(File(root, "snapshots"), root)
        val snapshot = store.begin().use { transaction ->
            transaction.write("message.txt") { it.write("hello".toByteArray()) }
            transaction.write("project.py") { it.write("VALUE = 42\n".toByteArray()) }
            transaction.commit()
        }
        val runtime = PythonRuntimeSupervisor(
            context = context,
            capabilityGateway = PythonCapabilityGateway.DENY_ALL,
            callbackExecutor = Executor(Runnable::run),
            descriptorBroker = AndroidPythonRuntimeDescriptorBroker(
                context = context,
                workspaceArchiveProvider = PrivateWorkspacePythonArchiveProvider(
                    store,
                    File(root, "archives"),
                    root,
                ),
            ),
        )
        try {
            val completed = CountDownLatch(1)
            var result: PythonExecutionResult? = null
            runtime.execute(
                PythonExecutionRequest(
                    requestId = "workspace-e2e",
                    idempotencyKey = "idem-workspace-e2e",
                    environmentDigest = PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST,
                    entrypoint = PythonEntrypoint(
                        kind = PythonEntrypointKind.CODE,
                        source = """
                            import hans_workspace
                            import project
                            try:
                                hans_workspace.open('message.txt', 'w')
                                write_denied = False
                            except PermissionError:
                                write_denied = True
                            result = {
                                'message': hans_workspace.read_text('message.txt'),
                                'answer': project.VALUE,
                                'origin': project.__file__,
                                'writeDenied': write_denied,
                            }
                        """.trimIndent(),
                    ),
                    argumentsJson = "{}",
                    workspaceHandle = snapshot.handle.value,
                    limits = PythonResourceLimits(
                        deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + 30_000,
                    ),
                ),
                PythonStreamListener { true },
            ) {
                result = it
                completed.countDown()
            }
            assertTrue("Workspace execution timed out", completed.await(45, TimeUnit.SECONDS))
            assertEquals(PythonExecutionStatus.SUCCEEDED, result?.status)
            val value = JSONObject(requireNotNull(result?.valueJson))
            assertEquals("hello", value.getString("message"))
            assertEquals(42, value.getInt("answer"))
            assertTrue(value.getBoolean("writeDenied"))
            assertTrue(value.getString("origin").startsWith("hans-workspace://"))
            assertEquals(
                "hello",
                store.openFile(snapshot.handle, "message.txt").bufferedReader().use { it.readText() },
            )
        } finally {
            runtime.close()
            root.deleteRecursively()
        }
    }
}
