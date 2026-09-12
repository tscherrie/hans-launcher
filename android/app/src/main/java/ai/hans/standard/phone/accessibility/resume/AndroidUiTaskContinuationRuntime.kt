package ai.hans.standard.phone.accessibility.resume

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.phone.accessibility.UiInteractionAvailability
import android.content.Context

/**
 * Process-wide bridge shared by the dynamic-tool gate and public Android unlock signals.
 *
 * Phase B intentionally installs no dispatcher yet. The journal is durable and every unlock
 * signal reaches the coordinator, but [automaticResumeSupported] stays false until App Server has
 * a host-owned same-thread dispatch which is both non-visible and authority-bound.
 */
internal object AndroidUiTaskContinuationRuntime : UiTaskContinuationCheckpointer {
    @Volatile
    private var installed: Installed? = null

    const val automaticResumeSupported: Boolean = false

    @Synchronized
    fun install(context: Context): Boolean {
        if (installed != null) return true
        return runCatching {
            val journal = UiTaskContinuationJournal(
                AtomicFileUiTaskContinuationStorage(context.applicationContext),
            )
            journal.recoverClaimedAfterProcessDeath()
            journal.expireStaleWaiting()
            installed = Installed(
                journal = journal,
                coordinator = UiTaskContinuationCoordinator(
                    journal,
                    UiTaskContinuationDispatcher.NONE,
                ),
            )
            true
        }.getOrDefault(false)
    }

    override fun checkpoint(
        call: DynamicToolCallParams,
        availability: UiInteractionAvailability,
    ): UiTaskContinuationCheckpoint = installed?.journal?.checkpoint(call, availability)
        ?: UiTaskContinuationCheckpoint.NotPersisted

    /** Called only after a public-API unlock/interactivity probe succeeds. */
    fun onUserPresent(context: Context): UiTaskContinuationReconcileResult {
        install(context)
        return installed?.coordinator?.onUserPresent()
            ?: UiTaskContinuationReconcileResult.DISPATCH_UNAVAILABLE
    }

    private data class Installed(
        val journal: UiTaskContinuationJournal,
        val coordinator: UiTaskContinuationCoordinator,
    )
}
