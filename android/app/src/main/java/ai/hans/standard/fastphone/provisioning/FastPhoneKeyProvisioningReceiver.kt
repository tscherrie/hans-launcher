package ai.hans.standard.fastphone.provisioning

import ai.hans.standard.settings.fastphone.FastPhoneCredentialStatus
import ai.hans.standard.settings.fastphone.FastPhoneUseSettingsStore
import ai.hans.standard.fastphone.DecisionsCall
import ai.hans.standard.fastphone.DecisionsChoice
import ai.hans.standard.fastphone.DecisionsQuestion
import ai.hans.standard.fastphone.OpenAiDecisionsClient
import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Explicit ordered, DUMP-authorized owner provisioning. Ordinary apps cannot invoke it. */
class FastPhoneKeyProvisioningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isOrderedBroadcast) return
        if (!validRegistration(context) || intent.component != ComponentName(context, javaClass)) {
            setResult(Activity.RESULT_CANCELED, FAILURE_PREFIX + "registration_invalid", null)
            return
        }
        val request = try {
            val expected = when (intent.action) {
                ACTION_BEGIN, ACTION_VERIFY -> setOf(EXTRA_PROTOCOL)
                ACTION_COMMIT -> setOf(EXTRA_PROTOCOL, EXTRA_CHALLENGE, EXTRA_WRAPPED_KEY, EXTRA_IV, EXTRA_CIPHERTEXT)
                else -> emptySet()
            }
            if (expected.isEmpty() || intent.extras?.keySet() != expected || intent.getIntExtra(EXTRA_PROTOCOL, 0) != 1) null
            else when (intent.action) {
                ACTION_BEGIN -> Request.Begin
                ACTION_VERIFY -> Request.Verify
                else -> {
                    val fields = listOf(EXTRA_CHALLENGE, EXTRA_WRAPPED_KEY, EXTRA_IV, EXTRA_CIPHERTEXT).map { name ->
                        intent.getStringExtra(name)?.takeIf { it.length in 1..FastPhoneKeyProvisioningProtocol.MAX_ENVELOPE_CHARACTERS }
                    }
                    if (fields.any { it == null } || fields.sumOf { it!!.length } > FastPhoneKeyProvisioningProtocol.MAX_ENVELOPE_CHARACTERS) null
                    else Request.Commit(ProvisioningEnvelope(fields[0]!!, fields[1]!!, fields[2]!!, fields[3]!!))
                }
            }
        } catch (_: Exception) { null }
        if (request == null) {
            protocol.clear()
            setResult(Activity.RESULT_CANCELED, FAILURE_PREFIX + "malformed", null)
            return
        }
        val pending = goAsync()
        val completed = AtomicBoolean(false)
        val activeCall = AtomicReference<DecisionsCall?>(null)
        val mutationAdmission = FastPhoneKeyProvisioningAdmission()
        fun finish(code: Int, value: String) {
            if (completed.compareAndSet(false, true)) {
                mutationAdmission.complete()
                pending.resultCode = code
                pending.resultData = value
                pending.finish()
            }
        }
        val timeout = Runnable {
            val code = mutationAdmission.timeoutCode()
            activeCall.get()?.cancel()
            protocol.clear()
            finish(Activity.RESULT_CANCELED, FAILURE_PREFIX + code)
        }
        handler.postDelayed(timeout, RECEIVER_TIMEOUT_MILLIS)
        try {
            executor.execute {
                try {
                    if (completed.get()) return@execute
                    when (request) {
                        Request.Begin -> {
                            val result = protocol.begin(unlocked(context))
                            if (completed.get()) { protocol.clear(); return@execute }
                            val json = JSONObject().put("protocol", 1).put("public_key", result.publicKey)
                                .put("challenge", result.challenge).put("ttl_ms", result.ttlMillis)
                            handler.postDelayed({ protocol.expire(result.challenge) }, result.ttlMillis)
                            finish(Activity.RESULT_OK, CHALLENGE_PREFIX + FastPhoneKeyProvisioningProtocol.encode(json.toString().toByteArray(StandardCharsets.UTF_8)))
                        }
                        is Request.Commit -> {
                            val key = protocol.consume(request.envelope, unlocked(context))
                            try {
                                if (completed.get() || !unlocked(context)) throw ProvisioningFailure("device_locked")
                                FastPhoneUseSettingsStore(context.applicationContext).use { store ->
                                    if (!mutationAdmission.admitSave()) throw ProvisioningFailure("timeout")
                                    if (!store.saveApiKey(key)) throw ProvisioningFailure("storage_failed")
                                    if (completed.get() || !unlocked(context)) throw ProvisioningFailure("saved_disabled")
                                    if (!mutationAdmission.admitEnable()) throw ProvisioningFailure("outcome_unknown")
                                    if (!store.setEnabled(true)) throw ProvisioningFailure("saved_disabled")
                                    val state = store.snapshot()
                                    if (!state.requestedEnabled || state.credentialStatus != FastPhoneCredentialStatus.AVAILABLE) {
                                        throw ProvisioningFailure("verification_failed")
                                    }
                                }
                                finish(Activity.RESULT_OK, RESULT_ENABLED)
                            } finally { key.fill('\u0000') }
                        }
                        Request.Verify -> {
                            if (!unlocked(context)) throw ProvisioningFailure("device_locked")
                            FastPhoneUseSettingsStore(context.applicationContext).use { store ->
                                val revision = store.revision()
                                val key = store.readApiKey() ?: throw ProvisioningFailure("key_unavailable")
                                val call = try {
                                    verificationClient.create(verificationQuestion(), key, VERIFY_TIMEOUT_MILLIS)
                                } finally { key.fill('\u0000') }
                                activeCall.set(call)
                                if (completed.get() || store.revision() != revision || !store.isEnabled()) {
                                    call.cancel()
                                    throw ProvisioningFailure("verification_cancelled")
                                }
                                val started = SystemClock.elapsedRealtime()
                                val outcome = call.execute()
                                activeCall.set(null)
                                if (completed.get()) return@execute
                                val code = fastPhoneVerificationCode(outcome, revision, store.revision(), store.isEnabled(), unlocked(context))
                                if (code == "verified") store.recordSuccess(revision) else store.recordFailure(code, revision)
                                val elapsed = (SystemClock.elapsedRealtime() - started).coerceIn(0, VERIFY_TIMEOUT_MILLIS + 1_000)
                                val json = JSONObject().put("protocol", 1).put("code", code).put("elapsed_ms", elapsed)
                                finish(Activity.RESULT_OK, VERIFY_PREFIX + FastPhoneKeyProvisioningProtocol.encode(json.toString().toByteArray(StandardCharsets.UTF_8)))
                            }
                        }
                    }
                } catch (failure: ProvisioningFailure) {
                    finish(Activity.RESULT_CANCELED, FAILURE_PREFIX + failure.code)
                } catch (_: Exception) {
                    protocol.clear()
                    finish(Activity.RESULT_CANCELED, FAILURE_PREFIX + "failed")
                } finally {
                    handler.removeCallbacks(timeout)
                }
            }
        } catch (_: Exception) {
            handler.removeCallbacks(timeout)
            protocol.clear()
            finish(Activity.RESULT_CANCELED, FAILURE_PREFIX + "busy")
        }
    }

    private fun unlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == true &&
            context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false

    @Suppress("DEPRECATION")
    private fun validRegistration(context: Context): Boolean = runCatching {
        val info = context.packageManager.getReceiverInfo(ComponentName(context, javaClass), 0)
        info.exported && info.permission == Manifest.permission.DUMP && info.processName == context.packageName
    }.getOrDefault(false)

    private sealed interface Request {
        data object Begin : Request
        data object Verify : Request
        data class Commit(val envelope: ProvisioningEnvelope) : Request
    }

    companion object {
        const val ACTION_BEGIN = "ai.hans.standard.fastphone.PROVISION_BEGIN"
        const val ACTION_COMMIT = "ai.hans.standard.fastphone.PROVISION_COMMIT"
        const val ACTION_VERIFY = "ai.hans.standard.fastphone.PROVISION_VERIFY"
        const val EXTRA_PROTOCOL = "protocol_version"
        const val EXTRA_CHALLENGE = "challenge"
        const val EXTRA_WRAPPED_KEY = "wrapped_key"
        const val EXTRA_IV = "iv"
        const val EXTRA_CIPHERTEXT = "ciphertext"
        const val CHALLENGE_PREFIX = "hfp1:"
        const val VERIFY_PREFIX = "hfpv1:"
        const val FAILURE_PREFIX = "fast_phone_key_provisioning_"
        const val RESULT_ENABLED = "fast_phone_key_provisioned_enabled"
        private const val RECEIVER_TIMEOUT_MILLIS = 8_000L
        private const val VERIFY_TIMEOUT_MILLIS = 6_000L
        private val verificationClient = OpenAiDecisionsClient()
        internal fun verificationQuestion() = DecisionsQuestion(
            "Synthetic connectivity test only. The synthetic screen contains the text READY. " +
                "The bounded goal is to identify READY. No phone UI or private content is supplied, and no phone action is allowed.",
            listOf(DecisionsChoice("ready", "The synthetic screen explicitly contains READY."),
                DecisionsChoice("escalate", "The supplied synthetic evidence is missing or ambiguous.")),
        )
        private val handler = Handler(Looper.getMainLooper())
        private val protocol = FastPhoneKeyProvisioningProtocol(SystemClock::elapsedRealtime)
        private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), { runnable ->
            Thread(runnable, "hans-key-provisioning").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())
    }
}
