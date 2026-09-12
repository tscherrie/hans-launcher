package ai.hans.standard.runtime.python;

/**
 * Synchronous by design: the return value provides Binder backpressure and prevents an
 * unbounded stdout/stderr callback queue in the Hans main process.
 */
interface IPythonStreamCallback {
    boolean onChunk(
        long operationId,
        String requestId,
        long sequence,
        int streamKind,
        in byte[] payload
    );
}
