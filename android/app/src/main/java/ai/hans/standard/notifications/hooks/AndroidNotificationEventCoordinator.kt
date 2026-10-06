package ai.hans.standard.notifications.hooks

import ai.hans.standard.HansApplication
import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.integration.AndroidCodexSessionHost
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.CodexClientObserver
import ai.hans.standard.integration.NativeNotificationDispatchReceipt
import ai.hans.standard.integration.NativeNotificationDispatchResult
import ai.hans.standard.integration.NativeNotificationExternalMessage
import ai.hans.standard.integration.NativeNotificationSpeechAdmission
import ai.hans.standard.notifications.NotificationTriageIntegration
import ai.hans.standard.phone.notifications.AtomicNotificationPrivacyPurgeFence
import ai.hans.standard.phone.notifications.NotificationCaptureDecision
import ai.hans.standard.phone.notifications.NotificationInboxEvent
import ai.hans.standard.phone.notifications.NotificationEventKind
import ai.hans.standard.phone.notifications.NotificationPrivacyMutationCoordinator
import ai.hans.standard.phone.notifications.NotificationPrivacyRepository
import ai.hans.standard.phone.publicapi.ActiveNotificationReplyRegistry
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Event-driven durable push -> same main Hans context bridge. There is no classification model,
 * secondary conversational context, relevance policy, timer or wake-on-streamed-token loop.
 */
internal class AndroidNotificationEventCoordinator(
    private val app: HansApplication,
    val ledger: NotificationEventLedger = NotificationEventLedger(AtomicFileNotificationEventStorage(app)),
) {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-notification-events").apply { isDaemon = true }
    }
    private val wakeQueued = AtomicBoolean(false)
    private val observers = CopyOnWriteArraySet<(NotificationEventStatus) -> Unit>()
    private val retryGate = NotificationEventRetryGate()
    private val privacy by lazy { NotificationPrivacyRepository(app) }
    @Volatile private var host: AndroidCodexSessionHost? = null
    @Volatile private var sourceReady: () -> Boolean = { false }
    @Volatile private var waitReason: String? = "source_not_ready"
    private var observedControlKey: String? = null
    private var observedWakeKey: String? = null
    private var recoveryRequestedKey: String? = null
    private var lastDiagnosticSourceSequence = 0L
    private var sourceCommitCount = 0L
    private var sourceRemovalCount = 0L

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) externalWake()
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        NotificationTriageIntegration.attachNotificationEventWakeup(::externalWake)
        app.internetConnectivity.observe { externalWake() }
    }

    fun activateAfter(maxExistingSequence: Long, sourceEvent: (Long) -> NotificationInboxEvent? = { null }): Boolean = synchronized(NotificationPrivacyMutationCoordinator.lock) {
        ledger.activateAfter(maxExistingSequence, sourceEvent).also { if (it) synchronized(this) {
            lastDiagnosticSourceSequence = maxOf(lastDiagnosticSourceSequence, maxExistingSequence)
        } }
    }

    fun accept(event: NotificationInboxEvent): Boolean {
        val accepted = synchronized(NotificationPrivacyMutationCoordinator.lock) {
            if (event.kind == NotificationEventKind.REMOVED) mutateAndConfirmOutputStop(
                NativeNotificationSpeechAdmission.Source(event.snapshot.packageName, event.snapshot.androidKey)) { ledger.accept(event) }
            else ledger.accept(event)
        }
        if (accepted) synchronized(this) {
            if (event.sequence > lastDiagnosticSourceSequence) {
                sourceCommitCount++
                if (event.kind == NotificationEventKind.REMOVED) sourceRemovalCount++
                lastDiagnosticSourceSequence = event.sequence
            }
        }
        if (accepted) externalWake() else publishStatus()
        return accepted
    }

    fun attachAuthoritativeSource(ready: () -> Boolean) {
        sourceReady = ready
        externalWake()
    }

    fun attachSourceReady(ready: () -> Boolean) = attachAuthoritativeSource(ready)

    fun attach(sessionHost: AndroidCodexSessionHost) {
        if (host === sessionHost) return
        check(host == null) { "notification_event_host_already_attached" }
        host = sessionHost
        sessionHost.addObserver(CodexClientObserver { snapshot ->
            val control = "${snapshot.runtimePhase}/${snapshot.sessionPhase}/${snapshot.generation}/" +
                "${snapshot.session.account.phase}/${snapshot.session.currentThreadId}/${snapshot.migrationReadiness.activeTurn}"
            val wake = buildString {
                append(control).append('/').append(snapshot.agentChannelHistoryRevision)
                snapshot.terminalTurns.takeLast(12).forEach { append('/').append(it.threadId)
                    .append(':').append(it.turnId).append(':').append(it.status) }
            }
            val changed = synchronized(this) {
                if (observedControlKey != control) {
                    observedControlKey = control
                    // Receipt/history revisions do NOT clear this latch. A rejected request only
                    // retries on a genuine capture, connection, unlock or runtime control event.
                    retryGate.externalWake()
                    recoveryRequestedKey = null
                }
                (observedWakeKey != wake).also { if (it) observedWakeKey = wake }
            }
            if (changed) requestDrain()
        })
        externalWake()
    }

    fun status(): NotificationEventStatus = ledger.status(waitReason).copy(outcomes =
        (host?.notificationHookOutcomeDiagnostics() ?: NotificationHookOutcomeSnapshot()).let { outcome ->
            synchronized(this) { outcome.copy(sourceCommitCount = sourceCommitCount, sourceRemovalCount = sourceRemovalCount) }
        })

    /** No classifier: this verifies the captured source still has its original privacy lease. */
    fun isReportSourceCurrent(eventId: String, threadId: String,
        source: NativeNotificationSpeechAdmission.Source): Boolean = synchronized(NotificationPrivacyMutationCoordinator.lock) {
        sourceReady() && !AtomicNotificationPrivacyPurgeFence(app).isRequired() &&
            privacy.captureDecision(source.packageName) == NotificationCaptureDecision.Allowed &&
            ActiveNotificationReplyRegistry.processWide.isAvailable() &&
            ledger.hasLiveReportSource(eventId, threadId, source.packageName, source.androidKey)
    }

    fun commitReport(eventId: String, threadId: String, turnId: String,
        source: NativeNotificationSpeechAdmission.Source, report: NotificationEventReport): NotificationEventReport? =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            if (!isReportSourceCurrent(eventId, threadId, source)) null
            else ledger.commitReport(eventId, threadId, turnId, report)
        }

    fun persistedReport(eventId: String, threadId: String, turnId: String): NotificationEventReport? =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            val source = ledger.reportSourceFor(eventId, threadId, turnId)
            if (source == null || !isReportSourceCurrent(eventId, threadId,
                    NativeNotificationSpeechAdmission.Source(source.first, source.second))) null
            else ledger.reportFor(eventId, threadId, turnId)
        }

    fun persistedReports(threadId: String?): List<NotificationEventReport> =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            if (AtomicNotificationPrivacyPurgeFence(app).isRequired()) emptyList()
            else ledger.reports(threadId) { privacy.captureDecision(it) == NotificationCaptureDecision.Allowed }
        }

    fun observeStatus(observer: (NotificationEventStatus) -> Unit): Closeable {
        observers.add(observer)
        worker.execute { if (observer in observers) runCatching { observer(status()) } }
        return Closeable { observers.remove(observer) }
    }

    fun externalWake() {
        retryGate.externalWake()
        requestDrain()
    }

    fun requestDrain() {
        if (!wakeQueued.compareAndSet(false, true)) return
        worker.execute {
            wakeQueued.set(false)
            runCatching { drain() }.onFailure { ledger.reportFailure("coordinator_failed") }
            publishStatus()
        }
    }

    fun remove(packageName: String, androidKey: String): Boolean = synchronized(NotificationPrivacyMutationCoordinator.lock) {
        mutateAndConfirmOutputStop(NativeNotificationSpeechAdmission.Source(packageName, androidKey)) {
            ledger.remove(packageName, androidKey)
        }.also { requestDrain() }
    }

    fun clearPrivateData(): Boolean = synchronized(NotificationPrivacyMutationCoordinator.lock) {
        mutateAndConfirmOutputStop { ledger.clearPrivateData() }.also { requestDrain() }
    }

    fun purgeExcluded(): Boolean = synchronized(NotificationPrivacyMutationCoordinator.lock) {
        mutateAndConfirmOutputStop {
            ledger.purgeExcluded { privacy.captureDecision(it) == NotificationCaptureDecision.Allowed }
        }.also { requestDrain() }
    }

    private fun drain() {
        val initial = ledger.status()
        if (!initial.available || !initial.activated) { waitReason = initial.failureCode ?: "activation_required"; return }
        val pending = ledger.pending().firstOrNull()
        val currentHost = host ?: run {
            val recovery = ledger.unsettled().firstOrNull { it.correlation != null }
            val needsRuntime = pending ?: recovery
            if (needsRuntime != null && intakeWaitReason(needsRuntime.packageName) == null) {
                waitReason = "runtime_starting"
                app.sessionHost
            } else waitReason = if (needsRuntime == null) "idle" else intakeWaitReason(needsRuntime.packageName)
            return
        }
        val snapshot = currentHost.snapshot()
        if (!NotificationEventRuntimeAdmission.allows(snapshot?.runtimePhase, snapshot?.sessionPhase,
                snapshot?.session?.account?.phase)) {
            waitReason = "runtime_not_ready"; return
        }
        val readySnapshot = snapshot ?: return
        val threadId = readySnapshot.session.currentThreadId ?: run { waitReason = "thread_not_ready"; return }
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            NotificationEventReceiptReconciler(ledger).reconcile(currentHost.notificationExternalHistory(),
                readySnapshot.terminalTurns.map { NotificationEventTerminalProof(it.threadId, it.turnId, it.status) })
        }
        if (ledger.unsettled().any { it.correlation?.threadId == threadId }) {
            val key = "$threadId/${readySnapshot.generation}"
            val refresh = synchronized(this) {
                (recoveryRequestedKey != key).also { if (it) recoveryRequestedKey = key }
            }
            if (refresh) currentHost.refreshNotificationExternalHistory(threadId)
        }
        val next = ledger.pending().firstOrNull() ?: run { waitReason = "idle"; return }
        if (!retryGate.mayAttempt(next.eventId)) { waitReason = "awaiting_external_wake"; return }
        intakeWaitReason(next.packageName)?.let { waitReason = it; return }
        var result: NativeNotificationDispatchResult = NativeNotificationDispatchResult.RejectedBeforeTransport
        var claimed: NotificationEventRecord? = null
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            if (intakeWaitReason(next.packageName) != null) return@synchronized
            val claim = ledger.claim(next.eventId, threadId) ?: return@synchronized
            claimed = claim
            result = runCatching {
                currentHost.dispatchNotificationEvent(
                    NativeNotificationExternalMessage(claim.eventId, claim.payloadJson, claim.payloadSha256),
                    threadId,
                    source = NativeNotificationSpeechAdmission.Source(claim.packageName, claim.androidKey),
                    beforeTransport = { intakeWaitReason(claim.packageName) == null && ledger.mayTransmit(claim) },
                    onReceipt = { receipt ->
                        // Never acquire the privacy/ledger lock in a synchronous Host callback.
                        worker.execute {
                            synchronized(NotificationPrivacyMutationCoordinator.lock) {
                                when (receipt) {
                                    is NativeNotificationDispatchReceipt.Accepted -> {
                                        ledger.accepted(claim, receipt.threadId, receipt.turnId)
                                        // Native acceptance is a real free-slot signal even if the
                                        // main turn remains BUSY; do not wait for overall task end.
                                        retryGate.runtimeReceiptAccepted()
                                    }
                                    NativeNotificationDispatchReceipt.RejectedByRuntime,
                                    NativeNotificationDispatchReceipt.OutcomeAmbiguous -> ledger.markUncertain(claim)
                                }
                            }
                            requestDrain()
                        }
                    },
                )
            }.getOrDefault(NativeNotificationDispatchResult.TransportOutcomeAmbiguous)
            when (val sent = result) {
                is NativeNotificationDispatchResult.Submitted -> {
                    if (sent.threadId == threadId && sent.eventId == claim.eventId) ledger.markSubmitted(claim)
                    else ledger.markUncertain(claim)
                }
                NativeNotificationDispatchResult.RejectedBeforeTransport -> {
                    ledger.releaseUnsent(claim)
                    retryGate.rejectedBeforeTransport(claim.eventId)
                }
                NativeNotificationDispatchResult.TransportOutcomeAmbiguous -> ledger.markUncertain(claim)
            }
        }
        waitReason = if (claimed == null) "intake_changed" else when (result) {
            is NativeNotificationDispatchResult.Submitted -> "awaiting_receipt"
            NativeNotificationDispatchResult.RejectedBeforeTransport -> "awaiting_external_wake"
            NativeNotificationDispatchResult.TransportOutcomeAmbiguous -> "transport_uncertain"
        }
        // Multiple new events may join the active ordinary turn. No model/settlement roundtrip is
        // required between them, but definite rejections never self-schedule a retry loop.
        if (result is NativeNotificationDispatchResult.Submitted && ledger.pending().isNotEmpty()) requestDrain()
    }

    private fun intakeWaitReason(packageName: String): String? = when {
        !sourceReady() -> "source_not_ready"
        AtomicNotificationPrivacyPurgeFence(app).isRequired() -> "privacy_purge_required"
        privacy.captureDecision(packageName) != NotificationCaptureDecision.Allowed -> "capture_not_allowed"
        !ActiveNotificationReplyRegistry.processWide.isAvailable() -> "source_not_ready"
        !app.internetConnectivity.snapshot().status.permitsExplicitRequest -> "network_not_ready"
        app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false -> "phone_locked"
        !NotificationTriageIntegration.isNotificationEventIntakeAllowed() -> "voice_busy"
        else -> null
    }

    private fun publishStatus() {
        val current = status()
        observers.forEach { runCatching { it(current) } }
    }

    private fun mutateAndConfirmOutputStop(source: NativeNotificationSpeechAdmission.Source? = null,
        mutation: () -> Boolean): Boolean =
        NotificationEventPrivacyBarrier.apply(mutation,
            confirmOutputStop = {
                if (source == null) host?.cancelNativeNotificationSpeechAndAwait() != false
                else host?.cancelNativeNotificationSourceSpeechAndAwait(source) != false
            },
            reportFailure = ledger::reportFailure)

}
