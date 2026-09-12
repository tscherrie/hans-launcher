package ai.hans.standard.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodicAccountRefreshControllerTest {
    @Test
    fun activeLifecycleRunsImmediatelyThenExactlyOncePerInterval() {
        val scheduler = FakeScheduler()
        var refreshes = 0
        val controller = PeriodicAccountRefreshController(
            scheduler = scheduler,
            action = AccountRefreshAction { refreshes += 1; true },
            refreshIntervalMillis = 60_000,
        )

        controller.start()
        controller.start()
        assertEquals(listOf(0L), scheduler.pendingDelays())

        scheduler.runNext()
        assertEquals(1, refreshes)
        assertEquals(listOf(60_000L), scheduler.pendingDelays())

        scheduler.runNext()
        assertEquals(2, refreshes)
        assertEquals(listOf(60_000L), scheduler.pendingDelays())
    }

    @Test
    fun failedOrThrowingRefreshStillUsesBoundedIntervalInsteadOfBusyRetry() {
        val scheduler = FakeScheduler()
        var refreshes = 0
        val controller = PeriodicAccountRefreshController(
            scheduler,
            AccountRefreshAction {
                refreshes += 1
                if (refreshes == 1) error("offline")
                false
            },
            refreshIntervalMillis = 60_000,
        )

        controller.start()
        scheduler.runNext()
        assertEquals(listOf(60_000L), scheduler.pendingDelays())
        scheduler.runNext()
        assertEquals(2, refreshes)
        assertEquals(listOf(60_000L), scheduler.pendingDelays())
    }

    @Test
    fun stopCancelsPendingAndLateCallbackCannotRearm() {
        val scheduler = FakeScheduler()
        var refreshes = 0
        val controller = PeriodicAccountRefreshController(
            scheduler,
            AccountRefreshAction { refreshes += 1; true },
            refreshIntervalMillis = 60_000,
        )

        controller.start()
        val late = scheduler.peekTask()
        controller.stop()
        assertTrue(late.cancelled)
        late.runEvenIfCancelled()

        assertEquals(0, refreshes)
        assertTrue(scheduler.pendingDelays().isEmpty())

        controller.start()
        scheduler.runNext()
        assertEquals(1, refreshes)
    }

    @Test
    fun intervalIsBounded() {
        val scheduler = FakeScheduler()
        assertThrows(IllegalArgumentException::class.java) {
            PeriodicAccountRefreshController(scheduler, AccountRefreshAction { true }, 59_999)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeriodicAccountRefreshController(
                scheduler,
                AccountRefreshAction { true },
                24 * 60 * 60 * 1_000L + 1,
            )
        }
    }

    private class FakeScheduler : AccountRefreshScheduler {
        private val tasks = mutableListOf<Task>()

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledAccountRefresh =
            Task(delayMillis, task).also(tasks::add)

        fun pendingDelays(): List<Long> = tasks.filterNot(Task::cancelled).map(Task::delayMillis)

        fun peekTask(): Task = tasks.first { !it.cancelled }

        fun runNext() {
            val task = tasks.first { !it.cancelled }
            tasks.remove(task)
            task.runEvenIfCancelled()
        }

        class Task(
            val delayMillis: Long,
            private val task: () -> Unit,
        ) : ScheduledAccountRefresh {
            var cancelled = false
                private set

            override fun close() {
                cancelled = true
            }

            fun runEvenIfCancelled() = task()
        }
    }
}
