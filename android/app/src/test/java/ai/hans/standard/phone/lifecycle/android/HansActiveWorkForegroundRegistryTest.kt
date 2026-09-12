package ai.hans.standard.phone.lifecycle.android

import ai.hans.standard.phone.lifecycle.HansActiveWorkForegroundEvent
import ai.hans.standard.phone.lifecycle.HansActiveWorkReason
import androidx.lifecycle.Lifecycle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HansActiveWorkForegroundRegistryTest {
    @Test
    fun dialogConsentNeedsResumedActivityButDoesNotRequireWindowFocus() {
        // A Compose/Android dialog keeps its host RESUMED while owning window focus itself.
        assertTrue(canAcquireRemoteControl(Lifecycle.State.RESUMED, isFinishing = false, isDestroyed = false))
        Lifecycle.State.values().filter { it != Lifecycle.State.RESUMED }.forEach { state ->
            assertFalse(canAcquireRemoteControl(state, isFinishing = false, isDestroyed = false))
        }
        assertFalse(canAcquireRemoteControl(null, isFinishing = false, isDestroyed = false))
        assertFalse(canAcquireRemoteControl(Lifecycle.State.RESUMED, isFinishing = true, isDestroyed = false))
        assertFalse(canAcquireRemoteControl(Lifecycle.State.RESUMED, isFinishing = false, isDestroyed = true))
    }

    @Test
    fun provisionalPromotionIsNotProtectionProofUntilEffectiveReasonsArePublished() {
        val owner = Any()
        HansActiveWorkForegroundRegistry.markForeground(owner)
        HansActiveWorkForegroundRegistry.clearForeground(owner)
        val before = HansActiveWorkForegroundRegistry.snapshot()
        HansActiveWorkForegroundRegistry.markForeground(owner)
        try {
            assertEquals(before, HansActiveWorkForegroundRegistry.snapshot())
            assertTrue(HansActiveWorkForegroundRegistry.snapshot().protectedReasons.isEmpty())
            HansActiveWorkForegroundRegistry.publishProtection(owner, setOf(HansActiveWorkReason.REMOTE_CONTROL), 12L)
            val confirmed = HansActiveWorkForegroundRegistry.snapshot()
            assertEquals(HansActiveWorkForegroundEvent.PROTECTED, confirmed.event)
            assertEquals(12L, confirmed.revision)
            assertEquals(setOf(HansActiveWorkReason.REMOTE_CONTROL), confirmed.protectedReasons)
            assertTrue(confirmed.sequence > before.sequence)
        } finally {
            HansActiveWorkForegroundRegistry.clearForeground(owner)
        }
    }

    @Test
    fun promotionFailureAndTimeoutWithdrawProtectionWithoutClaimingRemoteRpcRevocation() {
        listOf(HansActiveWorkForegroundEvent.PROMOTION_REJECTED, HansActiveWorkForegroundEvent.TIMED_OUT).forEach { event ->
            val owner = Any()
            HansActiveWorkForegroundRegistry.markForeground(owner)
            HansActiveWorkForegroundRegistry.publishProtection(owner, setOf(HansActiveWorkReason.REMOTE_CONTROL), 3L)
            val before = HansActiveWorkForegroundRegistry.snapshot()
            HansActiveWorkForegroundRegistry.failProtection(owner, 3L, event)
            val failed = HansActiveWorkForegroundRegistry.snapshot()
            assertEquals(event, failed.event)
            assertEquals(3L, failed.revision)
            assertTrue(failed.protectedReasons.isEmpty())
            assertFalse(HansActiveWorkForegroundRegistry.isForeground())
            assertEquals(before.remoteStopRequestSequence, failed.remoteStopRequestSequence)
        }
    }

    @Test
    fun obsoleteOwnerCannotPublishOrRejectCurrentProtection() {
        val oldOwner = Any()
        val owner = Any()
        HansActiveWorkForegroundRegistry.markForeground(oldOwner)
        HansActiveWorkForegroundRegistry.markForeground(owner)
        try {
            HansActiveWorkForegroundRegistry.publishProtection(owner, setOf(HansActiveWorkReason.REMOTE_CONTROL), 8L)
            val before = HansActiveWorkForegroundRegistry.snapshot()
            HansActiveWorkForegroundRegistry.publishProtection(oldOwner, setOf(HansActiveWorkReason.CODEX_ACTIVE), 2L)
            HansActiveWorkForegroundRegistry.failProtection(oldOwner, 2L, HansActiveWorkForegroundEvent.PROMOTION_REJECTED)
            HansActiveWorkForegroundRegistry.clearForeground(oldOwner)
            assertEquals(before, HansActiveWorkForegroundRegistry.snapshot())
        } finally {
            HansActiveWorkForegroundRegistry.clearForeground(owner)
        }
    }

    @Test
    fun notificationStopRequestsRevocationWithoutDroppingForegroundOwnership() {
        val owner = Any()
        HansActiveWorkForegroundRegistry.markForeground(owner)
        try {
            HansActiveWorkForegroundRegistry.publishProtection(owner, setOf(HansActiveWorkReason.CODEX_ACTIVE), 1L)
            val codexOnly = HansActiveWorkForegroundRegistry.snapshot()
            HansActiveWorkForegroundRegistry.requestRemoteStop()
            assertEquals(codexOnly, HansActiveWorkForegroundRegistry.snapshot())

            HansActiveWorkForegroundRegistry.publishProtection(owner, setOf(HansActiveWorkReason.REMOTE_CONTROL), 2L)
            val remote = HansActiveWorkForegroundRegistry.snapshot()
            HansActiveWorkForegroundRegistry.requestRemoteStop()
            val requested = HansActiveWorkForegroundRegistry.snapshot()
            assertEquals(remote.remoteStopRequestSequence + 1L, requested.remoteStopRequestSequence)
            assertEquals(remote.protectedReasons, requested.protectedReasons)
            assertEquals(HansActiveWorkForegroundEvent.PROTECTED, requested.event)
            assertTrue(HansActiveWorkForegroundRegistry.isForeground())
        } finally {
            HansActiveWorkForegroundRegistry.clearForeground(owner)
        }
    }

    @Test
    fun registryObserverReplaysThenDeliversOnlyChangesUntilRemoved() {
        val owner = Any()
        val observed = mutableListOf<Long>()
        val subscription = HansActiveWorkForegroundRegistry.observe {
            observed += HansActiveWorkForegroundRegistry.snapshot().sequence
        }
        assertEquals(1, observed.size)
        HansActiveWorkForegroundRegistry.markForeground(owner)
        try {
            assertEquals(1, observed.size)
            HansActiveWorkForegroundRegistry.publishProtection(owner, setOf(HansActiveWorkReason.REMOTE_CONTROL), 4L)
            assertEquals(2, observed.size)
            subscription.close()
            HansActiveWorkForegroundRegistry.requestRemoteStop()
            assertEquals(2, observed.size)
        } finally {
            subscription.close()
            HansActiveWorkForegroundRegistry.clearForeground(owner)
        }
    }

    @Test
    fun startThenStopBeforeFirstServiceCommandDefersExternalStop() {
        assertFalse(shouldStopActiveWorkService(foregroundPublished = false))
    }

    @Test
    fun normallyRunningForegroundServiceIsStillStoppedImmediately() {
        assertTrue(shouldStopActiveWorkService(foregroundPublished = true))
    }

    @Test
    fun staleServiceOwnerCannotClearANewerForegroundInstance() {
        val oldOwner = Any()
        val currentOwner = Any()
        HansActiveWorkForegroundRegistry.markForeground(oldOwner)
        HansActiveWorkForegroundRegistry.markForeground(currentOwner)

        HansActiveWorkForegroundRegistry.clearForeground(oldOwner)

        assertTrue(HansActiveWorkForegroundRegistry.isForeground())
        HansActiveWorkForegroundRegistry.clearForeground(currentOwner)
        assertFalse(HansActiveWorkForegroundRegistry.isForeground())
    }

    @Test
    fun servicePromotesBeforeInvalidOrStaleReconciliationThroughSurvivalBoundary() {
        val source = listOf(
            File(
                "src/main/java/ai/hans/standard/phone/lifecycle/android/" +
                    "HansActiveWorkService.kt",
            ),
            File(
                "android/app/src/main/java/ai/hans/standard/phone/lifecycle/android/" +
                    "HansActiveWorkService.kt",
            ),
        ).first(File::isFile).readText()
        val start = source.substringAfter("override fun onStartCommand(")
            .substringBefore("override fun onTimeout")

        assertTrue(start.indexOf("publishForegroundOrStop(provisionalReasons, startId)") in
            0 until start.indexOf("HansActiveWorkOwner.versionedSnapshot(this)"))
        assertTrue(start.contains("publishForegroundOrStop(provisionalReasons, startId)"))
    }

    @Test
    fun activeWorkCallerRejectionIsTransactionalAndErrorsStillEscape() {
        assertFalse(
            dispatchActiveWorkCommand {
                throw SecurityException("background start rejected")
            },
        )
        try {
            dispatchActiveWorkCommand { throw AssertionError("fatal") }
            fail("Error must escape active-work caller boundary")
        } catch (_: AssertionError) {
            // Expected.
        }
    }

    @Test
    fun quotaAndServiceTypePromotionRejectionsFailClosed() {
        var rejected = 0
        listOf(
            IllegalStateException("dataSync quota exhausted"),
            SecurityException("foreground service type rejected"),
        ).forEach { rejection ->
            assertFalse(
                runActiveWorkPromotion(
                    promote = { throw rejection },
                    onRejected = { rejected += 1 },
                ),
            )
        }
        assertEquals(2, rejected)

        try {
            runActiveWorkPromotion(
                promote = { throw AssertionError("fatal") },
                onRejected = {},
            )
            fail("Error must escape active-work promotion boundary")
        } catch (_: AssertionError) {
            // Expected.
        }
    }
}
