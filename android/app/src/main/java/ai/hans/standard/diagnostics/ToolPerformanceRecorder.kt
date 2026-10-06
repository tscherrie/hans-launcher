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
    private val lifecycleCapacity: Int = 128,
) {
    init {
        require(capacity in 1..128)
        require(lifecycleCapacity in 1..256)
    }

    private var generation: Long? = null
    private var threadId: String? = null
    private var epoch = 0L
    private var enabled = false
    private var started = 0L
    private var completed = 0L
    private var originNanos: Long? = null
    private var lastObservedEndMicros: Long? = null
    private var unresolvedCancellations = 0L
    private val samples = ArrayDeque<MutableMap<String, Any>>()
    private var phaseContext = PerformanceContextToken()
    private var phase = PerformancePhase.UNKNOWN
    private var measuredThroughMicros = 0L
    private val phaseTotalsMicros = LongArray(PerformancePhase.entries.size)
    private var lastObservedEndPhaseTotals: LongArray? = null
    private var lifecycleEvents = 0L
    private val lifecycle = ArrayDeque<Map<String, Any>>()

    @Synchronized
    fun observeContext(generation: Long?, threadId: String?) {
        if (this.generation == generation && this.threadId == threadId) return
        this.generation = generation
        this.threadId = threadId
        phaseContext = PerformanceContextToken()
        phase = PerformancePhase.UNKNOWN
        enabled = false
        reset()
    }

    @Synchronized
    fun contextToken(): PerformanceContextToken = phaseContext

    @Synchronized
    fun observePhase(phase: PerformancePhase, context: PerformanceContextToken? = null) {
        if (context != null && context !== phaseContext) return
        if (this.phase == phase) return
        if (!enabled) {
            // Keep the effective state for a later opt-in, without reading a clock at idle.
            this.phase = phase
            return
        }
        val at = advancePhaseClock(clockNanos())
        this.phase = phase
        appendLifecycle(PerformanceEvent.PHASE_CHANGED, at)
    }

    @Synchronized
    fun recordEvent(event: PerformanceEvent, context: PerformanceContextToken? = null) {
        if (!enabled || (context != null && context !== phaseContext)) return
        appendLifecycle(event, advancePhaseClock(clockNanos()))
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
        originNanos = null
        lastObservedEndMicros = null
        unresolvedCancellations = 0
        samples.clear()
        measuredThroughMicros = 0
        phaseTotalsMicros.fill(0)
        lastObservedEndPhaseTotals = null
        lifecycleEvents = 0
        lifecycle.clear()
    }

    @Synchronized
    fun begin(call: DynamicToolCallParams, declaredTool: Boolean = false): Attempt? {
        if (!enabled || call.threadId != threadId) return null
        // Only fixed first-party Computer Use/work labels are recorded. Plugin/user-defined
        // names can contain personal data and are intentionally collapsed to a constant.
        val label = if (declaredTool && call.namespace in RECORDED_NAMESPACES && SAFE_TOOL.matches(call.tool)) {
            "${call.namespace}.${call.tool}"
        } else "other"
        // Lifecycle and tools share one relative clock, starting at the first observed event.
        // Enabling recording or reading diagnostics never wakes an idle device/read its clock.
        val now = clockNanos()
        val startedMicros = advancePhaseClock(now)
        val activeAtStart = started - completed
        val gapState = when {
            started == 0L -> "first_call"
            activeAtStart > 0 -> "overlap"
            unresolvedCancellations > 0 -> "cancelled_call_completion_unknown"
            else -> "measured"
        }
        val gapBeforeMicros = if (gapState == "measured") {
            lastObservedEndMicros?.let { (startedMicros - it).coerceAtLeast(0) }
        } else null
        return Attempt(
            epoch, ++started, label, now, startedMicros, activeAtStart, gapState,
            gapBeforeMicros, phase,
            if (gapBeforeMicros != null) lastObservedEndPhaseTotals?.let { previous ->
                LongArray(phaseTotalsMicros.size) { (phaseTotalsMicros[it] - previous[it]).coerceAtLeast(0) }
            } else null,
        )
    }

    @Synchronized
    fun finish(attempt: Attempt?, outcome: String, result: DynamicToolExecutionResult? = null) {
        if (attempt == null || !enabled || attempt.epoch != epoch) return
        if (attempt.finished.get()) {
            // A cancellation receipt is not a completion receipt. Until the real result arrives,
            // never present subsequent time as an idle gap: the operation may still be running.
            if (attempt.awaitingCompletion && result != null) {
                settleCancellation(attempt, "completionObservedMicros")
            }
            return
        }
        val now = clockNanos()
        if (!attempt.finished.compareAndSet(false, true)) return
        val elapsed = (now - attempt.startedNanos).coerceAtLeast(0)
        val endedMicros = advancePhaseClock(now)
        val sample = linkedMapOf<String, Any>(
            "sequence" to attempt.sequence,
            "tool" to attempt.tool,
            "startedMicros" to attempt.startedMicros,
            "endedMicros" to endedMicros,
            "durationMicros" to elapsed / 1_000,
            "activeCallsAtStart" to attempt.activeCallsAtStart,
            "gapBeforeState" to attempt.gapBeforeState,
            "phaseAtStart" to attempt.phaseAtStart.wire,
            "outcome" to outcome,
        )
        attempt.gapBeforeMicros?.let { sample["gapBeforeMicros"] = it }
        attempt.gapBeforePhaseMicros?.let { sample["gapBeforePhasesMicros"] = phasePayload(it) }
        result?.let {
            sample["resultTextUtf8Bytes"] = utf8Length(it.contentText)
            sample["imageCount"] = it.imageUrls.size
            if (!it.success) {
                val failure = it.failureDiagnostic ?: ToolFailureDiagnostic(ToolFailureCode.UNKNOWN)
                sample["failureCode"] = failure.code.wire
                failure.detail?.let { detail -> sample["failureDetail"] = detail.wire }
            }
        }
        if (outcome == "cancel_requested_effect_may_have_started" || outcome == "cancelled_before_effect") {
            // No external effect does not imply no remaining local work: a running executor
            // can still be unwinding. Both dispositions require a completion/quiescence receipt.
            attempt.awaitingCompletion = true
            unresolvedCancellations += 1
        }
        lastObservedEndMicros = endedMicros
        lastObservedEndPhaseTotals = phaseTotalsMicros.copyOf()
        completed += 1
        if (samples.size == capacity) samples.removeFirst()
        samples.addLast(sample)
    }

    @Synchronized
    fun observeQuiescence(attempt: Attempt?) {
        if (attempt == null || !enabled || attempt.epoch != epoch || !attempt.awaitingCompletion) return
        settleCancellation(attempt, "quiescentMicros")
    }

    private fun settleCancellation(attempt: Attempt, field: String) {
        val settledMicros = advancePhaseClock(clockNanos())
        attempt.awaitingCompletion = false
        unresolvedCancellations -= 1
        lastObservedEndMicros = settledMicros
        lastObservedEndPhaseTotals = phaseTotalsMicros.copyOf()
        // A handle must not extend the lifetime of an evicted diagnostic sample. Settlement
        // is rare; look up the optional sample in the bounded ring instead of retaining it.
        samples.firstOrNull { it["sequence"] == attempt.sequence }?.set(field, settledMicros)
    }

    @Synchronized
    fun snapshot(): Map<String, Any> = linkedMapOf(
        "enabled" to enabled,
        "epoch" to epoch,
        "startedCalls" to started,
        "finishedCalls" to completed,
        "activeCalls" to (started - completed),
        "unresolvedCancellationCalls" to unresolvedCancellations,
        "retainedSamples" to samples.size,
        "discardedSamples" to (completed - samples.size),
        "durationScope" to "executor_entry_to_result_or_cancel_including_local_queue_excluding_model_and_transport",
        "timingOrigin" to "first_recorded_lifecycle_or_tool_event_in_this_epoch_monotonic_not_wall_clock",
        "gapScope" to "previous_observed_end_to_next_entry_with_no_recorded_call_active_includes_user_model_transport_and_uninstrumented_work_not_cause_attribution",
        "sampleOrder" to "terminal_event_order_use_sequence_for_start_order",
        "samples" to samples.map { sample ->
            sample.mapValues { (_, value) -> if (value is Map<*, *>) value.toMap() else value }
        },
        "currentPhase" to phase.wire,
        "phaseClockStarted" to (originNanos != null),
        "phaseMeasuredThroughMicros" to measuredThroughMicros,
        "phaseTotalsMicros" to phasePayload(phaseTotalsMicros),
        "phaseScope" to "observable_session_state_not_cause_attribution_turn_active_includes_local_tools_model_transport_and_uninstrumented_work_dispatch_pending_includes_send_ack_latency_idle_is_not_user_thinking",
        "phaseTotalsScope" to "through_last_recorded_event_only_overlaps_tool_durations_do_not_sum_with_tool_time",
        "recordedLifecycleEvents" to lifecycleEvents,
        "retainedLifecycleEvents" to lifecycle.size,
        "discardedLifecycleEvents" to (lifecycleEvents - lifecycle.size),
        "lifecycle" to lifecycle.map { it.toMap() },
    )

    private fun advancePhaseClock(now: Long): Long {
        if (originNanos == null) {
            originNanos = now
            appendLifecycle(PerformanceEvent.RECORDING_PHASE_SEED, 0)
            return 0
        }
        val at = offsetMicros(now).coerceAtLeast(measuredThroughMicros)
        phaseTotalsMicros[phase.ordinal] += at - measuredThroughMicros
        measuredThroughMicros = at
        return at
    }

    private fun appendLifecycle(event: PerformanceEvent, atMicros: Long) {
        val entry = linkedMapOf<String, Any>(
            "sequence" to ++lifecycleEvents,
            "atMicros" to atMicros,
            "event" to event.wire,
            "phase" to phase.wire,
        )
        if (lifecycle.size == lifecycleCapacity) lifecycle.removeFirst()
        lifecycle.addLast(entry)
    }

    private fun phasePayload(totals: LongArray): Map<String, Long> =
        PerformancePhase.entries.associate { it.wire to totals[it.ordinal] }

    private fun offsetMicros(now: Long): Long = (now - checkNotNull(originNanos)).coerceAtLeast(0) / 1_000

    internal class Attempt(
        val epoch: Long,
        val sequence: Long,
        val tool: String,
        val startedNanos: Long,
        val startedMicros: Long,
        val activeCallsAtStart: Long,
        val gapBeforeState: String,
        val gapBeforeMicros: Long?,
        val phaseAtStart: PerformancePhase,
        val gapBeforePhaseMicros: LongArray?,
        val finished: AtomicBoolean = AtomicBoolean(false),
    ) {
        // Accessed only under the recorder monitor. No samples or extra attempt collection
        // are retained by the handle beyond its own constant-size lifecycle metadata.
        var awaitingCompletion = false
    }

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
            val cancellationReceiptRequested = AtomicBoolean(false)
            return object : DynamicToolExecutionHandle {
                override fun onQuiescent(listener: () -> Unit): Boolean = handle.onQuiescent(listener)

                override fun cancel() = handle.cancel().also {
                    record(attempt, if (it == DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT) {
                        "cancelled_before_effect"
                    } else "cancel_requested_effect_may_have_started")
                    if (attempt != null && cancellationReceiptRequested.compareAndSet(false, true)) {
                        // A cancelled executor may deliberately suppress its result callback.
                        // Use its optional physical-completion receipt without polling. If it is
                        // unavailable, retain the explicit unknown-gap state instead of guessing.
                        runCatching {
                            handle.onQuiescent { runCatching { recorder.observeQuiescence(attempt) } }
                        }
                    }
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
