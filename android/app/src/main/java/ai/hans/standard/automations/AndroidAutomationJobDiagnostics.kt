package ai.hans.standard.automations

import android.app.job.JobScheduler
import android.os.Build
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import java.util.ArrayDeque

internal data class AutomationPendingReasonHistory(
    val timestampMillis: Long,
    val reasons: List<Int>,
)

internal data class AutomationJobStopDiagnostic(
    val jobId: Int,
    val stopReason: Int,
    val pendingReasons: List<Int>,
    val pendingHistory: List<AutomationPendingReasonHistory>,
)

internal interface AutomationJobDiagnosticsPlatform {
    fun pendingReasons(jobId: Int): IntArray
    fun pendingHistory(jobId: Int): List<AutomationPendingReasonHistory>
}

internal fun interface AutomationJobDiagnosticSink {
    fun record(value: AutomationJobStopDiagnostic)
}

/**
 * Captures Android's public stop/pending reason codes without task text, automation IDs, or account
 * data. Stop reasons remain diagnostic only; correctness never branches on a closed list of codes.
 */
internal class AndroidAutomationJobDiagnostics(
    private val platform: AutomationJobDiagnosticsPlatform,
    private val sink: AutomationJobDiagnosticSink,
) {
    fun recordStop(jobId: Int, stopReason: Int) {
        val pending = runCatching { platform.pendingReasons(jobId) }
            .getOrDefault(IntArray(0))
            .distinct()
            .sorted()
            .take(MAX_REASONS)
        val history = runCatching { platform.pendingHistory(jobId) }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { it.timestampMillis >= 0L }
            .map { item ->
                item.copy(reasons = item.reasons.distinct().sorted().take(MAX_REASONS))
            }
            .fold(ArrayDeque<AutomationPendingReasonHistory>()) { bounded, item ->
                if (bounded.size == MAX_HISTORY_ENTRIES) bounded.removeFirst()
                bounded.addLast(item)
                bounded
            }
            .toList()
        runCatching {
            sink.record(
                AutomationJobStopDiagnostic(
                    jobId = jobId,
                    stopReason = stopReason,
                    pendingReasons = pending,
                    pendingHistory = history,
                ),
            )
        }
    }

    companion object {
        fun create(service: android.app.Service): AndroidAutomationJobDiagnostics {
            val scheduler = service.getSystemService(JobScheduler::class.java)
            return AndroidAutomationJobDiagnostics(
                platform = AndroidJobDiagnosticsPlatform(scheduler),
                sink = AutomationJobDiagnosticSink { value ->
                    val history = value.pendingHistory.joinToString(",") { item ->
                        "${item.timestampMillis}:${item.reasons.joinToString("+")}"
                    }
                    Log.i(
                        TAG,
                        "job=${value.jobId} stop=${value.stopReason} " +
                            "pending=${value.pendingReasons.joinToString("+")} history=$history",
                    )
                },
            )
        }

        @ChecksSdkIntAtLeast(api = 36)
        internal fun supportsPendingReasonHistory(sdkInt: Int): Boolean = sdkInt >= 36

        private const val TAG = "HansAutomationJob"
        private const val MAX_REASONS = 24
        private const val MAX_HISTORY_ENTRIES = 8
    }
}

private class AndroidJobDiagnosticsPlatform(
    private val scheduler: JobScheduler,
) : AutomationJobDiagnosticsPlatform {
    override fun pendingReasons(jobId: Int): IntArray =
        if (AndroidAutomationJobDiagnostics.supportsPendingReasonHistory(Build.VERSION.SDK_INT)) {
            scheduler.getPendingJobReasons(jobId)
        } else {
            IntArray(0)
        }

    override fun pendingHistory(jobId: Int): List<AutomationPendingReasonHistory> =
        if (AndroidAutomationJobDiagnostics.supportsPendingReasonHistory(Build.VERSION.SDK_INT)) {
            scheduler.getPendingJobReasonsHistory(jobId).map { item ->
                AutomationPendingReasonHistory(
                    timestampMillis = item.timestampMillis,
                    reasons = item.pendingJobReasons.toList(),
                )
            }
        } else {
            emptyList()
        }
}
