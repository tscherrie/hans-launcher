package ai.hans.standard.voice.realtime

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException

/**
 * One sleeping worker owns native media objects. Callback threads enqueue and never wait;
 * callers that need effective-state acknowledgment may use [call]. No locks cross native APIs.
 */
internal class LiveVoiceTransportControl(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-live-native-control").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val worker = ThreadLocal<Boolean>()

    fun <T> call(block: () -> T): T {
        if (worker.get() == true) return block()
        val task = FutureTask<T> { onWorker(block) }
        executor.execute(task)
        return try {
            task.get()
        } catch (error: ExecutionException) {
            if (error.cause is Error) throw error.cause as Error
            throw (error.cause as? RuntimeException
                ?: IllegalStateException("live_native_control_failed", error.cause))
        } catch (error: InterruptedException) {
            // Never cancel queued cleanup when its caller is interrupted. Terminal guards make
            // stale requests harmless; owned publication/teardown must still drain in order.
            Thread.currentThread().interrupt()
            throw IllegalStateException("live_native_control_interrupted", error)
        }
    }

    fun execute(block: () -> Unit): Boolean = try {
        executor.execute { onWorker(block) }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun <T> onWorker(block: () -> T): T {
        worker.set(true)
        return try { block() } finally { worker.remove() }
    }

    /** Already-queued calls drain after close and observe the transport's terminal flag. */
    override fun close() = executor.shutdown()
}
