package ai.hans.standard.voice.realtime

import ai.hans.standard.voice.realtime.LiveVoiceStateMachine.Action
import ai.hans.standard.voice.realtime.LiveVoiceStateMachine.TimeoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceStateMachineTest {
    private val config = LiveVoiceSessionConfig(
        maximumReconnectAttempts = 3,
        reconnectBaseDelayMillis = 1_000,
        reconnectMaximumDelayMillis = 4_000,
    )

    @Test
    fun normalWebRtcVadInterruptionNeverManuallyClearsOrTruncatesAudio() {
        val machine = readyMachine()
        machine.responseStarted(machine.snapshot.generation)

        val actions = machine.userSpeechStarted(machine.snapshot.generation)

        assertEquals(LiveVoicePhase.USER_SPEAKING, machine.snapshot.phase)
        assertTrue(actions.all { it is Action.CancelTimeout })
        assertFalse(actions.any { it.toString().contains("truncate", ignoreCase = true) })
        assertFalse(actions.any { it.toString().contains("clear", ignoreCase = true) })
    }

    @Test
    fun reconnectUsesBoundedExponentialBackoffAndIgnoresStaleGeneration() {
        val machine = readyMachine()
        val oldGeneration = machine.snapshot.generation
        val first = machine.transportClosed(
            oldGeneration,
            LiveVoiceFailure("realtime_peer_disconnected", true),
        )
        assertEquals(LiveVoicePhase.RECONNECTING, machine.snapshot.phase)
        assertEquals(1_000, first.filterIsInstance<Action.Schedule>().single().delayMillis)

        val acquire = machine.timeout(oldGeneration, TimeoutKind.RECONNECT)
        val newGeneration = machine.snapshot.generation
        assertEquals(oldGeneration + 1, newGeneration)
        assertTrue(acquire.single() is Action.AcquireCredential)

        assertTrue(machine.sessionConfigured(oldGeneration).isEmpty())
        assertEquals(LiveVoicePhase.CONNECTING, machine.snapshot.phase)

        machine.credentialReady(newGeneration)
        val second = machine.transportClosed(
            newGeneration,
            LiveVoiceFailure("realtime_peer_disconnected", true),
        )
        assertEquals(2_000, second.filterIsInstance<Action.Schedule>().single().delayMillis)
    }

    @Test
    fun nonRetryableFailureIsTerminal() {
        val machine = readyMachine()
        val actions = machine.transportClosed(
            machine.snapshot.generation,
            LiveVoiceFailure("realtime_ephemeral_credential_rejected", false),
        )

        assertEquals(LiveVoicePhase.FAILED, machine.snapshot.phase)
        assertEquals("realtime_ephemeral_credential_rejected", machine.snapshot.lastFailureCode)
        assertEquals(listOf(Action.CloseTransport), actions)
    }

    @Test
    fun plannedRenewalCreatesFreshGenerationWithoutDroppingPendingTask() {
        val machine = readyMachine()
        val generation = machine.snapshot.generation
        machine.taskStarted(generation)

        val actions = machine.timeout(generation, TimeoutKind.RENEW)

        assertEquals(generation + 1, machine.snapshot.generation)
        assertEquals(1, machine.snapshot.pendingTaskCount)
        assertEquals(LiveVoicePhase.RECONNECTING, machine.snapshot.phase)
        assertTrue(actions.contains(Action.CloseTransport))
        assertTrue(actions.any { it is Action.AcquireCredential })
    }

    @Test
    fun taskCompletionCannotOverwriteAnActiveResponsePhase() {
        val machine = readyMachine()
        val generation = machine.snapshot.generation
        machine.taskStarted(generation)
        machine.responseStarted(generation)

        machine.taskFinished(generation)

        assertEquals(0, machine.snapshot.pendingTaskCount)
        assertEquals(LiveVoicePhase.HANS_SPEAKING, machine.snapshot.phase)
        machine.responseFinished(generation)
        assertEquals(LiveVoicePhase.LISTENING, machine.snapshot.phase)
    }

    private fun readyMachine(): LiveVoiceStateMachine {
        val machine = LiveVoiceStateMachine(config)
        val generation = (machine.start().single() as Action.AcquireCredential).generation
        machine.credentialReady(generation)
        machine.transportOpen(generation)
        machine.sessionConfigured(generation)
        assertEquals(LiveVoicePhase.LISTENING, machine.snapshot.phase)
        return machine
    }
}
