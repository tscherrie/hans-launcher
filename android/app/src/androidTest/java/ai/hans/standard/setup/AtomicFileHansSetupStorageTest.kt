package ai.hans.standard.setup

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicFileHansSetupStorageTest {
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
