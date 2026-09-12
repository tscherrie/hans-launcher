package ai.hans.standard.phone.notifications

import ai.hans.standard.notifications.NotificationIngressResult
import ai.hans.standard.notifications.NotificationTriageQueue

/**
 * Exact bridge from the durable SQLite inbox to restricted triage.
 *
 * Only a successfully stored [NotificationSignal.Upsert] may create model work. A removal never
 * creates work, but always invalidates matching undelivered queue records (including leases).
 */
internal class NotificationTriageIngress(
    private val queue: NotificationTriageQueue,
) {
    fun afterInboxWrite(
        signal: NotificationSignal,
        result: NotificationWriteResult,
    ): NotificationIngressResult? {
        if (signal is NotificationSignal.Removed) {
            val cancellation = queue.cancelOutstanding(signal.packageName, signal.androidKey)
            return NotificationIngressResult.CancelledRemovedNotification(
                recordsCancelled = cancellation.recordsCancelled,
                inFlightModelCancelled = cancellation.inFlightModelCancelled,
            )
        }
        val upsert = signal as NotificationSignal.Upsert
        val stored = result as? NotificationWriteResult.Stored ?: return null
        if (stored.sequence <= 0 || stored.kind == NotificationEventKind.REMOVED) return null

        return queue.ingest(
            NotificationInboxEvent(
                sequence = stored.sequence,
                kind = stored.kind,
                observedAtEpochMillis = upsert.observedAtEpochMillis,
                removalReason = null,
                snapshot = upsert.snapshot,
            ),
        )
    }
}
