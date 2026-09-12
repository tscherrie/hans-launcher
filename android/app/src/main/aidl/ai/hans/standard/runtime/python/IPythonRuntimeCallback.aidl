package ai.hans.standard.runtime.python;

/** Small, versioned lifecycle/result envelopes. Payload limits are enforced by both peers. */
oneway interface IPythonRuntimeCallback {
    void onState(long operationId, String stateJson);
    void onResult(long operationId, String resultJson);
}
