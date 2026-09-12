package ai.hans.standard.phone.keys

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundModelKeyRouterTest {
    @Test
    fun ownsSymDownRepeatsAndChangedMetaUpButEmitsOnlyOneCompletedPress() {
        val router = configuredRouter()
        val down = symDown()
        assertTrue(router.onDeliveredEvent(down) is ActionKeyDispatchResult.AwaitingRelease)
        assertNotNull(router.onDeliveredEvent(down))
        assertNotNull(router.onDeliveredEvent(down.copy(eventTimeMillis = 140L, repeatCount = 1)))
        assertEquals(
            ActionKeyCommand.ToggleLunaMaxSolUltra,
            (router.onDeliveredEvent(symUp(down)) as ActionKeyDispatchResult.Command).command,
        )
        assertNull(router.onDeliveredEvent(symUp(down)))
    }

    @Test
    fun debouncedPressOwnsItsWholeStreamWithoutTogglingThenLaterPressWorks() {
        val router = configuredRouter()
        val first = symDown()
        router.onDeliveredEvent(first)
        assertTrue(router.onDeliveredEvent(symUp(first)) is ActionKeyDispatchResult.Command)

        val tooSoon = symDown(200L)
        val debounced = router.onDeliveredEvent(tooSoon) as ActionKeyDispatchResult.Ignored
        assertEquals(ActionKeyIgnoreReason.DEBOUNCED, debounced.reason)
        assertNotNull(router.onDeliveredEvent(tooSoon.copy(eventTimeMillis = 220L, repeatCount = 1)))
        assertEquals(debounced, router.onDeliveredEvent(symUp(tooSoon)))

        val later = symDown(700L)
        assertNotNull(router.onDeliveredEvent(later))
        assertTrue(router.onDeliveredEvent(symUp(later)) is ActionKeyDispatchResult.Command)
    }

    @Test
    fun unconfiguredKeysDictationAndNewChordOrOtherDevicePassThrough() {
        val empty = ForegroundModelKeyRouter()
        assertNull(empty.onDeliveredEvent(symDown()))
        val router = configuredRouter()
        router.replaceMappings(ActionKeyMappingSet.of(listOf(modelMapping(), KeyTestFixtures.mapping())))
        listOf(
            KeyTestFixtures.event(),
            symDown().copy(keyCode = KeyEvent.KEYCODE_A),
            symDown().copy(physicalDevice = KeyTestFixtures.OTHER_DEVICE),
            symDown().copy(metaState = KeyEvent.META_SYM_ON or KeyEvent.META_ALT_ON),
            symDown().copy(source = 0x102),
            symDown().copy(scanCode = 250),
            symDown().copy(repeatCount = 1),
            symDown().copy(isLongPress = true),
            symUp(symDown()),
        ).forEach { assertNull(router.onDeliveredEvent(it)) }
    }

    @Test
    fun unchangedModelPolicyRefreshPreservesOwnershipIncludingUnrelatedMappingChanges() {
        val router = configuredRouter()
        val down = symDown()
        router.onDeliveredEvent(down)
        router.replaceMappings(ActionKeyMappingSet.of(listOf(modelMapping())))
        router.replaceMappings(ActionKeyMappingSet.of(listOf(modelMapping(), KeyTestFixtures.mapping())))
        assertTrue(router.onDeliveredEvent(symUp(down)) is ActionKeyDispatchResult.Command)
    }

    @Test
    fun focusLossOrChangedModelMappingClearsOldPressAndDoesNotClaimOrphanUp() {
        val router = configuredRouter()
        val first = symDown()
        router.onDeliveredEvent(first)
        router.clearActivePresses()
        assertNull(router.onDeliveredEvent(symUp(first)))

        val second = symDown(700L)
        router.onDeliveredEvent(second)
        router.replaceMappings(ActionKeyMappingSet.of(listOf(modelMapping().copy(scanCode = 250))))
        assertNull(router.onDeliveredEvent(symUp(second)))
        assertNull(router.onDeliveredEvent(symDown(1_000L)))
        val replacement = symDown(1_000L).copy(scanCode = 250)
        assertNotNull(router.onDeliveredEvent(replacement))
        assertTrue(router.onDeliveredEvent(symUp(replacement)) is ActionKeyDispatchResult.Command)

        router.replaceMappings(ActionKeyMappingSet.empty())
        assertNull(router.onDeliveredEvent(symDown(1_500L)))
    }

    @Test
    fun acceptedTailRequiresSamePhysicalIdentityAndDownTime() {
        val router = configuredRouter()
        val down = symDown()
        router.onDeliveredEvent(down)
        val up = symUp(down)
        listOf(
            up.copy(physicalDevice = KeyTestFixtures.OTHER_DEVICE),
            up.copy(source = 0x102),
            up.copy(scanCode = 250),
            up.copy(keyCode = KeyEvent.KEYCODE_A),
            up.copy(downTimeMillis = down.downTimeMillis + 1L),
        ).forEach { assertNull(router.onDeliveredEvent(it)) }
        assertTrue(router.onDeliveredEvent(up) is ActionKeyDispatchResult.Command)
    }

    private fun configuredRouter() = ForegroundModelKeyRouter().apply {
        replaceMappings(ActionKeyMappingSet.of(listOf(modelMapping())))
    }

    private fun modelMapping() = KeyTestFixtures.mapping(
        mappingId = "model-toggle",
        scanCode = 249,
        keyCode = KeyEvent.KEYCODE_SYM,
        metaState = KeyEvent.META_SYM_ON,
        action = KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA,
    )

    private fun symDown(at: Long = 100L) = KeyTestFixtures.event(
        eventTimeMillis = at,
        downTimeMillis = at,
        scanCode = 249,
        keyCode = KeyEvent.KEYCODE_SYM,
        metaState = KeyEvent.META_SYM_ON,
    )

    private fun symUp(down: ObservableAndroidKeyEvent) = down.copy(
        phase = ObservableKeyPhase.UP,
        eventTimeMillis = down.eventTimeMillis + 80L,
        metaState = 0,
    )
}
