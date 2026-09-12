package ai.hans.standard.voice.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveConnectionAudioGateTest {
    @Test fun ringbackStopsBeforePhysicalCaptureAndDuplicateStartedIsIdempotent() {
        val events = mutableListOf<String>()
        val gate = gate(events)
        gate.connecting()
        gate.connecting()
        gate.connected()
        gate.connected()
        assertEquals(listOf("record:false", "tone:start", "tone:stop", "record:true"), events)
    }

    @Test fun failureOrHangupClosesToneAndCannotBeResurrectedByLateStarted() {
        val events = mutableListOf<String>()
        val gate = gate(events)
        gate.connecting()
        gate.close()
        gate.connected()
        gate.connecting()
        gate.close()
        assertEquals(listOf("record:false", "tone:start", "tone:stop", "record:false"), events)
    }

    @Test fun throwingToneCloseStillDisablesPhysicalRecordingAndCannotRestart() {
        val events = mutableListOf<String>()
        val gate = LiveConnectionAudioGate(object : LiveConnectionTone {
            override fun start() { events += "tone:start" }
            override fun close() { events += "tone:stop"; error("tone release failed") }
        }) { events += "record:$it" }
        gate.connecting()
        assertTrue(runCatching { gate.close() }.isFailure)
        gate.connected()
        gate.close()
        assertEquals(listOf("record:false", "tone:start", "tone:stop", "record:false"), events)
    }

    @Test fun failedToneStopDuringConnectionNeverOpensCapture() {
        val events = mutableListOf<String>()
        val gate = LiveConnectionAudioGate(object : LiveConnectionTone {
            override fun start() = Unit
            override fun close() = error("tone not stopped")
        }) { events += "record:$it" }
        gate.connecting()
        assertTrue(runCatching { gate.connected() }.isFailure)
        runCatching { gate.close() }
        assertEquals(listOf("record:false", "record:false"), events)
    }

    @Test fun failedPhysicalRecordingEnableStillAllowsTerminalDisable() {
        val events = mutableListOf<String>()
        val gate = LiveConnectionAudioGate(object : LiveConnectionTone {
            override fun start() = Unit
            override fun close() { events += "tone:stop" }
        }) { enabled ->
            events += "record:$enabled"
            if (enabled) error("native recording failed")
        }
        gate.connecting()
        assertTrue(runCatching { gate.connected() }.isFailure)
        gate.close()
        assertEquals(listOf("record:false", "tone:stop", "record:true", "tone:stop", "record:false"), events)
    }

    private fun gate(events: MutableList<String>) = LiveConnectionAudioGate(
        object : LiveConnectionTone {
            override fun start() { events += "tone:start" }
            override fun close() { events += "tone:stop" }
        },
    ) { events += "record:$it" }
}
