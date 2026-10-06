package ai.hans.standard.voice.stt

import ai.hans.standard.voice.RecordingId
import java.util.concurrent.TimeUnit

/** Only latest-run lifecycle metadata. No content, account/thread identity, timer or idle polling. */
object BatchTranscriptionDiagnostics {
    enum class Event { OPENED, FIRST_AUDIO, INPUT_ENDED, REQUEST_STARTED, TEXT_READY, FAILED, CANCELLED }
    data class Record(val type: Event, val elapsedMillis: Long, val audioBytes: Int? = null)
    data class Snapshot(val run: Long, val records: List<Record>)
    private var owner: RecordingId? = null
    private var run = 0L
    private var start = 0L
    private val records = ArrayList<Record>()
    @Synchronized fun begin(recordingId: RecordingId) {
        owner = recordingId; run++; start = System.nanoTime(); records.clear()
        records.add(Record(Event.OPENED, 0))
    }
    @Synchronized fun event(recordingId: RecordingId, type: Event, audioBytes: Int? = null) {
        if (owner != recordingId || records.size >= 16) return
        records.add(Record(type, TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - start).coerceAtLeast(0)),
            audioBytes?.coerceIn(0, CodexBatchTranscriptionProvider.MAX_PCM_BYTES)))
    }
    @Synchronized fun snapshot(): Snapshot? = if (owner == null) null else Snapshot(run, records.toList())
}
