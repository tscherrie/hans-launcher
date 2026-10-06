package ai.hans.standard.integration

import ai.hans.standard.diagnostics.PerformanceEvent
import ai.hans.standard.diagnostics.PerformancePhase

/**
 * Optional, synchronous diagnostic port. Identity is used only for the recorder's
 * existing context fence; no input, output, command or raw protocol frame crosses it.
 * Callers isolate observer failures from session operation.
 */
internal interface PerformanceSessionObserver {
    fun onState(generation: Long?, threadId: String?, phase: PerformancePhase)
    fun onEvent(event: PerformanceEvent)

    companion object {
        val NONE: PerformanceSessionObserver = object : PerformanceSessionObserver {
            override fun onState(generation: Long?, threadId: String?, phase: PerformancePhase) = Unit
            override fun onEvent(event: PerformanceEvent) = Unit
        }
    }
}
