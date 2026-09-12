package ai.hans.standard.runtime;

/**
 * One-shot evidence returned by the bounded embedded-runtime readiness gate.
 * Every field is deliberately explicit so callers never infer readiness from
 * process creation alone.
 */
oneway interface ICodexRuntimeCallback {
    void onResult(
        long requestId,
        int runtimePid,
        String executablePath,
        long executableBytes,
        int versionExitCode,
        String version,
        String versionStdout,
        String versionStderr,
        int initializeExitCode,
        String userAgent,
        String codexHome,
        String platformFamily,
        String platformOs,
        String initializeResponseJson,
        String initializeStderr,
        boolean timedOut,
        boolean cleanupSucceeded,
        String error
    );
}
