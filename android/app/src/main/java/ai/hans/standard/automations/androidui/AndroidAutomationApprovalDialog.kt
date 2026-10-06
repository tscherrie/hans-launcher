package ai.hans.standard.automations.androidui

import ai.hans.standard.R
import ai.hans.standard.localization.AndroidHansTextResolver

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
                        .setPositiveButton(AndroidHansTextResolver(activity).text(R.string.integration_allow_once_90153ce)) { _, _ ->
                            accepted.set(true)
                            latch.countDown()
                        }
                        .setNegativeButton(AndroidHansTextResolver(activity).text(R.string.integration_decline_7be75ce)) { _, _ -> latch.countDown() }
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
        AutomationToolRisk.USER_VISIBLE_CHANGE -> AndroidHansTextResolver(activity).text(R.string.integration_change_automation_8ee9be3)
        AutomationToolRisk.EXECUTE_AGENT -> AndroidHansTextResolver(activity).text(R.string.integration_run_automation_now_bb938a5)
        AutomationToolRisk.DESTRUCTIVE -> AndroidHansTextResolver(activity).text(R.string.integration_delete_automation_fa45b91)
    }

    private fun message(request: AutomationToolApprovalRequest): String {
        val target = request.targetAutomationId?.value?.let { " „$it“" }.orEmpty()
        return when (request.operation) {
            "create" -> AndroidHansTextResolver(activity).text(R.string.integration_hans_would_like_to_create_a_new_automation_e576176)
            "update" -> AndroidHansTextResolver(activity).text(R.string.integration_hans_would_like_to_change_automation_1_e572f99, target)
            "enable" -> AndroidHansTextResolver(activity).text(R.string.integration_hans_would_like_to_enable_automation_1_08c6b0a, target)
            "disable" -> AndroidHansTextResolver(activity).text(R.string.integration_hans_would_like_to_disable_automation_1_and_stop_pendin_2c2a0d9, target)
            "delete" -> AndroidHansTextResolver(activity).text(R.string.integration_hans_would_like_to_permanently_delete_automation_1_2cfd2a7, target)
            "run_now" -> AndroidHansTextResolver(activity).text(R.string.integration_hans_would_like_to_run_automation_1_once_now_150720a, target)
            "confirm" -> AndroidHansTextResolver(activity).text(R.string.integration_hans_would_like_to_approve_this_exact_scheduled_run_of__c1059fe, target)
            else -> AndroidHansTextResolver(activity).text(R.string.integration_hans_would_like_to_perform_an_automation_action_that_re_9aebff3)
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
