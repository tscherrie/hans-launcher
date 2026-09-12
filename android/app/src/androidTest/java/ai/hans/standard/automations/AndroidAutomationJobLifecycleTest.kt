package ai.hans.standard.automations

import ai.hans.standard.BuildConfig
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real JobScheduler -> HansAutomationJobService -> RuntimeOwner -> SQLite composition.
 *
 * Opt in only on a host-verified disposable emulator with:
 *   hansAutomationLifecycle=true, hansAutomationRunId=<canonical UUID>
 * The in-test gates are a second safety boundary, not permission to launch instrumentation on a
 * configured phone. No JobParameters are manufactured and no lifecycle callback is called directly.
 * All effects are rows in a separate UUID-owned SQLite database; there is no Codex or network client.
 * The process-local work ledger does not establish process-death/reboot recovery coverage.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAutomationJobLifecycleTest {
    private lateinit var context: Context
    private lateinit var hostRunId: UUID
    private var fixture: JobLifecycleFixture? = null

    @Before
    fun requireOwnedDebugEmulator() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Requires explicit automation lifecycle opt-in on a disposable emulator",
            arguments.getString("hansAutomationLifecycle") == "true",
        )
        val rawRunId = arguments.getString("hansAutomationRunId")
        require(!rawRunId.isNullOrBlank()) { "hansAutomationRunId must be a canonical UUID" }
        hostRunId = UUID.fromString(rawRunId)
        require(hostRunId.toString() == rawRunId.lowercase(Locale.ROOT)) {
            "hansAutomationRunId must be a canonical UUID"
        }
        assumeTrue("Requires Android 12 or newer", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        assumeTrue("Requires a debug application", BuildConfig.DEBUG)
        val hardware = Build.HARDWARE.lowercase(Locale.ROOT)
        val product = Build.PRODUCT.lowercase(Locale.ROOT)
        val model = Build.MODEL.lowercase(Locale.ROOT)
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.ROOT)
        assumeTrue(
            "Refusing lifecycle test on a device without public Android-emulator identity",
            hardware in setOf("ranchu", "goldfish") &&
                (product.contains("sdk") || model.contains("sdk") || fingerprint.contains("sdk")),
        )
        context = ApplicationProvider.getApplicationContext()
        assumeTrue(
            "Requires a debuggable target package",
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
    }

    @After
    fun releaseOnlyOwnedFixture() {
        fixture?.close()
        fixture = null
    }

    /** Baseline regression: real Android stop must wake the blocked executor without test help. */
    @Test
    fun systemStopBeforeDispatchFenceCancelsWithoutEffectAndCanSafelyRetry() {
        val test = createFixture()
        val first = test.addGate(DispatchGatePosition.BEFORE_FENCE)
        test.requestWakeup()
        first.awaitEntered()
        assertEquals(AutomationRunState.LEASED, test.persistedRun().state)
        assertNull(test.persistedRun().dispatchFence)
        assertEquals(0L, test.persistedEffectCount())

        test.cancelOwnedPlatformJob()
        first.awaitSystemCancellation()
        // The cancellation listener itself releases the executor. Do not release it here.
        test.awaitSettled()

        val stopped = test.persistedRun()
        assertEquals(AutomationRunState.RETRY_WAIT, stopped.state)
        assertEquals("job_execution_stopped", stopped.lastFailureCode)
        assertNull(stopped.dispatchFence)
        assertEquals(0L, test.persistedEffectCount())
        assertEquals(0L, test.ledger.acknowledgedGeneration())
        assertEquals(1L, test.ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)

        // cancel() does NOT cause an Android retry. This is a separate, explicit safe wakeup.
        test.clock.set(stopped.availableAt)
        val resumed = test.addGate(DispatchGatePosition.BEFORE_FENCE)
        test.requestWakeup()
        resumed.awaitEntered()
        assertFalse("A prior stop must not cancel the resumed run", resumed.wasCancelled())
        resumed.releaseForSuccess()
        test.awaitAcknowledgedGeneration(2)
        test.awaitSettled()

        assertEquals(AutomationRunState.SUCCEEDED, test.persistedRun().state)
        assertEquals(2, test.executorEntries.get())
        assertEquals(1L, test.persistedEffectCount())
        assertEquals(2, test.platformScheduleCalls.get())
        assertNull(test.ledger.pending(AutomationRuntimeWorkKind.TIMER))
    }

    @Test
    fun systemStopAfterDispatchFenceLeavesUnknownOutcomeAndNeverReplaysEffect() {
        val test = createFixture()
        val dispatched = test.addGate(DispatchGatePosition.AFTER_FENCE)
        test.requestWakeup()
        dispatched.awaitEntered()

        val inFlight = test.persistedRun()
        assertEquals(AutomationRunState.LEASED, inFlight.state)
        assertNotNull("The fence must be durable before the synthetic effect", inFlight.dispatchFence)
        assertEquals(1L, test.persistedEffectCount())

        test.cancelOwnedPlatformJob()
        dispatched.awaitSystemCancellation()
        test.awaitSettled()
        assertEquals(AutomationRunState.FAILED_TERMINAL, test.persistedRun().state)
        assertEquals("automation_outcome_ambiguous", test.persistedRun().lastFailureCode)
        assertEquals(1L, test.persistedEffectCount())
        assertEquals(0L, test.ledger.acknowledgedGeneration())

        test.clock.advance(Duration.ofMinutes(2))
        test.requestWakeup()
        test.awaitAcknowledgedGeneration(2)
        test.awaitSettled()

        assertEquals("Unknown effects must not invoke the executor again", 1, test.executorEntries.get())
        assertEquals(1L, test.persistedEffectCount())
        assertEquals(AutomationRunState.FAILED_TERMINAL, test.persistedRun().state)
    }

    @Test
    fun coalescedWakeupsExecuteOneEffectAndAcknowledgeNewestWorkGeneration() {
        val test = createFixture()
        val first = test.addGate(DispatchGatePosition.BEFORE_FENCE)
        test.requestWakeup()
        first.awaitEntered()

        test.requestWakeup()
        test.requestWakeup()
        assertEquals("Active work must not schedule replacement platform jobs", 1, test.platformScheduleCalls.get())
        assertEquals(3L, test.ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
        assertFalse(first.wasCancelled())

        first.releaseForSuccess()
        test.awaitAcknowledgedGeneration(3)
        test.awaitSettled()

        assertEquals(1, test.executorEntries.get())
        assertEquals(1L, test.persistedEffectCount())
        assertEquals(AutomationRunState.SUCCEEDED, test.persistedRun().state)
        assertTrue("The newer generation must get a recheck cycle", test.background.completedCount.get() >= 2)
        assertNull(test.ledger.pending(AutomationRuntimeWorkKind.TIMER))
        assertEquals(1, test.platformScheduleCalls.get())
    }

    @Test
    fun forcedDeliveryOfScheduledPlatformRetryDoesNotRepeatEffect() {
        val test = createFixture(wakeupFailures = 1, tokenizedScheduling = true)
        val first = test.addGate(DispatchGatePosition.BEFORE_FENCE)
        test.requestWakeup()
        first.awaitEntered()
        val firstNonce = requireNotNull(test.pendingScheduleNonce())
        first.releaseForSuccess()

        // The real service installs the production retry in place of finishing the old scheduling.
        val retry = test.awaitScheduledRetry()
        assertEquals(30_000L, retry.minLatencyMillis)
        assertEquals(35_000L, retry.maxExecutionDelayMillis)
        assertTrue("Pending retry must survive reboot", retry.isPersisted)
        assertEquals(1, AndroidAutomationDispatch.retryAttemptFrom(retry.extras))
        val retryNonce = requireNotNull(retry.extras.getString(AndroidAutomationDispatch.KEY_SCHEDULE_NONCE))
        assertEquals(retryNonce, UUID.fromString(retryNonce).toString())
        assertFalse("Replacement must not reuse the old scheduling identity", firstNonce == retryNonce)
        assertEquals(0L, test.ledger.acknowledgedGeneration())
        assertEquals(1L, test.ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
        assertEquals(1L, test.persistedEffectCount())

        // setOverrideDeadline has not guaranteed wall-clock delivery since Android 6 and may be
        // deferred further for scheduler optimization or system-health reasons. First prove the
        // old service fully handed off the exact replacement, then ask the disposable emulator to
        // deliver that already-persisted job through the real JobScheduler -> JobService path.
        test.awaitSettled()
        test.forceOwnedScheduledRetry(retry)
        test.awaitAcknowledgedGeneration(1)
        test.awaitSettled()

        assertEquals(2, test.platformScheduleCalls.get())
        assertTrue("Expected a genuine second RuntimeOwner cycle", test.background.completedCount.get() >= 2)
        assertEquals(2, test.wakeupCalls.get())
        assertEquals(1, test.executorEntries.get())
        assertEquals(1L, test.persistedEffectCount())
        assertEquals(AutomationRunState.SUCCEEDED, test.persistedRun().state)
        assertNull(test.ledger.pending(AutomationRuntimeWorkKind.TIMER))
    }

    @Test
    fun newWakeupSupersedesBoundedRetryWithoutLosingGenerationOrRepeatingEffect() {
        assertAwakeForImmediateDispatch()
        val test = createFixture(wakeupFailures = 1, tokenizedScheduling = true)
        val first = test.addGate(DispatchGatePosition.BEFORE_FENCE)
        test.requestWakeup()
        first.awaitEntered()
        first.releaseForSuccess()

        val retry = test.awaitScheduledRetry()
        assertEquals(1L, test.ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
        assertEquals(0L, test.ledger.acknowledgedGeneration())
        assertEquals(2, test.platformScheduleCalls.get())
        // This request races with the old service's finish, not with the underlying effect.
        // It must wait for that handoff, then replace the backoff with a fresh immediate job.
        test.requestWakeup()
        test.awaitAcknowledgedGeneration(2)
        test.awaitSettled()

        assertEquals(3, test.platformScheduleCalls.get())
        val freshNonce = requireNotNull(test.lastExplicitScheduleNonce())
        assertFalse(freshNonce == retry.extras.getString(AndroidAutomationDispatch.KEY_SCHEDULE_NONCE))
        assertEquals(2, test.wakeupCalls.get())
        assertEquals(1, test.executorEntries.get())
        assertEquals(1L, test.persistedEffectCount())
        assertEquals(AutomationRunState.SUCCEEDED, test.persistedRun().state)
        assertNull(test.ledger.pending(AutomationRuntimeWorkKind.TIMER))
    }

    @Test
    fun systemStopAfterAcknowledgedEffectPreservesKnownSuccess() {
        val test = createFixture()
        val acknowledged = test.addGate(
            DispatchGatePosition.AFTER_FENCE,
            effectAlreadyAcknowledged = true,
        )
        test.requestWakeup()
        acknowledged.awaitEntered()
        assertEquals(1L, test.persistedEffectCount())

        test.cancelOwnedPlatformJob()
        acknowledged.awaitSystemCancellation()
        test.awaitSettled()
        assertEquals(AutomationRunState.SUCCEEDED, test.persistedRun().state)
        assertNull(test.persistedRun().lastFailureCode)

        test.requestWakeup()
        test.awaitAcknowledgedGeneration(2)
        test.awaitSettled()
        assertEquals(1, test.executorEntries.get())
        assertEquals(1L, test.persistedEffectCount())
        assertEquals(AutomationRunState.SUCCEEDED, test.persistedRun().state)
    }

    @Test
    fun tokenizedStopAndExplicitRetryUseStableDistinctSchedulingNonces() {
        val test = createFixture(tokenizedScheduling = true)
        val first = test.addGate(DispatchGatePosition.BEFORE_FENCE)
        test.requestWakeup()
        first.awaitEntered()
        val firstNonce = requireNotNull(test.pendingScheduleNonce())
        assertEquals("Scheduling identity must be a UUID", firstNonce, UUID.fromString(firstNonce).toString())

        // A coalesced work generation belongs to the same platform scheduling, so its nonce must
        // remain unchanged. Only an actual schedule() call may mint a replacement nonce.
        test.requestWakeup()
        assertEquals(1, test.platformScheduleCalls.get())
        assertEquals(firstNonce, test.pendingScheduleNonce())
        assertEquals(2L, test.ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
        assertEquals(0L, test.persistedEffectCount())
        assertNull(test.persistedRun().dispatchFence)

        test.cancelOwnedPlatformJob()
        first.awaitSystemCancellation()
        test.awaitSettled()
        val stopped = test.persistedRun()
        assertEquals(AutomationRunState.RETRY_WAIT, stopped.state)
        assertEquals("job_execution_stopped", stopped.lastFailureCode)
        assertNull(stopped.dispatchFence)
        assertEquals(0L, test.persistedEffectCount())
        assertEquals(0L, test.ledger.acknowledgedGeneration())

        test.clock.set(stopped.availableAt)
        val retry = test.addGate(DispatchGatePosition.BEFORE_FENCE)
        test.requestWakeup()
        retry.awaitEntered()
        val retryNonce = requireNotNull(test.pendingScheduleNonce())
        assertEquals("Retry scheduling identity must be a UUID", retryNonce, UUID.fromString(retryNonce).toString())
        assertFalse("An explicit new scheduling must not reuse the stopped nonce", firstNonce == retryNonce)
        assertEquals(2, test.platformScheduleCalls.get())
        assertEquals(retryNonce, test.pendingScheduleNonce())
        assertFalse("The stopped scheduling must not cancel its successor", retry.wasCancelled())
        assertEquals(0L, test.persistedEffectCount())

        retry.releaseForSuccess()
        test.awaitAcknowledgedGeneration(3)
        test.awaitSettled()
        assertEquals(AutomationRunState.SUCCEEDED, test.persistedRun().state)
        assertEquals(2, test.executorEntries.get())
        assertEquals(1L, test.persistedEffectCount())
        assertNull(test.ledger.pending(AutomationRuntimeWorkKind.TIMER))
    }

    private fun createFixture(
        wakeupFailures: Int = 0,
        tokenizedScheduling: Boolean = false,
    ): JobLifecycleFixture {
        check(fixture == null) { "Only one isolated binding is allowed per test" }
        val candidate = JobLifecycleFixture(context, hostRunId, wakeupFailures, tokenizedScheduling)
        fixture = candidate
        candidate.initialize()
        return candidate
    }

    private fun assertAwakeForImmediateDispatch() {
        val power = context.getSystemService(PowerManager::class.java)
        assertTrue("This immediate dispatch acceptance requires an awake emulator", power.isInteractive)
        assertFalse("This immediate dispatch acceptance does not claim a Doze guarantee", power.isDeviceIdleMode)
        assertFalse("This immediate dispatch acceptance requires battery saver to be off", power.isPowerSaveMode)
    }
}

private enum class DispatchGatePosition { BEFORE_FENCE, AFTER_FENCE }

/** The only normal unblock paths are explicit success or an actual RuntimeOwner cancellation. */
private class DispatchGate(
    val position: DispatchGatePosition,
    val effectAlreadyAcknowledged: Boolean,
) {
    val entered = CountDownLatch(1)
    private val proceed = CountDownLatch(1)
    private val systemCancelled = CountDownLatch(1)

    fun onSystemCancellation() {
        systemCancelled.countDown()
        proceed.countDown()
    }

    fun awaitEntered() {
        assertTrue("Real JobScheduler did not reach the isolated executor", entered.await(20, TimeUnit.SECONDS))
    }

    fun awaitSystemCancellation() {
        assertTrue(
            "Actual JobScheduler.cancel did not reach heartbeat.onCancellation; no test-side release was made",
            systemCancelled.await(10, TimeUnit.SECONDS),
        )
    }

    fun wasCancelled(): Boolean = systemCancelled.count == 0L

    fun releaseForSuccess() = proceed.countDown()

    fun releaseForCleanup() = proceed.countDown()

    fun awaitProceed(): Boolean = proceed.await(45, TimeUnit.SECONDS)
}

private class JobLifecycleFixture(
    private val context: Context,
    hostRunId: UUID,
    private val wakeupFailures: Int,
    private val tokenizedScheduling: Boolean,
) : AutoCloseable {
    private val caseId = UUID.randomUUID().toString()
    private val filePrefix = "automation-job-${hostRunId.toString().take(8)}-$caseId"
    private val automationFileName = "$filePrefix.db"
    private val effectsFileName = "$filePrefix-effects.db"
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val originalGlobalOwner = HansAutomationRuntime.currentOrNull()
    private val aborted = AtomicBoolean(false)
    private val gates = LinkedBlockingQueue<DispatchGate>()
    private val allGates = CopyOnWriteArrayList<DispatchGate>()
    private val explicitSchedules = CopyOnWriteArrayList<JobInfo>()
    private val installedRetries = LinkedBlockingQueue<JobInfo>()
    private val executorFailure = AtomicReference<Throwable?>(null)
    private val activityEvents = LinkedBlockingQueue<Unit>()
    private val activeCycles = AtomicInteger(0)
    private var binding: AutoCloseable? = null
    private var activitySubscription: AutoCloseable? = null
    private var persistence: SQLiteAutomationSnapshotPersistence? = null
    private var effects: SyntheticEffectDatabase? = null
    private var initialized = false
    private var closed = false
    private var jobWasScheduled = false
    private lateinit var storage: PersistentAutomationStorage
    private lateinit var owner: AutomationRuntimeOwner
    private lateinit var runKey: AutomationRunKey

    val clock = LifecycleClock(Instant.parse("2026-08-27T09:00:00Z"), caseId)
    val ledger = LifecycleWorkLedger()
    private val coordinator = AutomationRuntimeWorkCoordinator(ledger)
    val background = LifecycleBackgroundExecutor()
    val executorEntries = AtomicInteger(0)
    val platformScheduleCalls = AtomicInteger(0)
    val wakeupCalls = AtomicInteger(0)
    private val jobId = reserveUnusedJobId()

    fun initialize() {
        ownedFiles().forEach { check(!it.exists()) { "Refusing to overwrite an existing fixture file" } }
        val ownPersistence = SQLiteAutomationSnapshotPersistence(context, automationFileName)
        persistence = ownPersistence
        storage = PersistentAutomationStorage(ownPersistence)
        effects = SyntheticEffectDatabase(context.noBackupFilesDir.resolve(effectsFileName))
        seedOneDueRun()
        owner = AutomationRuntimeOwner(
            storage = storage,
            wakeupAdapter = object : AutomationWakeupAdapter {
                override fun replaceWakeup(plan: AutomationWakeupPlan): AutomationAdapterResult =
                    if (wakeupCalls.incrementAndGet() <= wakeupFailures) {
                        AutomationAdapterResult.Rejected("fixture_wakeup_retry")
                    } else {
                        AutomationAdapterResult.Accepted
                    }
            },
            cycleDispatcher = AutomationRuntimeCycleDispatcher {
                error("The fixture must enter through the real JobScheduler, not a direct dispatch")
            },
            backgroundExecutor = background,
            platformState = clock,
            // The synthetic definition requires no account/network/capability grants.
            liveEnvironment = AutomationLiveEnvironmentSource.FAIL_CLOSED,
            codexExecutor = object : CodexAutomationExecutor {
                override fun execute(
                    request: CodexAutomationExecutionRequest,
                    heartbeat: AutomationHeartbeat,
                ): CodexAutomationExecutionOutcome = executeSyntheticEffect(request, heartbeat)
            },
            workerId = AutomationWorkerId("lifecycle-$caseId"),
            maximumCycleDuration = Duration.ofMinutes(3),
        )
        activitySubscription = owner.addActivityObserver { snapshot ->
            activeCycles.set(snapshot.acceptedCycleCount)
            activityEvents.offer(Unit)
        }
        binding = AndroidAutomationDispatch.installTestBinding(
            context,
            jobId,
            owner,
            coordinator,
            scheduleRetry = { ticket, failedAttempt ->
                platformScheduleCalls.incrementAndGet()
                val accepted = AndroidAutomationDispatch.scheduleExecutionRetry(
                    context,
                    jobId,
                    ticket,
                    failedAttempt,
                )
                if (accepted) {
                    installedRetries.offer(checkNotNull(scheduler.getPendingJob(jobId)) {
                        "Android accepted the replacement but did not retain the pending job"
                    })
                }
                accepted
            },
        )
        initialized = true
        assertSame("The fixture must not replace the production runtime", originalGlobalOwner, HansAutomationRuntime.currentOrNull())
    }

    fun addGate(
        position: DispatchGatePosition,
        effectAlreadyAcknowledged: Boolean = false,
    ): DispatchGate = DispatchGate(position, effectAlreadyAcknowledged).also {
        allGates += it
        gates.offer(it)
    }

    fun requestWakeup() {
        check(initialized && !closed)
        assertTrue(
            "The isolated coordinator rejected the owned platform scheduling request",
            coordinator.request(jobId, AutomationRuntimeWorkKind.TIMER, null) {
                platformScheduleCalls.incrementAndGet()
                // Keep the original stop regression's legacy scheduling identity covered.
                val extras = PersistableBundle().apply {
                    putString("trigger", "timer")
                    if (tokenizedScheduling) {
                        putString(AndroidAutomationDispatch.KEY_SCHEDULE_NONCE, UUID.randomUUID().toString())
                    }
                }
                val job = JobInfo.Builder(jobId, ComponentName(context, HansAutomationJobService::class.java))
                    .setMinimumLatency(0)
                    .setOverrideDeadline(0)
                    .setBackoffCriteria(10_000L, JobInfo.BACKOFF_POLICY_LINEAR)
                    .setExtras(extras)
                    .build()
                jobWasScheduled = true
                val accepted = scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
                if (accepted) explicitSchedules += job
                accepted
            },
        )
    }

    fun cancelOwnedPlatformJob() {
        check(binding != null && jobWasScheduled)
        scheduler.cancel(jobId)
    }

    fun pendingScheduleNonce(): String? = checkNotNull(scheduler.getPendingJob(jobId)) {
        "The owned scheduling must still exist while its executor is gated"
    }.extras.getString(AndroidAutomationDispatch.KEY_SCHEDULE_NONCE)

    fun lastExplicitScheduleNonce(): String? = explicitSchedules.last()
        .extras.getString(AndroidAutomationDispatch.KEY_SCHEDULE_NONCE)

    fun awaitScheduledRetry(): JobInfo = checkNotNull(installedRetries.poll(20, TimeUnit.SECONDS)) {
        "The actual service did not install a bounded retry through the production dispatcher"
    }

    fun forceOwnedScheduledRetry(expected: JobInfo) {
        check(binding != null && jobWasScheduled)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {}
        val pending = checkNotNull(scheduler.getPendingJob(jobId)) {
            "The explicit retry disappeared before deterministic platform delivery"
        }
        assertEquals(jobId, pending.id)
        assertEquals(ComponentName(context, HansAutomationJobService::class.java), pending.service)
        assertEquals(expected.service, pending.service)
        assertEquals(expected.minLatencyMillis, pending.minLatencyMillis)
        assertEquals(expected.maxExecutionDelayMillis, pending.maxExecutionDelayMillis)
        assertEquals(expected.isPersisted, pending.isPersisted)
        assertEquals(
            AndroidAutomationDispatch.retryAttemptFrom(expected.extras),
            AndroidAutomationDispatch.retryAttemptFrom(pending.extras),
        )
        assertEquals(
            expected.extras.getString(AndroidAutomationDispatch.KEY_SCHEDULE_NONCE),
            pending.extras.getString(AndroidAutomationDispatch.KEY_SCHEDULE_NONCE),
        )
        assertEquals(
            AutomationRuntimeTrigger.Timer,
            AndroidAutomationDispatch.triggerFrom(pending.extras),
        )

        val output = boundedShellOutput(
            "cmd jobscheduler run -f -u 0 ai.hans.standard $jobId",
            maximumBytes = 8 * 1024,
        )
        assertEquals("Running job [FORCED]", output.trim())
    }

    fun awaitAcknowledgedGeneration(generation: Long, timeoutSeconds: Long = 20) {
        try {
            ledger.awaitAcknowledged(generation, timeoutSeconds)
        } catch (failure: AssertionError) {
            // Capture before @After cancels the platform job and removes its isolated files.
            // Do not change the wait, schedule a replacement, or conceal the original assertion.
            val evidence = runCatching { timeoutEvidence() }.getOrElse {
                "Fixture evidence unavailable: ${it.javaClass.simpleName}: ${it.message}"
            }
            failure.addSuppressed(AssertionError(evidence))
            throw failure
        }
    }

    private fun timeoutEvidence(): String = buildString {
        val power = context.getSystemService(PowerManager::class.java)
        val pending = scheduler.getPendingJob(jobId)
        appendLine("Lifecycle timeout snapshot before cleanup (not an atomic system snapshot):")
        appendLine("jobId=$jobId schedules=${platformScheduleCalls.get()} wakeupCalls=${wakeupCalls.get()}")
        appendLine("executorEntries=${executorEntries.get()} completedCycles=${background.completedCount.get()}")
        appendLine("activeCycles=${activeCycles.get()} coordinatorActive=${coordinator.isActive(jobId)}")
        appendLine("pendingGeneration=${ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation} acknowledged=${ledger.acknowledgedGeneration()}")
        appendLine("runState=${persistedRun().state} effectCount=${persistedEffectCount()}")
        appendLine("platformJobPresent=${pending != null} service=${pending?.service?.flattenToShortString()}")
        appendLine("interactive=${power.isInteractive} deviceIdle=${power.isDeviceIdleMode} powerSave=${power.isPowerSaveMode}")
        appendLine("elapsedRealtimeMs=${SystemClock.elapsedRealtime()} uptimeMs=${SystemClock.uptimeMillis()}")
        // UiAutomation is available only in the already emulator-gated instrumentation APK.
        // These are read-only dumps with a platform-enforced two-second deadline and byte cap.
        for (command in listOf("dumpsys -t 2 jobscheduler ai.hans.standard", "dumpsys -t 2 alarm")) {
            appendLine("--- read-only diagnostic: $command ---")
            appendLine(runCatching { boundedSystemDump(command) }.getOrElse {
                "Diagnostic unavailable: ${it.javaClass.simpleName}: ${it.message}"
            })
        }
    }

    private fun boundedSystemDump(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val bytes = ByteArray(256 * 1024 + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            val truncated = count == bytes.size
            val text = String(bytes, 0, minOf(count, bytes.size - 1), Charsets.UTF_8)
            // Keep the JUnit failure Bundle comfortably below Android's Binder limit. The
            // beginning contains registered jobs; the end contains scheduler/alarm history.
            val excerpt = if (text.length > 32 * 1024) {
                text.take(16 * 1024) + "\n[middle of diagnostic omitted]\n" + text.takeLast(16 * 1024)
            } else {
                text
            }
            excerpt + if (truncated) "\n[source truncated at 256 KiB; incomplete]" else "\n[source EOF observed]"
        }
    }

    private fun boundedShellOutput(command: String, maximumBytes: Int): String {
        require(maximumBytes in 1..(64 * 1024))
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val bytes = ByteArray(maximumBytes + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            check(count <= maximumBytes) { "Owned JobScheduler command output exceeded its byte bound" }
            String(bytes, 0, count, Charsets.UTF_8)
        }
    }

    fun awaitSettled(checkWorkerFailure: Boolean = true) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (true) {
            // A posted service completion can outlive the owner's activity=0 publication.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {}
            if (activeCycles.get() == 0 && !coordinator.isActive(jobId)) break
            val remaining = deadline - System.nanoTime()
            assertTrue("Owned service callbacks did not settle", remaining > 0)
            assertNotNull("Owned service callbacks did not settle", activityEvents.poll(remaining, TimeUnit.NANOSECONDS))
        }
        background.awaitDrained()
        if (checkWorkerFailure) assertNull("Synthetic executor failed", executorFailure.get())
        assertSame(originalGlobalOwner, HansAutomationRuntime.currentOrNull())
    }

    fun persistedRun(): AutomationRun =
        SQLiteAutomationSnapshotPersistence(context, automationFileName).use { reopened ->
            PersistentAutomationStorage(reopened).snapshot().runs.single { it.key == runKey }
        }

    fun persistedEffectCount(): Long = SyntheticEffectDatabase.readCount(
        context.noBackupFilesDir.resolve(effectsFileName),
    )

    private fun executeSyntheticEffect(
        request: CodexAutomationExecutionRequest,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome {
        val entry = executorEntries.incrementAndGet()
        if (aborted.get()) return CodexAutomationExecutionOutcome.RetryableFailure("fixture_cleanup")
        val gate = gates.poll()
        if (gate == null) {
            executorFailure.compareAndSet(null, IllegalStateException("Unexpected synthetic executor entry $entry"))
            return CodexAutomationExecutionOutcome.PermanentFailure("fixture_unexpected_dispatch")
        }
        val cancellation = heartbeat.onCancellation(gate::onSystemCancellation)
        try {
            if (gate.position == DispatchGatePosition.AFTER_FENCE) {
                check(heartbeat.markExternalDispatchStarted()) { "Fixture could not persist its dispatch fence" }
                checkNotNull(effects).record(request.idempotencyKey, entry)
            }
            gate.entered.countDown()
            check(gate.awaitProceed()) { "Fixture executor gate timed out" }
            if (aborted.get()) return CodexAutomationExecutionOutcome.RetryableFailure("fixture_cleanup")
            // A confirmed result remains authoritative even when cancellation reaches us afterward.
            if (gate.position == DispatchGatePosition.AFTER_FENCE && gate.effectAlreadyAcknowledged) {
                return CodexAutomationExecutionOutcome.Succeeded
            }
            if (!heartbeat.beat()) {
                return CodexAutomationExecutionOutcome.RetryableFailure("job_execution_stopped")
            }
            if (gate.position == DispatchGatePosition.BEFORE_FENCE) {
                check(heartbeat.markExternalDispatchStarted()) { "Fixture lost ownership before effect" }
                checkNotNull(effects).record(request.idempotencyKey, entry)
            }
            return CodexAutomationExecutionOutcome.Succeeded
        } catch (error: Exception) {
            executorFailure.compareAndSet(null, error)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            return CodexAutomationExecutionOutcome.PermanentFailure("fixture_executor_failed")
        } finally {
            cancellation.close()
        }
    }

    private fun seedOneDueRun() {
        val now = clock.now()
        val definition = AutomationDefinition(
            id = AutomationId("automation.lifecycle.$caseId"),
            revision = 1,
            enabled = true,
            schedule = AutomationSchedule(
                LocalDateTime.ofInstant(now, ZoneId.of("UTC")),
                AutomationTimeZone.Fixed("UTC"),
                "FREQ=DAILY",
            ),
            missedRunPolicy = MissedRunPolicy(),
            retryPolicy = AutomationRetryPolicy(),
            target = CodexAutomationTarget.Independent,
            instruction = "Record one isolated synthetic lifecycle test effect.",
            updatedAt = now,
            requirements = AutomationRequirements(
                requiredCapabilities = emptySet(),
                requiresCodexAuthentication = false,
                requiresNetwork = false,
                confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
            ),
        )
        runKey = AutomationRunKey(definition.id, now)
        assertEquals(DefinitionWriteResult.INSERTED, storage.upsertDefinition(definition))
        assertTrue(
            storage.recordDiscovery(
                AutomationDiscoveryBatch(
                    definition.id,
                    definition.revision,
                    now,
                    listOf(AutomationInboxItem(runKey, definition.revision, now, now, AutomationDiscoverySource.TIMER)),
                ),
            ).accepted,
        )
        assertEquals(listOf(runKey), storage.materializeDueInbox(now).createdRuns)
    }

    private fun reserveUnusedJobId(): Int {
        repeat(32) {
            val candidate = 0x48540000 or (UUID.randomUUID().hashCode() and 0xffff)
            if (scheduler.getPendingJob(candidate) == null) return candidate
        }
        error("No unused automation lifecycle test job ID found")
    }

    override fun close() {
        if (closed) return
        aborted.set(true)
        allGates.forEach(DispatchGate::releaseForCleanup)
        if (binding != null && jobWasScheduled) scheduler.cancel(jobId)
        // If this cannot settle, retain the isolated binding/files for host teardown instead of
        // deleting resources beneath a live callback. The host owns the disposable emulator.
        if (initialized) awaitSettled(checkWorkerFailure = false)
        background.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {}
        if (binding != null && jobWasScheduled) scheduler.cancel(jobId)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {}
        check(!coordinator.isActive(jobId)) { "Refusing to close a binding with active work" }
        binding?.close()
        binding = null
        activitySubscription?.close()
        persistence?.close()
        effects?.close()
        val parent = context.noBackupFilesDir.canonicalFile
        ownedFiles().forEach { file ->
            check(file.canonicalFile.parentFile == parent) { "Fixture cleanup escaped its directory" }
            if (file.exists()) check(file.isFile && file.delete()) { "Could not remove owned fixture file" }
        }
        assertSame(originalGlobalOwner, HansAutomationRuntime.currentOrNull())
        closed = true
    }

    private fun ownedFiles(): List<File> = listOf(automationFileName, effectsFileName).flatMap { name ->
        listOf("", "-wal", "-shm", "-journal").map { suffix -> context.noBackupFilesDir.resolve(name + suffix) }
    }
}

private class LifecycleClock(initial: Instant, caseId: String) : AutomationPlatformStateSource {
    private val instant = AtomicReference(initial)
    private val boot = AutomationBootSessionId("lifecycle-$caseId")
    override fun now(): Instant = instant.get()
    override fun systemZone(): ZoneId = ZoneId.of("UTC")
    override fun bootSessionId(): AutomationBootSessionId = boot
    fun set(value: Instant) = instant.set(value)
    fun advance(duration: Duration) = instant.updateAndGet { it.plus(duration) }
}

/** Process-local by design: these tests do not pretend to exercise a process/reboot migration. */
private class LifecycleWorkLedger : AutomationRuntimeWorkLedger {
    private val requested = linkedMapOf<AutomationRuntimeWorkKind, AutomationRuntimeWorkTicket>()
    private val acknowledged = linkedMapOf<AutomationRuntimeWorkKind, Long>()
    private val acknowledgements = LinkedBlockingQueue<Long>()

    @Synchronized
    override fun record(kind: AutomationRuntimeWorkKind, observedAt: Instant?): AutomationRuntimeWorkTicket =
        AutomationRuntimeWorkTicket(kind, (requested[kind]?.generation ?: 0L) + 1, observedAt).also {
            requested[kind] = it
        }

    @Synchronized
    override fun pending(kind: AutomationRuntimeWorkKind): AutomationRuntimeWorkTicket? =
        requested[kind]?.takeIf { it.generation > (acknowledged[kind] ?: 0L) }

    @Synchronized
    override fun allPending(): List<AutomationRuntimeWorkTicket> = requested.keys.mapNotNull(::pending)

    @Synchronized
    override fun acknowledge(ticket: AutomationRuntimeWorkTicket): AutomationRuntimeWorkTicket? {
        acknowledged[ticket.kind] = maxOf(acknowledged[ticket.kind] ?: 0L, ticket.generation)
        acknowledgements.offer(ticket.generation)
        return pending(ticket.kind)
    }

    @Synchronized
    fun acknowledgedGeneration(): Long = acknowledged[AutomationRuntimeWorkKind.TIMER] ?: 0L

    fun awaitAcknowledged(generation: Long, timeoutSeconds: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (acknowledgedGeneration() < generation) {
            val remaining = deadline - System.nanoTime()
            assertTrue("Work generation $generation was not acknowledged", remaining > 0)
            assertNotNull("Work generation $generation was not acknowledged", acknowledgements.poll(remaining, TimeUnit.NANOSECONDS))
        }
    }
}

private class LifecycleBackgroundExecutor : Executor, AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { command ->
        Thread(command, "hans-automation-lifecycle-fixture")
    }
    val completedCount = AtomicInteger(0)

    override fun execute(command: Runnable) {
        executor.execute {
            try {
                command.run()
            } finally {
                completedCount.incrementAndGet()
            }
        }
    }

    fun awaitDrained() {
        // Called only after owner and service have no active cycle; this also observes the final
        // executor wrapper's completion count, which is published after the owner's last event.
        executor.submit {}.get(10, TimeUnit.SECONDS)
    }

    override fun close() {
        executor.shutdown()
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Fixture worker did not terminate" }
        }
    }
}

/** Counts every effect attempt; no unique idempotency constraint is allowed to hide a duplicate. */
private class SyntheticEffectDatabase(private val file: File) : AutoCloseable {
    private val database = SQLiteDatabase.openOrCreateDatabase(file, null).apply {
        execSQL(
            "CREATE TABLE synthetic_effects (sequence INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "idempotency_key TEXT NOT NULL, executor_entry INTEGER NOT NULL)",
        )
    }

    @Synchronized
    fun record(idempotencyKey: String, executorEntry: Int) {
        database.insertOrThrow(
            "synthetic_effects",
            null,
            ContentValues().apply {
                put("idempotency_key", idempotencyKey)
                put("executor_entry", executorEntry)
            },
        )
    }

    override fun close() = database.close()

    companion object {
        fun readCount(file: File): Long = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { reopened ->
            reopened.rawQuery("SELECT COUNT(*) FROM synthetic_effects", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
        }
    }
}
