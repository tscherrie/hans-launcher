package ai.hans.standard.fastphone.provisioning

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No OpenAI calls, microphone use, private UI input, or credential writes. */
@RunWith(AndroidJUnit4::class)
class FastPhoneKeyProvisioningAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun manifestRequiresDumpExplicitComponentAndMainProcess() {
        val component = ComponentName(context, FastPhoneKeyProvisioningReceiver::class.java)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getReceiverInfo(component, 0)
        assertTrue(info.exported)
        assertEquals(Manifest.permission.DUMP, info.permission)
        assertEquals(context.packageName, info.processName)
        for (action in listOf(FastPhoneKeyProvisioningReceiver.ACTION_BEGIN,
            FastPhoneKeyProvisioningReceiver.ACTION_COMMIT, FastPhoneKeyProvisioningReceiver.ACTION_VERIFY)) {
            @Suppress("DEPRECATION")
            val matches = context.packageManager.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            assertFalse(matches.any { it.activityInfo.name == component.className })
        }
    }

    @Test fun malformedAuthorizedShellCallReturnsConstantAndNeverImports() {
        val output = shell("am broadcast --user 0 -n ${context.packageName}/.fastphone.provisioning.FastPhoneKeyProvisioningReceiver " +
            "-a ${FastPhoneKeyProvisioningReceiver.ACTION_COMMIT} --ei protocol_version 1 --es ciphertext malformed")
        assertTrue(output, output.contains("Broadcast completed: result=${Activity.RESULT_CANCELED}, data=\"fast_phone_key_provisioning_malformed\""))
    }

    @Test fun ordinaryForeignApplicationUidCannotBeginProvisioning() {
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(Manifest.permission.DUMP, instrumentation.context.packageName))
        val token = "key-probe-${System.nanoTime()}"
        shell("am start --user 0 -W -n ${instrumentation.context.packageName}/${OrdinaryUidFastPhoneProvisioningProbeActivity::class.java.name} --es probeToken $token")
        var found = false
        repeat(40) {
            if (!found) {
                val log = shell("logcat -d -s ${OrdinaryUidFastPhoneProvisioningProbeActivity.LOG_TAG}:I *:S")
                if (log.contains("$token:DENIED_")) found = true else SystemClock.sleep(100)
            }
        }
        assertTrue("Ordinary-UID probe must be denied by Android", found)
    }

    @Test fun actualAndroidCryptoProviderSupportsExplicitOaep256Mgf256AndGcm() {
        val protocol = FastPhoneKeyProvisioningProtocol({ 1_000L })
        val challenge = protocol.begin(true)
        val decodedChallenge = Base64.getUrlDecoder().decode(challenge.challenge)
        val public = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(challenge.publicKey)))
        val aes = ByteArray(32) { (it + 1).toByte() }
        val iv = ByteArray(12) { (it + 20).toByte() }
        val key = "synthetic-key-never-valid-at-openai".toByteArray()
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
            updateAAD(FastPhoneKeyProvisioningProtocol.aad(decodedChallenge))
        }
        val wrap = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.ENCRYPT_MODE, public, FastPhoneKeyProvisioningProtocol.oaepParameters())
        }
        val envelope = ProvisioningEnvelope(challenge.challenge, FastPhoneKeyProvisioningProtocol.encode(wrap.doFinal(aes)),
            FastPhoneKeyProvisioningProtocol.encode(iv), FastPhoneKeyProvisioningProtocol.encode(encrypt.doFinal(key)))
        val decoded = protocol.consume(envelope, true)
        try { assertArrayEquals(String(key).toCharArray(), decoded) } finally { decoded.fill('\u0000'); aes.fill(0); key.fill(0) }
        try { protocol.consume(envelope, true); throw AssertionError("Replay admitted") }
        catch (failure: ProvisioningFailure) { assertEquals("no_pending_challenge", failure.code) }
    }

    @Test fun verificationQuestionIsFixedSyntheticAndHasNoPhoneExecutionArguments() {
        val question = FastPhoneKeyProvisioningReceiver.verificationQuestion()
        assertTrue(question.evidence.startsWith("Synthetic connectivity test only."))
        assertEquals(listOf("ready", "escalate"), question.choices.map { it.id })
        assertFalse(question.evidence.contains("OPENAI_API_KEY"))
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
