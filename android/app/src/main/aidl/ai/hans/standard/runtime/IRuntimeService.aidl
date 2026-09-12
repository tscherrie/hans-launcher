package ai.hans.standard.runtime;

import ai.hans.standard.runtime.IRuntimeProbeCallback;
import ai.hans.standard.runtime.ICodexRuntimeCallback;
import ai.hans.standard.runtime.IAppServerSessionCallback;

interface IRuntimeService {
    void runNativeProbe(long requestId, IRuntimeProbeCallback callback);
    void runCodexReadinessGate(long requestId, ICodexRuntimeCallback callback);

    int getSessionProtocolVersion();
    void startAppServerSession(long operationId, IAppServerSessionCallback callback);
    void restartAppServerSession(long operationId, long expectedGeneration);
    void sendAppServerFrameChunk(
        long generation,
        long clientSequence,
        int chunkIndex,
        int chunkCount,
        int totalBytes,
        in byte[] payload
    );
    void stopAppServerSession(long operationId, long expectedGeneration);
    String readNativeMemoryHealth(long expectedGeneration);
}
