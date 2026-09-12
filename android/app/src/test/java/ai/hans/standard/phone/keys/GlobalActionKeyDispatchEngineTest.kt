package ai.hans.standard.phone.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalActionKeyDispatchEngineTest {
    @Test
    fun inactiveFilterAndForeignOrPartialStreamsAlwaysPassThrough() {
        val engine = GlobalActionKeyDispatchEngine()

        assertFalse(engine.hasMappings())
        assertFalse(engine.requiresFrameworkFiltering())
        assertFalse(engine.onDeliveredEvent(KeyTestFixtures.event()).consume)

        engine.replaceMappings(
            ActionKeyMappingSet.of(
                listOf(
                    KeyTestFixtures.mapping(
                        action = KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA,
                    ),
                ),
            ),
        )
        assertFalse(engine.hasMappings())
        assertFalse(engine.requiresFrameworkFiltering())
        assertFalse(engine.onDeliveredEvent(KeyTestFixtures.event()).consume)
    }

    @Test
    fun consumesOnlyExactUserMappedDictationPressAndOwnsBothHalves() {
        val mapping = KeyTestFixtures.mapping()
        val engine = GlobalActionKeyDispatchEngine(ActionKeyMappingSet.of(listOf(mapping)))

        assertFalse(engine.onDeliveredEvent(KeyTestFixtures.event(scanCode = 999)).consume)
        assertFalse(engine.onDeliveredEvent(KeyTestFixtures.event(source = 0x201)).consume)
        assertFalse(
            engine.onDeliveredEvent(
                KeyTestFixtures.event(physicalDevice = KeyTestFixtures.OTHER_DEVICE),
            ).consume,
        )

        val down = engine.onDeliveredEvent(KeyTestFixtures.event())
        assertTrue(down.consume)
        assertTrue(down.commands.isEmpty())

        val repeat = engine.onDeliveredEvent(
            KeyTestFixtures.event(
                eventTimeMillis = 130,
                repeatCount = 1,
                metaState = android.view.KeyEvent.META_ALT_ON,
            ),
        )
        assertTrue(repeat.consume)
        assertTrue(repeat.commands.isEmpty())

        val up = engine.onDeliveredEvent(
            KeyTestFixtures.event(phase = ObservableKeyPhase.UP, eventTimeMillis = 180),
        )
        assertTrue(up.consume)
        assertEquals(listOf(ActionKeyCommand.ToggleDictation), up.commands)

        // Hans did not consume a second DOWN, so a duplicate/orphaned UP is passed through.
        assertFalse(
            engine.onDeliveredEvent(
                KeyTestFixtures.event(phase = ObservableKeyPhase.UP, eventTimeMillis = 190),
            ).consume,
        )
    }

    @Test
    fun repeatOrLongPressWithoutAnOwnedInitialDownPassesThrough() {
        val engine = GlobalActionKeyDispatchEngine(
            ActionKeyMappingSet.of(listOf(KeyTestFixtures.mapping())),
        )

        assertFalse(
            engine.onDeliveredEvent(
                KeyTestFixtures.event(eventTimeMillis = 130, repeatCount = 1),
            ).consume,
        )
        assertFalse(
            engine.onDeliveredEvent(
                KeyTestFixtures.event(eventTimeMillis = 500, isLongPress = true),
            ).consume,
        )
        assertFalse(
            engine.onDeliveredEvent(
                KeyTestFixtures.event(
                    phase = ObservableKeyPhase.UP,
                    eventTimeMillis = 550,
                ),
            ).consume,
        )
    }

    @Test
    fun modelToggleMappingIsNeverClaimedByGlobalAccessibilityFilter() {
        val toggle = KeyTestFixtures.mapping(
            action = KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA,
        )
        val engine = GlobalActionKeyDispatchEngine(ActionKeyMappingSet.of(listOf(toggle)))

        assertFalse(engine.hasMappings())
        assertFalse(engine.onDeliveredEvent(KeyTestFixtures.event()).consume)
    }

    @Test
    fun removingActiveHoldStopsRecordingAndClearsReleaseOwnership() {
        val hold = KeyTestFixtures.mapping(trigger = ActionKeyTrigger.HOLD_TO_TALK)
        val engine = GlobalActionKeyDispatchEngine(ActionKeyMappingSet.of(listOf(hold)))

        val down = engine.onDeliveredEvent(KeyTestFixtures.event())
        assertTrue(down.consume)
        assertEquals(listOf(ActionKeyCommand.StartDictation), down.commands)

        val removed = engine.replaceMappings(ActionKeyMappingSet.empty())
        assertEquals(listOf(ActionKeyCommand.StopDictation), removed.commands)
        assertFalse(engine.hasMappings())
        assertTrue(engine.requiresFrameworkFiltering())
        val release = engine.onDeliveredEvent(
            KeyTestFixtures.event(phase = ObservableKeyPhase.UP, eventTimeMillis = 700),
        )
        assertTrue(release.consume)
        assertTrue(release.commands.isEmpty())
        assertFalse(engine.requiresFrameworkFiltering())
    }

    @Test
    fun reconfigurationKeepsOldReleaseOwnedWithoutExecutingTheOldPress() {
        val old = KeyTestFixtures.mapping(mappingId = "dictation")
        val replacement = KeyTestFixtures.mapping(
            mappingId = "dictation",
            scanCode = 999,
            keyCode = 281,
        )
        val engine = GlobalActionKeyDispatchEngine(ActionKeyMappingSet.of(listOf(old)))

        assertTrue(engine.onDeliveredEvent(KeyTestFixtures.event()).consume)
        engine.replaceMappings(ActionKeyMappingSet.of(listOf(replacement)))
        assertTrue(engine.requiresFrameworkFiltering())

        val oldRepeat = engine.onDeliveredEvent(
            KeyTestFixtures.event(eventTimeMillis = 130, repeatCount = 1),
        )
        val oldUp = engine.onDeliveredEvent(
            KeyTestFixtures.event(phase = ObservableKeyPhase.UP, eventTimeMillis = 180),
        )
        assertTrue(oldRepeat.consume)
        assertTrue(oldUp.consume)
        assertTrue(oldUp.commands.isEmpty())

        val replacementDown = engine.onDeliveredEvent(
            KeyTestFixtures.event(
                eventTimeMillis = 400,
                downTimeMillis = 400,
                scanCode = 999,
                keyCode = 281,
            ),
        )
        val replacementUp = engine.onDeliveredEvent(
            KeyTestFixtures.event(
                phase = ObservableKeyPhase.UP,
                eventTimeMillis = 450,
                downTimeMillis = 400,
                scanCode = 999,
                keyCode = 281,
            ),
        )
        assertTrue(replacementDown.consume)
        assertTrue(replacementUp.consume)
        assertEquals(listOf(ActionKeyCommand.ToggleDictation), replacementUp.commands)
    }

    @Test
    fun debouncedMappedPressStillSuppressesACompleteDownUpPair() {
        val engine = GlobalActionKeyDispatchEngine(
            ActionKeyMappingSet.of(listOf(KeyTestFixtures.mapping())),
        )
        engine.onDeliveredEvent(KeyTestFixtures.event())
        engine.onDeliveredEvent(
            KeyTestFixtures.event(phase = ObservableKeyPhase.UP, eventTimeMillis = 180),
        )

        val down = engine.onDeliveredEvent(
            KeyTestFixtures.event(eventTimeMillis = 300, downTimeMillis = 300),
        )
        val up = engine.onDeliveredEvent(
            KeyTestFixtures.event(
                phase = ObservableKeyPhase.UP,
                eventTimeMillis = 320,
                downTimeMillis = 300,
            ),
        )
        assertTrue(down.consume)
        assertTrue(up.consume)
        assertTrue(down.commands.isEmpty())
        assertTrue(up.commands.isEmpty())
    }

    @Test
    fun identicalEventTimeRegatePreservesDownOwnershipUntilToggleRelease() {
        val mapping = KeyTestFixtures.mapping()
        val set = ActionKeyMappingSet.of(listOf(mapping))
        val engine = GlobalActionKeyDispatchEngine(set)

        val down = engine.onDeliveredEvent(KeyTestFixtures.event())
        assertTrue(down.consume)

        // The Android MP01 controller re-probes vendor state before both DOWN
        // and UP. Replacing an identical set must not orphan the accepted DOWN.
        engine.replaceMappings(ActionKeyMappingSet.of(listOf(mapping.copy())))
        val up = engine.onDeliveredEvent(
            KeyTestFixtures.event(phase = ObservableKeyPhase.UP, eventTimeMillis = 180),
        )

        assertTrue(up.consume)
        assertEquals(listOf(ActionKeyCommand.ToggleDictation), up.commands)
    }
}
