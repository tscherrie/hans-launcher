package ai.hans.standard.phone.tools

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
                        .setTitle("Android-Aktion erlauben?")
                        .setMessage(messageFor(request.capabilityId, persistentDescriptor))
                        .setNegativeButton("Ablehnen", null)
                        .setOnCancelListener {
                            decision.compareAndSet(DialogDecision.PENDING, DialogDecision.REJECTED)
                        }
                    if (persistentDescriptor == null) {
                        builder.setPositiveButton("Einmal erlauben", null)
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
                "Hans möchte die auf diesem Telefon startbaren Apps auflisten."
            CapabilityId.LAUNCH_APP ->
                "Hans möchte jetzt eine installierte App sichtbar öffnen."
            CapabilityId.OPEN_VIEW ->
                "Hans möchte jetzt eine öffentliche Webadresse in einer App öffnen."
            CapabilityId.OPEN_SETTINGS ->
                "Hans möchte jetzt eine Android-Einstellungsseite öffnen."
            CapabilityId.MANAGE_NOTIFICATION_PRIVACY ->
                "Hans möchte jetzt deine lokalen Benachrichtigungs-Datenschutzregeln ändern. " +
                    "Dabei können gespeicherte Push-Daten gelöscht oder künftig mehr Inhalte " +
                    "aufbewahrt werden."
            else ->
                "Hans möchte jetzt eine sichtbare Android-Aktion ausführen."
        }
        val durableExplanation = when (persistentDescriptor?.scope) {
            PersistentAndroidConsentScope.INSTALLED_APPS_READ ->
                "Dauerhaft erlauben lässt Hans künftig ohne weiteren Hans-Dialog prüfen, welche Apps installiert und startbar sind."
            PersistentAndroidConsentScope.OPEN_APP ->
                "Dauerhaft erlauben lässt Hans künftig installierte Apps sichtbar öffnen."
            PersistentAndroidConsentScope.OPEN_SAFE_NAVIGATION ->
                "Dauerhaft erlauben lässt Hans künftig geprüfte Web-, Karten- und Store-Adressen sichtbar öffnen."
            PersistentAndroidConsentScope.OPEN_SETTINGS_PAGE ->
                "Dauerhaft erlauben lässt Hans künftig bekannte öffentliche Android-Einstellungsseiten sichtbar öffnen. Sicherheits- und Berechtigungsdialoge bleiben unter deiner Kontrolle."
            null -> return action
            else -> return action
        }
        return "$action $durableExplanation Du kannst die Freigabe jederzeit in Hans widerrufen. Android-Systemberechtigungen bleiben davon getrennt."
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
