package ai.hans.standard.setup

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SetupUiCommandRouterTest {
    @Test
    fun absentAndDetachedActivityFailClosed() {
        val router = SetupUiCommandRouter()
        var result: SetupUiCommandResult? = null
        val command = command()

        router.request(command) { result = it }
        assertEquals(SetupUiCommandResult.UserInteractionRequired, result)

        val registration = router.attach(SetupUiCommandHandler { _, _, completion ->
            completion(SetupUiCommandResult.Accepted(settingsOpened = true))
        })
        router.request(command) { result = it }
        assertEquals(SetupUiCommandResult.Accepted(settingsOpened = true), result)

        registration.close()
        router.request(command) { result = it }
        assertEquals(SetupUiCommandResult.UserInteractionRequired, result)
    }

    @Test
    fun settingsCommandCannotBeAcceptedWhenNoAndroidRouteOpened() {
        val router = SetupUiCommandRouter()
        var result: SetupUiCommandResult? = null
        router.attach(SetupUiCommandHandler { _, _, completion ->
            completion(SetupUiCommandResult.Accepted(settingsOpened = false))
        })

        router.request(command()) { result = it }

        assertEquals(
            SetupUiCommandResult.Rejected("settings_ui_not_opened"),
            result,
        )
    }

    @Test
    fun alreadyEffectiveCapabilityNeedsNoRedundantAndroidSheet() {
        val router = SetupUiCommandRouter()
        var result: SetupUiCommandResult? = null
        router.attach(SetupUiCommandHandler { _, _, completion ->
            completion(
                SetupUiCommandResult.Accepted(
                    settingsOpened = false,
                    liveVerificationAccepted = true,
                ),
            )
        })

        router.request(command()) { result = it }

        assertEquals(
            SetupUiCommandResult.Accepted(
                settingsOpened = false,
                liveVerificationAccepted = true,
            ),
            result,
        )
    }

    @Test
    fun optionalCapabilityIsPreservedAcrossTheActivityRouter() {
        val router = SetupUiCommandRouter()
        var observed: SetupUiCommand.OpenSettings? = null
        val command = SetupUiCommand.OpenSettings(
            token = command().token,
            optionalCapability = HansSetupOptionalCapability.LOCATION,
        )
        router.attach(SetupUiCommandHandler { routed, _, completion ->
            observed = routed as SetupUiCommand.OpenSettings
            completion(
                SetupUiCommandResult.Accepted(
                    settingsOpened = true,
                    liveVerificationAccepted = false,
                ),
            )
        })

        var result: SetupUiCommandResult? = null
        router.request(command) { result = it }

        assertEquals(HansSetupOptionalCapability.LOCATION, observed?.optionalCapability)
        assertEquals(
            SetupUiCommandResult.Accepted(
                settingsOpened = true,
                liveVerificationAccepted = false,
            ),
            result,
        )
    }

    @Test
    fun lostActivityCallbackCompletesAtTheBoundedDeadlineAndIgnoresLateReceipt() {
        val deadlines = FakeDeadlineScheduler()
        val router = SetupUiCommandRouter(deadlines, requestTimeoutMillis = 1_234)
        var lateCompletion: ((SetupUiCommandResult) -> Unit)? = null
        var cancellations = 0
        router.attach(object : SetupUiCommandHandler {
            override fun handle(
                command: SetupUiCommand,
                lease: SetupUiCommandLease,
                completion: (SetupUiCommandResult) -> Unit,
            ) {
                lateCompletion = completion
            }

            override fun cancel(command: SetupUiCommand) {
                cancellations += 1
            }
        })
        val results = mutableListOf<SetupUiCommandResult>()

        router.request(command(), results::add)

        assertEquals(listOf(1_234L), deadlines.pendingDelays())
        assertEquals(emptyList<SetupUiCommandResult>(), results)
        deadlines.runNext()
        assertEquals(
            listOf(SetupUiCommandResult.Rejected("setup_ui_request_timeout")),
            results,
        )
        assertEquals(1, cancellations)

        lateCompletion?.invoke(SetupUiCommandResult.Accepted(settingsOpened = true))
        assertEquals(1, results.size)
    }

    @Test
    fun detachingActivityOwnerCompletesPendingRequestAndCancelsItsDeadline() {
        val deadlines = FakeDeadlineScheduler()
        val router = SetupUiCommandRouter(deadlines, requestTimeoutMillis = 1_234)
        var lateCompletion: ((SetupUiCommandResult) -> Unit)? = null
        val registration = router.attach(
            SetupUiCommandHandler { _, _, completion -> lateCompletion = completion },
        )
        val results = mutableListOf<SetupUiCommandResult>()
        router.request(command(), results::add)

        registration.close()

        assertEquals(listOf(SetupUiCommandResult.UserInteractionRequired), results)
        assertEquals(emptyList<Long>(), deadlines.pendingDelays())
        lateCompletion?.invoke(SetupUiCommandResult.Accepted(settingsOpened = true))
        deadlines.runAll()
        assertEquals(1, results.size)
    }

    @Test
    fun replacementOwnerCancelsOldRequestsWithoutLettingOldCloseClearNewOwner() {
        val deadlines = FakeDeadlineScheduler()
        val router = SetupUiCommandRouter(deadlines, requestTimeoutMillis = 1_234)
        val old = router.attach(SetupUiCommandHandler { _, _, _ -> Unit })
        var oldResult: SetupUiCommandResult? = null
        router.request(command()) { oldResult = it }

        router.attach(SetupUiCommandHandler { _, _, completion ->
            completion(SetupUiCommandResult.Accepted(settingsOpened = true))
        })
        old.close()

        assertEquals(SetupUiCommandResult.UserInteractionRequired, oldResult)
        var newResult: SetupUiCommandResult? = null
        router.request(command()) { newResult = it }
        assertEquals(SetupUiCommandResult.Accepted(settingsOpened = true), newResult)
        assertFalse(deadlines.hasLiveTasks())
    }

    @Test
    fun detachedOwnerInvalidatesLeaseBeforeQueuedUiRuns() {
        val deadlines = FakeDeadlineScheduler()
        val router = SetupUiCommandRouter(deadlines, requestTimeoutMillis = 1_234)
        var queuedLease: SetupUiCommandLease? = null
        val registration = router.attach(SetupUiCommandHandler { _, lease, _ ->
            queuedLease = lease
        })
        val results = mutableListOf<SetupUiCommandResult>()
        router.request(command(), results::add)

        assertEquals(true, queuedLease?.isActive())
        registration.close()

        assertEquals(false, queuedLease?.isActive())
        assertEquals(listOf(SetupUiCommandResult.UserInteractionRequired), results)
    }

    @Test
    fun expiredLeaseCannotClaimLateVoiceSideEffects() {
        val deadlines = FakeDeadlineScheduler()
        val router = SetupUiCommandRouter(deadlines, requestTimeoutMillis = 1_234)
        var lateLease: SetupUiCommandLease? = null
        router.attach(SetupUiCommandHandler { _, lease, _ -> lateLease = lease })
        val results = mutableListOf<SetupUiCommandResult>()
        router.request(command(), results::add)
        deadlines.runNext()

        val claimed = checkNotNull(lateLease).tryComplete(
            SetupUiCommandResult.Accepted(liveVerificationAccepted = true),
        )

        assertFalse(claimed)
        assertEquals(
            listOf(SetupUiCommandResult.Rejected("setup_ui_request_timeout")),
            results,
        )
    }

    @Test
    fun blockedActivityHandlerCannotBlockTheIndependentDeadline() {
        val deadlines = FakeDeadlineScheduler()
        val router = SetupUiCommandRouter(deadlines, requestTimeoutMillis = 1_234)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        router.attach(SetupUiCommandHandler { _, _, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        val results = mutableListOf<SetupUiCommandResult>()
        val requestThread = Thread { router.request(command(), results::add) }
        requestThread.start()
        check(entered.await(5, TimeUnit.SECONDS))

        deadlines.runNext()

        assertEquals(
            listOf(SetupUiCommandResult.Rejected("setup_ui_request_timeout")),
            results,
        )
        release.countDown()
        requestThread.join(5_000)
        assertFalse(requestThread.isAlive)
    }

    @Test
    fun callbackThenHandlerThrowStillCompletesExactlyOnce() {
        val deadlines = FakeDeadlineScheduler()
        val router = SetupUiCommandRouter(deadlines, requestTimeoutMillis = 1_234)
        router.attach(SetupUiCommandHandler { _, _, completion ->
            completion(SetupUiCommandResult.Accepted(settingsOpened = true))
            error("throw after callback")
        })
        val results = mutableListOf<SetupUiCommandResult>()

        router.request(command(), results::add)

        assertEquals(
            listOf(SetupUiCommandResult.Accepted(settingsOpened = true)),
            results,
        )
        assertFalse(deadlines.hasLiveTasks())
    }

    @Test
    fun blockedTimeoutCompletionDoesNotDelayAnotherProcessDeadline() {
        val router = SetupUiCommandRouter(requestTimeoutMillis = 10)
        router.attach(SetupUiCommandHandler { _, _, _ -> Unit })
        val firstCompletionEntered = CountDownLatch(1)
        val releaseFirstCompletion = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        var secondResult: SetupUiCommandResult? = null

        router.request(command()) {
            firstCompletionEntered.countDown()
            check(releaseFirstCompletion.await(5, TimeUnit.SECONDS))
        }
        router.request(command()) {
            secondResult = it
            secondCompleted.countDown()
        }

        try {
            assertEquals(true, firstCompletionEntered.await(2, TimeUnit.SECONDS))
            assertEquals(true, secondCompleted.await(2, TimeUnit.SECONDS))
            assertEquals(
                SetupUiCommandResult.Rejected("setup_ui_request_timeout"),
                secondResult,
            )
        } finally {
            releaseFirstCompletion.countDown()
        }
    }

    private fun command() = SetupUiCommand.OpenSettings(
        HansSetupOperationToken(
            step = HansSetupStep.HOME_ROLE,
            generation = 1,
            nonce = "setup_nonce_123456789",
        ),
    )

    private class FakeDeadlineScheduler : SetupUiCommandDeadlineScheduler {
        private val tasks = mutableListOf<Task>()

        override fun schedule(
            delayMillis: Long,
            task: () -> Unit,
        ): SetupUiCommandDeadline = Task(delayMillis, task).also(tasks::add)

        fun pendingDelays(): List<Long> = tasks.filterNot(Task::cancelled).map(Task::delayMillis)

        fun hasLiveTasks(): Boolean = tasks.any { !it.cancelled }

        fun runNext() {
            val next = checkNotNull(tasks.firstOrNull { !it.cancelled }) {
                "No pending deadline"
            }
            next.task()
        }

        fun runAll() {
            tasks.filterNot(Task::cancelled).toList().forEach { it.task() }
        }

        private class Task(
            val delayMillis: Long,
            val task: () -> Unit,
        ) : SetupUiCommandDeadline {
            var cancelled = false
                private set

            override fun cancel() {
                cancelled = true
            }
        }
    }
}
