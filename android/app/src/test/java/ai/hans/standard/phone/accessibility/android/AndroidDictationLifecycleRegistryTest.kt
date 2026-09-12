package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.DictationLifecycleStamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDictationLifecycleRegistryTest {
    private val registrations = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() {
        registrations.asReversed().forEach { runCatching { it.close() } }
        // The latest registration is the only one whose close can clear the registry.
        val sentinel = AndroidDictationLifecycleRegistry.register {
            DictationLifecycleStamp(0, false)
        }
        sentinel.close()
    }

    @Test
    fun actionFailsClosedWhenNoProbeIsRegistered() {
        val sentinel = AndroidDictationLifecycleRegistry.register {
            DictationLifecycleStamp(0, false)
        }
        sentinel.close()

        assertThrows(IllegalStateException::class.java) {
            AndroidDictationLifecycleRegistry.beforeAccessibilityAction()
        }
    }

    @Test
    fun unchangedActiveAndInactiveLifecyclesRemainIndependent() {
        listOf(false, true).forEach { active ->
            val probe = MutableProbe(DictationLifecycleStamp(9, active))
            registrations += AndroidDictationLifecycleRegistry.register(probe)

            val before = AndroidDictationLifecycleRegistry.beforeAccessibilityAction()

            assertTrue(AndroidDictationLifecycleRegistry.remainedIndependent(before))
            assertFalse(AndroidDictationLifecycleRegistry.remainedIndependent(before))
        }
    }

    @Test
    fun generationOrRecordingStateChangeIsInterference() {
        val probe = MutableProbe(DictationLifecycleStamp(5, true))
        registrations += AndroidDictationLifecycleRegistry.register(probe)
        val generationBefore = AndroidDictationLifecycleRegistry.beforeAccessibilityAction()
        probe.stamp = DictationLifecycleStamp(6, true)
        assertFalse(AndroidDictationLifecycleRegistry.remainedIndependent(generationBefore))

        val stateBefore = AndroidDictationLifecycleRegistry.beforeAccessibilityAction()
        probe.stamp = DictationLifecycleStamp(6, false)
        assertFalse(AndroidDictationLifecycleRegistry.remainedIndependent(stateBefore))
    }

    @Test
    fun replacingProbeDuringActionIsInterferenceEvenWithIdenticalStamp() {
        val stamp = DictationLifecycleStamp(12, true)
        registrations += AndroidDictationLifecycleRegistry.register(MutableProbe(stamp))
        val before = AndroidDictationLifecycleRegistry.beforeAccessibilityAction()
        registrations += AndroidDictationLifecycleRegistry.register(MutableProbe(stamp))

        assertFalse(AndroidDictationLifecycleRegistry.remainedIndependent(before))
    }

    @Test
    fun removingProbeDuringActionIsInterference() {
        val registration = AndroidDictationLifecycleRegistry.register(
            MutableProbe(DictationLifecycleStamp(2, true)),
        )
        registrations += registration
        val before = AndroidDictationLifecycleRegistry.beforeAccessibilityAction()
        registration.close()

        assertFalse(AndroidDictationLifecycleRegistry.remainedIndependent(before))
    }

    @Test
    fun probeFailuresFailClosedBothBeforeAndAfterAction() {
        val probe = MutableProbe(DictationLifecycleStamp(1, true))
        probe.throwOnSnapshot = true
        registrations += AndroidDictationLifecycleRegistry.register(probe)
        assertThrows(IllegalStateException::class.java) {
            AndroidDictationLifecycleRegistry.beforeAccessibilityAction()
        }

        probe.throwOnSnapshot = false
        val before = AndroidDictationLifecycleRegistry.beforeAccessibilityAction()
        probe.throwOnSnapshot = true
        assertFalse(AndroidDictationLifecycleRegistry.remainedIndependent(before))
    }

    @Test
    fun lifecycleObservationCannotBeConsumedFromAnotherThread() {
        val stamp = DictationLifecycleStamp(4, true)
        registrations += AndroidDictationLifecycleRegistry.register(MutableProbe(stamp))
        val before = AndroidDictationLifecycleRegistry.beforeAccessibilityAction()
        val result = AtomicBoolean(true)
        val done = CountDownLatch(1)

        Thread {
            result.set(AndroidDictationLifecycleRegistry.remainedIndependent(before))
            done.countDown()
        }.start()

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertFalse(result.get())
        // Clear the originating thread's observation and prove it was independent there.
        assertTrue(AndroidDictationLifecycleRegistry.remainedIndependent(before))
    }

    private class MutableProbe(
        @Volatile var stamp: DictationLifecycleStamp,
    ) : ReadOnlyDictationLifecycleProbe {
        @Volatile
        var throwOnSnapshot: Boolean = false

        override fun snapshot(): DictationLifecycleStamp {
            if (throwOnSnapshot) error("synthetic probe failure")
            return stamp
        }
    }
}
