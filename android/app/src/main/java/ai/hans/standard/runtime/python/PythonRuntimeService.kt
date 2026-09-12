package ai.hans.standard.runtime.python

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import java.util.concurrent.atomic.AtomicReference

/**
 * Private isolated worker. It has neither Hans's UID/data access nor INTERNET; all readable bytes
 * arrive as narrowly scoped, read-only file-descriptor capabilities from the authenticated owner.
 */
class PythonRuntimeService : Service() {
    private lateinit var engine: PythonNativeExecutionEngine
    private val session = AtomicReference<WorkerSession?>()

    private val binder = object : IPythonRuntimeService.Stub() {
        override fun getProtocolVersion(): Int {
            enforceOwnerUid()
            return PythonRuntimeContract.PROTOCOL_VERSION
        }

        override fun readState(sessionNonce: String): String {
            enforceSession(sessionNonce)
            return PythonRuntimeContract.encodeSnapshot(engine.snapshot())
        }

        override fun runReadinessGate(
            operationId: Long,
            bootstrapManifestJson: String,
            stdlibFd: ParcelFileDescriptor,
            callback: IPythonRuntimeCallback,
        ) {
            enforceOwnerUid()
            val manifest = PythonRuntimeFdContract.decodeBootstrap(bootstrapManifestJson)
            val owned = try {
                PythonRuntimeFdContract.duplicateReadOnly(stdlibFd, manifest.expectedStdlibBytes)
            } finally {
                runCatching(stdlibFd::close)
            }
            configureSession(manifest, owned)
            engine.runReadinessGate(operationId, observer(callback))
        }

        override fun execute(
            operationId: Long,
            sessionNonce: String,
            requestJson: String,
            leaseManifestJson: String,
            environmentFd: ParcelFileDescriptor,
            workspaceFd: ParcelFileDescriptor?,
            callback: IPythonRuntimeCallback,
            streamCallback: IPythonStreamCallback,
            capabilityCallback: IPythonCapabilityCallback,
        ) {
            enforceSession(sessionNonce)
            val request = PythonRuntimeContract.decodeRequest(
                requestJson,
                android.os.SystemClock.elapsedRealtime(),
            )
            val manifest = PythonRuntimeFdContract.decodeExecutionLease(
                leaseManifestJson,
                sessionNonce,
                request,
            )
            val descriptor = try {
                PythonRuntimeFdContract.duplicateReadOnly(environmentFd, manifest.environmentBytes)
            } finally {
                runCatching(environmentFd::close)
            }
            val workspaceDescriptor = try {
                when (val workspace = manifest.workspace) {
                    null -> {
                        require(workspaceFd == null) { "Unexpected Python workspace descriptor" }
                        null
                    }
                    else -> PythonRuntimeFdContract.duplicateReadOnly(
                        requireNotNull(workspaceFd) { "Python workspace descriptor is missing" },
                        workspace.archiveBytes,
                    )
                }
            } catch (error: Throwable) {
                descriptor.close()
                throw error
            } finally {
                runCatching { workspaceFd?.close() }
            }
            engine.execute(
                operationId,
                requestJson,
                PythonNativeExecutionLease(manifest, descriptor, workspaceDescriptor),
                observer(callback, streamCallback, capabilityCallback),
            )
        }

        override fun cancel(sessionNonce: String, requestId: String): Boolean {
            enforceSession(sessionNonce)
            return engine.cancel(requestId)
        }

        override fun completeCapability(
            sessionNonce: String,
            requestId: String,
            sequence: Long,
            resultJson: String,
        ): Boolean {
            enforceSession(sessionNonce)
            return engine.completeCapability(requestId, sequence, resultJson)
        }

        override fun restart(
            sessionNonce: String,
            operationId: Long,
            callback: IPythonRuntimeCallback,
        ) {
            enforceSession(sessionNonce)
            engine.restart(operationId, observer(callback))
        }

        override fun stop(
            sessionNonce: String,
            operationId: Long,
            callback: IPythonRuntimeCallback,
        ) {
            enforceSession(sessionNonce)
            engine.stop(operationId, observer(callback))
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = PythonNativeExecutionEngine(
            bootstrapProvider = {
                session.get()?.bootstrap ?: error("Python runtime has no authenticated bootstrap")
            },
            nowElapsedRealtimeMillis = android.os.SystemClock::elapsedRealtime,
            // A native extension which ignores cancellation cannot escape this isolated process.
            hardAbort = { Process.killProcess(Process.myPid()) },
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        engine.shutdown()
        super.onDestroy()
    }

    private fun observer(
        callback: IPythonRuntimeCallback,
        streamCallback: IPythonStreamCallback? = null,
        capabilityCallback: IPythonCapabilityCallback? = null,
    ): PythonRuntimeObserver = object : PythonRuntimeObserver {
        override fun onState(operationId: Long, snapshot: PythonRuntimeSnapshot) {
            try {
                callback.onState(operationId, PythonRuntimeContract.encodeSnapshot(snapshot))
            } catch (_: RemoteException) {
                snapshot.activeRequestId?.let(engine::cancel)
            }
        }

        override fun onResult(operationId: Long, resultJson: String) {
            try {
                callback.onResult(operationId, resultJson)
            } catch (_: RemoteException) {
                // The caller died. Engine cleanup and descriptor release continue locally.
            }
        }

        override fun onStream(operationId: Long, chunk: PythonStreamChunk): Boolean {
            val target = streamCallback ?: return false
            return try {
                target.onChunk(
                    operationId,
                    chunk.requestId,
                    chunk.sequence,
                    chunk.kind.wireValue,
                    chunk.payload,
                )
            } catch (_: RemoteException) {
                false
            }
        }

        override fun onCapabilityRequest(
            operationId: Long,
            request: PythonCapabilityRequest,
        ) {
            val target = capabilityCallback
            if (target == null) {
                engine.completeCapability(
                    request.requestId,
                    request.sequence,
                    PythonRuntimeContract.capabilityFailure(request, "capability_unavailable"),
                )
                return
            }
            try {
                target.onCapabilityRequest(
                    operationId,
                    request.requestId,
                    request.sequence,
                    request.capabilityJson,
                )
            } catch (_: RemoteException) {
                engine.completeCapability(
                    request.requestId,
                    request.sequence,
                    PythonRuntimeContract.capabilityFailure(request, "capability_client_died"),
                )
            }
        }
    }

    private fun configureSession(
        manifest: PythonRuntimeBootstrapManifest,
        descriptor: PythonOwnedFileDescriptor,
    ) {
        val candidate = WorkerSession(
            manifest,
            PythonNativeBootstrapLease(
                manifest = manifest,
                descriptor = descriptor,
                nativeLibraryDirectory = applicationInfo.nativeLibraryDir,
            ),
        )
        if (session.compareAndSet(null, candidate)) return
        val existing = session.get() ?: run {
            candidate.bootstrap.close()
            throw IllegalStateException("Python session disappeared")
        }
        val matches = PythonRuntimeFdContract.constantTimeEquals(
            existing.manifest.sessionNonce,
            manifest.sessionNonce,
        ) && existing.manifest == manifest
        candidate.bootstrap.close()
        if (!matches) throw SecurityException("Python bootstrap session cannot be replaced")
    }

    private fun enforceOwnerUid() {
        if (Binder.getCallingUid() != applicationInfo.uid) {
            throw SecurityException("Caller is not the Hans owner UID")
        }
    }

    private fun enforceSession(sessionNonce: String) {
        enforceOwnerUid()
        val expected = session.get()?.manifest?.sessionNonce
            ?: throw SecurityException("Python bootstrap session is not established")
        if (!PythonRuntimeFdContract.constantTimeEquals(expected, sessionNonce)) {
            throw SecurityException("Python bootstrap session does not match")
        }
    }

    private data class WorkerSession(
        val manifest: PythonRuntimeBootstrapManifest,
        val bootstrap: PythonNativeBootstrapLease,
    )
}
