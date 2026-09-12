package ai.hans.standard.setup

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import ai.hans.standard.LauncherActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HansSetupHandoffAndroidContractTest {
    @Test
    fun explicitTypedReceiverIntentParsesAndMalformedVariantsFailClosed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, HansSetupHandoffReceiver::class.java)
        val id = "84e3b844-47da-42eb-b8f0-bda209b8dd78"
        val valid = validReceiverIntent(context, id)

        assertEquals(
            HansSetupHandoffCommand(1, id, HansSetupHandoffReason.INSTALL),
            HansSetupHandoffContract.parse(valid, component),
        )
        assertNull(HansSetupHandoffContract.parse(Intent(valid).setComponent(null), component))
        assertNull(
            HansSetupHandoffContract.parse(
                Intent(valid).setComponent(ComponentName(context, LauncherActivity::class.java)),
                component,
            ),
        )
        assertNull(
            HansSetupHandoffContract.parse(
                Intent(valid).putExtra(HansSetupHandoffContract.EXTRA_PROTOCOL_VERSION, 2),
                component,
            ),
        )
        assertNull(
            HansSetupHandoffContract.parse(
                Intent(valid).putExtra(HansSetupHandoffContract.EXTRA_HANDOFF_ID, "invalid"),
                component,
            ),
        )
        assertNull(
            HansSetupHandoffContract.parse(
                Intent(valid).putExtra(
                    HansSetupHandoffContract.EXTRA_HANDOFF_ID,
                    id.uppercase(),
                ),
                component,
            ),
        )
        assertNull(
            HansSetupHandoffContract.parse(
                Intent(valid).putExtra(HansSetupHandoffContract.EXTRA_REASON, "root"),
                component,
            ),
        )
        assertNull(
            HansSetupHandoffContract.parse(
                Intent(valid).putExtra("unexpected", "field"),
                component,
            ),
        )
    }

    @Test
    fun manifestRequiresDumpPermissionAndRealOrdinaryAppUidIsDenied() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val productionFile = productionFile(context)
        deleteAtomicFile(productionFile)
        val receiverInfo = context.packageManager.getReceiverInfo(
            ComponentName(context, HansSetupHandoffReceiver::class.java),
            0,
        )
        assertEquals(Manifest.permission.DUMP, receiverInfo.permission)
        assertTrue(receiverInfo.exported)
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.DUMP),
        )
        val handoffId = "9981d495-9bbb-4ef4-9c5b-7ddb76b48df4"
        val ordinaryPackage = instrumentation.context.packageName
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(Manifest.permission.DUMP, ordinaryPackage),
        )
        val probeToken = "probe-${System.nanoTime()}"
        try {
            val launchOutput = executeShell(
                listOf(
                    "am",
                    "start",
                    "--user",
                    "current",
                    "-W",
                    "-n",
                    "$ordinaryPackage/${OrdinaryUidHandoffProbeActivity::class.java.name}",
                    "--es",
                    OrdinaryUidHandoffProbeActivity.EXTRA_PROBE_TOKEN,
                    probeToken,
                    "--es",
                    HansSetupHandoffContract.EXTRA_HANDOFF_ID,
                    handoffId,
                ).joinToString(" "),
            )
            val probeResult = awaitProbeResult(probeToken)

            assertTrue(launchOutput, launchOutput.contains("Status: ok"))
            assertTrue(probeResult, probeResult.contains("$probeToken:DENIED_"))
            assertTrue(AtomicFileHansSetupHandoffStorage(context).read().records.isEmpty())
        } finally {
            deleteAtomicFile(productionFile)
        }
    }

    @Test
    fun exportedHomeActivityNeverAcceptsInstallerAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val productionFile = productionFile(context)
        deleteAtomicFile(productionFile)
        val oldUnsafeIntent = Intent(HansSetupHandoffContract.ACTION_CONTINUE_SETUP)
            .setComponent(ComponentName(context, LauncherActivity::class.java))
            .putExtra(HansSetupHandoffContract.EXTRA_PROTOCOL_VERSION, 1)
            .putExtra(
                HansSetupHandoffContract.EXTRA_HANDOFF_ID,
                "c9547ca7-3e6d-4de8-b881-4bf7c9c55bba",
            )
            .putExtra(HansSetupHandoffContract.EXTRA_REASON, "install")
        try {
            ActivityScenario.launch<LauncherActivity>(oldUnsafeIntent).use {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertTrue(AtomicFileHansSetupHandoffStorage(context).read().records.isEmpty())
            }
        } finally {
            deleteAtomicFile(productionFile)
        }
    }

    @Test
    fun directNonOrderedReceiverDeliveryCannotRegisterHandoff() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val productionFile = productionFile(context)
        deleteAtomicFile(productionFile)
        try {
            HansSetupHandoffReceiver().onReceive(
                context,
                validReceiverIntent(context, "77b11775-a0c0-488e-b0cf-86bfa8c9f86f"),
            )

            assertTrue(AtomicFileHansSetupHandoffStorage(context).read().records.isEmpty())
        } finally {
            deleteAtomicFile(productionFile)
        }
    }

    @Test
    fun realShellBroadcastReturnsBoundAckAndDuplicateCreatesOneReceipt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val productionFile = productionFile(context)
        deleteAtomicFile(productionFile)
        val handoffId = "457d5bda-052f-4e70-b1a6-d95074fa43a3"
        val command = HansSetupHandoffCommand(1, handoffId, HansSetupHandoffReason.INSTALL)
        val expectedAck = HansSetupHandoffAcknowledgement.create(
            command,
            HansSetupHandoffAckStatus.ACCEPTED,
        )
        val expectedDuplicateAck = HansSetupHandoffAcknowledgement.create(
            command,
            HansSetupHandoffAckStatus.DUPLICATE,
        )
        val shellCommand = canonicalShellBroadcast(context, handoffId, "install")
        try {
            val firstOutput = executeShell(shellCommand)
            val duplicateOutput = executeShell(shellCommand)
            val launcherOutput = executeShell(canonicalShellLauncherStart(context))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertShellAck(firstOutput, expectedAck)
            assertShellAck(duplicateOutput, expectedDuplicateAck)
            assertTrue(launcherOutput.contains("Status: ok"))
            val records = AtomicFileHansSetupHandoffStorage(context).read().records
                .filter { it.command.handoffId == handoffId }
            assertEquals(1, records.size)
            assertEquals(HansSetupHandoffPhase.QUEUED, records.single().phase)
            assertEquals(command.clientMessageId(), records.single().clientUserMessageId)
        } finally {
            finishLauncherActivities()
            deleteAtomicFile(productionFile)
        }
    }

    @Test
    fun realShellMalformedBroadcastFailsClosedWithoutReceipt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val productionFile = productionFile(context)
        deleteAtomicFile(productionFile)
        val malformed = canonicalShellBroadcast(
            context,
            "not-a-uuid",
            "install",
        )
        try {
            val output = executeShell(malformed)

            val acknowledgement = parseShellAck(output)
            assertEquals(0, acknowledgement.resultCode)
            assertEquals(HansSetupHandoffReceiver.RESULT_MALFORMED, acknowledgement.resultData)
            assertTrue(AtomicFileHansSetupHandoffStorage(context).read().records.isEmpty())
        } finally {
            deleteAtomicFile(productionFile)
        }
    }

    @Test
    fun atomicStorageDeduplicatesConcurrentCoordinatorInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "handoff-concurrent-${System.nanoTime()}.json")
        val request = HansSetupHandoffCommand(
            1,
            "8dd37fcf-f6e6-44e9-9774-b19a00a58bf9",
            HansSetupHandoffReason.REPAIR,
        )
        try {
            val first = HansSetupHandoffCoordinator(AtomicFileHansSetupHandoffStorage(context, file))
            val second = HansSetupHandoffCoordinator(AtomicFileHansSetupHandoffStorage(context, file))
            val threads = listOf(first, second).map { coordinator ->
                Thread { coordinator.register(request) }
            }
            threads.forEach(Thread::start)
            threads.forEach(Thread::join)

            assertEquals(1, AtomicFileHansSetupHandoffStorage(context, file).read().records.size)
        } finally {
            deleteAtomicFile(file)
        }
    }

    private fun validReceiverIntent(context: Context, id: String): Intent =
        Intent(HansSetupHandoffContract.ACTION_CONTINUE_SETUP)
            .setComponent(ComponentName(context, HansSetupHandoffReceiver::class.java))
            .putExtra(HansSetupHandoffContract.EXTRA_PROTOCOL_VERSION, 1)
            .putExtra(HansSetupHandoffContract.EXTRA_HANDOFF_ID, id)
            .putExtra(HansSetupHandoffContract.EXTRA_REASON, "install")

    private fun canonicalShellBroadcast(
        context: Context,
        id: String,
        reason: String,
    ): String = listOf(
        "am",
        "broadcast",
        "--user",
        "current",
        "--include-stopped-packages",
        "-n",
        "${context.packageName}/.setup.HansSetupHandoffReceiver",
        "-a",
        HansSetupHandoffContract.ACTION_CONTINUE_SETUP,
        "--ei",
        HansSetupHandoffContract.EXTRA_PROTOCOL_VERSION,
        HansSetupHandoffContract.PROTOCOL_VERSION.toString(),
        "--es",
        HansSetupHandoffContract.EXTRA_HANDOFF_ID,
        id,
        "--es",
        HansSetupHandoffContract.EXTRA_REASON,
        reason,
    ).joinToString(" ")

    private fun canonicalShellLauncherStart(context: Context): String = listOf(
        "am",
        "start",
        "--user",
        "current",
        "-W",
        "-n",
        "${context.packageName}/.LauncherActivity",
        "-a",
        Intent.ACTION_MAIN,
    ).joinToString(" ")

    private fun assertShellAck(
        output: String,
        acknowledgement: HansSetupHandoffAcknowledgement,
    ) {
        val parsed = parseShellAck(output)
        assertEquals(output, Activity.RESULT_OK, parsed.resultCode)
        assertEquals(output, acknowledgement.resultData, parsed.resultData)
    }

    private fun parseShellAck(output: String): ParsedShellAck {
        val match = Regex(
            """Broadcast completed: result=(-?\d+), data=\"([^\"]*)\"""",
        ).find(output) ?: error("Missing exact ordered-broadcast result data in: $output")
        return ParsedShellAck(
            resultCode = match.groupValues[1].toInt(),
            resultData = match.groupValues[2],
        )
    }

    private fun HansSetupHandoffCommand.clientMessageId(): String =
        HansSetupHandoffRecord.messageIdFor(handoffId)

    private fun executeShell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private fun awaitProbeResult(probeToken: String): String {
        repeat(50) {
            val output = executeShell(
                "logcat -d -s ${OrdinaryUidHandoffProbeActivity.LOG_TAG}:I *:S",
            )
            if (output.contains("$probeToken:")) return output
            SystemClock.sleep(100)
        }
        error("Ordinary-UID handoff probe did not report a result for $probeToken")
    }

    private fun finishLauncherActivities() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.entries
                .flatMap { stage -> monitor.getActivitiesInStage(stage) }
                .filterIsInstance<LauncherActivity>()
                .distinct()
                .forEach(LauncherActivity::finishAndRemoveTask)
        }
        instrumentation.waitForIdleSync()
    }

    private fun productionFile(context: Context): File = File(
        context.noBackupFilesDir,
        AtomicFileHansSetupHandoffStorage.FILE_NAME,
    )

    private fun deleteAtomicFile(file: File) {
        file.delete()
        File(file.path + ".bak").delete()
        File(file.path + ".new").delete()
    }

    private data class ParsedShellAck(
        val resultCode: Int,
        val resultData: String,
    )

}
