package ai.hans.standard.phone.lifecycle.android

import android.Manifest
import android.app.Notification
import android.app.Activity
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import ai.hans.standard.R
import ai.hans.standard.phone.lifecycle.ActiveWorkCommand
import ai.hans.standard.phone.lifecycle.ActiveWorkCommandSink
import ai.hans.standard.phone.lifecycle.ActiveWorkServiceReconciler
import ai.hans.standard.phone.lifecycle.HansActiveWorkCoordinator
import ai.hans.standard.phone.lifecycle.HansActiveWorkReason
import ai.hans.standard.phone.lifecycle.HansActiveWorkSnapshot
import ai.hans.standard.phone.lifecycle.HansActiveWorkUpdate
import ai.hans.standard.phone.lifecycle.HansActiveWorkUpdateStatus
import ai.hans.standard.phone.lifecycle.HansActiveWorkForegroundEvent
import ai.hans.standard.phone.lifecycle.HansActiveWorkForegroundObserver
import ai.hans.standard.phone.lifecycle.HansActiveWorkForegroundSnapshot

enum class HansActiveWorkNotificationVisibility {
    DRAWER_AND_TASK_MANAGER,
    TASK_MANAGER_ONLY,
}

/**
 * Process-local entry point used by the Codex host and speech player.
 *
 * Android 12+ may reject a new foreground-service start while Hans is in the
 * background. That rejection is returned to the caller instead of claiming
 * that work is protected when it is not. Releasing an absent reason is a pure
 * no-op and can never start the service.
 */
object HansActiveWorkOwner {
    private val ownerLock = Any()
    @Volatile
    private var coordinator: HansActiveWorkCoordinator? = null

    fun setReason(
        context: Context,
        reason: HansActiveWorkReason,
        active: Boolean,
    ): HansActiveWorkUpdate {
        val coordinator = coordinator(context)
        if (reason == HansActiveWorkReason.REMOTE_CONTROL && active && reason !in coordinator.snapshot() &&
            (context !is Activity || !canAcquireRemoteControl(
                lifecycleState = (context as? LifecycleOwner)?.lifecycle?.currentState,
                isFinishing = context.isFinishing,
                isDestroyed = context.isDestroyed,
            ))) {
            val current = coordinator.versionedSnapshot()
            return HansActiveWorkUpdate(HansActiveWorkUpdateStatus.REJECTED, current.activeReasons, current.revision)
        }
        return coordinator.setReason(reason, active)
    }

    /** Only a visible local menu interaction may acquire remote availability, never a background RPC. */
    fun acquireRemoteControlFromVisibleActivity(activity: Activity): HansActiveWorkUpdate =
        setReason(activity, HansActiveWorkReason.REMOTE_CONTROL, true)

    fun foregroundSnapshot(): HansActiveWorkForegroundSnapshot = HansActiveWorkForegroundRegistry.snapshot()

    /** Dispatch success is not promotion proof. Callbacks run asynchronously on the main executor. */
    fun addForegroundObserver(context: Context, observer: HansActiveWorkForegroundObserver): AutoCloseable {
        val executor = ContextCompat.getMainExecutor(context.applicationContext)
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val subscription = HansActiveWorkForegroundRegistry.observe {
            executor.execute {
                if (active.get()) observer.onForegroundChanged(HansActiveWorkForegroundRegistry.snapshot())
            }
        }
        return AutoCloseable { active.set(false); subscription.close() }
    }

    internal fun requestRemoteStop() = HansActiveWorkForegroundRegistry.requestRemoteStop()

    fun activeReasons(context: Context): Set<HansActiveWorkReason> =
        coordinator(context).snapshot()

    internal fun versionedSnapshot(context: Context): HansActiveWorkSnapshot =
        coordinator(context).versionedSnapshot()

    /**
     * On Android 13+, foreground work remains legal without notification
     * permission, but Android shows it only in Task Manager rather than the
     * notification drawer. Permission UX is intentionally owned by the UI.
     */
    fun notificationVisibility(context: Context): HansActiveWorkNotificationVisibility =
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            HansActiveWorkNotificationVisibility.TASK_MANAGER_ONLY
        } else {
            HansActiveWorkNotificationVisibility.DRAWER_AND_TASK_MANAGER
        }

    internal fun onServiceStopped(serviceRevision: Long) {
        coordinator?.onServiceStopped(serviceRevision)
    }

    internal fun onServiceTimedOut() {
        coordinator?.onServiceTimedOut()
    }

    private fun coordinator(context: Context): HansActiveWorkCoordinator {
        coordinator?.let { return it }
        return synchronized(ownerLock) {
            coordinator ?: HansActiveWorkCoordinator(
                AndroidActiveWorkCommandSink(context.applicationContext),
            ).also { coordinator = it }
        }
    }
}

/** A local modal dialog may own window focus while its resumed activity handles consent. */
internal fun canAcquireRemoteControl(
    lifecycleState: Lifecycle.State?,
    isFinishing: Boolean,
    isDestroyed: Boolean,
): Boolean = lifecycleState == Lifecycle.State.RESUMED && !isFinishing && !isDestroyed

/**
 * Process-local acknowledgement of a successfully published foreground notification.
 *
 * A last-release can race the service's first [Service.onStartCommand]. Stopping the service
 * externally before that first command calls startForeground leaves Android's five-second
 * obligation armed and eventually kills the whole Hans process. The owner token lets STOP defer
 * that teardown until the pending service has promoted and reconciled the latest revision.
 */
internal object HansActiveWorkForegroundRegistry {
    private val lock = Any()
    private var owner: Any? = null
    private var state = HansActiveWorkForegroundSnapshot()
    private val observers = linkedSetOf<() -> Unit>()

    fun markForeground(owner: Any) = synchronized(lock) {
        this.owner = owner
    }

    fun clearForeground(owner: Any) {
        val changed = synchronized(lock) {
            if (this.owner !== owner) return@synchronized false
            this.owner = null
            state = state.copy(sequence = state.sequence + 1, protectedReasons = emptySet(),
                event = HansActiveWorkForegroundEvent.STOPPED)
            true
        }
        if (changed) notifyObservers()
    }

    fun isForeground(): Boolean = synchronized(lock) { owner != null }

    fun snapshot(): HansActiveWorkForegroundSnapshot = synchronized(lock) { state }

    fun observe(observer: () -> Unit): AutoCloseable {
        synchronized(lock) { observers += observer }
        observer()
        return AutoCloseable { synchronized(lock) { observers -= observer } }
    }

    fun publishProtection(owner: Any, reasons: Set<HansActiveWorkReason>, revision: Long) {
        val changed = synchronized(lock) {
            if (this.owner !== owner) return@synchronized false
            state = state.copy(sequence = state.sequence + 1, revision = revision,
                protectedReasons = reasons.toSet(), event = HansActiveWorkForegroundEvent.PROTECTED)
            true
        }
        if (changed) notifyObservers()
    }

    fun failProtection(owner: Any, revision: Long, event: HansActiveWorkForegroundEvent) {
        require(event == HansActiveWorkForegroundEvent.PROMOTION_REJECTED || event == HansActiveWorkForegroundEvent.TIMED_OUT)
        val changed = synchronized(lock) {
            if (this.owner != null && this.owner !== owner) return@synchronized false
            this.owner = null
            state = state.copy(sequence = state.sequence + 1, revision = revision,
                protectedReasons = emptySet(), event = event)
            true
        }
        if (changed) notifyObservers()
    }

    fun requestRemoteStop() {
        val changed = synchronized(lock) {
            if (HansActiveWorkReason.REMOTE_CONTROL !in state.protectedReasons) return@synchronized false
            state = state.copy(sequence = state.sequence + 1,
                remoteStopRequestSequence = state.remoteStopRequestSequence + 1)
            true
        }
        if (changed) notifyObservers()
    }

    private fun notifyObservers() {
        val current = synchronized(lock) { observers.toList() }
        current.forEach { observer -> runCatching(observer) }
    }
}

internal fun shouldStopActiveWorkService(foregroundPublished: Boolean): Boolean =
    foregroundPublished

private class AndroidActiveWorkCommandSink(
    private val context: Context,
) : ActiveWorkCommandSink {
    override fun apply(
        command: ActiveWorkCommand,
        reasons: Set<HansActiveWorkReason>,
        revision: Long,
    ): Boolean = dispatchActiveWorkCommand {
        when (command) {
            ActiveWorkCommand.NONE -> true
            ActiveWorkCommand.START -> {
                ContextCompat.startForegroundService(
                    context,
                    HansActiveWorkService.syncIntent(context, reasons, revision),
                )
                true
            }
            ActiveWorkCommand.UPDATE -> {
                context.startService(
                    HansActiveWorkService.syncIntent(context, reasons, revision),
                ) != null
            }
            ActiveWorkCommand.STOP -> {
                // If START is still queued, coordinator state already contains the empty target.
                // Its first command will promote synchronously, observe that target and stop
                // itself. Calling stopService in that narrow window reproduces the platform's
                // ForegroundServiceDidNotStartInTimeException and kills every Hans component,
                // including Accessibility.
                if (shouldStopActiveWorkService(HansActiveWorkForegroundRegistry.isForeground())) {
                    context.stopService(HansActiveWorkService.stopIntent(context))
                }
                true
            }
        }
    }
}

/** Android command dispatch rejection is transactional and must never kill Accessibility. */
internal fun dispatchActiveWorkCommand(dispatch: () -> Boolean): Boolean = try {
    dispatch()
} catch (_: RuntimeException) {
    // Includes Android 12 background-start rejection, exhausted Android 15 dataSync quota,
    // missing permission, and process/service races. Exception text is deliberately not logged
    // because framework messages may contain app or work metadata.
    false
}

/**
 * Foreground owner only. It contains no Binder endpoint and no user content.
 * The actual Codex process and speech player keep their own lifecycles.
 */
class HansActiveWorkService : Service() {
    private val foregroundOwner = Any()
    private var activeReasons: Set<HansActiveWorkReason> = emptySet()
    private var lastAppliedRevision: Long = 0L
    private var foregroundPublished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedReasons = intent
            ?.takeIf { it.action == ACTION_SYNC }
            ?.getIntExtra(EXTRA_REASON_MASK, 0)
            ?.let(HansActiveWorkReason::fromWireMask)
        val requestedRevision = intent?.getLongExtra(EXTRA_REVISION, 0L) ?: 0L

        // This is deliberately before every validity/staleness exit and before consulting the
        // process owner. A START followed immediately by STOP can make the requested revision
        // stale before Android delivers it, but the foreground-start obligation still exists.
        val provisionalReasons = ActiveWorkServiceReconciler.provisionalReasons(
            alreadyPublished = activeReasons,
            requested = requestedReasons,
            fallback = PROVISIONAL_REASONS,
        )
        if (
            (!foregroundPublished || activeReasons != provisionalReasons) &&
            !publishForegroundOrStop(provisionalReasons, startId)
        ) {
            return START_NOT_STICKY
        }

        val desired = HansActiveWorkOwner.versionedSnapshot(this)
        val target = ActiveWorkServiceReconciler.targetSnapshot(
            desired = desired,
            requested = requestedReasons,
            requestedRevision = requestedRevision,
        )

        lastAppliedRevision = maxOf(lastAppliedRevision, target.revision)
        if (target.activeReasons.isEmpty()) {
            stopForegroundAndSelf(startId)
            return START_NOT_STICKY
        }

        // A stale queued command must never roll the user-visible notification or service type
        // back. Re-publish only the coordinator's latest accepted state.
        if (
            target.activeReasons != provisionalReasons &&
            !publishForegroundOrStop(target.activeReasons, startId)
        ) {
            return START_NOT_STICKY
        }
        activeReasons = target.activeReasons
        HansActiveWorkForegroundRegistry.publishProtection(foregroundOwner, activeReasons, lastAppliedRevision)
        return START_NOT_STICKY
    }

    /**
     * Android 15 requires a timed-out dataSync service to stop itself within a
     * few seconds; merely dropping that type is not sufficient. The platform
     * therefore wins over process-local ownership and all reasons are cleared.
     * A later user-visible operation can acquire a fresh owner normally.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        activeReasons = emptySet()
        HansActiveWorkForegroundRegistry.failProtection(foregroundOwner, lastAppliedRevision,
            HansActiveWorkForegroundEvent.TIMED_OUT)
        ignoreActiveWorkRuntimeFailure { HansActiveWorkOwner.onServiceTimedOut() }
        ignoreActiveWorkRuntimeFailure { stopForeground(STOP_FOREGROUND_REMOVE) }
        HansActiveWorkForegroundRegistry.clearForeground(foregroundOwner)
        foregroundPublished = false
        ignoreActiveWorkRuntimeFailure { stopSelf() }
    }

    override fun onDestroy() {
        activeReasons = emptySet()
        HansActiveWorkForegroundRegistry.clearForeground(foregroundOwner)
        foregroundPublished = false
        if (lastAppliedRevision > 0L) {
            ignoreActiveWorkRuntimeFailure {
                HansActiveWorkOwner.onServiceStopped(lastAppliedRevision)
            }
        }
        super.onDestroy()
    }

    private fun publishForegroundOrStop(
        reasons: Set<HansActiveWorkReason>,
        startId: Int,
    ): Boolean = runActiveWorkPromotion(
        promote = { publishForeground(reasons) },
        onRejected = { failClosedAfterPromotionRejection(startId) },
    )

    private fun publishForeground(reasons: Set<HansActiveWorkReason>) {
        ensureNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(reasons),
            foregroundServiceTypes(reasons),
        )
        foregroundPublished = true
        HansActiveWorkForegroundRegistry.markForeground(foregroundOwner)
    }

    private fun stopForegroundAndSelf(startId: Int) {
        activeReasons = emptySet()
        if (foregroundPublished) {
            ignoreActiveWorkRuntimeFailure { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
        HansActiveWorkForegroundRegistry.clearForeground(foregroundOwner)
        foregroundPublished = false
        ignoreActiveWorkRuntimeFailure { stopSelf(startId) }
    }

    /**
     * Once promotion is rejected, no claimed reason is actually protected. Clear the owner
     * transaction and stop synchronously so Android's foreground-start obligation cannot later
     * terminate the shared Hans process.
     */
    private fun failClosedAfterPromotionRejection(startId: Int) {
        activeReasons = emptySet()
        HansActiveWorkForegroundRegistry.failProtection(foregroundOwner,
            HansActiveWorkOwner.versionedSnapshot(this).revision,
            HansActiveWorkForegroundEvent.PROMOTION_REJECTED)
        ignoreActiveWorkRuntimeFailure { HansActiveWorkOwner.onServiceTimedOut() }
        if (foregroundPublished) {
            ignoreActiveWorkRuntimeFailure { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
        HansActiveWorkForegroundRegistry.clearForeground(foregroundOwner)
        foregroundPublished = false
        ignoreActiveWorkRuntimeFailure { stopSelf(startId) }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.active_work_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.active_work_channel_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    internal fun buildNotification(reasons: Set<HansActiveWorkReason>): Notification {
        val remote = HansActiveWorkReason.REMOTE_CONTROL in reasons
        val text = when (reasons) {
            setOf(HansActiveWorkReason.CODEX_ACTIVE) -> R.string.active_work_codex
            setOf(HansActiveWorkReason.SPEECH_ACTIVE) -> R.string.active_work_speech
            else -> R.string.active_work_codex_and_speech
        }
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hans)
            .setContentTitle(getString(if (remote) R.string.remote_active_work_title else R.string.active_work_notification_title))
            .setContentText(getString(if (remote) R.string.remote_active_work_description else text))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (remote) {
            val stop = PendingIntent.getBroadcast(this, REMOTE_STOP_REQUEST_CODE,
                HansActiveWorkRemoteStopReceiver.intent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, getString(R.string.remote_active_work_stop), stop).build())
        }
        return builder.build()
    }

    internal companion object {
        const val NOTIFICATION_CHANNEL_ID = "hans_active_work_v1"
        const val NOTIFICATION_ID = 4101
        private const val REMOTE_STOP_REQUEST_CODE = 4102
        private const val ACTION_SYNC = "ai.hans.standard.action.SYNC_ACTIVE_WORK_V1"
        private const val EXTRA_REASON_MASK = "active_reason_mask"
        private const val EXTRA_REVISION = "active_reason_revision"
        // Malformed internal sync commands perform only a brief state reconciliation, so the
        // fallback remains dataSync rather than claiming media playback which is not occurring.
        // Production START/UPDATE commands always use their requested effective reasons.
        private val PROVISIONAL_REASONS = setOf(HansActiveWorkReason.CODEX_ACTIVE)

        fun syncIntent(
            context: Context,
            reasons: Set<HansActiveWorkReason>,
            revision: Long,
        ): Intent = Intent(context, HansActiveWorkService::class.java)
            .setAction(ACTION_SYNC)
            .putExtra(EXTRA_REASON_MASK, HansActiveWorkReason.toWireMask(reasons))
            .putExtra(EXTRA_REVISION, revision)

        fun stopIntent(context: Context): Intent =
            Intent(context, HansActiveWorkService::class.java)

        fun foregroundServiceTypes(reasons: Set<HansActiveWorkReason>): Int {
            var types = 0
            if (HansActiveWorkReason.CODEX_ACTIVE in reasons) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            if (HansActiveWorkReason.SPEECH_ACTIVE in reasons) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            if (HansActiveWorkReason.REMOTE_CONTROL in reasons) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            return types
        }
    }
}

/**
 * Contains quota, service-type, security and eligibility rejection. [Error] deliberately escapes.
 */
internal fun runActiveWorkPromotion(
    promote: () -> Unit,
    onRejected: () -> Unit,
): Boolean = try {
    promote()
    true
} catch (_: RuntimeException) {
    ignoreActiveWorkRuntimeFailure(onRejected)
    false
}

/** Best-effort lifecycle cleanup which intentionally does not catch [Error]. */
internal inline fun ignoreActiveWorkRuntimeFailure(block: () -> Unit) {
    try {
        block()
    } catch (_: RuntimeException) {
        // The desired terminal state is already recorded in process-local state.
    }
}
