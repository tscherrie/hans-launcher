package ai.hans.standard.phone.publicapi

import android.app.Activity
import android.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import ai.hans.standard.phone.consent.PersistentAndroidConsentStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One-at-a-time, Activity-scoped confirmation UI for personal reads and mutations. This is called
 * only from the dedicated tool executor and never blocks Binder, Compose or the Android main
 * thread. Closing the Activity invalidates all outstanding confirmations.
 */
class AndroidPublicPhoneConfirmationDialog(
    private val activity: Activity,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    private val consentStore: PersistentAndroidConsentStore = PersistentAndroidConsentStore.NONE,
    private val onConsentChanged: () -> Unit = {},
) : PublicPhoneConfirmationProvider, AutoCloseable {
    private val oneAtATime = Semaphore(1, true)
    private val closed = AtomicBoolean(false)
    private val visibleDialog = AtomicReference<AlertDialog?>(null)
    private val activeWaiter = AtomicReference<CountDownLatch?>(null)

    init {
        require(timeoutSeconds in 1..MAX_TIMEOUT_SECONDS)
    }

    override fun confirm(
        request: PublicPhoneConfirmationRequest,
    ): PublicPhoneConfirmationGrant? {
        if (closed.get()) return null
        val persistentDescriptor = PublicPhonePersistentConsentPolicy.descriptorFor(request)
        if (
            persistentDescriptor != null &&
            runCatching { consentStore.contains(persistentDescriptor) }.getOrDefault(false)
        ) {
            return exactGrant(request)
        }
        val acquired = try {
            oneAtATime.tryAcquire(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) return null

        val decision = AtomicReference(DialogDecision.PENDING)
        val latch = CountDownLatch(1)
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
                        .setTitle(titleFor(request.risk))
                        .setMessage(messageFor(request, persistentDescriptor != null))
                        .setNegativeButton("Ablehnen", null)
                        .setOnCancelListener {
                            decision.compareAndSet(DialogDecision.PENDING, DialogDecision.REJECTED)
                        }
                    if (persistentDescriptor == null) {
                        builder.setPositiveButton(positiveLabelFor(request.risk), null)
                    } else {
                        builder
                            .setNeutralButton("Einmal erlauben", null)
                            .setPositiveButton("Dauerhaft erlauben", null)
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
                    // Publish only after show() created every button and the exact
                    // decision listeners are installed. Tests and diagnostics can
                    // therefore never observe a half-initialized dialog.
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
                latch.await(timeoutSeconds, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!completed || closed.get()) return null
            return when (decision.get()) {
                DialogDecision.PERSIST -> {
                    val descriptor = persistentDescriptor ?: return null
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

    override fun close() {
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
            // Android normally invokes onDismiss synchronously. Keep this fallback so a
            // vendor dialog implementation can never strand the dedicated tool executor.
            latch.countDown()
        }
    }

    private fun titleFor(risk: PublicPhoneRisk): String = when (risk) {
        PublicPhoneRisk.SENSITIVE_READ -> "Private Telefondaten lesen?"
        PublicPhoneRisk.USER_VISIBLE -> "Telefonaktion öffnen?"
        PublicPhoneRisk.EXTERNAL_MUTATION -> "Externe Aktion ausführen?"
    }

    private fun positiveLabelFor(risk: PublicPhoneRisk): String = when (risk) {
        PublicPhoneRisk.SENSITIVE_READ -> "Einmal erlauben"
        PublicPhoneRisk.USER_VISIBLE -> "Jetzt öffnen"
        PublicPhoneRisk.EXTERNAL_MUTATION -> "Jetzt ausführen"
    }

    private fun exactGrant(request: PublicPhoneConfirmationRequest) =
        PublicPhoneConfirmationGrant(
            callId = request.callId,
            tool = request.tool,
            risk = request.risk,
            argumentFingerprint = request.argumentFingerprint,
        )

    private fun messageFor(
        request: PublicPhoneConfirmationRequest,
        persistentEligible: Boolean,
    ): String = if (persistentEligible) {
        val category = if (request.risk == PublicPhoneRisk.SENSITIVE_READ) {
            "diese private Lesekategorie"
        } else {
            "diese sichtbare Öffnungsaktion"
        }
        "${request.displaySummary} Du kannst $category dauerhaft erlauben und später in Hans widerrufen. Die Android-Systemberechtigung ist separat."
    } else {
        request.displaySummary
    }

    private fun isVisibleDialogHost(): Boolean {
        val lifecycleOwner = activity as? LifecycleOwner ?: return false
        return !activity.isFinishing &&
            !activity.isDestroyed &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 90L
        const val MAX_TIMEOUT_SECONDS = 300L
    }

    private enum class DialogDecision {
        PENDING,
        ACCEPT_ONCE,
        PERSIST,
        REJECTED,
    }
}
