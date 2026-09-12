package ai.hans.standard.runtime;

oneway interface IRuntimeProbeCallback {
    void onResult(
        long requestId,
        int runtimePid,
        int exitCode,
        String stdout,
        String stderr,
        String error
    );
}

