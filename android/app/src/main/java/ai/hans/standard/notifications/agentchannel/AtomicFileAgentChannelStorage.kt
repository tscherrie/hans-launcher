package ai.hans.standard.notifications.agentchannel

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** One application-owned singleton is required: no cross-process/channel-instance writers. */
class AtomicFileAgentChannelStorage(context: Context, fileName: String = FILE_NAME) : AgentChannelStorage {
    private val base = File(context.applicationContext.noBackupFilesDir, fileName).also {
        require(fileName == File(fileName).name && fileName.isNotBlank())
    }
    private val file = AtomicFile(base)
    private val seal = AtomicFile(File(base.parentFile, "$fileName.initialized"))

    @Synchronized override fun read(): AgentChannelState? = runCatching {
        if (!base.isFile && !File("${base.path}.bak").isFile) {
            if (seal.baseFile.exists() || File("${seal.baseFile.path}.bak").exists()) return null
            write(AgentChannelState())
        }
        require(base.length() <= AgentChannelLimits.MAX_STORAGE_BYTES)
        require(File("${base.path}.bak").length() <= AgentChannelLimits.MAX_STORAGE_BYTES)
        val bytes = file.readFully()
        require(bytes.isNotEmpty() && bytes.size <= AgentChannelLimits.MAX_STORAGE_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        AgentChannelStateCodec.decode(text)
    }.getOrNull()

    @Synchronized override fun write(state: AgentChannelState) {
        val text = AgentChannelStateCodec.encode(state)
        require(AgentChannelStateCodec.decode(text) == state)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= AgentChannelLimits.MAX_STORAGE_BYTES)
        // Seal FIRST. A crash between the two writes is closed, never a clean unbound restart.
        writeAtomic(seal, byteArrayOf(1))
        writeAtomic(file, bytes)
    }

    private fun writeAtomic(target: AtomicFile, bytes: ByteArray) {
        val output = target.startWrite()
        try { output.write(bytes); output.fd.sync(); target.finishWrite(output) }
        catch (failure: Exception) { target.failWrite(output); throw failure }
    }

    companion object { const val FILE_NAME = "whatsapp_agent_channel.json" }
}
