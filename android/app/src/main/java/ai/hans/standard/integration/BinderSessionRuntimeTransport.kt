package ai.hans.standard.integration

import ai.hans.standard.runtime.AppServerSessionContract
import ai.hans.standard.runtime.BinderFrameChunker
import ai.hans.standard.runtime.IAppServerSessionCallback
import ai.hans.standard.runtime.IRuntimeService

/** Android Binder adapter; all protocol/state logic remains in the controller. */
internal class BinderSessionRuntimeTransport(
    private val runtime: IRuntimeService,
    private val phoneToolsBridge: ai.hans.standard.remotecontrol.RemotePhoneToolsBridgeConfig? = null,
) : SessionRuntimeTransport {
    private val assembler = OrderedServerDeliveryAssembler()
    private val pendingStart = PendingRuntimeStart()
    private var listener: RuntimeSessionListener? = null
    private var outboundGeneration: Long? = null
    private var nextClientSequence = 1L

    override val protocolVersion: Int
        get() = runtime.sessionProtocolVersion

    private val callback = object : IAppServerSessionCallback.Stub() {
        override fun onSessionState(
            operationId: Long,
            generation: Long,
            eventSequence: Long,
            state: Int,
            runtimePid: Int,
            detail: String,
        ) {
            val target = listener ?: return
            if (generation == 0L) {
                // Prerequisite/lease failures precede any process generation. They are
                // operation receipts, not stream events: repeated (0, 0) deliveries must
                // not poison the ordered session stream or satisfy a newer operation.
                if (pendingStart.acceptStandaloneFailure(operationId, eventSequence, state)) {
                    target.onSessionState(operationId, generation, eventSequence, state)
                }
                return
            }
            val failure = assembler.acceptSingle(generation, eventSequence)
            if (failure != null) {
                target.onTransportProtocolFailure(failure)
                return
            }
            if (state == AppServerSessionContract.STATE_STARTING ||
                state == AppServerSessionContract.STATE_READY ||
                state == AppServerSessionContract.STATE_FAILED
            ) pendingStart.complete(operationId)
            synchronized(this@BinderSessionRuntimeTransport) {
                if (generation > 0 && outboundGeneration != generation) {
                    outboundGeneration = generation
                    nextClientSequence = 1L
                }
            }
            target.onSessionState(operationId, generation, eventSequence, state)
        }

        override fun onFrameChunk(
            generation: Long,
            eventSequence: Long,
            chunkIndex: Int,
            chunkCount: Int,
            totalBytes: Int,
            payload: ByteArray,
        ) {
            val target = listener ?: return
            when (
                val result = assembler.acceptChunk(
                    generation = generation,
                    eventSequence = eventSequence,
                    chunkIndex = chunkIndex,
                    chunkCount = chunkCount,
                    totalBytes = totalBytes,
                    payload = payload,
                )
            ) {
                ServerChunkAssemblyResult.Incomplete -> Unit
                is ServerChunkAssemblyResult.Complete -> target.onServerFrame(
                    result.generation,
                    result.eventSequence,
                    result.frame,
                )
                is ServerChunkAssemblyResult.Rejected ->
                    target.onTransportProtocolFailure(result.failure)
            }
        }

        override fun onTransportNotice(
            generation: Long,
            eventSequence: Long,
            code: Int,
            relatedSequence: Long,
            detail: String,
        ) {
            val target = listener ?: return
            val failure = assembler.acceptSingle(generation, eventSequence)
            if (failure != null) {
                target.onTransportProtocolFailure(failure)
                return
            }
            target.onTransportNotice(generation, eventSequence, code, relatedSequence)
        }
    }

    @Synchronized
    override fun start(operationId: Long, listener: RuntimeSessionListener) {
        check(this.listener == null || this.listener === listener) {
            "Runtime transport already has a listener"
        }
        this.listener = listener
        pendingStart.begin(operationId)
        try {
            runtime.configurePhoneToolsBridge(phoneToolsBridge?.port ?: 0, phoneToolsBridge?.token.orEmpty())
            runtime.startAppServerSession(operationId, callback)
        } catch (failure: Exception) {
            pendingStart.complete(operationId)
            throw failure
        }
    }

    override fun restart(operationId: Long, expectedGeneration: Long) {
        pendingStart.begin(operationId)
        try {
            runtime.configurePhoneToolsBridge(phoneToolsBridge?.port ?: 0, phoneToolsBridge?.token.orEmpty())
            runtime.restartAppServerSession(operationId, expectedGeneration)
        } catch (failure: Exception) {
            pendingStart.complete(operationId)
            throw failure
        }
    }

    @Synchronized
    override fun sendFrame(generation: Long, frame: ByteArray) {
        require(frame.isNotEmpty()) { "App Server frame must not be empty" }
        require(frame.size <= AppServerSessionContract.MAX_CLIENT_FRAME_BYTES) {
            "App Server client frame exceeds the transport limit"
        }
        check(outboundGeneration == generation) { "App Server generation is not ready" }
        check(nextClientSequence < Long.MAX_VALUE) { "Client sequence exhausted" }
        val clientSequence = nextClientSequence
        val chunks = BinderFrameChunker.chunks(frame)
        chunks.forEach { chunk ->
            runtime.sendAppServerFrameChunk(
                generation,
                clientSequence,
                chunk.index,
                chunk.count,
                chunk.totalBytes,
                chunk.payload,
            )
        }
        nextClientSequence += 1
    }

    override fun stop(operationId: Long, expectedGeneration: Long) {
        runtime.stopAppServerSession(operationId, expectedGeneration)
    }
}

/** Correlates only out-of-band start failures; the assembler owns all real stream events. */
internal class PendingRuntimeStart {
    private var operationId: Long? = null

    @Synchronized
    fun begin(operationId: Long) {
        this.operationId = operationId
    }

    @Synchronized
    fun complete(operationId: Long): Boolean {
        if (this.operationId != operationId) return false
        this.operationId = null
        return true
    }

    @Synchronized
    fun acceptStandaloneFailure(operationId: Long, eventSequence: Long, state: Int): Boolean =
        eventSequence == 0L && state == AppServerSessionContract.STATE_FAILED && complete(operationId)
}
