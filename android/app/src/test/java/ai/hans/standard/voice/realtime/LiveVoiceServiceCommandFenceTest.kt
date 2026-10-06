package ai.hans.standard.voice.realtime

import org.junit.Assert.*
import org.junit.Test

class LiveVoiceServiceCommandFenceTest {
    @Test fun stopBeforeQueuedServiceStartNeverClaimsCapture() {
        val fence = LiveVoiceServiceCommandFence()
        val start = fence.reserveStart()!!
        assertEquals(start, fence.requestStop())
        assertFalse(fence.isRequestedOrActive())
        assertFalse(fence.claimStart(start))
        assertTrue(fence.consumeStop(start))
        assertFalse(fence.consumeStop(start))
        assertTrue(fence.wasStopRequested(start))
    }

    @Test fun duplicateStartIsOneShotAndCannotReserveAnotherCapture() {
        val fence = LiveVoiceServiceCommandFence()
        val start = fence.reserveStart()!!
        assertNull(fence.reserveStart())
        assertTrue(fence.claimStart(start))
        assertFalse(fence.claimStart(start))
        assertNull(fence.reserveStart())
        assertTrue(fence.isRequestedOrActive())
    }

    @Test fun delayedStopAndFailureCannotCancelNewerStart() {
        val fence = LiveVoiceServiceCommandFence()
        val old = fence.reserveStart()!!
        fence.requestStop()
        val next = fence.reserveStart()!!
        assertTrue(next > old)
        assertFalse(fence.consumeStop(old))
        assertNull(fence.requestStop(old))
        assertFalse(fence.failPendingStart(old))
        assertFalse(fence.complete(old))
        assertTrue(fence.claimStart(next))
        assertTrue(fence.isRequestedOrActive())
    }

    @Test fun activeStopKeepsCaptureReservedUntilActualTerminalReceipt() {
        val fence = LiveVoiceServiceCommandFence()
        val start = fence.reserveStart()!!
        assertTrue(fence.claimStart(start))
        assertEquals(start, fence.requestStop())
        assertTrue(fence.consumeStop(start))
        assertNull(fence.requestStop(start))
        assertTrue(fence.isRequestedOrActive())
        assertNull(fence.reserveStart())
        assertFalse(fence.failPendingStart(start))
        assertTrue(fence.complete(start))
        assertFalse(fence.complete(start))
        assertNotNull(fence.reserveStart())
    }

    @Test fun delayedStopAfterTerminalCannotStopLaterActiveCall() {
        val fence = LiveVoiceServiceCommandFence()
        val old = fence.reserveStart()!!
        fence.claimStart(old)
        fence.requestStop()
        fence.complete(old)
        val next = fence.reserveStart()!!
        fence.claimStart(next)
        assertFalse(fence.consumeStop(old))
        assertNull(fence.requestStop(old))
        assertEquals(next, fence.currentToken())
    }

    @Test fun failedPendingStartReleasesOnlyItsOwnReservation() {
        val fence = LiveVoiceServiceCommandFence()
        val failed = fence.reserveStart()!!
        assertTrue(fence.failPendingStart(failed))
        assertFalse(fence.claimStart(failed))
        assertFalse(fence.isRequestedOrActive())
        val next = fence.reserveStart()!!
        assertFalse(fence.failPendingStart(failed))
        assertTrue(fence.isPending(next))
    }

    @Test fun stopWhileAudioBarrierWaitsPreventsStartRequest() {
        val fence = LiveVoiceServiceCommandFence()
        val token = fence.reserveStart()!!
        var starts = 0
        val result = requestLiveVoiceStartAfterAudioBarrier(
            captureStartBarrier = LiveVoiceCaptureStartBarrier {
                fence.requestStop(token)
                true
            },
            requestStart = {
                if (!fence.isPending(token)) LiveVoiceServiceCommandResult.START_NOT_ALLOWED
                else { starts++; LiveVoiceServiceCommandResult.REQUESTED }
            },
        )
        assertEquals(LiveVoiceServiceCommandResult.START_NOT_ALLOWED, result)
        assertEquals(0, starts)
        assertFalse(fence.isRequestedOrActive())
    }

    @Test fun failedOldBarrierCannotReleaseReplacementRequest() {
        val fence = LiveVoiceServiceCommandFence()
        val old = fence.reserveStart()!!
        var replacement = 0L
        val result = requestLiveVoiceStartAfterAudioBarrier(
            captureStartBarrier = LiveVoiceCaptureStartBarrier {
                fence.requestStop(old)
                replacement = fence.reserveStart()!!
                false
            },
            requestStart = { fail("Failed barrier must not start service"); LiveVoiceServiceCommandResult.REQUESTED },
        )
        assertEquals(LiveVoiceServiceCommandResult.AUDIO_OUTPUT_NOT_STOPPED, result)
        assertFalse(fence.failPendingStart(old))
        assertTrue(fence.isPending(replacement))
    }

    @Test fun absentTokenCannotClaimOrStopAnything() {
        val fence = LiveVoiceServiceCommandFence()
        val token = fence.reserveStart()!!
        assertFalse(fence.claimStart(0))
        assertFalse(fence.consumeStop(0))
        assertNull(fence.requestStop(0))
        assertTrue(fence.isPending(token))
    }

    @Test fun testResetRevokesPendingAndActiveTokensWithoutRecyclingThem() {
        val fence = LiveVoiceServiceCommandFence()
        val old = fence.reserveStart()!!
        fence.claimStart(old)
        fence.requestStop()
        fence.clearForTest()
        assertFalse(fence.isRequestedOrActive())
        assertFalse(fence.consumeStop(old))
        assertFalse(fence.isLatest(old))
        val next = fence.reserveStart()!!
        assertTrue(next > old)
        assertFalse(fence.complete(old))
        assertTrue(fence.isPending(next))
    }

    @Test fun staleCaptureSnapshotCannotReleaseNewerCaptureReservation() {
        val state = LiveVoiceCaptureActivityState()
        state.publish(true, 1)
        state.publish(false, 1)
        state.publish(true, 2)
        state.publish(false, 1)
        assertTrue(state.isRequestedOrActive())
        state.publish(false, 2)
        assertFalse(state.isRequestedOrActive())
    }

    @Test fun staleSessionCannotDeliverFailureOrSnapshotIntoNewSession() {
        val hub = LiveVoiceObserverHub()
        var snapshots = 0
        var failures = 0
        hub.add(object : LiveVoiceObserver {
            override fun onSnapshot(snapshot: LiveVoiceSnapshot) { snapshots++ }
            override fun onFailure(failure: LiveVoiceFailure) { failures++ }
        })
        val replayCount = snapshots
        val latestToken = 2L
        hub.ifCurrentSource({ latestToken == 1L }) {
            hub.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.STOPPED))
            hub.onFailure(LiveVoiceFailure("stale", false))
        }
        assertEquals(replayCount, snapshots)
        assertEquals(0, failures)
        hub.ifCurrentSource({ latestToken == 2L }) {
            hub.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.LISTENING))
        }
        assertEquals(replayCount + 1, snapshots)
    }
}
