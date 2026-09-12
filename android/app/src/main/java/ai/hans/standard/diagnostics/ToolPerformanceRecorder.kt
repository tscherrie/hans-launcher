package ai.hans.standard.diagnostics

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import java.util.concurrent.atomic.AtomicBoolean

/** Owner-enabled, process-local measurements. No arguments, output text, images or raw ids retained. */
internal class ToolPerformanceRecorder(
    private val clockNanos: () -> Long = System::nanoTime,
    private val capacity: Int = 64,
) {
    init { require(capacity in 1..128) }

    private var generation: Long? = null
    private var threadId: String? = null
    private var epoch = 0L
    private var enabled = false
    private var started = 0L
    private var completed = 0L
    private val samples = ArrayDeque<Map<String, Any>>()

    @Synchronized
    fun observeContext(generation: Long?, threadId: String?) {
        if (this.generation == generation && this.threadId == threadId) return
        this.generation = generation
        this.threadId = threadId
        enabled = false
        reset()
    }

    @Synchronized
    fun start(): Boolean {
        if (generation == null || threadId == null) return false
        reset()
        enabled = true
        return true
    }

    @Synchronized
    fun stop() { enabled = false }

    private fun reset() {
        epoch += 1
        started = 0
        completed = 0
        samples.clear()
    }

    @Synchronized
    fun begin(call: DynamicToolCallParams, declaredTool: Boolean = false): Attempt? {
        if (!enabled || call.threadId != threadId) return null
        // Only fixed first-party Computer Use/work labels are recorded. Plugin/user-defined
        // names can contain personal data and are intentionally collapsed to a constant.
        val label = if (declaredTool && call.namespace in RECORDED_NAMESPACES && SAFE_TOOL.matches(call.tool)) {
            "${call.namespace}.${call.tool}"
        } else "other"
        return Attempt(epoch, ++started, label, clockNanos())
    }

    @Synchronized
    fun finish(attempt: Attempt?, outcome: String, result: DynamicToolExecutionResult? = null) {
        if (attempt == null || !enabled || attempt.epoch != epoch || !attempt.finished.compareAndSet(false, true)) return
        val elapsed = (clockNanos() - attempt.startedNanos).coerceAtLeast(0)
        val sample = linkedMapOf<String, Any>(
            "sequence" to attempt.sequence,
            "tool" to attempt.tool,
            "durationMicros" to elapsed / 1_000,
            "outcome" to outcome,
        )
        result?.let {
            sample["resultTextUtf8Bytes"] = utf8Length(it.contentText)
            sample["imageCount"] = it.imageUrls.size
        }
        completed += 1
        if (samples.size == capacity) samples.removeFirst()
        samples.addLast(sample)
    }

    @Synchronized
    fun snapshot(): Map<String, Any> = linkedMapOf(
        "enabled" to enabled,
        "epoch" to epoch,
        "startedCalls" to started,
        "finishedCalls" to completed,
        "retainedSamples" to samples.size,
        "discardedSamples" to (completed - samples.size),
        "durationScope" to "executor_entry_to_result_or_cancel_including_local_queue_excluding_model_and_transport",
        "samples" to samples.map { it.toMap() },
    )

    internal class Attempt(
        val epoch: Long,
        val sequence: Long,
        val tool: String,
        val startedNanos: Long,
        val finished: AtomicBoolean = AtomicBoolean(false),
    )

    companion object {
        private val RECORDED_NAMESPACES = setOf(
            "android_ui", "android", "hans_workspace", "hans_artifact", "hans_work", "hans_files",
        )
        private val SAFE_TOOL = Regex("[a-z][a-z0-9_]{0,63}")

        /** Java/Android UTF-8 replacement is one byte for an unpaired UTF-16 surrogate. */
        internal fun utf8Length(text: String): Int {
            var bytes = 0
            var index = 0
            while (index < text.length) {
                val ch = text[index++]
                bytes += when {
                    ch.code < 0x80 -> 1
                    ch.code < 0x800 -> 2
                    ch.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
                        index += 1
                        4
                    }
                    ch.isSurrogate() -> 1
                    else -> 3
                }
            }
            return bytes
        }
    }
}

/** Transparent delegation: measurement failures must never affect tool completion or cancellation. */
internal class MeasuredDynamicToolExecutor(
    private val delegate: DynamicToolExecutor,
    private val recorder: ToolPerformanceRecorder,
) : DynamicToolExecutor {
    private val declaredTools = runCatching {
        delegate.specs.flatMap { namespace -> namespace.tools.map { namespace.name to it.name } }.toSet()
    }.getOrDefault(emptySet())
    override val specs get() = delegate.specs

    override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) {
        val attempt = runCatching { recorder.begin(call, (call.namespace to call.tool) in declaredTools) }.getOrNull()
        try {
            delegate.execute(call) { result ->
                record(attempt, if (result.success) "success" else "failure", result)
                completion(result)
            }
        } catch (failure: Exception) {
            record(attempt, "exception")
            throw failure
        }
    }

    override fun executeCancellable(
        call: DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle {
        val attempt = runCatching { recorder.begin(call, (call.namespace to call.tool) in declaredTools) }.getOrNull()
        try {
            val handle = delegate.executeCancellable(call, cancellation) { result ->
                record(attempt, if (result.success) "success" else "failure", result)
                completion(result)
            }
            return object : DynamicToolExecutionHandle {
                override fun cancel() = handle.cancel().also {
                    record(attempt, if (it == DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT) {
                        "cancelled_before_effect"
                    } else "cancel_requested_effect_may_have_started")
                }
            }
        } catch (failure: Exception) {
            record(attempt, "exception")
            throw failure
        }
    }

    override fun failureResult(call: DynamicToolCallParams, code: String) = delegate.failureResult(call, code)

    private fun record(
        attempt: ToolPerformanceRecorder.Attempt?,
        outcome: String,
        result: DynamicToolExecutionResult? = null,
    ) { runCatching { recorder.finish(attempt, outcome, result) } }
}
