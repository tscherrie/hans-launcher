package ai.hans.standard.diagnostics

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HansMigrationDiagnosticsAndroidContractTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun manifestPinsDumpExportedPrivateProcessAndNoIntentFilter() {
        val component = ComponentName(context, HansMigrationDiagnosticsReceiver::class.java)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getReceiverInfo(component, 0)

        assertTrue(info.exported)
        assertEquals(Manifest.permission.DUMP, info.permission)
        assertEquals("${context.packageName}:migrationDiagnostics", info.processName)
        @Suppress("DEPRECATION")
        val implicit = context.packageManager.queryBroadcastReceivers(
            Intent(MigrationDiagnosticsContract.ACTION).setPackage(context.packageName),
            0,
        )
        assertFalse(implicit.any { it.activityInfo.name == component.className })
    }

    @Test
    fun realDumpAuthorizedShellMalformedPathFailsClosedWithoutReadinessMutation() {
        val readiness = File(context.noBackupFilesDir, MigrationSessionReadinessPublisher.FILE_NAME)
        val existed = readiness.exists()
        val bytes = readiness.takeIf(File::isFile)?.readBytes()
        val modified = readiness.takeIf(File::isFile)?.lastModified()
        val runtimeBefore = executeShell("pidof ${context.packageName}:runtime").trim()

        val output = executeShell(
            listOf(
                "am",
                "broadcast",
                "--user",
                "current",
                "--include-stopped-packages",
                "-n",
                "${context.packageName}/.diagnostics.HansMigrationDiagnosticsReceiver",
                "-a",
                MigrationDiagnosticsContract.ACTION,
                "--ei",
                MigrationDiagnosticsContract.EXTRA_PROTOCOL_VERSION,
                "1",
                "--es",
                MigrationDiagnosticsContract.EXTRA_REQUEST_ID,
                "not-a-uuid",
                "--es",
                MigrationDiagnosticsContract.EXTRA_PHASE,
                "preflight",
            ).joinToString(" "),
        )
        val result = parseConstantResult(output)

        assertEquals(Activity.RESULT_CANCELED, result.first)
        assertEquals(MigrationDiagnosticsContract.RESULT_MALFORMED, result.second)
        assertEquals(runtimeBefore, executeShell("pidof ${context.packageName}:runtime").trim())
        assertEquals(existed, readiness.exists())
        if (bytes != null) {
            assertTrue(bytes.contentEquals(readiness.readBytes()))
            assertEquals(modified, readiness.lastModified())
        }
    }

    @Test
    fun actualOrdinaryApplicationUidCannotInvokeDumpProtectedReceiver() {
        val ordinaryPackage = instrumentation.context.packageName
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(Manifest.permission.DUMP, ordinaryPackage),
        )
        val token = "migration-probe-${System.nanoTime()}"
        executeShell(
            listOf(
                "am",
                "start",
                "--user",
                "current",
                "-W",
                "-n",
                "$ordinaryPackage/${OrdinaryUidMigrationDiagnosticsProbeActivity::class.java.name}",
                "--es",
                OrdinaryUidMigrationDiagnosticsProbeActivity.EXTRA_PROBE_TOKEN,
                token,
            ).joinToString(" "),
        )

        val log = awaitProbe(token)
        assertTrue(log, log.contains("$token:DENIED_"))
    }

    @Test
    fun boundedPreferencesParserRunsOnAndroidAndRejectsEntities() {
        val valid = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
              <int name="other" value="7" />
              <string name="thread_id">thread&amp;one</string>
            </map>
        """.trimIndent().toByteArray()
        assertEquals("thread&one", readFixedSharedPreferenceString(valid, "thread_id"))
        val entity = """
            <!DOCTYPE map [<!ENTITY xxe SYSTEM "file:///data/local/tmp/secret">]>
            <map><string name="thread_id">&xxe;</string></map>
        """.trimIndent().toByteArray()
        var failed = false
        try {
            readFixedSharedPreferenceString(entity, "thread_id")
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

    private fun parseConstantResult(output: String): Pair<Int, String> {
        val match = Regex(
            """(?m)^Broadcast completed: result=(-?\d+), data="([a-z0-9._:-]+)"$""",
        ).find(output) ?: error("Missing bounded ordered-broadcast result in: $output")
        return match.groupValues[1].toInt() to match.groupValues[2]
    }

    private fun awaitProbe(token: String): String {
        repeat(50) {
            val output = executeShell(
                "logcat -d -s ${OrdinaryUidMigrationDiagnosticsProbeActivity.LOG_TAG}:I *:S",
            )
            if (output.contains("$token:")) return output
            SystemClock.sleep(100)
        }
        error("Ordinary-UID migration probe did not report a result for $token")
    }

    private fun executeShell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
}
