package ai.hans.standard.phone.keys

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionKeyCaptureStateMachineTest {
    @Test
    fun completedPressCapturesOnlyDeliveredPhysicalEvidence() {
        val machine = ActionKeyCaptureStateMachine()
        val request = KeyTestFixtures.request()
        machine.begin(request)

        machine.onDeliveredEvent(request.sessionId, KeyTestFixtures.event())
        val completed = machine.onDeliveredEvent(
            request.sessionId,
            KeyTestFixtures.event(
                phase = ObservableKeyPhase.UP,
                eventTimeMillis = 180L,
            ),
        ) as CaptureEventResult.StateChanged
        val state = completed.state as ActionKeyCaptureState.Completed

        assertEquals(KeyTestFixtures.DEVICE.selector(), state.mapping.device)
        assertEquals(172, state.mapping.scanCode)
        assertEquals(280, state.mapping.keyCode)
        assertEquals(0, state.mapping.metaState)
        assertEquals(ActionKeyTrigger.PRESS, state.mapping.trigger)
        assertEquals(KeySemanticAction.DICTATION, state.mapping.action)

        val duplicateBegin = machine.begin(request)
        assertTrue(duplicateBegin is ActionKeyCaptureState.Completed)
        assertEquals(
            CaptureIgnoreReason.NOT_CAPTURING,
            (machine.onDeliveredEvent(request.sessionId, KeyTestFixtures.event())
                as CaptureEventResult.Ignored).reason,
        )
    }

    @Test
    fun longPressRepeatIsEvidenceButNeverCreatesASecondCapture() {
        val machine = ActionKeyCaptureStateMachine(minimumHoldMillis = 450L)
        val request = KeyTestFixtures.request(trigger = ActionKeyTrigger.HOLD_TO_TALK)
        machine.begin(request)
        machine.onDeliveredEvent(request.sessionId, KeyTestFixtures.event())

        val repeat = machine.onDeliveredEvent(
            request.sessionId,
            KeyTestFixtures.event(
                eventTimeMillis = 560L,
                repeatCount = 1,
                isLongPress = true,
            ),
        ) as CaptureEventResult.StateChanged
        assertTrue((repeat.state as ActionKeyCaptureState.Capturing).longPressObserved)

        val complete = machine.onDeliveredEvent(
            request.sessionId,
            KeyTestFixtures.event(
                phase = ObservableKeyPhase.UP,
                eventTimeMillis = 620L,
            ),
        ) as CaptureEventResult.StateChanged
        assertTrue(complete.state is ActionKeyCaptureState.Completed)
    }

    @Test
    fun shortHoldIsRejectedRatherThanSilentlyBecomingPress() {
        val machine = ActionKeyCaptureStateMachine(minimumHoldMillis = 450L)
        val request = KeyTestFixtures.request(trigger = ActionKeyTrigger.HOLD_TO_TALK)
        machine.begin(request)
        machine.onDeliveredEvent(request.sessionId, KeyTestFixtures.event())
        val result = machine.onDeliveredEvent(
            request.sessionId,
            KeyTestFixtures.event(
                phase = ObservableKeyPhase.UP,
                eventTimeMillis = 300L,
            ),
        ) as CaptureEventResult.StateChanged

        assertEquals(
            CaptureRejection.HoldWasNotDemonstrated,
            (result.state as ActionKeyCaptureState.Rejected).reason,
        )
    }

    @Test
    fun reservedAndUnsupportedKeysAreReportedHonestly() {
        val reservedMachine = ActionKeyCaptureStateMachine()
        val request = KeyTestFixtures.request()
        reservedMachine.begin(request)
        val reserved = reservedMachine.onDeliveredEvent(
            request.sessionId,
            KeyTestFixtures.event(keyCode = KeyEvent.KEYCODE_HOME),
        ) as CaptureEventResult.StateChanged
        assertEquals(
            CaptureRejection.Reserved(ReservedHardwareKey.HOME),
            (reserved.state as ActionKeyCaptureState.Rejected).reason,
        )

        val virtualMachine = ActionKeyCaptureStateMachine()
        virtualMachine.begin(request.copy(sessionId = CaptureSessionId(2L)))
        val virtual = virtualMachine.onDeliveredEvent(
            CaptureSessionId(2L),
            KeyTestFixtures.event(physicalDevice = null),
        ) as CaptureEventResult.StateChanged
        assertEquals(
            CaptureRejection.NotAnIdentifiablePhysicalDevice,
            (virtual.state as ActionKeyCaptureState.Rejected).reason,
        )

        val unknownMachine = ActionKeyCaptureStateMachine()
        unknownMachine.begin(request.copy(sessionId = CaptureSessionId(3L)))
        val unknown = unknownMachine.onDeliveredEvent(
            CaptureSessionId(3L),
            KeyTestFixtures.event(scanCode = 0, keyCode = KeyEvent.KEYCODE_UNKNOWN),
        ) as CaptureEventResult.StateChanged
        assertTrue(unknown.state is ActionKeyCaptureState.Rejected)
    }

    @Test
    fun noDeliveredEventHasDistinctTimeoutAndStaleSessionCannotMutateCapture() {
        val machine = ActionKeyCaptureStateMachine()
        val first = KeyTestFixtures.request(session = 10L)
        val current = KeyTestFixtures.request(session = 11L)
        machine.begin(first)
        machine.begin(current)

        assertEquals(
            CaptureIgnoreReason.STALE_SESSION,
            (machine.onDeliveredEvent(first.sessionId, KeyTestFixtures.event())
                as CaptureEventResult.Ignored).reason,
        )
        val timeout = machine.onTimeout(current.sessionId, 2_000L)
            as CaptureEventResult.StateChanged
        assertEquals(
            CaptureTimeoutReason.NO_DELIVERED_EVENT,
            (timeout.state as ActionKeyCaptureState.TimedOut).reason,
        )
    }

    @Test
    fun multipleKeysAndUpWithoutDownCannotCreateAmbiguousMapping() {
        val ambiguousMachine = ActionKeyCaptureStateMachine()
        val request = KeyTestFixtures.request()
        ambiguousMachine.begin(request)
        ambiguousMachine.onDeliveredEvent(request.sessionId, KeyTestFixtures.event())
        val ambiguous = ambiguousMachine.onDeliveredEvent(
            request.sessionId,
            KeyTestFixtures.event(scanCode = 173, keyCode = 281),
        ) as CaptureEventResult.StateChanged
        assertEquals(
            CaptureRejection.AmbiguousMultipleKeys,
            (ambiguous.state as ActionKeyCaptureState.Rejected).reason,
        )

        val upOnlyMachine = ActionKeyCaptureStateMachine()
        val upOnly = KeyTestFixtures.request(session = 2L)
        upOnlyMachine.begin(upOnly)
        assertEquals(
            CaptureIgnoreReason.UP_WITHOUT_DELIVERED_DOWN,
            (upOnlyMachine.onDeliveredEvent(
                upOnly.sessionId,
                KeyTestFixtures.event(
                    phase = ObservableKeyPhase.UP,
                    eventTimeMillis = 150L,
                ),
            ) as CaptureEventResult.Ignored).reason,
        )
        val timeout = upOnlyMachine.onTimeout(upOnly.sessionId, 2_000L)
            as CaptureEventResult.StateChanged
        assertEquals(
            CaptureTimeoutReason.INCOMPLETE_PRESS,
            (timeout.state as ActionKeyCaptureState.TimedOut).reason,
        )
    }

    @Test
    fun symReleaseMayClearOnlyItsOwnModifierAndPreservesCapturedDownState() {
        listOf(0, KeyEvent.META_ALT_ON).forEach { heldModifiers ->
            val down = symDown().copy(metaState = KeyEvent.META_SYM_ON or heldModifiers)
            val completed = capturePair(
                down,
                down.copy(
                    phase = ObservableKeyPhase.UP,
                    eventTimeMillis = 180L,
                    metaState = heldModifiers,
                ),
            ) as ActionKeyCaptureState.Completed

            assertEquals(down.metaState, completed.mapping.metaState)
            assertEquals(down.keyCode, completed.mapping.keyCode)
            assertEquals(down.scanCode, completed.mapping.scanCode)
            assertEquals(down.physicalDevice!!.selector(), completed.mapping.device)
            assertEquals(KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA, completed.mapping.action)
        }
    }

    @Test
    fun symCaptureStillRejectsUnrelatedChordChangesAndChangedRepeatDown() {
        val sym = symDown()
        val up = sym.copy(phase = ObservableKeyPhase.UP, eventTimeMillis = 180L, metaState = 0)
        listOf(
            sym to up.copy(metaState = KeyEvent.META_ALT_ON),
            sym.copy(metaState = KeyEvent.META_SYM_ON or KeyEvent.META_ALT_ON) to up,
            sym.copy(metaState = 0) to up.copy(metaState = KeyEvent.META_SYM_ON),
            sym to sym.copy(eventTimeMillis = 160L, repeatCount = 1, metaState = 0),
            sym.copy(keyCode = KeyEvent.KEYCODE_A) to up.copy(keyCode = KeyEvent.KEYCODE_A),
        ).forEach { (down, next) ->
            val rejected = capturePair(down, next) as ActionKeyCaptureState.Rejected
            assertEquals(CaptureRejection.AmbiguousMultipleKeys, rejected.reason)
        }
    }

    @Test
    fun symReleaseStillRequiresExactDeviceSourceScanKeyAndDownTime() {
        val down = symDown()
        val up = down.copy(phase = ObservableKeyPhase.UP, eventTimeMillis = 180L, metaState = 0)
        listOf(
            up.copy(physicalDevice = KeyTestFixtures.OTHER_DEVICE),
            up.copy(source = up.source + 1),
            up.copy(scanCode = up.scanCode + 1),
            up.copy(keyCode = KeyEvent.KEYCODE_A),
            up.copy(downTimeMillis = down.downTimeMillis + 1L),
        ).forEach { changedUp ->
            val rejected = capturePair(down, changedUp) as ActionKeyCaptureState.Rejected
            assertEquals(CaptureRejection.AmbiguousMultipleKeys, rejected.reason)
        }
    }

    @Test
    fun capturedSymMappingDispatchesExactlyOncePerLaterCompletedPress() {
        val capturedDown = symDown()
        val captured = capturePair(
            capturedDown,
            capturedDown.copy(phase = ObservableKeyPhase.UP, eventTimeMillis = 180L, metaState = 0),
        ) as ActionKeyCaptureState.Completed
        val dispatcher = ActionKeyDispatcher(ActionKeyMappingSet.of(listOf(captured.mapping)))

        listOf(500L, 1_000L).forEach { pressedAt ->
            val down = symDown().copy(downTimeMillis = pressedAt, eventTimeMillis = pressedAt)
            val up = down.copy(
                phase = ObservableKeyPhase.UP,
                eventTimeMillis = pressedAt + 100L,
                metaState = 0,
            )
            assertTrue(dispatcher.onDeliveredEvent(down) is ActionKeyDispatchResult.AwaitingRelease)
            assertEquals(
                ActionKeyIgnoreReason.REPEAT,
                (dispatcher.onDeliveredEvent(
                    down.copy(eventTimeMillis = pressedAt + 50L, repeatCount = 1),
                ) as ActionKeyDispatchResult.Ignored).reason,
            )
            assertEquals(
                ActionKeyCommand.ToggleLunaMaxSolUltra,
                (dispatcher.onDeliveredEvent(up) as ActionKeyDispatchResult.Command).command,
            )
            assertTrue(dispatcher.onDeliveredEvent(up) is ActionKeyDispatchResult.Ignored)
        }

        assertEquals(
            ActionKeyIgnoreReason.NO_MAPPING,
            (dispatcher.onDeliveredEvent(
                symDown().copy(
                    downTimeMillis = 1_500L,
                    eventTimeMillis = 1_500L,
                    metaState = KeyEvent.META_SYM_ON or KeyEvent.META_ALT_ON,
                ),
            ) as ActionKeyDispatchResult.Ignored).reason,
        )
    }

    private fun capturePair(
        down: ObservableAndroidKeyEvent,
        next: ObservableAndroidKeyEvent,
    ): ActionKeyCaptureState {
        val machine = ActionKeyCaptureStateMachine()
        val request = KeyTestFixtures.request(action = KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA)
        machine.begin(request)
        machine.onDeliveredEvent(request.sessionId, down)
        return (machine.onDeliveredEvent(request.sessionId, next) as CaptureEventResult.StateChanged).state
    }

    private fun symDown(): ObservableAndroidKeyEvent = KeyTestFixtures.event(
        // Synthetic physical evidence, not a hard-coded MP01 binding.
        scanCode = 249,
        keyCode = KeyEvent.KEYCODE_SYM,
        metaState = KeyEvent.META_SYM_ON,
    )
}
