package ai.hans.standard.runtime.python;

/** Delivers one broker request. Its eventual response returns through completeCapability(). */
oneway interface IPythonCapabilityCallback {
    void onCapabilityRequest(
        long operationId,
        String requestId,
        long sequence,
        String requestJson
    );
}
