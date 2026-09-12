package ai.hans.standard.runtime.python

import java.util.concurrent.atomic.AtomicBoolean

/** Idempotent owner-facing lease that always releases its worker cancellation hook once. */
class PythonExecutionLease internal constructor(
    private val handle: PythonExecutionHandle,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) handle.cancel()
    }
}
