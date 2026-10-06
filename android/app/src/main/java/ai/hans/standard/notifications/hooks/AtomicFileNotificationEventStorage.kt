package ai.hans.standard.notifications.hooks

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** One application singleton. Missing initialized files are an error, never an empty restart. */
internal class AtomicFileNotificationEventStorage(context: Context, fileName: String = FILE_NAME) : NotificationEventStorage {
    private val base = File(context.applicationContext.noBackupFilesDir, fileName).also {
        require(fileName == File(fileName).name && fileName.isNotBlank())
    }
    private val file = AtomicFile(base)
    private val seal = AtomicFile(File(base.parentFile, "$fileName.initialized"))

    @Synchronized override fun read(): NotificationEventState? = runCatching {
        if (!base.isFile && !File("${base.path}.bak").isFile) {
            if (seal.baseFile.exists() || File("${seal.baseFile.path}.bak").exists()) return null
            write(NotificationEventState())
        }
        require(base.length() <= NotificationEventLimits.MAX_STORAGE_BYTES &&
            File("${base.path}.bak").length() <= NotificationEventLimits.MAX_STORAGE_BYTES)
        val bytes = file.readFully()
        require(bytes.isNotEmpty() && bytes.size <= NotificationEventLimits.MAX_STORAGE_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        NotificationEventStateCodec.decode(text)
    }.getOrNull()

    @Synchronized override fun write(state: NotificationEventState) {
        val text = NotificationEventStateCodec.encode(state)
        require(NotificationEventStateCodec.decode(text) == state)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= NotificationEventLimits.MAX_STORAGE_BYTES)
        writeAtomic(seal, byteArrayOf(1))
        writeAtomic(file, bytes)
    }

    private fun writeAtomic(target: AtomicFile, bytes: ByteArray) {
        val output = target.startWrite()
        try { output.write(bytes); output.fd.sync(); target.finishWrite(output) }
        catch (failure: Exception) { target.failWrite(output); throw failure }
    }

    companion object { const val FILE_NAME = "notification_external_events.json" }
}
