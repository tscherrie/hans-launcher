package ai.hans.standard.settings.fastphone

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/** Atomic, non-backed-up opt-in. A failed write never publishes a clicked value as effective. */
internal class FastPhoneUsePreferenceStore(private val storage: FastPhoneUsePreferenceStorage) {
    constructor(context: Context, name: String) : this(AtomicFastPhoneUsePreferenceStorage(
        File(context.applicationContext.noBackupFilesDir, "$name.txt"),
    ))

    fun read(): Boolean = synchronized(processLock) {
        storage.read()?.contentEquals(ENABLED) == true
    }

    fun save(enabled: Boolean): Boolean = synchronized(processLock) {
        val bytes = if (enabled) ENABLED else DISABLED
        storage.writeAtomically(bytes)
        check(storage.read()?.contentEquals(bytes) == true) { "fast_phone_preference_not_confirmed" }
        enabled
    }

    companion object {
        private val processLock = Any()
        internal const val MAX_STORED_BYTES = 8
        private val ENABLED = byteArrayOf(1)
        private val DISABLED = byteArrayOf(0)
    }
}

internal interface FastPhoneUsePreferenceStorage {
    fun read(): ByteArray?
    fun writeAtomically(bytes: ByteArray)
}

private class AtomicFastPhoneUsePreferenceStorage(file: File) : FastPhoneUsePreferenceStorage {
    private val file = file
    private val atomicFile = AtomicFile(file)

    override fun read(): ByteArray? {
        val input = try { atomicFile.openRead() } catch (failure: FileNotFoundException) {
            if (file.exists() || File(file.path + ".bak").exists()) throw failure
            return null
        }
        return input.use {
            val bytes = ByteArray(FastPhoneUsePreferenceStore.MAX_STORED_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = it.read(bytes, count, bytes.size - count)
                if (read < 0) break
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
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (failure: Exception) {
            atomicFile.failWrite(output)
            throw failure
        }
    }
}
