package ai.hans.standard.runtime.python

object PythonRuntimeReadinessGate {
    fun evaluate(
        nativeResultJson: String,
        expected: PythonRuntimeBootstrapManifest,
    ): PythonRuntimeReadiness = PythonRuntimeContract.decodeReadiness(nativeResultJson).also { result ->
        if (result.ready) {
            require(result.pythonVersion == expected.expectedPythonVersion) {
                "Python runtime version does not match its signed manifest"
            }
            require(result.abi == expected.expectedAbi) {
                "Python runtime ABI does not match its signed manifest"
            }
            require(result.stdlibDigest == expected.expectedStdlibDigest) {
                "Python stdlib does not match its signed manifest"
            }
        }
    }
}
