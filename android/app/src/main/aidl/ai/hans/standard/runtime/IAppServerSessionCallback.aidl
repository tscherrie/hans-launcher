package ai.hans.standard.runtime;

/**
 * Version-1 callback contract for one persistent App Server session.
 *
 * Methods are intentionally synchronous: each small Binder transaction is
 * backpressure for the stdout reader, preventing an unbounded callback queue.
 */
interface IAppServerSessionCallback {
    void onSessionState(
        long operationId,
        long generation,
        long eventSequence,
        int state,
        int runtimePid,
        String detail
    );

    void onFrameChunk(
        long generation,
        long eventSequence,
        int chunkIndex,
        int chunkCount,
        int totalBytes,
        in byte[] payload
    );

    void onTransportNotice(
        long generation,
        long eventSequence,
        int code,
        long relatedSequence,
        String detail
    );
}
