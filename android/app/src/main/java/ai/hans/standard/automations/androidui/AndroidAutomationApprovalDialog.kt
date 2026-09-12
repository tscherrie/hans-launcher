package ai.hans.standard.automations.androidui

import ai.hans.standard.automations.AutomationToolApprovalProvider
import ai.hans.standard.automations.AutomationToolApprovalReceipt
import ai.hans.standard.automations.AutomationToolApprovalRequest
import ai.hans.standard.automations.AutomationToolRisk
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import android.app.Activity
import android.app.AlertDialog
import java.io.Closeable
import java.time.Duration
import java.time.Instant
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Trusted on-device consent surface for an exact, hash-correlated automation mutation. */
class AndroidAutomationApprovalDialog(
    private val activity: Activity,
    private val now: () -> Instant = Instant::now,
) : AutomationToolApprovalProvider, Closeable {
    private val oneAtATime = Semaphore(1, true)
    private val closed = AtomicBoolean(false)
    private val dialog = AtomicReference<AlertDialog?>(null)
    private val waiter = AtomicReference<CountDownLatch?>(null)
    private val secureRandom = SecureRandom()

    override fun approve(request: AutomationToolApprovalRequest): AutomationToolApprovalReceipt? {
        if (closed.get()) return null
        val acquired = try {
            oneAtATime.tryAcquire(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired || closed.get()) return null

        val latch = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        waiter.set(latch)
        try {
            activity.runOnUiThread {
                if (closed.get() || activity.isFinishing || activity.isDestroyed) {
                    latch.countDown()
                    return@runOnUiThread
                }
                runCatching {
                    val visible = AlertDialog.Builder(activity)
                        .setTitle(title(request.risk))
                        .setMessage(message(request))
                        .setPositiveButton("Einmal erlauben") { _, _ ->
                            accepted.set(true)
                            latch.countDown()
                        }
                        .setNegativeButton("Ablehnen") { _, _ -> latch.countDown() }
                        .setOnCancelListener { latch.countDown() }
                        .create()
                    visible.setOnDismissListener {
                        dialog.compareAndSet(visible, null)
                        latch.countDown()
                    }
                    dialog.set(visible)
                    visible.show()
                }.onFailure { latch.countDown() }
            }
            val completed = try {
                latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!completed || !accepted.get() || closed.get()) return null
            val approvedAt = now()
            return AutomationToolApprovalReceipt(
                callId = request.callId,
                operation = request.operation,
                argumentsSha256 = request.argumentsSha256,
                approvedAt = approvedAt,
                expiresAt = approvedAt.plus(RECEIPT_VALIDITY),
                nonce = newNonce(),
            )
        } finally {
            waiter.compareAndSet(latch, null)
            activity.runOnUiThread { dialog.getAndSet(null)?.dismiss() }
            oneAtATime.release()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        waiter.getAndSet(null)?.countDown()
        activity.runOnUiThread { dialog.getAndSet(null)?.dismiss() }
    }

    private fun title(risk: AutomationToolRisk): String = when (risk) {
        AutomationToolRisk.USER_VISIBLE_CHANGE -> "Automation ändern?"
        AutomationToolRisk.EXECUTE_AGENT -> "Automation jetzt ausführen?"
        AutomationToolRisk.DESTRUCTIVE -> "Automation löschen?"
    }

    private fun message(request: AutomationToolApprovalRequest): String {
        val target = request.targetAutomationId?.value?.let { " „$it“" }.orEmpty()
        return when (request.operation) {
            "create" -> "Hans möchte eine neue Automation anlegen."
            "update" -> "Hans möchte Automation$target ändern."
            "enable" -> "Hans möchte Automation$target einschalten."
            "disable" -> "Hans möchte Automation$target ausschalten und offene Läufe stoppen."
            "delete" -> "Hans möchte Automation$target dauerhaft löschen."
            "run_now" -> "Hans möchte Automation$target jetzt einmal ausführen."
            "confirm" -> "Hans möchte genau diesen geplanten Lauf von Automation$target freigeben."
            else -> "Hans möchte eine bestätigungspflichtige Automationsaktion ausführen."
        }
    }

    private fun newNonce(): String {
        val bytes = ByteArray(18).also(secureRandom::nextBytes)
        return "approval:" + bytes.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 90L
        val RECEIPT_VALIDITY: Duration = Duration.ofMinutes(2)
    }
}

/**
 * Process-lifetime router. Confirmation mode requires a visible provider. Explicit full access
 * authorizes requested tool mutations with the same exact, short-lived receipt, without another
 * Hans dialog. This does not authorize creating unsolicited goals or bypass a run's requirements.
 * In particular, the separate `confirm` tool still needs an explicit exact-run confirmation.
 */
class SwappableAutomationToolApprovalProvider(
    private val actionPolicy: HansPhoneActionPolicy = HansPhoneActionPolicy.CONFIRM_ACTIONS,
    private val now: () -> Instant = Instant::now,
) : AutomationToolApprovalProvider {
    private val current = AtomicReference<AutomationToolApprovalProvider?>(null)

    override fun approve(request: AutomationToolApprovalRequest): AutomationToolApprovalReceipt? {
        if (actionPolicy == HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS &&
            request.operation in MUTATION_RISKS
        ) {
            if (request.risk != MUTATION_RISKS[request.operation] || request.callId.isBlank() ||
                !request.argumentsSha256.matches(Regex("[a-f0-9]{64}"))
            ) return null
            val approvedAt = now()
            return AutomationToolApprovalReceipt(
                callId = request.callId,
                operation = request.operation,
                argumentsSha256 = request.argumentsSha256,
                approvedAt = approvedAt,
                expiresAt = approvedAt.plus(Duration.ofMinutes(2)),
                nonce = "approval:${UUID.randomUUID()}",
            )
        }
        return current.get()?.approve(request)
    }

    fun attach(provider: AutomationToolApprovalProvider): Closeable {
        current.set(provider)
        return Closeable { current.compareAndSet(provider, null) }
    }

    fun clear() {
        current.set(null)
    }

    private companion object {
        val MUTATION_RISKS = mapOf(
            "create" to AutomationToolRisk.USER_VISIBLE_CHANGE,
            "update" to AutomationToolRisk.USER_VISIBLE_CHANGE,
            "enable" to AutomationToolRisk.USER_VISIBLE_CHANGE,
            "disable" to AutomationToolRisk.USER_VISIBLE_CHANGE,
            "delete" to AutomationToolRisk.DESTRUCTIVE,
            "run_now" to AutomationToolRisk.EXECUTE_AGENT,
        )
    }
}
