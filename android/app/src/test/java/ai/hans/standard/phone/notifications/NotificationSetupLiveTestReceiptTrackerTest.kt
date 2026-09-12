package ai.hans.standard.phone.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSetupLiveTestReceiptTrackerTest {
    private var now = 1_000L
    private val tracker = NotificationSetupLiveTestReceiptTracker.forTest(
        elapsedRealtimeMillis = { now },
        receiptTtlMillis = 500L,
    )
    private val ownPackage = "ai.hans.standard"
    private val nonce = "setup_nonce_123456789"

    @Test
    fun exactNonceIsOneShotVerifiedWithoutAnyDurableReceipt() {
        assertTrue(tracker.arm(nonce))

        assertTrue(intercept(nonce))
        assertTrue(tracker.consumeVerified(nonce))
        assertFalse(tracker.consumeVerified(nonce))
    }

    @Test
    fun wrongNonceIsConsumedBeforeOrdinaryIntakeButCannotVerify() {
        assertTrue(tracker.arm(nonce))

        assertTrue(intercept("setup_nonce_wrong_987654321"))
        assertFalse(tracker.consumeVerified(nonce))
        // The wrong observation does not destroy the correctly armed attempt.
        assertTrue(intercept(nonce))
        assertTrue(tracker.consumeVerified(nonce))
    }

    @Test
    fun expiredNonceIsConsumedBeforeOrdinaryIntakeButCannotVerify() {
        assertTrue(tracker.arm(nonce))
        now += 501L

        assertTrue(intercept(nonce))
        assertFalse(tracker.consumeVerified(nonce))
    }

    @Test
    fun onlyExactOwnPackageIdAndChannelUseThePrivateProbePath() {
        assertTrue(tracker.arm(nonce))

        assertFalse(intercept(nonce, packageName = "org.example.external"))
        assertFalse(
            intercept(
                nonce,
                notificationId = NotificationSetupLiveTestReceiptTracker.NOTIFICATION_ID + 1,
            ),
        )
        assertFalse(intercept(nonce, channelId = "ordinary-channel"))
        assertFalse(tracker.consumeVerified(nonce))
    }

    private fun intercept(
        observedNonce: String,
        packageName: String = ownPackage,
        notificationId: Int = NotificationSetupLiveTestReceiptTracker.NOTIFICATION_ID,
        channelId: String = NotificationSetupLiveTestReceiptTracker.NOTIFICATION_CHANNEL_ID,
    ): Boolean = tracker.interceptPostedCandidate(
        packageName = packageName,
        notificationId = notificationId,
        channelId = channelId,
        observedNonce = observedNonce,
        ownPackageName = ownPackage,
    )
}
