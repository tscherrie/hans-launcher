package ai.hans.standard.ui

import ai.hans.standard.phone.keys.ActionKeyTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HansUiModelsTest {
    @Test
    fun composerCanSendOnlyNonBlankEnabledText() {
        assertFalse(ComposerUiState(text = "").canSend)
        assertFalse(ComposerUiState(text = "   \n").canSend)
        assertFalse(ComposerUiState(text = "Hallo", enabled = false).canSend)
        assertTrue(ComposerUiState(text = "Hallo").canSend)
    }

    @Test
    fun appStartsBehindCheckingAuthGate() {
        val state = HansUiState()

        assertEquals(HansDestination.AUTH_GATE, state.destination)
        assertEquals(AuthGateStage.CHECKING, state.authGate.stage)
    }

    @Test
    fun deviceCodeIsCompleteOnlyWithCodeAndVerificationPage() {
        assertFalse(AuthGateUiState().hasCompleteDeviceCode)
        assertFalse(AuthGateUiState(userCode = "ABCD-EFGH").hasCompleteDeviceCode)
        assertFalse(
            AuthGateUiState(verificationUri = "https://auth.openai.com/device").hasCompleteDeviceCode,
        )
        assertTrue(
            AuthGateUiState(
                userCode = "ABCD-EFGH",
                verificationUri = "https://auth.openai.com/device",
            ).hasCompleteDeviceCode,
        )
    }

    @Test
    fun authGateRepresentsEveryRuntimePhase() {
        assertEquals(
            listOf("CHECKING", "SIGNED_OUT", "DEVICE_CODE_AWAITING", "COMPLETING", "ERROR"),
            AuthGateStage.entries.map { it.name },
        )
    }

    @Test
    fun modelChoicesUseRuntimeIdsWithoutImplicitSelection() {
        assertEquals(
            listOf("gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol", "gpt-6-astra"),
            ModelUiOption.HANS_MODELS.map { it.id },
        )
        assertEquals(
            ai.hans.standard.settings.HansSettings.MODEL_ORDER,
            ModelUiOption.HANS_MODELS.map { it.id },
        )
        assertEquals("Astra", ModelUiOption.HANS_MODELS.last().label)
        assertNull(SettingsUiState().selectedModelId)
    }

    @Test
    fun reasoningChoicesCoverEverySupportedEffortInOrder() {
        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max", "ultra"),
            ReasoningEffortUiOption.entries.map { it.id },
        )
        assertNull(SettingsUiState().selectedReasoningEffortId)
    }

    @Test
    fun speechRateUsesCompactGermanLabels() {
        assertEquals("1,0×", formatSpeechRate(1f))
        assertEquals("1,25×", formatSpeechRate(1.25f))
        assertEquals("1,5×", formatSpeechRate(1.5f))
        assertEquals("2,0×", formatSpeechRate(2f))
    }

    @Test
    fun timelineRevisionCanAdvanceWithoutReplacingStreamedMessage() {
        val message = ChatMessageUiModel(
            id = "answer",
            author = ChatMessageAuthor.HANS,
            text = "Erster Teil",
        )
        val original = ChatUiState(messages = listOf(message), timelineRevision = 1)
        val advanced = original.copy(
            messages = listOf(message.copy(text = "Erster Teil. Zweiter Teil", revision = 1)),
            timelineRevision = 2,
        )

        assertEquals("answer", advanced.messages.single().id)
        assertEquals(2L, advanced.timelineRevision)
        assertEquals(1L, advanced.messages.single().revision)
    }

    @Test
    fun idleHardwareFocusKeepsFirstPrintableAndAltResolvedCodePoint() {
        assertEquals("a", printableCodePoint('a'.code))
        assertEquals("€", printableCodePoint('€'.code))
        assertEquals("🧪", printableCodePoint(0x1F9EA))
        assertNull(printableCodePoint(0))
        assertNull(printableCodePoint('\n'.code))
    }

    @Test
    fun stockMp01SystemPolicyNeedsCompatibleShortPressNotStaleConfirmation() {
        val incompatible = Mp01VendorActionConflictUiState(
            detected = true,
            replacementConfirmed = true,
            kind = Mp01VendorActionConflictUiKind.STOCK_SYSTEM_POLICY,
        )
        val compatible = incompatible.copy(stockPressToggleCompatible = true)

        assertFalse(incompatible.userRemediable)
        assertTrue(incompatible.replacementRequired)
        assertFalse(compatible.userRemediable)
        assertFalse(compatible.replacementRequired)
    }

    @Test
    fun legacyMp01AccessibilityConflictRemainsUserRemediable() {
        val pending = Mp01VendorActionConflictUiState(
            detected = true,
            replacementConfirmed = false,
            kind = Mp01VendorActionConflictUiKind.LEGACY_ACCESSIBILITY,
        )
        val confirmed = pending.copy(replacementConfirmed = true)

        assertTrue(pending.userRemediable)
        assertTrue(pending.replacementRequired)
        assertFalse(confirmed.replacementRequired)
    }

    @Test
    fun dictationInputControlsDefaultToToggleAndCameraOnly() {
        val settings = SettingsUiState()
        val chat = ChatUiState()

        assertEquals(ActionKeyTrigger.PRESS, settings.actionKey.dictationTrigger)
        assertFalse(settings.cameraHoldToTalkEnabled)
        assertFalse(chat.cameraHoldToTalkEnabled)
    }
}
