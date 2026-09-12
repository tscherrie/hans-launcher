package ai.hans.standard.phone.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionKeyDispatcherTest {
    @Test
    fun pressTogglesOnceAndDebouncesImmediateSecondPress() {
        val mapping = KeyTestFixtures.mapping()
        val dispatcher = ActionKeyDispatcher(ActionKeyMappingSet.of(listOf(mapping)))

        assertEquals(
            ActionKeyDispatchResult.AwaitingRelease(mapping.mappingId),
            dispatcher.onDeliveredEvent(KeyTestFixtures.event()),
        )
        assertEquals(
            ActionKeyIgnoreReason.REPEAT,
            (dispatcher.onDeliveredEvent(
                KeyTestFixtures.event(eventTimeMillis = 140L, repeatCount = 1),
            ) as ActionKeyDispatchResult.Ignored).reason,
        )
        assertEquals(
            ActionKeyCommand.ToggleDictation,
            (dispatcher.onDeliveredEvent(
                KeyTestFixtures.event(
                    phase = ObservableKeyPhase.UP,
                    eventTimeMillis = 180L,
                ),
            ) as ActionKeyDispatchResult.Command).command,
        )

        assertEquals(
            ActionKeyIgnoreReason.NO_MAPPING,
            (dispatcher.onDeliveredEvent(
                KeyTestFixtures.event(
                    phase = ObservableKeyPhase.UP,
                    eventTimeMillis = 180L,
                ),
            ) as ActionKeyDispatchResult.Ignored).reason,
        )
        assertEquals(
            ActionKeyIgnoreReason.DEBOUNCED,
            (dispatcher.onDeliveredEvent(
                KeyTestFixtures.event(eventTimeMillis = 300L, downTimeMillis = 300L),
            ) as ActionKeyDispatchResult.Ignored).reason,
        )
    }

    @Test
    fun holdStartsOnDownStopsOnMatchingUpAndIgnoresRepeats() {
        val mapping = KeyTestFixtures.mapping(trigger = ActionKeyTrigger.HOLD_TO_TALK)
        val dispatcher = ActionKeyDispatcher(ActionKeyMappingSet.of(listOf(mapping)))

        assertEquals(
            ActionKeyCommand.StartDictation,
            (dispatcher.onDeliveredEvent(KeyTestFixtures.event())
                as ActionKeyDispatchResult.Command).command,
        )
        assertEquals(
            ActionKeyIgnoreReason.REPEAT,
            (dispatcher.onDeliveredEvent(
                KeyTestFixtures.event(
                    eventTimeMillis = 600L,
                    repeatCount = 3,
                    isLongPress = true,
                ),
            ) as ActionKeyDispatchResult.Ignored).reason,
        )
        assertEquals(
            ActionKeyCommand.StopDictation,
            (dispatcher.onDeliveredEvent(
                KeyTestFixtures.event(
                    phase = ObservableKeyPhase.UP,
                    eventTimeMillis = 700L,
                ),
            ) as ActionKeyDispatchResult.Command).command,
        )
    }

    @Test
    fun symSemanticActionTogglesOnlyTheTwoNamedPresets() {
        val mapping = KeyTestFixtures.mapping(
            mappingId = "sym",
            scanCode = 173,
            keyCode = 63,
            action = KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA,
        )
        val dispatcher = ActionKeyDispatcher(ActionKeyMappingSet.of(listOf(mapping)))
        dispatcher.onDeliveredEvent(KeyTestFixtures.event(scanCode = 173, keyCode = 63))
        val command = dispatcher.onDeliveredEvent(
            KeyTestFixtures.event(
                phase = ObservableKeyPhase.UP,
                eventTimeMillis = 140L,
                scanCode = 173,
                keyCode = 63,
            ),
        ) as ActionKeyDispatchResult.Command

        assertEquals(ActionKeyCommand.ToggleLunaMaxSolUltra, command.command)
        assertEquals(
            HansModelPreset.ASTRA_ULTRA,
            HansModelPresetToggle.next(HansModelPreset.LUNA_MAX),
        )
        assertEquals(
            HansModelPreset.LUNA_MAX,
            HansModelPresetToggle.next(HansModelPreset.ASTRA_ULTRA),
        )
        assertEquals("gpt-6-astra", HansModelPreset.ASTRA_ULTRA.model)
        assertEquals("ultra", HansModelPreset.ASTRA_ULTRA.effort)
        assertEquals("gpt-5.6-luna", HansModelPreset.LUNA_MAX.model)
        assertEquals("max", HansModelPreset.LUNA_MAX.effort)
        // Existing serialized keyboard mappings must remain readable after the preset update.
        assertEquals(
            KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA,
            KeySemanticAction.valueOf("TOGGLE_LUNA_MAX_SOL_ULTRA"),
        )
    }

    @Test
    fun mappingsAreEditableButDuplicatePhysicalGestureIsRejected() {
        val initial = KeyTestFixtures.mapping()
        val changed = initial.copy(scanCode = 190, keyCode = 290)
        val edited = ActionKeyMappingSet.empty().upsert(initial).upsert(changed)
        assertEquals(listOf(changed), edited.mappings)
        assertTrue(edited.remove(initial.mappingId).mappings.isEmpty())

        assertThrows(IllegalArgumentException::class.java) {
            ActionKeyMappingSet.of(
                listOf(initial, initial.copy(mappingId = "other-action")),
            )
        }
    }

    @Test
    fun editingAnActiveHoldEmitsStopSoDictationCannotRemainStuck() {
        val hold = KeyTestFixtures.mapping(trigger = ActionKeyTrigger.HOLD_TO_TALK)
        val dispatcher = ActionKeyDispatcher(ActionKeyMappingSet.of(listOf(hold)))
        dispatcher.onDeliveredEvent(KeyTestFixtures.event())

        val cleanup = dispatcher.replaceMappings(ActionKeyMappingSet.empty())

        assertEquals(1, cleanup.size)
        assertEquals(ActionKeyCommand.StopDictation, cleanup.single().command)
    }
}
