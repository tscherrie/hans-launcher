package ai.hans.standard.phone.keys

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskVoiceInputControlsTest {
    @Test fun allProductionOwnersUseTheSameEffectiveGestureIncludingVendorConfirmation() {
        fun source(relative: String) = sequenceOf(File("src/main/java/$relative"),
            File("android/app/src/main/java/$relative")).first(File::isFile).readText()
        val activity = source("ai/hans/standard/LauncherActivity.kt")
        val confirmation = activity.substringAfter("private fun confirmMp01VendorActionCleared()")
            .substringBefore("private fun acceptActionKeyCapture(")
        assertTrue(confirmation.contains("mappings = mappings.forTaskVoiceControls()"))
        val refresh = activity.substringAfter("private fun refreshMp01VendorActionConflict(")
            .substringBefore("private fun openMp01VendorSettings()")
        assertTrue(refresh.contains("val voiceMappings = mappings.forTaskVoiceControls()"))
        assertTrue(refresh.contains("mappings = voiceMappings"))
        val global = source("ai/hans/standard/phone/keys/AndroidGlobalActionKeyAccessibilityController.kt")
        assertTrue(global.contains("val voiceMappings = latestMappings.forTaskVoiceControls()"))
        assertTrue(global.contains("mappings = voiceMappings"))
        assertTrue(source("ai/hans/standard/setup/HansSetupRuntime.kt")
            .contains(".read().forTaskVoiceControls().mappings"))
    }

    @Test fun legacyHoldMapsToPressWithoutMutatingItsStoredAssignmentOrModelShortcut() {
        val voice = KeyTestFixtures.mapping(trigger = ActionKeyTrigger.HOLD_TO_TALK)
        val model = KeyTestFixtures.mapping(mappingId = "model", scanCode = 173, keyCode = 63,
            action = KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA)
        val stored = ActionKeyMappingSet.of(listOf(voice, model))
        val effective = stored.forTaskVoiceControls()
        assertEquals(voice, stored.mappings.first { it.mappingId == voice.mappingId })
        assertEquals(voice.copy(trigger = ActionKeyTrigger.PRESS),
            effective.mappings.first { it.mappingId == voice.mappingId })
        assertEquals(model, effective.mappings.first { it.mappingId == model.mappingId })
        assertEquals(effective.mappings, effective.forTaskVoiceControls().mappings)
        assertTrue(ActionKeyMappingSet.empty().forTaskVoiceControls().mappings.isEmpty())
    }

    @Test fun upgradedHoldNeverStartsOnDownOrStopsOnRelease() {
        val stored = ActionKeyMappingSet.of(listOf(
            KeyTestFixtures.mapping(trigger = ActionKeyTrigger.HOLD_TO_TALK),
        ))
        val dispatcher = ActionKeyDispatcher(stored.forTaskVoiceControls())
        assertTrue(dispatcher.onDeliveredEvent(KeyTestFixtures.event()) is
            ActionKeyDispatchResult.AwaitingRelease)
        val result = dispatcher.onDeliveredEvent(KeyTestFixtures.event(
            phase = ObservableKeyPhase.UP, eventTimeMillis = 180,
        )) as ActionKeyDispatchResult.Command
        assertEquals(ActionKeyCommand.ToggleDictation, result.command)
    }
}
