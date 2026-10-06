package ai.hans.standard.integration

import ai.hans.standard.HansApplication
import ai.hans.standard.notifications.NotificationTriageIntegration
import ai.hans.standard.notifications.agentchannel.AgentChannelStatus
import ai.hans.standard.notifications.agentchannel.WhatsAppAgentChannel
import ai.hans.standard.notifications.agentchannel.WhatsAppNotificationSource
import ai.hans.standard.phone.notifications.NotificationCaptureDecision
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
import java.util.concurrent.atomic.AtomicReference

/** Event-driven bridge. It does not expose a new model-callable channel or enroll on model input. */
internal class AndroidWhatsAppAgentChannelCoordinator(
    private val app: HansApplication,
    val channel: WhatsAppAgentChannel,
) {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-whatsapp-agent-inbox").apply { isDaemon = true }
    }
    private val wakeQueued = AtomicBoolean(false)
    private val observers = CopyOnWriteArraySet<(AgentChannelStatus) -> Unit>()
    @Volatile private var host: AndroidCodexSessionHost? = null
    @Volatile private var authoritativeSourceReady: () -> Boolean = { false }
    private var observedWakeKey: String? = null
    private var observedControlKey: String? = null
    private val deferredUnsentId = AtomicReference<String?>(null)
    private val privacy by lazy { NotificationPrivacyRepository(app) }

    init {
        // A locked phone waits for real user presence, never a timer or an unlock attempt.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) externalWake()
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        NotificationTriageIntegration.attachIdleWakeup(::externalWake)
        app.internetConnectivity.observe { externalWake() }
    }

    fun observeStatus(observer: (AgentChannelStatus) -> Unit): Closeable {
        observers.add(observer)
        worker.execute { if (observer in observers) observer(channel.status()) }
        return Closeable { observers.remove(observer) }
    }

    fun attachAuthoritativeSource(ready: () -> Boolean) {
        authoritativeSourceReady = ready
        requestDrain()
    }

    /** Called only after the listener accepted this notification under its current privacy lease. */
    fun accept(source: WhatsAppNotificationSource): Boolean {
        val result = channel.observe(source)
        externalWake()
        return result.failureCode == null
    }

    fun attach(sessionHost: AndroidCodexSessionHost) {
        if (host === sessionHost) return
        check(host == null) { "agent_channel_host_already_attached" }
        host = sessionHost
        sessionHost.addObserver(CodexClientObserver { snapshot ->
            val controlKey = "${snapshot.runtimePhase}/${snapshot.sessionPhase}/${snapshot.session.account.phase}/" +
                "${snapshot.session.currentThreadId}/${snapshot.agentChannelHistoryRevision}"
            // Do not read the ledger for each streamed token. Wake only on control/receipt changes.
            val key = buildString {
                append(snapshot.runtimePhase).append('/').append(snapshot.sessionPhase)
                append('/').append(snapshot.session.currentThreadId).append('/').append(snapshot.agentChannelHistoryRevision)
                snapshot.outboundTimeline.takeLast(12).forEach {
                    append('/').append(it.clientUserMessageId).append(':').append(it.status).append(':').append(it.turnId)
                }
                snapshot.terminalTurns.takeLast(12).forEach {
                    append('/').append(it.threadId).append(':').append(it.turnId).append(':').append(it.status)
                }
            }
            val changed = synchronized(this) {
                if (observedControlKey != controlKey) {
                    observedControlKey = controlKey
                    deferredUnsentId.set(null)
                }
                (observedWakeKey != key).also { if (it) observedWakeKey = key }
            }
            if (changed) requestDrain()
        })
    }

    private fun externalWake() {
        deferredUnsentId.set(null)
        requestDrain()
    }

    fun requestDrain() {
        if (!wakeQueued.compareAndSet(false, true)) return
        worker.execute {
            // Clear before work: an event emitted by dispatch must schedule a later reconciliation.
            wakeQueued.set(false)
            runCatching { drain() }
            publishStatus()
        }
    }

    fun cancelPending() {
        channel.cancelPending()
        requestDrain()
    }

    fun revoke() {
        channel.revoke()
        requestDrain()
    }

    fun clearPrivateData(): Boolean {
        val cleared = channel.clearPrivateData()
        requestDrain()
        return cleared
    }

    private fun drain() {
        val status = channel.status()
        if (!status.available || status.binding == null) return
        if (privacy.captureDecision("com.whatsapp") != NotificationCaptureDecision.Allowed) {
            channel.clearPrivateData()
            return
        }
        val currentHost = host
        if (currentHost == null) {
            // This is an explicitly enrolled agent-backed wakeup, not ordinary notification triage.
            val needsExistingThreadRecovery = channel.unsettledReceipts().any { it.dispatchCorrelation != null }
            if ((channel.pendingReceipts().isNotEmpty() || needsExistingThreadRecovery) && intakeAvailable()) app.sessionHost
            return
        }
        currentHost.snapshot()?.let { snapshot ->
            WhatsAppAgentReceiptReconciler(channel).reconcile(
                snapshot.outboundTimeline, snapshot.terminalTurns, currentHost.agentChannelRecoveredHistory(),
            )
        }
        WhatsAppAgentHandoff(
            channel,
            mayDispatch = {
                val snapshot = currentHost.snapshot()
                intakeAvailable() && NotificationTriageIntegration.isInteractiveIdle() &&
                    channel.pendingReceipts().firstOrNull()?.id != deferredUnsentId.get() &&
                    snapshot?.runtimePhase == ClientRuntimePhase.READY &&
                    snapshot.sessionPhase == ClientSessionPhase.READY &&
                    snapshot.session.account.phase == ai.hans.standard.codex.AccountPhase.SIGNED_IN &&
                    !snapshot.migrationReadiness.activeTurn
            },
            submit = {
                currentHost.dispatchWhatsAppAgentRequest(channel, it).also { result ->
                    // A definitely-unsent request can retry on a real new event, never in a loop
                    // caused by its own outbound-receipt updates. Ambiguous requests stay claimed.
                    if (result == CodexDispatchAttemptResult.RejectedBeforeTransport) deferredUnsentId.set(it.id)
                }
            },
        ).drainOne()
    }

    internal fun intakeAvailable(): Boolean =
        authoritativeSourceReady() &&
            !ai.hans.standard.phone.notifications.AtomicNotificationPrivacyPurgeFence(app).isRequired() &&
        ActiveNotificationReplyRegistry.processWide.isAvailable() &&
            app.internetConnectivity.snapshot().status.permitsExplicitRequest &&
            app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false &&
            privacy.captureDecision("com.whatsapp") == NotificationCaptureDecision.Allowed

    private fun publishStatus() {
        val status = channel.status()
        observers.forEach { observer -> runCatching { observer(status) } }
    }
}
