package ai.hans.standard.phone.notifications

import android.os.SystemClock
import android.service.notification.StatusBarNotification

/**
 * Memory-only receipt for the setup notification-listener probe. The structurally unique Hans
 * self-notification is consumed before privacy capture, Inbox persistence, triage, recall, or any
 * model boundary. It never weakens the permanent own-package protection.
 */
internal class NotificationSetupLiveTestReceiptTracker private constructor(
    private val elapsedRealtimeMillis: () -> Long,
    private val receiptTtlMillis: Long,
) {
    private val monitor = Any()
    private var attempt: Attempt? = null

    fun arm(nonce: String): Boolean {
        if (!isBoundedNonce(nonce)) return false
        val now = elapsedRealtimeMillis()
        synchronized(monitor) {
            attempt = Attempt(
                nonce = nonce,
                expiresAtElapsedMillis = saturatingAdd(now, receiptTtlMillis),
                verified = false,
            )
        }
        return true
    }

    fun clear(nonce: String) {
        synchronized(monitor) {
            if (attempt?.nonce == nonce) attempt = null
        }
    }

    /** Returns true for every structurally exact self-probe so it cannot reach ordinary intake. */
    fun interceptPosted(
        notification: StatusBarNotification,
        ownPackageName: String,
    ): Boolean = interceptPostedCandidate(
        packageName = notification.packageName,
        notificationId = notification.id,
        channelId = notification.notification.channelId,
        observedNonce = runCatching {
            notification.notification.extras?.getString(EXTRA_OPERATION_NONCE)
        }.getOrNull(),
        ownPackageName = ownPackageName,
    )

    internal fun interceptPostedCandidate(
        packageName: String,
        notificationId: Int,
        channelId: String?,
        observedNonce: String?,
        ownPackageName: String,
    ): Boolean {
        if (!isProbeNotification(packageName, notificationId, channelId, ownPackageName)) {
            return false
        }
        val now = elapsedRealtimeMillis()
        synchronized(monitor) {
            val current = attempt ?: return@synchronized
            if (now >= current.expiresAtElapsedMillis) {
                attempt = null
            } else if (observedNonce == current.nonce) {
                attempt = current.copy(verified = true)
            }
        }
        return true
    }

    fun interceptRemoved(
        notification: StatusBarNotification,
        ownPackageName: String,
    ): Boolean = isProbeNotification(
        packageName = notification.packageName,
        notificationId = notification.id,
        channelId = notification.notification.channelId,
        ownPackageName = ownPackageName,
    )

    fun consumeVerified(nonce: String): Boolean {
        val now = elapsedRealtimeMillis()
        return synchronized(monitor) {
            val current = attempt ?: return@synchronized false
            if (now >= current.expiresAtElapsedMillis) {
                attempt = null
                return@synchronized false
            }
            if (current.nonce != nonce || !current.verified) return@synchronized false
            attempt = null
            true
        }
    }

    private fun isProbeNotification(
        packageName: String,
        notificationId: Int,
        channelId: String?,
        ownPackageName: String,
    ): Boolean =
        packageName == ownPackageName &&
            notificationId == NOTIFICATION_ID &&
            channelId == NOTIFICATION_CHANNEL_ID

    private data class Attempt(
        val nonce: String,
        val expiresAtElapsedMillis: Long,
        val verified: Boolean,
    )

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "hans_setup_verification_v2"
        const val NOTIFICATION_ID = 0x53455455
        const val EXTRA_OPERATION_NONCE =
            "ai.hans.standard.extra.SETUP_NOTIFICATION_LIVE_TEST_NONCE"

        private const val RECEIPT_TTL_MILLIS = 2 * 60 * 1_000L
        private val SAFE_NONCE = Regex("[A-Za-z0-9_-]{16,128}")

        val processWide: NotificationSetupLiveTestReceiptTracker by lazy {
            NotificationSetupLiveTestReceiptTracker(
                elapsedRealtimeMillis = SystemClock::elapsedRealtime,
                receiptTtlMillis = RECEIPT_TTL_MILLIS,
            )
        }

        internal fun forTest(
            elapsedRealtimeMillis: () -> Long,
            receiptTtlMillis: Long = RECEIPT_TTL_MILLIS,
        ): NotificationSetupLiveTestReceiptTracker {
            require(receiptTtlMillis > 0L)
            return NotificationSetupLiveTestReceiptTracker(
                elapsedRealtimeMillis = elapsedRealtimeMillis,
                receiptTtlMillis = receiptTtlMillis,
            )
        }

        private fun isBoundedNonce(value: String): Boolean = SAFE_NONCE.matches(value)

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}

/** Exact production routing seam used by JVM and real-Android cross-contract tests. */
internal fun routePostedNotificationAroundSetupProbe(
    notification: StatusBarNotification,
    ownPackageName: String,
    receiptTracker: NotificationSetupLiveTestReceiptTracker,
    ordinaryIntake: () -> Unit,
): Boolean {
    if (receiptTracker.interceptPosted(notification, ownPackageName)) return true
    ordinaryIntake()
    return false
}

internal fun routeRemovedNotificationAroundSetupProbe(
    notification: StatusBarNotification,
    ownPackageName: String,
    receiptTracker: NotificationSetupLiveTestReceiptTracker,
    ordinaryIntake: () -> Unit,
): Boolean {
    if (receiptTracker.interceptRemoved(notification, ownPackageName)) return true
    ordinaryIntake()
    return false
}
