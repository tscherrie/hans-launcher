package ai.hans.standard.voice.realtime

import ai.hans.standard.voice.realtime.TaskVoiceLifecycleDiagnostics.Details
import ai.hans.standard.voice.realtime.TaskVoiceLifecycleDiagnostics.Event
import ai.hans.standard.voice.realtime.TaskVoiceLifecycleDiagnostics.Reason
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TaskVoiceLifecycleDiagnosticsTest {
    @Test fun unstartedSnapshotIsUnknownAndReadingItDoesNotConsultTheClock() {
        var reads = 0
        val recorder = TaskVoiceLifecycleDiagnostics.Recorder { reads++; 0L }
        assertNull(recorder.snapshot())
        assertFalse(recorder.event(1, Event.HANDOFF))
        assertEquals(0, reads)
        recorder.beginRun()
        val first = recorder.snapshot()
        repeat(10) { assertEquals(first, recorder.snapshot()) }
        assertEquals(1, reads)
    }

    @Test fun recordsExactArrivalOrderingAndEnumReasonsWithoutClaimingServerTime() {
        var now = TimeUnit.SECONDS.toNanos(10)
        val recorder = TaskVoiceLifecycleDiagnostics.Recorder { now }
        val run = recorder.beginRun()
        val events = listOf(Event.NATIVE_READY, Event.HANDOFF, Event.WORK_STATE,
            Event.COMPLETION_MATCHED, Event.TAIL_STARTED, Event.OUTPUT_ACTIVE,
            Event.OUTPUT_QUIET, Event.TAIL_CLOSED, Event.SESSION_CLOSED)
        events.forEachIndexed { index, type ->
            now += TimeUnit.MILLISECONDS.toNanos(13)
            assertTrue(recorder.event(run, type, Details(
                handoffCount = 1,
                workOutcome = CodexTaskVoiceWorkOutcome.COMPLETED,
                reason = Reason.COMPLETED,
                mediaClosed = index == events.lastIndex,
                nativeClosed = index == events.lastIndex,
            )))
        }
        val snapshot = requireNotNull(recorder.snapshot())
        assertEquals(listOf(Event.RUN_STARTED) + events, snapshot.records.map { it.type })
        assertEquals((0L..9L).map { it * 13 }, snapshot.records.map { it.elapsedMillis })
        assertEquals((1L..10L).toList(), snapshot.records.map { it.sequence })
        assertEquals(0L, snapshot.droppedCount)
        assertEquals(Reason.COMPLETED, snapshot.records.last().details.reason)
        now += TimeUnit.DAYS.toNanos(5)
        assertEquals(snapshot, recorder.snapshot()) // Completed run remains available, no expiry/poll.
    }

    @Test fun newRunFencesLateOldEventsAndRetainsFailureUntilNextExplicitBegin() {
        val recorder = TaskVoiceLifecycleDiagnostics.Recorder { 0L }
        val old = recorder.beginRun()
        assertTrue(recorder.event(old, Event.SESSION_FAILED, Details(reason = Reason.FINISH_TIMEOUT)))
        val failed = recorder.snapshot()
        assertEquals(failed, recorder.snapshot())
        val current = recorder.beginRun()
        assertTrue(current > old)
        assertFalse(recorder.event(old, Event.SESSION_CLOSED))
        assertFalse(recorder.event(0, Event.HANDOFF))
        assertFalse(recorder.event(current, Event.RUN_STARTED))
        assertEquals(listOf(Event.RUN_STARTED), recorder.snapshot()!!.records.map { it.type })
        assertTrue(recorder.event(current, Event.HANDOFF))
        assertEquals(2L, recorder.snapshot()!!.eventCount)
    }

    @Test fun bufferIsStrictlyBoundedWithExplicitDropCountAndLatestClosePreserved() {
        val recorder = TaskVoiceLifecycleDiagnostics.Recorder { 0L }
        val run = recorder.beginRun()
        repeat(1_000) { recorder.event(run, Event.WORK_STATE, Details(activeWork = it % 2 == 0)) }
        recorder.event(run, Event.SESSION_CLOSED, Details(mediaClosed = true, nativeClosed = true))
        val snapshot = recorder.snapshot()!!
        assertEquals(TaskVoiceLifecycleDiagnostics.MAX_RECORDS, snapshot.records.size)
        assertEquals(1_002L, snapshot.eventCount)
        assertEquals(1_002L - TaskVoiceLifecycleDiagnostics.MAX_RECORDS, snapshot.droppedCount)
        assertEquals(Event.SESSION_CLOSED, snapshot.records.last().type)
        assertEquals(snapshot.eventCount, snapshot.records.last().sequence)
    }

    @Test fun relativeTimeNeverMovesBackwardsAndInvalidCountersAreBounded() {
        var now = 100_000_000L
        val recorder = TaskVoiceLifecycleDiagnostics.Recorder { now }
        val run = recorder.beginRun()
        now += 30_000_000
        recorder.event(run, Event.HANDOFF, Details(handoffCount = Int.MAX_VALUE,
            inputDelayMillis = Long.MAX_VALUE))
        now -= 20_000_000
        recorder.event(run, Event.INPUT_MUTE_CHANGED, Details(muted = true,
            handoffCount = -1, inputDelayMillis = -1))
        assertEquals(listOf(0L, 30L, 30L), recorder.snapshot()!!.records.map { it.elapsedMillis })
        val first = recorder.snapshot()!!.records[1].details
        val last = recorder.snapshot()!!.records.last().details
        assertEquals(1_000_000_000, first.handoffCount)
        assertEquals(120_000L, first.inputDelayMillis)
        assertEquals(0, last.handoffCount)
        assertEquals(0L, last.inputDelayMillis)
    }

    @Test fun snapshotsAreDetachedFromLaterMutationAndParallelEventsAreSerialized() {
        val recorder = TaskVoiceLifecycleDiagnostics.Recorder { 0L }
        val run = recorder.beginRun()
        val initial = recorder.snapshot()!!
        val threads = (1..4).map { Thread { repeat(50) { recorder.event(run, Event.HANDOFF) } } }
        threads.forEach(Thread::start)
        threads.forEach { it.join(2_000); assertFalse(it.isAlive) }
        assertEquals(1, initial.records.size)
        val current = recorder.snapshot()!!
        assertEquals(201L, current.eventCount)
        assertEquals(current.records.map { it.sequence }.distinct().size, current.records.size)
        assertEquals(current.records.map { it.sequence }.sorted(), current.records.map { it.sequence })
    }

    @Test fun recorderTypesHaveNoPayloadTextOrIdentityFields() {
        val allowed = setOf(java.lang.Boolean::class.java, java.lang.Integer::class.java,
            java.lang.Long::class.java, CodexTaskVoiceWorkOutcome::class.java, Reason::class.java)
        Details::class.java.declaredFields.filterNot {
            it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.forEach {
            assertTrue("Unexpected payload field ${it.name}", it.type in allowed)
        }
        assertTrue(RecordFields.names.all { it in setOf("sequence", "elapsedMillis", "type", "details") })
    }

    private object RecordFields {
        val names = TaskVoiceLifecycleDiagnostics.Record::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }
    }
}
