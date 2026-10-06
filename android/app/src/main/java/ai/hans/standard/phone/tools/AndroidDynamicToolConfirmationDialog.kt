package ai.hans.standard.phone.tools

import ai.hans.standard.R
import ai.hans.standard.localization.AndroidHansTextResolver

import android.app.Activity
import android.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import ai.hans.standard.phone.capabilities.CapabilityConfirmation
import ai.hans.standard.phone.capabilities.CapabilityId
import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import ai.hans.standard.phone.consent.PersistentAndroidConsentStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Presents one explicit, call-correlated confirmation for Android actions.
 *
 * [confirmedGrant] is invoked only on the dedicated dynamic-tool executor. It
 * may wait for the user there, but never blocks Binder, Compose or the Android
 * main thread. Closing Hans invalidates every outstanding request.
 */
class AndroidDynamicToolConfirmationDialog(
    private val activity: Activity,
    private val consentStore: PersistentAndroidConsentStore = PersistentAndroidConsentStore.NONE,
    private val onConsentChanged: () -> Unit = {},
) : DynamicToolConfirmationProvider {
    private val oneAtATime = Semaphore(1, true)
    private val closed = AtomicBoolean(false)
    private val visibleDialog = AtomicReference<AlertDialog?>(null)
    private val activeWaiter = AtomicReference<CountDownLatch?>(null)

    override fun confirmedGrant(
        request: DynamicToolConfirmationRequest,
    ): CapabilityConfirmation? {
        if (closed.get()) return null
        val persistentDescriptor = DynamicToolPersistentConsentPolicy.descriptorFor(request)
        if (
            persistentDescriptor != null &&
            runCatching { consentStore.contains(persistentDescriptor) }.getOrDefault(false)
        ) {
            return exactGrant(request)
        }
        val acquired = try {
            oneAtATime.tryAcquire(CONFIRMATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) return null

        val latch = CountDownLatch(1)
        val decision = AtomicReference(DialogDecision.PENDING)
        activeWaiter.set(latch)
        try {
            if (closed.get()) return null
            if (
                persistentDescriptor != null &&
                runCatching { consentStore.contains(persistentDescriptor) }.getOrDefault(false)
            ) {
                return exactGrant(request)
            }
            if (!isVisibleDialogHost()) return null
            activity.runOnUiThread {
                if (closed.get() || !isVisibleDialogHost()) {
                    latch.countDown()
                    return@runOnUiThread
                }
                try {
                    val builder = AlertDialog.Builder(activity)
                        .setTitle(AndroidHansTextResolver(activity).text(R.string.integration_allow_android_action_8538fd2))
                        .setMessage(messageFor(request.capabilityId, persistentDescriptor))
                        .setNegativeButton(AndroidHansTextResolver(activity).text(R.string.integration_decline_7be75ce), null)
                        .setOnCancelListener {
                            decision.compareAndSet(DialogDecision.PENDING, DialogDecision.REJECTED)
                        }
                    if (persistentDescriptor == null) {
                        builder.setPositiveButton(AndroidHansTextResolver(activity).text(R.string.integration_allow_once_90153ce), null)
                    } else {
                        builder
                            .setNeutralButton(AndroidHansTextResolver(activity).text(R.string.integration_allow_once_90153ce), null)
                            .setPositiveButton(AndroidHansTextResolver(activity).text(R.string.integration_always_allow_179369f), null)
                    }
                    val dialog = builder.create()
                    dialog.setOnDismissListener {
                        decision.compareAndSet(DialogDecision.PENDING, DialogDecision.REJECTED)
                        visibleDialog.compareAndSet(dialog, null)
                        latch.countDown()
                    }
                    dialog.show()
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        completeDialog(
                            dialog = dialog,
                            decision = decision,
                            chosen = DialogDecision.REJECTED,
                            latch = latch,
                        )
                    }
                    if (persistentDescriptor == null) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            completeDialog(
                                dialog = dialog,
                                decision = decision,
                                chosen = DialogDecision.ACCEPT_ONCE,
                                latch = latch,
                            )
                        }
                    } else {
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            completeDialog(
                                dialog = dialog,
                                decision = decision,
                                chosen = DialogDecision.ACCEPT_ONCE,
                                latch = latch,
                            )
                        }
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            completeDialog(
                                dialog = dialog,
                                decision = decision,
                                chosen = DialogDecision.PERSIST,
                                latch = latch,
                            )
                        }
                    }
                    // Do not expose a dialog until show() has created every
                    // button and the exact decision listeners are in place.
                    if (closed.get() || !isVisibleDialogHost()) {
                        decision.compareAndSet(DialogDecision.PENDING, DialogDecision.REJECTED)
                        dialog.dismiss()
                        latch.countDown()
                    } else {
                        visibleDialog.set(dialog)
                    }
                } catch (_: RuntimeException) {
                    latch.countDown()
                }
            }

            val completed = try {
                latch.await(CONFIRMATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!completed || closed.get()) return null
            return when (decision.get()) {
                DialogDecision.PERSIST -> {
                    val descriptor = persistentDescriptor ?: return null
                    if (DynamicToolPersistentConsentPolicy.descriptorFor(request) != descriptor) {
                        return null
                    }
                    val persisted = runCatching { consentStore.grant(descriptor) }.getOrDefault(false)
                    if (!persisted || closed.get()) return null
                    runCatching(onConsentChanged)
                    exactGrant(request)
                }
                DialogDecision.ACCEPT_ONCE -> exactGrant(request)
                DialogDecision.PENDING,
                DialogDecision.REJECTED,
                -> null
            }
        } finally {
            activeWaiter.compareAndSet(latch, null)
            val staleDialog = visibleDialog.getAndSet(null)
            activity.runOnUiThread { staleDialog?.dismiss() }
            oneAtATime.release()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeWaiter.getAndSet(null)?.countDown()
        val staleDialog = visibleDialog.getAndSet(null)
        activity.runOnUiThread { staleDialog?.dismiss() }
    }

    private fun completeDialog(
        dialog: AlertDialog,
        decision: AtomicReference<DialogDecision>,
        chosen: DialogDecision,
        latch: CountDownLatch,
    ) {
        if (!decision.compareAndSet(DialogDecision.PENDING, chosen)) return
        try {
            dialog.dismiss()
        } finally {
            // Keep a bounded fallback for vendor AlertDialog implementations which do not
            // synchronously dispatch onDismiss after a programmatic button decision.
            latch.countDown()
        }
    }

    private fun exactGrant(request: DynamicToolConfirmationRequest) = CapabilityConfirmation(
        capabilityId = request.capabilityId,
        idempotencyKey = request.idempotencyKey,
        risk = request.risk,
    )

    private fun messageFor(
        capabilityId: CapabilityId,
        persistentDescriptor: PersistentAndroidConsentDescriptor?,
    ): String {
        val action = when (capabilityId) {
            CapabilityId.LIST_LAUNCHABLE_APPS ->
                AndroidHansTextResolver(activity).text(R.string.integration_hans_would_like_to_list_the_launchable_apps_on_this_pho_4a0ec03)
            CapabilityId.LAUNCH_APP ->
                AndroidHansTextResolver(activity).text(R.string.integration_hans_would_like_to_visibly_open_an_installed_app_now_2647e3f)
            CapabilityId.OPEN_VIEW ->
                AndroidHansTextResolver(activity).text(R.string.integration_hans_would_like_to_open_a_public_web_address_in_an_app__562e14f)
            CapabilityId.OPEN_SETTINGS ->
                AndroidHansTextResolver(activity).text(R.string.integration_hans_would_like_to_open_an_android_settings_page_now_638c6df)
            CapabilityId.MANAGE_NOTIFICATION_PRIVACY ->
                AndroidHansTextResolver(activity).text(R.string.integration_hans_would_like_to_change_your_local_notification_priva_3647697) +
                    AndroidHansTextResolver(activity).text(R.string.integration_this_may_delete_stored_notification_data_or_retain_more_9026854) +
                    AndroidHansTextResolver(activity).text(R.string.integration_in_future_307fe1c)
            else ->
                AndroidHansTextResolver(activity).text(R.string.integration_hans_would_like_to_perform_a_visible_android_action_now_15c7080)
        }
        val durableExplanation = when (persistentDescriptor?.scope) {
            PersistentAndroidConsentScope.INSTALLED_APPS_READ ->
                AndroidHansTextResolver(activity).text(R.string.integration_always_allow_lets_hans_check_which_apps_are_installed_a_63efda5)
            PersistentAndroidConsentScope.OPEN_APP ->
                AndroidHansTextResolver(activity).text(R.string.integration_always_allow_lets_hans_visibly_open_installed_apps_in_f_952a936)
            PersistentAndroidConsentScope.OPEN_SAFE_NAVIGATION ->
                AndroidHansTextResolver(activity).text(R.string.integration_always_allow_lets_hans_visibly_open_validated_web_map_a_b0a7192)
            PersistentAndroidConsentScope.OPEN_SETTINGS_PAGE ->
                AndroidHansTextResolver(activity).text(R.string.integration_always_allow_lets_hans_visibly_open_known_public_androi_d784765)
            null -> return action
            else -> return action
        }
        return AndroidHansTextResolver(activity).text(R.string.integration_1_2_you_can_revoke_this_permission_in_hans_at_any_time__b5c72c5, action, durableExplanation)
    }

    private fun isVisibleDialogHost(): Boolean {
        val lifecycleOwner = activity as? LifecycleOwner ?: return false
        return !activity.isFinishing &&
            !activity.isDestroyed &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    private companion object {
        const val CONFIRMATION_TIMEOUT_SECONDS = 90L
    }

    private enum class DialogDecision {
        PENDING,
        ACCEPT_ONCE,
        PERSIST,
        REJECTED,
    }
}
