package ai.hans.standard.runtime.python;

import android.os.ParcelFileDescriptor;
import ai.hans.standard.runtime.python.IPythonRuntimeCallback;
import ai.hans.standard.runtime.python.IPythonStreamCallback;
import ai.hans.standard.runtime.python.IPythonCapabilityCallback;

/** Version-1 Binder boundary for the private, separate-process CPython worker. */
interface IPythonRuntimeService {
    int getProtocolVersion();
    String readState(String sessionNonce);

    void runReadinessGate(
        long operationId,
        String bootstrapManifestJson,
        in ParcelFileDescriptor stdlibFd,
        IPythonRuntimeCallback callback
    );
    void execute(
        long operationId,
        String sessionNonce,
        String requestJson,
        String leaseManifestJson,
        in ParcelFileDescriptor environmentFd,
        in @nullable ParcelFileDescriptor workspaceFd,
        IPythonRuntimeCallback callback,
        IPythonStreamCallback streamCallback,
        IPythonCapabilityCallback capabilityCallback
    );

    boolean cancel(String sessionNonce, String requestId);
    boolean completeCapability(String sessionNonce, String requestId, long sequence, String resultJson);
    void restart(String sessionNonce, long operationId, IPythonRuntimeCallback callback);
    void stop(String sessionNonce, long operationId, IPythonRuntimeCallback callback);
}
