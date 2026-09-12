package ai.hans.standard.plugins

import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.runtime.python.PythonRuntimePhase
import ai.hans.standard.runtime.python.PythonRuntimeReadiness
import ai.hans.standard.runtime.python.PythonRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidRuntimeEvidenceProjectionTest {
    @Test
    fun packagedCodeModeIsNotReportedReadyBeforeItsLiveGate() {
        val evidence = AndroidRuntimeEvidenceProjection.codeMode(
            runtimePhase = null,
            packagedVersion = "0.154.0",
            abi = PluginRuntimeAbi.ANDROID_ARM64_V8A,
        )

        assertEquals(PluginRuntimeReadiness.DEGRADED, evidence.readiness)
        assertEquals("0.154.0", evidence.version)
    }

    @Test
    fun readySessionProvesCodeModeBecauseItIsAnAppServerStartupPrerequisite() {
        val evidence = AndroidRuntimeEvidenceProjection.codeMode(
            runtimePhase = ClientRuntimePhase.READY,
            packagedVersion = "0.154.0",
            abi = PluginRuntimeAbi.ANDROID_ARM64_V8A,
        )

        assertEquals(PluginRuntimeReadiness.READY, evidence.readiness)
    }

    @Test
    fun pythonNeedsBothReadyPhaseAndSuccessfulReadinessGate() {
        val notProven = AndroidRuntimeEvidenceProjection.python(
            snapshot = PythonRuntimeSnapshot(
                phase = PythonRuntimePhase.READY,
                generation = 1,
                runtimePid = 42,
                readiness = PythonRuntimeReadiness(ready = false, pythonVersion = "3.14.7"),
            ),
            packagedVersion = "3.14.7",
            abi = PluginRuntimeAbi.ANDROID_ARM64_V8A,
        )
        val proven = AndroidRuntimeEvidenceProjection.python(
            snapshot = PythonRuntimeSnapshot(
                phase = PythonRuntimePhase.RUNNING,
                generation = 1,
                runtimePid = 42,
                readiness = PythonRuntimeReadiness(ready = true, pythonVersion = "3.14.7"),
            ),
            packagedVersion = "3.14.7",
            abi = PluginRuntimeAbi.ANDROID_ARM64_V8A,
        )

        assertEquals(PluginRuntimeReadiness.DEGRADED, notProven.readiness)
        assertEquals(PluginRuntimeReadiness.READY, proven.readiness)
    }
}
