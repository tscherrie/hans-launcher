package ai.hans.standard.backup

import ai.hans.standard.settings.HansSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HansBackupCoordinatorTest {
    @Test
    fun `preview reports conflicts and import requires exact explicit confirmation`() {
        val current = payload(profile = "Alt", voice = "fable")
        val imported = payload(profile = "Neu", voice = "nova")
        val gateway = FakeAtomicGateway(current)
        val coordinator = coordinator(gateway)
        val preview = coordinator.prepareImport(
            HansBackupDocumentCodec.encode(imported, 10L),
        )

        assertTrue(preview.settingsChanged)
        assertTrue(preview.confirmedProfileWillChange)
        assertTrue(preview.dispatchSelectionWillBeStaged)
        assertThrows(HansBackupException::class.java) {
            coordinator.confirmImport(preview.confirmationToken, explicitUserConfirmation = false)
        }

        val secondPreview = coordinator.prepareImport(
            HansBackupDocumentCodec.encode(imported, 10L),
        )
        val result = coordinator.confirmImport(
            secondPreview.confirmationToken,
            explicitUserConfirmation = true,
        )

        assertTrue(result.imported)
        assertEquals("gpt-5.6-terra", result.requestedModel)
        assertEquals("high", result.requestedReasoningEffort)
        assertEquals(HansSettings.DEFAULT_SERVICE_TIER, result.requestedServiceTier)
        assertEquals("Neu", gateway.state.confirmedProfileSummary)
        assertEquals("nova", gateway.state.settings.voice)
        // Effective dispatch remains unchanged until the normal App Server-proven path runs.
        assertEquals("gpt-5.6-luna", gateway.state.settings.model)
        assertEquals("max", gateway.state.settings.reasoningEffort)
    }

    @Test
    fun `state change after preview invalidates confirmation`() {
        val gateway = FakeAtomicGateway(payload())
        val coordinator = coordinator(gateway)
        val preview = coordinator.prepareImport(
            HansBackupDocumentCodec.encode(payload(profile = "Import"), 10L),
        )
        gateway.state = payload(profile = "Concurrent edit")

        val failure = assertThrows(HansBackupException::class.java) {
            coordinator.confirmImport(preview.confirmationToken, true)
        }

        assertEquals("backup_state_changed", failure.errorCode)
        assertEquals("Concurrent edit", gateway.state.confirmedProfileSummary)
    }

    @Test
    fun `failed atomic gateway publishes none of the imported state`() {
        val before = payload(profile = "Vorher", voice = "fable")
        val gateway = FakeAtomicGateway(before).apply { failNextReplace = true }
        val coordinator = coordinator(gateway)
        val preview = coordinator.prepareImport(
            HansBackupDocumentCodec.encode(payload(profile = "Nachher", voice = "nova"), 10L),
        )

        assertThrows(HansBackupException::class.java) {
            coordinator.confirmImport(preview.confirmationToken, true)
        }

        assertEquals(before, gateway.state)
        assertFalse(gateway.recoveryPending)
    }

    private fun coordinator(gateway: FakeAtomicGateway) = HansBackupCoordinator(
        gateway = gateway,
        clock = BackupClock { 10L },
        tokens = BackupConfirmationTokenSource { "abcdefghijklmnopqrstuvwxyz123456" },
    )

    private fun payload(
        profile: String? = "Profil",
        voice: String = "fable",
    ) = HansBackupPayload(
        settings = HansSettings(
            model = if (voice == "nova") "gpt-5.6-terra" else "gpt-5.6-luna",
            reasoningEffort = if (voice == "nova") "high" else "max",
            voice = voice,
        ),
        confirmedProfileSummary = profile,
        automations = emptyList(),
        plugins = emptyList(),
        skills = emptyList(),
    )

    private class FakeAtomicGateway(initial: HansBackupPayload) : HansBackupStateGateway {
        var state = initial
        var failNextReplace = false
        var recoveryPending = false

        override fun snapshot(): HansBackupPayload = state

        override fun replaceAll(payload: HansBackupPayload) {
            val before = state
            recoveryPending = true
            if (failNextReplace) {
                failNextReplace = false
                state = payload.copy(confirmedProfileSummary = "partial")
                state = before
                recoveryPending = false
                error("injected")
            }
            state = payload
            recoveryPending = false
        }

        override fun recoverInterruptedImport(): Boolean {
            val hadPending = recoveryPending
            recoveryPending = false
            return hadPending
        }
    }
}
