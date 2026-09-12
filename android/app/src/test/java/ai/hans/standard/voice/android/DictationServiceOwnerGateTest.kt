package ai.hans.standard.voice.android

import ai.hans.standard.voice.RecordingIdGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationServiceOwnerGateTest {
    @Test
    fun delayedOldCleanupCannotPublishOrClearANewOwnersRecording() {
        val gate = DictationServiceOwnerGate()
        val oldOwner = Any()
        val newOwner = Any()
        var active = false
        var publication = "idle"
        assertTrue(gate.acquire(oldOwner))
        gate.runIfOwner(oldOwner) { active = true }
        assertFalse("Replacement must not capture while old cleanup is pending", gate.acquire(newOwner))
        gate.release(oldOwner) { active = false }
        assertTrue(gate.acquire(newOwner))
        gate.runIfOwner(newOwner) { active = true; publication = "listening" }

        assertFalse(gate.runIfOwner(oldOwner) { active = false; publication = "failed" })
        assertFalse(gate.release(oldOwner) { active = false })

        assertTrue(active)
        assertEquals("listening", publication)
        gate.release(newOwner) { active = false }
        assertFalse(active)
    }

    @Test
    fun ownerIsNotHandedOverUntilItsFinalPublicationHasReturned() {
        val gate = DictationServiceOwnerGate()
        val owner = Any()
        val next = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanupDone = AtomicBoolean(false)
        assertTrue(gate.acquire(owner))
        val worker = Thread {
            gate.release(owner) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                cleanupDone.set(true)
            }
        }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(gate.acquire(next))
            assertTrue(cleanupDone.get())
        } finally {
            release.countDown()
            worker.join(3_000)
            assertFalse(worker.isAlive)
        }
    }

    @Test
    fun partialNewFactoryFailureCannotReleaseAnotherServicesAudioBarrier() {
        val gate = DictationServiceOwnerGate()
        val oldOwner = Any()
        assertTrue(gate.acquire(oldOwner))
        val rejectedOwner = Any()
        var releases = 0
        assertFalse(gate.acquire(rejectedOwner))
        assertFalse(gate.release(rejectedOwner) { releases++ })
        assertTrue(gate.runIfOwner(oldOwner) {})
        assertEquals(0, releases)
    }

    @Test
    fun sharedRecordingIdsDoNotAliasAcrossServiceLifetimes() {
        val processIds = RecordingIdGenerator()
        val firstServiceRecording = processIds.next()
        val replacementRecording = processIds.next()
        assertNotEquals(firstServiceRecording, replacementRecording)
    }
}
