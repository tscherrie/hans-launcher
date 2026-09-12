package ai.hans.standard.runtime.python

/** Pure, synchronized state machine shared by service and JVM contract tests. */
class PythonRuntimeStateMachine(
    private val runtimePid: Int,
) {
    private var generation = 0L
    private var phase = PythonRuntimePhase.STOPPED
    private var activeRequestId: String? = null
    private var readiness: PythonRuntimeReadiness? = null
    private var detail: String? = null

    @Synchronized
    fun snapshot(): PythonRuntimeSnapshot = PythonRuntimeSnapshot(
        phase = phase,
        generation = generation,
        runtimePid = runtimePid,
        activeRequestId = activeRequestId,
        readiness = readiness,
        detail = detail,
    )

    @Synchronized
    fun beginStart(restarting: Boolean = false): PythonRuntimeSnapshot {
        require(phase in setOf(
            PythonRuntimePhase.STOPPED,
            PythonRuntimePhase.DEGRADED,
            PythonRuntimePhase.CRASHED,
        )) { "Cannot start Python from $phase" }
        generation += 1
        phase = if (restarting) PythonRuntimePhase.RESTARTING else PythonRuntimePhase.STARTING
        activeRequestId = null
        readiness = null
        detail = null
        return snapshot()
    }

    @Synchronized
    fun finishStart(result: PythonRuntimeReadiness): PythonRuntimeSnapshot {
        require(phase in setOf(PythonRuntimePhase.STARTING, PythonRuntimePhase.RESTARTING))
        readiness = result
        phase = if (result.ready) PythonRuntimePhase.READY else PythonRuntimePhase.DEGRADED
        detail = result.detail
        return snapshot()
    }

    @Synchronized
    fun beginExecution(requestId: String): PythonRuntimeSnapshot {
        require(phase == PythonRuntimePhase.READY) { "Python runtime is not ready" }
        phase = PythonRuntimePhase.RUNNING
        activeRequestId = requestId
        detail = null
        return snapshot()
    }

    @Synchronized
    fun beginCancellation(requestId: String): PythonRuntimeSnapshot? {
        if (phase != PythonRuntimePhase.RUNNING || activeRequestId != requestId) return null
        phase = PythonRuntimePhase.CANCELLING
        return snapshot()
    }

    @Synchronized
    fun finishExecution(requestId: String): PythonRuntimeSnapshot {
        require(activeRequestId == requestId) { "Stale Python execution completion" }
        require(phase in setOf(PythonRuntimePhase.RUNNING, PythonRuntimePhase.CANCELLING))
        activeRequestId = null
        phase = if (readiness?.ready == true) PythonRuntimePhase.READY else PythonRuntimePhase.DEGRADED
        return snapshot()
    }

    @Synchronized
    fun crash(detail: String): PythonRuntimeSnapshot {
        phase = PythonRuntimePhase.CRASHED
        activeRequestId = null
        this.detail = detail.take(8_192)
        return snapshot()
    }

    @Synchronized
    fun beginShutdown(): PythonRuntimeSnapshot {
        phase = PythonRuntimePhase.SHUTTING_DOWN
        activeRequestId = null
        return snapshot()
    }

    @Synchronized
    fun stopped(): PythonRuntimeSnapshot {
        phase = PythonRuntimePhase.STOPPED
        activeRequestId = null
        readiness = null
        detail = null
        return snapshot()
    }
}
