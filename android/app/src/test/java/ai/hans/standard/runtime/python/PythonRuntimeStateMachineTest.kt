package ai.hans.standard.runtime.python

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PythonRuntimeStateMachineTest {
    private val ready = PythonRuntimeReadiness(
        ready = true,
        pythonVersion = "3.14.2",
        abi = "arm64-v8a",
        stdlibDigest = "a".repeat(64),
        verifiedImports = setOf("json", "asyncio", "sqlite3", "_ssl", "_hans_android"),
        writableNativeImports = false,
    )

    @Test
    fun normalLifecycleIsExplicitAndGenerationBound() {
        val state = PythonRuntimeStateMachine(runtimePid = 44)

        assertEquals(PythonRuntimePhase.STOPPED, state.snapshot().phase)
        assertEquals(1, state.beginStart().generation)
        assertEquals(PythonRuntimePhase.READY, state.finishStart(ready).phase)
        assertEquals("request-1", state.beginExecution("request-1").activeRequestId)
        assertEquals(PythonRuntimePhase.CANCELLING, state.beginCancellation("request-1")?.phase)
        assertEquals(PythonRuntimePhase.READY, state.finishExecution("request-1").phase)
        assertNull(state.snapshot().activeRequestId)
    }

    @Test
    fun staleCancellationAndCompletionCannotAffectAnotherRun() {
        val state = PythonRuntimeStateMachine(44)
        state.beginStart()
        state.finishStart(ready)
        state.beginExecution("current")

        assertNull(state.beginCancellation("stale"))
        assertThrows(IllegalArgumentException::class.java) { state.finishExecution("stale") }
        assertEquals("current", state.snapshot().activeRequestId)
    }

    @Test
    fun crashClearsAmbiguousRequestAndPermitsARealRestart() {
        val state = PythonRuntimeStateMachine(44)
        state.beginStart()
        state.finishStart(ready)
        state.beginExecution("request-1")

        assertEquals(PythonRuntimePhase.CRASHED, state.crash("native crash").phase)
        assertNull(state.snapshot().activeRequestId)
        assertEquals(PythonRuntimePhase.RESTARTING, state.beginStart(restarting = true).phase)
        assertEquals(2, state.snapshot().generation)
    }
}
