package ai.hans.standard.automations

import ai.hans.standard.backup.HansBackupMaintenance
import java.time.Instant
import java.time.Duration
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

fun interface AutomationLiveEnvironmentSource {
    /** Must probe current capabilities and grants; cached UI selections are not authoritative. */
    fun snapshot(): AutomationExecutionEnvironment

    companion object {
        val FAIL_CLOSED = AutomationLiveEnvironmentSource {
            AutomationExecutionEnvironment(
                availableCapabilities = emptySet(),
                grantedPermissions = emptySet(),
                codexAuthenticated = false,
                networkAvailable = false,
                deviceUnlocked = false,
            )
        }
    }
}

interface AutomationCodexGateway {
    /** Call [heartbeat] immediately before the first App Server/network side effect. */
    fun executeInExistingThread(
        threadId: String,
        instruction: String,
        idempotencyKey: String,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome

    /**
     * Starts an independent App Server thread/turn (or equivalent adapter), checking [heartbeat]
     * immediately before the first App Server/network side effect.
     */
    fun executeInNewThread(
        instruction: String,
        idempotencyKey: String,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome
}

class GatewayCodexAutomationExecutor(
    private val gateway: AutomationCodexGateway,
) : CodexAutomationExecutor {
    override fun execute(
        request: CodexAutomationExecutionRequest,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome = when (val target = request.target) {
        is CodexAutomationTarget.ThreadBound -> gateway.executeInExistingThread(
            target.threadId,
            request.instruction,
            request.idempotencyKey,
            heartbeat,
        )
        CodexAutomationTarget.Independent -> gateway.executeInNewThread(
            request.instruction,
            request.idempotencyKey,
            heartbeat,
        )
    }
}

interface AutomationPlatformStateSource {
    fun now(): Instant
    fun systemZone(): ZoneId
    /** Stable across process restarts in one boot and different after a reboot. */
    fun bootSessionId(): AutomationBootSessionId
}

sealed interface AutomationRuntimeTrigger {
    data object Timer : AutomationRuntimeTrigger
    data object ManualRun : AutomationRuntimeTrigger
    data object ConnectivityRestored : AutomationRuntimeTrigger
    data object DefinitionChanged : AutomationRuntimeTrigger
    /** Event-driven catch-up after Android reports that the user is present again. */
    data object UserPresent : AutomationRuntimeTrigger
    data class Boot(val observedAt: Instant) : AutomationRuntimeTrigger
    data class TimeZoneChanged(val observedAt: Instant) : AutomationRuntimeTrigger
    data class WallClockChanged(val observedAt: Instant) : AutomationRuntimeTrigger
}

/**
 * Routes process-external automation signals through a platform-owned lifecycle. Production uses
 * JobScheduler; tests provide a deterministic recorder. Persisting work is not sufficient on its
 * own because a plain process executor can be killed as soon as the UI leaves the foreground.
 */
fun interface AutomationRuntimeCycleDispatcher {
    fun dispatch(trigger: AutomationRuntimeTrigger): AutomationAdapterResult
}

fun interface AutomationMonotonicTimeSource {
    fun nowNanos(): Long

    companion object {
        val SYSTEM = AutomationMonotonicTimeSource(System::nanoTime)
    }
}

enum class AutomationRuntimeCycleStopReason {
    SYSTEM_STOP,
    TIME_BUDGET_EXHAUSTED,
}

data class AutomationRuntimeCycleReport(
    val trigger: AutomationRuntimeTrigger,
    val reconciled: AutomationReconciliationReport,
    val materializedRuns: Int,
    val executionAttempts: Int,
    val successfulRuns: Int,
    val deferredRuns: Int,
    val failedRuns: Int,
    val ownershipLosses: Int,
    val wakeupResult: AutomationAdapterResult,
    val stopReason: AutomationRuntimeCycleStopReason? = null,
)

/** Content-free liveness used by the publisher-key migration quiescence gate. */
data class AutomationRuntimeActivitySnapshot(
    val acceptedCycleCount: Int,
    val persistedLeaseCount: Int,
) {
    init {
        require(acceptedCycleCount >= 0)
        require(persistedLeaseCount >= 0)
    }

    val activeAutomationCount: Int
        get() = maxOf(acceptedCycleCount, persistedLeaseCount)
}

fun interface AutomationRuntimeActivityObserver {
    fun onSnapshot(snapshot: AutomationRuntimeActivitySnapshot)
}

class AutomationRuntimeCycleHandle internal constructor(
    private val monotonicTimeSource: AutomationMonotonicTimeSource,
    private val deadlineNanos: Long,
) {
    private val lock = Any()
    private val cancelled = AtomicBoolean(false)
    private val cancellationListeners = LinkedHashSet<() -> Unit>()

    @Volatile
    private var reason: AutomationRuntimeCycleStopReason? = null

    fun cancel() {
        cancel(AutomationRuntimeCycleStopReason.SYSTEM_STOP)
    }

    internal fun cancel(stopReason: AutomationRuntimeCycleStopReason) {
        val listeners = synchronized(lock) {
            if (!cancelled.compareAndSet(false, true)) return
            reason = stopReason
            cancellationListeners.toList().also { cancellationListeners.clear() }
        }
        listeners.forEach { listener -> runCatching(listener) }
    }

    internal fun onCancellation(listener: () -> Unit): AutoCloseable {
        val invokeNow = synchronized(lock) {
            if (cancelled.get()) {
                true
            } else {
                cancellationListeners += listener
                false
            }
        }
        if (invokeNow) runCatching(listener)
        return AutoCloseable {
            synchronized(lock) { cancellationListeners.remove(listener) }
        }
    }

    /** Null means stopped; otherwise this is a ceiling-safe wait bound for blocking gateways. */
    internal fun remainingMillis(): Long? {
        if (cancelled.get()) return null
        val remaining = deadlineNanos - monotonicTimeSource.nowNanos()
        if (remaining <= 0L) {
            cancel(AutomationRuntimeCycleStopReason.TIME_BUDGET_EXHAUSTED)
            return null
        }
        return maxOf(1L, (remaining + 999_999L) / 1_000_000L)
    }

    internal fun isCancelled(): Boolean = remainingMillis() == null

    internal fun stopReason(): AutomationRuntimeCycleStopReason? = reason
}

/**
 * Process-wide owner. All scheduling/storage operations are serialized off the Android main
 * thread, so BroadcastReceiver and JobService entry points can safely share one instance.
 */
class AutomationRuntimeOwner(
    storage: AutomationStorage,
    private val wakeupAdapter: AutomationWakeupAdapter,
    private val cycleDispatcher: AutomationRuntimeCycleDispatcher,
    backgroundExecutor: Executor,
    private val platformState: AutomationPlatformStateSource,
    liveEnvironment: AutomationLiveEnvironmentSource,
    codexExecutor: CodexAutomationExecutor,
    private val workerId: AutomationWorkerId = AutomationWorkerId("hans-automation-worker"),
    private val leaseTokenSource: AutomationLeaseTokenSource = AutomationLeaseTokenSource {
        AutomationLeaseToken("lease:${UUID.randomUUID()}")
    },
    private val maximumRunsPerCycle: Int = 32,
    private val maximumCycleDuration: Duration = DEFAULT_MAXIMUM_CYCLE_DURATION,
    private val monotonicTimeSource: AutomationMonotonicTimeSource =
        AutomationMonotonicTimeSource.SYSTEM,
    val backupMaintenance: HansBackupMaintenance =
        (storage as? BackupMaintainedAutomationStorage)?.maintenance ?: HansBackupMaintenance(),
) {
    val storage: AutomationStorage = if (storage is BackupMaintainedAutomationStorage) {
        check(storage.maintenance === backupMaintenance) { "automation_backup_boundary_mismatch" }
        storage
    } else {
        BackupMaintainedAutomationStorage(storage, backupMaintenance)
    }
    val definitions = AutomationDefinitionManager(this.storage, AutomationInstantSource(platformState::now))
    val history = AutomationHistoryReader(this.storage)
    private val scheduler = AutomationScheduler(this.storage)
    private val rescheduleCoordinator = AutomationRescheduleCoordinator(this.storage)
    private val serialExecutor = SerialExecutor(backgroundExecutor)
    private val authorizationProvider = EnvironmentAutomationAuthorizationProvider {
        val now = platformState.now()
        val live = liveEnvironment.snapshot()
        // EVERY_RUN consent is authoritative only when represented by our durable exact-run
        // receipt. A live adapter cannot manufacture confirmation by claiming a key.
        live.copy(confirmedRuns = this.storage.confirmedRunKeys(now))
    }
    private val runner = AutomationExecutionRunner(
        storage = this.storage,
        executor = codexExecutor,
        instantSource = AutomationInstantSource(platformState::now),
        tokenSource = leaseTokenSource,
        workerId = workerId,
        bootSessionId = platformState.bootSessionId(),
        authorizationProvider = authorizationProvider,
    )
    private val activityLock = Any()
    private val activityObservers = LinkedHashSet<AutomationRuntimeActivityObserver>()
    private val pendingActivityPublications = ArrayDeque<AutomationActivityPublication>()
    private var drainingActivityPublications = false
    private var acceptedCycleCount = 0

    init {
        require(maximumRunsPerCycle in 1..256)
        require(!maximumCycleDuration.isNegative && !maximumCycleDuration.isZero)
        require(maximumCycleDuration <= Duration.ofMinutes(30))
    }

    fun requestCycle(
        trigger: AutomationRuntimeTrigger,
        completion: (Result<AutomationRuntimeCycleReport>) -> Unit = {},
    ): AutomationRuntimeCycleHandle {
        val now = monotonicTimeSource.nowNanos()
        val durationNanos = maximumCycleDuration.toNanos()
        val deadline = if (now > Long.MAX_VALUE - durationNanos) Long.MAX_VALUE else {
            now + durationNanos
        }
        val handle = AutomationRuntimeCycleHandle(monotonicTimeSource, deadline)
        val reservation = try {
            backupMaintenance.reserveAutomationActivity()
        } catch (failure: Exception) {
            runCatching { completion(Result.failure(failure)) }
            return handle
        }
        val activityCompletion = AtomicBoolean(false)
        try {
            markCycleAccepted()
        } catch (failure: Exception) {
            reservation.close()
            throw failure
        }
        serialExecutor.execute(
            command = {
                try {
                    val result = if (handle.isCancelled()) {
                        Result.failure(AutomationRuntimeCycleCancelledException())
                    } else {
                        runCatching { runCycle(trigger, handle) }
                    }
                    runCatching { completion(result) }
                } finally {
                    try {
                        markCycleFinished(activityCompletion)
                    } finally {
                        reservation.close()
                    }
                }
            },
            rejected = {
                try {
                    runCatching {
                        completion(Result.failure(AutomationRuntimeDispatchException()))
                    }
                } finally {
                    try {
                        markCycleFinished(activityCompletion)
                    } finally {
                        reservation.close()
                    }
                }
            },
        )
        return handle
    }

    /** Passive observation only; registering neither schedules nor executes automation work. */
    fun addActivityObserver(observer: AutomationRuntimeActivityObserver): AutoCloseable {
        val shouldDrain = synchronized(activityLock) {
            activityObservers += observer
            enqueueActivityPublicationLocked(listOf(observer))
        }
        if (shouldDrain) drainActivityPublications()
        return AutoCloseable { synchronized(activityLock) { activityObservers -= observer } }
    }

    private fun markCycleAccepted() {
        val shouldDrain = synchronized(activityLock) {
            check(acceptedCycleCount < Int.MAX_VALUE) { "automation_activity_overflow" }
            acceptedCycleCount += 1
            enqueueActivityPublicationLocked(activityObservers.toList())
        }
        if (shouldDrain) drainActivityPublications()
    }

    private fun markCycleFinished(completion: AtomicBoolean) {
        if (!completion.compareAndSet(false, true)) return
        val shouldDrain = synchronized(activityLock) {
            check(acceptedCycleCount > 0) { "automation_activity_underflow" }
            acceptedCycleCount -= 1
            enqueueActivityPublicationLocked(activityObservers.toList())
        }
        if (shouldDrain) drainActivityPublications()
    }

    private fun enqueueActivityPublicationLocked(
        targets: List<AutomationRuntimeActivityObserver>,
    ): Boolean {
        pendingActivityPublications.addLast(
            AutomationActivityPublication(
                acceptedCycleCount = acceptedCycleCount,
                targets = targets,
            ),
        )
        if (drainingActivityPublications) return false
        drainingActivityPublications = true
        return true
    }

    private fun drainActivityPublications() {
        while (true) {
            val publication = synchronized(activityLock) {
                pendingActivityPublications.pollFirst().also {
                    if (it == null) drainingActivityPublications = false
                }
            } ?: return
            val persistedLeaseCount = runCatching { storage.snapshot().leases.size }
                .getOrDefault(1)
            val snapshot = AutomationRuntimeActivitySnapshot(
                acceptedCycleCount = publication.acceptedCycleCount,
                persistedLeaseCount = persistedLeaseCount,
            )
            publication.targets.forEach { observer ->
                val stillRegistered = synchronized(activityLock) { observer in activityObservers }
                if (stillRegistered) runCatching { observer.onSnapshot(snapshot) }
            }
        }
    }

    private data class AutomationActivityPublication(
        val acceptedCycleCount: Int,
        val targets: List<AutomationRuntimeActivityObserver>,
    )

    fun enqueueRunNow(
        id: AutomationId,
        expectedRevision: Long,
        requestId: AutomationManualRequestId,
    ): AutomationManualEnqueueResult = backupMaintenance.withStateAccess {
        val result = storage.enqueueManualRun(
            id,
            expectedRevision,
            requestId,
            platformState.now(),
        )
        if (
            result is AutomationManualEnqueueResult.Enqueued ||
            result is AutomationManualEnqueueResult.Duplicate
        ) {
            val dispatch = cycleDispatcher.dispatch(AutomationRuntimeTrigger.ManualRun)
            if (dispatch is AutomationAdapterResult.Rejected) {
                val key = when (result) {
                    is AutomationManualEnqueueResult.Enqueued -> result.key
                    is AutomationManualEnqueueResult.Duplicate -> result.key
                    else -> error("unreachable")
                }
                return@withStateAccess AutomationManualEnqueueResult.DispatchRejected(
                    key = key,
                    errorCode = dispatch.errorCode,
                    retryable = dispatch.retryable,
                )
            }
        }
        result
    }

    /**
     * Trusted host path for a literal user tap on "run now". Unlike the model-facing dynamic
     * tool, that gesture itself is exact-run consent: an EVERY_RUN definition receives a bounded,
     * durable receipt for only the newly enqueued run before platform dispatch. This method must
     * never be exposed as an agent-callable tool.
     */
    fun enqueueUserApprovedRunNow(
        id: AutomationId,
        expectedRevision: Long,
        requestId: AutomationManualRequestId,
    ): AutomationManualEnqueueResult = backupMaintenance.withStateAccess {
        val now = platformState.now()
        val prepared = storage.withAtomicAccess {
            val enqueued = storage.enqueueManualRun(id, expectedRevision, requestId, now)
            val key = when (enqueued) {
                is AutomationManualEnqueueResult.Enqueued -> enqueued.key
                is AutomationManualEnqueueResult.Duplicate -> enqueued.key
                else -> return@withAtomicAccess enqueued
            }
            val definition = storage.definition(id)
                ?: return@withAtomicAccess AutomationManualEnqueueResult.DispatchRejected(
                    key,
                    "automation_definition_changed",
                    retryable = false,
                )
            if (
                definition.revision != expectedRevision ||
                definition.requirements.confirmationPolicy != AutomationConfirmationPolicy.EVERY_RUN
            ) {
                return@withAtomicAccess enqueued
            }

            // A confirmation is tied to a materialized run, not merely an inbox occurrence. Keep
            // the whole prepare sequence under the storage transaction so another worker cannot
            // lease the run between materialization and the exact receipt write.
            var run = storage.snapshot().runs.firstOrNull { it.key == key }
            var batches = 0
            while (run == null && batches < 16) {
                val batch = storage.materializeDueInbox(now, maximumItems = 256)
                batches += 1
                run = storage.snapshot().runs.firstOrNull { it.key == key }
                if (batch.createdRuns.isEmpty() && batch.staleRunsSkipped.isEmpty()) break
            }
            val effectiveRun = run
                ?: return@withAtomicAccess AutomationManualEnqueueResult.DispatchRejected(
                    key,
                    "automation_run_materialization_failed",
                    retryable = true,
                )
            if (effectiveRun.state in setOf(
                    AutomationRunState.SUCCEEDED,
                    AutomationRunState.FAILED_TERMINAL,
                    AutomationRunState.SKIPPED,
                )
            ) {
                return@withAtomicAccess enqueued
            }
            val confirmation = AutomationRunConfirmationReceipt(
                id = AutomationConfirmationId("ui-run:${UUID.randomUUID()}"),
                key = key,
                definitionRevision = expectedRevision,
                confirmedAt = now,
                // The tap authorizes only this exact durable run. Give Android enough time to
                // leave Doze or regain a network; after one day the normal EVERY_RUN approval
                // path deliberately takes over instead of executing a stale request silently.
                expiresAt = now.plus(Duration.ofHours(24)),
            )
            when (storage.recordConfirmation(confirmation, now)) {
                AutomationConfirmationWriteResult.STORED,
                AutomationConfirmationWriteResult.DUPLICATE,
                -> enqueued
                AutomationConfirmationWriteResult.CONFIRMATION_ID_CONFLICT,
                AutomationConfirmationWriteResult.RUN_NOT_FOUND,
                AutomationConfirmationWriteResult.REVISION_CONFLICT,
                AutomationConfirmationWriteResult.RUN_NOT_CONFIRMABLE,
                -> AutomationManualEnqueueResult.DispatchRejected(
                    key,
                    "automation_confirmation_persistence_failed",
                    retryable = false,
                )
            }
        }
        if (
            prepared is AutomationManualEnqueueResult.Enqueued ||
            prepared is AutomationManualEnqueueResult.Duplicate
        ) {
            val dispatch = cycleDispatcher.dispatch(AutomationRuntimeTrigger.ManualRun)
            if (dispatch is AutomationAdapterResult.Rejected) {
                val key = when (prepared) {
                    is AutomationManualEnqueueResult.Enqueued -> prepared.key
                    is AutomationManualEnqueueResult.Duplicate -> prepared.key
                    else -> error("unreachable")
                }
                return@withStateAccess AutomationManualEnqueueResult.DispatchRejected(
                    key,
                    dispatch.errorCode,
                    dispatch.retryable,
                )
            }
        }
        prepared
    }

    fun scheduleChanged(): AutomationAdapterResult =
        backupMaintenance.withStateAccess {
            cycleDispatcher.dispatch(AutomationRuntimeTrigger.DefinitionChanged)
        }

    fun connectivityRestored(): AutomationAdapterResult =
        backupMaintenance.withStateAccess {
            cycleDispatcher.dispatch(AutomationRuntimeTrigger.ConnectivityRestored)
        }

    fun userPresent(): AutomationAdapterResult =
        backupMaintenance.withStateAccess {
            cycleDispatcher.dispatch(AutomationRuntimeTrigger.UserPresent)
        }

    private fun runCycle(
        trigger: AutomationRuntimeTrigger,
        handle: AutomationRuntimeCycleHandle,
    ): AutomationRuntimeCycleReport {
        val now = platformState.now()
        val zone = platformState.systemZone()
        if (handle.isCancelled()) throw AutomationRuntimeCycleCancelledException()
        applyRescheduleSignal(trigger, now, zone)
        storage.recoverLeases(now, platformState.bootSessionId())
        storage.releaseDeferredRuns(trigger.resolvedPreconditions(), now)
        if (handle.isCancelled()) throw AutomationRuntimeCycleCancelledException()
        val source = when (trigger) {
            AutomationRuntimeTrigger.Timer,
            AutomationRuntimeTrigger.DefinitionChanged,
            -> AutomationDiscoverySource.TIMER
            AutomationRuntimeTrigger.ManualRun -> AutomationDiscoverySource.MANUAL_RUN
            AutomationRuntimeTrigger.ConnectivityRestored -> AutomationDiscoverySource.OFFLINE_RECOVERY
            AutomationRuntimeTrigger.UserPresent -> AutomationDiscoverySource.MANUAL_RECONCILIATION
            is AutomationRuntimeTrigger.Boot -> AutomationDiscoverySource.BOOT_RECOVERY
            is AutomationRuntimeTrigger.TimeZoneChanged ->
                AutomationDiscoverySource.TIME_ZONE_RESCHEDULE
            is AutomationRuntimeTrigger.WallClockChanged ->
                AutomationDiscoverySource.MANUAL_RECONCILIATION
        }
        val reconciliation = scheduler.reconcile(now, zone, source)
        var materialized = 0
        for (batch in 0 until 16) {
            if (handle.isCancelled()) break
            val result = storage.materializeDueInbox(platformState.now(), maximumItems = 256)
            materialized += result.createdRuns.size + result.staleRunsSkipped.size
            if (result.createdRuns.size + result.staleRunsSkipped.size < 256) break
        }

        var attempts = 0
        var successes = 0
        var deferred = 0
        var failures = 0
        var ownershipLosses = 0
        while (attempts < maximumRunsPerCycle && !handle.isCancelled()) {
            when (
                runner.runNext(
                    cancellationRequested = handle::isCancelled,
                    cancellationRegistration = handle::onCancellation,
                    waitBound = { proposed ->
                        handle.remainingMillis()?.let { minOf(it, proposed) } ?: 1L
                    },
                ).status
            ) {
                AutomationRunAttemptStatus.NO_RUN_AVAILABLE -> break
                AutomationRunAttemptStatus.SUCCEEDED -> {
                    attempts += 1
                    successes += 1
                }
                AutomationRunAttemptStatus.DEFERRED -> {
                    attempts += 1
                    deferred += 1
                }
                AutomationRunAttemptStatus.RETRY_SCHEDULED -> {
                    attempts += 1
                    failures += 1
                }
                AutomationRunAttemptStatus.FAILED_TERMINAL -> {
                    attempts += 1
                    failures += 1
                }
                AutomationRunAttemptStatus.OWNERSHIP_LOST -> {
                    attempts += 1
                    ownershipLosses += 1
                }
            }
        }
        val wakeup = nextRuntimeWakeup(platformState.now(), zone)
        return AutomationRuntimeCycleReport(
            trigger = trigger,
            reconciled = reconciliation,
            materializedRuns = materialized,
            executionAttempts = attempts,
            successfulRuns = successes,
            deferredRuns = deferred,
            failedRuns = failures,
            ownershipLosses = ownershipLosses,
            wakeupResult = wakeupAdapter.replaceWakeup(wakeup),
            stopReason = handle.stopReason(),
        )
    }

    companion object {
        /**
         * Android 16 job quota is cumulative and device-dependent, not an eight-minute promise.
         * This ceiling merely leaves cleanup margin below the documented roughly ten-minute
         * rare/restricted-bucket guidance and is checked before every run and by gateway waits.
         */
        val DEFAULT_MAXIMUM_CYCLE_DURATION: Duration = Duration.ofMinutes(8)

        /** Inexact scheduling hint for durable outstanding work, not a dispatch-time guarantee. */
        private val RECOVERY_MAXIMUM_INEXACT_DELAY: Duration = Duration.ofSeconds(30)
    }

    private fun applyRescheduleSignal(
        trigger: AutomationRuntimeTrigger,
        now: Instant,
        zone: ZoneId,
    ) {
        val signal = when (trigger) {
            is AutomationRuntimeTrigger.Boot -> AutomationRescheduleSignal.BootCompleted(
                trigger.observedAt,
                platformState.bootSessionId(),
                zone,
            )
            is AutomationRuntimeTrigger.TimeZoneChanged -> AutomationRescheduleSignal.TimeZoneChanged(
                trigger.observedAt,
                storage.recoveryState()?.let { ZoneId.of(it.systemZoneId) } ?: zone,
                zone,
            )
            is AutomationRuntimeTrigger.WallClockChanged -> AutomationRescheduleSignal.WallClockChanged(
                trigger.observedAt,
                zone,
                platformState.bootSessionId(),
            )
            AutomationRuntimeTrigger.ConnectivityRestored ->
                AutomationRescheduleSignal.ConnectivityRestored(now, zone)
            AutomationRuntimeTrigger.Timer,
            AutomationRuntimeTrigger.ManualRun,
            AutomationRuntimeTrigger.DefinitionChanged,
            AutomationRuntimeTrigger.UserPresent,
            -> null
        }
        if (signal != null) rescheduleCoordinator.apply(signal)
    }

    private fun nextRuntimeWakeup(now: Instant, zone: ZoneId): AutomationWakeupPlan {
        val scheduled = scheduler.nextWakeup(now, zone)
        val snapshot = storage.snapshot()
        val dueCandidates = buildList {
            snapshot.inbox.forEach { add(it.key.automationId to it.readyAt) }
            snapshot.runs.filter {
                it.state == AutomationRunState.PENDING || it.state == AutomationRunState.RETRY_WAIT
            }.forEach { add(it.key.automationId to it.availableAt) }
            snapshot.leases.forEach { add(it.key.automationId to it.expiresAt) }
        }
        val dueAt = dueCandidates.minOfOrNull { it.second }
        val nextInexact = listOfNotNull(scheduled.nextInexactWakeAt, dueAt).minOrNull()
        if (dueAt == null || scheduled.wakeAt != null && scheduled.wakeAt <= dueAt) {
            return scheduled.copy(
                nextInexactWakeAt = nextInexact,
                maximumInexactDelay = if (dueAt == null) null else RECOVERY_MAXIMUM_INEXACT_DELAY,
            )
        }
        return AutomationWakeupPlan(
            wakeAt = maxOf(now, dueAt),
            automationIds = dueCandidates.filter { it.second == dueAt }.mapTo(linkedSetOf()) { it.first },
            timingPolicy = AutomationTimingPolicy.RELIABLE_INEXACT,
            nextInexactWakeAt = maxOf(now, dueAt),
            failures = scheduled.failures,
            maximumInexactDelay = RECOVERY_MAXIMUM_INEXACT_DELAY,
        )
    }
}

private fun AutomationRuntimeTrigger.resolvedPreconditions(): Set<AutomationResolvedPrecondition> =
    when (this) {
        AutomationRuntimeTrigger.ConnectivityRestored ->
            setOf(AutomationResolvedPrecondition.VALIDATED_NETWORK)
        AutomationRuntimeTrigger.UserPresent ->
            setOf(AutomationResolvedPrecondition.DEVICE_UNLOCKED)
        AutomationRuntimeTrigger.Timer,
        AutomationRuntimeTrigger.ManualRun,
        AutomationRuntimeTrigger.DefinitionChanged,
        is AutomationRuntimeTrigger.Boot,
        is AutomationRuntimeTrigger.TimeZoneChanged,
        is AutomationRuntimeTrigger.WallClockChanged,
        -> emptySet()
    }

/** Static Android component entry point; install exactly once from the application/host. */
object HansAutomationRuntime {
    @Volatile
    private var owner: AutomationRuntimeOwner? = null

    @Synchronized
    fun install(candidate: AutomationRuntimeOwner) {
        val current = owner
        check(current == null || current === candidate) { "Hans automation runtime already installed" }
        owner = candidate
    }

    fun currentOrNull(): AutomationRuntimeOwner? = owner

    @Synchronized
    internal fun clearForTests() {
        owner = null
    }
}

private class AutomationRuntimeDispatchException :
    IllegalStateException("automation_runtime_executor_rejected")

private class AutomationRuntimeCycleCancelledException :
    IllegalStateException("automation_runtime_cycle_cancelled")

private class SerialExecutor(
    private val delegate: Executor,
) {
    private data class PendingTask(
        val command: () -> Unit,
        val rejected: () -> Unit,
    )

    private val tasks = ArrayDeque<PendingTask>()
    private var active: PendingTask? = null

    fun execute(command: () -> Unit, rejected: () -> Unit) {
        val next = synchronized(this) {
            tasks.addLast(PendingTask(command, rejected))
            takeNextLocked()
        }
        next?.let(::dispatch)
    }

    private fun dispatch(task: PendingTask) {
        try {
            delegate.execute {
                try {
                    task.command()
                } finally {
                    val next = synchronized(this) {
                        if (active === task) active = null
                        takeNextLocked()
                    }
                    next?.let(::dispatch)
                }
            }
        } catch (_: RuntimeException) {
            val rejectedTasks = synchronized(this) {
                buildList {
                    if (active === task) {
                        active = null
                        add(task)
                    }
                    while (tasks.isNotEmpty()) add(tasks.removeFirst())
                }
            }
            rejectedTasks.forEach { rejectedTask -> runCatching { rejectedTask.rejected() } }
        }
    }

    private fun takeNextLocked(): PendingTask? {
        if (active != null || tasks.isEmpty()) return null
        return tasks.removeFirst().also { active = it }
    }
}
