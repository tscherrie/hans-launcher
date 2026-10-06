package ai.hans.standard.setup

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicFileHansSetupStorageTest {
    @Test
    fun persistedRetiredVoiceOperationsResumePastOldUiAndKeepOtherProgress() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.noBackupFilesDir, "setup-migration-test-${System.nanoTime()}.json")
        val storage = AtomicFileHansSetupStorage(context, file)
        try {
            HANS_SETUP_RETIRED_STEPS.forEach { retired ->
                val before = HANS_SETUP_ORDER.takeWhile { it != HansSetupStep.MODEL_REASONING }
                    .associateWith { HansSetupStepRecord(HansSetupStepStatus.VERIFIED,
                        detailCode = "existing_grant_verified") }
                val legacy = HansSetupDocument(revision = 5, started = true, currentStep = retired,
                    inputChoice = HansSetupInputChoice.HARDWARE_HOLD, cameraHoldEnabled = true,
                    steps = before + (retired to HansSetupStepRecord(HansSetupStepStatus.VERIFYING,
                        3, "retired_operation_nonce_123456", "operation_requested", 10, null, true, true)),
                    effectiveModel = "gpt-6-astra", effectiveReasoningEffort = "medium")
                storage.write(legacy)
                assertEquals(legacy, storage.read()) // Every historical enum remains decodable.
                val resumed = HansSetupRepository(storage).startOrResume()
                assertEquals(HansSetupStep.MODEL_REASONING, resumed.currentStep)
                assertEquals(HansSetupStepStatus.SKIPPED, resumed.record(retired).status)
                assertEquals(null, resumed.record(retired).operationNonce)
                assertEquals(before, resumed.steps - retired)
                assertEquals(legacy.inputChoice, resumed.inputChoice)
                assertEquals(legacy.cameraHoldEnabled, resumed.cameraHoldEnabled)
                assertEquals(legacy.effectiveModel, resumed.effectiveModel)
                assertEquals(resumed, storage.read()) // Migration persists at explicit resume.
                assertEquals(resumed, HansSetupRepository(storage).startOrResume())
                assertTrue(runCatching { HansSetupRepository(storage).beginOperation(retired) }.isFailure)
            }
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
            File(file.path + ".new").delete()
        }
    }

    @Test
    fun roundTripLivesUnderNoBackupAndPreservesOperationReceipt() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.noBackupFilesDir, "setup-test-${System.nanoTime()}.json")
        val storage = AtomicFileHansSetupStorage(context, file)
        val document = HansSetupDocument(
            revision = 4,
            started = true,
            currentStep = HansSetupStep.NOTIFICATION_LIVE_TEST,
            steps = mapOf(
                HansSetupStep.NOTIFICATION_LIVE_TEST to HansSetupStepRecord(
                    status = HansSetupStepStatus.VERIFYING,
                    generation = 3,
                    operationNonce = "setup_nonce_123456789",
                    detailCode = "operation_requested",
                    requestedAtMillis = 42,
                ),
            ),
            inputChoice = HansSetupInputChoice.NO_HARDWARE_KEY,
            cameraHoldEnabled = false,
            updatedAtMillis = 42,
        )
        try {
            storage.write(document)
            assertEquals(document, storage.read())
            assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
            File(file.path + ".new").delete()
        }
    }
}
