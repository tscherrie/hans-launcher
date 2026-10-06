package ai.hans.standard.ui

import ai.hans.standard.encodeHansVoiceDiagnostics
import ai.hans.standard.integration.CodexRealtimeDiagnostics
import ai.hans.standard.integration.CodexRealtimeIssue
import ai.hans.standard.integration.CodexRealtimeNativePhase
import ai.hans.standard.integration.CodexRealtimeState
import ai.hans.standard.voice.android.DictationUiPhase
import ai.hans.standard.voice.realtime.LiveVoicePhase
import ai.hans.standard.voice.realtime.TaskVoiceLifecycleDiagnostics
import ai.hans.standard.voice.realtime.TaskVoiceLifecycleDiagnostics.Details
import ai.hans.standard.voice.realtime.TaskVoiceLifecycleDiagnostics.Event
import ai.hans.standard.voice.realtime.TaskVoiceLifecycleDiagnostics.Reason
import ai.hans.standard.voice.realtime.CodexTaskVoiceWorkOutcome
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HansVoiceDiagnosticsTest {
    @Test fun batchLifecycleExportsOnlyBoundedContentFreeLatencyBoundaries() {
        val trace = ai.hans.standard.voice.stt.BatchTranscriptionDiagnostics.Snapshot(3, listOf(
            ai.hans.standard.voice.stt.BatchTranscriptionDiagnostics.Record(
                ai.hans.standard.voice.stt.BatchTranscriptionDiagnostics.Event.INPUT_ENDED, 1_200, 48_000),
            ai.hans.standard.voice.stt.BatchTranscriptionDiagnostics.Record(
                ai.hans.standard.voice.stt.BatchTranscriptionDiagnostics.Event.TEXT_READY, 1_800),
        ))
        val result = JSONObject(encodeHansVoiceDiagnostics(null, DictationUiPhase.SENT,
            LiveVoicePhase.IDLE, false, taskLifecycle = null, batchLifecycle = trace))
        assertEquals(KEYS, result.keys().asSequence().toSet())
        val batch = result.getJSONObject("batchLifecycle")
        assertEquals(setOf("run", "records"), batch.keys().asSequence().toSet())
        val records = batch.getJSONArray("records")
        assertEquals(2, records.length())
        repeat(records.length()) { assertEquals(setOf("type", "elapsedMillis", "audioBytes"),
            records.getJSONObject(it).keys().asSequence().toSet()) }
        assertEquals(600L, records.getJSONObject(1).getLong("elapsedMillis") -
            records.getJSONObject(0).getLong("elapsedMillis"))
        assertEquals(48_000, records.getJSONObject(0).getInt("audioBytes"))
        assertTrue(records.getJSONObject(1).isNull("audioBytes"))
    }
    @Test fun exposesExactlyTheEnumAndCounterAllowlistWithoutRuntimePayloads() {
        val result = JSONObject(encodeHansVoiceDiagnostics(
            CodexRealtimeDiagnostics(state = CodexRealtimeState.DRAINING, started = true,
                stopAcknowledged = false, handoffCount = 2, userFinalCount = 3, assistantFinalCount = 4,
                lastNativePhase = CodexRealtimeNativePhase.HANDOFF, lastError = CodexRealtimeIssue.TIMED_OUT,
                userDeltaCount = 7, userDeltaCharacters = 91),
            DictationUiPhase.FINALIZING, LiveVoicePhase.STOPPED, true, taskLifecycle = null,
        ))
        assertEquals(KEYS, result.keys().asSequence().toSet())
        assertEquals(1, result.getInt("schema"))
        assertTrue(result.getBoolean("codexBound"))
        assertEquals("DRAINING", result.getString("state"))
        assertTrue(result.getBoolean("started"))
        assertFalse(result.getBoolean("stopAck"))
        assertEquals(2, result.getInt("handoffCount"))
        assertEquals(3, result.getInt("userFinalCount"))
        assertEquals(4, result.getInt("assistantFinalCount"))
        assertEquals(7, result.getInt("userDeltaCount"))
        assertEquals(91, result.getInt("userDeltaCharacters"))
        assertEquals("HANDOFF", result.getString("lastPhase"))
        assertEquals("TIMED_OUT", result.getString("error"))
        assertEquals("FINALIZING", result.getString("dictationPhase"))
        assertEquals("STOPPED", result.getString("livePhase"))
        assertTrue(result.getBoolean("captureRequestedOrActive"))
        assertTrue(result.isNull("taskLifecycle"))
    }

    @Test fun unboundCodexIsUnknownRatherThanAnInventedSuccessfulOrIdleSession() {
        val result = JSONObject(encodeHansVoiceDiagnostics(null, DictationUiPhase.IDLE,
            LiveVoicePhase.IDLE, false, taskLifecycle = null))
        assertEquals(KEYS, result.keys().asSequence().toSet())
        assertFalse(result.getBoolean("codexBound"))
        listOf("state", "started", "stopAck", "handoffCount", "userFinalCount", "assistantFinalCount",
            "lastPhase", "error", "userDeltaCount", "userDeltaCharacters").forEach { assertTrue(it, result.isNull(it)) }
        assertFalse(result.getBoolean("captureRequestedOrActive"))
    }

    @Test fun errorsAreEnumNamesOnlyAndRepeatedEncodingIsPassiveAndStable() {
        CodexRealtimeIssue.entries.forEach { issue ->
            val receipt = CodexRealtimeDiagnostics(lastError = issue)
            val first = encodeHansVoiceDiagnostics(receipt, DictationUiPhase.FAILED,
                LiveVoicePhase.IDLE, false, taskLifecycle = null)
            val second = encodeHansVoiceDiagnostics(receipt, DictationUiPhase.FAILED,
                LiveVoicePhase.IDLE, false, taskLifecycle = null)
            assertEquals(first, second)
            assertEquals(issue.name, JSONObject(first).getString("error"))
            assertEquals(KEYS, JSONObject(first).keys().asSequence().toSet())
        }
    }

    @Test fun taskLifecycleExportsOnlyItsFixedEnumBooleanAndCounterAllowlist() {
        val recorder = TaskVoiceLifecycleDiagnostics.Recorder { 100L }
        val run = recorder.beginRun()
        recorder.event(run, Event.TAIL_CLOSED, Details(activeWork = false, pendingDispatch = false,
            muted = true, workOutcome = CodexTaskVoiceWorkOutcome.INTERRUPTED,
            reason = Reason.INTERRUPTED, handoffCount = 2, inputDelayMillis = 450,
            mediaClosed = true, nativeClosed = false))
        val raw = encodeHansVoiceDiagnostics(null, DictationUiPhase.IDLE, LiveVoicePhase.STOPPED,
            false, recorder.snapshot())
        val result = JSONObject(raw)
        assertEquals(KEYS, result.keys().asSequence().toSet())
        val trace = result.getJSONObject("taskLifecycle")
        assertEquals(setOf("schema", "run", "eventCount", "droppedCount", "records"),
            trace.keys().asSequence().toSet())
        assertEquals(1, trace.getInt("schema"))
        assertEquals(run, trace.getLong("run"))
        assertEquals(2L, trace.getLong("eventCount"))
        assertEquals(0L, trace.getLong("droppedCount"))
        val events = trace.getJSONArray("records")
        assertEquals(2, events.length())
        val expectedKeys = setOf("sequence", "elapsedMillis", "type", "activeWork", "pendingDispatch",
            "muted", "workOutcome", "reason", "handoffCount", "inputDelayMillis", "mediaClosed", "nativeClosed")
        repeat(events.length()) { assertEquals(expectedKeys,
            events.getJSONObject(it).keys().asSequence().toSet()) }
        val start = events.getJSONObject(0)
        assertEquals("RUN_STARTED", start.getString("type"))
        assertTrue(start.isNull("reason"))
        assertTrue(start.isNull("activeWork"))
        val close = events.getJSONObject(1)
        assertEquals("TAIL_CLOSED", close.getString("type"))
        assertEquals("INTERRUPTED", close.getString("reason"))
        assertEquals("INTERRUPTED", close.getString("workOutcome"))
        assertEquals(2, close.getInt("handoffCount"))
        assertEquals(450L, close.getLong("inputDelayMillis"))
        assertFalse(close.getBoolean("activeWork"))
        assertFalse(close.getBoolean("pendingDispatch"))
        assertTrue(close.getBoolean("muted"))
        assertTrue(close.getBoolean("mediaClosed"))
        assertFalse(close.getBoolean("nativeClosed"))
        listOf("transcript", "threadId", "turnId", "accountId", "sdp", "pcm", "apiKey")
            .forEach { assertFalse(it, raw.contains(it)) }
    }

    @Test fun dumpAndGettersArePassiveAndDoNotInitializeTheHost() {
        val client = source("integration/CodexSessionClient.kt")
        val host = source("integration/AndroidCodexSessionHost.kt")
        assertTrue(client.contains("fun realtimeDiagnostics(): CodexRealtimeDiagnostics = controller.realtimeDiagnostics()"))
        assertTrue(host.contains("fun realtimeDiagnostics(): CodexRealtimeDiagnostics? = currentClient()?.realtimeDiagnostics()"))
        val dump = source("LauncherActivity.kt").substringAfter("if (command == \"--hans-voice\") {")
            .substringBefore("if (command == \"--hans-remote-control\")")
        assertTrue(dump.contains("::sessionHost.isInitialized"))
        assertTrue(dump.contains("sessionHost.realtimeDiagnostics() else null"))
        assertTrue(dump.contains("HANS_VOICE "))
        assertTrue(dump.contains("HansDictationRuntime.snapshotUi().phase"))
        assertTrue(dump.contains("AndroidLiveVoiceRuntime.snapshot().phase"))
        assertTrue(dump.contains("AndroidLiveVoiceRuntime.isCaptureRequestedOrActive()"))
        assertTrue(dump.contains("return"))
        listOf("sessionHost.start(", "sessionHost.restart(", "AndroidLiveVoiceRuntime.start(",
            "HansDictationService.start(", "addObserver(", "postDelayed(", "snapshot().toString()")
            .forEach { assertFalse(it, dump.contains(it)) }
    }

    private fun source(path: String) = sequenceOf(File("src/main/java/ai/hans/standard/$path"),
        File("android/app/src/main/java/ai/hans/standard/$path")).first(File::isFile).readText()

    private companion object {
        val KEYS = setOf("schema", "codexBound", "state", "started", "stopAck", "handoffCount",
            "userFinalCount", "assistantFinalCount", "lastPhase", "error", "dictationPhase", "livePhase",
            "captureRequestedOrActive", "userDeltaCount", "userDeltaCharacters", "taskLifecycle", "batchLifecycle")
    }
}
