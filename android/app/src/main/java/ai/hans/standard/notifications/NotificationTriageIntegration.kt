package ai.hans.standard.notifications

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local, narrow integration surface. It never exposes source notification text.
 *
 * The launcher may attach a sink after its chat/TTS pipeline is ready. Until then deliveries stay
 * durable and retryable. Attaching neither dispatches a Codex turn nor grants any Android tool.
 */
object NotificationTriageIntegration {
    private val suggestionSink = AtomicReference<UserFacingNotificationSuggestionSink?>(null)
    private val relevanceContext = AtomicReference<NotificationRelevanceContextProvider?>(null)
    private val runtimeWakeup = AtomicReference<(() -> Unit)?>(null)
    private val runtimePreempt = AtomicReference<(() -> Unit)?>(null)
    private val validatedSuggestionController =
        AtomicReference<ValidatedNotificationSuggestionController?>(null)
    private val interactiveActivities = ConcurrentHashMap.newKeySet<NotificationInteractiveActivity>()
    private val idleWakeups = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val notificationEventWakeups = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    internal fun attachNotificationEventWakeup(wakeup: () -> Unit): Closeable {
        notificationEventWakeups.add(wakeup)
        return Closeable { notificationEventWakeups.remove(wakeup) }
    }

    internal fun attachIdleWakeup(wakeup: () -> Unit): Closeable {
        idleWakeups.add(wakeup)
        return Closeable { idleWakeups.remove(wakeup) }
    }

    fun attachSuggestionSink(sink: UserFacingNotificationSuggestionSink): Closeable {
        suggestionSink.set(sink)
        runtimeWakeup.get()?.invoke()
        return Closeable { suggestionSink.compareAndSet(sink, null) }
    }

    fun attachRelevanceContextProvider(provider: NotificationRelevanceContextProvider): Closeable {
        relevanceContext.set(provider)
        return Closeable { relevanceContext.compareAndSet(provider, null) }
    }

    internal fun deliverySink(): UserFacingNotificationSuggestionSink = object :
        UserFacingNotificationSuggestionSink {
        override fun isReady(): Boolean = suggestionSink.get()?.isReady() == true

        override fun deliver(
            delivery: UserFacingNotificationDelivery,
        ): UserFacingDeliveryDisposition {
            return suggestionSink.get()?.deliver(delivery) ?: UserFacingDeliveryDisposition.RETRY
        }


        override fun activate(
            delivery: UserFacingNotificationDelivery,
        ): UserFacingNotificationActivationDisposition =
            suggestionSink.get()?.activate(delivery)
                ?: UserFacingNotificationActivationDisposition.RETRY

        override fun finalizeActivation(
            delivery: UserFacingNotificationDelivery,
            disposition: UserFacingNotificationActivationDisposition,
        ) {
            suggestionSink.get()?.finalizeActivation(delivery, disposition)
        }

        override fun revoke(delivery: UserFacingNotificationDelivery) {
            suggestionSink.get()?.revoke(delivery)
        }
    }

    internal fun contextProvider(): NotificationRelevanceContextProvider =
        NotificationRelevanceContextProvider {
            relevanceContext.get()?.current() ?: NotificationRelevanceContext(
                userIsDictating = true,
                liveVoiceIsActive = true,
                quietModeIsActive = true,
            )
        }

    internal fun attachRuntimeWakeup(wakeup: () -> Unit): Closeable {
        runtimeWakeup.set(wakeup)
        return Closeable { runtimeWakeup.compareAndSet(wakeup, null) }
    }

    internal fun attachRuntimePreemption(preempt: () -> Unit): Closeable {
        runtimePreempt.set(preempt)
        return Closeable { runtimePreempt.compareAndSet(preempt, null) }
    }

    /** Consent/runtime-authority revocation aborts any in-flight enrichment or synthesis now. */
    fun preemptForEnrichmentAuthorityChange() {
        runtimePreempt.get()?.invoke()
    }

    fun attachValidatedSuggestionController(
        controller: ValidatedNotificationSuggestionController,
    ): Closeable {
        validatedSuggestionController.set(controller)
        return Closeable { validatedSuggestionController.compareAndSet(controller, null) }
    }

    internal fun invalidatePendingSuggestion(supersessionKey: String): Boolean = runCatching {
        validatedSuggestionController.get()?.invalidatePending(supersessionKey)
    }.getOrNull() == true

    internal fun clearValidatedSuggestions(): Boolean = runCatching {
        validatedSuggestionController.get()?.clearAll()
    }.getOrNull() == true

    /**
     * Android's active-notification snapshot is the authority for whether an already validated
     * summary may still be shown, spoken or attached to a turn. Unknown listener state closes
     * that boundary before any potentially blocking durable clear/physical-audio stop begins.
     */
    internal fun quarantineValidatedDelivery(): Boolean {
        NotificationValidatedDeliveryAuthority.quarantine()
        return runCatching {
            validatedSuggestionController.get()?.quarantineOutput()
        }.getOrNull() == true
    }

    /**
     * Publishes authority only after snapshot reconciliation and every stale revocation finish.
     * If host resume fails after publication, close authority again and synchronously reapply the
     * physical-output barrier before reporting failure.
     */
    internal fun markValidatedDeliveryReady(): Boolean {
        NotificationValidatedDeliveryAuthority.markReady()
        val resumed = runCatching {
            validatedSuggestionController.get()?.resumeOutputAfterAuthorityRestored()
        }.getOrNull() == true
        if (resumed && NotificationValidatedDeliveryAuthority.isReady()) return true
        NotificationValidatedDeliveryAuthority.quarantine()
        runCatching { validatedSuggestionController.get()?.quarantineOutput() }
        return false
    }

    /**
     * Notification model work always yields to the user's foreground interaction. State changes
     * wake the event-driven worker only when the complete interactive pipeline becomes idle;
     * there is deliberately no polling timer.
     */
    internal fun setInteractiveActivity(
        activity: NotificationInteractiveActivity,
        active: Boolean,
    ) {
        val changed = if (active) {
            interactiveActivities.add(activity)
        } else {
            interactiveActivities.remove(activity)
        }
        if (!changed) return
        notificationEventWakeups.forEach { wakeup -> runCatching { wakeup() } }
        if (interactiveActivities.isEmpty()) {
            runtimeWakeup.get()?.invoke()
            idleWakeups.forEach { wakeup -> runCatching { wakeup() } }
        } else if (active) {
            runtimePreempt.get()?.invoke()
        }
    }

    internal fun isInteractiveIdle(): Boolean = interactiveActivities.isEmpty()

    /** Native external messages may join regular work, but never compete with live audio. */
    internal fun isNotificationEventIntakeAllowed(): Boolean =
        NotificationInteractiveActivity.DICTATION !in interactiveActivities &&
            NotificationInteractiveActivity.LIVE_VOICE !in interactiveActivities
}

/**
 * Process-local fail-closed snapshot authority. A new process starts quarantined; the listener is
 * the sole writer that may publish READY after a successful authoritative snapshot. Durable
 * Center contents remain intact for crash recovery but are invisible while this gate is closed.
 */
internal object NotificationValidatedDeliveryAuthority {
    private val ready = AtomicBoolean(false)

    fun isReady(): Boolean = ready.get()

    fun quarantine() {
        ready.set(false)
    }

    fun markReady() {
        ready.set(true)
    }
}

interface ValidatedNotificationSuggestionController {
    fun invalidatePending(supersessionKey: String): Boolean
    fun clearAll(): Boolean
    /** Stop/cancel every notification output attempt without deleting its durable Center stage. */
    fun quarantineOutput(): Boolean
    /** Re-enable the retained stage only after the authoritative delivery gate is READY. */
    fun resumeOutputAfterAuthorityRestored(): Boolean
}

/**
 * Uses a previously initialized interactive controller when one exists, but never invokes a
 * factory or lazy host accessor from notification capture/triage. Routine and silent pushes can
 * therefore invalidate private staged state without booting the main App Server.
 */
internal class PassiveFirstValidatedSuggestionController(
    private val initializedInteractiveController:
        AtomicReference<ValidatedNotificationSuggestionController?>,
    private val passiveInvalidate: (String) -> Boolean,
    private val passiveClear: () -> Boolean,
    private val passiveQuarantine: () -> Boolean = { true },
    private val passiveResume: () -> Boolean = { true },
) : ValidatedNotificationSuggestionController {
    override fun invalidatePending(supersessionKey: String): Boolean =
        initializedInteractiveController.get()?.invalidatePending(supersessionKey)
            ?: passiveInvalidate(supersessionKey)

    override fun clearAll(): Boolean =
        initializedInteractiveController.get()?.clearAll() ?: passiveClear()

    override fun quarantineOutput(): Boolean =
        initializedInteractiveController.get()?.quarantineOutput() ?: passiveQuarantine()

    override fun resumeOutputAfterAuthorityRestored(): Boolean =
        initializedInteractiveController.get()?.resumeOutputAfterAuthorityRestored()
            ?: passiveResume()
}

/** Process-local bridge for atomic privacy purges against the live queue instance. */
object NotificationTriagePrivacyIntegration {
    private val controller = AtomicReference<NotificationTriagePrivacyController?>(null)

    internal fun attach(value: NotificationTriagePrivacyController): Closeable {
        controller.set(value)
        return Closeable { controller.compareAndSet(value, null) }
    }

    fun clearAll(): Boolean = runCatching { controller.get()?.clearAll() }
        .getOrNull() == true

    fun purgeExcluded(): Boolean = runCatching { controller.get()?.purgeExcluded() }
        .getOrNull() == true
}

internal interface NotificationTriagePrivacyController {
    fun clearAll(): Boolean
    fun purgeExcluded(): Boolean
}

internal enum class NotificationInteractiveActivity {
    CODEX_TURN_OR_TOOL,
    DICTATION,
    LIVE_VOICE,
}
