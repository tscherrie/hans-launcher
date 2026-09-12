package ai.hans.standard.voice.stt.android

import ai.hans.standard.voice.stt.SttTranscriptionDelay
import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/** Device-local preference for the next recording, never a claim of server acceptance. */
class SttLatencyPreferenceStore internal constructor(
    private val storage: SttLatencyPreferenceStorage,
) {
    constructor(context: Context) : this(
        AtomicSttLatencyPreferenceStorage(
            File(context.applicationContext.noBackupFilesDir, "hans-stt-latency-v1.txt"),
        ),
    )

    fun read(): SttTranscriptionDelay = synchronized(processLock) { readLocked() }

    fun save(delay: SttTranscriptionDelay): SttTranscriptionDelay = synchronized(processLock) {
        // No speculative in-memory preference: a failed transaction leaves the durable file alone.
        val bytes = delay.wireValue.toByteArray(Charsets.US_ASCII)
        storage.writeAtomically(bytes)
        check(storage.read()?.contentEquals(bytes) == true) { "stt_latency_preference_not_confirmed" }
        delay
    }

    private fun readLocked(): SttTranscriptionDelay = decode(
        storage.read()?.takeIf { it.size <= MAX_STORED_BYTES }?.toString(Charsets.US_ASCII),
    )

    companion object {
        // AtomicFile does not synchronize callers. Share this lock across launcher/service instances.
        private val processLock = Any()
        internal const val MAX_STORED_BYTES = 16

        internal fun decode(value: String?): SttTranscriptionDelay =
            SttTranscriptionDelay.entries.firstOrNull { it.wireValue == value }
                ?: SttTranscriptionDelay.LOW
    }
}

/** The backend commits all bytes or retains the previous value; it publishes no speculative cache. */
internal interface SttLatencyPreferenceStorage {
    fun read(): ByteArray?

    fun writeAtomically(bytes: ByteArray)
}

private class AtomicSttLatencyPreferenceStorage(private val file: File) : SttLatencyPreferenceStorage {
    private val atomicFile = AtomicFile(file)

    override fun read(): ByteArray? {
        val input = try {
            atomicFile.openRead()
        } catch (failure: FileNotFoundException) {
            // Only an absent preference defaults. Permission/I/O errors must not look like Low.
            if (file.exists() || File(file.path + ".bak").exists()) throw failure
            return null
        }
        return input.use {
            val bytes = ByteArray(SttLatencyPreferenceStore.MAX_STORED_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = it.read(bytes, count, bytes.size - count)
                if (read == -1) break
                count += read
            }
            bytes.copyOf(count)
        }
    }

    override fun writeAtomically(bytes: ByteArray) {
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            output.flush()
            // Explicit sync surfaces failures before commit; AtomicFile.finishWrite may only log them.
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (failure: Exception) {
            atomicFile.failWrite(output)
            throw failure
        }
    }
}
