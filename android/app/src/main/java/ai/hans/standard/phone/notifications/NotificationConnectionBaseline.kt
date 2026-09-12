package ai.hans.standard.phone.notifications

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets

/** Durable marker distinguishing initial permission baseline from later reconnect recovery. */
internal interface NotificationConnectionBaselineStore {
    fun isEstablished(): Boolean
    fun markEstablished(): Boolean
}

internal class AtomicFileNotificationConnectionBaselineStore(
    context: Context,
    fileName: String = FILE_NAME,
) : NotificationConnectionBaselineStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, fileName))

    @Synchronized
    override fun isEstablished(): Boolean = runCatching {
        file.readFully().contentEquals(CONTENT)
    }.getOrDefault(false)

    @Synchronized
    override fun markEstablished(): Boolean = runCatching {
        val output = file.startWrite()
        try {
            output.write(CONTENT)
            output.fd.sync()
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
        true
    }.getOrDefault(false)

    companion object {
        internal const val FILE_NAME = "notification-listener-baseline-v1"
        private val CONTENT = "baseline-v1\n".toByteArray(StandardCharsets.US_ASCII)
    }
}

/**
 * Pure policy for one active-notification snapshot. On the first successful connection every
 * item is persisted for inbox querying/reply, but none is announced. On later reconnects only a
 * genuinely new durable inbox event is eligible for triage; unchanged active notifications stay
 * duplicates.
 */
internal class NotificationConnectionBaselinePolicy(
    private val store: NotificationConnectionBaselineStore,
) {
    fun shouldQueueLiveWrite(result: NotificationWriteResult): Boolean =
        store.isEstablished() && result is NotificationWriteResult.Stored

    fun acceptSnapshot(
        signals: List<NotificationSignal.Upsert>,
        inboxWrite: (NotificationSignal.Upsert, queueForRestrictedTriage: Boolean) ->
            NotificationWriteResult,
        enqueueStored: (NotificationSignal.Upsert, NotificationWriteResult.Stored) -> Unit,
    ): NotificationConnectionBaselineResult {
        val establishedBeforeSnapshot = store.isEstablished()
        var stored = 0
        var queued = 0
        signals.forEach { signal ->
            when (val result = inboxWrite(signal, establishedBeforeSnapshot)) {
                is NotificationWriteResult.Stored -> {
                    stored += 1
                    if (establishedBeforeSnapshot) {
                        enqueueStored(signal, result)
                        queued += 1
                    }
                }
                is NotificationWriteResult.Duplicate -> Unit
                is NotificationWriteResult.ExcludedByPrivacy -> Unit
                NotificationWriteResult.PrunedByRetention -> Unit
            }
        }
        val baselineEstablished = establishedBeforeSnapshot || store.markEstablished()
        return NotificationConnectionBaselineResult(
            storedCount = stored,
            triageEligibleCount = queued,
            baselineEstablished = baselineEstablished,
        )
    }
}

internal data class NotificationConnectionBaselineResult(
    val storedCount: Int,
    val triageEligibleCount: Int,
    val baselineEstablished: Boolean,
)
