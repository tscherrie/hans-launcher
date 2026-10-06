package ai.hans.standard.voice.stt

import ai.hans.standard.voice.RecordingId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTranscriptionDiagnosticsTest {
    @Test fun beginCreatesOnlyContentFreeOpenedEvidenceAtZeroElapsedTime() {
        val id = RecordingId(1001)
        BatchTranscriptionDiagnostics.begin(id)
        val snapshot = checkNotNull(BatchTranscriptionDiagnostics.snapshot())
        assertTrue(snapshot.run > 0)
        assertEquals(listOf(BatchTranscriptionDiagnostics.Record(BatchTranscriptionDiagnostics.Event.OPENED, 0)),
            snapshot.records)
        assertFalse(snapshot.toString().contains(id.toString()))
    }

    @Test fun openingAnotherRunReplacesTheOldRunAndRejectsItsLateEvents() {
        val oldId = RecordingId(1002)
        val nextId = RecordingId(1003)
        BatchTranscriptionDiagnostics.begin(oldId)
        BatchTranscriptionDiagnostics.event(oldId, BatchTranscriptionDiagnostics.Event.FIRST_AUDIO)
        val old = checkNotNull(BatchTranscriptionDiagnostics.snapshot())
        BatchTranscriptionDiagnostics.begin(nextId)
        BatchTranscriptionDiagnostics.event(oldId, BatchTranscriptionDiagnostics.Event.TEXT_READY)
        val next = checkNotNull(BatchTranscriptionDiagnostics.snapshot())
        assertEquals(old.run + 1, next.run)
        assertEquals(listOf(BatchTranscriptionDiagnostics.Event.OPENED), next.records.map { it.type })
        assertEquals(2, old.records.size)
    }

    @Test fun snapshotsAreCopiesAndCannotChangeAfterMoreEventsAreRecorded() {
        val id = RecordingId(1004)
        BatchTranscriptionDiagnostics.begin(id)
        val before = checkNotNull(BatchTranscriptionDiagnostics.snapshot())
        BatchTranscriptionDiagnostics.event(id, BatchTranscriptionDiagnostics.Event.INPUT_ENDED, 48_000)
        assertEquals(1, before.records.size)
        val after = checkNotNull(BatchTranscriptionDiagnostics.snapshot())
        assertEquals(2, after.records.size)
        assertEquals(48_000, after.records.last().audioBytes)
    }

    @Test fun audioByteCountsAreBoundedAndElapsedTimesNeverGoBackwards() {
        val id = RecordingId(1005)
        BatchTranscriptionDiagnostics.begin(id)
        BatchTranscriptionDiagnostics.event(id, BatchTranscriptionDiagnostics.Event.INPUT_ENDED, -1)
        BatchTranscriptionDiagnostics.event(id, BatchTranscriptionDiagnostics.Event.REQUEST_STARTED, Int.MAX_VALUE)
        BatchTranscriptionDiagnostics.event(id, BatchTranscriptionDiagnostics.Event.TEXT_READY)
        val records = checkNotNull(BatchTranscriptionDiagnostics.snapshot()).records
        assertEquals(0, records[1].audioBytes)
        assertEquals(CodexBatchTranscriptionProvider.MAX_PCM_BYTES, records[2].audioBytes)
        assertEquals(null, records[3].audioBytes)
        assertTrue(records.all { it.elapsedMillis >= 0 })
        assertEquals(records.map { it.elapsedMillis }.sorted(), records.map { it.elapsedMillis })
    }

    @Test fun evidenceCapacityIsFixedWithoutEvictingTheOpenedBoundary() {
        val id = RecordingId(1006)
        BatchTranscriptionDiagnostics.begin(id)
        repeat(100) { BatchTranscriptionDiagnostics.event(id, BatchTranscriptionDiagnostics.Event.FIRST_AUDIO) }
        val records = checkNotNull(BatchTranscriptionDiagnostics.snapshot()).records
        assertEquals(16, records.size)
        assertEquals(BatchTranscriptionDiagnostics.Event.OPENED, records.first().type)
    }

    @Test fun exposedDataClassesCannotStoreContentOrAccountThreadIdentifiers() {
        assertEquals(setOf("run", "records"), BatchTranscriptionDiagnostics.Snapshot::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
        assertEquals(setOf("type", "elapsedMillis", "audioBytes"), BatchTranscriptionDiagnostics.Record::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
    }

    @Test fun providerLifecycleSeparatesCaptureEndFromRequestAndFinalText() {
        val id = RecordingId(1007)
        var backend: ((Result<String>) -> Unit)? = null
        CodexBatchTranscriptionProvider(CodexBatchTranscriptionGateway { _, callback ->
            backend = callback
            BatchTranscriptionCancellation {}
        }).use { provider ->
            val session = provider.openSession(id, CodexBatchTranscriptionProvider.FORMAT)
            session.submitChunk(ai.hans.standard.voice.PcmAudioChunk.create(id, 0, ByteArray(48_000), true, 0)) {}
            assertEquals(listOf(BatchTranscriptionDiagnostics.Event.OPENED,
                BatchTranscriptionDiagnostics.Event.FIRST_AUDIO, BatchTranscriptionDiagnostics.Event.INPUT_ENDED),
                checkNotNull(BatchTranscriptionDiagnostics.snapshot()).records.map { it.type })
            session.finish {}
            assertEquals(BatchTranscriptionDiagnostics.Event.REQUEST_STARTED,
                checkNotNull(BatchTranscriptionDiagnostics.snapshot()).records.last().type)
            checkNotNull(backend)(Result.success("Synthetic text never enters diagnostics"))
            val snapshot = checkNotNull(BatchTranscriptionDiagnostics.snapshot())
            assertEquals(BatchTranscriptionDiagnostics.Event.TEXT_READY, snapshot.records.last().type)
            assertFalse(snapshot.toString().contains("Synthetic text"))
        }
    }
}
