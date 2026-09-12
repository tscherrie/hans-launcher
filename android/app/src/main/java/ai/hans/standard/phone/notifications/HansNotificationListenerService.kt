package ai.hans.standard.phone.notifications

import ai.hans.standard.notifications.AtomicFileNotificationTriageStorage
import ai.hans.standard.notifications.CodexAppServerRestrictedNotificationTriageExecutor
import ai.hans.standard.notifications.AndroidRootFreeNotificationEnrichmentFactory
import ai.hans.standard.notifications.AndroidNotificationPersonalMemoryContextFactory
import ai.hans.standard.notifications.HansNotificationExclusionPolicy
import ai.hans.standard.notifications.NotificationIngressResult
import ai.hans.standard.notifications.NotificationTriageIntegration
import ai.hans.standard.notifications.NotificationTriagePrivacyController
import ai.hans.standard.notifications.NotificationTriagePrivacyIntegration
import ai.hans.standard.notifications.NotificationTriageQueue
import ai.hans.standard.notifications.NotificationTriageQueueHealth
import ai.hans.standard.notifications.NotificationTriageRuntime
import ai.hans.standard.notifications.NotificationTriageTransactionCoordinator
import ai.hans.standard.notifications.NotificationSupersessionKey
import ai.hans.standard.phone.publicapi.ActiveNotificationReplyRegistry
import ai.hans.standard.phone.notifications.facts.AndroidNotificationFactArchiveFactory
import ai.hans.standard.phone.notifications.facts.NotificationFactRepository
import ai.hans.standard.notifications.NotificationFactMemoryProjection
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Root-free notification intake. Callbacks only enqueue work; normalization and SQLite writes
 * happen serially off the main thread. No notification content is logged or dispatched.
 */
class HansNotificationListenerService : NotificationListenerService() {
    private lateinit var worker: NotificationListenerWorkExecutor
    private lateinit var inbox: NotificationInboxStore
    private lateinit var privacyRepository: NotificationPrivacyRepository
    private lateinit var triageIngress: NotificationTriageIngress
    private lateinit var factArchive: NotificationFactRepository
    private lateinit var triageQueue: NotificationTriageQueue
    private lateinit var triageRuntime: NotificationTriageRuntime
    private lateinit var triageWakeupRegistration: Closeable
    private lateinit var triagePreemptionRegistration: Closeable
    private lateinit var triagePrivacyRegistration: Closeable
    private lateinit var connectionBaseline: NotificationConnectionBaselinePolicy
    private lateinit var privacyPurgeFence: NotificationPrivacyPurgeFence
    private val authoritativeSnapshotGate = NotificationAuthoritativeSnapshotGate()
    private val captureEpochGate = NotificationListenerCaptureEpochGate()
    private val destroying = AtomicBoolean(false)
    private val validatedDeliveryOutputStoppedWhileQuarantined = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        destroying.set(false)
        captureEpochGate.beginEpoch()
        privacyRepository = NotificationPrivacyRepository(applicationContext)
        privacyPurgeFence = AtomicNotificationPrivacyPurgeFence(applicationContext)
        // Construction is lazy: no database is opened on the Android main thread.
        factArchive = AndroidNotificationFactArchiveFactory.create(
            applicationContext, privacyRepository, privacyPurgeFence,
        )
        inbox = NotificationInboxStore(
            context = applicationContext,
            privacyRepository = privacyRepository,
            privacyPurgeFence = privacyPurgeFence,
        )
        triageQueue = NotificationTriageQueue(
            factArchive = factArchive,
            storage = AtomicFileNotificationTriageStorage(applicationContext),
            exclusionPolicy = HansNotificationExclusionPolicy(
                ownPackageNames = setOf(applicationContext.packageName),
                additionalExclusion = { packageName ->
                    privacyRepository.captureDecision(packageName) !=
                        NotificationCaptureDecision.Allowed
                },
            ),
        )
        triageIngress = NotificationTriageIngress(triageQueue)
        connectionBaseline = NotificationConnectionBaselinePolicy(
            AtomicFileNotificationConnectionBaselineStore(applicationContext),
        )
        triageRuntime = NotificationTriageRuntime(
            queue = triageQueue,
            restrictedExecutor = CodexAppServerRestrictedNotificationTriageExecutor(
                context = applicationContext,
                contextProvider = NotificationTriageIntegration.contextProvider(),
                enrichmentProvider = AndroidRootFreeNotificationEnrichmentFactory.create(
                    context = applicationContext,
                    inbox = inbox,
                ),
                personalMemoryContextProvider =
                    AndroidNotificationPersonalMemoryContextFactory.create(
                        applicationContext,
                        additionalUnverifiedCandidateSource = NotificationFactMemoryProjection(factArchive)::candidates,
                    ),
            ),
            suggestionSink = NotificationTriageIntegration.deliverySink(),
            processingPermit = {
                notificationProcessingPermitted(
                    authoritativeSnapshotReady = authoritativeSnapshotGate.isReady(),
                    interactiveIdle = NotificationTriageIntegration.isInteractiveIdle(),
                    privacyPurgeRequired = privacyPurgeFence.isRequired(),
                )
            },
        )
        triageWakeupRegistration = NotificationTriageIntegration.attachRuntimeWakeup(
            triageRuntime::requestDrain,
        )
        triagePreemptionRegistration = NotificationTriageIntegration.attachRuntimePreemption(
            triageRuntime::preemptForInteraction,
        )
        triagePrivacyRegistration = NotificationTriagePrivacyIntegration.attach(
            object : NotificationTriagePrivacyController {
                override fun clearAll(): Boolean {
                    triageRuntime.preemptCancelledWork()
                    ActiveNotificationReplyRegistry.processWide.onListenerConnected(emptyList())
                    // Queue delivery and clear share one monitor. Clear the queue first so a
                    // concurrently staged delivery either loses its lease or fully activates;
                    // the subsequent center purge then removes either outcome.
                    val queueCleared = triageQueue.clearAll()
                    val suggestionsCleared = NotificationTriageIntegration.clearValidatedSuggestions()
                    return queueCleared && suggestionsCleared
                }

                override fun purgeExcluded(): Boolean {
                    triageRuntime.preemptCancelledWork()
                    refreshActiveReplyRegistryForPrivacy()
                    val queuePurged = runCatching { triageQueue.purgeExcluded() }.isSuccess
                    // The validated store intentionally contains no package name, so a privacy
                    // exclusion conservatively clears every still-pending announcement.
                    val suggestionsCleared =
                        NotificationTriageIntegration.clearValidatedSuggestions()
                    return queuePurged && suggestionsCleared
                }
            },
        )
        worker = NotificationListenerWorkExecutor.create()
        quarantineAuthoritativeNotificationState()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        quarantineAuthoritativeNotificationState()
        captureEpochGate.beginEpoch()
        submit(requireAuthoritativeSnapshot = false) { captureLease ->
            reconcileActiveNotificationSnapshot(captureLease)
        }
    }

    override fun onNotificationPosted(
        statusBarNotification: StatusBarNotification,
        rankingMap: RankingMap,
    ) {
        val observedAt = System.currentTimeMillis()
        routePostedNotificationAroundSetupProbe(
            notification = statusBarNotification,
            ownPackageName = applicationContext.packageName,
            receiptTracker = NotificationSetupLiveTestReceiptTracker.processWide,
        ) {
            submit { captureLease ->
                if (!ensureCapturePolicyAvailable()) return@submit
                if (!capturePermittedOrQuarantine(statusBarNotification)) {
                    captureEpochGate.mutateIfCurrent(captureLease) {
                        ActiveNotificationReplyRegistry.processWide.onNotificationRemoved(
                            statusBarNotification.key,
                        )
                    }
                    return@submit
                }
                val signal = AndroidNotificationExtractor.upsert(
                    statusBarNotification = statusBarNotification,
                    observedAtEpochMillis = observedAt,
                )
                acceptAndQueue(signal, captureLease) {
                    ActiveNotificationReplyRegistry.processWide.onNotificationPosted(
                        statusBarNotification,
                    )
                }
            }
        }
    }

    override fun onNotificationRemoved(
        statusBarNotification: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int,
    ) {
        val observedAt = System.currentTimeMillis()
        routeRemovedNotificationAroundSetupProbe(
            notification = statusBarNotification,
            ownPackageName = applicationContext.packageName,
            receiptTracker = NotificationSetupLiveTestReceiptTracker.processWide,
        ) {
            submit { captureLease ->
                if (!ensureCapturePolicyAvailable()) return@submit
                val signal = AndroidNotificationExtractor.removed(
                    statusBarNotification = statusBarNotification,
                    observedAtEpochMillis = observedAt,
                    reason = reason,
                )
                val writeResult = acceptInboxOrQuarantine(signal, captureLease) {
                    ActiveNotificationReplyRegistry.processWide.onNotificationRemoved(
                        statusBarNotification.key,
                    )
                } ?: return@submit
                if (writeResult is NotificationWriteResult.ExcludedByPrivacy) {
                    enforceCaptureDecision(writeResult.decision)
                    return@submit
                }
                check(drainDurableTriageOutbox()) {
                    "notification_triage_outbox_replay_failed"
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        quarantineAuthoritativeNotificationState()
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            captureEpochGate.invalidateEpoch()
            ActiveNotificationReplyRegistry.processWide.onListenerDisconnected()
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        quarantineAuthoritativeNotificationState()
        // This boundary shares the exact durable Queue transaction monitor. A Queue writer that
        // already read state must finish before teardown becomes visible; no callback can begin a
        // later Queue write after this point.
        // Global lock order is Privacy -> Queue. Invalidating capture first prevents any stale
        // callback from mutating Inbox/ReplyRegistry; the following Queue barrier then waits for
        // an already-running replay without ever acquiring Privacy while holding Queue.
        runNotificationListenerTeardownBarrier(
            privacyLock = NotificationPrivacyMutationCoordinator.lock,
            queueLock = NotificationTriageTransactionCoordinator.lock,
            markDestroyingAndInvalidate = {
                destroying.set(true)
                captureEpochGate.invalidateEpoch()
                ActiveNotificationReplyRegistry.processWide.onListenerDisconnected()
            },
        )
        if (::triageWakeupRegistration.isInitialized) triageWakeupRegistration.close()
        if (::triagePreemptionRegistration.isInitialized) triagePreemptionRegistration.close()
        if (::triageRuntime.isInitialized) triageRuntime.close()
        val workerStopped = if (::worker.isInitialized) {
            worker.shutdownNow()
            runCatching {
                worker.awaitTermination(LISTENER_SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
        } else {
            true
        }
        // Keep the authoritative live Queue controller attached until both model delivery and
        // callback intake are quiescent. Fallback clears can only take over after this boundary.
        val runtimeStopped = !::triageRuntime.isInitialized || runCatching {
            triageRuntime.awaitQuiescence(0, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (workerStopped && runtimeStopped) {
            closeListenerPrivacyResources()
        } else {
            deferListenerPrivacyResourceCloseUntilWorkerStops()
        }
        super.onDestroy()
    }

    private fun acceptAndQueue(
        signal: NotificationSignal.Upsert,
        captureLease: NotificationListenerCaptureEpochGate.Lease,
        beforeAccept: () -> Unit,
    ) {
        val writeResult = acceptInboxOrQuarantine(signal, captureLease, beforeAccept) ?: return
        if (writeResult is NotificationWriteResult.ExcludedByPrivacy) {
            enforceCaptureDecision(writeResult.decision)
            return
        }
        if (
            writeResult is NotificationWriteResult.Stored &&
            connectionBaseline.shouldQueueLiveWrite(writeResult)
        ) {
            check(drainDurableTriageOutbox()) { "notification_triage_outbox_replay_failed" }
        } else if (writeResult is NotificationWriteResult.Stored) {
            // Initial baseline events intentionally stay private/inbox-only.
            check(inbox.acknowledgeTriageOutbox(writeResult.sequence))
        } else if (writeResult is NotificationWriteResult.Duplicate) {
            // A crash may have committed SQLite and its outbox before Queue/Center replay.
            check(drainDurableTriageOutbox()) { "notification_triage_outbox_replay_failed" }
        }
    }

    private fun acceptInboxOrQuarantine(
        signal: NotificationSignal,
        captureLease: NotificationListenerCaptureEpochGate.Lease,
        beforeAccept: () -> Unit = {},
    ): NotificationWriteResult? {
        val accepted = captureEpochGate.mutateIfCurrent(captureLease) {
            beforeAccept()
            runCatching { inbox.accept(signal) }
        } ?: return null
        return accepted.getOrElse {
            if (!privacyRepository.status().policyAvailable) {
                enforceCaptureDecision(NotificationCaptureDecision.PolicyUnavailable)
            } else {
                quarantineAuthoritativeNotificationState()
            }
            null
        }
    }

    private fun invalidatePendingSuggestion(packageName: String, androidKey: String): Boolean {
        val key = NotificationSupersessionKey.forSource(packageName, androidKey)
        return key.isNotBlank() &&
            NotificationTriageIntegration.invalidatePendingSuggestion(key)
    }

    private fun preemptCancelledTriageIfNeeded(result: NotificationIngressResult?) {
        if (
            result is NotificationIngressResult.CancelledRemovedNotification &&
            result.inFlightModelCancelled
        ) {
            triageRuntime.preemptCancelledWork()
        }
    }

    private fun submit(
        requireAuthoritativeSnapshot: Boolean = true,
        block: (NotificationListenerCaptureEpochGate.Lease) -> Unit,
    ) {
        if (!::worker.isInitialized || worker.isShutdown) return
        val captureLease = captureEpochGate.currentLease() ?: return
        val accepted = worker.executeOrRequestReconciliation(
            task = {
                if (requireAuthoritativeSnapshot) {
                    runNotificationCallbackWithAuthoritativeSnapshot(
                        gate = authoritativeSnapshotGate,
                        reconcile = { reconcileActiveNotificationSnapshot(captureLease) },
                        onFailure = ::quarantineAuthoritativeNotificationState,
                        callback = {
                            if (captureEpochGate.isCurrent(captureLease)) block(captureLease)
                        },
                    )
                } else {
                    // Deliberately swallow failures here: exception messages may contain SQLite
                    // data. Health telemetry can expose counters later, but never content.
                    runCatching {
                        if (captureEpochGate.isCurrent(captureLease)) block(captureLease)
                    }
                }
            },
            reconciliation = { reconcileAfterCallbackOverflow(captureLease) },
        )
        quarantineOnRejectedNotificationCallback(
            accepted = accepted,
            gate = authoritativeSnapshotGate,
            preempt = ::quarantineAuthoritativeNotificationState,
        )
    }

    private fun reconcileAfterCallbackOverflow(
        captureLease: NotificationListenerCaptureEpochGate.Lease,
    ) {
        if (!captureEpochGate.isCurrent(captureLease)) return
        quarantineAuthoritativeNotificationState()
        // Unknown active state keeps every durable queue item and retained Center stage hidden.
        // Do not delete the stage: a committed Queue receipt can only recover by activating that
        // exact idempotency key after a later authoritative snapshot succeeds.
        reconcileActiveNotificationSnapshot(captureLease)
    }

    /** Latest-state recovery after connect or callback-queue overflow; never treats null as empty. */
    private fun reconcileActiveNotificationSnapshot(
        captureLease: NotificationListenerCaptureEpochGate.Lease,
    ): Boolean =
        reconcileAuthoritativeNotificationSnapshot(
            gate = authoritativeSnapshotGate,
            source = ActiveNotificationSnapshotSource { activeNotifications?.toList() },
            reconcile = { active ->
                check(ensureCapturePolicyAvailable()) {
                    "notification_capture_policy_unavailable"
                }
                check(ensureNotificationDeliveryStateHealthyBeforeReady()) {
                    "notification_delivery_state_repair_failed"
                }
                check(drainDurableTriageOutbox()) {
                    "notification_triage_outbox_replay_failed"
                }
                val permittedActive = buildList {
                    active.forEach { notification ->
                        when (val decision = privacyRepository.captureDecision(
                            notification.packageName.orEmpty(),
                        )) {
                            NotificationCaptureDecision.Allowed -> add(notification)
                            NotificationCaptureDecision.PolicyUnavailable -> {
                                enforceCaptureDecision(decision)
                                error("notification_capture_policy_became_unavailable")
                            }
                            NotificationCaptureDecision.ProtectedPackage,
                            NotificationCaptureDecision.UserExcludedPackage,
                            -> Unit
                        }
                    }
                }
                val observedAt = System.currentTimeMillis()
                val normalized = permittedActive.mapNotNull { notification ->
                    runCatching {
                        AndroidNotificationExtractor.upsert(notification, observedAt)
                    }.getOrNull()
                }
                captureEpochGate.mutateIfCurrent(captureLease) {
                    ActiveNotificationReplyRegistry.processWide.onListenerConnected(permittedActive)
                    connectionBaseline.acceptSnapshot(
                        signals = normalized,
                        inboxWrite = { signal, queueForRestrictedTriage ->
                            val result =
                            inbox.accept(signal, queueForRestrictedTriage)
                            if (
                                result is NotificationWriteResult.ExcludedByPrivacy &&
                                result.decision == NotificationCaptureDecision.PolicyUnavailable
                            ) {
                                enforceCaptureDecision(result.decision)
                                error("notification_capture_policy_became_unavailable")
                            }
                            result
                        },
                        enqueueStored = { _, _ ->
                            check(drainDurableTriageOutbox()) {
                                "notification_triage_outbox_replay_failed"
                            }
                        },
                    )
                    inbox.reconcileActiveKeys(
                        activeAndroidKeys = normalized.mapTo(mutableSetOf()) { it.androidKey },
                        observedAtEpochMillis = observedAt,
                    )
                } ?: error("notification_listener_capture_lease_expired")
                check(drainDurableTriageOutbox()) {
                    "notification_triage_outbox_replay_failed"
                }
            },
            wake = triageRuntime::requestDrain,
            beforeReady = {
                captureEpochGate.isCurrent(captureLease) &&
                    !destroying.get() &&
                    prepareValidatedDeliveryAfterAuthoritativeSnapshot()
            },
        )

    private fun closeListenerPrivacyResources() {
        if (::triagePrivacyRegistration.isInitialized) triagePrivacyRegistration.close()
        if (::inbox.isInitialized) runCatching { inbox.close() }
        if (::factArchive.isInitialized) runCatching { factArchive.close() }
    }

    private fun deferListenerPrivacyResourceCloseUntilWorkerStops() {
        Thread(
            {
                val stopped = runCatching {
                    val intakeStopped = !::worker.isInitialized ||
                        worker.awaitTermination(DEFERRED_LISTENER_SHUTDOWN_DAYS, TimeUnit.DAYS)
                    val runtimeStopped = !::triageRuntime.isInitialized ||
                        triageRuntime.awaitQuiescence(DEFERRED_LISTENER_SHUTDOWN_DAYS, TimeUnit.DAYS)
                    intakeStopped && runtimeStopped
                }.getOrDefault(false)
                if (stopped) closeListenerPrivacyResources()
            },
            "hans-notification-listener-cleanup",
        ).apply { isDaemon = true }.start()
    }

    /**
     * Central unknown-state boundary: hide Center/context synchronously, revoke any host
     * correlation and physically stop notification TTS before other callbacks can proceed.
     */
    private fun quarantineAuthoritativeNotificationState() {
        authoritativeSnapshotGate.quarantine()
        if (::triageRuntime.isInitialized) triageRuntime.preemptCancelledWork()
        val stopped = NotificationTriageIntegration.quarantineValidatedDelivery()
        validatedDeliveryOutputStoppedWhileQuarantined.set(stopped)
    }

    private fun prepareValidatedDeliveryAfterAuthoritativeSnapshot(): Boolean {
        if (!validatedDeliveryOutputStoppedWhileQuarantined.get()) {
            val stopped = NotificationTriageIntegration.quarantineValidatedDelivery()
            validatedDeliveryOutputStoppedWhileQuarantined.set(stopped)
        }
        if (!validatedDeliveryOutputStoppedWhileQuarantined.get()) return false
        return NotificationTriageIntegration.markValidatedDeliveryReady()
    }

    /** Queue and Center are one derived delivery unit; repair or expose neither of them. */
    private fun ensureNotificationDeliveryStateHealthyBeforeReady(): Boolean =
        ensureNotificationQueueAndCenterHealthyBeforeReady(
            queueHealth = triageQueue::health,
            privacyFence = privacyPurgeFence,
            preempt = triageRuntime::preemptCancelledWork,
            repairQueueToVerifiedEmpty = triageQueue::repairToVerifiedEmpty,
            clearValidatedCenter = NotificationTriageIntegration::clearValidatedSuggestions,
        )

    private fun triageAfterInboxWriteUnlessDestroying(
        signal: NotificationSignal,
        writeResult: NotificationWriteResult,
    ): NotificationIngressResult? = synchronized(NotificationTriageTransactionCoordinator.lock) {
        if (destroying.get()) return@synchronized null
        triageIngress.afterInboxWrite(signal, writeResult)
    }

    /** Replays the SQLite-atomic Inbox -> Queue/Center handoff before READY can be published. */
    private fun drainDurableTriageOutbox(): Boolean {
        repeat(MAX_TRIAGE_OUTBOX_DRAIN_BATCHES) {
            val outcome = drainNotificationOutboxBatch(
                lock = NotificationPrivacyMutationCoordinator.lock,
                pending = inbox::pendingTriageOutbox,
                replay = { event ->
                    replayDurableNotificationOutboxEvent(
                        event = event,
                        applyIngress = ::triageAfterInboxWriteUnlessDestroying,
                        invalidate = ::invalidatePendingSuggestion,
                        preempt = triageRuntime::preemptCancelledWork,
                    )
                },
                acknowledge = inbox::acknowledgeTriageOutbox,
                onReplayed = triageRuntime::requestDrain,
            )
            when (outcome) {
                OutboxDrainBatch.EMPTY -> return true
                OutboxDrainBatch.FAILED -> return false
                OutboxDrainBatch.REPLAYED -> Unit
            }
        }
        return inbox.pendingTriageOutbox(limit = 1).isEmpty()
    }

    private fun capturePermittedOrQuarantine(notification: StatusBarNotification): Boolean =
        enforceCaptureDecision(
            privacyRepository.captureDecision(notification.packageName.orEmpty()),
        )

    private fun enforceCaptureDecision(decision: NotificationCaptureDecision): Boolean =
        enforceNotificationCaptureDecision(
            decision = decision,
            gate = authoritativeSnapshotGate,
            purgeFence = privacyPurgeFence,
            preempt = triageRuntime::preemptCancelledWork,
            purge = {
                ActiveNotificationReplyRegistry.processWide.onListenerConnected(emptyList())
                inbox.clearHistory().triageQueueCleared
            },
        )

    /** Corrupt/unreadable policy is a global privacy fence, never merely one excluded callback. */
    private fun ensureCapturePolicyAvailable(): Boolean =
        enforceNotificationCapturePolicyAvailability(
            policyAvailable = privacyRepository.status().policyAvailable,
            gate = authoritativeSnapshotGate,
            purgeFence = privacyPurgeFence,
            preempt = triageRuntime::preemptCancelledWork,
            purge = {
                ActiveNotificationReplyRegistry.processWide.onListenerConnected(emptyList())
                inbox.clearHistory().triageQueueCleared
            },
        )

    private fun refreshActiveReplyRegistryForPrivacy() {
        val captureLease = captureEpochGate.currentLease() ?: return
        val permitted = readRequiredActiveNotificationSnapshot(
            ActiveNotificationSnapshotSource { activeNotifications?.toList() },
        )?.filter(::capturePermittedOrQuarantine) ?: emptyList()
        captureEpochGate.mutateIfCurrent(captureLease) {
            ActiveNotificationReplyRegistry.processWide.onListenerConnected(permitted)
        }
    }

    private companion object {
        const val LISTENER_SHUTDOWN_TIMEOUT_MILLIS = 5_000L
        const val DEFERRED_LISTENER_SHUTDOWN_DAYS = 365L
        const val MAX_TRIAGE_OUTBOX_DRAIN_BATCHES = 32
    }

}

/** Enforces the sole cross-domain lock order used by callback replay and listener teardown. */
internal fun runNotificationListenerTeardownBarrier(
    privacyLock: Any,
    queueLock: Any,
    markDestroyingAndInvalidate: () -> Unit,
) {
    synchronized(privacyLock) { markDestroyingAndInvalidate() }
    synchronized(queueLock) { /* wait for any pre-invalidation Queue mutation */ }
}

/**
 * Instance-bound capture authority. A callback keeps the exact opaque lease that existed when it
 * was accepted by the listener. Teardown invalidates that lease under the same process-wide lock
 * used by every Inbox mutation, so a delayed old callback can never write after privacy clear.
 */
internal class NotificationListenerCaptureEpochGate(
    private val lock: Any = NotificationPrivacyMutationCoordinator.lock,
) {
    internal class Lease internal constructor(internal val token: Any)

    private var activeToken: Any? = null

    fun beginEpoch(): Lease = synchronized(lock) {
        Lease(Any()).also { lease -> activeToken = lease.token }
    }

    fun currentLease(): Lease? = synchronized(lock) {
        activeToken?.let(::Lease)
    }

    fun invalidateEpoch() = synchronized(lock) {
        activeToken = null
    }

    fun isCurrent(lease: Lease): Boolean = synchronized(lock) {
        activeToken === lease.token
    }

    fun <T : Any> mutateIfCurrent(lease: Lease, mutation: () -> T): T? = synchronized(lock) {
        if (activeToken !== lease.token) return@synchronized null
        mutation()
    }
}

internal enum class OutboxDrainBatch {
    EMPTY,
    FAILED,
    REPLAYED,
}

/**
 * One linearizable SQLite-outbox -> Queue/Center -> ACK transaction. The optional wake is
 * outside this helper's transaction; a snapshot-reconciliation caller may still own the same
 * reentrant monitor. Runtime wake/preemption must therefore never reverse the data-lock order.
 */
internal fun drainNotificationOutboxBatch(
    lock: Any,
    pending: () -> List<NotificationInboxEvent>,
    replay: (NotificationInboxEvent) -> Boolean,
    acknowledge: (Long) -> Boolean,
    onReplayed: () -> Unit = {},
): OutboxDrainBatch {
    val outcome = synchronized(lock) {
        val events = pending()
        if (events.isEmpty()) return@synchronized OutboxDrainBatch.EMPTY
        events.forEach { event ->
            if (!replay(event) || !acknowledge(event.sequence)) {
                return@synchronized OutboxDrainBatch.FAILED
            }
        }
        OutboxDrainBatch.REPLAYED
    }
    if (outcome == OutboxDrainBatch.REPLAYED) onReplayed()
    return outcome
}

/** One idempotent crash-replay step; acknowledgement remains a separate durable store write. */
internal fun replayDurableNotificationOutboxEvent(
    event: NotificationInboxEvent,
    applyIngress: (NotificationSignal, NotificationWriteResult) -> NotificationIngressResult?,
    invalidate: (packageName: String, androidKey: String) -> Boolean,
    preempt: () -> Unit,
): Boolean {
    val signal: NotificationSignal = if (event.kind == NotificationEventKind.REMOVED) {
        NotificationSignal.Removed(
            packageName = event.snapshot.packageName,
            androidKey = event.snapshot.androidKey,
            observedAtEpochMillis = event.observedAtEpochMillis,
            reason = event.removalReason,
        )
    } else {
        NotificationSignal.Upsert(event.snapshot, event.observedAtEpochMillis)
    }
    val result = applyIngress(
        signal,
        NotificationWriteResult.Stored(event.sequence, event.kind),
    ) ?: return false
    if (
        result is NotificationIngressResult.Queued && result.inFlightModelSuperseded ||
        result is NotificationIngressResult.CancelledRemovedNotification &&
        result.inFlightModelCancelled
    ) {
        preempt()
    }
    // Queue cancellation/supersession is not enough: ACK is legal only after the validated
    // Center and any physically playing stale audio confirmed their revocation as well.
    if (!invalidate(signal.packageName, signal.androidKey)) return false
    return true
}

/** Durable work is never processed until Android has supplied a successful active snapshot. */
internal class NotificationAuthoritativeSnapshotGate {
    internal class ReconciliationLease internal constructor(val generation: Long)

    private var generation = 0L
    private var readyGeneration: Long? = null

    @Synchronized
    fun isReady(): Boolean = readyGeneration == generation

    /** Invalidates every reconcile that began before this exact unknown-state boundary. */
    @Synchronized
    fun quarantine() {
        generation = if (generation == Long.MAX_VALUE) 0L else generation + 1L
        readyGeneration = null
    }

    @Synchronized
    fun beginReconciliation(): ReconciliationLease {
        quarantine()
        return ReconciliationLease(generation)
    }

    /** Test/runtime convenience for a caller that owns the current generation synchronously. */
    @Synchronized
    fun markReady() {
        readyGeneration = generation
    }

    /**
     * Publishes both downstream delivery authority and this gate while the generation lock is
     * held. A disconnect/destroy quarantine therefore happens wholly before or wholly after the
     * commit; an older blocked reconcile can never reopen READY.
     */
    @Synchronized
    fun commitReady(
        lease: ReconciliationLease,
        publishDownstreamReady: () -> Boolean,
    ): Boolean {
        if (lease.generation != generation || readyGeneration != null) return false
        if (!publishDownstreamReady()) return false
        if (lease.generation != generation) return false
        readyGeneration = generation
        return true
    }

    @Synchronized
    fun keepQuarantinedIfCurrent(lease: ReconciliationLease) {
        if (lease.generation == generation) readyGeneration = null
    }
}

/**
 * Strict destructive recovery for an unreadable Queue or an interrupted shared privacy purge.
 * The process-wide transaction lock prevents capture/outbox writers from overtaking the repair.
 */
internal fun ensureNotificationQueueAndCenterHealthyBeforeReady(
    queueHealth: () -> NotificationTriageQueueHealth,
    privacyFence: NotificationPrivacyPurgeFence,
    preempt: () -> Unit,
    repairQueueToVerifiedEmpty: () -> Boolean,
    clearValidatedCenter: () -> Boolean,
): Boolean = synchronized(NotificationTriageTransactionCoordinator.lock) {
    val initialHealth = runCatching(queueHealth).getOrNull()
    if (
        initialHealth is NotificationTriageQueueHealth.Available &&
        !privacyFence.isRequired()
    ) return@synchronized true

    val marked = privacyFence.markRequired() && privacyFence.isRequired()
    if (!marked) return@synchronized false
    runCatching(preempt)
    val queueRepaired = runCatching(repairQueueToVerifiedEmpty).getOrDefault(false)
    val centerCleared = runCatching(clearValidatedCenter).getOrDefault(false)
    val repairedHealth = runCatching(queueHealth).getOrNull()
    // Repair empties transient delivery only; a valid fact outbox and capacity counters survive.
    val verifiedQueueEmpty = repairedHealth is NotificationTriageQueueHealth.Available &&
        repairedHealth.recordCount == 0
    if (!queueRepaired || !centerCleared || !verifiedQueueEmpty) return@synchronized false
    privacyFence.markClean() && !privacyFence.isRequired()
}

/** Raw queue content never reaches either restricted model while a durable privacy purge is due. */
internal fun notificationProcessingPermitted(
    authoritativeSnapshotReady: Boolean,
    interactiveIdle: Boolean,
    privacyPurgeRequired: Boolean,
): Boolean = authoritativeSnapshotReady && interactiveIdle && !privacyPurgeRequired

internal class NotificationListenerWorkExecutor private constructor() : ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_QUEUED_CALLBACKS),
        { runnable ->
            Thread(runnable, "hans-notification-inbox").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
) {
    private val reconciliationRequested = AtomicBoolean(false)
    private val reconciliation = AtomicReference<(() -> Unit)?>(null)

    fun executeOrRequestReconciliation(
        task: () -> Unit,
        reconciliation: () -> Unit,
    ): Boolean {
        return try {
            execute(task)
            true
        } catch (_: RejectedExecutionException) {
            if (!isShutdown) {
                this.reconciliation.set(reconciliation)
                reconciliationRequested.set(true)
            }
            false
        }
    }

    override fun afterExecute(runnable: Runnable?, failure: Throwable?) {
        super.afterExecute(runnable, failure)
        if (queue.isNotEmpty() || !reconciliationRequested.compareAndSet(true, false)) return
        val recovery = reconciliation.getAndSet(null) ?: return
        runCatching(recovery)
    }

    companion object {
        const val MAX_QUEUED_CALLBACKS = 128
        fun create() = NotificationListenerWorkExecutor()
    }
}

internal fun interface ActiveNotificationSnapshotSource<T> {
    fun read(): List<T>?
}

/** Null and exceptions mean "snapshot unavailable", never an authoritative empty inbox. */
internal fun <T> readRequiredActiveNotificationSnapshot(
    source: ActiveNotificationSnapshotSource<T>,
): List<T>? = runCatching(source::read).getOrNull()?.toList()

/** Cancellation/reconciliation completes before READY publication and the one exact wake. */
internal fun <T> reconcileAuthoritativeNotificationSnapshot(
    gate: NotificationAuthoritativeSnapshotGate,
    source: ActiveNotificationSnapshotSource<T>,
    reconcile: (List<T>) -> Unit,
    wake: () -> Unit,
    beforeReady: () -> Boolean = { true },
): Boolean {
    val lease = gate.beginReconciliation()
    val active = readRequiredActiveNotificationSnapshot(source) ?: return false
    return runCatching {
        reconcile(active)
        if (!gate.commitReady(lease, beforeReady)) return@runCatching false
        wake()
        true
    }.getOrElse {
        gate.keepQuarantinedIfCurrent(lease)
        false
    }.also { completed ->
        if (!completed) gate.keepQuarantinedIfCurrent(lease)
    }
}

/** A dropped callback makes Android's latest state unknown immediately, not after backlog drain. */
internal fun quarantineOnRejectedNotificationCallback(
    accepted: Boolean,
    gate: NotificationAuthoritativeSnapshotGate,
    preempt: () -> Unit,
) {
    if (accepted) return
    gate.quarantine()
    preempt()
}

/**
 * A later Android callback is also a bounded, event-driven recovery opportunity after a transient
 * binder snapshot failure. The callback itself runs only after the successful latest-state
 * reconcile has cancelled stale durable work and published READY.
 */
internal fun runNotificationCallbackWithAuthoritativeSnapshot(
    gate: NotificationAuthoritativeSnapshotGate,
    reconcile: () -> Boolean,
    onFailure: () -> Unit = {},
    callback: () -> Unit,
): Boolean {
    if (!gate.isReady() && !reconcile()) return false
    return runCatching {
        callback()
        true
    }.getOrElse {
        gate.quarantine()
        onFailure()
        false
    }
}


internal fun enforceNotificationCapturePolicyAvailability(
    policyAvailable: Boolean,
    gate: NotificationAuthoritativeSnapshotGate,
    purgeFence: NotificationPrivacyPurgeFence,
    preempt: () -> Unit,
    purge: () -> Boolean,
): Boolean {
    if (!policyAvailable) {
        gate.quarantine()
        preempt()
        // Keep REQUIRED while policy is unreadable even when this first purge succeeds. Repair
        // must prove one complete Queue+Center purge again before READY can be published.
        purgeFence.markRequired()
        runCatching(purge)
        purgeFence.markRequired()
        return false
    }
    if (!purgeFence.isRequired()) return true
    gate.quarantine()
    preempt()
    if (runCatching(purge).getOrDefault(false) != true) return false
    return purgeFence.markClean()
}

/** A second policy read can fail after status() succeeded; never collapse that into exclusion. */
internal fun enforceNotificationCaptureDecision(
    decision: NotificationCaptureDecision,
    gate: NotificationAuthoritativeSnapshotGate,
    purgeFence: NotificationPrivacyPurgeFence,
    preempt: () -> Unit,
    purge: () -> Boolean,
): Boolean = when (decision) {
    NotificationCaptureDecision.Allowed -> true
    NotificationCaptureDecision.ProtectedPackage,
    NotificationCaptureDecision.UserExcludedPackage,
    -> false
    NotificationCaptureDecision.PolicyUnavailable -> {
        enforceNotificationCapturePolicyAvailability(
            policyAvailable = false,
            gate = gate,
            purgeFence = purgeFence,
            preempt = preempt,
            purge = purge,
        )
        false
    }
}
