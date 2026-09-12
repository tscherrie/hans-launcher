package ai.hans.standard.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictedSettingsRecoveryRepositoryTest {
    @Test
    fun freshEffectiveReceiptVerifiesBothRestrictedSettingsCapabilities() {
        RestrictedSettingsCapability.entries.forEach { capability ->
            val repository = repositoryAt(capability.setupStep)
            val token = repository.beginOperation(capability.setupStep)
            repository.markSettingsOpened(token)

            val verified = repository.acceptRestrictedSettingsRecovery(token)

            val record = verified.record(capability.setupStep)
            assertEquals(HansSetupStepStatus.VERIFIED, record.status)
            assertEquals("restricted_settings_recovery_verified", record.detailCode)
            assertNull(record.operationNonce)
            assertEquals(1_234L, record.verifiedAtMillis)
        }
    }

    @Test
    fun manualRequiredIsNotSuccessAndExplicitNotNowSkipsTheDependentLiveTests() {
        RestrictedSettingsCapability.entries.forEach { capability ->
            val repository = repositoryAt(capability.setupStep)
            val token = repository.beginOperation(capability.setupStep)
            repository.markSettingsOpened(token)

            val manual = repository.markRestrictedSettingsManualRequired(token)

            val manualRecord = manual.record(capability.setupStep)
            assertEquals(HansSetupStepStatus.AWAITING_USER, manualRecord.status)
            assertEquals("restricted_settings_manual_required", manualRecord.detailCode)
            assertNull(manualRecord.operationNonce)
            assertFalse(manualRecord.terminal)

            val skipped = repository.recordRestrictedSettingsNotNow(capability.setupStep)
            assertEquals(
                HansSetupStepStatus.SKIPPED,
                skipped.record(capability.setupStep).status,
            )
            val dependentSteps = when (capability) {
                RestrictedSettingsCapability.ACCESSIBILITY -> setOf(
                    HansSetupStep.ACCESSIBILITY_LIVE_TEST,
                    HansSetupStep.HARDWARE_LIVE_TEST,
                )
                RestrictedSettingsCapability.NOTIFICATION_LISTENER -> setOf(
                    HansSetupStep.NOTIFICATION_LIVE_TEST,
                )
            }
            dependentSteps.forEach { step ->
                assertEquals(HansSetupStepStatus.SKIPPED, skipped.record(step).status)
            }
        }
    }

    @Test
    fun staleOrWrongStepReceiptsCannotClaimRestrictedAccess() {
        val repository = repositoryAt(HansSetupStep.ACCESSIBILITY_ACCESS)
        val stale = repository.beginOperation(HansSetupStep.ACCESSIBILITY_ACCESS)
        val current = repository.beginOperation(HansSetupStep.ACCESSIBILITY_ACCESS)

        assertTrue(runCatching { repository.acceptRestrictedSettingsRecovery(stale) }.isFailure)
        assertEquals(
            HansSetupStepStatus.VERIFYING,
            repository.read().record(HansSetupStep.ACCESSIBILITY_ACCESS).status,
        )
        assertTrue(
            runCatching {
                repository.acceptRestrictedSettingsRecovery(
                    current.copy(step = HansSetupStep.MICROPHONE_ACCESS),
                )
            }.isFailure,
        )
        assertFalse(repository.read().record(HansSetupStep.ACCESSIBILITY_ACCESS).terminal)
    }

    private fun repositoryAt(step: HansSetupStep): HansSetupRepository = HansSetupRepository(
        storage = MemoryStorage(
            HansSetupDocument(
                started = true,
                steps = HANS_SETUP_ORDER
                    .takeWhile { it != step }
                    .associateWith {
                        HansSetupStepRecord(
                            status = HansSetupStepStatus.VERIFIED,
                            detailCode = if (it == HansSetupStep.HARDWARE_LIVE_TEST) {
                                "hardware_accessibility_start_stop_sent_verified"
                            } else {
                                "prerequisite_verified"
                            },
                        )
                    },
            ),
        ),
        clock = HansSetupClock { 1_234L },
        nonces = HansSetupNonceSource { "restricted_nonce_123456789" },
    )

    private class MemoryStorage(
        private var document: HansSetupDocument,
    ) : HansSetupStorage {
        override fun read(): HansSetupDocument = document

        override fun write(document: HansSetupDocument) {
            this.document = document
        }
    }
}
