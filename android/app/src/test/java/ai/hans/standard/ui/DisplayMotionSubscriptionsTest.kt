package ai.hans.standard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayMotionSubscriptionsTest {
    @Test
    fun successfulSubscriptionsCloseInReverseOrderExactlyOnce() {
        val events = mutableListOf<String>()
        val subscription = registerDisplayMotionObservers(
            { events += "open1"; AutoCloseable { events += "close1" } },
            { events += "open2"; AutoCloseable { events += "close2" } },
        )
        subscription.close()
        subscription.close()
        assertEquals(listOf("open1", "open2", "close2", "close1"), events)
    }

    @Test
    fun laterRegistrationFailureReleasesEveryAlreadyAcquiredObserver() {
        val events = mutableListOf<String>()
        val result = runCatching {
            registerDisplayMotionObservers(
                { events += "open1"; AutoCloseable { events += "close1" } },
                { events += "open2"; AutoCloseable { events += "close2" } },
                { error("Android rejected the last registration") },
            )
        }
        assertTrue(result.isFailure)
        assertEquals(listOf("open1", "open2", "close2", "close1"), events)
    }

    @Test
    fun oneFailingCleanupCannotLeakTheRemainingObservers() {
        val events = mutableListOf<String>()
        val subscription = registerDisplayMotionObservers(
            { AutoCloseable { events += "close1" } },
            { AutoCloseable { error("Already unregistered by Android") } },
            { AutoCloseable { events += "close3" } },
        )
        subscription.close()
        assertEquals(listOf("close3", "close1"), events)
    }
}
